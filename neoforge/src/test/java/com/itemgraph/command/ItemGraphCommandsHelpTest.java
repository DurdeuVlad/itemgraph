package com.itemgraph.command;

import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.QueryWindow;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemGraphCommandsHelpTest {

    @Test
    void statusHelpDistinguishesWorkerStartupFromDatabaseHealthAndCapture() {
        String statusHelp = String.join(" ", CommandHelp.topicLines("status"));
        assertTrue(statusHelp.contains("RUNNING means the worker started, not database health or capture success"));
        assertTrue(statusHelp.contains("facts, not calibrated health thresholds"));
        assertTrue(statusHelp.contains("units, reset windows, and recovery next steps"));
    }

    @Test
    void commandOverviewFitsNarrowMinecraftChatAndRoutesByTask() {
        List<String> overview = CommandHelp.overviewLines();
        assertTrue(String.join(" ", overview).contains("/ig help journeys"));
        assertTrue(overview.stream().allMatch(line -> line.length() <= 80),
                "the first-time command overview should not wrap multiple times in narrow chat");
    }

    @Test
    void journeyHelpDistinguishesRawEvidenceInferenceAndPlayerOnlyPaging() {
        List<String> journeyLines = CommandHelp.topicLines("journeys");
        String journeys = String.join("\n", journeyLines);
        assertTrue(journeys.contains("/ig event <observationId>"));
        assertTrue(journeys.contains("Admin/creative outcome UUIDs use /ig event event:<uuid>"));
        assertFalse(journeys.contains("copy event:<uuid> from /ig trace"));
        assertTrue(journeys.contains("/ig explain <edgeId>"));
        assertTrue(journeys.contains("/ig help journeys near"));
        assertTrue(journeys.contains("/ig help journeys filters"));
        assertTrue(journeys.contains("Player paging: /ig page 2; console near returns one page."));
        String longestLine = journeyLines.stream().max(java.util.Comparator.comparingInt(String::length)).orElse("");
        assertTrue(longestLine.length() <= 80,
                () -> "journey help line exceeds narrow Minecraft chat width (" + longestLine.length() + "): " + longestLine);
        assertTrue(journeyLines.size() <= 12,
                "journey help should fit a compact chat overview including its shared permission message");
    }

    @Test
    void eachAdminJourneyNamesPermissionsEvidenceContinuationAndSafeEmptyChecks() {
        assertJourneyTopic("journeys inspect", "/ig inspect on", "OBSERVED", "INFERRED",
                "itemgraph.command.inspect", "itemgraph.audit", "itemgraph.gui",
                "/ig lookup <eventType>", "Empty:", "dimension and time range");
        assertJourneyTopic("journeys trace", "/ig trace item <query>", "OBS ID:",
                "/ig event <observationId>", "INFERRED edge:", "/ig explain <edgeId>",
                "itemgraph.trace", "itemgraph.audit", "itemgraph.event", "itemgraph.explain",
                "Ambiguous matches", "Empty:", "event type is supported");
        assertJourneyTopic("journeys near", "/ig lookup near", "OBSERVED audit rows",
                "/ig lookup <eventType>", "itemgraph.command.lookup", "itemgraph.audit",
                "/ig page 2", "itemgraph.command.page", "Console near returns one page",
                "has no nearby scope", "Empty:", "coordinates, range");
        assertJourneyTopic("journeys filters", "/ig lookup <filters...>", "audit, observation",
                "transformation, and import", "OBSERVED, INFERRED, UNRESOLVED, PROVENANCE_ONLY",
                "action.<value> radius.50 time.1h", "itemgraph.command.lookup", "itemgraph.audit",
                "itemgraph.command.page", "Radius is player-only", "Empty:", "supported events");
        AuditLookupFilters exampleFilters = AuditLookupFilters.parse(
                "action.break_block radius.50 time.1h", 1_000L);
        assertEquals(50.0, exampleFilters.radiusBlocks(),
                "the advertised follow-up lookup must satisfy the required bounded radius filter");
    }

    private static void assertJourneyTopic(String topic, String... requiredText) {
        List<String> lines = CommandHelp.topicLines(topic);
        assertNotNull(lines, "missing help topic " + topic);
        String content = String.join("\n", lines);
        for (String required : requiredText) {
            assertTrue(content.contains(required), () -> topic + " is missing: " + required);
        }
        String longestLine = lines.stream().max(java.util.Comparator.comparingInt(String::length)).orElse("");
        assertTrue(longestLine.length() <= 80,
                () -> topic + " has a line wider than 80 chars: " + longestLine);
        assertTrue(lines.size() <= 12,
                () -> topic + " exceeds 12 chat messages including shared permission guidance");
    }

    @Test
    void ingestHelpExplainsNativeCaptureAndOptionalGriefLoggerSync() {
        String ingestHelp = String.join(" ", CommandHelp.topicLines("ingest now"));
        assertTrue(ingestHelp.contains("Optional GriefLogger source sync"));
        assertTrue(ingestHelp.contains("Native event capture runs automatically when enabled"));
        assertTrue(ingestHelp.contains("Enable the GriefLogger source integration in config"));
    }

    @Test
    void featureCatalogueUsesDurableStatusesAndUuidEvidenceRoute() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/ADMIN_QUICK_START.md"))) {
            root = root.getParent();
        }
        assertNotNull(root);
        String guide = Files.readString(root.resolve("docs/ADMIN_QUICK_START.md"));
        int featureMap = guide.indexOf("## Feature map: currently available surfaces");
        assertTrue(featureMap >= 0);
        String catalogue = guide.substring(featureMap);
        assertTrue(catalogue.contains("/ig help journeys"));
        assertTrue(catalogue.contains("/ig event event:<uuid>"));
        assertTrue(catalogue.contains("/ig goto <token>"));
        assertTrue(catalogue.contains("internal and click-only"));
        assertFalse(catalogue.contains("PR CI pending"));
        assertFalse(catalogue.contains("local tests passed"));
    }

    @Test
    void everyTaskHelpSentenceResolvesThroughExplicitPerKeyEnglishFallback() {
        CommandHelp.initializeMessages();
        var english = CommandHelp.overviewLines();
        for (String topic : CommandHelp.TOPIC_NAMES) {
            List<String> topicLines = CommandHelp.topicLines(topic);
            if (topicLines != null) english = java.util.stream.Stream.concat(english.stream(), topicLines.stream()).toList();
        }
        assertTrue(com.itemgraph.i18n.ItemGraphLanguage.sourceInventory().containsAll(english));

        com.itemgraph.i18n.ItemGraphLanguage.setLocale("nl_nl");
        List<String> nl = english.stream().map(com.itemgraph.i18n.ItemGraphLanguage::sourceText).toList();
        assertEquals(english.get(0), nl.get(0), "The new task route must be inventoried as English fallback until translated");
        Set<String> nlFallback = com.itemgraph.i18n.ItemGraphLanguage.sourceFallbackInventory("nl_nl");
        assertEquals(196, nlFallback.size(), "Every untranslated source sentence stays explicit in the fallback inventory");
        assertTrue(nlFallback.contains("[ItemGraph] "), "The chat action prefix is an inventoried English fallback");
        for (int i = 0; i < english.size(); i++) {
            if (nlFallback.contains(english.get(i))) assertEquals(english.get(i), nl.get(i));
        }
        com.itemgraph.i18n.ItemGraphLanguage.setLocale("zh_tw");
        List<String> zh = english.stream().map(com.itemgraph.i18n.ItemGraphLanguage::sourceText).toList();
        assertEquals(english.get(0), zh.get(0), "The new task route must be inventoried as English fallback until translated");
        Set<String> zhFallback = com.itemgraph.i18n.ItemGraphLanguage.sourceFallbackInventory("zh_tw");
        assertEquals(196, zhFallback.size(), "Every untranslated source sentence stays explicit in the fallback inventory");
        assertTrue(zhFallback.contains("[ItemGraph] "), "The chat action prefix is an inventoried English fallback");
        for (int i = 0; i < english.size(); i++) {
            if (zhFallback.contains(english.get(i))) assertEquals(english.get(i), zh.get(i));
        }
        com.itemgraph.i18n.ItemGraphLanguage.setLocale("en_us");
    }

    @Test
    void sensitiveLookupAliasesAndBroadFiltersRetainAuditPermissionAcrossPages() {
        assertEquals(List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT),
                ItemGraphCommands.lookupPermissionsForType("chat_message"));
        assertEquals(List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT),
                ItemGraphCommands.lookupPermissionsForType("COMMAND_ATTEMPT"));
        assertEquals(List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT),
                ItemGraphCommands.lookupPermissionsForType("command_executed"));
        assertEquals(List.of(ItemGraphPermissions.LOOKUP),
                ItemGraphCommands.lookupPermissionsForType("PLAYER_JOIN"));
        assertTrue(ItemGraphCommands.requiresAuditPermission(List.of()));
        assertTrue(ItemGraphCommands.requiresAuditPermission(List.of("PLAYER_JOIN", "CREATIVE_SLOT_EFFECT")));
        assertTrue(ItemGraphCommands.requiresAuditPermission(List.of("ADMIN_ITEM_CREATE")));
        assertFalse(ItemGraphCommands.requiresAuditPermission(List.of("PLAYER_JOIN")));

        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                java.util.UUID.randomUUID(), "COMMAND_ATTEMPT", null,
                com.itemgraph.query.QueryWindow.unbounded(), 20, null,
                null, null, null, null, null, null, "command attempt lookup",
                ItemGraphPermissions.LOOKUP, true, System.currentTimeMillis());
        assertEquals(List.of(ItemGraphPermissions.PAGE, ItemGraphPermissions.LOOKUP,
                        ItemGraphPermissions.AUDIT), ItemGraphCommands.pagePermissionsFor(session));
    }

    @Test
    void lookupOnlyGrantCannotReachSensitiveEventAliasesOrSavedPages() throws Exception {
        ItemGraphPermissions.setChecker((source, node) -> Set.of(ItemGraphPermissions.COMMAND,
                ItemGraphPermissions.LOOKUP, ItemGraphPermissions.PAGE).contains(node));
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        for (String command : List.of(
                "ig lookup CHAT_MESSAGE",
                "ig lookup chat_message",
                "itemgraph lookup player Alex COMMAND_ATTEMPT",
                "ig lookup near minecraft:overworld 0 64 0 5 COMMAND_EXECUTED",
                "ig lookup filters action.chat_message radius.5",
                "ig lookup action.command_attempt radius.5")) {
            CommandSourceStack source = source();
            assertEquals(0, dispatcher.execute(command, source), command);
            ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);
            verify(source).sendFailure(failure.capture());
            assertTrue(failure.getValue().getString().contains("permission")
                            || failure.getValue().getString().contains("itemgraph.audit"),
                    command + " failure text: " + failure.getValue().getString());
            verify(source, never()).sendSuccess(any(), anyBoolean());
        }

        UUID ownerId = UUID.randomUUID();
        ServerPlayer owner = mock(ServerPlayer.class);
        when(owner.getUUID()).thenReturn(ownerId);
        CommandSourceStack pageSource = sourceForPlayer(owner);
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                UUID.randomUUID(), "COMMAND_ATTEMPT", null, QueryWindow.unbounded(), 20, null,
                null, null, null, null, null, null, "command lookup",
                ItemGraphPermissions.LOOKUP, true, System.currentTimeMillis());
        ItemGraphCommands.rememberPageSession(pageSource, session);

        assertEquals(0, dispatcher.execute("ig page 2 " + session.sessionId(), pageSource));
        ArgumentCaptor<Component> pageFailure = ArgumentCaptor.forClass(Component.class);
        verify(pageSource).sendFailure(pageFailure.capture());
        assertTrue(pageFailure.getValue().getString().contains("permission"),
                pageFailure.getValue().getString());
        verify(pageSource, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void protectedEvidenceSurfacesFailClosedWithoutAuditBeforeQueryDispatch() throws Exception {
        ItemGraphPermissions.setChecker((source, node) -> Set.of(ItemGraphPermissions.COMMAND,
                ItemGraphPermissions.EVENT, ItemGraphPermissions.EXPLAIN, ItemGraphPermissions.TRACE,
                ItemGraphPermissions.GUI).contains(node));
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        for (String command : List.of("ig event 1", "ig explain 1", "ig trace item diamond", "ig gui item diamond")) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source), command);
            verify(source, never()).sendSuccess(any(), anyBoolean());
        }
    }

    @Test
    void pageLocationGrantsRetainPageOriginAndAuditNodes() {
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                UUID.randomUUID(), "ADMIN_ITEM_CREATE", null, QueryWindow.unbounded(), 10, null,
                null, null, null, null, null, null, "admin item history",
                ItemGraphPermissions.LOOKUP, true, System.currentTimeMillis());
        assertEquals(List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.PAGE, ItemGraphPermissions.AUDIT),
                ItemGraphCommands.locationPermissionNodes(session, ItemGraphPermissions.PAGE));
        assertEquals(List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT),
                ItemGraphCommands.locationPermissionNodes(session, ItemGraphPermissions.LOOKUP));
    }

    @Test
    void explainSummaryHoverDoesNotLabelCitedObservedEvidenceAsInferred() {
        var edge = new com.itemgraph.query.EdgeExplanation(1,
                new com.itemgraph.query.NodeRef(1, "PLAYER", "Alex", null, null, null, null),
                new com.itemgraph.query.NodeRef(2, "CONTAINER", null, "minecraft:overworld", 1d, 2d, 3d),
                new com.itemgraph.query.FingerprintRef(1, "minecraft:diamond", null, "safe-hash"),
                1, 1000, 2000, 0.9, "supporting observations", 2000, List.of(), false);

        Map<Integer, QueryDispatcher.ChatHoverDetail> hovers = ItemGraphCommands.explainSummaryHover(edge);

        assertEquals(Set.of(0), hovers.keySet(), "raw observation/evidence lines have no inherited inferred hover");
        assertTrue(hovers.get(0).evidenceClass().startsWith("INFERRED"));
        assertFalse(hovers.values().stream().anyMatch(hover -> hover.evidenceClass().equals("OBSERVED")));
    }

    @AfterEach
    void resetPermissionChecker() {
        ItemGraphPermissions.setChecker(null);
    }

    @BeforeAll
    static void initMinecraftRegistries() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @Test
    void cappedAuditPageDoesNotOfferARepeatingNextControl() {
        assertTrue(ItemGraphCommands.canCheckNextAuditPage(1, 30, 0, 30));
        assertFalse(ItemGraphCommands.canCheckNextAuditPage(334, 30, 9_990, 30));
        assertFalse(ItemGraphCommands.canCheckNextAuditPage(334, 30, 9_990, 29));
    }

    @Test
    void bareFullRootAndAliasShowOverview() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph", source));
        String overview = String.join("\n", successes);
        assertTrue(overview.contains("Trace: /ig trace item <query>"));
        assertTrue(overview.contains("/ig help journeys"));
        assertTrue(overview.contains("/ig help commands"));
        assertTrue(overview.contains("/ig inspect on"));
        assertTrue(overview.contains("/ig event <id>"));
        assertTrue(overview.contains("/ig explain <id>"));
        assertTrue(overview.contains("/ig help permissions"));
        assertTrue(overview.contains("audit: /ig audit"));
        assertTrue(overview.contains("inferred edge"));
        assertTrue(successes.size() <= 5, "first-use help must fit a normal chat view");
        assertFalse(overview.contains("/ig lookup page <page> <eventType>"),
                "full syntax belongs in the exhaustive commands topic");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup near minecraft:overworld 120 64 -30 32 BREAK_BLOCK 20 1440", source),
                "in-game help's documented nearby audit syntax");

        successes.clear();
        assertEquals(1, dispatcher.execute("ig", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("Trace: /ig trace item <query>")));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig help commands")));
    }

    @Test
    void namedPermissionsAllowLookupOnlyAndExplicitDenialsOverrideOperatorFallback() {
        CommandSourceStack levelOne = source();
        when(levelOne.hasPermission(2)).thenReturn(false);
        Map<String, Boolean> decisions = Map.ofEntries(
                Map.entry(ItemGraphPermissions.COMMAND, true),
                Map.entry(ItemGraphPermissions.LOOKUP, true),
                Map.entry(ItemGraphPermissions.INSPECT, false),
                Map.entry(ItemGraphPermissions.PAGE, false),
                Map.entry(ItemGraphPermissions.TRACE, false),
                Map.entry(ItemGraphPermissions.EVENT, false),
                Map.entry(ItemGraphPermissions.EXPLAIN, false),
                Map.entry(ItemGraphPermissions.AUDIT, false),
                Map.entry(ItemGraphPermissions.GUI, false),
                Map.entry(ItemGraphPermissions.INGEST, false),
                Map.entry(ItemGraphPermissions.IMPORT, false));
        ItemGraphPermissions.setChecker((source, node) -> decisions.getOrDefault(node, source.hasPermission(2)));
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();

        assertFalse(dispatcher.parse("itemgraph lookup BREAK_BLOCK", levelOne).getReader().canRead(),
                "explicit root + lookup grants permit a level-1 lookup user");
        for (String denied : List.of("itemgraph inspect on", "itemgraph page 1", "itemgraph trace item stone",
                "itemgraph event 1", "itemgraph explain 1", "itemgraph audit", "itemgraph gui item stone",
                "itemgraph ingest now", "itemgraph ingest history")) {
            assertTrue(dispatcher.parse(denied, levelOne).getReader().canRead(),
                    "an explicit denial must hide the command despite the lookup grant: " + denied);
        }

        CommandSourceStack operator = source();
        when(operator.hasPermission(2)).thenReturn(true);
        assertTrue(ItemGraphPermissions.check(operator, "itemgraph.unset"),
                "an unset node must use the level-2 fallback");
        assertFalse(ItemGraphPermissions.check(operator, ItemGraphPermissions.INSPECT),
                "an explicit false must override the level-2 fallback");
    }

    @Test
    void absentProviderUsesVanillaLevelTwoForNamedNodes() {
        CommandSourceStack levelOne = source();
        when(levelOne.hasPermission(2)).thenReturn(false);
        ItemGraphPermissions.setChecker(null);
        assertFalse(ItemGraphPermissions.canUse(levelOne, ItemGraphPermissions.LOOKUP));

        CommandSourceStack operator = source();
        when(operator.hasPermission(2)).thenReturn(true);
        assertTrue(ItemGraphPermissions.canUse(operator, ItemGraphPermissions.LOOKUP));
    }

    @Test
    void vanillaCommandTreePacketCodecRoundTripsBothCommandRoots() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        @SuppressWarnings({"rawtypes", "unchecked"})
        RootCommandNode<SharedSuggestionProvider> commandTree = (RootCommandNode) dispatcher.getRoot();
        FriendlyByteBuf encoded = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundCommandsPacket.STREAM_CODEC.encode(encoded, new ClientboundCommandsPacket(commandTree));
            assertTrue(encoded.readableBytes() > 0, "the registered command tree must produce a network payload");

            ClientboundCommandsPacket decoded = ClientboundCommandsPacket.STREAM_CODEC.decode(encoded);
            RootCommandNode<SharedSuggestionProvider> received = decoded.getRoot(
                    CommandBuildContext.simple(RegistryAccess.EMPTY, FeatureFlags.DEFAULT_FLAGS));
            var itemgraph = received.getChild("itemgraph");
            var alias = received.getChild("ig");
            assertNotNull(itemgraph);
            assertNotNull(itemgraph.getChild("lookup"));
            assertNotNull(itemgraph.getChild("page"));
            assertNotNull(itemgraph.getChild("inspect"));
            assertNotNull(alias);
            assertSame(itemgraph, alias.getRedirect(), "/ig must retain its redirect to /itemgraph after decode");
            assertNull(received.getChild("gl"));
            assertNull(received.getChild("grieflogger"));
            assertEquals(0, encoded.readableBytes(), "the entire packet payload must decode");
        } finally {
            encoded.release();
        }
    }

    @Test
    void helpListsEveryRegisteredCommandPath() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(Set.of("itemgraph", "ig"), dispatcher.getRoot().getChildren().stream()
                        .map(CommandNode::getName).collect(Collectors.toSet()),
                "ItemGraph exposes its two named roots without GriefLogger command aliases");
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("itemgraph");
        assertNotNull(root);
        assertEquals(Set.of("help", "status", "audit", "lookup", "ingest", "event", "explain", "page", "trace", "gui", "inspect", "goto"),
                root.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
        assertEquals(Set.of("now", "history"), childNames(root, "ingest"));
        assertEquals(Set.of("item", "player", "container"), childNames(root, "trace"));
        assertEquals(Set.of("item", "player", "container"), childNames(root, "gui"));
        assertEquals(Set.of("on", "off", "status"), childNames(root, "inspect"));

        for (String topLevel : Set.of("help", "commands", "permissions", "status", "audit", "lookup", "ingest", "event", "explain", "page", "trace", "gui", "inspect", "goto")) {
            assertNotNull(CommandHelp.topicLines(topLevel), "missing help topic for /ig " + topLevel);
        }
        for (String path : List.of("ingest now", "ingest history", "lookup near", "lookup page", "lookup player",
                "lookup filters", "lookup provenance", "trace item", "trace player", "trace container",
                "gui item", "gui player", "gui container")) {
            List<String> lines = CommandHelp.topicLines(path);
            assertNotNull(lines, "missing help topic for /ig " + path);
            assertTrue(String.join("\n", lines).contains("/ig " + path),
                    "help topic does not document its registered path: " + path);
        }
        String inspectHelp = String.join("\n", CommandHelp.topicLines("inspect"));
        assertTrue(inspectHelp.contains("/ig inspect [on|off|status]"));
        for (String child : List.of("on", "off", "status")) {
            assertTrue(inspectHelp.contains("/ig inspect " + child),
                    "inspect help does not document /ig inspect " + child);
        }
        String pageHelp = String.join("\n", CommandHelp.topicLines("page"));
        assertTrue(pageHelp.contains("Syntax: /ig page <page> [session]"));
        assertTrue(pageHelp.contains("per-player"));

        String filteredLookupHelp = String.join("\n", CommandHelp.topicLines("lookup filters"));
        assertTrue(filteredLookupHelp.contains("Player-only"));
        assertTrue(filteredLookupHelp.contains("From console, use /ig lookup near"));

        String commandsHelp = String.join("\n", CommandHelp.topicLines("commands"));
        assertTrue(CommandHelp.topicLines("commands").stream().allMatch(line -> line.length() <= 220),
                "the grouped command catalog must avoid lines that become dense chat paragraphs");
        for (CommandNode<CommandSourceStack> child : root.getChildren()) {
            String commandPath = switch (child.getName()) {
                case "lookup" -> "/ig lookup <eventType>";
                case "ingest" -> "/ig ingest now";
                default -> "/ig " + child.getName();
            };
            assertTrue(commandsHelp.contains(commandPath),
                    "exhaustive in-game help omits registered root command " + child.getName());
        }
        for (String path : List.of(
                "/ig help", "/ig status", "/ig audit", "/ig ingest now", "/ig ingest history",
                "/ig event <observationId>", "/ig explain <edgeId>", "/ig page <page>",
                "/ig lookup <eventType>", "/ig lookup near", "/ig lookup page", "/ig lookup player",
                "/ig lookup filters", "/ig lookup provenance", "/ig trace item", "/ig trace player",
                "/ig trace container", "/ig gui item", "/ig gui player", "/ig gui container",
                "/ig inspect [on|off|status]")) {
            assertTrue(commandsHelp.contains(path), "exhaustive in-game command help omits " + path);
        }
    }

    @Test
    void documentedQuickStartExamplesParseAgainstRegisteredCommandTree() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("docs/ADMIN_QUICK_START.md"))) {
            root = root.getParent();
        }
        assertNotNull(root, "test process must be able to locate repository documentation");
        String guide = Files.readString(root.resolve("docs/ADMIN_QUICK_START.md"));
        String startMarker = "<!-- executable-command-examples:start -->";
        String endMarker = "<!-- executable-command-examples:end -->";
        int start = guide.indexOf(startMarker);
        int end = guide.indexOf(endMarker);
        assertTrue(start >= 0 && end > start, "quick-start command example markers must be paired");

        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> examples = guide.substring(start + startMarker.length(), end).lines()
                .map(String::strip)
                .filter(line -> line.startsWith("/ig "))
                .map(line -> line.substring(1))
                .toList();
        assertFalse(examples.isEmpty(), "quick start must contain executable /ig examples");
        for (String example : examples) {
            assertParsedCompletely(dispatcher.parse(example, source), "quick-start example /" + example);
        }
    }

    @Test
    void lookupHelpUsesTheSharedAuditEventCatalog() {
        String lookupHelp = String.join("\n", CommandHelp.topicLines("lookup"));

        assertTrue(lookupHelp.contains(
                "eventType: " + String.join(", ", AuditEventQueryService.EVENT_TYPES) + "."));
    }

    @Test
    void transformationHelpExplainsOutputOnlyEvidenceAndLimits() {
        String help = String.join("\n", CommandHelp.topicLines("lookup transformations"));
        assertTrue(help.contains("CRAFT_OUTPUT_UNRESOLVED"));
        assertTrue(help.contains("SMELT_OUTPUT_UNRESOLVED"));
        assertTrue(help.contains("input is unknown; no lineage edge"));
        assertTrue(help.contains("itemgraph.command.lookup"));
        assertTrue(help.contains("itemgraph.audit"));
        assertTrue(help.contains("Legacy CRAFT/SMELT is unresolved"));
        assertTrue(help.contains("trade, enchanting, brewing, smithing"));
    }

    @Test
    void permissionHelpGivesNovicesExactRoleBundles() {
        List<String> lines = CommandHelp.topicLines("permissions");
        String help = String.join("\n", lines);
        assertTrue(help.contains("All commands need itemgraph.command; status/help need only it"));
        assertTrue(help.contains("itemgraph.command + itemgraph.command.lookup"));
        assertTrue(help.contains("Protected lookup adds itemgraph.audit"));
        assertTrue(help.contains("For provenance chats/commands, add itemgraph.audit to lookup"));
        assertTrue(help.contains("Other provenance tables: itemgraph.command.lookup only"));
        assertTrue(help.contains("/ig audit: itemgraph.command + itemgraph.audit"));
        assertTrue(help.contains("itemgraph.command.page"));
        assertTrue(help.contains("itemgraph.command + itemgraph.trace + itemgraph.audit"));
        assertTrue(help.contains("itemgraph.command + itemgraph.event + itemgraph.audit"));
        assertTrue(help.contains("itemgraph.command + itemgraph.explain + itemgraph.audit"));
        assertTrue(help.contains("itemgraph.command + itemgraph.command.inspect"));
        assertTrue(help.contains("Block history in Inspect also needs itemgraph.audit"));
        assertTrue(help.contains("GUI in Inspect: add itemgraph.gui + itemgraph.audit"));
        assertTrue(help.contains("Direct GUI: itemgraph.command + itemgraph.gui + itemgraph.audit"));
        assertTrue(help.contains("/ig ingest now: itemgraph.command + itemgraph.ingest"));
        assertTrue(help.contains("Import: itemgraph.command + itemgraph.ingest + itemgraph.import"));
        assertTrue(help.contains("Explicit false denies; unset nodes use level 2"));
        assertTrue(help.contains("Matrix/provider behavior: docs/SECURITY_AND_PERMISSIONS.md"));
        assertTrue(help.contains("Dotted nodes do not inherit"));
        assertTrue(lines.size() <= 20, "permissions topic including shared guidance must fit 20 chat messages");
        String longest = lines.stream().max(java.util.Comparator.comparingInt(String::length)).orElse("");
        assertTrue(longest.length() <= 80, () -> "permissions topic line exceeds 80 chars: " + longest);
    }

    @Test
    void helpTopicReturnsSyntaxDefaultsPermissionAndExample() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph help", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("Trace: /ig trace item <query>")));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help commands", source));
        String commandsHelp = String.join("\n", successes);
        assertTrue(commandsHelp.contains("docs/ADMIN_QUICK_START.md"));
        assertTrue(commandsHelp.contains("/ig ingest history"));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help trace item", source));
        String itemHelp = String.join("\n", successes);
        assertTrue(itemHelp.contains("Syntax: /ig trace item <query> [limit] [sinceMinutes]"));
        assertTrue(itemHelp.contains("Unset grants use level 2"));
        assertTrue(itemHelp.contains("asynchronous"));
        assertTrue(itemHelp.contains("default 20"));
        assertTrue(itemHelp.contains("Example:"));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help gui container", source));
        String guiHelp = String.join("\n", successes);
        assertTrue(guiHelp.contains("Syntax: /ig gui container <dimension> <x> <y> <z> [sinceMinutes]"));
        assertTrue(guiHelp.contains("Example:"));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help inspect", source));
        String inspectHelp = String.join("\n", successes);
        assertTrue(inspectHelp.contains("Unset grants use level 2"));
        assertTrue(inspectHelp.contains("held items are not used"));
        assertTrue(inspectHelp.contains("Example: /ig inspect on"));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help lookup filters", source));
        String filteredLookupHelp = String.join("\n", successes);
        assertTrue(filteredLookupHelp.contains("Syntax: /ig lookup <filter1>"));
        assertTrue(filteredLookupHelp.contains("default to 10 rows"));
    }

    @Test
    void everySuggestedHelpTopicExecutes() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        for (String topic : CommandHelp.TOPIC_NAMES) {
            successes.clear();
            assertEquals(1, dispatcher.execute("itemgraph help " + topic, source), topic);
            assertFalse(successes.isEmpty(), topic);
            String lines = String.join("\n", successes);
            assertTrue(lines.contains("Unset grants use level 2"), topic);
            assertTrue(lines.contains("Example"), topic);
        }
        verify(source, never()).sendFailure(any());
    }

    @Test
    void unknownHelpTopicFailsWithValidTopics() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

        assertEquals(0, dispatcher.execute("itemgraph help not-a-topic", source));
        verify(source).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("Unknown help topic 'not-a-topic'"));
        assertTrue(failure.getValue().getString().contains("trace item"));
        assertTrue(failure.getValue().getString().contains("gui container"));
        assertTrue(failure.getValue().getString().contains("guide"));
    }

    @Test
    void helpRequiresPermissionLevelTwo() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        when(source.hasPermission(2)).thenReturn(false);

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("itemgraph", source));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("itemgraph help", source));
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("ig lookup add_item_ender", source),
                "the Ender inventory lookup must remain behind the permission-level-2 root");
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void standalonePageRequiresAnActivePlayerSession() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

        assertEquals(0, dispatcher.execute("itemgraph page 2", source));
        verify(source).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("No active lookup page session"));
    }

    @Test
    void standalonePageAcceptsAQuerySessionToken() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

        assertEquals(0, dispatcher.execute(
                "itemgraph page 2 00000000-0000-0000-0000-000000000001", source));
        verify(source).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("No active lookup page session"));
    }

    @Test
    void pageSessionTokensAreIsolatedByPlayerAndExplicitlyClearable() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        ServerPlayer ownerPlayer = mock(ServerPlayer.class);
        ServerPlayer otherPlayer = mock(ServerPlayer.class);
        when(ownerPlayer.getUUID()).thenReturn(ownerId);
        when(otherPlayer.getUUID()).thenReturn(otherId);
        CommandSourceStack owner = sourceForPlayer(ownerPlayer);
        CommandSourceStack other = sourceForPlayer(otherPlayer);
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                sessionId, "BREAK_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=BREAK_BLOCK", System.currentTimeMillis());
        ItemGraphCommands.rememberPageSession(owner, session);

        assertSame(session, ItemGraphCommands.pageSession(owner, sessionId));
        assertNull(ItemGraphCommands.pageSession(other, sessionId),
                "another level-2 player must not resolve a copied session token");
        assertEquals(0, dispatcher.execute("itemgraph page 2 " + sessionId, other));
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);
        verify(other).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("No active lookup page session"));

        ItemGraphCommands.clearPageSession(ownerId);
        assertNull(ItemGraphCommands.pageSession(owner, sessionId),
                "clearing the owner's page state must invalidate its session token");
        ItemGraphCommands.clearPageSession(otherId);
    }

    @Test
    void suggestionsCoverLiteralsPlayersItemsTopicsAndDimensions() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertSuggestions(dispatcher, source, "itemgraph ", "help", "status", "audit", "lookup", "page", "trace", "gui", "inspect");
        assertSuggestions(dispatcher, source, "itemgraph lookup ", "all", "BREAK_BLOCK", "CHAT_MESSAGE", "INTERACT_ENTITY");
        assertSuggestions(dispatcher, source, "itemgraph lookup action.", "break_block", "chat_message", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup \"action.break_block,",
                "place_block", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup a.", "break_block", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup user.", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph lookup u.", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph lookup include.", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph lookup exclude.", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph lookup action.break_block ", "user.", "include.", "exclude.", "time.", "radius.");
        assertSuggestions(dispatcher, source, "itemgraph help ", "trace item", "gui container", "inspect");
        assertSuggestions(dispatcher, source, "itemgraph inspect ", "on", "off", "status");
        assertSuggestions(dispatcher, source, "itemgraph trace player ", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph gui player ", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph trace item ", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph gui item ", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph gui container ", "minecraft:overworld");
    }

    @Test
    void publishedGriefLoggerLookupFilterSyntaxParsesDirectly() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        var parsed = dispatcher.parse(
                "itemgraph lookup action.break_block \"include.diamond_ore,gold_ore\" radius.20",
                source);

        assertParsedCompletely(parsed, "direct GriefLogger filter syntax");

        var explicit = dispatcher.parse(
                "itemgraph lookup filters action.break_block radius.20", source);
        assertParsedCompletely(explicit, "explicit ItemGraph filter syntax");

        var radiusOnly = dispatcher.parse("itemgraph lookup radius.10", source);
        assertParsedCompletely(radiusOnly, "one-token GriefLogger filter syntax");
        assertTrue(radiusOnly.getContext().getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals("lookupFilters")),
                "radius-only filter was routed to the direct filter branch");

        var nativeLookup = dispatcher.parse("itemgraph lookup BREAK_BLOCK 50 60", source);
        assertParsedCompletely(nativeLookup, "native audit lookup syntax");
        assertTrue(nativeLookup.getContext().getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals("BREAK_BLOCK")),
                "native event lookup was routed to the native literal branch");

        var nativeInteractionLookup = dispatcher.parse("itemgraph lookup INTERACT_BLOCK", source);
        assertParsedCompletely(nativeInteractionLookup, "native block-interaction lookup syntax");
        assertTrue(nativeInteractionLookup.getContext().getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals("INTERACT_BLOCK")),
                "native block-interaction lookup was routed to the native literal branch");

        var nativeEntityInteractionLookup = dispatcher.parse("itemgraph lookup INTERACT_ENTITY", source);
        assertParsedCompletely(nativeEntityInteractionLookup, "native entity-interaction lookup syntax");
        assertTrue(nativeEntityInteractionLookup.getContext().getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals("INTERACT_ENTITY")),
                "native entity-interaction lookup was routed to the native literal branch");

        var nativeAllLookup = dispatcher.parse("itemgraph lookup all", source);
        assertParsedCompletely(nativeAllLookup, "native all-events lookup syntax");
        assertTrue(nativeAllLookup.getContext().getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals("all")),
                "native all-events lookup was routed to the native literal branch");

        var nativeUppercaseAllLookup = dispatcher.parse("itemgraph lookup ALL", source);
        assertParsedCompletely(nativeUppercaseAllLookup, "uppercase native all-events lookup syntax");
        assertTrue(nativeUppercaseAllLookup.getContext().getNodes().stream()
                        .anyMatch(node -> node.getNode().getName().equals("ALL")),
                "uppercase native all-events lookup was routed to the native literal branch");

        assertParsedCompletely(dispatcher.parse("itemgraph lookup AlL", source),
                "mixed-case native all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse("itemgraph lookup bReAk_BloCk 5 60", source),
                "mixed-case native block lookup syntax");
    }

    @Test
    void allPublishedLookupExamplesParseForBothItemGraphRoots() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        for (PublishedLookupExample example : publishedExamples()) {
            AuditLookupFilters expected = example.expectedFilters();
            AuditLookupFilters actual = AuditLookupFilters.parse(
                    example.filters(), PUBLISHED_LOOKUP_NOW_MS);
            assertEquals(expected.eventTypes(), actual.eventTypes(), example.filters());
            assertEquals(expected.playerNames(), actual.playerNames(), example.filters());
            assertEquals(expected.includeSubjects(), actual.includeSubjects(), example.filters());
            assertEquals(expected.excludeSubjects(), actual.excludeSubjects(), example.filters());
            assertEquals(expected.radiusBlocks(), actual.radiusBlocks(), example.filters());
            assertEquals(expected.window(), actual.window(), example.filters());

            for (String root : List.of("ig", "itemgraph")) {
                String command = root + " lookup " + example.filters();
                var parsed = dispatcher.parse(command, source);
                assertParsedCompletely(parsed, "published /" + command);
                assertEquals(example.filters(), argumentValue(parsed, "lookupFilters"), command);
            }
        }
    }

    private static final long PUBLISHED_LOOKUP_NOW_MS = 10_000_000L;

    private record PublishedLookupExample(
            String filters,
            List<String> actions,
            List<String> users,
            List<String> includes,
            List<String> excludes,
            double radius,
            long timeWindowMinutes) {
        private AuditLookupFilters expectedFilters() {
            return new AuditLookupFilters(actions, users, includes, excludes, radius,
                    timeWindowMinutes == 0
                            ? new QueryWindow(null, null)
                            : new QueryWindow(PUBLISHED_LOOKUP_NOW_MS - timeWindowMinutes * 60_000L,
                                    PUBLISHED_LOOKUP_NOW_MS));
        }
    }

    private static List<PublishedLookupExample> publishedExamples() {
        return List.of(
                new PublishedLookupExample("action.break_block include.diamond_ore radius.50",
                        List.of("BREAK_BLOCK"), List.of(), List.of("minecraft:diamond_ore"), List.of(), 50, 0),
                new PublishedLookupExample("user.Griefer42 time.1h radius.100",
                        List.of(), List.of("Griefer42"), List.of(), List.of(), 100, 60),
                new PublishedLookupExample("action.remove_item \"include.diamond,netherite_ingot\" radius.30 time.6h",
                        List.of("REMOVE_ITEM"), List.of(), List.of("minecraft:diamond", "minecraft:netherite_ingot"),
                        List.of(), 30, 360),
                new PublishedLookupExample("\"action.break_block,place_block\" radius.15",
                        List.of("BREAK_BLOCK", "PLACE_BLOCK"), List.of(), List.of(), List.of(), 15, 0),
                new PublishedLookupExample("\"action.join,quit\" time.10m radius.100",
                        List.of("PLAYER_JOIN", "PLAYER_QUIT"), List.of(), List.of(), List.of(), 100, 10),
                new PublishedLookupExample("user.MinerJoe action.break_block \"exclude.stone,dirt,cobblestone,gravel\" radius.50",
                        List.of("BREAK_BLOCK"), List.of("MinerJoe"), List.of(),
                        List.of("minecraft:stone", "minecraft:dirt", "minecraft:cobblestone", "minecraft:gravel"), 50, 0),
                new PublishedLookupExample("radius.20 action.remove_item exclude.cobblestone",
                        List.of("REMOVE_ITEM"), List.of(), List.of(), List.of("minecraft:cobblestone"), 20, 0),
                new PublishedLookupExample("include.diamond_block radius.100",
                        List.of(), List.of(), List.of("minecraft:diamond_block"), List.of(), 100, 0),
                new PublishedLookupExample("action.remove_item \"include.netherite_sword,netherite_pickaxe,netherite_ingot\" time.1d radius.50",
                        List.of("REMOVE_ITEM"), List.of(),
                        List.of("minecraft:netherite_sword", "minecraft:netherite_pickaxe", "minecraft:netherite_ingot"),
                        List.of(), 50, 1_440),
                new PublishedLookupExample("radius.5",
                        List.of(), List.of(), List.of(), List.of(), 5, 0),
                new PublishedLookupExample("include.tnt time.30m radius.25",
                        List.of(), List.of(), List.of("minecraft:tnt"), List.of(), 25, 30),
                new PublishedLookupExample("\"action.add_item,remove_item\" time.90m radius.50",
                        List.of("ADD_ITEM", "REMOVE_ITEM"), List.of(), List.of(), List.of(), 50, 90),
                new PublishedLookupExample("action.join time.3d radius.50",
                        List.of("PLAYER_JOIN"), List.of(), List.of(), List.of(), 50, 4_320),
                new PublishedLookupExample("user.Notch radius.50",
                        List.of(), List.of("Notch"), List.of(), List.of(), 50, 0),
                new PublishedLookupExample("\"user.Player1,Player2\" action.break_block time.1d radius.50",
                        List.of("BREAK_BLOCK"), List.of("Player1", "Player2"), List.of(), List.of(), 50, 1_440));
    }

    @Test
    void lookupFiltersFollowPublishedAliasesRadiusAndConflictRules() {
        AuditLookupFilters parsed = AuditLookupFilters.parse(
                "a.break_block u.Alex i.diamond_ore t.1h r.50", 10_000_000L);
        assertEquals(List.of("BREAK_BLOCK"), parsed.eventTypes());
        assertEquals(List.of("Alex"), parsed.playerNames());
        assertEquals(List.of("minecraft:diamond_ore"), parsed.includeSubjects());
        assertEquals(50.0, parsed.radiusBlocks());
        AuditLookupFilters fiveFilters = AuditLookupFilters.parse(
                "action.break_block user.Alex include.stone time.1h radius.50", 10_000_000L);
        assertEquals(List.of("BREAK_BLOCK"), fiveFilters.eventTypes());
        assertEquals(List.of("Alex"), fiveFilters.playerNames());
        assertEquals(List.of("minecraft:stone"), fiveFilters.includeSubjects());
        assertEquals(List.of("PROJECTILE_SPAWN_ACCEPTED"),
                AuditLookupFilters.parse("action.projectile_spawn_accepted radius.10", 10_000_000L)
                        .eventTypes());

        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("action.break_block", 10_000_000L));
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse(
                        "action.break_block user.Alex time.1h include.stone exclude.dirt radius.50",
                        10_000_000L));
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("include.stone exclude.dirt radius.50", 10_000_000L));
    }

    @Test
    void malformedLookupFiltersReturnExactFailuresOnBothRootsAndForms() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        List<InvalidLookup> invalidLookups = List.of(
                new InvalidLookup("action.break_block", "radius filter is required"),
                new InvalidLookup("radius.", "invalid filter 'radius.'; use name.value"),
                new InvalidLookup("radius.0", "radius must be a positive number"),
                new InvalidLookup("who.Alex radius.10", "unknown filter 'who'"),
                new InvalidLookup("action.break_block a.join radius.10", "filter 'action' may be used once"),
                new InvalidLookup("include.stone exclude.dirt radius.10",
                        "include and exclude filters cannot be combined"),
                new InvalidLookup("action.break_block user.Alex include.stone time.1h radius.10 exclude.dirt",
                        "at most 5 filters are allowed"));

        for (String root : List.of("ig", "itemgraph")) {
            for (String lookupPrefix : List.of(root + " lookup ", root + " lookup filters ")) {
                for (InvalidLookup invalid : invalidLookups) {
                    CommandSourceStack source = source();
                    ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

                    assertEquals(0, dispatcher.execute(lookupPrefix + invalid.filters(), source),
                            lookupPrefix + invalid.filters());
                    verify(source).sendFailure(failure.capture());
                    assertEquals("[ItemGraph] Invalid lookup filter: " + invalid.expectedDetail(),
                            failure.getValue().getString(), lookupPrefix + invalid.filters());
                    verify(source, never()).sendSuccess(any(), anyBoolean());
                }
            }
        }
    }

    private record InvalidLookup(String filters, String expectedDetail) { }

    @Test
    void lookupPageSessionHasABoundedThirtyMinuteLifetime() {
        long createdAt = 1_000L;
        assertFalse(ItemGraphCommands.pageSessionExpired(createdAt, createdAt + 1_799_999L));
        assertFalse(ItemGraphCommands.pageSessionExpired(createdAt, createdAt + 1_800_000L));
        assertTrue(ItemGraphCommands.pageSessionExpired(createdAt, createdAt + 1_800_001L));
    }

    @Test
    void nativeLiteralLookupVariantsKeepPublishedArgumentOrder() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup player Alex BREAK_BLOCK 5 60", source),
                "player audit lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup player Alex ALL 5 60", source),
                "uppercase player all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup player Alex aLl 5 60", source),
                "mixed-case player all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup player Alex bReAk_BloCk 5 60", source),
                "mixed-case player block lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup page 2 break_block 5 60", source),
                "lowercase paged audit lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup page 2 ALL 5 60", source),
                "uppercase paged all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup page 2 aLl 5 60", source),
                "mixed-case paged all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup page 2 bReAk_BloCk 5 60", source),
                "mixed-case paged block lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup near minecraft:overworld 0 64 0 10 SHOOT_ITEM 5 60", source),
                "near audit lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup near minecraft:overworld 0 64 0 10 ALL 5 60", source),
                "uppercase near all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup near minecraft:overworld 0 64 0 10 aLl 5 60", source),
                "mixed-case near all-events lookup syntax");
        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup near minecraft:overworld 0 64 0 10 bReAk_BloCk 5 60", source),
                "mixed-case near block lookup syntax");
    }

    @Test
    void logicalInspectionTargetsIncludeDoubleChestAndDoorPartners() {
        Level level = mock(Level.class);

        BlockPos chest = new BlockPos(10, 64, 10);
        var leftChest = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, ChestType.LEFT)
                .setValue(ChestBlock.FACING, Direction.NORTH);
        BlockPos chestPartner = chest.relative(ChestBlock.getConnectedDirection(leftChest));
        var rightChest = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.TYPE, ChestType.RIGHT)
                .setValue(ChestBlock.FACING, Direction.NORTH);
        when(level.getBlockState(chest)).thenReturn(leftChest);
        when(level.getBlockState(chestPartner)).thenReturn(rightChest);

        assertEquals(List.of(
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(10, 64, 10),
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(
                                chestPartner.getX(), chestPartner.getY(), chestPartner.getZ())),
                BlockInspectionTargets.resolve(level, chest));
        assertEquals(chest.compareTo(chestPartner) <= 0 ? chest : chestPartner,
                BlockInspectionTargets.canonicalPosition(level, chest));

        when(level.getBlockState(chestPartner)).thenReturn(rightChest.setValue(ChestBlock.FACING, Direction.EAST));
        assertEquals(1, BlockInspectionTargets.resolve(level, chest).size(),
                "double chest halves with different facings must not be merged");

        when(level.getBlockState(chestPartner)).thenReturn(leftChest);
        assertEquals(1, BlockInspectionTargets.resolve(level, chest).size(),
                "two same-side chest states must not be treated as one logical chest");

        BlockPos door = new BlockPos(20, 64, 20);
        var lowerDoor = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockPos upperDoor = door.above();
        var upperDoorState = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        when(level.getBlockState(door)).thenReturn(lowerDoor);
        when(level.getBlockState(upperDoor)).thenReturn(upperDoorState);

        assertEquals(List.of(
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(20, 64, 20),
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(20, 65, 20)),
                BlockInspectionTargets.resolve(level, door));

        BlockPos target = new BlockPos(30, 64, 30);
        List<net.minecraft.world.level.block.Block> pinnedTargets = List.of(
                Blocks.OAK_FENCE_GATE, Blocks.DISPENSER, Blocks.NOTE_BLOCK, Blocks.CHEST,
                Blocks.FURNACE, Blocks.LEVER, Blocks.OAK_TRAPDOOR, Blocks.OAK_DOOR,
                Blocks.BREWING_STAND, Blocks.REPEATER, Blocks.HOPPER, Blocks.DROPPER,
                Blocks.SHULKER_BOX, Blocks.BARREL, Blocks.GRINDSTONE, Blocks.STONE_BUTTON,
                Blocks.LOOM, Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.ENCHANTING_TABLE,
                Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.CRAFTER, Blocks.VAULT,
                Blocks.DAYLIGHT_DETECTOR, Blocks.OAK_SIGN, Blocks.LECTERN, Blocks.BEACON);
        for (net.minecraft.world.level.block.Block block : pinnedTargets) {
            when(level.getBlockState(target)).thenReturn(block.defaultBlockState());
            assertTrue(BlockInspectionTargets.isGriefLoggerFunctionalBlock(level, target),
                    block + " must remain in the exact pinned GriefLogger target set");
        }
        when(level.getBlockState(target)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        assertTrue(BlockInspectionTargets.isInspectableRightClickTarget(level, target));
        assertTrue(BlockInspectionTargets.isGriefLoggerBlockInteraction(
                        level, target, net.minecraft.world.InteractionHand.MAIN_HAND),
                "pinned GriefLogger functional blocks are recorded for the main hand");
        assertFalse(BlockInspectionTargets.isGriefLoggerBlockInteraction(
                        level, target, net.minecraft.world.InteractionHand.OFF_HAND),
                "the pinned GriefLogger hook ignores off-hand callbacks");
        when(level.getBlockState(target)).thenReturn(Blocks.STONE.defaultBlockState());
        assertFalse(BlockInspectionTargets.isInspectableRightClickTarget(level, target));
        assertFalse(BlockInspectionTargets.isGriefLoggerBlockInteraction(
                        level, target, net.minecraft.world.InteractionHand.MAIN_HAND),
                "ordinary blocks are not in the pinned functional-block set");
        when(level.getBlockEntity(target)).thenReturn(mock(net.minecraft.world.level.block.entity.BlockEntity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(net.minecraft.world.Container.class)));
        assertTrue(BlockInspectionTargets.isInspectableRightClickTarget(level, target),
                "modded block entities implementing Container remain inspectable");
        assertFalse(BlockInspectionTargets.isGriefLoggerBlockInteraction(
                        level, target, net.minecraft.world.InteractionHand.MAIN_HAND),
                "ItemGraph's modded-container inspection extension is not mislabeled as GriefLogger action parity");
    }

    private void assertParsedCompletely(com.mojang.brigadier.ParseResults<CommandSourceStack> parsed,
                                        String description) {
        assertFalse(parsed.getReader().canRead(),
                description + " was not consumed: " + parsed.getReader().getRemaining());
        assertTrue(parsed.getExceptions().isEmpty(),
                description + " produced parse errors: " + parsed.getExceptions());
    }

    private Object argumentValue(com.mojang.brigadier.ParseResults<CommandSourceStack> parsed, String name) {
        var context = parsed.getContext();
        while (context != null) {
            var argument = context.getArguments().get(name);
            if (argument != null) {
                return argument.getResult();
            }
            context = context.getChild();
        }
        throw new AssertionError("Parsed command did not contain argument " + name);
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        return dispatcher;
    }

    private CommandSourceStack source() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getEntity()).thenReturn(mock(ServerPlayer.class));
        when(source.getOnlinePlayerNames()).thenReturn(List.of("Alex", "Steve"));
        when(source.levels()).thenReturn(Set.of(Level.OVERWORLD));
        return source;
    }

    private static CommandSourceStack sourceForPlayer(ServerPlayer player) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getEntity()).thenReturn(player);
        when(source.hasPermission(2)).thenReturn(true);
        return source;
    }

    private List<String> captureSuccesses(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        doAnswer(invocation -> {
            Supplier<Component> message = invocation.getArgument(0);
            lines.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        return lines;
    }

    private Set<String> childNames(CommandNode<CommandSourceStack> root, String child) {
        CommandNode<CommandSourceStack> node = root.getChild(child);
        assertNotNull(node, "missing /ig " + child);
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
    }

    private void assertSuggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                   CommandSourceStack source, String input, String... expected) {
        List<String> suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
                .join().getList().stream().map(Suggestion::getText).toList();
        for (String text : expected) {
            assertTrue(suggestions.contains(text), input + " did not suggest " + text + ": " + suggestions);
        }
    }
}
