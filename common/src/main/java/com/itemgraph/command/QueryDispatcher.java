package com.itemgraph.command;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.query.QueryFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.sqlite.ProgressHandler;
import org.sqlite.SQLiteConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.CallableStatement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
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
 * unbounded number of JDBC readers or accumulating unbounded pending work. Each statement
 * receives the five-second timeout and is registered for cancellation; SQLite additionally
 * uses its progress handler and cross-thread interrupt support.
 */
public final class QueryDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryDispatcher.class);

    private static final int MAX_QUEUED_QUERIES = 64;
    private static final long PLAYER_QUERY_TIMEOUT_MS = 5_000L;

    /** Created on first use and reused; the single worker accepts a bounded queue. */
    private static volatile ExecutorService executor;
    private static volatile ScheduledExecutorService timeoutExecutor;
    private static final java.util.concurrent.ConcurrentHashMap<QueryCancellation, CompletableFuture<?>>
            CANCELLABLE_DATA_TASKS = new java.util.concurrent.ConcurrentHashMap<>();

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
    interface CancellableDataQuery<T> {
        T run(Connection conn, java.util.function.BooleanSupplier cancelled,
              java.util.function.BooleanSupplier commit) throws SQLException;
    }

    @FunctionalInterface
    interface CancellableTask<T> {
        T run(java.util.function.BooleanSupplier cancelled) throws Exception;
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
     * @param actions bounded player-scoped chat controls sent after the text
     */
    public record QueryAction(String label, String command) {
        public QueryAction {
            if (label == null || label.isBlank() || command == null || command.isBlank()) {
                throw new IllegalArgumentException("query action label and command are required");
            }
        }
    }

    public record QueryOutput(boolean found, List<String> lines, List<QueryAction> actions) {

        public QueryOutput(boolean found, List<String> lines) {
            this(found, lines, List.of());
        }

        public QueryOutput {
            lines = List.copyOf(lines);
            actions = List.copyOf(actions);
        }

        public static QueryOutput found(List<String> lines) {
            return new QueryOutput(true, lines);
        }

        public static QueryOutput found(List<String> lines, List<QueryAction> actions) {
            return new QueryOutput(true, lines, actions);
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
            String unavailable = "status".equals(label)
                    ? "the ItemGraph database is not connected; inspect the server log for connection details"
                    : "the ItemGraph database is not connected"
                            + (db.getLastError() != null ? " (" + db.getLastError() + ")" : "")
                            + ". See /ig status.";
            source.sendFailure(Component.literal(QueryFormatter.queryFailed(unavailable)));
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
        ScheduledFuture<?> playerTimeout = source.getEntity() instanceof ServerPlayer
                ? schedulePlayerTimeout(cancellation) : null;

        if (server != null && Thread.currentThread() == serverThread && source.getEntity() == null) {
            source.sendSuccess(() -> Component.literal(
                    "[ItemGraph] Query accepted; completed results will be written to the server log."), false);
            future.whenComplete((output, throwable) -> {
                cancelTimeout(playerTimeout);
                deliver(source, server, serverThread, label, output, throwable);
            });
            return 1;
        }

        // Only an entity-less off-server-thread source can block briefly for its response buffer.
        // Player sources always use server-thread delivery, even if an unexpected caller invokes dispatch off-thread.
        if (server != null && Thread.currentThread() != serverThread && source.getEntity() == null) {
            try {
                QueryOutput output = future.get(5, TimeUnit.SECONDS);
                if (output.found()) {
                    output.lines().forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
                    sendActions(source, output.actions());
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
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(callerFailureMessage(label, cause))));
                return 0;
            }
        }

        future.whenComplete((output, throwable) -> {
            cancelTimeout(playerTimeout);
            deliver(source, server, serverThread, label, output, throwable);
        });

        return 1;
    }

    /**
     * Internal API bridge: submits a read-only query to the same bounded worker without
     * exposing the JDBC {@link Connection} outside {@code com.itemgraph.command}.
     */
    static <T> CompletableFuture<T> submitData(DataQuery<T> query) {
        QueryCancellation cancellation = new QueryCancellation();
        ScheduledFuture<?> timeout = schedulePlayerTimeout(cancellation);
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> executeData(query, cancellation), queryExecutor());
        } catch (RejectedExecutionException rejected) {
            cancelTimeout(timeout);
            throw rejected;
        }
        future.whenComplete((result, failure) -> cancelTimeout(timeout));
        return future;
    }

    /** Submits bounded database work and returns a handle that cancels active JDBC statements. */
    static <T> CancellableDataHandle<T> submitCancellableData(CancellableDataQuery<T> query) {
        QueryCancellation cancellation = new QueryCancellation();
        ScheduledFuture<?> timeout = schedulePlayerTimeout(cancellation);
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> executeReadOnly(conn -> query.run(conn, cancellation::isCancelled,
                            cancellation::finish), cancellation), queryExecutor());
        } catch (RejectedExecutionException rejected) {
            cancelTimeout(timeout);
            throw rejected;
        }
        CANCELLABLE_DATA_TASKS.put(cancellation, future);
        future.whenComplete((result, failure) -> {
            cancelTimeout(timeout);
            CANCELLABLE_DATA_TASKS.remove(cancellation);
        });
        return new CancellableDataHandle<>(future, cancellation);
    }

    /** Submits bounded worker work that does not require an ItemGraph database connection. */
    static <T> CancellableDataHandle<T> submitCancellableTask(CancellableTask<T> task) {
        QueryCancellation cancellation = new QueryCancellation();
        ScheduledFuture<?> timeout = schedulePlayerTimeout(cancellation);
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                if (cancellation.isCancelled()) {
                    throw new java.util.concurrent.CompletionException(
                            new java.util.concurrent.CancellationException("query task cancelled"));
                }
                try {
                    T result = task.run(cancellation::isCancelled);
                    if (!cancellation.finish()) {
                        throw new java.util.concurrent.CancellationException("query task cancelled");
                    }
                    return result;
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, queryExecutor());
        } catch (RejectedExecutionException rejected) {
            cancelTimeout(timeout);
            throw rejected;
        }
        CANCELLABLE_DATA_TASKS.put(cancellation, future);
        future.whenComplete((result, failure) -> {
            cancelTimeout(timeout);
            CANCELLABLE_DATA_TASKS.remove(cancellation);
        });
        return new CancellableDataHandle<>(future, cancellation);
    }

    static final class CancellableDataHandle<T> {
        private final CompletableFuture<T> future;
        private final QueryCancellation cancellation;

        private CancellableDataHandle(CompletableFuture<T> future, QueryCancellation cancellation) {
            this.future = future;
            this.cancellation = cancellation;
        }

        CompletableFuture<T> future() {
            return future;
        }

        boolean cancel() {
            if (future.isDone()) {
                return false;
            }
            return cancellation.cancel();
        }
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
        long started = System.nanoTime();
        boolean succeeded = false;
        try {
            T result;
            try (Connection conn = DatabaseManager.getInstance().openReadOnlyConnection()) {
                SQLiteConnection sqliteConnection = cancellation.attach(conn);
                if (cancellation.isCancelled()) {
                    throw new SQLException("query cancelled before execution began");
                }
                boolean progressHandlerSet = false;
                try {
                    if (sqliteConnection != null) {
                        ProgressHandler.setHandler(sqliteConnection, 10_000, new ProgressHandler() {
                            @Override
                            protected int progress() {
                                return cancellation.isCancelled() ? 1 : 0;
                            }
                        });
                        progressHandlerSet = true;
                    }
                    if (cancellation.isCancelled()) {
                        throw new SQLException("query cancelled before execution began");
                    }
                    result = query.run(cancellation.instrument(conn));
                    if (!cancellation.finish()) {
                        throw new SQLException("query cancelled before completion");
                    }
                } finally {
                    try {
                        if (progressHandlerSet) {
                            ProgressHandler.clearHandler(conn);
                        }
                    } finally {
                        cancellation.detach(conn);
                    }
                }
            }
            succeeded = true;
            return result;
        } catch (SQLException e) {
            throw new QueryFailure(e);
        } finally {
            OperationalMetrics.getInstance().recordQuery(System.nanoTime() - started, succeeded);
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
                    source.sendFailure(Component.literal(QueryFormatter.queryFailed(callerFailureMessage(label, cause))));
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
            sendActions(source, output.actions());
        });
    }

    static String callerFailureMessage(String label, Throwable cause) {
        if ("status".equals(label)) {
            return "status query failed; inspect the server log for connection details";
        }
        return String.valueOf(cause.getMessage());
    }

    /** Sends bounded interactive controls after the textual query output. */
    static void sendActions(CommandSourceStack source, List<QueryAction> actions) {
        if (actions.isEmpty()) {
            return;
        }
        MutableComponent controls = Component.literal("[ItemGraph] ");
        for (int i = 0; i < actions.size(); i++) {
            QueryAction action = actions.get(i);
            if (i > 0) {
                controls.append(" ");
            }
            controls.append(Component.literal("[" + action.label() + "]").withStyle(style ->
                    style.withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, action.command()))));
        }
        source.sendSuccess(() -> controls, false);
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

    private static ScheduledFuture<?> schedulePlayerTimeout(QueryCancellation cancellation) {
        return queryTimeoutExecutor().schedule(cancellation::cancel,
                PLAYER_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static void cancelTimeout(ScheduledFuture<?> timeout) {
        if (timeout != null) {
            timeout.cancel(false);
        }
    }

    private static ScheduledExecutorService queryTimeoutExecutor() {
        ScheduledExecutorService current = timeoutExecutor;
        if (current != null) {
            return current;
        }
        synchronized (QueryDispatcher.class) {
            if (timeoutExecutor == null) {
                timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "ItemGraph-Query-Timeout");
                    t.setDaemon(true);
                    return t;
                });
            }
            return timeoutExecutor;
        }
    }

    /** Stops the query executor. Called from {@code ServerStoppingEvent}. */
    public static void shutdown() {
        CANCELLABLE_DATA_TASKS.forEach((cancellation, future) -> {
            if (cancellation.cancel()) {
                future.cancel(true);
            }
        });
        CANCELLABLE_DATA_TASKS.clear();
        ExecutorService current;
        ScheduledExecutorService timers;
        synchronized (QueryDispatcher.class) {
            current = executor;
            executor = null;
            timers = timeoutExecutor;
            timeoutExecutor = null;
        }
        if (timers != null) {
            timers.shutdownNow();
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

    static final class QueryCancellation {
        private SQLiteConnection activeConnection;
        private volatile boolean cancelled;
        private boolean finished;
        private final Set<Statement> activeStatements = java.util.concurrent.ConcurrentHashMap.newKeySet();

        boolean isCancelled() {
            return cancelled;
        }

        synchronized boolean finish() {
            if (cancelled) {
                return false;
            }
            finished = true;
            return true;
        }

        synchronized SQLiteConnection attach(Connection connection) {
            if (cancelled) {
                return null;
            }
            if (connection instanceof SQLiteConnection sqliteConnection) {
                activeConnection = sqliteConnection;
                return sqliteConnection;
            }
            // Non-SQLite JDBC drivers enforce statement timeouts at the JDBC layer.
            return null;
        }

        Connection instrument(Connection connection) {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (cancelled && (method.getName().equals("createStatement")
                                || method.getName().equals("prepareStatement")
                                || method.getName().equals("prepareCall"))) {
                            throw new SQLException("query cancelled before statement creation");
                        }
                        try {
                            Object result = method.invoke(connection, args);
                            if (result instanceof Statement statement) {
                                return instrument(statement);
                            }
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private Statement instrument(Statement statement) {
            try {
                statement.setQueryTimeout((int) TimeUnit.MILLISECONDS.toSeconds(PLAYER_QUERY_TIMEOUT_MS));
            } catch (SQLException | AbstractMethodError unsupported) {
                LOGGER.debug("JDBC driver {} does not support statement timeouts",
                        statement.getClass().getName());
            }
            activeStatements.add(statement);
            Class<?> statementType = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return (Statement) java.lang.reflect.Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[]{statementType},
                    (proxy, method, args) -> {
                        if (cancelled && isStatementExecution(method.getName())) {
                            throw new SQLException("query cancelled before statement execution");
                        }
                        try {
                            return method.invoke(statement, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        } finally {
                            if (method.getName().equals("close")) {
                                activeStatements.remove(statement);
                            }
                        }
                    });
        }

        private static boolean isStatementExecution(String methodName) {
            return methodName.equals("execute") || methodName.equals("executeQuery")
                    || methodName.equals("executeUpdate") || methodName.equals("executeLargeUpdate")
                    || methodName.equals("executeBatch") || methodName.equals("executeLargeBatch");
        }

        synchronized void detach(Connection connection) {
            if (activeConnection == connection) {
                activeConnection = null;
            }
            activeStatements.clear();
        }

        synchronized boolean cancel() {
            if (finished) {
                return false;
            }
            cancelled = true;
            for (Statement statement : List.copyOf(activeStatements)) {
                try {
                    statement.cancel();
                } catch (SQLException | RuntimeException e) {
                    LOGGER.debug("Unable to cancel an ItemGraph JDBC statement", e);
                }
            }
            if (activeConnection != null) {
                try {
                    activeConnection.getDatabase().interrupt();
                } catch (SQLException | RuntimeException e) {
                    LOGGER.warn("Unable to interrupt a timed-out ItemGraph SQLite query", e);
                }
            }
            return true;
        }
    }
}
