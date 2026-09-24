package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Tracks open player container sessions and capability-mediated deltas (0.2.0 — Issue 3).
 *
 * <h2>Session evidence</h2>
 * <p>Vanilla container menus mutate the backing {@code Container} directly, so player
 * transfers are measured as fingerprint-level net changes between
 * {@code PlayerContainerEvent.Open} and {@code PlayerContainerEvent.Close}. Each row is
 * an interval-bounded session net delta, not a click-time event. Transfers that return
 * the container to its baseline are not represented and their absence is not evidence
 * that no interaction occurred.
 *
 * <h2>Capability reconciliation</h2>
 * <p>{@link ContainerCapabilityWrapper} observes content changes made through an
 * {@code IItemHandler}. The caller is unknown, so its signed delta is excluded from the
 * player session net delta whether the raw row was queued or retained as unresolved after
 * queue rejection.
 *
 * <h2>Attribution</h2>
 * <p>A residual delta is attributed to the session's participants: exactly one
 * participant means a single candidate; multiple participants emit one ambiguous row
 * with the candidate players in {@code raw_data}. Quantity is never multiplied by the
 * number of viewers.
 *
 * <h2>Threading</h2>
 * <p>All callers run on the server thread. The maps remain safe for concurrent access.
 */
public class ContainerInteractionTracker {

    private static final ContainerInteractionTracker INSTANCE = new ContainerInteractionTracker();

    /** Sentinel identity for transfers whose actor cannot be resolved to one player. */
    public static final String AMBIGUOUS_UUID = "00000000-0000-0000-0000-000000000001";
    public static final String AMBIGUOUS_NAME = "[ambiguous]";

    public static ContainerInteractionTracker getInstance() {
        return INSTANCE;
    }

    /** Location key for one physical container (a double chest uses the clicked half). */
    public record ContainerKey(String levelId, int x, int y, int z) {}

    /**
     * Fingerprint-level snapshot of a container's contents: total count per
     * fingerprint plus one representative {@link CanonicalItem} per fingerprint.
     */
    public record InventoryTotals(Map<String, Long> counts, Map<String, CanonicalItem> exemplars) {}

    private record CapabilityGapKey(String fingerprintHash, String actionType) {}

    private record CapabilityGap(CanonicalItem item, long amount) {}

    private static final class Watch {
        final ContainerKey key;
        final Supplier<InventoryTotals> snapshotSource;
        /** Players that currently hold this container open (uuid -> name). */
        final Map<UUID, String> sessions = new LinkedHashMap<>();
        /** Every session active at any point since the baseline snapshot (uuid -> name). */
        final Map<UUID, String> participants = new LinkedHashMap<>();
        Map<String, Long> baseline;
        Map<String, CanonicalItem> exemplars;
        long windowStartMs;
        /** Signed capability deltas observed while watched: +insert, -extract. */
        final Map<String, Long> capabilityDelta = new ConcurrentHashMap<>();
        final Map<CapabilityGapKey, CapabilityGap> unpersistedCapabilityTransfers = new HashMap<>();

        Watch(ContainerKey key, Supplier<InventoryTotals> snapshotSource) {
            this.key = key;
            this.snapshotSource = snapshotSource;
        }
    }

    /** Canonical container key -> active watch. */
    private final Map<ContainerKey, Watch> watches = new ConcurrentHashMap<>();
    /** Secondary positions (e.g. the second half of a double chest) -> canonical key. */
    private final Map<ContainerKey, ContainerKey> aliases = new ConcurrentHashMap<>();
    /** Player uuid -> canonical key of the container they have open. */
    private final Map<UUID, ContainerKey> playerSessions = new ConcurrentHashMap<>();
    private final AtomicLong totalCapabilityQueueRejections = new AtomicLong();

    private ContainerInteractionTracker() {}

    /**
     * Opens a player session on the container at {@code key}. {@code snapshotSource}
     * must return a fresh {@link InventoryTotals} of the container's live contents;
     * it is invoked immediately for the baseline and again on session close.
     * {@code aliases} are secondary positions (double-chest partner half) whose
     * capability calls must resolve to the same watch.
     */
    public void openSession(UUID playerUuid, String playerName, ContainerKey key,
                            Supplier<InventoryTotals> snapshotSource, List<ContainerKey> aliases) {
        ContainerKey previous = playerSessions.get(playerUuid);
        if (previous != null) {
            // Defensive: a menu switch without an intervening Close event — flush
            // the previous window, anchored at the previous container's position.
            closeSession(playerUuid, previous.x(), previous.y(), previous.z());
        }
        ContainerKey canonical = resolve(key);
        Watch watch = watches.computeIfAbsent(canonical, k -> {
            Watch w = new Watch(k, snapshotSource);
            InventoryTotals totals = snapshotSource.get();
            w.baseline = new HashMap<>(totals.counts());
            w.exemplars = new HashMap<>(totals.exemplars());
            w.windowStartMs = System.currentTimeMillis();
            return w;
        });
        if (aliases != null) {
            for (ContainerKey alias : aliases) {
                if (!alias.equals(canonical)) {
                    this.aliases.put(alias, canonical);
                }
            }
        }
        watch.sessions.put(playerUuid, playerName);
        watch.participants.putIfAbsent(playerUuid, playerName);
        playerSessions.put(playerUuid, canonical);
    }

