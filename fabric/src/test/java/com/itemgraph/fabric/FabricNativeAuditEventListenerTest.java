package com.itemgraph.fabric;

import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class FabricNativeAuditEventListenerTest {
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
}
