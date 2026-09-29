package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Measures one player's Ender Chest menu session without polling the inventory.
 * Ender Chest contents are player-owned and have no world position, so the
 * resulting observations use a durable {@code ENDER_CHEST} endpoint keyed by the
 * player's UUID. The player's position is retained only as the interaction
 * context at close time.
 */
public final class EnderChestInteractionTracker {
    private static final EnderChestInteractionTracker INSTANCE = new EnderChestInteractionTracker();

    private record Session(String playerName, String levelName,
                           Supplier<ContainerInteractionTracker.InventoryTotals> snapshotSource,
                           Map<String, Long> baseline,
                           Map<String, CanonicalItem> exemplars,
                           long windowStartMs) {
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private EnderChestInteractionTracker() {
    }

    public static EnderChestInteractionTracker getInstance() {
        return INSTANCE;
    }

    /** Opens or replaces a player-owned Ender Chest session. */
    public void openSession(UUID playerUuid, String playerName, String levelName,
                            Supplier<ContainerInteractionTracker.InventoryTotals> snapshotSource) {
        if (playerUuid == null || snapshotSource == null) {
            return;
        }
        // A menu switch can skip a loader close callback. Flush a block-container
        // watch before replacing it with this player-owned inventory session.
        ContainerInteractionTracker.getInstance().closeSession(playerUuid, 0.0, 0.0, 0.0);
        closeSession(playerUuid, 0.0, 0.0, 0.0);
        ContainerInteractionTracker.InventoryTotals totals = snapshotSource.get();
        sessions.put(playerUuid, new Session(
                playerName == null ? "" : playerName,
                levelName == null ? "" : levelName,
                snapshotSource,
                new HashMap<>(totals.counts()),
                new HashMap<>(totals.exemplars()),
                System.currentTimeMillis()));
    }

    /** Closes a session and emits signed Ender Chest quantity deltas. */
    public void closeSession(UUID playerUuid, double x, double y, double z) {
        Session session = sessions.remove(playerUuid);
        if (session == null) {
            return;
        }
        ContainerInteractionTracker.InventoryTotals current = session.snapshotSource().get();
        long endMs = System.currentTimeMillis();
        Map<String, Long> delta = computeDelta(session.baseline(), current.counts());
        Map<String, CanonicalItem> exemplars = new HashMap<>(session.exemplars());
        exemplars.putAll(current.exemplars());
        for (Map.Entry<String, Long> entry : delta.entrySet()) {
            CanonicalItem item = exemplars.get(entry.getKey());
            if (item == null) {
                continue;
            }
            long signed = entry.getValue();
            String action = signed > 0 ? "ADD_ITEM_ENDER" : "REMOVE_ITEM_ENDER";
            byte[] rawData = ("{\"capture\":\"ender_inventory_session_net_delta\","
                    + "\"ownerUuid\":\"" + playerUuid + "\","
                    + "\"sessionStartMs\":" + session.windowStartMs() + ","
                    + "\"sessionEndMs\":" + endMs + "}").getBytes(StandardCharsets.UTF_8);
            long remaining = signed > 0 ? signed : -signed;
            while (remaining > 0) {
                int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
                InternalObservationService.getInstance().submit(
                        new InternalObservationService.InternalObservation(
                                session.windowStartMs(), action, playerUuid.toString(), session.playerName(),
                                session.levelName(), x, y, z,
                                session.levelName(), null, null, null, "ENDER_CHEST",
                                item.itemId(), rawData, item, amount, null, endMs));
                remaining -= amount;
            }
        }
    }

    static Map<String, Long> computeDelta(Map<String, Long> baseline, Map<String, Long> current) {
        Map<String, Long> result = new HashMap<>();
        HashSet<String> keys = new HashSet<>(baseline.keySet());
        keys.addAll(current.keySet());
        for (String key : keys) {
            long delta = current.getOrDefault(key, 0L) - baseline.getOrDefault(key, 0L);
            if (delta != 0) {
                result.put(key, delta);
            }
        }
        return result;
    }

    public void clearAll() {
        sessions.clear();
    }

    /** Flushes active sessions during an orderly server stop. */
    public void closeAllSessions() {
        for (UUID playerUuid : new HashSet<>(sessions.keySet())) {
            closeSession(playerUuid, 0.0, 0.0, 0.0);
        }
        sessions.clear();
    }

    int sessionCount() {
        return sessions.size();
    }
}
