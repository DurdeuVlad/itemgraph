package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measures one player's Ender Chest menu session without polling the inventory.
 * Ender Chest contents are player-owned and have no world position, so the
 * resulting observations use a durable {@code ENDER_CHEST} endpoint keyed by the
 * player's UUID. The player's position is retained only as the interaction
 * context at close time.
 */
public final class EnderChestInteractionTracker {
    private static final long SHUTDOWN_QUEUE_RETRY_BUDGET_MS = 5_000L;
    private static final long SHUTDOWN_QUEUE_RETRY_INTERVAL_MS = 25L;
    private static final EnderChestInteractionTracker INSTANCE = new EnderChestInteractionTracker(
            observations -> InternalObservationService.getInstance().submitAll(observations));
    private static final Logger LOGGER = LoggerFactory.getLogger(EnderChestInteractionTracker.class);

    private record Session(String playerName, String levelName,
                           Supplier<ContainerInteractionTracker.InventoryTotals> snapshotSource,
                           Map<String, Long> baseline,
                           Map<String, CanonicalItem> exemplars,
                           long windowStartMs,
                           double lastX, double lastY, double lastZ) {
    }

    private enum CloseResult {
        CLOSED,
        SNAPSHOT_FAILED,
        QUEUE_REJECTED
    }

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Predicate<List<InternalObservationService.InternalObservation>> observationSink;

    EnderChestInteractionTracker(
            Predicate<List<InternalObservationService.InternalObservation>> observationSink) {
        this.observationSink = observationSink;
    }

    public static EnderChestInteractionTracker getInstance() {
        return INSTANCE;
    }

    /** Opens or replaces a player-owned Ender Chest session. */
    public void openSession(UUID playerUuid, String playerName, String levelName,
                            Supplier<ContainerInteractionTracker.InventoryTotals> snapshotSource) {
        openSession(playerUuid, playerName, levelName, Double.NaN, Double.NaN, Double.NaN, snapshotSource);
    }

    /** Opens a session while retaining the player's last known server position. */
    public void openSession(UUID playerUuid, String playerName, String levelName,
                            double x, double y, double z,
                            Supplier<ContainerInteractionTracker.InventoryTotals> snapshotSource) {
        if (playerUuid == null || snapshotSource == null) {
            return;
        }
        // A menu switch can skip a loader close callback. Flush a block-container
        // watch before replacing it with this player-owned inventory session.
        if (!ContainerInteractionTracker.getInstance().closeSession(
                playerUuid, Double.NaN, Double.NaN, Double.NaN)) {
            // Keep the block session retryable rather than opening a second
            // player-owned session after a failed final snapshot.
            return;
        }
        if (!closeSession(playerUuid, Double.NaN, Double.NaN, Double.NaN)) {
            // Preserve the old session when its final snapshot failed. Replacing
            // it would discard the only retryable evidence window.
            return;
        }
        ContainerInteractionTracker.InventoryTotals totals = snapshotSource.get();
        sessions.put(playerUuid, new Session(
                playerName == null ? "" : playerName,
                levelName == null ? "" : levelName,
                snapshotSource,
                new HashMap<>(totals.counts()),
                new HashMap<>(totals.exemplars()),
                System.currentTimeMillis(), x, y, z));
    }

    /** Closes a session and emits signed Ender Chest quantity deltas. */
    public boolean closeSession(UUID playerUuid, double x, double y, double z) {
        return closeSessionResult(playerUuid, x, y, z) == CloseResult.CLOSED;
    }

    private CloseResult closeSessionResult(UUID playerUuid, double x, double y, double z) {
        Session session = sessions.get(playerUuid);
        if (session == null) {
            return CloseResult.CLOSED;
        }
        double resolvedX = Double.isFinite(x) ? x : session.lastX();
        double resolvedY = Double.isFinite(y) ? y : session.lastY();
        double resolvedZ = Double.isFinite(z) ? z : session.lastZ();
        Session positioned = new Session(session.playerName(), session.levelName(), session.snapshotSource(),
                session.baseline(), session.exemplars(), session.windowStartMs(),
                resolvedX, resolvedY, resolvedZ);
        sessions.replace(playerUuid, session, positioned);
        session = positioned;
        ContainerInteractionTracker.InventoryTotals current;
        try {
            current = session.snapshotSource().get();
        } catch (RuntimeException e) {
            LOGGER.warn("ItemGraph: failed to snapshot Ender Chest session for {}: {}",
                    playerUuid, e.toString());
            return CloseResult.SNAPSHOT_FAILED;
        }
        long endMs = System.currentTimeMillis();
        Map<String, Long> delta = computeDelta(session.baseline(), current.counts());
        Map<String, CanonicalItem> exemplars = new HashMap<>(session.exemplars());
        exemplars.putAll(current.exemplars());
        List<InternalObservationService.InternalObservation> observations = new ArrayList<>();
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
                observations.add(
                        new InternalObservationService.InternalObservation(
                                session.windowStartMs(), action, playerUuid.toString(), session.playerName(),
                                session.levelName(), session.lastX(), session.lastY(), session.lastZ(),
                                session.levelName(), null, null, null, "ENDER_CHEST",
                                item.itemId(), rawData, item, amount, null, endMs));
                remaining -= amount;
            }
        }
        try {
            if (!observationSink.test(List.copyOf(observations))) {
                return CloseResult.QUEUE_REJECTED;
            }
        } catch (RuntimeException e) {
            LOGGER.warn("ItemGraph: failed to enqueue Ender Chest deltas for {}; retaining session for retry: {}",
                    playerUuid, e.toString());
            return CloseResult.QUEUE_REJECTED;
        }
        sessions.remove(playerUuid, session);
        return CloseResult.CLOSED;
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
        closeAllSessions(SHUTDOWN_QUEUE_RETRY_BUDGET_MS);
    }

    void closeAllSessions(long retryBudgetMs) {
        long retryDeadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, retryBudgetMs));
        for (UUID playerUuid : new HashSet<>(sessions.keySet())) {
            CloseResult result = CloseResult.QUEUE_REJECTED;
            int attempts = 0;
            while (true) {
                attempts++;
                result = closeSessionResult(playerUuid, Double.NaN, Double.NaN, Double.NaN);
                long remainingNanos = retryDeadlineNanos - System.nanoTime();
                if (result != CloseResult.QUEUE_REJECTED || remainingNanos <= 0L) {
                    break;
                }
                try {
                    long sleepNanos = Math.min(remainingNanos,
                            TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_QUEUE_RETRY_INTERVAL_MS));
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (result != CloseResult.CLOSED) {
                LOGGER.error("ItemGraph: could not flush Ender Chest session for {} during shutdown (result={}, attempts={}); discarding volatile session state",
                        playerUuid, result, attempts);
                sessions.remove(playerUuid);
            }
        }
    }

    int sessionCount() {
        return sessions.size();
    }
}
