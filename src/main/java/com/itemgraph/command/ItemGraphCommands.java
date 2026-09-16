package com.itemgraph.command;

import com.itemgraph.ItemGraph;
import com.itemgraph.correlation.CorrelationResult;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionResult;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.SourceCheckpoint;
import com.itemgraph.query.EventQueryService;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryLimits;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceQueryService;
import com.itemgraph.query.TraceResult;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.sql.SQLException;

/**
 * The {@code /itemgraph} (alias {@code /ig}) command tree.
 *
 * <p>The query handlers here are deliberately thin: they parse arguments and hand a
 * closure to {@link QueryDispatcher}, which runs the SQL off the server thread and sends
 * the result back on it. All actual query logic lives in {@code com.itemgraph.query} and
 * is unit-tested against a real database without a running server; what is left in this
 * file is only Brigadier plumbing, which cannot be tested that way and so should contain
 * as little decidable behaviour as possible.
 *
 * <p>The whole tree requires permission level 2. Per
 * {@code docs/SECURITY_AND_PERMISSIONS.md} the graph is sensitive — a trace names
 * players, containers and coordinates — so access is default-deny and the query
 * subcommands inherit the same gate as the operational ones rather than relaxing it.
 */
public final class ItemGraphCommands {

    private static final EventQueryService EVENT_QUERIES = new EventQueryService();
    private static final ExplainQueryService EXPLAIN_QUERIES = new ExplainQueryService();
    private static final TraceQueryService TRACE_QUERIES = new TraceQueryService();