    public void recordCapabilityDelta(ContainerKey key, CanonicalItem item, long delta, boolean persisted) {
        if (!persisted) {
            totalCapabilityQueueRejections.incrementAndGet();
        }
        Watch watch = watches.get(resolve(key));
        if (watch == null) {
            return;
        }

        watch.capabilityDelta.merge(item.fingerprintHash(), delta, Long::sum);
        if (!persisted) {
            String actionType = delta > 0 ? "CAPABILITY_INSERT" : "CAPABILITY_EXTRACT";
            CapabilityGapKey gapKey = new CapabilityGapKey(item.fingerprintHash(), actionType);
            watch.unpersistedCapabilityTransfers.merge(gapKey,
                    new CapabilityGap(item, Math.abs(delta)),
                    (existing, added) -> new CapabilityGap(existing.item(), existing.amount() + added.amount()));
        }
    }

    public long getTotalCapabilityQueueRejections() {
        return totalCapabilityQueueRejections.get();
    }

    /**
     * Whether a session watch exists for the container at {@code key}.
     */
    public boolean isWatched(ContainerKey key) {
        return watches.containsKey(resolve(key));
    }

    /**
     * Closes the player's session: recomputes the container totals, subtracts the
     * capability deltas accumulated during the window, and emits one interval-bounded
     * {@code ADD_ITEM}/{@code REMOVE_ITEM} net observation per remaining fingerprint.
     * {@code x,y,z} is the closing player's position.
     */
    public void closeSession(UUID playerUuid, double x, double y, double z) {
        ContainerKey key = playerSessions.remove(playerUuid);
        if (key == null) {
            return;
        }
        Watch watch = watches.get(resolve(key));
        if (watch == null) {
            return;
        }

        InventoryTotals now = watch.snapshotSource.get();
        long windowEndMs = System.currentTimeMillis();
        Map<String, Long> playerDelta = computePlayerDelta(
                watch.baseline, now.counts(), watch.capabilityDelta);

        Map<String, CanonicalItem> exemplars = new HashMap<>(watch.exemplars);
        exemplars.putAll(now.exemplars());
        emit(watch, playerDelta, exemplars, x, y, z, watch.windowStartMs, windowEndMs);
        emitUnpersistedCapabilityTransfers(watch, watch.windowStartMs, windowEndMs);

        // The emitted window ends here: the next delta window starts from now and
        // only the still-open sessions can be responsible for further changes.
        watch.baseline = new HashMap<>(now.counts());
        watch.exemplars = new HashMap<>(now.exemplars());
        watch.windowStartMs = windowEndMs;
        watch.capabilityDelta.clear();
        watch.unpersistedCapabilityTransfers.clear();
        watch.sessions.remove(playerUuid);
        watch.participants.clear();
        watch.participants.putAll(watch.sessions);
        if (watch.sessions.isEmpty()) {
            watches.remove(watch.key);
            aliases.values().removeIf(watch.key::equals);
        }
    }

    /**
     * Net session delta per fingerprint after capability-mediated changes are removed.
     */
    static Map<String, Long> computePlayerDelta(Map<String, Long> baseline,
                                                Map<String, Long> current,
                                                Map<String, Long> capabilityCredits) {
        Map<String, Long> delta = new HashMap<>();
        for (String fp : unionKeys(baseline, current)) {
            long net = current.getOrDefault(fp, 0L) - baseline.getOrDefault(fp, 0L);
            long player = net - capabilityCredits.getOrDefault(fp, 0L);
            if (player != 0) {
                delta.put(fp, player);
            }
        }
        return delta;
    }

    private static List<String> unionKeys(Map<String, Long> a, Map<String, Long> b) {
        List<String> keys = new ArrayList<>(a.keySet());
        for (String k : b.keySet()) {
            if (!a.containsKey(k)) {
                keys.add(k);
            }
        }
        return keys;
    }

