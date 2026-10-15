package com.itemgraph.command;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.i18n.ItemGraphLanguage;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.query.QueryFormatter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    private static final int MAX_LOCATION_GRANTS = 512;
    private static final int MAX_LOCATION_LINKS = 8;
    private static final long LOCATION_GRANT_TTL_MS = 2 * 60 * 1_000L;
    private static final Map<UUID, LocationGrant> LOCATION_GRANTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Created on first use and reused; the single worker accepts a bounded queue. */
    private static volatile ExecutorService executor;
    private static volatile ScheduledExecutorService timeoutExecutor;

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
     * @param actions bounded player-scoped chat controls sent after the text
     */
    public record QueryAction(String label, String command) {
        public QueryAction {
            if (label == null || label.isBlank() || command == null || command.isBlank()) {
                throw new IllegalArgumentException("query action label and command are required");
            }
        }
    }

    /** A safe world position from the query domain, kept separate from formatted output text. */
    public record LocationAction(String dimension, double x, double y, double z,
                                 List<String> permissionNodes) {
        public LocationAction(String dimension, double x, double y, double z) {
            this(dimension, x, y, z, List.of());
        }

        public LocationAction {
            if (dimension == null || dimension.isBlank() || !Double.isFinite(x)
                    || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("finite location and dimension are required");
            }
            permissionNodes = List.copyOf(permissionNodes);
        }
    }

    private record LocationGrant(UUID playerId, List<String> permissionNodes, LocationAction target,
                                 long expiresAtMs) {}

    public record ChatHoverDetail(String evidenceClass, String itemIdentity, String metadataFingerprint,
                                  String eventKind, String utcTime, String origin, String destination) {}

    public record QueryOutput(boolean found, List<String> lines, List<QueryAction> actions,
                              List<LocationAction> locations, Map<Integer, ChatHoverDetail> hovers) {

        public QueryOutput(boolean found, List<String> lines) {
            this(found, lines, List.of(), List.of(), Map.of());
        }

        public QueryOutput(boolean found, List<String> lines, List<QueryAction> actions) {
            this(found, lines, actions, List.of(), Map.of());
        }

        public QueryOutput(boolean found, List<String> lines, List<QueryAction> actions,
                           List<LocationAction> locations) {
            this(found, lines, actions, locations, Map.of());
        }

        public QueryOutput {
            lines = List.copyOf(lines);
            actions = List.copyOf(actions);
            locations = List.copyOf(locations);
            hovers = Map.copyOf(hovers);
            int lineCount = lines.size();
            if (hovers.keySet().stream().anyMatch(index -> index < 0 || index >= lineCount)) {
                throw new IllegalArgumentException("hover detail line index is outside query output");
            }
        }

        public static QueryOutput found(List<String> lines) {
            return new QueryOutput(true, lines);
        }

        public static QueryOutput found(List<String> lines, List<QueryAction> actions) {
            return new QueryOutput(true, lines, actions);
        }

        public static QueryOutput found(List<String> lines, List<LocationAction> locations,
                                        List<QueryAction> actions) {
            return new QueryOutput(true, lines, actions, locations, Map.of());
        }

        public static QueryOutput found(List<String> lines, List<LocationAction> locations,
                                        List<QueryAction> actions, Map<Integer, ChatHoverDetail> hovers) {
            return new QueryOutput(true, lines, actions, locations, hovers);
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
    public static int dispatch(CommandSourceStack source, String permissionNode, String label, Query query) {
        return dispatch(source, List.of(permissionNode), label, query);
    }

    public static int dispatch(CommandSourceStack source, List<String> permissionNodes, String label, Query query) {
        List<String> requiredPermissions = List.copyOf(permissionNodes);
        if (requiredPermissions.isEmpty() || !authorizedFor(source, requiredPermissions)) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("permission.denied", "[ItemGraph] You do not have permission to use this ItemGraph command.")));
            return 0;
        }
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            String unavailable = "status".equals(label)
                    ? ItemGraphLanguage.text("query.db_unavailable_status", "the ItemGraph database is not connected; inspect the server log for connection details")
                    : ItemGraphLanguage.text("query.db_unavailable", "the ItemGraph database is not connected")
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
        ScheduledFuture<?> scheduledTimeout = null;
        if (source.getEntity() instanceof ServerPlayer) {
            try {
                scheduledTimeout = schedulePlayerTimeout(cancellation);
            } catch (RejectedExecutionException stopping) {
                // The deadline scheduler is gone during server shutdown; the query still
                // delivers normally — there is just nothing left to time out.
                LOGGER.warn("ItemGraph query '{}' accepted without a timeout deadline: the scheduler is stopping", label);
            }
        }
        final ScheduledFuture<?> playerTimeout = scheduledTimeout;
        if (source.getEntity() instanceof ServerPlayer) {
            source.sendSuccess(() -> Component.literal(
                    ItemGraphLanguage.text("query.accepted", "[ItemGraph] Query accepted; results will appear in chat shortly.")), false);
        }

        if (server != null && Thread.currentThread() == serverThread && source.getEntity() == null) {
            source.sendSuccess(() -> Component.literal(
                    ItemGraphLanguage.text("query.accepted_log", "[ItemGraph] Query accepted; completed results will be written to the server log.")), false);
            future.whenComplete((output, throwable) -> {
                cancelTimeout(playerTimeout);
                deliver(source, server, serverThread, requiredPermissions, label, output, throwable, cancellation);
            });
            return 1;
        }

        // Only an entity-less off-server-thread source can block briefly for its response buffer.
        // Player sources always use server-thread delivery, even if an unexpected caller invokes dispatch off-thread.
        if (server != null && Thread.currentThread() != serverThread && source.getEntity() == null) {
            try {
                QueryOutput output = future.get(5, TimeUnit.SECONDS);
                if (!authorizedFor(source, requiredPermissions)) {
                    source.sendFailure(Component.literal(ItemGraphLanguage.text("permission.result_revoked", "[ItemGraph] You no longer have permission to view this ItemGraph result.")));
                    return 0;
                }
                if (output.found()) {
                    sendFormattedLines(source, output);
                    sendLocationActions(source, requiredPermissions, output.locations());
                    if (ItemGraphPermissions.canUse(source, ItemGraphPermissions.PAGE)) {
                        sendActions(source, output.actions());
                    }
                } else {
                    output.lines().forEach(line -> source.sendFailure(Component.literal(line)));
                }
                return output.found() ? 1 : 0;
            } catch (InterruptedException e) {
                cancellation.cancel();
                future.cancel(true);
                Thread.currentThread().interrupt();
                LOGGER.warn("ItemGraph query '{}' was interrupted on synchronous worker", label);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(ItemGraphLanguage.text("query.interrupted", "the query was interrupted"))));
                return 0;
            } catch (TimeoutException e) {
                cancellation.cancel();
                future.cancel(true);
                LOGGER.warn("ItemGraph query '{}' timed out on synchronous worker", label);
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(ItemGraphLanguage.text("query.timed_out",
                        "the query timed out; narrow the time window, filters, or limit and retry"))));
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
            deliver(source, server, serverThread, requiredPermissions, label, output, throwable, cancellation);
        });

        return 1;
    }

    /**
     * Internal API bridge: submits a read-only query to the same bounded worker without
     * exposing the JDBC {@link Connection} outside {@code com.itemgraph.command}.
     */
    static <T> CompletableFuture<T> submitData(DataQuery<T> query) {
        QueryCancellation cancellation = new QueryCancellation();
        ScheduledFuture<?> timeout;
        try {
            timeout = schedulePlayerTimeout(cancellation);
        } catch (RejectedExecutionException stopping) {
            timeout = null;
            LOGGER.warn("ItemGraph data query accepted without a timeout deadline: the scheduler is stopping");
        }
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(
                    () -> executeData(query, cancellation), queryExecutor());
        } catch (RejectedExecutionException rejected) {
            cancelTimeout(timeout);
            throw rejected;
        }
        ScheduledFuture<?> scheduledTimeout = timeout;
        future.whenComplete((result, failure) -> cancelTimeout(scheduledTimeout));
        return future;
    }

    static <T> int dispatchData(CommandSourceStack source, String permissionNode, String label, DataQuery<T> query,
                                BiConsumer<CommandSourceStack, T> consumer) {
        return dispatchData(source, permissionNode, label, query, consumer, () -> {});
    }

    static <T> int dispatchData(CommandSourceStack source, List<String> permissionNodes, String label,
                                DataQuery<T> query, BiConsumer<CommandSourceStack, T> consumer,
                                Runnable failureConsumer) {
        List<String> requiredPermissions = List.copyOf(permissionNodes);
        if (requiredPermissions.isEmpty() || !authorizedFor(source, requiredPermissions)) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("permission.denied", "[ItemGraph] You do not have permission to use this ItemGraph command.")));
            failureConsumer.run();
            return 0;
        }
        return dispatchDataAuthorized(source, requiredPermissions, label, query, consumer, failureConsumer);
    }

    static <T> int dispatchData(CommandSourceStack source, String permissionNode, String label,
                                DataQuery<T> query, BiConsumer<CommandSourceStack, T> consumer,
                                Runnable failureConsumer) {
        if (permissionNode == null || !ItemGraphPermissions.canUse(source, permissionNode)) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("permission.denied", "[ItemGraph] You do not have permission to use this ItemGraph command.")));
            failureConsumer.run();
            return 0;
        }
        return dispatchDataAuthorized(source, List.of(permissionNode), label, query, consumer, failureConsumer);
    }

    private static <T> int dispatchDataAuthorized(CommandSourceStack source, List<String> permissionNodes, String label,
                                DataQuery<T> query, BiConsumer<CommandSourceStack, T> consumer,
                                Runnable failureConsumer) {
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            source.sendFailure(Component.literal(QueryFormatter.queryFailed(
                    ItemGraphLanguage.text("query.db_unavailable", "the ItemGraph database is not connected")
                            + (db.getLastError() != null ? " (" + db.getLastError() + ")" : "")
                            + ". See /ig status.")));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (server == null) {
            source.sendFailure(Component.literal(QueryFormatter.queryFailed(ItemGraphLanguage.text("query.server_unavailable", "the server is not available"))));
            return 0;
        }
        Thread serverThread = server.getRunningThread();
        QueryCancellation cancellation = new QueryCancellation();
        ScheduledFuture<?> scheduledTimeout;
        try {
            scheduledTimeout = schedulePlayerTimeout(cancellation);
        } catch (RejectedExecutionException stopping) {
            // The deadline scheduler is gone during server shutdown; the query still
            // delivers normally — there is just nothing left to time out.
            scheduledTimeout = null;
            LOGGER.warn("ItemGraph data query '{}' accepted without a timeout deadline: the scheduler is stopping", label);
        }
        final ScheduledFuture<?> timeout = scheduledTimeout;
        CompletableFuture<T> future;
        try {
            future = CompletableFuture.supplyAsync(() -> executeData(query, cancellation), queryExecutor());
        } catch (RejectedExecutionException e) {
            cancelTimeout(timeout);
            return rejectQueue(source);
        }
        future.whenComplete((result, throwable) -> cancelTimeout(timeout));
        future.whenComplete((result, throwable) -> server.execute(() -> {
            if (Thread.currentThread() != serverThread) {
                failureConsumer.run();
                return;
            }
            if (!canStillReport(source, server)) {
                failureConsumer.run();
                return;
            }
            if (!authorizedFor(source, permissionNodes)) {
                source.sendFailure(Component.literal(ItemGraphLanguage.text("permission.result_revoked", "[ItemGraph] You no longer have permission to view this ItemGraph result.")));
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
                source.sendFailure(Component.literal(QueryFormatter.queryFailed(callerFailureMessage(label, cause, cancellation))));
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
        source.sendFailure(Component.literal(ItemGraphLanguage.text("query.queue_full", "[ItemGraph] Query worker queue is full or shutting down; retry shortly.")));
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
                    cancellation.markStarted();
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
                                List<String> permissionNodes, String label, QueryOutput output, Throwable throwable,
                                QueryCancellation cancellation) {
        if (server == null) {
            LOGGER.warn("Dropping /ig {} result: the command source has no server.", label);
            return;
        }

        // The hop back onto the server thread. Everything below this line runs on it.
        server.execute(() -> {
            if (Thread.currentThread() != serverThread || !canStillReport(source, server)) {
                return;
            }
            if (!authorizedFor(source, permissionNodes)) {
                source.sendFailure(Component.literal(ItemGraphLanguage.text("permission.result_revoked", "[ItemGraph] You no longer have permission to view this ItemGraph result.")));
                return;
            }
            boolean entityless = source.getEntity() == null;

            if (throwable != null) {
                // CompletableFuture wrappers stringify their causes; unwrap consistently so the
                // admin sees the underlying SQL message rather than an implementation class name.
                Throwable cause = unwrap(throwable);
                LOGGER.error("ItemGraph query '{}' failed", label, cause);
                if (!entityless) {
                    source.sendFailure(Component.literal(QueryFormatter.queryFailed(
                            callerFailureMessage(label, cause, cancellation))));
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
            sendFormattedLines(source, output);
            sendLocationActions(source, permissionNodes, output.locations());
            if (ItemGraphPermissions.canUse(source, ItemGraphPermissions.PAGE)) {
                sendActions(source, output.actions());
            }
        });
    }

    static String callerFailureMessage(String label, Throwable cause) {
        if ("status".equals(label)) {
            return "status query failed; inspect the server log for connection details";
        }
        return String.valueOf(cause.getMessage());
    }

    /**
     * Maps a failed async query to caller-facing text. A dispatcher-fired timeout is
     * reported as a timeout — distinguishing a query that never left the worker queue
     * from one cancelled mid-execution — instead of leaking the JDBC cancellation
     * sentinel message. Other failures keep the underlying cause text.
     */
    static String callerFailureMessage(String label, Throwable cause, QueryCancellation cancellation) {
        if (cancellation != null && cancellation.wasTimedOut()) {
            return cancellation.timedOutInQueue()
                    ? ItemGraphLanguage.text("query.timed_out_queued",
                            "the query waited too long in the worker queue; retry shortly")
                    : ItemGraphLanguage.text("query.timed_out",
                            "the query timed out; narrow the time window, filters, or limit and retry");
        }
        return callerFailureMessage(label, cause);
    }

    static boolean authorizedFor(CommandSourceStack source, List<String> permissionNodes) {
        return permissionNodes != null && !permissionNodes.isEmpty()
                && permissionNodes.stream().allMatch(node -> ItemGraphPermissions.canUse(source, node));
    }

    private static boolean authorizedFor(CommandSourceStack source, String permissionNode) {
        return permissionNode != null && ItemGraphPermissions.canUse(source, permissionNode);
    }

    /** Sends bounded interactive controls after the textual query output. */
    static void sendActions(CommandSourceStack source, List<QueryAction> actions) {
        if (actions.isEmpty()) {
            return;
        }
        MutableComponent controls = Component.literal(ItemGraphLanguage.sourceText("[ItemGraph] "));
        for (int i = 0; i < actions.size(); i++) {
            QueryAction action = actions.get(i);
            if (i > 0) {
                controls.append(" ");
            }
            controls.append(Component.literal("[" + ItemGraphLanguage.sourceText(action.label()) + "]").withStyle(style ->
                    style.withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, action.command()))));
        }
        source.sendSuccess(() -> controls, false);
    }

    private static void sendFormattedLines(CommandSourceStack source, QueryOutput output) {
        for (int i = 0; i < output.lines().size(); i++) {
            String line = output.lines().get(i);
            ChatHoverDetail detail = output.hovers().get(i);
            source.sendSuccess(() -> source.getEntity() == null || detail == null
                    ? Component.literal(line) : chatLine(line, detail), false);
        }
    }

    static Component chatLine(String line, ChatHoverDetail detail) {
        Component hover = Component.literal(ItemGraphLanguage.sourceText("Evidence") + ": " + safeHoverField(detail.evidenceClass(), 48)
                + "\n" + ItemGraphLanguage.sourceText("Item") + ": " + safeHoverField(detail.itemIdentity(), 96)
                + "\n" + ItemGraphLanguage.sourceText("Canonical metadata fingerprint") + ": " + safeHoverField(detail.metadataFingerprint(), 80)
                + "\n" + ItemGraphLanguage.sourceText("Event") + ": " + safeHoverField(detail.eventKind(), 64)
                + "\n" + ItemGraphLanguage.sourceText("UTC time") + ": " + safeHoverField(detail.utcTime(), 32)
                + "\n" + ItemGraphLanguage.sourceText("Origin") + ": " + safeHoverField(detail.origin(), 96)
                + "\n" + ItemGraphLanguage.sourceText("Destination") + ": " + safeHoverField(detail.destination(), 96));
        return Component.literal(line).withStyle(style -> style.withHoverEvent(
                new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    private static String safeHoverField(String value, int maxLength) {
        if (value == null || value.isBlank()) return "not recorded";
        String sanitized = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength - 3) + "...";
    }

    static void sendLocationActions(CommandSourceStack source, List<String> permissionNodes,
                                    List<LocationAction> locations) {
        if (!(source.getEntity() instanceof ServerPlayer player) || locations.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<LocationAction> distinct = locations.stream().distinct().toList();
        int shown = 0;
        for (LocationAction location : distinct.stream().limit(MAX_LOCATION_LINKS).toList()) {
            List<String> actionPermissions = location.permissionNodes().isEmpty()
                    ? permissionNodes : location.permissionNodes();
            UUID token = issueLocationGrant(player.getUUID(), actionPermissions, location, now);
            if (token == null) break;
            shown++;
            String label = "[" + ItemGraphLanguage.text("navigation.go_to", "Go to {0} {1} {2} {3}",
                    location.dimension(), formatCoordinate(location.x()), formatCoordinate(location.y()),
                    formatCoordinate(location.z())) + "]";
            player.sendSystemMessage(Component.literal(label).withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ig goto " + token))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(
                            ItemGraphLanguage.text("navigation.go_to_hover",
                                    "Teleports you (only you) to this recorded location."))))));
        }
        if (shown < distinct.size()) {
            player.sendSystemMessage(Component.literal(ItemGraphLanguage.text("navigation.more_locations",
                    "[ItemGraph] ...and {0} more recorded locations could not be shown; narrow the query to reach them.",
                    distinct.size() - shown)));
        }
    }

    static UUID issueLocationGrant(UUID playerId, List<String> permissionNodes, LocationAction target, long nowMs) {
        LOCATION_GRANTS.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= nowMs);
        if (LOCATION_GRANTS.size() >= MAX_LOCATION_GRANTS || playerId == null
                || permissionNodes == null || permissionNodes.isEmpty() || target == null) return null;
        UUID token = UUID.randomUUID();
        LOCATION_GRANTS.put(token, new LocationGrant(playerId, List.copyOf(permissionNodes), target,
                nowMs + LOCATION_GRANT_TTL_MS));
        return token;
    }

    private static String formatCoordinate(double coordinate) {
        return coordinate == Math.rint(coordinate) ? Long.toString((long) coordinate)
                : String.format(java.util.Locale.ROOT, "%.2f", coordinate);
    }

    static boolean consumeLocationGrant(CommandSourceStack source, UUID token) {
        if (!(source.getEntity() instanceof ServerPlayer player) || source.getServer() == null) return false;
        LocationGrant grant = LOCATION_GRANTS.remove(token);
        if (grant == null || grant.expiresAtMs() <= System.currentTimeMillis()
                || !grant.playerId().equals(player.getUUID())
                || source.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                || !authorizedFor(source, grant.permissionNodes())) return false;
        ResourceLocation dimension = ResourceLocation.tryParse(grant.target().dimension());
        if (dimension == null) return false;
        net.minecraft.server.level.ServerLevel level = source.getServer().getLevel(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension));
        if (level == null) return false;
        player.teleportTo(level, grant.target().x(), grant.target().y(), grant.target().z(),
                Set.of(), player.getYRot(), player.getXRot());
        return true;
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
        return queryTimeoutExecutor().schedule(cancellation::cancelTimedOut,
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
        LOCATION_GRANTS.clear();
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
        private volatile boolean started;
        private volatile boolean timedOut;
        private volatile boolean timedOutInQueue;
        private final Set<Statement> activeStatements = java.util.concurrent.ConcurrentHashMap.newKeySet();

        boolean isCancelled() {
            return cancelled;
        }

        /** Called once just before the query body runs, so a deadline that fires during connection setup still counts as queue time. */
        void markStarted() {
            started = true;
        }

        boolean wasTimedOut() {
            return timedOut;
        }

        /** True when the timeout fired while the query still waited in the worker queue. */
        boolean timedOutInQueue() {
            return timedOutInQueue;
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

        /** Dispatcher-fired deadline; records whether execution had begun so callers can phrase the timeout correctly. */
        synchronized void cancelTimedOut() {
            timedOut = true;
            timedOutInQueue = !started;
            cancel();
        }

        synchronized void cancel() {
            if (finished) {
                return;
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
        }
    }
}