    private ItemGraphCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("itemgraph")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ItemGraphCommands::status))
                        .then(Commands.literal("ingest")
                                .then(Commands.literal("now").executes(ItemGraphCommands::ingestNow)))

                        // /ig event <observationId>
                        .then(Commands.literal("event")
                                .then(Commands.argument("observationId", LongArgumentType.longArg(1))
                                        .executes(ItemGraphCommands::event)))

                        // /ig explain <edgeId>
                        .then(Commands.literal("explain")
                                .then(Commands.argument("edgeId", LongArgumentType.longArg(1))
                                        .executes(ItemGraphCommands::explain)))

                        // /ig trace item <fingerprintId> [limit] [sinceMinutes]
                        .then(Commands.literal("trace")
                                .then(Commands.literal("item")
                                        .then(Commands.argument("fingerprintId", LongArgumentType.longArg(1))
                                                .executes(ctx -> traceItem(ctx, QueryLimits.DEFAULT_LIMIT, null))
                                                // No upper bound on the argument on purpose: QueryLimits caps
                                                // an over-large request and the output says it was capped.
                                                // Rejecting it with a usage message would leave an admin
                                                // chasing an incident with no data at all.
                                                .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> traceItem(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "limit"), null))
                                                        .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                                                .executes(ctx -> traceItem(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "limit"),
                                                                        LongArgumentType.getLong(ctx, "sinceMinutes"))))))))
        );

        dispatcher.register(Commands.literal("ig").redirect(root));
    }

    // ------------------------------------------------------------------
    // Query subcommands (Phase 6)
    //
    // Each of these returns as soon as the query is queued. The int is Brigadier's
    // "accepted" code, not a statement about whether anything was found.
    // ------------------------------------------------------------------

    /** {@code /ig event <observationId>} — one raw observation, labelled OBSERVED. */
    private static int event(CommandContext<CommandSourceStack> ctx) {
        long observationId = LongArgumentType.getLong(ctx, "observationId");
        return QueryDispatcher.dispatch(ctx.getSource(), "event", conn ->
                EVENT_QUERIES.findObservation(conn, observationId)
                        .map(obs -> QueryDispatcher.QueryOutput.found(QueryFormatter.formatEvent(obs)))
                        .orElseGet(() -> QueryDispatcher.QueryOutput.notFound(
                                QueryFormatter.eventNotFound(observationId))));
    }

    /** {@code /ig explain <edgeId>} — one inferred edge plus every observation it cites. */
    private static int explain(CommandContext<CommandSourceStack> ctx) {
        long edgeId = LongArgumentType.getLong(ctx, "edgeId");
        return QueryDispatcher.dispatch(ctx.getSource(), "explain", conn ->
                EXPLAIN_QUERIES.findEdge(conn, edgeId)
                        .map(edge -> QueryDispatcher.QueryOutput.found(QueryFormatter.formatExplain(edge)))
                        .orElseGet(() -> QueryDispatcher.QueryOutput.notFound(
                                QueryFormatter.explainNotFound(edgeId))));
    }

    /**
     * {@code /ig trace item <fingerprintId> [limit] [sinceMinutes]} — the merged
     * OBSERVED + INFERRED timeline for one item fingerprint.
     *
     * <p>A fingerprint id that does not exist is a failure rather than an empty trace:
     * an empty timeline for a real item and a typo'd id are very different findings, and
     * conflating them would let a mistyped query read as "nothing ever happened to it".
     *
     * @param sinceMinutes null for an all-time trace; the row count is capped either way
     */
    private static int traceItem(CommandContext<CommandSourceStack> ctx, int limit, Long sinceMinutes) {
        long fingerprintId = LongArgumentType.getLong(ctx, "fingerprintId");
        // Resolved here, on the server thread, so the window an admin sees in the output
        // is the moment they ran the command rather than whenever the worker got to it.
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());

        return QueryDispatcher.dispatch(ctx.getSource(), "trace item", conn -> {
            TraceResult result = TRACE_QUERIES.trace(conn, fingerprintId, limit, window);
            if (!result.fingerprint().resolved()) {
                return QueryDispatcher.QueryOutput.notFound(
                        QueryFormatter.traceNoSuchFingerprint(fingerprintId));
            }
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatTrace(result));
        });
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        String modVersion = ModList.get()
                .getModContainerById(ItemGraph.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");

        boolean griefLoggerLoaded = ModList.get().isLoaded("grieflogger");

        DatabaseManager db = DatabaseManager.getInstance();
        boolean dbConnected = db.isInitialized();
        String dbPath = db.getDatabasePath() != null ? db.getDatabasePath().toString() : "not set";
        String dbError = db.getLastError();

        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] version=" + modVersion +
                " griefLogger=" + (griefLoggerLoaded ? "detected" : "NOT DETECTED") +
                " db=" + (dbConnected ? "connected (schema v" + db.getCurrentSchemaVersion() + ")" : "NOT CONNECTED") +
                " dbPath=" + dbPath +
                (dbError != null ? " lastError=" + dbError : "")
        ), false);

        IngestionService ingestion = IngestionService.getInstance();
        long totalObservations = ingestion.getTotalObservationsCount();
        IngestionResult lastResult = ingestion.getLastResult();

        String checkpointsSummary;
        try {
            var igConn = db.getConnection();
            SourceCheckpoint itemsCp = ingestion.getCheckpoint(igConn, IngestionService.SOURCE_ITEMS);
            SourceCheckpoint containersCp = ingestion.getCheckpoint(igConn, IngestionService.SOURCE_CONTAINERS);
            checkpointsSummary = "items(rowid=" + itemsCp.lastSourceRowid() + ") containers(rowid=" + containersCp.lastSourceRowid() + ")";
        } catch (SQLException e) {
            checkpointsSummary = "unavailable (" + e.getMessage() + ")";
        }
        final String checkpointsSummaryFinal = checkpointsSummary;

        CorrelationResult lastCorrelation = ingestion.getCorrelationEngine().getLastResult();
        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] correlation: groundBridgeWindow=" + ingestion.getCorrelationEngine().getWindowSeconds() + "s" +
                " lastPass=" + (lastCorrelation == null ? "never run yet" :
                    (lastCorrelation.success() ? "OK" : "ERROR (" + lastCorrelation.errorMessage() + ")") +
                    " (" + lastCorrelation.observationsFinalised() + " evaluated, " +
                    lastCorrelation.edgesCreated() + " bridges inferred, " +
                    lastCorrelation.deferred() + " deferred, " + lastCorrelation.durationMs() + "ms)")
        ), false);

        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] ingestion: running=" + ingestion.isRunning() +
                " totalObservations=" + totalObservations +
                " checkpoints=" + checkpointsSummaryFinal +
                " lastCycle=" + (lastResult == null ? "never run yet" :
                    (lastResult.success() ? "OK" : "ERROR (" + lastResult.errorMessage() + ")") +
                    " (" + lastResult.itemsIngested() + " items, " + lastResult.containersIngested() +
                    " containers, " + lastResult.durationMs() + "ms)")
        ), false);

        return 1;
    }

    private static int ingestNow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        IngestionResult result = IngestionService.getInstance().runIngestion();

        if (result.success()) {
            // Correlation is queued onto the ingestion worker rather than run here:
            // candidate search must never happen on the server thread.
            boolean queued = IngestionService.getInstance().requestCorrelationAsync();
            source.sendSuccess(() -> Component.literal(
                    "[ItemGraph] Ingestion cycle complete: " + result.itemsIngested() + " new item observations, " +
                    result.containersIngested() + " new container observations (" + result.durationMs() + "ms). " +
                    (queued ? "Correlation pass queued on the ingestion worker."
                            : "Ingestion service is stopped; correlation not queued.")
            ), false);
        } else {
            source.sendFailure(Component.literal("[ItemGraph] Ingestion cycle failed: " + result.errorMessage()));
        }

        return result.success() ? 1 : 0;
    }
}
