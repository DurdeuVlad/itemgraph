package com.itemgraph.fabric;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.InspectionService;
import com.itemgraph.command.BlockInspectionTargets;
import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.command.ItemGraphPermissions;
import com.itemgraph.ingest.InternalObservationService;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.any;

class FabricNativeAuditEventListenerTest {
    private final InspectionService inspections = InspectionService.getInstance();

    @AfterEach
    void clearInspectionState() {
        inspections.clear();
        ItemGraphPermissions.setChecker(null);
        FabricNativeAuditEventListener.clearPendingDrops();
        // A denied handleEntityUse leaves denialRecordedByListener set until the
        // next aggregate callback drains it — drain so no later test loses a row.
        com.itemgraph.ingest.EntityInteractionEvidence.recordFabricCallbackResult(
                null, InteractionResult.PASS);
    }

    @Test
    void disconnectCleanupTargetsOnlyThatPlayersPageAndInspectionState() {
        UUID loggedOutPlayer = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        inspections.setEnabled(loggedOutPlayer, true);
        inspections.setEnabled(otherPlayer, true);

        try (MockedStatic<ItemGraphCommands> commands = mockStatic(ItemGraphCommands.class)) {
            FabricNativeAuditEventListener.clearDisconnectState(loggedOutPlayer);

            commands.verify(() -> ItemGraphCommands.clearPageSession(loggedOutPlayer));
            commands.verify(ItemGraphCommands::clearPageSessions, org.mockito.Mockito.never());
        }

        assertFalse(inspections.isEnabled(loggedOutPlayer));
        assertTrue(inspections.isEnabled(otherPlayer));
    }

    @Test
    void disconnectHandlerClearsOnlyThatPlayersStateAndRecordsPlayerQuit() {
        UUID disconnectedPlayerId = UUID.randomUUID();
        UUID otherPlayerId = UUID.randomUUID();
        inspections.setEnabled(disconnectedPlayerId, true);
        inspections.setEnabled(otherPlayerId, true);

        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.getUUID()).thenReturn(disconnectedPlayerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(disconnectedPlayerId, "disconnect-test"));
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(new BlockPos(4, 64, 2));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        InternalObservationService service = mock(InternalObservationService.class);

        try (MockedStatic<InternalObservationService> services = mockStatic(InternalObservationService.class);
             MockedStatic<ItemGraphCommands> commands = mockStatic(ItemGraphCommands.class);
             MockedStatic<FabricContainerSessionListener> containers =
                     mockStatic(FabricContainerSessionListener.class)) {
            services.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.onDisconnect(player);

            commands.verify(() -> ItemGraphCommands.clearPageSession(disconnectedPlayerId));
            containers.verify(() -> FabricContainerSessionListener.onMenuClosing(player));
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("PLAYER_QUIT", captured.getValue().eventType());
        assertEquals(disconnectedPlayerId.toString(), captured.getValue().playerUuid());
        assertFalse(inspections.isEnabled(disconnectedPlayerId));
        assertTrue(inspections.isEnabled(otherPlayerId));
    }

