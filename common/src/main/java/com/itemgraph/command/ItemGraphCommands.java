package com.itemgraph.command;

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
import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.query.AuditEventDetail;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.NodeRef;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryLimits;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceQueryService;
import com.itemgraph.query.TraceResult;
import com.itemgraph.query.UnifiedEvidenceDetail;
import com.itemgraph.query.UnifiedEvidenceQueryService;
import com.itemgraph.tracker.ItemEntityTracker;
import com.itemgraph.core.port.RuntimeInformationPort;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The /itemgraph (alias /ig) command tree.
 *
 * All historical and traversal queries are executed asynchronously off the Minecraft
 * server thread via QueryDispatcher.
 */
public final class ItemGraphCommands {

    private static final EventQueryService EVENT_QUERIES = new EventQueryService();
    private static final AuditEventQueryService AUDIT_EVENT_QUERIES = new AuditEventQueryService();
    private static final UnifiedEvidenceQueryService UNIFIED_EVIDENCE_QUERIES = new UnifiedEvidenceQueryService();
    private static final ExplainQueryService EXPLAIN_QUERIES = new ExplainQueryService();
    private static final TraceQueryService TRACE_QUERIES = new TraceQueryService();
    private static final AuditService AUDIT_SERVICE = new AuditService();
    private static final int MAX_PAGE_SESSIONS_PER_PLAYER = 8;
    private static final Map<UUID, Map<UUID, AuditPageSession>> AUDIT_PAGE_SESSIONS = new ConcurrentHashMap<>();
    private static final long PAGE_SESSION_TTL_MS = 30L * 60L * 1_000L;
    private static volatile RuntimeInformationPort runtimeInformation = new RuntimeInformationPort() {
        @Override public String modVersion() { return "unknown"; }
        @Override public boolean isModLoaded(String modId) { return false; }
    };

    private record AuditPageSession(
            UUID sessionId,
            String eventType,
            String playerName,
            QueryWindow window,
            int limit,
            String levelId,
            Double centerX,
            Double centerY,
            Double centerZ,
            Double radius,
            AuditLookupFilters filters,
            String filterDescription,
            long createdAtMs) {
    }

    private ItemGraphCommands() {}

