package com.itemgraph.command;

import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.QueryWindow;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemGraphCommandsHelpTest {

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
        assertTrue(ItemGraphCommands.shouldOfferNextAuditPage(1, 30, 0, 30));
        assertFalse(ItemGraphCommands.shouldOfferNextAuditPage(334, 30, 9_990, 30));
        assertFalse(ItemGraphCommands.shouldOfferNextAuditPage(334, 30, 9_990, 29));
    }

    @Test
    void bareFullRootAndAliasShowOverview() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/itemgraph is the full command root")));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig help [topic]")));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig gui container <dimension>")));

        successes.clear();
        assertEquals(1, dispatcher.execute("ig", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig inspect [on|off|status]")));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig page <page>")));
    }

    @Test
    void helpListsEveryRegisteredCommandPath() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertEquals(Set.of("itemgraph", "ig"), dispatcher.getRoot().getChildren().stream()
                        .map(CommandNode::getName).collect(Collectors.toSet()),
                "ItemGraph exposes its two named roots without GriefLogger command aliases");
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("itemgraph");
        assertNotNull(root);
        assertEquals(Set.of("help", "status", "audit", "lookup", "ingest", "event", "explain", "page", "trace", "gui", "inspect"),
                root.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
        assertEquals(Set.of("now", "history"), childNames(root, "ingest"));
        assertEquals(Set.of("item", "player", "container"), childNames(root, "trace"));
        assertEquals(Set.of("item", "player", "container"), childNames(root, "gui"));
        assertEquals(Set.of("on", "off", "status"), childNames(root, "inspect"));

        for (String topLevel : Set.of("help", "status", "audit", "lookup", "ingest", "event", "explain", "page", "trace", "gui", "inspect")) {
            assertNotNull(CommandHelp.topicLines(topLevel), "missing help topic for /ig " + topLevel);
        }
        for (String path : List.of("ingest now", "ingest history", "trace item", "trace player", "trace container",
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
        assertTrue(pageHelp.contains("Syntax: /ig page <page>"));
        assertTrue(pageHelp.contains("per-player"));
    }

    @Test
    void helpTopicReturnsSyntaxDefaultsPermissionAndExample() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph help", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("limit=20") && line.contains("100")));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help trace item", source));
        String itemHelp = String.join("\n", successes);
        assertTrue(itemHelp.contains("Syntax: /ig trace item <query> [limit] [sinceMinutes]"));
        assertTrue(itemHelp.contains("Permission: level 2"));
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
        assertTrue(inspectHelp.contains("Permission: level 2"));
        assertTrue(inspectHelp.contains("does not consume the held item"));
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
            assertTrue(lines.contains("Permission: level 2"), topic);
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
        List<String> publishedExamples = List.of(
                "action.break_block include.diamond_ore radius.50",
                "user.Griefer42 time.1h radius.100",
                "action.remove_item \"include.diamond,netherite_ingot\" radius.30 time.6h",
                "\"action.break_block,place_block\" radius.15",
                "\"action.join,quit\" time.10m radius.100",
                "user.MinerJoe action.break_block \"exclude.stone,dirt,cobblestone,gravel\" radius.50",
                "radius.20 action.remove_item exclude.cobblestone",
                "include.diamond_block radius.100",
                "action.remove_item \"include.netherite_sword,netherite_pickaxe,netherite_ingot\" time.1d radius.50",
                "radius.5",
                "include.tnt time.30m radius.25",
                "\"action.add_item,remove_item\" time.90m radius.50",
                "action.join time.3d radius.50",
                "user.Notch radius.50",
                "\"user.Player1,Player2\" action.break_block time.1d radius.50");

        for (String example : publishedExamples) {
            assertParsedCompletely(dispatcher.parse("ig lookup " + example, source),
                    "published /ig lookup " + example);
            assertParsedCompletely(dispatcher.parse("itemgraph lookup " + example, source),
                    "published /itemgraph lookup " + example);
        }
    }

    @Test
    void lookupFiltersFollowPublishedAliasesRadiusAndConflictRules() {
        AuditLookupFilters parsed = AuditLookupFilters.parse(
                "a.break_block u.Alex i.diamond_ore t.1h r.50", 10_000_000L);
        assertEquals(List.of("BREAK_BLOCK"), parsed.eventTypes());
        assertEquals(List.of("Alex"), parsed.playerNames());
        assertEquals(List.of("minecraft:diamond_ore"), parsed.includeSubjects());
        assertEquals(50.0, parsed.radiusBlocks());
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
