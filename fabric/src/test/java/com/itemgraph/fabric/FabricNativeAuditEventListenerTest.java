package com.itemgraph.fabric;

import com.itemgraph.canon.CanonicalItem;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FabricNativeAuditEventListenerTest {
    private final InspectionService inspections = InspectionService.getInstance();

    @AfterEach
    void clearInspectionState() {
        inspections.clear();
    }

    @Test
    void activeSupportedFabricInspectionConsumesClickAfterBrowserAccepts() {
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
    void projectileRecorderStoresEvidenceWithoutQuantityFlow() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            FabricNativeAuditEventListener.recordProjectileAudit(
                    "SHOOT_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    1.5, 64.0, -2.5, "minecraft:bow", "minecraft:arrow");
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("SHOOT_ITEM", captured.getValue().eventType());
        assertEquals("minecraft:bow", captured.getValue().subjectId());
        assertEquals("projectile=minecraft:arrow evidence=spawned_by_player",
                captured.getValue().detail());
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
}
