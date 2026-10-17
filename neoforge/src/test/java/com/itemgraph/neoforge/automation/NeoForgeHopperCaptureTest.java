package com.itemgraph.neoforge.automation;

import com.itemgraph.canon.CanonicalItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeoForgeHopperCaptureTest {
    @Test
    void hopperSnapshotSlotBoundRejectsOverflowingInventorySizes() {
        assertFalse(NeoForgeHopperCapture.slotCountWithinBound(5, Integer.MAX_VALUE));
        assertTrue(NeoForgeHopperCapture.slotCountWithinBound(5, 507));
        assertFalse(NeoForgeHopperCapture.slotCountWithinBound(5, 508));
    }

    @Test
    void afterSnapshotRejectsUnavailableOrReplacedContainers() {
        ServerLevel level = mock(ServerLevel.class);
        BlockPos containerPos = BlockPos.ZERO;
        when(level.hasChunkAt(containerPos)).thenReturn(false);
        assertNull(NeoForgeHopperCapture.snapshot(level, List.of(containerPos)),
                "an unloaded container cannot be interpreted as empty after a transfer");
        verify(level, never()).getBlockEntity(containerPos);

        when(level.hasChunkAt(containerPos)).thenReturn(true);
        assertNull(NeoForgeHopperCapture.snapshot(level, List.of(containerPos)),
                "a replaced or missing container makes the after snapshot incomplete");

        BlockEntity emptyContainerEntity = mock(BlockEntity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Container.class));
        Container emptyContainer = (Container) emptyContainerEntity;
        when(emptyContainer.getContainerSize()).thenReturn(0);
        when(level.getBlockEntity(containerPos)).thenReturn(emptyContainerEntity);
        assertEquals(Map.of(containerPos, Map.of()), NeoForgeHopperCapture.snapshot(level, List.of(containerPos)),
                "a readable empty container is a valid empty snapshot");
    }

    @Test
    void computesOnlyCommittedNetDeltasAndPreservesQuantity() {
        CanonicalItem dirt = new CanonicalItem("minecraft:dirt", "fp-dirt", null, null, null);
        CanonicalItem stone = new CanonicalItem("minecraft:stone", "fp-stone", null, null, null);
        BlockPos hopper = new BlockPos(0, 64, 0);
        BlockPos destination = hopper.east();

        List<NeoForgeHopperCapture.Delta> deltas = NeoForgeHopperCapture.deltas(
                Map.of(hopper, Map.of(dirt, 8, stone, 2), destination, Map.of(dirt, 1)),
                Map.of(hopper, Map.of(dirt, 5, stone, 2), destination, Map.of(dirt, 4)));

        List<NeoForgeHopperCapture.Delta> sorted = deltas.stream()
                .sorted(Comparator.comparing(delta -> delta.pos().toShortString())).toList();
        assertEquals(List.of(
                new NeoForgeHopperCapture.Delta(hopper, dirt, 3, false),
                new NeoForgeHopperCapture.Delta(destination, dirt, 3, true)), sorted);
        assertEquals(0, deltas.stream().mapToInt(delta -> delta.inserted() ? delta.count() : -delta.count()).sum(),
                "paired container deltas must conserve observed quantity");
    }

    @Test
    void ignoresUnchangedInventoriesAndKeepsExactOutgoingQuantity() {
        CanonicalItem dirt = new CanonicalItem("minecraft:dirt", "fp-dirt", null, null, null);
        BlockPos hopper = new BlockPos(0, 64, 0);

        assertEquals(List.of(), NeoForgeHopperCapture.deltas(
                Map.of(hopper, Map.of(dirt, 8)), Map.of(hopper, Map.of(dirt, 8))));
        assertEquals(List.of(new NeoForgeHopperCapture.Delta(hopper, dirt, 8, false)),
                NeoForgeHopperCapture.deltas(
                        Map.of(hopper, Map.of(dirt, 8)), Map.of(hopper, Map.of(dirt, 0))));
    }
}