    public static void setRuntimeInformation(RuntimeInformationPort information) {
        runtimeInformation = information == null ? runtimeInformation : information;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("itemgraph")
                        .requires(source -> source.hasPermission(2))
                        .executes(ItemGraphCommands::help)
                        .then(Commands.literal("help")
                                .executes(ItemGraphCommands::help)
                                .then(Commands.argument("topic", StringArgumentType.greedyString())
                                        .suggests(ItemGraphCommands::suggestHelpTopics)
                                        .executes(ItemGraphCommands::helpTopic)))
                        .then(Commands.literal("status").executes(ItemGraphCommands::status))
                        .then(Commands.literal("audit").executes(ItemGraphCommands::audit))
                        .then(buildStandalonePageCommand())
                        .then(buildLookupCommand())
                        .then(Commands.literal("ingest")
                                .then(Commands.literal("now").executes(ItemGraphCommands::ingestNow))
                                .then(Commands.literal("history").executes(ItemGraphCommands::ingestHistory)))

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
                                                .suggests(ItemGraphCommands::suggestItemIds)
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
                                                .suggests(ItemGraphCommands::suggestOnlinePlayers)
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
                                .suggests(ItemGraphCommands::suggestItemIds)
                                .executes(ctx -> guiItem(ctx, null))
                                .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                        .executes(ctx -> guiItem(ctx, LongArgumentType.getLong(ctx, "sinceMinutes"))))))
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.string())
                                .suggests(ItemGraphCommands::suggestOnlinePlayers)
                                .executes(ctx -> guiPlayer(ctx, null))
                                .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                        .executes(ctx -> guiPlayer(ctx, LongArgumentType.getLong(ctx, "sinceMinutes"))))))
                .then(Commands.literal("container")
                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                .suggests(ItemGraphCommands::suggestDimensions)
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> guiContainer(ctx, null))
                                                        .then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                                                                .executes(ctx -> guiContainer(ctx,
                                                                        LongArgumentType.getLong(ctx, "sinceMinutes")))))))))
                .build();
        root.addChild(gui);

        LiteralCommandNode<CommandSourceStack> inspect = Commands.literal("inspect")
                .requires(source -> source.hasPermission(2))
                .executes(ItemGraphCommands::inspectToggle)
                .then(Commands.literal("on").executes(ctx -> inspectSet(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> inspectSet(ctx, false)))
                .then(Commands.literal("status").executes(ItemGraphCommands::inspectStatus))
                .build();
        root.addChild(inspect);
        dispatcher.register(Commands.literal("ig")
                .requires(source -> source.hasPermission(2))
                .executes(ItemGraphCommands::help)
                .redirect(root));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildLookupCommand() {
        LiteralArgumentBuilder<CommandSourceStack> lookup = Commands.literal("lookup");

        var type = Commands.argument("eventType", StringArgumentType.word())
                .suggests(ItemGraphCommands::suggestAuditEventTypes)
                .executes(ctx -> lookupAudit(ctx,
                        StringArgumentType.getString(ctx, "eventType"), null,
                        QueryLimits.DEFAULT_LIMIT, null));
        var typeLimit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAudit(ctx,
                        StringArgumentType.getString(ctx, "eventType"), null,
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        typeLimit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAudit(ctx,
                        StringArgumentType.getString(ctx, "eventType"), null,
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        type.then(typeLimit);
        lookup.then(type);

        var playerType = Commands.argument("eventType", StringArgumentType.word())
                .suggests(ItemGraphCommands::suggestAuditEventTypes)
                .executes(ctx -> lookupAudit(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        StringArgumentType.getString(ctx, "playerName"),
                        QueryLimits.DEFAULT_LIMIT, null));
        var playerLimit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAudit(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        StringArgumentType.getString(ctx, "playerName"),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        playerLimit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAudit(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        StringArgumentType.getString(ctx, "playerName"),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        playerType.then(playerLimit);

        lookup.then(Commands.literal("player")
                .then(Commands.argument("playerName", StringArgumentType.string())
                        .then(playerType)));

        lookup.then(buildNearLookupCommand());
        lookup.then(buildPagedLookupCommand());
        lookup.then(Commands.literal("filters")
                .then(Commands.argument("filters", StringArgumentType.greedyString())
                        .executes(ctx -> lookupAuditFilters(ctx,
                                StringArgumentType.getString(ctx, "filters")))));
        lookup.then(Commands.literal("provenance")
                .then(Commands.argument("sourceSha256", StringArgumentType.word())
                        .then(Commands.argument("table", StringArgumentType.word())
                                .then(Commands.argument("sourceKey", StringArgumentType.word())
                                        .executes(ctx -> lookupHistoricalProvenance(ctx,
                                                QueryLimits.DEFAULT_LIMIT))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                                .executes(ctx -> lookupHistoricalProvenance(ctx,
                                                        IntegerArgumentType.getInteger(ctx, "limit"))))))));
        return lookup;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildStandalonePageCommand() {
        return Commands.literal("page")
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> lookupAuditSessionPage(ctx,
                                IntegerArgumentType.getInteger(ctx, "page"), null))
                        .then(Commands.argument("session", StringArgumentType.word())
                                .executes(ctx -> lookupAuditSessionPage(ctx,
                                        IntegerArgumentType.getInteger(ctx, "page"),
                                        StringArgumentType.getString(ctx, "session")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildPagedLookupCommand() {
        var since = Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAuditPage(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes")));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAuditPage(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(since);
        var eventType = Commands.argument("eventType", StringArgumentType.word())
                .suggests(ItemGraphCommands::suggestAuditEventTypes)
                .executes(ctx -> lookupAuditPage(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "page"),
                        QueryLimits.DEFAULT_LIMIT, null));
        eventType.then(limit);
        var page = Commands.argument("page", IntegerArgumentType.integer(1));
        page.then(eventType);
        return Commands.literal("page").then(page);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNearLookupCommand() {
        var since = Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAuditNear(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes")));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAuditNear(ctx,
                        StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(since);
        var eventType = Commands.argument("eventType", StringArgumentType.word())
                .suggests(ItemGraphCommands::suggestAuditEventTypes)
                .executes(ctx -> lookupAuditNear(ctx,
                        StringArgumentType.getString(ctx, "eventType"), QueryLimits.DEFAULT_LIMIT, null));
        eventType.then(limit);
        var radius = Commands.argument("radius", DoubleArgumentType.doubleArg(0.1));
        radius.then(eventType);
        var z = Commands.argument("z", DoubleArgumentType.doubleArg());
        z.then(radius);
        var y = Commands.argument("y", DoubleArgumentType.doubleArg());
        y.then(z);
        var x = Commands.argument("x", DoubleArgumentType.doubleArg());
        x.then(y);
        var dimension = Commands.argument("dimension", StringArgumentType.word());
        dimension.then(x);
        return Commands.literal("near").then(dimension);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandHelp.overviewLines().forEach(line ->
                source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static int helpTopic(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String topic = StringArgumentType.getString(ctx, "topic");
        List<String> lines = CommandHelp.topicLines(topic);
        if (lines == null) {
            source.sendFailure(Component.literal(
                    "[ItemGraph] Unknown help topic '" + topic + "'. Valid topics: " + CommandHelp.validTopicsText()));
            return 0;
        }
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestHelpTopics(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(CommandHelp.TOPIC_NAMES, builder);
    }

    private static CompletableFuture<Suggestions> suggestOnlinePlayers(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder);
    }

    private static CompletableFuture<Suggestions> suggestItemIds(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet().stream(), builder);
    }

    private static CompletableFuture<Suggestions> suggestDimensions(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(
                ctx.getSource().levels().stream().map(ResourceKey::location), builder);
    }

    private static CompletableFuture<Suggestions> suggestAuditEventTypes(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(UnifiedEvidenceQueryService.ACTION_TYPES, builder);
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

    /** /ig inspect - toggle the caller's in-world inspection mode. */
    private static int inspectToggle(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = inspectionPlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        boolean enabled = InspectionService.getInstance().toggle(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(enabled
                ? "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history; use /ig inspect off to disable."
                : "[ItemGraph] Inspection disabled."), false);
        return 1;
    }

    /** /ig inspect on|off - set the caller's mode deterministically. */
    private static int inspectSet(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        ServerPlayer player = inspectionPlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        boolean changed = InspectionService.getInstance().setEnabled(player.getUUID(), enabled);
        ctx.getSource().sendSuccess(() -> Component.literal(enabled
                ? changed
                        ? "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history."
                        : "[ItemGraph] Inspection is already enabled."
                : changed
                        ? "[ItemGraph] Inspection disabled."
                        : "[ItemGraph] Inspection is already disabled."), false);
        return 1;
    }

    /** /ig inspect status - report the caller's current mode without changing it. */
    private static int inspectStatus(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = inspectionPlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        boolean enabled = InspectionService.getInstance().isEnabled(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[ItemGraph] Inspection is " + (enabled ? "enabled" : "disabled") + "."), false);
        return 1;
    }

    private static ServerPlayer inspectionPlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(Component.literal("[ItemGraph] Inspection requires a player."));
        return null;
    }

    /** /ig audit - database invariant verification */
    private static int audit(CommandContext<CommandSourceStack> ctx) {
        return QueryDispatcher.dispatch(ctx.getSource(), "audit", conn -> {
            AuditReport report = AUDIT_SERVICE.audit(conn);
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatAudit(report));
        });
    }

    /** /ig lookup <eventType> [limit] [sinceMinutes] and /ig lookup player ... */
    private static int lookupAudit(CommandContext<CommandSourceStack> ctx, String eventType,
                                   String playerName, int limit, Long sinceMinutes) {
        return lookupAudit(ctx, eventType, playerName, limit, sinceMinutes,
                null, null, null, null, null);
    }

    private static int lookupAuditNear(CommandContext<CommandSourceStack> ctx, String eventType,
                                       int limit, Long sinceMinutes) {
        return lookupAudit(ctx, eventType, null, limit, sinceMinutes,
                StringArgumentType.getString(ctx, "dimension"),
                DoubleArgumentType.getDouble(ctx, "x"),
                DoubleArgumentType.getDouble(ctx, "y"),
                DoubleArgumentType.getDouble(ctx, "z"),
                DoubleArgumentType.getDouble(ctx, "radius"));
    }

    /**
     * Starts the bounded, exact-position audit view used by {@code /ig inspect}.
     * A zero radius is an internal sentinel for exact block coordinates; public
     * near lookups continue to use the documented minimum radius of one block.
     */
    public static int openBlockInspection(CommandSourceStack source, String dimension, int x, int y, int z) {
        if (!(source.getEntity() instanceof ServerPlayer) || !source.hasPermission(2)) {
            source.sendFailure(Component.literal(
                    "[ItemGraph] Block inspection requires a permission-level-2 player."));
            return 0;
        }
        AuditPageSession session = new AuditPageSession(
                UUID.randomUUID(), "all", null, QueryWindow.unbounded(), QueryLimits.DEFAULT_LIMIT,
                dimension, (double) x, (double) y, (double) z, 0.0, null,
                "inspect block=" + dimension + " [" + x + "," + y + "," + z + "]",
                System.currentTimeMillis());
        rememberPageSession(source, session);
        return dispatchAuditPage(source, "inspect block", session, 1, true);
    }

    /** Executes the GriefLogger-compatible name.value filter form around the issuing player. */
    private static int lookupAuditFilters(CommandContext<CommandSourceStack> ctx, String expression) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(
                    "[ItemGraph] Filtered lookup requires a permission-level-2 player so radius can use the current position."));
            return 0;
        }
        AuditLookupFilters filters;
        try {
            filters = AuditLookupFilters.parse(expression, System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("[ItemGraph] Invalid lookup filter: " + e.getMessage()));
            return 0;
        }
        String levelId = player.level().dimension().location().toString();
        double centerX = player.getX();
        double centerY = player.getY();
        double centerZ = player.getZ();
        String filterDescription = filters.describe()
                + " dimension=" + levelId
                + " center=" + centerX + "," + centerY + "," + centerZ;
        AuditPageSession session = new AuditPageSession(
                UUID.randomUUID(), null, null, filters.window(), QueryLimits.DEFAULT_LIMIT, levelId,
                centerX, centerY, centerZ, filters.radiusBlocks(), filters,
                filterDescription, System.currentTimeMillis());
        rememberPageSession(source, session);
        return dispatchAuditPage(source, "lookup filtered audit", session, 1, true);
    }

    private static int lookupHistoricalProvenance(CommandContext<CommandSourceStack> ctx, int requestedLimit) {
        CommandSourceStack source = ctx.getSource();
        String sourceSha256 = StringArgumentType.getString(ctx, "sourceSha256");
        String table = StringArgumentType.getString(ctx, "table");
        String sourceKey = StringArgumentType.getString(ctx, "sourceKey");
        int limit = QueryLimits.clampLimit(requestedLimit);
        return QueryDispatcher.dispatch(source, "lookup provenance", conn -> {
            List<UnifiedEvidenceDetail> evidence = UNIFIED_EVIDENCE_QUERIES.findHistoricalProvenance(
                    conn, sourceSha256, table, sourceKey, limit, 0);
            return QueryDispatcher.QueryOutput.found(
                    QueryFormatter.formatUnifiedEvidence(evidence,
                            "source=" + sourceSha256 + " table=" + table + " key=" + sourceKey
                                    + " limit=" + limit),
                    List.of());
        });
    }

    private static int lookupAuditPage(CommandContext<CommandSourceStack> ctx, String eventType,
                                       int page, int limit, Long sinceMinutes) {
        int clampedLimit = QueryLimits.clampLimit(limit);
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());
        AuditPageSession session = new AuditPageSession(
                UUID.randomUUID(), eventType, null, window, clampedLimit, null,
                null, null, null, null, null,
                "type=" + eventType + " window=" + window.describe(),
                System.currentTimeMillis());
        rememberPageSession(ctx.getSource(), session);
        return dispatchAuditPage(ctx.getSource(), "lookup audit", session, page,
                ctx.getSource().getEntity() instanceof ServerPlayer);
    }

    private static int lookupAuditSessionPage(CommandContext<CommandSourceStack> ctx, int page,
                                              String sessionToken) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(
                    "[ItemGraph] /ig page requires a player with an active lookup session."));
            return 0;
        }
        AuditPageSession session;
        if (sessionToken == null) {
            session = activePageSession(source);
        } else {
            UUID sessionId;
            try {
                sessionId = UUID.fromString(sessionToken);
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.literal("[ItemGraph] Invalid lookup page session."));
                return 0;
            }
            session = pageSession(source, sessionId);
        }
        if (session == null) {
            source.sendFailure(Component.literal(
                    "[ItemGraph] No active lookup page session. Run /ig lookup first."));
            return 0;
        }
        return dispatchAuditPage(source, "lookup page", session, page, true);
    }

    private static int dispatchAuditPage(CommandSourceStack source, String label,
                                         AuditPageSession session, int page,
                                         boolean standaloneCommand) {
        int clampedLimit = QueryLimits.clampLimit(session.limit());
        int requestedPage = Math.max(1, page);
        int offset = QueryLimits.clampPageOffset(requestedPage, clampedLimit);
        int effectivePage = offset / clampedLimit + 1;
        String filter = session.filterDescription() + " page=" + effectivePage
                + " limit=" + clampedLimit
                + (effectivePage == requestedPage ? "" : " requestedPage=" + requestedPage + " offset=" + offset);
        return QueryDispatcher.dispatch(source, label, conn -> {
            List<String> lines;
            int returnedRows;
            if (session.filters() != null) {
                List<UnifiedEvidenceDetail> evidence = UNIFIED_EVIDENCE_QUERIES.findFiltered(
                        conn, session.filters(), session.levelId(),
                        session.centerX(), session.centerY(), session.centerZ(), clampedLimit, offset);
                lines = QueryFormatter.formatUnifiedEvidence(evidence, filter);
                returnedRows = evidence.size();
            } else {
                List<AuditEventDetail> events = AUDIT_EVENT_QUERIES.find(conn, session.eventType(), session.playerName(),
                        session.window(), session.levelId(), session.centerX(), session.centerY(),
                        session.centerZ(), session.radius(), clampedLimit, offset);
                lines = QueryFormatter.formatAuditEvents(events, filter);
                returnedRows = events.size();
            }
            List<QueryDispatcher.QueryAction> actions = new java.util.ArrayList<>();
            if (standaloneCommand && effectivePage > 1) {
                actions.add(new QueryDispatcher.QueryAction("Previous",
                        standalonePageCommand(effectivePage - 1, session.sessionId())));
            }
            if (standaloneCommand && shouldOfferNextAuditPage(effectivePage, clampedLimit, offset,
                    returnedRows)) {
                actions.add(new QueryDispatcher.QueryAction("Next",
                        standalonePageCommand(effectivePage + 1, session.sessionId())));
            }
            return QueryDispatcher.QueryOutput.found(
                    lines, actions);
        });
    }

    private static void rememberPageSession(CommandSourceStack source, AuditPageSession session) {
        if (source.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
            if (playerId != null) {
                Map<UUID, AuditPageSession> sessions = AUDIT_PAGE_SESSIONS.computeIfAbsent(
                        playerId, ignored -> new ConcurrentHashMap<>());
                sessions.put(session.sessionId(), session);
                while (sessions.size() > MAX_PAGE_SESSIONS_PER_PLAYER) {
                    UUID oldestId = sessions.values().stream()
                            .min(java.util.Comparator.comparingLong(AuditPageSession::createdAtMs))
                            .map(AuditPageSession::sessionId)
                            .orElse(null);
                    if (oldestId == null) {
                        break;
                    }
                    sessions.remove(oldestId);
                }
            }
        }
    }

    private static AuditPageSession activePageSession(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return null;
        }
        UUID playerId = player.getUUID();
        if (playerId == null) {
            return null;
        }
        Map<UUID, AuditPageSession> sessions = AUDIT_PAGE_SESSIONS.get(playerId);
        if (sessions == null || sessions.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        sessions.values().removeIf(session -> now - session.createdAtMs() > PAGE_SESSION_TTL_MS);
        AuditPageSession active = sessions.values().stream()
                .max(java.util.Comparator.comparingLong(AuditPageSession::createdAtMs))
                .orElse(null);
        if (sessions.isEmpty()) {
            AUDIT_PAGE_SESSIONS.remove(playerId, sessions);
        }
        return active;
    }

    private static AuditPageSession pageSession(CommandSourceStack source, UUID sessionId) {
        if (!(source.getEntity() instanceof ServerPlayer player) || sessionId == null) {
            return null;
        }
        UUID playerId = player.getUUID();
        if (playerId == null) {
            return null;
        }
        Map<UUID, AuditPageSession> sessions = AUDIT_PAGE_SESSIONS.get(playerId);
        if (sessions == null) {
            return null;
        }
        AuditPageSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (System.currentTimeMillis() - session.createdAtMs() > PAGE_SESSION_TTL_MS) {
            sessions.remove(sessionId, session);
            return null;
        }
        return session;
    }

    /** Clears a player's paging state when the server lifecycle removes them. */
    public static void clearPageSession(UUID playerId) {
        if (playerId != null) {
            AUDIT_PAGE_SESSIONS.remove(playerId);
        }
    }

    /** Clears all paging state before the owning server/database shuts down. */
    public static void clearPageSessions() {
        AUDIT_PAGE_SESSIONS.clear();
    }

    private static String standalonePageCommand(int page, UUID sessionId) {
        return "/ig page " + Math.max(1, page) + " " + sessionId;
    }

    static boolean shouldOfferNextAuditPage(int effectivePage, int clampedLimit,
                                             int offset, int returnedRows) {
        if (returnedRows != clampedLimit) {
            return false;
        }
        int nextOffset = QueryLimits.clampPageOffset(effectivePage + 1, clampedLimit);
        return nextOffset > offset;
    }

    private static int lookupAudit(CommandContext<CommandSourceStack> ctx, String eventType,
                                   String playerName, int limit, Long sinceMinutes,
                                   String levelId, Double centerX, Double centerY, Double centerZ,
                                   Double radius) {
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());
        String filter = "type=" + eventType
                + (playerName == null ? "" : " player=" + playerName)
                + (levelId == null ? "" : " dimension=" + levelId)
                + (radius == null ? "" : " center=" + centerX + "," + centerY + "," + centerZ + " radius=" + radius)
                + " window=" + window.describe();
        AuditPageSession session = new AuditPageSession(UUID.randomUUID(), eventType, playerName, window,
                QueryLimits.clampLimit(limit), levelId, centerX, centerY, centerZ, radius,
                null, filter, System.currentTimeMillis());
        rememberPageSession(ctx.getSource(), session);
        return dispatchAuditPage(ctx.getSource(), "lookup audit", session, 1,
                ctx.getSource().getEntity() instanceof ServerPlayer);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String modVersion = runtimeInformation.modVersion();
        boolean griefLoggerLoaded = runtimeInformation.isModLoaded("grieflogger");
        boolean glDbAvailable = IngestionService.getInstance().getAdapter().isSupportedSchemaAvailable();
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
            long totalAuditEvents;
            try (var stmt = conn.createStatement(); var rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_audit_events")) {
                rs.next();
                totalAuditEvents = rs.getLong(1);
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
            String historicalImport;
            try (var stmt = conn.createStatement();
                 var rs = stmt.executeQuery("SELECT status, rows_imported, rows_opaque FROM ig_grieflogger_import_runs ORDER BY id DESC LIMIT 1")) {
                historicalImport = rs.next()
                        ? rs.getString("status") + " (" + rs.getLong("rows_imported")
                        + " rows, " + rs.getLong("rows_opaque") + " opaque)"
                        : "never run";
            }
            return QueryDispatcher.QueryOutput.found(List.of(
                    "[ItemGraph] correlation: groundBridgeWindow=" + ingestion.getCorrelationEngine().getWindowSeconds() + "s"
                            + " lastPass=" + (lastCorrelation == null ? "never run yet"
                            : (lastCorrelation.success() ? "OK" : "ERROR (" + lastCorrelation.errorMessage() + ")")
                            + " (" + lastCorrelation.observationsFinalised() + " evaluated, "
                            + lastCorrelation.edgesCreated() + " bridges inferred, " + lastCorrelation.deferred()
                            + " deferred, " + lastCorrelation.durationMs() + "ms)"),
                    "[ItemGraph] ingestion: running=" + ingestion.isRunning()
                            + " totalObservations=" + totalObservations
                            + " nativeAuditEvents=" + totalAuditEvents
                            + " checkpoints=" + checkpoints
                            + " historicalImport=" + historicalImport
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
                            + " transformations=" + internalObs.getTotalTransformations()
                            + " auditEvents=" + internalObs.getTotalAuditEvents(),
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

    private static int ingestHistory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!IngestionService.getInstance().requestHistoricalImportAsync()) {
            source.sendFailure(Component.literal("[ItemGraph] Historical GriefLogger import was not queued: the worker is stopped or an import is already queued."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] Read-only historical GriefLogger import queued on the background worker; check /ig status for completion."), false);
        return 1;
    }
}
