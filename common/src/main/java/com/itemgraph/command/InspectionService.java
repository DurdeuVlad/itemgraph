package com.itemgraph.command;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player state for the command-toggled in-world container inspector. */
public final class InspectionService {

    /**
     * Game-time window during which packets belonging to a consumed click are
     * still treated as part of that click. The client sends the off-hand
     * {@code useItemOn} and the item-use twin of a right-click as separate
     * packets that can straddle a server tick boundary, and the
     * revocation-detecting click clears the mode before its twin arrives, so a
     * small window — not just the current tick — is required.
     */
    public static final long CONSUMED_PACKET_WINDOW_TICKS = 5;

    private static final InspectionService INSTANCE = new InspectionService();

    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> consumedInteractionTicks = new ConcurrentHashMap<>();

    InspectionService() {}

    public static InspectionService getInstance() {
        return INSTANCE;
    }

    public boolean toggle(UUID playerUuid) {
        if (enabledPlayers.remove(playerUuid)) {
            return false;
        }
        enabledPlayers.add(playerUuid);
        return true;
    }

    public boolean setEnabled(UUID playerUuid, boolean enabled) {
        if (enabled) {
            return enabledPlayers.add(playerUuid);
        }
        return enabledPlayers.remove(playerUuid);
    }

    public boolean isEnabled(UUID playerUuid) {
        return enabledPlayers.contains(playerUuid);
    }

    /**
     * Records that a right-click interaction was consumed at {@code gameTime} so
     * the click's twin packets (off-hand {@code useItemOn}, item use) can be
     * consumed even after the mode is cleared by the click that detected a
     * permission revocation. Deliberately not cleared by {@link #clear(UUID)} —
     * that is exactly the revocation case the marker exists for.
     */
    public void markInteractionConsumed(UUID playerUuid, long gameTime) {
        consumedInteractionTicks.put(playerUuid, gameTime);
    }

    /** Whether a click was consumed for this player within the twin-packet window. */
    public boolean consumedInteractionRecently(UUID playerUuid, long gameTime) {
        Long tick = consumedInteractionTicks.get(playerUuid);
        if (tick == null) {
            return false;
        }
        if (gameTime < tick || gameTime - tick > CONSUMED_PACKET_WINDOW_TICKS) {
            consumedInteractionTicks.remove(playerUuid, tick);
            return false;
        }
        return true;
    }

    public void clear(UUID playerUuid) {
        enabledPlayers.remove(playerUuid);
    }

    public void clear() {
        enabledPlayers.clear();
        consumedInteractionTicks.clear();
    }
}
