package com.itemgraph.command;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.query.QueryFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Runs a historical ItemGraph query off the server thread and delivers its output back
 * on the server thread.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code /ig event}, {@code /ig explain} and {@code /ig trace item} are multi-join
 * reads over the observation and inferred-edge tables. The engineering rules forbid
 * running a database scan on the server thread, so the SQL must happen somewhere else —
 * but {@link CommandSourceStack#sendSuccess} ultimately touches the player list and
 * entity state, which the same rules forbid touching from an arbitrary worker thread.
 * Both halves therefore have to be true at once, and this class is the seam:
 *
 * <pre>
 *   server thread  ->  query executor        ->  server thread
 *   (parse args)       (JDBC + formatting)       (sendSuccess / sendFailure)
 * </pre>
 *
 * <h2>Marshalling back</h2>
 *
 * <p>The hand-back uses {@code source.getServer().execute(Runnable)}.
 * {@link MinecraftServer} extends {@code ReentrantBlockableEventLoop}, so
 * {@code execute} appends the task to the server's pending-task queue and the next tick
 * drains it on the server thread. This is the same mechanism NeoForge's own
 * {@code enqueueWork} uses for parallel-dispatch events, and matches how existing mods
 * report deferred command results — see the class-level note in
 * {@code docs/ARCHITECTURE.md} for the prior art this was checked against.
 *
 * <p>One caveat worth naming: if the server is already stopping,
 * {@code MinecraftServer.scheduleExecutables()} returns false and {@code execute} runs
 * the task inline on the calling thread instead of queueing it. {@link #canStillReport}
 * is checked first precisely so nothing is sent in that case.
 *
 * <h2>Its own executor, deliberately</h2>
 *
 * <p>This does <b>not</b> reuse the ingestion worker. That worker runs a 60-second
 * ingest-then-correlate cycle; queueing an admin's lookup behind it would mean an
 * incident query that answers a minute later, or not until the current cycle finishes.
 * A separate single-threaded executor keeps command latency independent of the
 * background pipeline. Its bounded queue serializes admin queries without opening an
 * unbounded number of SQLite readers or accumulating unbounded pending work.
 */
public final class QueryDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryDispatcher.class);

    private static final int MAX_QUEUED_QUERIES = 64;

    /** Created on first use and reused; the single worker accepts a bounded queue. */
    private static volatile ExecutorService executor;

    private QueryDispatcher() {}

    /**
     * A read-only query against the ItemGraph database, producing the exact lines to
     * show. Runs on the query executor, never on the server thread.
     *
     * <p>Returning {@link QueryOutput} rather than a domain object keeps every Minecraft
     * type out of the {@code com.itemgraph.query} package: formatting happens off-thread
     * too, and the server thread is left with nothing but the send calls.
     */
    @FunctionalInterface
    public interface Query {
        QueryOutput run(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    interface DataQuery<T> {
        T run(Connection conn) throws SQLException;
    }

    @FunctionalInterface
    private interface ConnectionQuery<T> {
        T run(Connection conn) throws SQLException;
    }

    /**
     * The rendered result of a query.
     *
     * @param found true if the thing asked about exists; false renders as a command
     *              failure, because "no observation #42" is a negative answer, not output
     * @param lines the text to send, already formatted by {@link QueryFormatter}
     */
    public record QueryOutput(boolean found, List<String> lines) {

        public QueryOutput {
            lines = List.copyOf(lines);
        }

        public static QueryOutput found(List<String> lines) {
            return new QueryOutput(true, lines);
        }

        /** A well-formed negative result: the id was valid, nothing matched it. */
        public static QueryOutput notFound(String line) {
            return new QueryOutput(false, List.of(line));
        }
    }

    /**
     * Dispatches {@code query} onto the query executor and reports its output back to
     * {@code source} on the server thread.
     *
     * <p>Returns immediately with Brigadier's success code. The return value means "the
     * query was accepted", not "the query found something" — the real answer cannot be
     * known synchronously without doing on-thread SQL, which is the thing being avoided.
     * The distinction is visible to the admin in the output itself.
     *
     * @param label short name of the command, used only in log and error messages
     */
    public static int dispatch(CommandSourceStack source, String label, Query query) {
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            source.sendFailure(Component.literal(QueryFormatter.queryFailed(
                    "the ItemGraph database is not connected"
                            + (db.getLastError() != null ? " (" + db.getLastError() + ")" : "")
                            + ". See /ig status.")));
            return 0;
        }

        MinecraftServer server = source.getServer();
        Thread serverThread = server == null ? null : server.getRunningThread();
        QueryCancellation cancellation = new QueryCancellation();
        CompletableFuture<QueryOutput> future;
        try {
            future = CompletableFuture.supplyAsync(() -> execute(query, cancellation), queryExecutor());
        } catch (RejectedExecutionException e) {
            return rejectQueue(source);
        }

        if (server != null && Thread.currentThread() == serverThread && source.getEntity() == null) {
            source.sendSuccess(() -> Component.literal(
                    "[ItemGraph] Query accepted; completed results will be written to the server log."), false);
            future.whenComplete((output, throwable) -> deliver(source, server, serverThread, label, output, throwable));
            return 1;
        }

        // Only an entity-less off-server-thread source can block briefly for its response buffer.
        // Player sources always use server-thread delivery, even if an unexpected caller invokes dispatch off-thread.
        if (server != null && Thread.currentThread() != serverThread && source.getEntity() == null) {
            try {
                QueryOutput output = future.get(5, TimeUnit.SECONDS);
                if (output.found()) {
                    output.lines().forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
                } else {
                    output.lines().forEach(line -> source.sendFailure(Component.literal(line)));
                }
                return output.found() ? 1 : 0;
            } catch (InterruptedException e) {
                cancellation.cancel();
                future.cancel(true);
                Thread.currentThread().interrupt();
                LOGGER.warn("ItemGraph query '{}' was interrupted on synchronous worker", label);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed("the query was interrupted")));
                return 0;
            } catch (TimeoutException e) {
                cancellation.cancel();
                future.cancel(true);
                LOGGER.warn("ItemGraph query '{}' timed out on synchronous worker", label);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed("the query timed out")));
                return 0;
            } catch (Exception e) {
                Throwable cause = unwrap(e);
                LOGGER.error("ItemGraph query '{}' failed on synchronous worker", label, cause);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(String.valueOf(cause.getMessage()))));
                return 0;
            }
        }

        future.whenComplete((output, throwable) -> deliver(source, server, serverThread, label, output, throwable));

        return 1;
    }

    static <T> int dispatchData(CommandSourceStack source, String label, DataQuery<T> query,
                                BiConsumer<CommandSourceStack, T> consumer) {
        return dispatchData(source, label, query, consumer, () -> {});
    }

    static <T> int dispatchData(CommandSourceStack source, String label, DataQuery<T> query,
                                BiConsumer<CommandSourceStack, T> consumer, Runnable failureConsumer) {
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            source.sendFailure(Component.literal(QueryFormatter.queryFailed(
                    "the ItemGraph database is not connected"
                            + (db.getLastError() != null ? " (" + db.getLastError() + ")" : "")
                            + ". See /ig status.")));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal(QueryFormatter.queryFailed("the server is not available")));
            return 0;
        }
        Thread serverThread = server.getRunningThread();
        QueryCancellation cancellation = new QueryCancellation();
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(() -> executeData(query, cancellation), queryExecutor());
        } catch (RejectedExecutionException e) {
            return rejectQueue(source);
        }
        future.whenComplete((result, throwable) -> server.execute(() -> {
            if (Thread.currentThread() != serverThread) {
                failureConsumer.run();
                return;
            }
            if (!canStillReport(source, server)) {
                failureConsumer.run();
                return;
            }
            if (!source.hasPermission(2)) {
                source.sendFailure(Component.literal("[ItemGraph] Permission level 2 is required to view this flow."));
                failureConsumer.run();
                return;
            }
            if (throwable != null) {
                Throwable cause = throwable;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof QueryFailure && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                LOGGER.error("ItemGraph data query '{}' failed", label, cause);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(String.valueOf(cause.getMessage()))));
                failureConsumer.run();
                return;
            }
            try {
                consumer.accept(source, result);
            } catch (RuntimeException e) {
                LOGGER.error("ItemGraph data query '{}' delivery failed", label, e);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(String.valueOf(e.getMessage()))));
                failureConsumer.run();
            }
        }));
        return 1;
    }

    /**
     * Runs the query on its own read-only connection and closes it again.
     *
     * <p>A fresh connection per query rather than the shared writer connection — see
     * {@link DatabaseManager#openReadOnlyConnection()} for why reading through the
     * ingestion worker's connection could show an admin uncommitted rows.
     */
    private static QueryOutput execute(Query query, QueryCancellation cancellation) {
        return executeReadOnly(query::run, cancellation);
    }

    private static int rejectQueue(CommandSourceStack source) {
        source.sendFailure(Component.literal("[ItemGraph] Query worker queue is full or shutting down; retry shortly."));
        return 0;
    }

    private static <T> T executeData(DataQuery<T> query, QueryCancellation cancellation) {
        return executeReadOnly(query::run, cancellation);
    }

    private static <T> T executeReadOnly(ConnectionQuery<T> query, QueryCancellation cancellation) {
        try (Connection conn = DatabaseManager.getInstance().openReadOnlyConnection()) {
            if (!cancellation.attach(conn)) {
                throw new SQLException("query cancelled before execution began");
            }
            boolean progressHandlerSet = false;
            try {
                ProgressHandler.setHandler(conn, 10_000, new ProgressHandler() {
                    @Override
                    protected int progress() {
                        return cancellation.isCancelled() ? 1 : 0;
                    }
                });
                progressHandlerSet = true;
                if (cancellation.isCancelled()) {
                    throw new SQLException("query cancelled before execution began");
                }
                return query.run(conn);
            } finally {
                try {
                    if (progressHandlerSet) {
                        ProgressHandler.clearHandler(conn);
                    }
                } finally {
                    cancellation.detach(conn);
                }
            }
        } catch (SQLException e) {
            throw new QueryFailure(e);
        }
    }

    private static void deliver(CommandSourceStack source, MinecraftServer server, Thread serverThread,
                                String label, QueryOutput output, Throwable throwable) {
        if (server == null) {
            LOGGER.warn("Dropping /ig {} result: the command source has no server.", label);
            return;
        }

        // The hop back onto the server thread. Everything below this line runs on it.
        server.execute(() -> {
            if (Thread.currentThread() != serverThread || !canStillReport(source, server)
                    || !source.hasPermission(2)) {
                return;
            }
            boolean entityless = source.getEntity() == null;

            if (throwable != null) {
                // CompletableFuture wrappers stringify their causes; unwrap consistently so the
                // admin sees the underlying SQL message rather than an implementation class name.
                Throwable cause = unwrap(throwable);
                LOGGER.error("ItemGraph query '{}' failed", label, cause);
                if (!entityless) {
                    source.sendFailure(Component.literal(QueryFormatter.queryFailed(String.valueOf(cause.getMessage()))));
                }
                return;
            }

            if (!output.found()) {
                if (entityless) {
                    output.lines().forEach(LOGGER::info);
                } else {
                    output.lines().forEach(line -> source.sendFailure(Component.literal(line)));
                }
                return;
            }

            if (entityless) {
                output.lines().forEach(LOGGER::info);
                return;
            }
            // false: query output is for the admin who asked, not broadcast to every op.
            // The graph is sensitive (see docs/SECURITY_AND_PERMISSIONS.md) and an item
            // trace names coordinates and players.
            output.lines().forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        });
    }

    /**
     * Whether it is still meaningful and safe to send feedback to this source.
     *
     * <p>A query can outlive the player who asked for it. Both checks read plain fields
     * and are evaluated on the server thread anyway, so this is about not addressing a
     * departed source rather than about thread safety.
     */
    static boolean canStillReport(CommandSourceStack source, MinecraftServer server) {
        if (server.isStopped()) {
            return false;
        }
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayer player) {
            return !player.hasDisconnected();
        }
        return true;
    }

    private static ExecutorService queryExecutor() {
        ExecutorService current = executor;
        if (current != null) {
            return current;
        }
        synchronized (QueryDispatcher.class) {
            if (executor == null) {
                executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(MAX_QUEUED_QUERIES), r -> {
                            Thread t = new Thread(r, "ItemGraph-Query-Worker");
                            t.setDaemon(true);
                            return t;
                        }, new ThreadPoolExecutor.AbortPolicy());
            }
            return executor;
        }
    }

    /** Stops the query executor. Called from {@code ServerStoppingEvent}. */
    public static void shutdown() {
        ExecutorService current;
        synchronized (QueryDispatcher.class) {
            current = executor;
            executor = null;
        }
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Carries a {@link SQLException} out of the supplier lambda without losing it. */
    private static final class QueryFailure extends RuntimeException {
        QueryFailure(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof QueryFailure) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final class QueryCancellation {
        private SQLiteConnection activeConnection;
        private volatile boolean cancelled;

        boolean isCancelled() {
            return cancelled;
        }

        synchronized boolean attach(Connection connection) throws SQLException {
            if (!(connection instanceof SQLiteConnection sqliteConnection)) {
                throw new SQLException("The read-only query connection is not SQLite-backed");
            }
            if (cancelled) {
                return false;
            }
            activeConnection = sqliteConnection;
            return true;
        }

        synchronized void detach(Connection connection) {
            if (activeConnection == connection) {
                activeConnection = null;
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (activeConnection != null) {
                try {
                    activeConnection.getDatabase().interrupt();
                } catch (SQLException | RuntimeException e) {
                    LOGGER.warn("Unable to interrupt a timed-out ItemGraph SQLite query", e);
                }
            }
        }
    }
}
