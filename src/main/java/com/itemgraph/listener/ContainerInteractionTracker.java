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
import java.util.function.Supplier;

/**
 * Tracks open player container sessions and per-container automation deltas so that
 * container contents changes can be attributed to the correct actor (0.2.0 — Issue 3).
 *
 * <h2>Why session diffing instead of IItemHandler interception</h2>
 * <p>Vanilla container menus mutate the backing {@code Container} directly
 * ({@code AbstractContainerMenu.moveItemStackTo}, {@code Slot.set}) — player GUI
 * clicks never traverse the {@code IItemHandler} capability, so no capability
 * wrapper can observe them. Player-driven transfers are therefore observed by
 * diffing the watched container's fingerprint totals between
 * {@code PlayerContainerEvent.Open} and {@code PlayerContainerEvent.Close}.
 *
 * <h2>Automation credits</h2>
 * <p>{@link ContainerCapabilityWrapper} still observes real automated transfers
 * through the {@code IItemHandler} capability. While a container is watched it
 * reports every moved quantity here as a signed automation credit
 * (+insert / -extract). At close, the credit is subtracted from the net content
 * delta so hopper moves that ran during an open session are not attributed to the
 * player and are not double-counted.
 *
 * <h2>Attribution</h2>
 * <p>A residual delta is attributed to the session's participants: exactly one
 * participant means unambiguous {@code ADD_ITEM}/{@code REMOVE_ITEM}; multiple
 * participants emit a single {@code [ambiguous]} observation whose
 * {@code raw_data} lists the candidate players (the actor cannot be determined,
 * and emitting one row per candidate would manufacture quantity).
 *
 * <h2>Threading</h2>
 * <p>All callers (container events, capability invocations) run on the server
 * thread, but the map types are safe for concurrent use anyway.
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

    private static final class Watch {
        final ContainerKey key;
        final Supplier<InventoryTotals> snapshotSource;
        /** Players that currently hold this container open (uuid -> name). */
        final Map<UUID, String> sessions = new LinkedHashMap<>();
        /** Every session active at any point since the baseline snapshot (uuid -> name). */
        final Map<UUID, String> participants = new LinkedHashMap<>();
        Map<String, Long> baseline;
        Map<String, CanonicalItem> exemplars;
        /** Signed automation deltas observed while watched: +insert, -extract. */
        final Map<String, Long> automationDelta = new ConcurrentHashMap<>();

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

    /**
     * Records a signed automated transfer against the watched container:
     * {@code +count} for an insertion, {@code -count} for an extraction.
     * No-op when nothing is watching the container (pure automation traffic is
     * already reported by the capability wrapper as {@code HOPPER_*} observations).
     */
    public void recordAutomationDelta(ContainerKey key, String fingerprintHash, long delta) {
        Watch watch = watches.get(resolve(key));
        if (watch != null) {
            watch.automationDelta.merge(fingerprintHash, delta, Long::sum);
        }
    }

    /**
     * Whether a session watch exists for the container at {@code key}.
     */
    public boolean isWatched(ContainerKey key) {
        return watches.containsKey(resolve(key));
    }

    /**
     * Closes the player's session: recomputes the container totals, subtracts the
     * automation credits accumulated during the window, and emits one
     * {@code ADD_ITEM}/{@code REMOVE_ITEM} observation per fingerprint for the
     * residual (player-attributable) delta. {@code x,y,z} is the closing player's
     * position.
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
        Map<String, Long> playerDelta = computePlayerDelta(
                watch.baseline, now.counts(), watch.automationDelta);

        Map<String, CanonicalItem> exemplars = new HashMap<>(watch.exemplars);
        exemplars.putAll(now.exemplars());
        emit(watch, playerDelta, exemplars, x, y, z);

        // The emitted window ends here: the next delta window starts from now and
        // only the still-open sessions can be responsible for further changes.
        watch.baseline = new HashMap<>(now.counts());
        watch.exemplars = new HashMap<>(now.exemplars());
        watch.automationDelta.clear();
        watch.sessions.remove(playerUuid);
        watch.participants.clear();
        watch.participants.putAll(watch.sessions);
        if (watch.sessions.isEmpty()) {
            watches.remove(watch.key);
            aliases.values().removeIf(watch.key::equals);
        }
    }

    /**
     * Net player-attributable delta per fingerprint:
     * {@code (current - baseline) - automationCredits}, zeroes removed.
     */
    static Map<String, Long> computePlayerDelta(Map<String, Long> baseline,
                                                Map<String, Long> current,
                                                Map<String, Long> automationCredits) {
        Map<String, Long> delta = new HashMap<>();
        for (String fp : unionKeys(baseline, current)) {
            long net = current.getOrDefault(fp, 0L) - baseline.getOrDefault(fp, 0L);
            long player = net - automationCredits.getOrDefault(fp, 0L);
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

    private void emit(Watch watch, Map<String, Long> playerDelta,
                      Map<String, CanonicalItem> exemplars, double x, double y, double z) {
        if (playerDelta.isEmpty()) {
            return;
        }
        boolean unambiguous = watch.participants.size() == 1;
        String actorUuid;
        String actorName;
        byte[] rawData;
        if (unambiguous) {
            Map.Entry<UUID, String> actor = watch.participants.entrySet().iterator().next();
            actorUuid = actor.getKey().toString();
            actorName = actor.getValue();
            rawData = null;
        } else {
            actorUuid = AMBIGUOUS_UUID;
            actorName = AMBIGUOUS_NAME;
            rawData = candidatesJson(watch.participants);
        }

        for (Map.Entry<String, Long> e : playerDelta.entrySet()) {
            CanonicalItem canonical = exemplars.get(e.getKey());
            if (canonical == null) {
                continue;
            }
            long delta = e.getValue();
            InternalObservationService.getInstance().submit(
                    new InternalObservationService.InternalObservation(
                            System.currentTimeMillis(),
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
                            null
                    )
            );
        }
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
    }

    public int watchCount() {
        return watches.size();
    }

    public int sessionCount() {
        return playerSessions.size();
    }
}
