package com.itemgraph.tracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemEntityTrackerTest {

    private ItemEntityTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = ItemEntityTracker.getInstance();
        tracker.clear();
    }

    @Test
    void testRecordAndRetrieveByUuid() {
        UUID entityUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        tracker.recordDrop(entityUuid, playerUuid, "minecraft:overworld", 100, 64, 200, "minecraft:diamond", 5, now);

        ItemEntityTracker.EntityRecord record = tracker.getByEntityUuid(entityUuid);
        assertNotNull(record);
        assertEquals(entityUuid, record.entityUuid());
        assertEquals(playerUuid, record.playerUuid());
        assertEquals("minecraft:overworld", record.levelName());
        assertEquals(100, record.blockX());
        assertEquals(64, record.blockY());
        assertEquals(200, record.blockZ());
        assertEquals("minecraft:diamond", record.itemId());
        assertEquals(5, record.amount());
        assertEquals(now, record.timestampMs());
    }

    @Test
    void testFindMatchingDropEntityBySpatialKey() {
        UUID entityUuid = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        long now = System.currentTimeMillis();

        tracker.recordDrop(entityUuid, playerUuid, "minecraft:overworld", 10, 64, 10, "minecraft:iron_ingot", 1, now);

        UUID found = tracker.findMatchingDropEntity("minecraft:overworld", 10, 64, 10, "minecraft:iron_ingot", now + 2000);
        assertEquals(entityUuid, found);

        // Outside time window (e.g. 30 seconds later)
        UUID tooLate = tracker.findMatchingDropEntity("minecraft:overworld", 10, 64, 10, "minecraft:iron_ingot", now + 30_000);
        assertNull(tooLate);

        // Different coordinates
        UUID wrongCoords = tracker.findMatchingDropEntity("minecraft:overworld", 15, 64, 10, "minecraft:iron_ingot", now);
        assertNull(wrongCoords);
    }

    @Test
    void ambiguousDropsAtTheSameLocationDoNotProduceAnExactEntityMatch() {
        long now = System.currentTimeMillis();
        tracker.recordDrop(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 10, 64, 10,
                "minecraft:iron_ingot", 1, now);
        tracker.recordDrop(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 10, 64, 10,
                "minecraft:iron_ingot", 1, now + 1_000);

        assertNull(tracker.findMatchingDropEntity("minecraft:overworld", 10, 64, 10,
                "minecraft:iron_ingot", now + 1_500),
                "a GriefLogger row must not be assigned to an arbitrary matching ItemEntity");
    }

    @Test
    void testCounters() {
        long initialDrops = tracker.getDropCount();
        long initialPickups = tracker.getPickupCount();
        long initialMatches = tracker.getContinuityMatchCount();

        UUID e1 = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        tracker.recordDrop(e1, p1, "minecraft:overworld", 0, 64, 0, "minecraft:gold_ingot", 1, System.currentTimeMillis());
        tracker.recordPickup(e1, p1, "minecraft:overworld", 0, 64, 0, "minecraft:gold_ingot", 1, System.currentTimeMillis());
        tracker.recordContinuityMatch();

        assertEquals(initialDrops + 1, tracker.getDropCount());
        assertEquals(initialPickups + 1, tracker.getPickupCount());
        assertEquals(initialMatches + 1, tracker.getContinuityMatchCount());
    }
}
