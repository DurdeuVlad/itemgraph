package com.itemgraph.listener;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
