package com.itemgraph.listener;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnderChestInteractionTrackerTest {
    @Test
    void computesSignedFingerprintDeltasWithoutInventingQuantity() {
        Map<String, Long> delta = EnderChestInteractionTracker.computeDelta(
                Map.of("diamond", 10L, "iron", 4L),
                Map.of("diamond", 7L, "gold", 3L));

        assertEquals(Map.of("diamond", -3L, "iron", -4L, "gold", 3L), delta);
    }

    @Test
    void identicalSnapshotsProduceNoObservationDelta() {
        assertEquals(Map.of(), EnderChestInteractionTracker.computeDelta(
                Map.of("diamond", 10L), Map.of("diamond", 10L)));
    }

    @Test
    void snapshotFailureRetainsSessionForRetry() {
        EnderChestInteractionTracker tracker = EnderChestInteractionTracker.getInstance();
        tracker.clearAll();
        AtomicBoolean fail = new AtomicBoolean(false);
        ContainerInteractionTracker.InventoryTotals empty =
                new ContainerInteractionTracker.InventoryTotals(Map.of(), Map.of());

        java.util.UUID player = java.util.UUID.randomUUID();
        tracker.openSession(player, "Steve", "minecraft:overworld",
                12.5, 64.0, -3.25, () -> {
                    if (fail.get()) {
                        throw new IllegalStateException("synthetic snapshot failure");
                    }
                    return empty;
                });
        fail.set(true);
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);
        assertEquals(1, tracker.sessionCount());
        fail.set(false);
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);
        assertEquals(0, tracker.sessionCount());
    }
}