    @Test
    void entityAttemptCarriesTargetUuidAndDoesNotClaimCompletion() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ArmorStand target = mock(ArmorStand.class);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.blockPosition()).thenReturn(new BlockPos(1, 70, 2));
        when(target.getUUID()).thenReturn(targetUuid);
        doReturn(EntityType.ARMOR_STAND).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordEntityInteractionAttempt(
                    player, level, InteractionHand.MAIN_HAND, target);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        var event = captured.getValue();
        assertEquals("INTERACT_ENTITY", event.eventType());
        assertEquals("minecraft:armor_stand", event.subjectId());
        assertEquals("outcome=attempt hand=main_hand target_uuid=" + targetUuid
                + " target_support=armor_stand_method_result"
                + " completion=armor_stand_return_hook", event.detail());
        assertEquals("minecraft:overworld", event.levelName());
    }

    @Test
    void activeContainerInspectionConsumesClickAfterUnifiedHistoryAccepts() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        BlockPos pos = BlockPos.ZERO;
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.tryOpenInspection(
                inspections,
                (openingPlayer, openingLevel, clickedPos) -> {
                    assertSame(player, openingPlayer);
                    assertSame(level, openingLevel);
                    assertEquals(pos, clickedPos);
                    opens.incrementAndGet();
                    return 1;
                },
                player, level, pos, Direction.UP);

        assertEquals(InteractionResult.SUCCESS, result);
        assertEquals(1, opens.get());
    }

    @Test
    void containerRightClickRoutesToFlowBrowserAndKeepsExactDimensionAndPosition() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        when(level.dimension()).thenReturn(Level.NETHER);
        BlockPos pos = BlockPos.ZERO;
        AtomicInteger blockHistoryOpens = new AtomicInteger();
        AtomicInteger flowBrowserOpens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.tryOpenInspection(
                inspections,
                (p, l, clickedPos) -> { blockHistoryOpens.incrementAndGet(); return 1; },
                (openingPlayer, openingLevel, clickedPos) -> {
                    assertSame(player, openingPlayer);
                    assertSame(level, openingLevel);
                    assertEquals(pos, clickedPos);
                    assertEquals(Level.NETHER, openingLevel.dimension());
                    flowBrowserOpens.incrementAndGet();
                    return 1;
                },
                player, level, pos, Direction.UP);

        assertEquals(InteractionResult.SUCCESS, result);
        assertEquals(0, blockHistoryOpens.get());
        assertEquals(1, flowBrowserOpens.get());
    }

    @Test
    void bothDoubleChestHalvesOpenTheCanonicalFlowBrowserTarget() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos left = new BlockPos(10, 64, -20);
        var leftState = Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        var rightState = leftState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        BlockPos right = left.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(leftState));
        BlockPos anchor = left.getX() <= right.getX() ? left : right;
        when(level.getBlockState(left)).thenReturn(leftState);
        when(level.getBlockState(right)).thenReturn(rightState);
        BlockEntity chest = mock(BlockEntity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Container.class));
        when(level.getBlockEntity(left)).thenReturn(chest);
        when(level.getBlockEntity(right)).thenReturn(chest);
        AtomicInteger opens = new AtomicInteger();
        FabricNativeAuditEventListener.ContainerFlowOpener opener = (p, l, pos) -> {
            assertEquals(anchor, pos);
            opens.incrementAndGet();
            return 1;
        };

        assertEquals(InteractionResult.SUCCESS, FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> { fail("double chest must open the flow browser"); return 0; },
                opener, player, level, left, Direction.UP));
        assertEquals(InteractionResult.SUCCESS, FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> { fail("double chest must open the flow browser"); return 0; },
                opener, player, level, right, Direction.UP));
        assertEquals(2, opens.get());
    }

    @Test
    void rejectedContainerFlowQueryPreservesVanillaContainerInteraction() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        AtomicInteger blockHistoryOpens = new AtomicInteger();
        AtomicInteger flowBrowserOpens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.tryOpenInspection(
                inspections,
                (p, l, pos) -> { blockHistoryOpens.incrementAndGet(); return 1; },
                (p, l, pos) -> { flowBrowserOpens.incrementAndGet(); return 0; },
                player, level, BlockPos.ZERO, Direction.UP);

        assertNull(result);
        assertEquals(0, blockHistoryOpens.get(), "rejected flow must not silently switch query types");
        assertEquals(1, flowBrowserOpens.get());
    }

    @Test
    void nativeBlockInteractionTargetMatchesPinnedFunctionalBlocksAndMainHand() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        ServerLevel level = mock(ServerLevel.class);
        BlockPos pos = BlockPos.ZERO;
        when(level.getBlockState(pos)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        List<Block> pinnedTargets = List.of(
                Blocks.OAK_FENCE_GATE, Blocks.DISPENSER, Blocks.NOTE_BLOCK, Blocks.CHEST,
                Blocks.FURNACE, Blocks.LEVER, Blocks.OAK_TRAPDOOR, Blocks.OAK_DOOR,
                Blocks.BREWING_STAND, Blocks.REPEATER, Blocks.HOPPER, Blocks.DROPPER,
                Blocks.SHULKER_BOX, Blocks.BARREL, Blocks.GRINDSTONE, Blocks.STONE_BUTTON,
                Blocks.LOOM, Blocks.CRAFTING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.ENCHANTING_TABLE,
                Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.CRAFTER, Blocks.VAULT,
                Blocks.DAYLIGHT_DETECTOR, Blocks.OAK_SIGN, Blocks.LECTERN, Blocks.BEACON);
        for (Block block : pinnedTargets) {
            when(level.getBlockState(pos)).thenReturn(block.defaultBlockState());
            org.junit.jupiter.api.Assertions.assertTrue(
                    BlockInspectionTargets.isGriefLoggerFunctionalBlock(level, pos),
                    block + " must remain in the exact pinned GriefLogger target set");
        }
        when(level.getBlockState(pos)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        ServerPlayer player = mock(ServerPlayer.class);
        UUID playerUuid = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        InternalObservationService service = mock(InternalObservationService.class);

        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordBlockInteractionAttempt(
                    player, level, pos, InteractionHand.MAIN_HAND);
            FabricNativeAuditEventListener.recordBlockInteractionAttempt(
                    player, level, pos, InteractionHand.OFF_HAND);

            when(level.getBlockState(pos)).thenReturn(Blocks.STONE.defaultBlockState());
            FabricNativeAuditEventListener.recordBlockInteractionAttempt(
                    player, level, pos, InteractionHand.MAIN_HAND);

            when(level.getBlockEntity(pos)).thenReturn(mock(BlockEntity.class,
                    org.mockito.Mockito.withSettings().extraInterfaces(Container.class)));
            org.junit.jupiter.api.Assertions.assertTrue(BlockInspectionTargets.isInspectableRightClickTarget(level, pos));
            FabricNativeAuditEventListener.recordBlockInteractionAttempt(
                    player, level, pos, InteractionHand.MAIN_HAND);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_BLOCK_ATTEMPT", captured.getValue().eventType());
        assertEquals("outcome=attempt", captured.getValue().detail());
        assertEquals(playerUuid.toString(), captured.getValue().playerUuid());
        assertEquals("Alex", captured.getValue().playerName());
        assertEquals("minecraft:crafting_table", captured.getValue().subjectId());
        assertEquals("minecraft:overworld", captured.getValue().levelName());
    }

    @Test
    void inactiveUnsupportedAndRejectedFabricInspectionPreserveVanillaBehavior() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel unsupportedLevel = org.mockito.Mockito.mock(ServerLevel.class);
        when(unsupportedLevel.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        AtomicInteger opens = new AtomicInteger();

        assertNull(FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> { opens.incrementAndGet(); return 1; },
                player, unsupportedLevel, BlockPos.ZERO, Direction.UP));

        inspections.setEnabled(playerUuid, true);
        assertNull(FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> { opens.incrementAndGet(); return 0; },
                player, serverLevelWithContainer(), BlockPos.ZERO, Direction.UP));
        assertEquals(1, opens.get());
    }

    @Test
    void activeBlockInspectionUsesTheUnifiedHistoryOpener() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = org.mockito.Mockito.mock(ServerLevel.class);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.tryOpenInspection(
                inspections,
                (openingPlayer, openingLevel, clickedPos) -> {
                    assertSame(player, openingPlayer);
                    assertSame(level, openingLevel);
                    assertEquals(BlockPos.ZERO, clickedPos);
                    opens.incrementAndGet();
                    return 1;
                },
                player, level, BlockPos.ZERO, Direction.UP);

        assertEquals(InteractionResult.SUCCESS, result);
        assertEquals(1, opens.get());
    }

    @Test
    void rightClickInspectionPassesDoorAndDoubleChestTargetsIntoUnifiedHistoryOpener() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos chest = new BlockPos(10, 64, 10);
        var leftChest = Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.ChestType.LEFT)
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.NORTH);
        BlockPos chestPartner = chest.relative(
                net.minecraft.world.level.block.ChestBlock.getConnectedDirection(leftChest));
        var rightChest = Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.ChestType.RIGHT)
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, Direction.NORTH);
        BlockPos door = new BlockPos(20, 64, 20);
        when(level.getBlockState(chest)).thenReturn(leftChest);
        when(level.getBlockState(chestPartner)).thenReturn(rightChest);
        when(level.getBlockState(door)).thenReturn(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        when(level.getBlockState(door.above())).thenReturn(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));

        Map<BlockPos, List<com.itemgraph.query.AuditEventQueryService.ExactPosition>> opened =
                new LinkedHashMap<>();
        FabricNativeAuditEventListener.BlockHistoryOpener opener = (openingPlayer, openingLevel, clicked) -> {
            opened.put(clicked, BlockInspectionTargets.resolve(openingLevel, clicked));
            return 1;
        };

        assertEquals(InteractionResult.SUCCESS, FabricNativeAuditEventListener.handleBlockUse(
                inspections, opener, player, level, InteractionHand.MAIN_HAND, chestPartner, Direction.UP));
        assertEquals(InteractionResult.SUCCESS, FabricNativeAuditEventListener.handleBlockUse(
                inspections, opener, player, level, InteractionHand.MAIN_HAND, door.above(), Direction.NORTH));

        assertEquals(List.of(
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(chestPartner.getX(),
                                chestPartner.getY(), chestPartner.getZ()),
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(chest.getX(),
                                chest.getY(), chest.getZ())),
                opened.get(chestPartner), "the clicked chest half must resolve both exact evidence positions");
        assertEquals(List.of(
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(door.getX(),
                                door.getY() + 1, door.getZ()),
                        new com.itemgraph.query.AuditEventQueryService.ExactPosition(door.getX(),
                                door.getY(), door.getZ())),
                opened.get(door.above()), "clicking the upper door must resolve the full two-block door");
    }

    @Test
    void acceptedContainerInspectionDoesNotEmitGameplayInteractionEvidence() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        BlockPos pos = BlockPos.ZERO;
        InternalObservationService service = mock(InternalObservationService.class);

        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleBlockUse(
                    inspections,
                    (p, l, target) -> {
                        throw new AssertionError("container inspection must not run block-history lookup");
                    },
                    (p, l, target) -> 1, player, level, InteractionHand.MAIN_HAND, pos, Direction.UP);
        }

        assertEquals(InteractionResult.SUCCESS, result);
        verifyNoInteractions(service);
    }

    @Test
    void ordinaryBlockClickInspectsTheAdjacentFaceHistory() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos clicked = BlockPos.ZERO;
        BlockPos target = clicked.relative(Direction.EAST);
        when(level.getBlockState(clicked)).thenReturn(Blocks.STONE.defaultBlockState());

        InteractionResult result = FabricNativeAuditEventListener.handleBlockUse(
                inspections, (p, l, pos) -> { assertEquals(target, pos); return 1; },
                player, level, InteractionHand.MAIN_HAND, clicked, Direction.EAST);

        assertEquals(InteractionResult.SUCCESS, result);
    }

    @Test
    void offHandBlockUseIsConsumedWhileInspectingWithoutRecordingEvidence() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        InternalObservationService service = mock(InternalObservationService.class);
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleBlockUse(
                    inspections, (p, l, pos) -> { opens.incrementAndGet(); return 1; },
                    player, level, InteractionHand.OFF_HAND, BlockPos.ZERO, Direction.UP);
        }

        assertEquals(InteractionResult.FAIL, result);
        assertEquals(0, opens.get());
        verifyNoInteractions(service);
    }

    @Test
    void transientlyRejectedOrdinaryBlockFallbackPreservesGameplay() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos clicked = BlockPos.ZERO;
        when(level.getBlockState(clicked)).thenReturn(Blocks.STONE.defaultBlockState());
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.handleBlockUse(
                inspections, (p, l, pos) -> { assertEquals(clicked.relative(Direction.WEST), pos);
                    opens.incrementAndGet(); return 0; },
                player, level, InteractionHand.MAIN_HAND, clicked, Direction.WEST);

        assertEquals(InteractionResult.PASS, result);
        assertEquals(1, opens.get());
    }

    @Test
    void transientlyRejectedBlockInspectionPreservesGameplay() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = org.mockito.Mockito.mock(ServerLevel.class);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());

        InteractionResult result = FabricNativeAuditEventListener.tryOpenInspection(
                inspections,
                (openingPlayer, openingLevel, clickedPos) -> 0,
                player, level, BlockPos.ZERO, Direction.UP);

        assertNull(result);
    }

    @Test
    void transientlyRejectedLeftClickInspectionPreservesBlockBreaking() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = org.mockito.Mockito.mock(ServerLevel.class);
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.tryOpenLeftClickInspection(
                inspections,
                (openingPlayer, openingLevel, pos) -> { opens.incrementAndGet(); return 0; },
                player, level, BlockPos.ZERO);

        assertNull(result, "queue rejection must allow vanilla block breaking");
        assertEquals(1, opens.get());
    }

    @Test
    void acceptedLeftClickInspectionConsumesBlockBreaking() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);

        InteractionResult result = FabricNativeAuditEventListener.tryOpenLeftClickInspection(
                inspections, (openingPlayer, openingLevel, pos) -> 1,
                player, org.mockito.Mockito.mock(ServerLevel.class), BlockPos.ZERO);

        assertEquals(InteractionResult.SUCCESS, result);
    }

    @Test
    void FabricInspectionPermissionLossDisablesModeNotifiesAndConsumes() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, false);

        assertEquals(InteractionResult.FAIL, FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> 1, player, serverLevelWithContainer(), BlockPos.ZERO, Direction.UP));
        assertFalse(inspections.isEnabled(playerUuid));
        verify(player).sendSystemMessage(any());
    }

    @Test
    void deniedAuditNonContainerUseIsConsumed() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.AUDIT));

        assertEquals(InteractionResult.FAIL, FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> 0, player, level, BlockPos.ZERO, Direction.UP));
    }

    @Test
    void rejectedContainerUsePreservesVanillaAccessOnAnyRejection() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.AUDIT));

        assertNull(FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> 0, player, serverLevelWithContainer(), BlockPos.ZERO, Direction.UP));
    }

    @Test
    void deniedAuditLeftClickIsConsumed() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.AUDIT));
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.tryOpenLeftClickInspection(
                inspections, (p, l, pos) -> { opens.incrementAndGet(); return 0; },
                player, mock(ServerLevel.class), BlockPos.ZERO);

        assertEquals(InteractionResult.FAIL, result, "a stable permission denial must not let the click break the block");
        assertEquals(1, opens.get());
    }

    @Test
    void explicitInspectNodeDenialDisablesModeAndDoesNotOpenHistory() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        AtomicInteger opens = new AtomicInteger();
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.INSPECT));

        assertEquals(InteractionResult.FAIL, FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, level, pos) -> { opens.incrementAndGet(); return 1; },
                player, serverLevelWithContainer(), BlockPos.ZERO, Direction.UP));

        assertFalse(inspections.isEnabled(playerUuid));
        assertEquals(0, opens.get(), "the named inspect denial must override vanilla operator status");
        verify(player).sendSystemMessage(any());
    }

    @Test
    void offHandBlockUseIsConsumedWhileInspectingWithoutOpeningHistory() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        AtomicInteger opens = new AtomicInteger();

        InteractionResult result = FabricNativeAuditEventListener.handleBlockUse(
                inspections, (p, l, pos) -> { opens.incrementAndGet(); return 1; },
                player, level, InteractionHand.OFF_HAND, BlockPos.ZERO, Direction.UP);

        // The off-hand twin of an inspection click must not place or use the
        // off-hand item, but it must not open a second history view either.
        assertEquals(InteractionResult.FAIL, result);
        assertEquals(0, opens.get());
    }

    @Test
    void offHandBlockUsePassesWhenNotInspecting() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);

        assertEquals(InteractionResult.PASS, FabricNativeAuditEventListener.handleBlockUse(
                inspections, (p, l, pos) -> 1,
                player, serverLevelWithContainer(), InteractionHand.OFF_HAND, BlockPos.ZERO, Direction.UP));
    }

    @Test
    void itemUseIsConsumedWhileInspecting() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);

        assertEquals(InteractionResult.FAIL, FabricNativeAuditEventListener.handleItemUse(
                inspections, player, mock(ServerLevel.class), InteractionHand.MAIN_HAND).getResult());
        verify(player.containerMenu).sendAllDataToRemote();
    }

    @Test
    void itemUsePassesWhenNotInspecting() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);

        assertEquals(InteractionResult.PASS, FabricNativeAuditEventListener.handleItemUse(
                inspections, player, mock(ServerLevel.class), InteractionHand.MAIN_HAND).getResult());
    }

    @Test
    void itemUseTwinIsConsumedAfterRevocationClearedTheMode() {
        // The revocation-detecting block click clears the mode before its twin
        // item-use packet arrives, so the consumed-interaction marker — not
        // isEnabled — must still consume the twin.
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = serverLevelWithContainer();
        when(level.getGameTime()).thenReturn(100L);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.INSPECT));

        assertEquals(InteractionResult.FAIL, FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> 1, player, level, BlockPos.ZERO, Direction.UP));
        assertFalse(inspections.isEnabled(playerUuid));

        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        assertEquals(InteractionResult.FAIL, FabricNativeAuditEventListener.handleItemUse(
                inspections, player, level, InteractionHand.MAIN_HAND).getResult());
    }

    @Test
    void itemUsePassesAfterConsumedMarkerExpires() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        inspections.markInteractionConsumed(playerUuid, 0L);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(InspectionService.CONSUMED_PACKET_WINDOW_TICKS + 1L);

        assertEquals(InteractionResult.PASS, FabricNativeAuditEventListener.handleItemUse(
                inspections, player, level, InteractionHand.MAIN_HAND).getResult());
    }

    @Test
    void entityUseIsDeniedWhileInspectingAndRecordsDeniedEvidence() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        Entity target = mock(Entity.class);
        when(target.blockPosition()).thenReturn(new BlockPos(3, 70, 4));
        when(target.getUUID()).thenReturn(targetUuid);
        doReturn(EntityType.PIG).when(target).getType();
        {
            net.minecraft.network.syncher.SynchedEntityData data =
                    mock(net.minecraft.network.syncher.SynchedEntityData.class);
            when(data.getNonDefaultValues()).thenReturn(java.util.List.of());
            when(target.getEntityData()).thenReturn(data);
        }
        when(target.getPassengers()).thenReturn(java.util.List.of());

        InternalObservationService service = mock(InternalObservationService.class);
        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleEntityUse(
                    inspections, player, level, InteractionHand.MAIN_HAND, target);
        }

        assertEquals(InteractionResult.FAIL, result);
        verify(player.containerMenu).sendAllDataToRemote();
        verify(player.connection, org.mockito.Mockito.atLeastOnce())
                .send(any(net.minecraft.network.protocol.Packet.class));
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        var event = captured.getValue();
        assertEquals("INTERACT_ENTITY_DENIED", event.eventType());
        assertEquals("minecraft:pig", event.subjectId());
        assertEquals("outcome=canceled hand=main_hand target_uuid=" + targetUuid
                + " target_support=callback_only target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT"
                + " completion=callback=use_entity reason=INSPECTION_MODE"
                + " held_item=minecraft:air held_count=0"
                + " held_fingerprint=26e56ae8962a68034262aa43a61146e23dba92566718b4880f921a0c006748d6",
                event.detail());
    }

    @Test
    void entityUsePassesWhenNotInspecting() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        Entity target = mock(Entity.class);
        when(target.blockPosition()).thenReturn(new BlockPos(3, 70, 4));
        when(target.getUUID()).thenReturn(UUID.randomUUID());
        doReturn(EntityType.PIG).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleEntityUse(
                    inspections, player, level, InteractionHand.MAIN_HAND, target);
        }

        assertEquals(InteractionResult.PASS, result);
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY", captured.getValue().eventType());
    }

    @Test
    void entityUseTwinIsDeniedAfterRevocationClearedTheMode() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getGameTime()).thenReturn(100L);
        inspections.markInteractionConsumed(playerUuid, 100L);
        assertFalse(inspections.isEnabled(playerUuid));
        Entity target = mock(Entity.class);
        when(target.blockPosition()).thenReturn(new BlockPos(3, 70, 4));
        when(target.getUUID()).thenReturn(UUID.randomUUID());
        doReturn(EntityType.PIG).when(target).getType();
        {
            net.minecraft.network.syncher.SynchedEntityData data =
                    mock(net.minecraft.network.syncher.SynchedEntityData.class);
            when(data.getNonDefaultValues()).thenReturn(java.util.List.of());
            when(target.getEntityData()).thenReturn(data);
        }
        when(target.getPassengers()).thenReturn(java.util.List.of());

        InternalObservationService service = mock(InternalObservationService.class);
        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleEntityUse(
                    inspections, player, level, InteractionHand.MAIN_HAND, target);
        }

        assertEquals(InteractionResult.FAIL, result);
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertTrue(captured.getValue().detail().contains("reason=INSPECTION_WINDOW"));
    }

    @Test
    void entityAttackIsDeniedWhileInspectingAndRecordsDeniedEvidence() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        Entity target = attackTarget(targetUuid);

        InternalObservationService service = mock(InternalObservationService.class);
        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleEntityAttack(
                    inspections, player, level, InteractionHand.MAIN_HAND, target);
        }

        assertEquals(InteractionResult.FAIL, result);
        verify(player.containerMenu).sendAllDataToRemote();
        verify(player.connection, org.mockito.Mockito.atLeastOnce())
                .send(any(net.minecraft.network.protocol.Packet.class));
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        var event = captured.getValue();
        assertEquals("ATTACK_ENTITY_DENIED", event.eventType());
        assertEquals("minecraft:pig", event.subjectId());
        assertEquals("outcome=canceled hand=main_hand target_uuid=" + targetUuid
                + " target_support=callback_only target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT"
                + " completion=callback=attack_entity reason=INSPECTION_MODE"
                + " held_item=minecraft:air held_count=0"
                + " held_fingerprint=26e56ae8962a68034262aa43a61146e23dba92566718b4880f921a0c006748d6",
                event.detail());
    }

    @Test
    void entityAttackPassesWhenNotInspecting() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        ServerLevel level = mock(ServerLevel.class);
        Entity target = attackTarget(UUID.randomUUID());

        InternalObservationService service = mock(InternalObservationService.class);
        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleEntityAttack(
                    inspections, player, level, InteractionHand.MAIN_HAND, target);
        }

        assertEquals(InteractionResult.PASS, result);
        verifyNoInteractions(service);
    }

    @Test
    void entityAttackTwinIsDeniedAfterRevocationClearedTheMode() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = playerWithPermission(playerUuid, true);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        player.connection = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getGameTime()).thenReturn(100L);
        inspections.markInteractionConsumed(playerUuid, 100L);
        assertFalse(inspections.isEnabled(playerUuid));
        Entity target = attackTarget(UUID.randomUUID());

        InternalObservationService service = mock(InternalObservationService.class);
        InteractionResult result;
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            result = FabricNativeAuditEventListener.handleEntityAttack(
                    inspections, player, level, InteractionHand.MAIN_HAND, target);
        }

        assertEquals(InteractionResult.FAIL, result);
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("ATTACK_ENTITY_DENIED", captured.getValue().eventType());
        assertTrue(captured.getValue().detail().contains("reason=INSPECTION_WINDOW"));
    }

    private Entity attackTarget(UUID targetUuid) {
        Entity target = mock(Entity.class);
        when(target.blockPosition()).thenReturn(new BlockPos(3, 70, 4));
        when(target.getUUID()).thenReturn(targetUuid);
        doReturn(EntityType.PIG).when(target).getType();
        net.minecraft.network.syncher.SynchedEntityData data =
                mock(net.minecraft.network.syncher.SynchedEntityData.class);
        when(data.getNonDefaultValues()).thenReturn(java.util.List.of());
        when(target.getEntityData()).thenReturn(data);
        when(target.getPassengers()).thenReturn(java.util.List.of());
        return target;
    }

    private ServerPlayer playerWithPermission(UUID uuid, boolean permitted) {
        ServerPlayer player = mock(ServerPlayer.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.createCommandSourceStack()).thenReturn(source);
        when(source.hasPermission(2)).thenReturn(permitted);
        return player;
    }

    private ServerLevel serverLevelWithContainer() {
        ServerLevel level = mock(ServerLevel.class);
        BlockEntity container = mock(BlockEntity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Container.class));
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(container);
        return level;
    }

    @Test
    void nullCommandParseIsIgnored() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.onCommandAttempt(null, "give Alex dirt");
            verifyNoInteractions(service);
        }
    }

    @Test
    void commandDispatchRecorderStoresAnAttempt() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordCommandAttempt(
                    "player-uuid", "Alex", "minecraft:overworld", BlockPos.ZERO, "give Alex dirt");
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("COMMAND_ATTEMPT", captured.getValue().eventType());
        assertEquals("give Alex dirt", captured.getValue().detail());
        assertEquals("player-uuid", captured.getValue().playerUuid());
    }

    @Test
    void projectileRecorderPreservesItemQuantityAndUnknownEndpoint() {
        InternalObservationService service = mock(InternalObservationService.class);
        when(service.submit(any(InternalObservationService.InternalObservation.class))).thenReturn(true);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordProjectileObservation(
                    "SHOOT_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    10.0, 65.0, -4.0, 11.5, 65.0, -2.5,
                    new CanonicalItem("minecraft:arrow", "fingerprint", null, null, null), 3,
                    "minecraft:arrow");
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service).submit(captured.capture());
        assertEquals("SHOOT_ITEM", captured.getValue().actionType());
        assertEquals("minecraft:arrow", captured.getValue().item().itemId());
        assertEquals(3, captured.getValue().amount());
        assertEquals("UNKNOWN", captured.getValue().targetType());
        assertNotNull(captured.getValue().sourceEventId());
        org.junit.jupiter.api.Assertions.assertNull(captured.getValue().targetX());
        assertEquals("player-uuid", captured.getValue().playerUuid());
        org.junit.jupiter.api.Assertions.assertTrue(
                new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("projectile_shoot_attempt"));

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> audit =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(audit.capture());
        assertEquals("SHOOT_ITEM", audit.getValue().eventType());
        assertEquals("minecraft:arrow", audit.getValue().subjectId());
        assertNotNull(audit.getValue().sourceEventId());
        org.junit.jupiter.api.Assertions.assertTrue(audit.getValue().detail().contains("quantity=3"));
    }

    @Test
    void rejectedProjectileObservationDoesNotQueueLegacyProjection() {
        InternalObservationService service = mock(InternalObservationService.class);
        when(service.submit(any(InternalObservationService.InternalObservation.class))).thenReturn(false);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordProjectileObservation(
                    "SHOOT_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    10.0, 65.0, -4.0, 11.5, 65.0, -2.5,
                    new CanonicalItem("minecraft:arrow", "fingerprint", null, null, null), 1,
                    "minecraft:arrow");
        }
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).submitAuditEvent(any());
    }

    @Test
    void acceptedProjectileSpawnIsRawEvidenceWithoutSecondQuantityRow() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordProjectileSpawnAccepted(
                    "THROW_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    11.5, 65.0, -2.5,
                    new CanonicalItem("minecraft:ender_pearl", "fingerprint", null, null, null), 2,
                    "minecraft:ender_pearl");
        }
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("PROJECTILE_SPAWN_ACCEPTED", captured.getValue().eventType());
        assertNotNull(captured.getValue().sourceEventId());
        org.junit.jupiter.api.Assertions.assertTrue(
                new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("\"outcome\":\"accepted\""));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .submit(any(InternalObservationService.InternalObservation.class));
    }

    @Test
    void rejectedProjectileRegistrationDoesNotRecordEvidence() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.onProjectileAdded(null, false);
            verifyNoInteractions(service);
        }
    }

    @Test
    void blockPlacementRecorderStoresCompletedEvidence() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordBlockPlacement(
                    "player-uuid", "Alex", "minecraft:overworld", BlockPos.ZERO,
                    "minecraft:stone");
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("PLACE_BLOCK", captured.getValue().eventType());
        assertEquals("minecraft:stone", captured.getValue().subjectId());
        assertEquals(0.0, captured.getValue().x());
        assertEquals(0.0, captured.getValue().y());
        assertEquals(0.0, captured.getValue().z());
    }

    @Test
    void changedBlockPositionsKeepEveryCellOfMultiBlockPlacement() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockPos lower = new BlockPos(4, 64, 4);
        BlockPos upper = lower.above();
        BlockPos existingNeighbor = lower.east();
        Block placedBlock = mock(Block.class);
        BlockState beforeLower = mock(BlockState.class);
        BlockState beforeUpper = mock(BlockState.class);
        BlockState beforeExisting = mock(BlockState.class);
        BlockState afterLower = mock(BlockState.class);
        BlockState afterUpper = mock(BlockState.class);
        BlockState afterExisting = mock(BlockState.class);
        when(afterLower.getBlock()).thenReturn(placedBlock);
        when(afterUpper.getBlock()).thenReturn(placedBlock);
        when(beforeExisting.getBlock()).thenReturn(placedBlock);
        when(afterExisting.getBlock()).thenReturn(placedBlock);
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        before.put(lower, beforeLower);
        before.put(upper, beforeUpper);
        before.put(existingNeighbor, beforeExisting);
        Map<BlockPos, BlockState> after = new LinkedHashMap<>();
        after.put(lower, afterLower);
        after.put(upper, afterUpper);
        after.put(existingNeighbor, afterExisting);

        assertEquals(List.of(lower, upper),
                FabricNativeAuditEventListener.changedBlockPositions(before, after, placedBlock));
    }

    @Test
    void itemDropAndPickupRecorderPreserveAuthoritativeQuantity() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem canonical = new CanonicalItem("minecraft:dirt", "fingerprint", null, null, null);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordItemObservation(
                    "DROP_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    1, 2, 3, 4, 5, 6, "GROUND", canonical, 8, "entity-uuid");
            FabricNativeAuditEventListener.recordItemObservation(
                    "PICKUP_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    4, 5, 6, 1, 2, 3, "GROUND", canonical, 3, "entity-uuid");
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service, times(2)).submit(captured.capture());
        assertEquals(List.of("DROP_ITEM", "PICKUP_ITEM"),
                captured.getAllValues().stream().map(InternalObservationService.InternalObservation::actionType).toList());
        assertEquals(8, captured.getAllValues().get(0).amount());
        assertEquals(3, captured.getAllValues().get(1).amount());
        assertEquals("GROUND", captured.getAllValues().get(0).targetType());
        assertEquals("GROUND", captured.getAllValues().get(1).targetType());
        assertEquals("entity-uuid", captured.getAllValues().get(0).itemEntityUuid());
    }

    @Test
    void itemObservationRejectsNonPositiveQuantity() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem canonical = new CanonicalItem("minecraft:dirt", "fingerprint", null, null, null);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordItemObservation(
                    "PICKUP_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    1, 2, 3, 4, 5, 6, "GROUND", canonical, 0, "entity-uuid");
            verifyNoInteractions(service);
        }
    }

    @Test
    void completedFoodUseRecordsOnlyConsumedServerPlayerItems() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem canonical = new CanonicalItem("minecraft:apple", "fingerprint", null, null, null);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID playerUuid = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getX()).thenReturn(1.0);
        when(player.getY()).thenReturn(2.0);
        when(player.getZ()).thenReturn(3.0);
        ItemStack original = new ItemStack(Items.APPLE);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(original)).thenReturn(canonical);
            FabricNativeAuditEventListener.onItemUseFinished(player, original, ItemStack.EMPTY);
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service).submit(captured.capture());
        assertEquals("CONSUME_ITEM", captured.getValue().actionType());
        assertEquals(1, captured.getValue().amount());
        assertEquals("UNKNOWN", captured.getValue().targetType());
    }

    @Test
    void completedFoodUseSkipsUnchangedStack() {
        InternalObservationService service = mock(InternalObservationService.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        ItemStack original = new ItemStack(Items.APPLE, 2);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.onItemUseFinished(player, original, original.copy());
        }

        verifyNoInteractions(service);
    }

    @Test
    void durabilityBreakRecordsTheBrokenServerPlayerItem() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond_pickaxe", "fingerprint", null, null, null);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID playerUuid = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getX()).thenReturn(1.0);
        when(player.getY()).thenReturn(2.0);
        when(player.getZ()).thenReturn(3.0);
        ItemStack original = new ItemStack(Items.DIAMOND_PICKAXE);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(original)).thenReturn(canonical);
            FabricNativeAuditEventListener.onItemDestroyed(player, original);
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service).submit(captured.capture());
        assertEquals("BREAK_ITEM", captured.getValue().actionType());
        assertEquals(1, captured.getValue().amount());
        assertEquals("UNKNOWN", captured.getValue().targetType());
    }

    @Test
    void craftingResultRecordsUnresolvedOutputWithoutClaimingPartialRecipe() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem result = new CanonicalItem("minecraft:bread", "fp-bread", null, null, null);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        Container matrix = mock(Container.class);
        UUID playerUuid = UUID.randomUUID();
        ItemStack ingredient = new ItemStack(Items.WHEAT, 3);
        ItemStack output = new ItemStack(Items.BREAD, 1);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getX()).thenReturn(1.0);
        when(player.getY()).thenReturn(2.0);
        when(player.getZ()).thenReturn(3.0);
        when(matrix.getContainerSize()).thenReturn(1);
        when(matrix.getItem(0)).thenReturn(ingredient);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(output)).thenReturn(result);
            FabricNativeAuditEventListener.onCrafted(player, matrix, output);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        verify(service, never()).submitTransformation(any());
        assertEquals("CRAFT_OUTPUT_UNRESOLVED", captured.getValue().eventType());
        assertEquals("minecraft:bread", captured.getValue().subjectId());
        assertTrue(captured.getValue().detail().contains("input=UNKNOWN"));
        assertFalse(new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("minecraft:wheat"));
    }

    @Test
    void smeltingResultRecordsUnresolvedOutputWithoutCreatingTransformation() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem result = new CanonicalItem("minecraft:iron_ingot", "fp-ingot", null, null, null);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        UUID playerUuid = UUID.randomUUID();
        ItemStack output = new ItemStack(Items.IRON_INGOT, 2);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getX()).thenReturn(1.0);
        when(player.getY()).thenReturn(2.0);
        when(player.getZ()).thenReturn(3.0);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(output)).thenReturn(result);
            FabricNativeAuditEventListener.onSmelted(player, output);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        verify(service, never()).submitTransformation(any());
        assertEquals("SMELT_OUTPUT_UNRESOLVED", captured.getValue().eventType());
        assertEquals("minecraft:iron_ingot", captured.getValue().subjectId());
        assertTrue(captured.getValue().detail().contains("quantity=2"));
        assertTrue(captured.getValue().detail().contains("input=UNKNOWN"));
        assertFalse(new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("smelt_ingredient"));
    }

    @Test
    void hopperDeltasUseNetCanonicalQuantitiesAndPreserveDirection() {
        CanonicalItem dirt = new CanonicalItem("minecraft:dirt", "fp-dirt", null, null, null);
        CanonicalItem stone = new CanonicalItem("minecraft:stone", "fp-stone", null, null, null);
        BlockPos hopper = new BlockPos(0, 64, 0);
        BlockPos destination = hopper.east();

        List<FabricNativeAuditEventListener.HopperDelta> deltas =
                FabricNativeAuditEventListener.computeHopperDeltas(
                        Map.of(hopper, Map.of(dirt, 8, stone, 2),
                                destination, Map.of(dirt, 1)),
                        Map.of(hopper, Map.of(dirt, 5, stone, 2),
                                destination, Map.of(dirt, 4)));

        assertEquals(2, deltas.size());
        assertEquals(List.of(
                        new FabricNativeAuditEventListener.HopperDelta(hopper, dirt, 3, false),
                        new FabricNativeAuditEventListener.HopperDelta(destination, dirt, 3, true)),
                deltas.stream().sorted(java.util.Comparator.comparing(delta -> delta.containerPos().toShortString())).toList());
    }

    @Test
    void dropCaptureRequiresAcceptedEntityAndSupportsNesting() {
        ItemEntity outer = mock(ItemEntity.class);
        ItemEntity inner = mock(ItemEntity.class);

        ServerPlayer outerPlayer = mock(ServerPlayer.class);
        ServerPlayer innerPlayer = mock(ServerPlayer.class);
        when(outerPlayer.isDeadOrDying()).thenReturn(false);
        when(innerPlayer.isDeadOrDying()).thenReturn(true);

        FabricNativeAuditEventListener.beginItemDropCapture(outerPlayer);
        FabricNativeAuditEventListener.beginItemDropCapture(innerPlayer);
        FabricNativeAuditEventListener.onItemEntityAdded(inner, true);
        assertEquals("DEATH_DROP", FabricNativeAuditEventListener.finishItemDropCapture(null, inner, null));
        assertNull(FabricNativeAuditEventListener.finishItemDropCapture(null, outer, null));

        FabricNativeAuditEventListener.beginItemDropCapture(outerPlayer);
        FabricNativeAuditEventListener.onItemEntityAdded(outer, false);
        assertNull(FabricNativeAuditEventListener.finishItemDropCapture(null, outer, null));
    }

    @Test
    void customDeathEntityIsRecordedOnceAfterAcceptedSpawn() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InternalObservationService service = mock(InternalObservationService.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ItemEntity itemEntity = mock(ItemEntity.class);
        UUID playerUuid = UUID.randomUUID();
        UUID entityUuid = UUID.randomUUID();
        ItemStack stack = new ItemStack(Items.DIAMOND, 2);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(playerUuid, "Alex"));
        when(player.getX()).thenReturn(1.0);
        when(player.getY()).thenReturn(2.0);
        when(player.getZ()).thenReturn(3.0);
        when(itemEntity.isRemoved()).thenReturn(false);
        when(itemEntity.onGround()).thenReturn(true);
        when(itemEntity.getItem()).thenReturn(stack);
        when(itemEntity.getX()).thenReturn(4.0);
        when(itemEntity.getY()).thenReturn(5.0);
        when(itemEntity.getZ()).thenReturn(6.0);
        when(itemEntity.getUUID()).thenReturn(entityUuid);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.beginPlayerDeathCapture(player);
            FabricNativeAuditEventListener.onItemEntityAdded(itemEntity, true);
            FabricNativeAuditEventListener.finishPlayerDeathCapture(player);
            FabricNativeAuditEventListener.resolvePendingDrops(System.currentTimeMillis());
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service).submit(captured.capture());
        assertEquals("DEATH_DROP", captured.getValue().actionType());
        assertEquals(2, captured.getValue().amount());
        assertEquals(entityUuid.toString(), captured.getValue().itemEntityUuid());
    }

    private static ServerPlayer mockDropPlayer(UUID playerUuid) {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(false);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(player.getX()).thenReturn(1.0);
        when(player.getY()).thenReturn(2.0);
        when(player.getZ()).thenReturn(3.0);
        return player;
    }

    private static ItemEntity mockFallingDrop(UUID entityUuid, ItemStack stack,
                                              double x, double y, double z) {
        ItemEntity entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(entityUuid);
        when(entity.getItem()).thenReturn(stack);
        when(entity.isRemoved()).thenReturn(false);
        when(entity.onGround()).thenReturn(false);
        when(entity.getDeltaMovement()).thenReturn(new net.minecraft.world.phys.Vec3(0, -0.4, 0));
        when(entity.getX()).thenReturn(x);
        when(entity.getY()).thenReturn(y);
        when(entity.getZ()).thenReturn(z);
        return entity;
    }

    private static void queueServerPlayerDrop(ServerPlayer player, ItemEntity entity, ItemStack stack) {
        FabricNativeAuditEventListener.beginItemDropCapture(player);
        FabricNativeAuditEventListener.onItemEntityAdded(entity, true);
        FabricNativeAuditEventListener.finishItemDropCapture(player, entity, stack);
    }

    private static ArgumentCaptor<InternalObservationService.InternalObservation> submitCaptor() {
        return ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
    }

    @Test
    void airborneDropWaitsForSettleBeforeRecordingGroundMovement() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InternalObservationService service = mock(InternalObservationService.class);
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = mockDropPlayer(playerUuid);
        UUID entityUuid = UUID.randomUUID();
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        ItemEntity entity = mockFallingDrop(entityUuid, stack, 15.0, 65.0, -20.0);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(stack)).thenReturn(canonical);
            queueServerPlayerDrop(player, entity, stack);
            FabricNativeAuditEventListener.resolvePendingDrops(System.currentTimeMillis());
            verifyNoInteractions(service);

            when(entity.onGround()).thenReturn(true);
            when(entity.getY()).thenReturn(64.0);
            FabricNativeAuditEventListener.resolvePendingDrops(System.currentTimeMillis());
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured = submitCaptor();
        verify(service).submit(captured.capture());
        assertEquals("DROP_ITEM", captured.getValue().actionType());
        assertEquals("GROUND", captured.getValue().targetType());
        assertEquals(64.0, captured.getValue().targetY(),
                "a settled drop must record the rest position, not the spawn position");
        assertEquals(entityUuid.toString(), captured.getValue().itemEntityUuid());
    }

    @Test
    void removedPendingDropEmitsConfirmedAtLastPosition() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InternalObservationService service = mock(InternalObservationService.class);
        ServerPlayer player = mockDropPlayer(UUID.randomUUID());
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        ItemEntity entity = mockFallingDrop(UUID.randomUUID(), stack, 15.0, 65.0, -20.0);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(stack)).thenReturn(canonical);
            queueServerPlayerDrop(player, entity, stack);
            FabricNativeAuditEventListener.resolvePendingDrops(System.currentTimeMillis());
            verifyNoInteractions(service);

            when(entity.isRemoved()).thenReturn(true);
            when(entity.getY()).thenReturn(64.0);
            FabricNativeAuditEventListener.resolvePendingDrops(System.currentTimeMillis());
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured = submitCaptor();
        verify(service).submit(captured.capture());
        assertEquals("DROP_ITEM", captured.getValue().actionType());
        assertEquals(64.0, captured.getValue().targetY(),
                "an absorbed or merged drop must confirm at its last known position");
    }

    @Test
    void pendingDropTtlExpiryEmitsConfirmedAtFallbackPosition() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InternalObservationService service = mock(InternalObservationService.class);
        ServerPlayer player = mockDropPlayer(UUID.randomUUID());
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        ItemEntity entity = mockFallingDrop(UUID.randomUUID(), stack, 15.0, 65.0, -20.0);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(stack)).thenReturn(canonical);
            queueServerPlayerDrop(player, entity, stack);
            FabricNativeAuditEventListener.resolvePendingDrops(System.currentTimeMillis() + 6000);
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured = submitCaptor();
        verify(service).submit(captured.capture());
        assertEquals("DROP_ITEM", captured.getValue().actionType(),
                "a drop that never reports a resting state must still confirm at TTL fallback");
        assertEquals(65.0, captured.getValue().targetY());
    }

    @Test
    void pickupOvertakingPendingDropEmitsDropAndPickupAtSameGroundPosition() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InternalObservationService service = mock(InternalObservationService.class);
        ServerPlayer player = mockDropPlayer(UUID.randomUUID());
        ItemStack stack = new ItemStack(Items.DIAMOND, 4);
        ItemEntity entity = mockFallingDrop(UUID.randomUUID(), stack, 15.0, 64.7, -20.0);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(stack)).thenReturn(canonical);
            queueServerPlayerDrop(player, entity, stack);
            FabricNativeAuditEventListener.onItemPickedUp(player, entity, stack, 0);
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured = submitCaptor();
        verify(service, times(2)).submit(captured.capture());
        assertEquals(List.of("DROP_ITEM", "PICKUP_ITEM"),
                captured.getAllValues().stream()
                        .map(InternalObservationService.InternalObservation::actionType).toList(),
                "the overtaken drop must emit before its pickup at the shared rest position");
        assertEquals(captured.getAllValues().get(0).targetX(), captured.getAllValues().get(1).x());
        assertEquals(captured.getAllValues().get(0).targetY(), captured.getAllValues().get(1).y());
        assertEquals(captured.getAllValues().get(0).targetZ(), captured.getAllValues().get(1).z());
    }

    @Test
    void requeuedEntityUuidEmitsUnresolvedForReplacedPendingDrop() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InternalObservationService service = mock(InternalObservationService.class);
        ServerPlayer player = mockDropPlayer(UUID.randomUUID());
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        UUID sharedUuid = UUID.randomUUID();
        ItemEntity first = mockFallingDrop(sharedUuid, stack, 15.0, 65.0, -20.0);
        ItemEntity second = mockFallingDrop(sharedUuid, stack, 15.0, 65.0, -20.0);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);

        try (MockedStatic<InternalObservationService> observations = mockStatic(InternalObservationService.class);
             MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            observations.when(InternalObservationService::getInstance).thenReturn(service);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(stack)).thenReturn(canonical);
            queueServerPlayerDrop(player, first, stack);
            queueServerPlayerDrop(player, second, stack);
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured = submitCaptor();
        verify(service).submit(captured.capture());
        assertEquals("DROP_UNRESOLVED", captured.getValue().actionType());
        assertEquals("UNKNOWN", captured.getValue().targetType());
        assertTrue(new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains("pending_entity_reused"),
                "the replaced pending drop must record why its ground endpoint stayed unknown");
    }
}
