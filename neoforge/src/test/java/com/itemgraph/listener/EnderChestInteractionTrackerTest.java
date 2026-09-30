package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService.InternalObservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnderChestInteractionTrackerTest {
    private final ContainerInteractionTracker blocks = ContainerInteractionTracker.getInstance();

    @AfterEach
    void clearSharedTrackers() {
        EnderChestInteractionTracker.getInstance().clearAll();
        blocks.clearAll();
    }

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
    void duplicateOpenCallbackFlushesOneDeltaThenStartsANewBaseline() {
        List<InternalObservation> emitted = new ArrayList<>();
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(collectingSink(emitted));
        UUID player = UUID.randomUUID();
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        AtomicReference<ContainerInteractionTracker.InventoryTotals> inventory = new AtomicReference<>(
                totals(Map.of("fp-diamond", 10L), Map.of("fp-diamond", diamond)));

        tracker.openSession(player, "Alex", "minecraft:overworld", 1, 64, 2, inventory::get);
        inventory.set(totals(Map.of("fp-diamond", 12L), Map.of("fp-diamond", diamond)));
        tracker.openSession(player, "Alex", "minecraft:overworld", 1, 64, 2, inventory::get);
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);

        assertEquals(1, emitted.size(), "a duplicate open must flush the prior delta once and baseline again");
        assertEquals("ADD_ITEM_ENDER", emitted.get(0).actionType());
        assertEquals(2, emitted.get(0).amount());
        assertEquals(player.toString(), emitted.get(0).playerUuid());
        assertEquals("ENDER_CHEST", emitted.get(0).targetType());
        assertTrue(new String(emitted.get(0).rawData(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"capture\":\"ender_inventory_session_net_delta\""));
    }

    @Test
    void reconnectProducesOnlyTheSignedDeltaForEachDisconnectedSession() {
        List<InternalObservation> emitted = new ArrayList<>();
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(collectingSink(emitted));
        UUID player = UUID.randomUUID();
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        AtomicReference<ContainerInteractionTracker.InventoryTotals> inventory = new AtomicReference<>(
                totals(Map.of("fp-diamond", 10L), Map.of("fp-diamond", diamond)));

        tracker.openSession(player, "Alex", "minecraft:overworld", inventory::get);
        inventory.set(totals(Map.of("fp-diamond", 13L), Map.of("fp-diamond", diamond)));
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);
        tracker.openSession(player, "Alex", "minecraft:overworld", inventory::get);
        inventory.set(totals(Map.of("fp-diamond", 11L), Map.of("fp-diamond", diamond)));
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);

        assertEquals(List.of("ADD_ITEM_ENDER", "REMOVE_ITEM_ENDER"),
                emitted.stream().map(InternalObservation::actionType).toList());
        assertEquals(List.of(3, 2), emitted.stream().map(InternalObservation::amount).toList());
        assertTrue(emitted.stream().allMatch(row -> player.toString().equals(row.playerUuid())));
    }

    @Test
    void partialTransfersPreserveSignedCountsAndDoNotPersistOpaqueComponentPayloads() {
        List<InternalObservation> emitted = new ArrayList<>();
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(collectingSink(emitted));
        UUID player = UUID.randomUUID();
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        CanonicalItem opaque = new CanonicalItem("example:opaque", "sha256-fingerprint", null, null,
                "opaque components redacted");
        AtomicReference<ContainerInteractionTracker.InventoryTotals> inventory = new AtomicReference<>(
                totals(Map.of("fp-diamond", 10L, "sha256-fingerprint", 2L),
                        Map.of("fp-diamond", diamond, "sha256-fingerprint", opaque)));

        tracker.openSession(player, "Alex", "minecraft:overworld", inventory::get);
        inventory.set(totals(Map.of("fp-diamond", 7L, "sha256-fingerprint", 4L),
                Map.of("fp-diamond", diamond, "sha256-fingerprint", opaque)));
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);

        assertEquals(2, emitted.size());
        assertEquals(Map.of("ADD_ITEM_ENDER", 2, "REMOVE_ITEM_ENDER", 3),
                emitted.stream().collect(java.util.stream.Collectors.toMap(
                        InternalObservation::actionType, InternalObservation::amount)));
        for (InternalObservation row : emitted) {
            String raw = new String(row.rawData(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(raw.contains("opaque components redacted"));
            assertFalse(raw.contains("sha256-fingerprint"));
        }
    }

    @Test
    void unchangedSessionDoesNotEmitRows() {
        List<InternalObservation> emitted = new ArrayList<>();
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(collectingSink(emitted));
        UUID player = UUID.randomUUID();
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        ContainerInteractionTracker.InventoryTotals unchanged =
                totals(Map.of("fp-diamond", 10L), Map.of("fp-diamond", diamond));

        tracker.openSession(player, "Alex", "minecraft:overworld", () -> unchanged);
        tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN);

        assertTrue(emitted.isEmpty());
    }

    private static ContainerInteractionTracker.InventoryTotals totals(
            Map<String, Long> counts, Map<String, CanonicalItem> exemplars) {
        return new ContainerInteractionTracker.InventoryTotals(counts, exemplars);
    }

    private static Predicate<List<InternalObservation>> collectingSink(List<InternalObservation> target) {
        return observations -> {
            target.addAll(observations);
            return true;
        };
    }

    @Test
    void rejectedDeltaBatchRetainsWholeSessionAndRetryDoesNotDuplicateAcceptedRows() {
        List<InternalObservation> emitted = new ArrayList<>();
        AtomicBoolean accept = new AtomicBoolean(false);
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(observations -> {
            if (!accept.get()) {
                return false;
            }
            emitted.addAll(observations);
            return true;
        });
        UUID player = UUID.randomUUID();
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        CanonicalItem iron = new CanonicalItem("minecraft:iron_ingot", "fp-iron", null, null, null);
        AtomicReference<ContainerInteractionTracker.InventoryTotals> inventory = new AtomicReference<>(
                totals(Map.of("fp-diamond", 10L, "fp-iron", 4L),
                        Map.of("fp-diamond", diamond, "fp-iron", iron)));

        tracker.openSession(player, "Alex", "minecraft:overworld", inventory::get);
        inventory.set(totals(Map.of("fp-diamond", 12L, "fp-iron", 1L),
                Map.of("fp-diamond", diamond, "fp-iron", iron)));

        assertFalse(tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN));
        assertEquals(1, tracker.sessionCount());
        assertTrue(emitted.isEmpty(), "rejected batch must not partially accept a fingerprint delta");

        accept.set(true);
        assertTrue(tracker.closeSession(player, Double.NaN, Double.NaN, Double.NaN));
        assertEquals(0, tracker.sessionCount());
        assertEquals(Map.of("ADD_ITEM_ENDER", 2, "REMOVE_ITEM_ENDER", 3),
                emitted.stream().collect(java.util.stream.Collectors.toMap(
                        InternalObservation::actionType, InternalObservation::amount)));
    }

    @Test
    void orderlyShutdownRetriesQueueRejectedSessionBeforeClearingIt() {
        List<InternalObservation> emitted = new ArrayList<>();
        AtomicBoolean rejectFirstBatch = new AtomicBoolean(true);
        AtomicReference<Integer> attempts = new AtomicReference<>(0);
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(observations -> {
            attempts.updateAndGet(count -> count + 1);
            if (rejectFirstBatch.getAndSet(false)) {
                return false;
            }
            emitted.addAll(observations);
            return true;
        });
        UUID player = UUID.randomUUID();
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        AtomicReference<ContainerInteractionTracker.InventoryTotals> inventory = new AtomicReference<>(
                totals(Map.of("fp-diamond", 10L), Map.of("fp-diamond", diamond)));

        tracker.openSession(player, "Alex", "minecraft:overworld", inventory::get);
        inventory.set(totals(Map.of("fp-diamond", 12L), Map.of("fp-diamond", diamond)));
        tracker.closeAllSessions();

        assertEquals(2, attempts.get(), "shutdown retries the rejected batch after queue capacity can change");
        assertEquals(0, tracker.sessionCount());
        assertEquals(1, emitted.size());
        assertEquals(2, emitted.getFirst().amount());
    }

    @Test
    void shutdownQueueRetryBudgetIsSharedAcrossAllSessions() {
        AtomicInteger attempts = new AtomicInteger();
        EnderChestInteractionTracker tracker = new EnderChestInteractionTracker(observations -> {
            attempts.incrementAndGet();
            return false;
        });
        CanonicalItem diamond = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        AtomicReference<ContainerInteractionTracker.InventoryTotals> inventory = new AtomicReference<>(
                totals(Map.of("fp-diamond", 1L), Map.of("fp-diamond", diamond)));
        List<UUID> players = java.util.stream.IntStream.range(0, 8)
                .mapToObj(ignored -> UUID.randomUUID()).toList();
        for (UUID player : players) {
            tracker.openSession(player, "Alex", "minecraft:overworld", inventory::get);
        }
        inventory.set(totals(Map.of("fp-diamond", 2L), Map.of("fp-diamond", diamond)));

        long startedNanos = System.nanoTime();
        tracker.closeAllSessions(50);
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertTrue(elapsedMillis < 300, "shutdown retry delay must be shared, not multiplied by player count");
        assertTrue(attempts.get() < players.size() * 2,
                "sessions after the shared deadline get one final enqueue attempt, not their own retry window");
        assertEquals(0, tracker.sessionCount());
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

    @Test
    void blockMenuDoesNotOpenWhenEnderFlushFails() {
        EnderChestInteractionTracker ender = EnderChestInteractionTracker.getInstance();
        ContainerInteractionTracker blocks = ContainerInteractionTracker.getInstance();
        ender.clearAll();
        blocks.clearAll();
        UUID player = UUID.randomUUID();
        AtomicBoolean fail = new AtomicBoolean(false);
        ContainerInteractionTracker.InventoryTotals empty =
                new ContainerInteractionTracker.InventoryTotals(Map.of(), Map.of());
        ender.openSession(player, "Steve", "minecraft:overworld", 1, 64, 1, () -> {
            if (fail.get()) {
                throw new IllegalStateException("synthetic snapshot failure");
            }
            return empty;
        });
        fail.set(true);

        ContainerInteractionTracker.ContainerKey key =
                new ContainerInteractionTracker.ContainerKey("minecraft:overworld", 10, 64, 10);
        blocks.openSession(player, "Steve", key, () -> empty, List.of());

        assertEquals(1, ender.sessionCount());
        assertFalse(blocks.isWatched(key));
        ender.clearAll();
        blocks.clearAll();
    }

    @Test
    void enderMenuDoesNotOpenWhenBlockFlushFails() {
        EnderChestInteractionTracker ender = EnderChestInteractionTracker.getInstance();
        ContainerInteractionTracker blocks = ContainerInteractionTracker.getInstance();
        ender.clearAll();
        blocks.clearAll();
        UUID player = UUID.randomUUID();
        AtomicBoolean fail = new AtomicBoolean(false);
        ContainerInteractionTracker.InventoryTotals empty =
                new ContainerInteractionTracker.InventoryTotals(Map.of(), Map.of());
        ContainerInteractionTracker.ContainerKey key =
                new ContainerInteractionTracker.ContainerKey("minecraft:overworld", 10, 64, 10);
        blocks.openSession(player, "Steve", key, () -> {
            if (fail.get()) {
                throw new IllegalStateException("synthetic snapshot failure");
            }
            return empty;
        }, List.of());
        fail.set(true);

        ender.openSession(player, "Steve", "minecraft:overworld", 1, 64, 1, () -> empty);

        assertEquals(0, ender.sessionCount());
        assertTrue(blocks.isWatched(key));
        fail.set(false);
        assertTrue(blocks.closeSession(player, Double.NaN, Double.NaN, Double.NaN));
        ender.clearAll();
        blocks.clearAll();
    }
}
