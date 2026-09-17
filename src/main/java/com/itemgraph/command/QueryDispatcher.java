package com.itemgraph.command;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.query.QueryFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
 * background pipeline. It is single-threaded (not a pool) so that concurrent admin
 * queries serialise rather than opening an unbounded number of SQLite readers.
 */
public final class QueryDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryDispatcher.class);

    /**
     * Created on first use and reused; queries are rare and short, so there is no reason
     * to hold a thread for a server that never runs one.
     */
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
        CompletableFuture<QueryOutput> future = CompletableFuture
                .supplyAsync(() -> execute(query), queryExecutor());

        // If the query is dispatched from an off-server-thread context (such as an RCON client worker),
        // we can safely block the off-thread caller up to a short timeout so synchronous command
        // collectors (like Minecraft's RCON buffer) receive the output before closing the connection.
        // If called on the Minecraft server thread, we NEVER block: we marshal back via server.execute().
        if (server != null && Thread.currentThread() != server.getRunningThread()) {
            try {
                QueryOutput output = future.get(5, TimeUnit.SECONDS);
                if (output.found()) {
                    output.lines().forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
                } else {
                    output.lines().forEach(line -> source.sendFailure(Component.literal(line)));
                }
                return output.found() ? 1 : 0;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                LOGGER.error("ItemGraph query '{}' failed on synchronous worker", label, cause);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(String.valueOf(cause.getMessage()))));
                return 0;
            }
        }

        future.whenComplete((output, throwable) -> deliver(source, label, output, throwable));

        return 1;
    }

    /**
     * Runs the query on its own read-only connection and closes it again.
     *
     * <p>A fresh connection per query rather than the shared writer connection — see
     * {@link DatabaseManager#openReadOnlyConnection()} for why reading through the
     * ingestion worker's connection could show an admin uncommitted rows.
     */
    private static QueryOutput execute(Query query) {
        try (Connection conn = DatabaseManager.getInstance().openReadOnlyConnection()) {
            return query.run(conn);
        } catch (SQLException e) {
            // Rethrown so whenComplete reports it; wrapped because the lambda is a Supplier.
            throw new QueryFailure(e);
        }
    }

    private static void deliver(CommandSourceStack source, String label, QueryOutput output, Throwable throwable) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            LOGGER.warn("Dropping /ig {} result: the command source has no server.", label);
            return;
        }

        // The hop back onto the server thread. Everything below this line runs on it.
        server.execute(() -> {
            if (!canStillReport(source, server)) {
                return;
            }

            if (throwable != null) {
                // CompletableFuture#supplyAsync wraps any exception thrown by the supplier in a
                // CompletionException, whose getMessage() returns the wrapped exception's toString()
                // (class name and all) rather than its plain message - unwrap that first, or the
                // admin sees "com.itemgraph.command.QueryDispatcher$QueryFailure: <message>" instead
                // of the clean message. Then peel QueryFailure/SQLException the same way as before.
                Throwable cause = throwable;
                while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof QueryFailure && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause.getCause() instanceof SQLException sql) {
                    cause = sql;
                }
                LOGGER.error("ItemGraph query '{}' failed", label, cause);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(String.valueOf(cause.getMessage()))));
                return;
            }

            if (!output.found()) {
                output.lines().forEach(line -> {
                    source.sendFailure(Component.literal(line));
                    if (source.getEntity() == null) {
                        LOGGER.info("{}", line);
                    }
                });
                return;
            }

            // false: query output is for the admin who asked, not broadcast to every op.
            // The graph is sensitive (see docs/SECURITY_AND_PERMISSIONS.md) and an item
            // trace names coordinates and players.
            output.lines().forEach(line -> {
                source.sendSuccess(() -> Component.literal(line), false);
                if (source.getEntity() == null) {
                    LOGGER.info("{}", line);
                }
            });
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
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "ItemGraph-Query-Worker");
                    t.setDaemon(true);
                    return t;
                });
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
}
