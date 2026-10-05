package com.itemgraph.fabric;

import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.command.ItemGraphPermissions;
import com.itemgraph.command.InspectionService;
import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.QueryWindow;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
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
import net.minecraft.world.flag.FeatureFlags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that Fabric executes the loader-neutral command contract used for
 * GriefLogger-compatible lookup, paging, inspection, and suggestions.
 */
class FabricItemGraphCommandsParityTest {
    private final InspectionService inspectionService = InspectionService.getInstance();

    @Test
    void vanillaCommandTreePacketCodecRoundTripsBothCommandRootsOnFabric() {
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
    void lookupHelpUsesTheSharedAuditEventCatalogOnFabric() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> messages = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph help lookup", source));
        assertTrue(String.join("\n", messages).contains(
                "eventType: " + String.join(", ", AuditEventQueryService.EVENT_TYPES) + "."));
    }

    @Test
    void protectedEvidenceSurfacesFailClosedWithoutAuditOnFabric() throws Exception {
        ItemGraphPermissions.setChecker((checkedSource, node) -> Set.of(ItemGraphPermissions.COMMAND,
                ItemGraphPermissions.EVENT, ItemGraphPermissions.EXPLAIN, ItemGraphPermissions.TRACE,
                ItemGraphPermissions.GUI).contains(node));
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        for (String command : List.of("ig event 1", "ig explain 1", "ig trace item diamond", "ig gui item diamond")) {
            assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                    () -> dispatcher.execute(command, source), command);
            verify(source, never()).sendSuccess(any(), anyBoolean());
        }
    }

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // The shared registries may already be bootstrapped by another test.
        }
    }

    @AfterEach
    void clearInspectionState() {
        inspectionService.clear();
        ItemGraphPermissions.setChecker(null);
    }

    @Test
    void inspectCommandExecutesBothRootsWithDeterministicStateAndMessagesOnFabric() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        UUID playerUuid = UUID.randomUUID();
        CommandSourceStack source = sourceForPlayer(playerUuid);
        List<String> messages = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph inspect", source));
        assertTrue(inspectionService.isEnabled(playerUuid));
        assertEquals(1, dispatcher.execute("ig inspect status", source));
        assertTrue(inspectionService.isEnabled(playerUuid), "status must not change inspection mode");
        assertEquals(1, dispatcher.execute("ig inspect on", source));
        assertTrue(inspectionService.isEnabled(playerUuid));
        assertEquals(1, dispatcher.execute("itemgraph inspect off", source));
        assertFalse(inspectionService.isEnabled(playerUuid));
        assertEquals(1, dispatcher.execute("ig inspect off", source));
        assertFalse(inspectionService.isEnabled(playerUuid));
        assertEquals(1, dispatcher.execute("ig inspect", source));
        assertTrue(inspectionService.isEnabled(playerUuid));
        assertEquals(1, dispatcher.execute("itemgraph inspect", source));
        assertFalse(inspectionService.isEnabled(playerUuid));

        assertEquals(List.of(
                "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history; use /ig inspect off to disable.",
                "[ItemGraph] Inspection is enabled.",
                "[ItemGraph] Inspection is already enabled.",
                "[ItemGraph] Inspection disabled.",
                "[ItemGraph] Inspection is already disabled.",
                "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history; use /ig inspect off to disable.",
                "[ItemGraph] Inspection disabled."), messages);
    }

    @Test
    void inspectCommandRejectsPermissionLevelBelowTwoOnFabric() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        UUID playerUuid = UUID.randomUUID();
        CommandSourceStack denied = sourceForPlayer(playerUuid);
        when(denied.hasPermission(2)).thenReturn(false);
        List<String> messages = captureSuccesses(denied);

        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("itemgraph inspect on", denied));
        assertFalse(inspectionService.isEnabled(playerUuid));
        assertTrue(messages.isEmpty(), "a denied inspect command must not emit a success receipt");
        verify(denied, never()).sendFailure(any());
    }

    @Test
    void namedPermissionNodesAllowLookupAndExplicitlyDenyIndependentSurfacesOnFabric() {
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

        assertFalse(dispatcher.parse("itemgraph lookup BREAK_BLOCK", levelOne).getReader().canRead());
        for (String denied : List.of("itemgraph inspect on", "itemgraph page 1", "itemgraph trace item stone",
                "itemgraph event 1", "itemgraph explain 1", "itemgraph audit", "itemgraph gui item stone",
                "itemgraph ingest now", "itemgraph ingest history")) {
            assertTrue(dispatcher.parse(denied, levelOne).getReader().canRead(),
                    "explicit permission denial must win for " + denied);
        }

        CommandSourceStack operator = source();
        assertFalse(ItemGraphPermissions.check(operator, ItemGraphPermissions.INSPECT),
                "explicit deny must override operator permission");
        assertTrue(ItemGraphPermissions.check(operator, "itemgraph.unset"),
                "unset permission must use the vanilla level-2 fallback");
    }

    @Test
    void absentPermissionProviderKeepsVanillaLevelTwoFallbackOnFabric() {
        CommandSourceStack levelOne = source();
        when(levelOne.hasPermission(2)).thenReturn(false);
        ItemGraphPermissions.setChecker(null);
        assertFalse(ItemGraphPermissions.canUse(levelOne, ItemGraphPermissions.LOOKUP));

        assertTrue(ItemGraphPermissions.canUse(source(), ItemGraphPermissions.LOOKUP));
    }

    @Test
    void publishedFilterPageAndInspectSyntaxParsesOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup action.break_block include.diamond_ore radius.20", source),
                "direct GriefLogger filter syntax");
        assertParsedCompletely(dispatcher.parse(
                "ig lookup filters action.break_block radius.20", source),
                "explicit ItemGraph filter syntax");
        assertParsedCompletely(dispatcher.parse("itemgraph page 2", source),
                "standalone page syntax");
        assertParsedCompletely(dispatcher.parse("ig inspect on", source),
                "inspection syntax");
    }

    @Test
    void publishedFilterSuggestionsRemainAvailableOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertSuggestions(dispatcher, source, "itemgraph lookup ",
                "all", "BREAK_BLOCK", "CHAT_MESSAGE", "INTERACT_ENTITY");
        assertSuggestions(dispatcher, source, "itemgraph lookup action.break_block ",
                "user.", "include.", "exclude.", "time.", "radius.");
        assertSuggestions(dispatcher, source, "ig inspect ", "on", "off", "status");
    }

    @Test
    void commandPermissionAndInvalidPageBehaviorMatchOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack denied = source();
        when(denied.hasPermission(2)).thenReturn(false);

        for (String command : List.of("ig lookup radius.10", "itemgraph lookup radius.10",
                "ig page 2", "itemgraph page 2", "ig inspect on", "itemgraph inspect on")) {
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, denied);
            assertTrue(parsed.getReader().canRead(),
                    "permission level 2 must protect " + command);
        }
        assertTrue(suggestions(dispatcher, denied, "ig ").isEmpty(),
                "an unauthorized player must not receive protected command suggestions");

        assertTrue(dispatcher.parse("ig page 0", source()).getReader().canRead(),
                "page zero must be rejected deterministically");
        assertTrue(dispatcher.parse("itemgraph page -1", source()).getReader().canRead(),
                "negative page numbers must be rejected deterministically");
    }

    @Test
    void allPublishedLookupExamplesParseForBothItemGraphRootsOnFabric() {
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
    void publishedFilterAliasesAndBoundsAreAppliedOnFabric() {
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
                () -> AuditLookupFilters.parse("action.break_block", 10_000_000L),
                "radius is required by the published lookup contract");
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse(
                        "action.break_block user.Alex time.1h include.stone exclude.dirt radius.50",
                        10_000_000L),
                "a lookup is limited to five filters");
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("include.stone exclude.dirt radius.50", 10_000_000L),
                "include and exclude are mutually exclusive");
    }

    @Test
    void malformedLookupFiltersReturnExactFailuresOnBothRootsAndFormsOnFabric() throws Exception {
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
    void filterSuggestionsOfferPublishedValuesAndRespectUsedFilterLimitsOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertSuggestions(dispatcher, source, "itemgraph lookup action.",
                "place_block", "break_block", "interact_block", "join", "quit", "craft_item");
        assertSuggestions(dispatcher, source, "itemgraph lookup a.", "break_block", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup user.", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph lookup u.", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph lookup include.", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph lookup exclude.", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph lookup action.break_block,",
                "place_block", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup \"action.break_block,",
                "place_block", "join");

        assertTrue(suggestions(dispatcher, source, "itemgraph lookup action.join ").stream()
                        .noneMatch(value -> value.startsWith("action.")),
                "the same filter must not be suggested twice");
        assertTrue(suggestions(dispatcher, source, "itemgraph lookup include.stone ").stream()
                        .noneMatch(value -> value.startsWith("exclude.")),
                "exclude must not be suggested after include");
        assertTrue(suggestions(dispatcher, source,
                "itemgraph lookup action.join user.Alex include.stone time.1h radius.50 ").isEmpty(),
                "no sixth filter is suggested");
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        return dispatcher;
    }

    private static CommandSourceStack source() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getEntity()).thenReturn(mock(ServerPlayer.class));
        when(source.getOnlinePlayerNames()).thenReturn(List.of("Alex", "Steve"));
        when(source.levels()).thenReturn(Set.of(Level.OVERWORLD));
        return source;
    }

    private static CommandSourceStack sourceForPlayer(UUID playerUuid) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerUuid);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getEntity()).thenReturn(player);
        return source;
    }

    private static List<String> captureSuccesses(CommandSourceStack source) {
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> {
            Supplier<Component> message = invocation.getArgument(0);
            messages.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        return messages;
    }

    private static void assertParsedCompletely(ParseResults<CommandSourceStack> parsed,
                                               String description) {
        assertFalse(parsed.getReader().canRead(),
                description + " was not consumed: " + parsed.getReader().getRemaining());
        assertTrue(parsed.getExceptions().isEmpty(),
                description + " produced parse errors: " + parsed.getExceptions());
    }

    private static Object argumentValue(ParseResults<CommandSourceStack> parsed, String name) {
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

    private static void assertSuggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                          CommandSourceStack source, String input,
                                          String... expected) {
        List<String> suggestions = suggestions(dispatcher, source, input);
        for (String text : expected) {
            assertTrue(suggestions.contains(text),
                    input + " did not suggest " + text + ": " + suggestions);
        }
    }

    private static List<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                            CommandSourceStack source, String input) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
                .join().getList().stream().map(Suggestion::getText).toList();
    }
}
