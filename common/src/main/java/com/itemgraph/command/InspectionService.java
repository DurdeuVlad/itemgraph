package com.itemgraph.command;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player state for the command-toggled in-world container inspector. */
public final class InspectionService {

    private static final InspectionService INSTANCE = new InspectionService();

    private final Set<UUID> enabledPlayers = ConcurrentHashMap.newKeySet();

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

    public void clear(UUID playerUuid) {
        enabledPlayers.remove(playerUuid);
    }

    public void clear() {
        enabledPlayers.clear();
    }
}
