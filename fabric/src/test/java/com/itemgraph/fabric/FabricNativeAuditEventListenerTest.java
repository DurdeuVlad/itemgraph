package com.itemgraph.fabric;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.InspectionService;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
                player, level, pos);

        assertEquals(InteractionResult.SUCCESS, result);
        assertEquals(1, opens.get());
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
                player, unsupportedLevel, BlockPos.ZERO));

        inspections.setEnabled(playerUuid, true);
        assertNull(FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> { opens.incrementAndGet(); return 0; },
                player, serverLevelWithContainer(), BlockPos.ZERO));
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
                player, level, BlockPos.ZERO);

        assertEquals(InteractionResult.SUCCESS, result);
        assertEquals(1, opens.get());
    }

    @Test
    void rejectedBlockInspectionPreservesGameplay() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, true);
        ServerLevel level = org.mockito.Mockito.mock(ServerLevel.class);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(BlockPos.ZERO)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());

        InteractionResult result = FabricNativeAuditEventListener.tryOpenInspection(
                inspections,
                (openingPlayer, openingLevel, clickedPos) -> 0,
                player, level, BlockPos.ZERO);

        assertNull(result);
    }

    @Test
    void rejectedLeftClickInspectionPreservesBlockBreaking() {
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
    void FabricInspectionPermissionLossDisablesMode() {
        UUID playerUuid = UUID.randomUUID();
        inspections.setEnabled(playerUuid, true);
        ServerPlayer player = playerWithPermission(playerUuid, false);

        assertNull(FabricNativeAuditEventListener.tryOpenInspection(
                inspections, (p, l, pos) -> 1, player, serverLevelWithContainer(), BlockPos.ZERO));
        assertFalse(inspections.isEnabled(playerUuid));
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
    void craftingResultRecordsTransformationWithPrimaryIngredient() {
        InternalObservationService service = mock(InternalObservationService.class);
        CanonicalItem source = new CanonicalItem("minecraft:wheat", "fp-wheat", null, null, null);
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
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(ingredient)).thenReturn(source);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(output)).thenReturn(result);
            FabricNativeAuditEventListener.onCrafted(player, matrix, output);
        }

        ArgumentCaptor<InternalObservationService.InternalTransformation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalTransformation.class);
        verify(service).submitTransformation(captured.capture());
        assertEquals("CRAFT", captured.getValue().transformationType());
        assertEquals(source, captured.getValue().sourceItem());
        assertEquals(result, captured.getValue().resultItem());
        assertEquals(1, captured.getValue().quantity());
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
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service).submit(captured.capture());
        assertEquals("DEATH_DROP", captured.getValue().actionType());
        assertEquals(2, captured.getValue().amount());
        assertEquals(entityUuid.toString(), captured.getValue().itemEntityUuid());
    }
}
