package com.itemgraph.command;

import com.itemgraph.ItemGraph;
import com.itemgraph.audit.AuditReport;
import com.itemgraph.audit.AuditService;
import com.itemgraph.correlation.CorrelationResult;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionResult;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.ingest.SourceCheckpoint;
import com.itemgraph.listener.ContainerInteractionTracker;
import com.itemgraph.query.EventQueryService;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.NodeRef;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryLimits;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceQueryService;
import com.itemgraph.query.TraceResult;
import com.itemgraph.tracker.ItemEntityTracker;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * The /itemgraph (alias /ig) command tree.
 *
 * All historical and traversal queries are executed asynchronously off the Minecraft
 * server thread via QueryDispatcher.
 */
public final class ItemGraphCommands {

    private static final EventQueryService EVENT_QUERIES = new EventQueryService();
    private static final ExplainQueryService EXPLAIN_QUERIES = new ExplainQueryService();
    private static final TraceQueryService TRACE_QUERIES = new TraceQueryService();
    private static final AuditService AUDIT_SERVICE = new AuditService();

    private ItemGraphCommands() {}

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("itemgraph")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ItemGraphCommands::status))
                        .then(Commands.literal("audit").executes(ItemGraphCommands::audit))
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

                        // /ig trace ...
                        .then(Commands.literal("trace")
                                // /ig trace item <query> [limit] [sinceMinutes]
                                .then(Commands.literal("item")
                                        .then(Commands.argument("itemQuery", StringArgumentType.string())
                                                .executes(ctx -> traceItem(ctx, QueryLimits.DEFAULT_LIMIT, null))
                                                .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> traceItem(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "limit"), null))
                                                        .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                                                .executes(ctx -> traceItem(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "limit"),
                                                                        LongArgumentType.getLong(ctx, "sinceMinutes")))))))

                                // /ig trace player <player> [limit] [sinceMinutes]
                                .then(Commands.literal("player")
                                        .then(Commands.argument("player", StringArgumentType.string())
                                                .executes(ctx -> tracePlayer(ctx, QueryLimits.DEFAULT_LIMIT, null))
                                                .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> tracePlayer(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "limit"), null))
                                                        .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                                                .executes(ctx -> tracePlayer(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "limit"),
                                                                        LongArgumentType.getLong(ctx, "sinceMinutes")))))))

                                // /ig trace container <x> <y> <z> [limit] [sinceMinutes]
                                .then(Commands.literal("container")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> traceContainer(ctx, QueryLimits.DEFAULT_LIMIT, null))
                                                                .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                                                        .executes(ctx -> traceContainer(ctx,
                                                                                IntegerArgumentType.getInteger(ctx, "limit"), null))
                                                                        .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                                                                .executes(ctx -> traceContainer(ctx,
                                                                                        IntegerArgumentType.getInteger(ctx, "limit"),
                                                                                        LongArgumentType.getLong(ctx, "sinceMinutes"))))))))))
        );

        LiteralCommandNode<CommandSourceStack> gui = Commands.literal("gui")
                .requires(source -> source.hasPermission(2) && source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("item")
                        .then(Commands.argument("itemQuery", StringArgumentType.string())
                                .executes(ctx -> guiItem(ctx, null))
                                .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                        .executes(ctx -> guiItem(ctx, LongArgumentType.getLong(ctx, "sinceMinutes"))))))
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .executes(ctx -> guiPlayer(ctx, null))
                                .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                        .executes(ctx -> guiPlayer(ctx, LongArgumentType.getLong(ctx, "sinceMinutes"))))))
                .then(Commands.literal("container")
                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> guiContainer(ctx, null))
                                                        .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                                                .executes(ctx -> guiContainer(ctx,
                                                                        LongArgumentType.getLong(ctx, "sinceMinutes")))))))))
                .build();
        root.addChild(gui);
        dispatcher.register(Commands.literal("ig").redirect(root));
    }

    /** /ig event <observationId> - one raw observation, labelled OBSERVED. */
    private static int event(CommandContext<CommandSourceStack> ctx) {
        long observationId = LongArgumentType.getLong(ctx, "observationId");
        return QueryDispatcher.dispatch(ctx.getSource(), "event", conn ->
                EVENT_QUERIES.findObservation(conn, observationId)
                        .map(obs -> QueryDispatcher.QueryOutput.found(QueryFormatter.formatEvent(obs)))
                        .orElseGet(() -> QueryDispatcher.QueryOutput.notFound(
                                QueryFormatter.eventNotFound(observationId))));
    }

    /** /ig explain <edgeId> - one inferred edge plus every observation it cites. */
    private static int explain(CommandContext<CommandSourceStack> ctx) {
        long edgeId = LongArgumentType.getLong(ctx, "edgeId");
        return QueryDispatcher.dispatch(ctx.getSource(), "explain", conn ->
                EXPLAIN_QUERIES.findEdge(conn, edgeId)
                        .map(edge -> QueryDispatcher.QueryOutput.found(QueryFormatter.formatExplain(edge)))
                        .orElseGet(() -> QueryDispatcher.QueryOutput.notFound(
                                QueryFormatter.explainNotFound(edgeId))));
    }

    /** /ig trace item <query> [limit] [sinceMinutes] */
    private static int traceItem(CommandContext<CommandSourceStack> ctx, int limit, Long sinceMinutes) {
        String query = StringArgumentType.getString(ctx, "itemQuery");
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());

        return QueryDispatcher.dispatch(ctx.getSource(), "trace item", conn -> {
            List<FingerprintRef> candidates = TRACE_QUERIES.resolveFingerprints(conn, query);
            if (candidates.isEmpty()) {
                return QueryDispatcher.QueryOutput.notFound(QueryFormatter.traceNoSuchFingerprint(query));
            }
            if (candidates.size() > 1) {
                return QueryDispatcher.QueryOutput.found(QueryFormatter.formatFingerprintCandidates(query, candidates));
            }
            FingerprintRef fp = candidates.get(0);
            TraceResult result = TRACE_QUERIES.trace(conn, fp.id(), limit, window);
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatTrace(result));
        });
    }

    /** /ig trace player <player> [limit] [sinceMinutes] */
    private static int tracePlayer(CommandContext<CommandSourceStack> ctx, int limit, Long sinceMinutes) {
        String player = StringArgumentType.getString(ctx, "player");
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());

        return QueryDispatcher.dispatch(ctx.getSource(), "trace player", conn -> {
            List<NodeRef> candidates = TRACE_QUERIES.resolvePlayerNodes(conn, player);
            if (candidates.isEmpty()) {
                return QueryDispatcher.QueryOutput.notFound(QueryFormatter.traceNoSuchTarget("player '" + player + "'"));
            }
            if (candidates.size() > 1) {
                return QueryDispatcher.QueryOutput.found(
                        QueryFormatter.formatNodeCandidates("player '" + player + "'", candidates));
            }
            TraceResult result = TRACE_QUERIES.tracePlayerNode(conn, candidates.get(0).id(), limit, window);
            if (result.hops().isEmpty()) {
                return QueryDispatcher.QueryOutput.notFound(QueryFormatter.traceNoSuchTarget("player '" + player + "'"));
            }
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatTrace(result));
        });
    }

    /** /ig trace container <x> <y> <z> [limit] [sinceMinutes] */
    private static int traceContainer(CommandContext<CommandSourceStack> ctx, int limit, Long sinceMinutes) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int y = IntegerArgumentType.getInteger(ctx, "y");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());

        return QueryDispatcher.dispatch(ctx.getSource(), "trace container", conn -> {
            String target = "container at [" + x + ", " + y + ", " + z + "]";
            List<NodeRef> candidates = TRACE_QUERIES.resolveContainerNodes(conn, null, x, y, z);
            if (candidates.isEmpty()) {
                return QueryDispatcher.QueryOutput.notFound(QueryFormatter.traceNoSuchTarget(target));
            }
            if (candidates.size() > 1) {
                return QueryDispatcher.QueryOutput.found(QueryFormatter.formatNodeCandidates(target, candidates));
            }
            TraceResult result = TRACE_QUERIES.traceContainerNode(conn, candidates.get(0).id(), limit, window);
            if (result.hops().isEmpty()) {
                return QueryDispatcher.QueryOutput.notFound(QueryFormatter.traceNoSuchTarget(target));
            }
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatTrace(result));
        });
    }

    private static int guiItem(CommandContext<CommandSourceStack> ctx, Long sinceMinutes) {
        return FlowBrowserService.openItem(ctx.getSource(),
                StringArgumentType.getString(ctx, "itemQuery"), sinceMinutes);
    }

    private static int guiPlayer(CommandContext<CommandSourceStack> ctx, Long sinceMinutes) {
        return FlowBrowserService.openPlayer(ctx.getSource(),
                StringArgumentType.getString(ctx, "player"), sinceMinutes);
    }

    private static int guiContainer(CommandContext<CommandSourceStack> ctx, Long sinceMinutes) {
        ResourceLocation dimension = ResourceLocationArgument.getId(ctx, "dimension");
        return FlowBrowserService.openContainer(ctx.getSource(), dimension.toString(),
                IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"),
                IntegerArgumentType.getInteger(ctx, "z"),
                sinceMinutes);
    }

    /** /ig audit - database invariant verification */
    private static int audit(CommandContext<CommandSourceStack> ctx) {
        return QueryDispatcher.dispatch(ctx.getSource(), "audit", conn -> {
            AuditReport report = AUDIT_SERVICE.audit(conn);
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatAudit(report));
        });
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String modVersion = ModList.get()
                .getModContainerById(ItemGraph.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        boolean griefLoggerLoaded = ModList.get().isLoaded("grieflogger");
        boolean glDbAvailable = IngestionService.getInstance().getAdapter().isDatabaseAvailable();
        String glStatus = !griefLoggerLoaded ? "DISABLED (not installed)"
                : glDbAvailable ? "ENABLED (database reachable)"
                : "DISABLED (mod present but database not found)";

        DatabaseManager db = DatabaseManager.getInstance();
        boolean dbConnected = db.isInitialized();
        String dbPath = db.getDatabasePath() != null ? db.getDatabasePath().toString() : "not set";
        String dbError = db.getLastError();
        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] version=" + modVersion +
                " griefLogger=" + glStatus +
                " db=" + (dbConnected ? "connected (schema v" + db.getCurrentSchemaVersion() + ")" : "NOT CONNECTED") +
                " dbPath=" + dbPath +
                (dbError != null ? " lastError=" + dbError : "")
        ), false);
        if (!dbConnected) {
            source.sendFailure(Component.literal("[ItemGraph] Database statistics unavailable; see the database error above."));
            return 0;
        }

        IngestionService ingestion = IngestionService.getInstance();
        IngestionResult lastResult = ingestion.getLastResult();
        CorrelationResult lastCorrelation = ingestion.getCorrelationEngine().getLastResult();
        InternalObservationService internalObs = InternalObservationService.getInstance();
        ItemEntityTracker entityTracker = ItemEntityTracker.getInstance();
        long capabilityQueueRejections = ContainerInteractionTracker.getInstance().getTotalCapabilityQueueRejections();

        return QueryDispatcher.dispatch(source, "status", conn -> {
            long totalObservations;
            try (var stmt = conn.createStatement(); var rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
                rs.next();
                totalObservations = rs.getLong(1);
            }
            long activeEdges = 0;
            long supersededEdges = 0;
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT edge_state, COUNT(*) FROM ig_inferred_edges GROUP BY edge_state")) {
                while (rs.next()) {
                    if ("ACTIVE".equals(rs.getString(1))) {
                        activeEdges = rs.getLong(2);
                    } else {
                        supersededEdges += rs.getLong(2);
                    }
                }
            }
            SourceCheckpoint itemsCp = ingestion.getCheckpoint(conn, IngestionService.SOURCE_ITEMS);
            SourceCheckpoint containersCp = ingestion.getCheckpoint(conn, IngestionService.SOURCE_CONTAINERS);
            String checkpoints = "items(rowid=" + itemsCp.lastSourceRowid() + ") containers(rowid="
                    + containersCp.lastSourceRowid() + ")";
            return QueryDispatcher.QueryOutput.found(List.of(
                    "[ItemGraph] correlation: groundBridgeWindow=" + ingestion.getCorrelationEngine().getWindowSeconds() + "s"
                            + " lastPass=" + (lastCorrelation == null ? "never run yet"
                            : (lastCorrelation.success() ? "OK" : "ERROR (" + lastCorrelation.errorMessage() + ")")
                            + " (" + lastCorrelation.observationsFinalised() + " evaluated, "
                            + lastCorrelation.edgesCreated() + " bridges inferred, " + lastCorrelation.deferred()
                            + " deferred, " + lastCorrelation.durationMs() + "ms)"),
                    "[ItemGraph] ingestion: running=" + ingestion.isRunning()
                            + " totalObservations=" + totalObservations
                            + " checkpoints=" + checkpoints
                            + " lastCycle=" + (lastResult == null ? "never run yet"
                            : (lastResult.success() ? "OK" : "ERROR (" + lastResult.errorMessage() + ")")
                            + " (" + lastResult.itemsIngested() + " items, " + lastResult.containersIngested()
                            + " containers, " + lastResult.durationMs() + "ms)"),
                    "[ItemGraph] inference ledger: activeEdges=" + activeEdges + " supersededEdges=" + supersededEdges,
                    "[ItemGraph] internal queue: size=" + internalObs.getQueueSize()
                            + " enqueued=" + internalObs.getTotalEnqueued()
                            + " persisted=" + internalObs.getTotalPersisted()
                            + " dropped=" + internalObs.getTotalDropped()
                            + " capabilityQueueRejections=" + capabilityQueueRejections
                            + " transformations=" + internalObs.getTotalTransformations(),
                    "[ItemGraph] entity tracking: active=" + entityTracker.getActiveEntityCount()
                            + " drops=" + entityTracker.getDropCount()
                            + " pickups=" + entityTracker.getPickupCount()
                            + " continuityMatches=" + entityTracker.getContinuityMatchCount()
            ));
        });
    }

    private static int ingestNow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!IngestionService.getInstance().requestIngestionAsync()) {
            source.sendFailure(Component.literal("[ItemGraph] Manual ingestion was not queued: the worker is stopped or a manual cycle is already queued."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] Manual ingestion and correlation queued on the background worker; check /ig status for the result."), false);
        return 1;
    }
}
