package com.itemgraph.command;

import com.itemgraph.audit.AuditReport;
import com.itemgraph.i18n.ItemGraphLanguage;
import com.itemgraph.audit.AuditService;
import com.itemgraph.correlation.CorrelationResult;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionResult;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.ingest.SourceCheckpoint;
import com.itemgraph.listener.ContainerInteractionTracker;
import com.itemgraph.metrics.OperationalMetrics;
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
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;
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
    /** Published GriefLogger lookup pages default to ten rows; native extensions use twenty. */
    private static final int GRIEFLOGGER_DEFAULT_LIMIT = 10;
    private static final int MAX_PAGE_SESSIONS_PER_PLAYER = 8;
    private static final Map<UUID, Map<UUID, AuditPageSession>> AUDIT_PAGE_SESSIONS = new ConcurrentHashMap<>();
    private static final long PAGE_SESSION_TTL_MS = 30L * 60L * 1_000L;
    private static final List<String> GRIEFLOGGER_ACTION_FILTER_VALUES = List.of(
            "place_block", "break_block", "interact_block", "kill_entity", "interact_entity",
            "join", "quit", "add_item", "remove_item", "drop_item", "pickup_item",
            "craft_item", "break_item", "consume_item", "throw_item", "shoot_item",
            "add_item_ender", "remove_item_ender");
    private static final Set<String> AUDIT_ONLY_LOOKUP_TYPES = Set.of(
            "CHAT_MESSAGE", "COMMAND_ATTEMPT", "COMMAND_EXECUTED");
    private static volatile RuntimeInformationPort runtimeInformation = new RuntimeInformationPort() {
        @Override public String modVersion() { return "unknown"; }
        @Override public boolean isModLoaded(String modId) { return false; }
    };

    record AuditPageSession(
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
            List<AuditEventQueryService.ExactPosition> exactPositions,
            AuditLookupFilters filters,
            String filterDescription,
            String originatingPermission,
            boolean requiresAuditPermission,
            long createdAtMs) {
        AuditPageSession(UUID sessionId, String eventType, String playerName, QueryWindow window, int limit,
                        String levelId, Double centerX, Double centerY, Double centerZ, Double radius,
                        List<AuditEventQueryService.ExactPosition> exactPositions, AuditLookupFilters filters,
                        String filterDescription, long createdAtMs) {
            this(sessionId, eventType, playerName, window, limit, levelId, centerX, centerY, centerZ, radius,
                    exactPositions, filters, filterDescription, ItemGraphPermissions.LOOKUP,
                    eventType == null || "all".equalsIgnoreCase(eventType)
                            || AUDIT_ONLY_LOOKUP_TYPES.contains(eventType.toUpperCase(java.util.Locale.ROOT)),
                    createdAtMs);
        }
    }

    private ItemGraphCommands() {}

    public static void setRuntimeInformation(RuntimeInformationPort information) {
        runtimeInformation = information == null ? runtimeInformation : information;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("itemgraph")
                        .requires(source -> ItemGraphPermissions.check(source, ItemGraphPermissions.COMMAND))
                        .executes(ItemGraphCommands::help)
                        .then(Commands.literal("help")
                                .executes(ItemGraphCommands::help)
                                .then(Commands.argument("topic", StringArgumentType.greedyString())
                                        .suggests(ItemGraphCommands::suggestHelpTopics)
                                        .executes(ItemGraphCommands::helpTopic)))
                        .then(Commands.literal("status").executes(ItemGraphCommands::status))
                        .then(Commands.literal("audit").requires(source -> ItemGraphPermissions.canUse(source, ItemGraphPermissions.AUDIT))
                                .executes(ItemGraphCommands::audit))
                        .then(buildStandalonePageCommand())
                        .then(buildLookupCommand())
                        .then(Commands.literal("ingest").requires(source -> ItemGraphPermissions.canUse(source, ItemGraphPermissions.INGEST))
                                .then(Commands.literal("now").executes(ItemGraphCommands::ingestNow))
                                .then(Commands.literal("history").requires(source -> ItemGraphPermissions.canUse(source, ItemGraphPermissions.IMPORT))
                                        .executes(ItemGraphCommands::ingestHistory)))

                        // /ig event <observationId>
                        .then(Commands.literal("event").requires(source -> ItemGraphPermissions.canUseAll(source, ItemGraphPermissions.EVENT, ItemGraphPermissions.AUDIT))
                                .then(Commands.argument("observationId", LongArgumentType.longArg(1))
                                        .executes(ItemGraphCommands::event)))

                        // /ig explain <edgeId>
                        .then(Commands.literal("explain").requires(source -> ItemGraphPermissions.canUseAll(source, ItemGraphPermissions.EXPLAIN, ItemGraphPermissions.AUDIT))
                                .then(Commands.argument("edgeId", LongArgumentType.longArg(1))
                                        .executes(ItemGraphCommands::explain)))

                        // /ig trace ...
                        .then(Commands.literal("trace").requires(source -> ItemGraphPermissions.canUseAll(source, ItemGraphPermissions.TRACE, ItemGraphPermissions.AUDIT))
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
                .requires(source -> ItemGraphPermissions.canUseAll(source, ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)
                        && source.getEntity() instanceof ServerPlayer)
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
                .requires(source -> ItemGraphPermissions.canUse(source, ItemGraphPermissions.INSPECT))
                .executes(ItemGraphCommands::inspectToggle)
                .then(Commands.literal("on").executes(ctx -> inspectSet(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> inspectSet(ctx, false)))
                .then(Commands.literal("status").executes(ItemGraphCommands::inspectStatus))
                .build();
        root.addChild(inspect);
        root.addChild(Commands.literal("goto")
                .then(Commands.argument("token", StringArgumentType.word())
                        .executes(ItemGraphCommands::navigateToResultLocation))
                .build());
        dispatcher.register(Commands.literal("ig")
                .requires(source -> ItemGraphPermissions.check(source, ItemGraphPermissions.COMMAND))
                .executes(ItemGraphCommands::help)
                .redirect(root));
    }

    private static int navigateToResultLocation(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        UUID token;
        try {
            token = UUID.fromString(StringArgumentType.getString(ctx, "token"));
        } catch (IllegalArgumentException invalidToken) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("goto.invalid", "[ItemGraph] This location link is invalid or expired.")));
            return 0;
        }
        if (!QueryDispatcher.consumeLocationGrant(source, token)) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("goto.rejected",
                    "[ItemGraph] This location link expired, belongs to another player, or its query permission was revoked.")));
            return 0;
        }
        return 1;
    }

    private static List<QueryDispatcher.LocationAction> locationActions(NodeRef... nodes) {
        return java.util.Arrays.stream(nodes)
                .filter(java.util.Objects::nonNull)
                .filter(node -> node.levelId() != null && node.x() != null && node.y() != null && node.z() != null)
                .filter(node -> Double.isFinite(node.x()) && Double.isFinite(node.y()) && Double.isFinite(node.z()))
                .map(node -> new QueryDispatcher.LocationAction(node.levelId(), node.x(), node.y(), node.z()))
                .distinct().limit(8).toList();
    }

    private static List<QueryDispatcher.LocationAction> locationsForHops(TraceResult result) {
        List<NodeRef> nodes = new java.util.ArrayList<>();
        result.hops().forEach(hop -> {
            nodes.add(hop.origin());
            nodes.add(hop.destination());
        });
        return locationActions(nodes.toArray(NodeRef[]::new));
    }

    private static QueryDispatcher.ChatHoverDetail chatHover(String evidenceClass, FingerprintRef item,
                                                               String eventKind, long timestampMs,
                                                               NodeRef origin, NodeRef destination) {
        return new QueryDispatcher.ChatHoverDetail(evidenceClass,
                item == null ? "item identity unavailable" : item.describe(),
                item == null || item.fingerprintHash() == null ? "not recorded" : item.fingerprintHash(),
                eventKind, QueryFormatter.formatTime(timestampMs),
                origin == null ? "endpoint not recorded" : origin.describe(),
                destination == null ? "endpoint not recorded" : destination.describe());
    }

    private static Map<Integer, QueryDispatcher.ChatHoverDetail> hoverEveryLine(
            int lineCount, QueryDispatcher.ChatHoverDetail detail) {
        Map<Integer, QueryDispatcher.ChatHoverDetail> hovers = new java.util.HashMap<>();
        for (int i = 0; i < lineCount; i++) hovers.put(i, detail);
        return Map.copyOf(hovers);
    }

    private static Map<Integer, QueryDispatcher.ChatHoverDetail> traceHovers(TraceResult result) {
        Map<Integer, QueryDispatcher.ChatHoverDetail> hovers = new java.util.HashMap<>();
        for (int i = 0; i < result.hops().size(); i++) {
            var hop = result.hops().get(i);
            String evidenceClass = hop.source() == com.itemgraph.query.TraceHop.Source.TRANSFORMATION
                    ? "OBSERVED / TRANSFORMATION"
                    : hop.detail() != null && hop.detail().contains("[source group ambiguous #")
                        ? "OBSERVED / AMBIGUOUS SOURCE GROUP"
                        : hop.kind() == com.itemgraph.query.TraceHop.Kind.OBSERVED ? "OBSERVED"
                        : "INFERRED conf=" + QueryFormatter.formatConfidence(hop.confidence());
            hovers.put(i + 2, chatHover(evidenceClass, hop.item(), hop.detail(), hop.timestampMs(),
                    hop.origin(), hop.destination()));
        }
        return Map.copyOf(hovers);
    }

    static Map<Integer, QueryDispatcher.ChatHoverDetail> explainSummaryHover(
            com.itemgraph.query.EdgeExplanation edge) {
        return Map.of(0, chatHover("INFERRED conf=" + QueryFormatter.formatConfidence(edge.confidence()),
                edge.fingerprint(), "inferred transfer", edge.timeStart(), edge.from(), edge.to()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildLookupCommand() {
        LiteralArgumentBuilder<CommandSourceStack> lookup = Commands.literal("lookup")
                .requires(source -> ItemGraphPermissions.canUse(source, ItemGraphPermissions.LOOKUP));

        // Event types are finite command literals rather than a custom Brigadier
        // argument. Vanilla can serialize these nodes to connected clients; a
        // custom ArgumentType without an ArgumentTypeInfo disconnects operators
        // when the command tree is sent. The direct greedy filter branch below
        // still owns dotted values such as radius.10 and routes mixed-case native
        // event tokens through the same normalized execution path.
        for (String eventType : AuditEventQueryService.EVENT_TYPES) {
            lookup.then(buildAuditLookupType(eventType, null));
            if (!eventType.equals(eventType.toLowerCase(java.util.Locale.ROOT))) {
                lookup.then(buildAuditLookupType(eventType.toLowerCase(java.util.Locale.ROOT), eventType));
            }
            if (!eventType.equals(eventType.toUpperCase(java.util.Locale.ROOT))) {
                lookup.then(buildAuditLookupType(eventType.toUpperCase(java.util.Locale.ROOT), null));
            }
            addMixedCaseAllLookupAliases(lookup, eventType, null);
        }

        var playerName = Commands.argument("playerName", StringArgumentType.string());
        for (String eventType : AuditEventQueryService.EVENT_TYPES) {
            playerName.then(buildAuditLookupType(eventType, "playerName"));
            if (!eventType.equals(eventType.toLowerCase(java.util.Locale.ROOT))) {
                playerName.then(buildAuditLookupType(eventType.toLowerCase(java.util.Locale.ROOT), "playerName"));
            }
            if (!eventType.equals(eventType.toUpperCase(java.util.Locale.ROOT))) {
                playerName.then(buildAuditLookupType(eventType.toUpperCase(java.util.Locale.ROOT), "playerName"));
            }
            addMixedCaseAllPlayerAliases(playerName, eventType);
        }
        playerName.then(buildCaseInsensitiveAuditLookupType("playerName"));
        lookup.then(Commands.literal("player").then(playerName));

        lookup.then(buildNearLookupCommand());
        lookup.then(buildPagedLookupCommand());
        lookup.then(Commands.literal("filters")
                .then(Commands.argument("filters", StringArgumentType.greedyString())
                        .executes(ctx -> lookupAuditFilters(ctx,
                                StringArgumentType.getString(ctx, "filters")))));
        // Keep the published GriefLogger spelling: `/gl lookup action.foo radius.10`.
        // The explicit `filters` literal remains as a discoverable ItemGraph extension,
        // while this greedy argument accepts the documented direct form without adding
        // any GriefLogger alias to the command root.
        lookup.then(Commands.argument("lookupFilters", StringArgumentType.greedyString())
                .suggests(ItemGraphCommands::suggestLookupFilters)
                .executes(ctx -> lookupAuditFilters(ctx,
                        StringArgumentType.getString(ctx, "lookupFilters"))));
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

    private static LiteralArgumentBuilder<CommandSourceStack> buildAuditLookupType(
            String commandEventType, String playerArgument) {
        String canonicalEventType = commandEventType.toUpperCase(java.util.Locale.ROOT);
        LiteralArgumentBuilder<CommandSourceStack> type = Commands.literal(commandEventType)
                .executes(ctx -> lookupAudit(ctx, canonicalEventType,
                        optionalPlayerArgument(ctx, playerArgument),
                        QueryLimits.DEFAULT_LIMIT, null));
        var limitArgument = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAudit(ctx, canonicalEventType,
                        optionalPlayerArgument(ctx, playerArgument),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limitArgument.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAudit(ctx, canonicalEventType,
                        optionalPlayerArgument(ctx, playerArgument),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        type.then(limitArgument);
        return type;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildStandalonePageCommand() {
        return Commands.literal("page").requires(source -> ItemGraphPermissions.canUse(source, ItemGraphPermissions.PAGE))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> lookupAuditSessionPage(ctx,
                                IntegerArgumentType.getInteger(ctx, "page"), null))
                        .then(Commands.argument("session", StringArgumentType.word())
                                .executes(ctx -> lookupAuditSessionPage(ctx,
                                        IntegerArgumentType.getInteger(ctx, "page"),
                                        StringArgumentType.getString(ctx, "session")))));
    }

    /** Vanilla StringArgumentType preserves arbitrary case without a custom network serializer. */
    private static RequiredArgumentBuilder<CommandSourceStack, String> buildCaseInsensitiveAuditLookupType(
            String playerArgument) {
        var type = Commands.argument("eventType", StringArgumentType.word())
                .executes(ctx -> lookupAudit(ctx, StringArgumentType.getString(ctx, "eventType"),
                        optionalPlayerArgument(ctx, playerArgument),
                        QueryLimits.DEFAULT_LIMIT, null));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAudit(ctx, StringArgumentType.getString(ctx, "eventType"),
                        optionalPlayerArgument(ctx, playerArgument),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAudit(ctx, StringArgumentType.getString(ctx, "eventType"),
                        optionalPlayerArgument(ctx, playerArgument),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        type.then(limit);
        return type;
    }

    private static String optionalPlayerArgument(CommandContext<CommandSourceStack> ctx, String argumentName) {
        if (argumentName == null) {
            return null;
        }
        try {
            return StringArgumentType.getString(ctx, argumentName);
        } catch (IllegalArgumentException notOnThisCommandBranch) {
            return null;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildPagedLookupCommand() {
        var page = Commands.argument("page", IntegerArgumentType.integer(1));
        for (String eventType : AuditEventQueryService.EVENT_TYPES) {
            page.then(buildPagedAuditType(eventType));
            if (!eventType.equals(eventType.toLowerCase(java.util.Locale.ROOT))) {
                page.then(buildPagedAuditType(eventType.toLowerCase(java.util.Locale.ROOT)));
            }
            if (!eventType.equals(eventType.toUpperCase(java.util.Locale.ROOT))) {
                page.then(buildPagedAuditType(eventType.toUpperCase(java.util.Locale.ROOT)));
            }
            addMixedCaseAllPagedAliases(page, eventType);
        }
        page.then(buildCaseInsensitivePagedAuditType());
        return Commands.literal("page").then(page);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> buildCaseInsensitivePagedAuditType() {
        var eventType = Commands.argument("eventType", StringArgumentType.word())
                .executes(ctx -> lookupAuditPage(ctx, StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "page"), QueryLimits.DEFAULT_LIMIT, null));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAuditPage(ctx, StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAuditPage(ctx, StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        eventType.then(limit);
        return eventType;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildPagedAuditType(String commandEventType) {
        String canonicalEventType = commandEventType.toUpperCase(java.util.Locale.ROOT);
        var eventType = Commands.literal(commandEventType)
                .executes(ctx -> lookupAuditPage(ctx, canonicalEventType,
                        IntegerArgumentType.getInteger(ctx, "page"),
                        QueryLimits.DEFAULT_LIMIT, null));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAuditPage(ctx, canonicalEventType,
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAuditPage(ctx, canonicalEventType,
                        IntegerArgumentType.getInteger(ctx, "page"),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        eventType.then(limit);
        return eventType;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNearLookupCommand() {
        var radius = Commands.argument("radius", DoubleArgumentType.doubleArg(0.1));
        // Attach the event-type literals before the radius node is linked into
        // the coordinate chain; Brigadier copies child nodes when linking them.
        for (String eventType : AuditEventQueryService.EVENT_TYPES) {
            radius.then(buildNearAuditType(eventType));
            if (!eventType.equals(eventType.toLowerCase(java.util.Locale.ROOT))) {
                radius.then(buildNearAuditType(eventType.toLowerCase(java.util.Locale.ROOT)));
            }
            if (!eventType.equals(eventType.toUpperCase(java.util.Locale.ROOT))) {
                radius.then(buildNearAuditType(eventType.toUpperCase(java.util.Locale.ROOT)));
            }
            addMixedCaseAllNearAliases(radius, eventType);
        }
        radius.then(buildCaseInsensitiveNearAuditType());
        var z = Commands.argument("z", DoubleArgumentType.doubleArg());
        z.then(radius);
        var y = Commands.argument("y", DoubleArgumentType.doubleArg());
        y.then(z);
        var x = Commands.argument("x", DoubleArgumentType.doubleArg());
        x.then(y);
        var dimension = Commands.argument("dimension", ResourceLocationArgument.id());
        dimension.then(x);
        return Commands.literal("near").then(dimension);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> buildCaseInsensitiveNearAuditType() {
        var eventType = Commands.argument("eventType", StringArgumentType.word())
                .executes(ctx -> lookupAuditNear(ctx, StringArgumentType.getString(ctx, "eventType"),
                        QueryLimits.DEFAULT_LIMIT, null));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAuditNear(ctx, StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAuditNear(ctx, StringArgumentType.getString(ctx, "eventType"),
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        eventType.then(limit);
        return eventType;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildNearAuditType(String commandEventType) {
        String canonicalEventType = commandEventType.toUpperCase(java.util.Locale.ROOT);
        var eventType = Commands.literal(commandEventType)
                .executes(ctx -> lookupAuditNear(ctx, canonicalEventType,
                        QueryLimits.DEFAULT_LIMIT, null));
        var limit = Commands.argument("limit", IntegerArgumentType.integer(1))
                .executes(ctx -> lookupAuditNear(ctx, canonicalEventType,
                        IntegerArgumentType.getInteger(ctx, "limit"), null));
        limit.then(Commands.argument("sinceMinutes", LongArgumentType.longArg(1))
                .executes(ctx -> lookupAuditNear(ctx, canonicalEventType,
                        IntegerArgumentType.getInteger(ctx, "limit"),
                        LongArgumentType.getLong(ctx, "sinceMinutes"))));
        eventType.then(limit);
        return eventType;
    }

    private static void addMixedCaseAllLookupAliases(
            LiteralArgumentBuilder<CommandSourceStack> parent, String eventType, String playerArgument) {
        if (!"all".equals(eventType)) {
            return;
        }
        for (String alias : List.of("All", "aLl", "alL", "ALl", "AlL", "aLL")) {
            parent.then(buildAuditLookupType(alias, playerArgument));
        }
    }

    private static void addMixedCaseAllPlayerAliases(
            com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> parent,
            String eventType) {
        if (!"all".equals(eventType)) {
            return;
        }
        for (String alias : List.of("All", "aLl", "alL", "ALl", "AlL", "aLL")) {
            parent.then(buildAuditLookupType(alias, "playerName"));
        }
    }

    private static void addMixedCaseAllPagedAliases(
            com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> parent,
            String eventType) {
        if (!"all".equals(eventType)) {
            return;
        }
        for (String alias : List.of("All", "aLl", "alL", "ALl", "AlL", "aLL")) {
            parent.then(buildPagedAuditType(alias));
        }
    }

    private static void addMixedCaseAllNearAliases(
            com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> parent,
            String eventType) {
        if (!"all".equals(eventType)) {
            return;
        }
        for (String alias : List.of("All", "aLl", "alL", "ALl", "AlL", "aLL")) {
            parent.then(buildNearAuditType(alias));
        }
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        CommandHelp.overviewLines().forEach(line ->
                source.sendSuccess(() -> Component.literal(ItemGraphLanguage.sourceText(line)), false));
        return 1;
    }

    private static int helpTopic(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String topic = StringArgumentType.getString(ctx, "topic");
        List<String> lines = CommandHelp.topicLines(topic);
        if (lines == null) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("help.unknown_topic",
                    "[ItemGraph] Unknown help topic ''{0}''. Valid topics: {1}", topic, CommandHelp.validTopicsText())));
            return 0;
        }
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(ItemGraphLanguage.sourceText(line)), false));
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

    /**
     * Command argument trees are sent to clients over the vanilla protocol. The
     * finite event-type vocabulary is registered as literal nodes so no custom
     * Brigadier network serializer is required; dotted GriefLogger filters stay
     * on their separate greedy branch.
     */
    private static String normalizeAuditEventType(CommandSourceStack source, String eventType) {
        String canonical = canonicalAuditEventType(eventType);
        if (canonical != null) {
            return canonical;
        }
        source.sendFailure(Component.literal(
                ItemGraphLanguage.text("lookup.unknown_event_type",
                        "[ItemGraph] Unknown audit event type '{0}'. Valid values: {1}", eventType,
                        String.join(", ", AuditEventQueryService.EVENT_TYPES))));
        return null;
    }

    private static String canonicalAuditEventType(String eventType) {
        for (String candidate : AuditEventQueryService.EVENT_TYPES) {
            if (candidate.equalsIgnoreCase(eventType)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Completes the published GriefLogger lookup vocabulary on the direct greedy form.
     * Suggestions replace only the token currently being typed, preserving earlier
     * filters such as {@code action.break_block }.
     */
    private static CompletableFuture<Suggestions> suggestLookupFilters(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        int tokenStart = remaining.lastIndexOf(' ') + 1;
        String rawToken = remaining.substring(tokenStart);
        boolean quoted = rawToken.startsWith("\"");
        SuggestionsBuilder tokenBuilder = builder.createOffset(
                builder.getStart() + tokenStart + (quoted ? 1 : 0));
        String token = (quoted ? rawToken.substring(1) : rawToken)
                .toLowerCase(java.util.Locale.ROOT);
        Set<String> usedFilters = usedLookupFilters(remaining.substring(0, tokenStart));
        if (usedFilters.size() >= 5) {
            return CompletableFuture.completedFuture(tokenBuilder.build());
        }
        if (!token.contains(".")) {
            boolean hasItemFilter = usedFilters.contains("include") || usedFilters.contains("exclude");
            List<String> suggestions = new java.util.ArrayList<>();
            for (String filter : List.of("action", "user", "include", "exclude", "time", "radius")) {
                if (usedFilters.contains(filter)
                        || hasItemFilter && (filter.equals("include") || filter.equals("exclude"))) {
                    continue;
                }
                suggestions.add(filter + ".");
                suggestions.add(filter.substring(0, 1) + ".");
            }
            return SharedSuggestionProvider.suggest(suggestions, tokenBuilder);
        }
        String name = token.substring(0, token.indexOf('.'));
        String canonicalName = canonicalLookupFilter(name);
        if (canonicalName == null || usedFilters.contains(canonicalName)
                || (canonicalName.equals("include") && usedFilters.contains("exclude"))
                || (canonicalName.equals("exclude") && usedFilters.contains("include"))) {
            return CompletableFuture.completedFuture(tokenBuilder.build());
        }

        List<String> values = switch (canonicalName) {
            case "action" -> lookupActionSuggestions();
            case "include", "exclude" -> BuiltInRegistries.ITEM.keySet().stream()
                    .map(ResourceLocation::toString)
                    .toList();
            case "time" -> List.of("5m", "1h", "1d", "1y");
            case "radius" -> List.of("5", "10", "50");
            default -> List.of();
        };
        if (canonicalName.equals("user")) {
            return HistoricalUsernameSuggestions.values(
                            List.copyOf(ctx.getSource().getOnlinePlayerNames()))
                    .thenCompose(names -> suggestFilterValues(tokenBuilder, token, names));
        }
        return suggestFilterValues(tokenBuilder, token, values);
    }

    private static List<String> lookupActionSuggestions() {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>(GRIEFLOGGER_ACTION_FILTER_VALUES);
        UnifiedEvidenceQueryService.ACTION_TYPES.stream()
                .filter(value -> !"all".equalsIgnoreCase(value))
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .forEach(values::add);
        return List.copyOf(values);
    }

    private static CompletableFuture<Suggestions> suggestFilterValues(
            SuggestionsBuilder tokenBuilder, String token, List<String> values) {
        int valueStart = token.indexOf('.') + 1;
        String existing = token.substring(valueStart);
        int lastComma = existing.lastIndexOf(',');
        String previousValues = lastComma < 0 ? "" : existing.substring(0, lastComma + 1);
        Set<String> alreadyEntered = java.util.Arrays.stream(previousValues.split(","))
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        int suggestionStart = tokenBuilder.getStart() + valueStart + previousValues.length();
        SuggestionsBuilder valueBuilder = tokenBuilder.createOffset(suggestionStart);
        List<String> candidates = values.stream()
                .filter(value -> !alreadyEntered.contains(value.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        return SharedSuggestionProvider.suggest(candidates, valueBuilder);
    }

    private static Set<String> usedLookupFilters(String completedTokens) {
        Set<String> used = new java.util.HashSet<>();
        for (String token : completedTokens.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
                token = token.substring(1, token.length() - 1);
            }
            int separator = token.indexOf('.');
            if (separator > 0) {
                String canonicalName = canonicalLookupFilter(token.substring(0, separator));
                if (canonicalName != null) {
                    used.add(canonicalName);
                }
            }
        }
        return used;
    }

    private static String canonicalLookupFilter(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "action", "a" -> "action";
            case "user", "u" -> "user";
            case "include", "i" -> "include";
            case "exclude", "e" -> "exclude";
            case "time", "t" -> "time";
            case "radius", "r" -> "radius";
            default -> null;
        };
    }

    /** /ig event <observationId> - one raw observation, labelled OBSERVED. */
    private static int event(CommandContext<CommandSourceStack> ctx) {
        long observationId = LongArgumentType.getLong(ctx, "observationId");
        return QueryDispatcher.dispatch(ctx.getSource(), List.of(ItemGraphPermissions.EVENT, ItemGraphPermissions.AUDIT), "event", conn ->
                EVENT_QUERIES.findObservation(conn, observationId)
                        .map(obs -> {
                            List<String> lines = QueryFormatter.formatEvent(obs);
                            return QueryDispatcher.QueryOutput.found(lines,
                                    locationActions(obs.origin(), obs.destination()), List.of(),
                                    hoverEveryLine(lines.size(), chatHover("OBSERVED", obs.fingerprint(),
                                            obs.actionType(), obs.timestampMs(), obs.origin(), obs.destination())));
                        })
                        .orElseGet(() -> QueryDispatcher.QueryOutput.notFound(
                                QueryFormatter.eventNotFound(observationId))));
    }

    /** /ig explain <edgeId> - one inferred edge plus every observation it cites. */
    private static int explain(CommandContext<CommandSourceStack> ctx) {
        long edgeId = LongArgumentType.getLong(ctx, "edgeId");
        return QueryDispatcher.dispatch(ctx.getSource(), List.of(ItemGraphPermissions.EXPLAIN, ItemGraphPermissions.AUDIT), "explain", conn ->
                EXPLAIN_QUERIES.findEdge(conn, edgeId)
                        .map(edge -> {
                            List<NodeRef> nodes = new java.util.ArrayList<>();
                            nodes.add(edge.from());
                            nodes.add(edge.to());
                            edge.evidence().forEach(obs -> {
                                nodes.add(obs.origin());
                                nodes.add(obs.destination());
                            });
                            List<String> lines = QueryFormatter.formatExplain(edge);
                            return QueryDispatcher.QueryOutput.found(lines,
                                    locationActions(nodes.toArray(NodeRef[]::new)), List.of(),
                                    explainSummaryHover(edge));
                        })
                        .orElseGet(() -> QueryDispatcher.QueryOutput.notFound(
                                QueryFormatter.explainNotFound(edgeId))));
    }

    /** /ig trace item <query> [limit] [sinceMinutes] */
    private static int traceItem(CommandContext<CommandSourceStack> ctx, int limit, Long sinceMinutes) {
        String query = StringArgumentType.getString(ctx, "itemQuery");
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());

        return QueryDispatcher.dispatch(ctx.getSource(), List.of(ItemGraphPermissions.TRACE, ItemGraphPermissions.AUDIT), "trace item", conn -> {
            List<FingerprintRef> candidates = TRACE_QUERIES.resolveFingerprints(conn, query);
            if (candidates.isEmpty()) {
                return QueryDispatcher.QueryOutput.notFound(QueryFormatter.traceNoSuchFingerprint(query));
            }
            if (candidates.size() > 1) {
                return QueryDispatcher.QueryOutput.found(QueryFormatter.formatFingerprintCandidates(query, candidates));
            }
            FingerprintRef fp = candidates.get(0);
            TraceResult result = TRACE_QUERIES.trace(conn, fp.id(), limit, window);
            List<String> lines = QueryFormatter.formatTrace(result);
            return QueryDispatcher.QueryOutput.found(lines, locationsForHops(result), List.of(),
                    traceHovers(result));
        });
    }

    /** /ig trace player <player> [limit] [sinceMinutes] */
    private static int tracePlayer(CommandContext<CommandSourceStack> ctx, int limit, Long sinceMinutes) {
        String player = StringArgumentType.getString(ctx, "player");
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());

        return QueryDispatcher.dispatch(ctx.getSource(), List.of(ItemGraphPermissions.TRACE, ItemGraphPermissions.AUDIT), "trace player", conn -> {
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
            List<String> lines = QueryFormatter.formatTrace(result);
            return QueryDispatcher.QueryOutput.found(lines, locationsForHops(result), List.of(),
                    traceHovers(result));
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

        return QueryDispatcher.dispatch(ctx.getSource(), List.of(ItemGraphPermissions.TRACE, ItemGraphPermissions.AUDIT), "trace container", conn -> {
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
            List<String> lines = QueryFormatter.formatTrace(result);
            return QueryDispatcher.QueryOutput.found(lines, locationsForHops(result), List.of(),
                    traceHovers(result));
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
                ? ItemGraphLanguage.text("inspect.enabled_full", "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history; use /ig inspect off to disable.")
                : ItemGraphLanguage.text("inspect.disabled", "[ItemGraph] Inspection disabled.")), false);
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
                        ? ItemGraphLanguage.text("inspect.enabled", "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history.")
                        : ItemGraphLanguage.text("inspect.already_enabled", "[ItemGraph] Inspection is already enabled.")
                : changed
                        ? ItemGraphLanguage.text("inspect.disabled", "[ItemGraph] Inspection disabled.")
                        : ItemGraphLanguage.text("inspect.already_disabled", "[ItemGraph] Inspection is already disabled.")), false);
        return 1;
    }

    /** /ig inspect status - report the caller's current mode without changing it. */
    private static int inspectStatus(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = inspectionPlayer(ctx.getSource());
        if (player == null) {
            return 0;
        }
        boolean enabled = InspectionService.getInstance().isEnabled(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal(ItemGraphLanguage.text("inspect.status",
                "[ItemGraph] Inspection is {0}.", ItemGraphLanguage.text(enabled ? "inspect.enabled_word" : "inspect.disabled_word",
                        enabled ? "enabled" : "disabled"))), false);
        return 1;
    }

    private static ServerPlayer inspectionPlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return player;
        }
        source.sendFailure(Component.literal(ItemGraphLanguage.text("inspect.requires_player", "[ItemGraph] Inspection requires a player.")));
        return null;
    }

    /** /ig audit - database invariant verification */
    private static int audit(CommandContext<CommandSourceStack> ctx) {
        return QueryDispatcher.dispatch(ctx.getSource(), ItemGraphPermissions.AUDIT, "audit", conn -> {
            AuditReport report = AUDIT_SERVICE.audit(conn);
            return QueryDispatcher.QueryOutput.found(QueryFormatter.formatAudit(report));
        });
    }

    /** /ig lookup <eventType> [limit] [sinceMinutes] and /ig lookup player ... */
    private static int lookupAudit(CommandContext<CommandSourceStack> ctx, String eventType,
                                   String playerName, int limit, Long sinceMinutes) {
        eventType = normalizeAuditEventType(ctx.getSource(), eventType);
        if (eventType == null) {
            return 0;
        }
        return lookupAudit(ctx, eventType, playerName, limit, sinceMinutes,
                null, null, null, null, null);
    }

    private static int lookupAuditNear(CommandContext<CommandSourceStack> ctx, String eventType,
                                       int limit, Long sinceMinutes) {
        eventType = normalizeAuditEventType(ctx.getSource(), eventType);
        if (eventType == null) {
            return 0;
        }
        return lookupAudit(ctx, eventType, null, limit, sinceMinutes,
                ResourceLocationArgument.getId(ctx, "dimension").toString(),
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
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !ItemGraphPermissions.canUse(source, ItemGraphPermissions.INSPECT)) {
            source.sendFailure(Component.literal(
                    ItemGraphLanguage.text("inspect.block_permission", "[ItemGraph] Block inspection requires a player with itemgraph.command and itemgraph.command.inspect.")));
            return 0;
        }
        List<AuditEventQueryService.ExactPosition> positions = BlockInspectionTargets.resolve(
                player.level(), new BlockPos(x, y, z));
        AuditPageSession session = new AuditPageSession(
                UUID.randomUUID(), "all", null, QueryWindow.unbounded(), QueryLimits.DEFAULT_LIMIT,
                dimension, (double) x, (double) y, (double) z, 0.0, positions, null,
                "inspect block=" + dimension + " [" + x + "," + y + "," + z + "]",
                ItemGraphPermissions.INSPECT, true, System.currentTimeMillis());
        rememberPageSession(source, session);
        int accepted = dispatchAuditPage(source, ItemGraphPermissions.INSPECT, "inspect block", session, 1, true);
        if (accepted == 0) {
            forgetPageSession(source, session.sessionId());
        }
        return accepted;
    }

    /** Executes the GriefLogger-compatible name.value filter form around the issuing player. */
    private static int lookupAuditFilters(CommandContext<CommandSourceStack> ctx, String expression) {
        CommandSourceStack source = ctx.getSource();
        int nativeLookup = tryNativeLookupExpression(ctx, expression);
        if (nativeLookup != Integer.MIN_VALUE) {
            return nativeLookup;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal(
                    ItemGraphLanguage.text("lookup.player_required", "[ItemGraph] Filtered lookup requires a player with itemgraph.command and itemgraph.command.lookup so radius can use the current position.")));
            return 0;
        }
        AuditLookupFilters filters;
        try {
            filters = AuditLookupFilters.parse(expression, System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("lookup.invalid_filter",
                    "[ItemGraph] Invalid lookup filter: {0}", e.getMessage())));
            return 0;
        }
        boolean requiresAudit = requiresAuditPermission(filters.eventTypes());
        if (requiresAudit && !ItemGraphPermissions.canUse(source, ItemGraphPermissions.AUDIT)) {
            source.sendFailure(Component.literal(
                    ItemGraphLanguage.text("lookup.audit_required", "[ItemGraph] This lookup can include protected audit events and requires itemgraph.audit.")));
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
                UUID.randomUUID(), null, null, filters.window(), GRIEFLOGGER_DEFAULT_LIMIT, levelId,
                centerX, centerY, centerZ, filters.radiusBlocks(), null, filters,
                filterDescription, ItemGraphPermissions.LOOKUP, requiresAudit, System.currentTimeMillis());
        rememberPageSession(source, session);
        return dispatchAuditPage(source, ItemGraphPermissions.LOOKUP, "lookup filtered audit", session, 1, true);
    }

    /** Handles mixed-case native event syntax routed through the greedy filter branch. */
    private static int tryNativeLookupExpression(CommandContext<CommandSourceStack> ctx, String expression) {
        if (expression == null || expression.isBlank()) {
            return Integer.MIN_VALUE;
        }
        String[] tokens = expression.trim().split("\\s+");
        if (tokens.length < 1 || tokens.length > 3 || tokens[0].contains(".")) {
            return Integer.MIN_VALUE;
        }
        String eventType = canonicalAuditEventType(tokens[0]);
        if (eventType == null) {
            return Integer.MIN_VALUE;
        }
        int limit = QueryLimits.DEFAULT_LIMIT;
        Long sinceMinutes = null;
        try {
            if (tokens.length >= 2) {
                limit = Integer.parseInt(tokens[1]);
                if (limit < 1) {
                    return Integer.MIN_VALUE;
                }
            }
            if (tokens.length == 3) {
                sinceMinutes = Long.parseLong(tokens[2]);
                if (sinceMinutes < 1) {
                    return Integer.MIN_VALUE;
                }
            }
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
        return lookupAudit(ctx, eventType, null, limit, sinceMinutes);
    }

    private static int lookupHistoricalProvenance(CommandContext<CommandSourceStack> ctx, int requestedLimit) {
        CommandSourceStack source = ctx.getSource();
        String sourceSha256 = StringArgumentType.getString(ctx, "sourceSha256");
        String table = StringArgumentType.getString(ctx, "table");
        String sourceKey = StringArgumentType.getString(ctx, "sourceKey");
        int limit = QueryLimits.clampLimit(requestedLimit);
        List<String> permissions = isSensitiveHistoricalTable(table)
                ? List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT)
                : List.of(ItemGraphPermissions.LOOKUP);
        return QueryDispatcher.dispatch(source, permissions, "lookup provenance", conn -> {
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
        eventType = normalizeAuditEventType(ctx.getSource(), eventType);
        if (eventType == null) {
            return 0;
        }
        int clampedLimit = QueryLimits.clampLimit(limit);
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());
        AuditPageSession session = new AuditPageSession(
                UUID.randomUUID(), eventType, null, window, clampedLimit, null,
                null, null, null, null, null,
                null,
                "type=" + eventType + " window=" + window.describe(),
                ItemGraphPermissions.LOOKUP, requiresAuditPermission(eventType), System.currentTimeMillis());
        rememberPageSession(ctx.getSource(), session);
        return dispatchAuditPage(ctx.getSource(), ItemGraphPermissions.LOOKUP, "lookup audit", session, page,
                ctx.getSource().getEntity() instanceof ServerPlayer);
    }

    private static int lookupAuditSessionPage(CommandContext<CommandSourceStack> ctx, int page,
                                              String sessionToken) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer)) {
            source.sendFailure(Component.literal(
                    ItemGraphLanguage.text("page.player_required", "[ItemGraph] /ig page requires a player with an active lookup session.")));
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
                source.sendFailure(Component.literal(ItemGraphLanguage.text("page.invalid_session", "[ItemGraph] Invalid lookup page session.")));
                return 0;
            }
            session = pageSession(source, sessionId);
        }
        if (session == null) {
            source.sendFailure(Component.literal(
                    ItemGraphLanguage.text("page.no_session", "[ItemGraph] No active lookup page session. Run /ig lookup first.")));
            return 0;
        }
        return dispatchAuditPage(source, ItemGraphPermissions.PAGE, "lookup page", session, page, true);
    }

    private static int dispatchAuditPage(CommandSourceStack source, String permissionNode, String label,
                                         AuditPageSession session, int page,
                                         boolean standaloneCommand) {
        int clampedLimit = QueryLimits.clampLimit(session.limit());
        int requestedPage = Math.max(1, page);
        int offset = QueryLimits.clampPageOffset(requestedPage, clampedLimit);
        int effectivePage = offset / clampedLimit + 1;
        String filter = session.filterDescription() + " page=" + effectivePage
                + " limit=" + clampedLimit
                + (effectivePage == requestedPage ? "" : " requestedPage=" + requestedPage + " offset=" + offset);
        List<String> requiredPermissions = new java.util.ArrayList<>();
        if (ItemGraphPermissions.PAGE.equals(permissionNode)) {
            requiredPermissions.addAll(pagePermissionsFor(session));
        } else {
            requiredPermissions.add(permissionNode);
            if (session.requiresAuditPermission()) {
                requiredPermissions.add(ItemGraphPermissions.AUDIT);
            }
        }
        return QueryDispatcher.dispatch(source, List.copyOf(requiredPermissions), label, conn -> {
            List<String> lines;
            int returnedRows;
            List<QueryDispatcher.LocationAction> locations = new java.util.ArrayList<>();
            Map<Integer, QueryDispatcher.ChatHoverDetail> hovers = new java.util.HashMap<>();
            List<String> locationPermissions = locationPermissionNodes(session, permissionNode);
            if (session.filters() != null) {
                List<UnifiedEvidenceDetail> evidence = UNIFIED_EVIDENCE_QUERIES.findFiltered(
                        conn, session.filters(), session.levelId(),
                        session.centerX(), session.centerY(), session.centerZ(), clampedLimit, offset);
                lines = QueryFormatter.formatUnifiedEvidence(evidence, filter);
                returnedRows = evidence.size();
                addUnifiedPresentation(evidence, locationPermissions, locations, hovers);
            } else if (session.exactPositions() != null && !session.exactPositions().isEmpty()) {
                List<UnifiedEvidenceDetail> evidence = UNIFIED_EVIDENCE_QUERIES.findExact(
                        conn, session.levelId(), session.exactPositions(), clampedLimit, offset);
                lines = QueryFormatter.formatUnifiedEvidence(evidence, filter);
                returnedRows = evidence.size();
                addUnifiedPresentation(evidence, locationPermissions, locations, hovers);
            } else {
                List<AuditEventDetail> events = AUDIT_EVENT_QUERIES.find(
                                conn, session.eventType(), session.playerName(),
                                session.window(), session.levelId(), session.centerX(), session.centerY(),
                                session.centerZ(), session.radius(), clampedLimit, offset);
                lines = QueryFormatter.formatAuditEvents(events, filter);
                returnedRows = events.size();
                addAuditPresentation(events, locationPermissions, locations, hovers);
            }
            boolean hasNextPage = standaloneCommand
                    && canCheckNextAuditPage(effectivePage, clampedLimit, offset, returnedRows)
                    && hasNextAuditPage(conn, session, effectivePage, clampedLimit, offset);
            return QueryDispatcher.QueryOutput.found(lines, locations,
                    auditPageActions(session, effectivePage, hasNextPage, standaloneCommand), hovers);
        });
    }

    private static void addAuditPresentation(List<AuditEventDetail> events, List<String> permissions,
                                             List<QueryDispatcher.LocationAction> locations,
                                             Map<Integer, QueryDispatcher.ChatHoverDetail> hovers) {
        for (int i = 0; i < events.size(); i++) {
            AuditEventDetail event = events.get(i);
            String location = event.levelName() == null ? "dimension not recorded"
                    : event.levelName() + " " + coordinateSummary(event.x(), event.y(), event.z());
            hovers.put(i + 1, new QueryDispatcher.ChatHoverDetail("OBSERVED",
                    "audit event #" + event.id(), "not recorded", event.eventType(),
                    QueryFormatter.formatTime(event.timestampMs()), location, "destination not recorded"));
            addLocation(event.levelName(), event.x(), event.y(), event.z(), permissions, locations);
        }
    }

    private static void addUnifiedPresentation(List<UnifiedEvidenceDetail> evidence, List<String> permissions,
                                               List<QueryDispatcher.LocationAction> locations,
                                               Map<Integer, QueryDispatcher.ChatHoverDetail> hovers) {
        for (int i = 0; i < evidence.size(); i++) {
            UnifiedEvidenceDetail row = evidence.get(i);
            String location = row.levelName() == null ? "dimension not recorded"
                    : row.x() == null || row.y() == null || row.z() == null ? row.levelName()
                    : row.levelName() + " " + coordinateSummary(row.x(), row.y(), row.z());
            hovers.put(i + 1, new QueryDispatcher.ChatHoverDetail(row.evidenceClass(),
                    row.source() + " " + row.evidenceId(), "not recorded", row.actionType(),
                    QueryFormatter.formatTime(row.timestampMs()), location, "destination not recorded"));
            if (row.x() != null && row.y() != null && row.z() != null) {
                addLocation(row.levelName(), row.x(), row.y(), row.z(), permissions, locations);
            }
        }
    }

    private static void addLocation(String dimension, double x, double y, double z, List<String> permissions,
                                    List<QueryDispatcher.LocationAction> locations) {
        if (dimension == null || dimension.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)) return;
        try {
            locations.add(new QueryDispatcher.LocationAction(dimension, x, y, z, permissions));
        } catch (IllegalArgumentException ignored) {
            // Invalid typed coordinates are omitted; formatted query text remains available.
        }
    }

    private static String coordinateSummary(double x, double y, double z) {
        return "[" + x + ", " + y + ", " + z + "]";
    }

    static List<QueryDispatcher.QueryAction> auditPageActions(
            AuditPageSession session, int effectivePage, boolean hasNextPage, boolean standaloneCommand) {
        if (!standaloneCommand) {
            return List.of();
        }
        List<QueryDispatcher.QueryAction> actions = new java.util.ArrayList<>(2);
        if (effectivePage > 1) {
            actions.add(new QueryDispatcher.QueryAction("Previous",
                    standalonePageCommand(effectivePage - 1, session.sessionId())));
        }
        if (hasNextPage) {
            actions.add(new QueryDispatcher.QueryAction("Next",
                    standalonePageCommand(effectivePage + 1, session.sessionId())));
        }
        return List.copyOf(actions);
    }

    static boolean hasNextAuditPage(java.sql.Connection conn, AuditPageSession session,
                                    int effectivePage, int limit, int offset) throws java.sql.SQLException {
        int nextOffset = QueryLimits.clampPageOffset(effectivePage + 1, limit);
        if (session.filters() != null) {
            return !UNIFIED_EVIDENCE_QUERIES.findFiltered(conn, session.filters(), session.levelId(),
                    session.centerX(), session.centerY(), session.centerZ(), 1, nextOffset).isEmpty();
        }
        if (session.exactPositions() != null && !session.exactPositions().isEmpty()) {
            return !UNIFIED_EVIDENCE_QUERIES.findExact(conn, session.levelId(),
                    session.exactPositions(), 1, nextOffset).isEmpty();
        }
        return !AUDIT_EVENT_QUERIES.find(conn, session.eventType(), session.playerName(),
                session.window(), session.levelId(), session.centerX(), session.centerY(),
                session.centerZ(), session.radius(), 1, nextOffset).isEmpty();
    }

    static boolean requiresAuditPermission(String eventType) {
        if (eventType == null || eventType.isBlank() || "all".equalsIgnoreCase(eventType)) {
            return true;
        }
        String normalized = eventType.toUpperCase(java.util.Locale.ROOT);
        return AUDIT_ONLY_LOOKUP_TYPES.contains(normalized)
                || normalized.startsWith("ADMIN_ITEM_COMMAND_")
                || normalized.startsWith("CREATIVE_SLOT_")
                || normalized.startsWith("CREATIVE_BLOCK_")
                || normalized.startsWith("ADMIN_ITEM_")
                || normalized.startsWith("CREATIVE_ITEM_");
    }

    static boolean requiresAuditPermission(List<String> eventTypes) {
        return eventTypes == null || eventTypes.isEmpty()
                || eventTypes.stream().anyMatch(ItemGraphCommands::requiresAuditPermission);
    }

    static List<String> lookupPermissionsForType(String eventType) {
        return requiresAuditPermission(eventType)
                ? List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT)
                : List.of(ItemGraphPermissions.LOOKUP);
    }

    static List<String> pagePermissionsFor(AuditPageSession session) {
        List<String> permissions = new java.util.ArrayList<>(List.of(
                ItemGraphPermissions.PAGE, session.originatingPermission()));
        if (session.requiresAuditPermission()) {
            permissions.add(ItemGraphPermissions.AUDIT);
        }
        return List.copyOf(permissions);
    }

    static List<String> locationPermissionNodes(AuditPageSession session, String permissionNode) {
        List<String> permissions = new java.util.ArrayList<>();
        permissions.add(session.originatingPermission());
        if (ItemGraphPermissions.PAGE.equals(permissionNode)) permissions.add(ItemGraphPermissions.PAGE);
        if (session.requiresAuditPermission()) permissions.add(ItemGraphPermissions.AUDIT);
        return List.copyOf(permissions);
    }

    private static boolean isSensitiveHistoricalTable(String table) {
        return table != null && Set.of("chats", "commands").contains(table.toLowerCase(java.util.Locale.ROOT));
    }

    static void rememberPageSession(CommandSourceStack source, AuditPageSession session) {
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

    private static void forgetPageSession(CommandSourceStack source, UUID sessionId) {
        if (source.getEntity() instanceof ServerPlayer player) {
            Map<UUID, AuditPageSession> sessions = AUDIT_PAGE_SESSIONS.get(player.getUUID());
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    AUDIT_PAGE_SESSIONS.remove(player.getUUID(), sessions);
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
        sessions.values().removeIf(session -> pageSessionExpired(session.createdAtMs(), now));
        AuditPageSession active = sessions.values().stream()
                .max(java.util.Comparator.comparingLong(AuditPageSession::createdAtMs))
                .orElse(null);
        if (sessions.isEmpty()) {
            AUDIT_PAGE_SESSIONS.remove(playerId, sessions);
        }
        return active;
    }

    static AuditPageSession pageSession(CommandSourceStack source, UUID sessionId) {
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
        if (pageSessionExpired(session.createdAtMs(), System.currentTimeMillis())) {
            sessions.remove(sessionId, session);
            if (sessions.isEmpty()) {
                AUDIT_PAGE_SESSIONS.remove(playerId, sessions);
            }
            return null;
        }
        return session;
    }

    static boolean hasPageSessionOwner(UUID playerId) {
        return playerId != null && AUDIT_PAGE_SESSIONS.containsKey(playerId);
    }

    static boolean pageSessionExpired(long createdAtMs, long nowMs) {
        return nowMs - createdAtMs > PAGE_SESSION_TTL_MS;
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

    static boolean canCheckNextAuditPage(int effectivePage, int clampedLimit,
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
                null, null, filter, ItemGraphPermissions.LOOKUP, requiresAuditPermission(eventType),
                System.currentTimeMillis());
        rememberPageSession(ctx.getSource(), session);
        return dispatchAuditPage(ctx.getSource(), ItemGraphPermissions.LOOKUP, "lookup audit", session, 1,
                ctx.getSource().getEntity() instanceof ServerPlayer);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String modVersion = runtimeInformation.modVersion();
        var sourceAdapter = IngestionService.getInstance().getAdapter();
        String glStatus = !sourceAdapter.isIntegrationEnabled()
                ? ItemGraphLanguage.text("status.source_disabled", "DISABLED (native-only; enable the GriefLogger integration in ItemGraph config to opt in)")
                : sourceAdapter.isSupportedSchemaAvailable()
                    ? ItemGraphLanguage.text("status.source_available", "ENABLED (read-only source available)")
                    : ItemGraphLanguage.text("status.source_unavailable", "ENABLED (read-only source unavailable)");

        DatabaseManager db = DatabaseManager.getInstance();
        boolean dbConnected = db.isInitialized();
        var dbSettings = db.getSettings();
        String backend = dbSettings == null ? "not configured" : dbSettings.backend().name().toLowerCase(java.util.Locale.ROOT);
        source.sendSuccess(() -> Component.literal(ItemGraphLanguage.text("status.summary",
                "[ItemGraph] version={0} griefLogger={1} db={2} backend={3} schemaVersion={4} maxPageSize={5} databaseConnectionTimeoutMs={6} useIndexes={7}",
                modVersion, glStatus, ItemGraphLanguage.text(dbConnected ? "status.connected" : "status.disconnected",
                        dbConnected ? "connected" : "NOT CONNECTED"), backend, db.getCurrentSchemaVersion(),
                QueryLimits.getConfiguredMaxPageSize(), dbSettings == null
                        ? ItemGraphLanguage.text("status.not_configured", "not configured") : dbSettings.connectionTimeoutMs(),
                dbSettings == null ? ItemGraphLanguage.text("status.not_configured", "not configured") : dbSettings.useIndexes())), false);
        if (!dbConnected) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("status.db_stats_unavailable",
                    "[ItemGraph] Database statistics unavailable; inspect the server log for connection details.")));
            return 0;
        }

        IngestionService ingestion = IngestionService.getInstance();
        IngestionResult lastResult = ingestion.getLastResult();
        CorrelationResult lastCorrelation = ingestion.getCorrelationEngine().getLastResult();
        InternalObservationService internalObs = InternalObservationService.getInstance();
        ItemEntityTracker entityTracker = ItemEntityTracker.getInstance();
        long capabilityQueueRejections = ContainerInteractionTracker.getInstance().getTotalCapabilityQueueRejections();

        return QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "status", conn -> {
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
            OperationalMetrics.Snapshot metrics = OperationalMetrics.getInstance().snapshot();
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
                            : (lastCorrelation.success() ? "OK" : "ERROR")
                            + " (" + lastCorrelation.observationsFinalised() + " evaluated, "
                            + lastCorrelation.edgesCreated() + " bridges inferred, " + lastCorrelation.deferred()
                            + " deferred, " + lastCorrelation.durationMs() + "ms)"),
                    "[ItemGraph] ingestion: running=" + ingestion.isRunning()
                            + " totalObservations=" + totalObservations
                            + " nativeAuditEvents=" + totalAuditEvents
                            + " checkpoints=" + checkpoints
                            + " historicalImport=" + historicalImport
                            + " lastCycle=" + (lastResult == null ? "never run yet"
                            : (lastResult.success() ? "OK" : "ERROR")
                            + " (" + lastResult.itemsIngested() + " items, " + lastResult.containersIngested()
                            + " containers, " + lastResult.durationMs() + "ms)"),
                    "[ItemGraph] inference ledger: activeEdges=" + activeEdges + " supersededEdges=" + supersededEdges,
                    "[ItemGraph] internal queue: size=" + internalObs.getQueueSize()
                            + " capacityPerQueue=10000"
                            + " idlePollMs=" + internalObs.getQueuePollIntervalMs()
                            + " flushEveryTicks=" + internalObs.getQueueFrequencyTicks()
                            + " maxBatchSize=" + internalObs.getMaxBatchSize()
                            + " networkHeartbeatMs=" + internalObs.getDatabaseHeartbeatIntervalMs()
                            + " networkHeartbeats=" + internalObs.getTotalDatabaseHeartbeats()
                            + " networkHeartbeatFailures=" + internalObs.getTotalDatabaseHeartbeatFailures()
                            + " captureEnabled=" + internalObs.isCaptureEnabled()
                            + " enqueued=" + internalObs.getTotalEnqueued()
                            + " persisted=" + internalObs.getTotalPersisted()
                            + " dropped=" + internalObs.getTotalDropped()
                            + " capabilityQueueRejections=" + capabilityQueueRejections
                            + " transformations=" + internalObs.getTotalTransformations()
                            + " auditEvents=" + internalObs.getTotalAuditEvents(),
                    "[ItemGraph] performance: enqueueCount=" + metrics.enqueue().count()
                            + " enqueueP95=" + latencyP95(metrics.enqueue())
                            + " enqueueFailed=" + metrics.enqueue().failed()
                            + " persistBatches=" + metrics.persistenceCommit().count()
                            + " persistFailedBatches=" + metrics.persistenceFailures()
                            + " persistedItems=" + metrics.persistedItems()
                            + " largestBatch=" + metrics.largestBatchSize()
                            + " commitP95=" + latencyP95(metrics.persistenceCommit())
                            + " commitMaxMs=" + metrics.persistenceCommit().maxMillis()
                            + " queryCount=" + metrics.query().count()
                            + " queryP95=" + latencyP95(metrics.query())
                            + " queryFailed=" + metrics.query().failed()
                            + " correlationCount=" + metrics.correlation().count()
                            + " correlationP95=" + latencyP95(metrics.correlation())
                            + " correlationFailed=" + metrics.correlation().failed()
                            + " queuePeak=" + metrics.peakQueueDepth()
                            + " queueRejectedItems=" + metrics.queueRejectedItems()
                            + " decodeFailureCacheInsertions=" + metrics.decodeFailureCacheInsertions()
                            + " decodeCacheHits=" + metrics.decodeCacheHits()
                            + " heapUsedBytes=" + metrics.heapUsedBytes()
                            + " heapMaxBytes=" + metrics.heapMaxBytes(),
                    "[ItemGraph] entity tracking: active=" + entityTracker.getActiveEntityCount()
                            + " drops=" + entityTracker.getDropCount()
                            + " pickups=" + entityTracker.getPickupCount()
                            + " continuityMatches=" + entityTracker.getContinuityMatchCount()
            ));
        });
    }

    private static String latencyP95(OperationalMetrics.LatencySnapshot snapshot) {
        return snapshot.count() == 0 ? "n/a"
                : snapshot.p95UpperBoundNanos() == Long.MAX_VALUE
                ? ">10000ms" : "<=" + snapshot.p95UpperBoundMillis() + "ms";
    }

    private static int ingestNow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        IngestionService ingestion = IngestionService.getInstance();
        if (!ingestion.getAdapter().isIntegrationEnabled()) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("ingest.integration_disabled",
                    "[ItemGraph] GriefLogger source integration is disabled; enable it in ItemGraph config to use this migration command.")));
            return 0;
        }
        if (!ingestion.requestIngestionAsync()) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("ingest.manual_not_queued",
                    "[ItemGraph] Manual ingestion was not queued: the worker is stopped or a manual cycle is already queued.")));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(ItemGraphLanguage.text("ingest.manual_queued",
                "[ItemGraph] Manual ingestion and correlation queued on the background worker; check /ig status for the result.")), false);
        return 1;
    }

    private static int ingestHistory(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        IngestionService ingestion = IngestionService.getInstance();
        if (!ingestion.getAdapter().isIntegrationEnabled()) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("ingest.integration_disabled",
                    "[ItemGraph] GriefLogger source integration is disabled; enable it in ItemGraph config to use this migration command.")));
            return 0;
        }
        if (!ingestion.requestHistoricalImportAsync()) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("ingest.history_not_queued",
                    "[ItemGraph] Historical GriefLogger import was not queued: the worker is stopped or an import is already queued.")));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(ItemGraphLanguage.text("ingest.history_queued",
                "[ItemGraph] Read-only historical GriefLogger import queued on the background worker; check /ig status for completion.")), false);
        return 1;
    }
}
