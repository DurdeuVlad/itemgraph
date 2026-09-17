package com.itemgraph.tracker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance temporal tracker for Minecraft ItemEntity UUIDs (Phase 8A).
 *
 * <p>Captures ItemEntity UUIDs when dropped or picked up in the world, allowing
 * ItemGraph to attach direct entity identity to ground observations and eliminate
 * circumstantial ambiguity during correlation.
 *
 * <p>Entries expire automatically after the configured retention window (default 10 minutes)
 * to prevent memory leaks.
 */
public class ItemEntityTracker {

    private static final ItemEntityTracker INSTANCE = new ItemEntityTracker();
    private static final long EXPIRATION_MS = 10 * 60 * 1000L; // 10 minutes

    public static ItemEntityTracker getInstance() {
        return INSTANCE;
    }

    public record EntityRecord(
            UUID entityUuid,
            UUID playerUuid,
            String levelName,
            int blockX,
            int blockY,
            int blockZ,
            String itemId,
            int amount,
            long timestampMs
    ) {}

    // Key: level:x:y:z:itemId -> EntityRecord
    private final Map<String, EntityRecord> recentDrops = new ConcurrentHashMap<>();
    private final Map<UUID, EntityRecord> entityByUuid = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong dropCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong pickupCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong continuityMatchCount = new java.util.concurrent.atomic.AtomicLong(0);

    private ItemEntityTracker() {}

    private static String makeSpatialKey(String level, int x, int y, int z, String itemId) {
        return level + ":" + x + ":" + y + ":" + z + ":" + itemId;
    }

    public void recordDrop(UUID entityUuid, UUID playerUuid, String level, int x, int y, int z, String itemId, int amount, long timestampMs) {
        dropCount.incrementAndGet();
        EntityRecord record = new EntityRecord(entityUuid, playerUuid, level, x, y, z, itemId, amount, timestampMs);
        entityByUuid.put(entityUuid, record);
        recentDrops.put(makeSpatialKey(level, x, y, z, itemId), record);
        cleanExpired(timestampMs);
    }

    public void recordPickup(UUID entityUuid, UUID playerUuid, String level, int x, int y, int z, String itemId, int amount, long timestampMs) {
        pickupCount.incrementAndGet();
        EntityRecord record = new EntityRecord(entityUuid, playerUuid, level, x, y, z, itemId, amount, timestampMs);
        entityByUuid.put(entityUuid, record);
        cleanExpired(timestampMs);
    }

    public void recordContinuityMatch() {
        continuityMatchCount.incrementAndGet();
    }

    public long getDropCount() {
        return dropCount.get();
    }

    public long getPickupCount() {
        return pickupCount.get();
    }

    public long getContinuityMatchCount() {
        return continuityMatchCount.get();
    }

    public int getActiveEntityCount() {
        return entityByUuid.size();
    }

    public EntityRecord getByEntityUuid(UUID entityUuid) {
        return entityByUuid.get(entityUuid);
    }

    public UUID findMatchingDropEntity(String level, int x, int y, int z, String itemId, long eventTimeMs) {
        String key = makeSpatialKey(level, x, y, z, itemId);
        EntityRecord record = recentDrops.get(key);
        if (record != null && Math.abs(record.timestampMs() - eventTimeMs) < 15_000L) {
            return record.entityUuid();
        }
        return null;
    }

    private void cleanExpired(long nowMs) {
        if (entityByUuid.size() > 5000) {
            long cutoff = nowMs - EXPIRATION_MS;
            entityByUuid.entrySet().removeIf(e -> e.getValue().timestampMs() < cutoff);
            recentDrops.entrySet().removeIf(e -> e.getValue().timestampMs() < cutoff);
        }
    }

    public void clear() {
        recentDrops.clear();
        entityByUuid.clear();
    }
}