    private void emitUnpersistedCapabilityTransfers(Watch watch, long sessionStartMs, long sessionEndMs) {
        for (Map.Entry<CapabilityGapKey, CapabilityGap> entry : watch.unpersistedCapabilityTransfers.entrySet()) {
            long remaining = entry.getValue().amount();
            while (remaining > 0) {
                int amount = (int) Math.min(Integer.MAX_VALUE, remaining);
                byte[] rawData = ("{\"capture\":\"queue_overflow_recovery\",\"sessionStartMs\":"
                        + sessionStartMs + ",\"sessionEndMs\":" + sessionEndMs + "}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                InternalObservationService.getInstance().submit(
                        new InternalObservationService.InternalObservation(
                                sessionStartMs,
                                entry.getKey().actionType(),
                                ContainerCapabilityWrapper.UNKNOWN_CALLER_UUID,
                                ContainerCapabilityWrapper.UNKNOWN_CALLER_NAME,
                                watch.key.levelId(),
                                watch.key.x(), watch.key.y(), watch.key.z(),
                                watch.key.levelId(),
                                (double) watch.key.x(), (double) watch.key.y(), (double) watch.key.z(),
                                "CONTAINER",
                                entry.getValue().item().itemId(),
                                rawData,
                                entry.getValue().item(),
                                amount,
                                null,
                                sessionEndMs
                        )
                );
                remaining -= amount;
            }
        }
    }

    private void emit(Watch watch, Map<String, Long> playerDelta,
                      Map<String, CanonicalItem> exemplars, double x, double y, double z,
                      long sessionStartMs, long sessionEndMs) {
        if (playerDelta.isEmpty()) {
            return;
        }
        boolean unambiguous = watch.participants.size() == 1;
        String actorUuid;
        String actorName;
        if (unambiguous) {
            Map.Entry<UUID, String> actor = watch.participants.entrySet().iterator().next();
            actorUuid = actor.getKey().toString();
            actorName = actor.getValue();
        } else {
            actorUuid = AMBIGUOUS_UUID;
            actorName = AMBIGUOUS_NAME;
        }
        byte[] rawData = sessionWindowJson(sessionStartMs, sessionEndMs,
                unambiguous ? null : watch.participants);

        for (Map.Entry<String, Long> e : playerDelta.entrySet()) {
            CanonicalItem canonical = exemplars.get(e.getKey());
            if (canonical == null) {
                continue;
            }
            long delta = e.getValue();
            InternalObservationService.getInstance().submit(
                    new InternalObservationService.InternalObservation(
                            sessionStartMs,
                            delta > 0 ? "ADD_ITEM" : "REMOVE_ITEM",
                            actorUuid,
                            actorName,
                            watch.key.levelId(),
                            unambiguous ? x : watch.key.x(),
                            unambiguous ? y : watch.key.y(),
                            unambiguous ? z : watch.key.z(),
                            watch.key.levelId(),
                            (double) watch.key.x(), (double) watch.key.y(), (double) watch.key.z(),
                            "CONTAINER",
                            canonical.itemId(),
                            rawData,
                            canonical,
                            (int) Math.abs(delta),
                            null,
                            sessionEndMs
                    )
            );
        }
    }

    private static byte[] sessionWindowJson(long startMs, long endMs, Map<UUID, String> participants) {
        StringBuilder json = new StringBuilder("{\"capture\":\"container_session_net_delta\",\"sessionStartMs\":")
                .append(startMs).append(",\"sessionEndMs\":").append(endMs);
        if (participants != null) {
            String candidates = new String(candidatesJson(participants), java.nio.charset.StandardCharsets.UTF_8);
            json.append(',').append(candidates, 1, candidates.length() - 1);
        }
        return json.append('}').toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] candidatesJson(Map<UUID, String> participants) {
        StringBuilder sb = new StringBuilder("{\"ambiguousActorCandidates\":[");
        boolean first = true;
        for (Map.Entry<UUID, String> p : participants.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"uuid\":\"").append(p.getKey())
                    .append("\",\"name\":\"").append(escapeJson(p.getValue())).append("\"}");
        }
        return sb.append("]}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ContainerKey resolve(ContainerKey key) {
        ContainerKey canonical = aliases.get(key);
        return canonical != null ? canonical : key;
    }

    // Test support ------------------------------------------------------------

    /** Removes all watches, sessions and aliases (tests only). */
    public void clearAll() {
        watches.clear();
        aliases.clear();
        playerSessions.clear();
        totalCapabilityQueueRejections.set(0);
    }

    public int watchCount() {
        return watches.size();
    }

    public int sessionCount() {
        return playerSessions.size();
    }
}
