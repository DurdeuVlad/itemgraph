package com.itemgraph.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;

/** Rebuildable UUID/name index derived from ItemGraph's immutable player-join evidence. */
final class PlayerNameHistory {
    private PlayerNameHistory() {}

    static void recordJoin(Connection connection, String playerUuid, String playerName, long timestampMs)
            throws SQLException {
        if (playerUuid == null || playerUuid.isBlank() || playerUuid.trim().length() > 36
                || playerName == null || playerName.isBlank() || playerName.trim().length() > 16) {
            return;
        }
        String normalizedUuid = playerUuid.trim();
        String displayName = playerName.trim();
        String normalizedName = displayName.toLowerCase(Locale.ROOT);
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE ig_player_name_history
                SET player_name = CASE WHEN last_seen_ms <= ? THEN ? ELSE player_name END,
                    first_seen_ms = CASE WHEN first_seen_ms > ? THEN ? ELSE first_seen_ms END,
                    last_seen_ms = CASE WHEN last_seen_ms < ? THEN ? ELSE last_seen_ms END
                WHERE player_uuid = ? AND normalized_name = ?
                """)) {
            update.setLong(1, timestampMs);
            update.setString(2, displayName);
            update.setLong(3, timestampMs);
            update.setLong(4, timestampMs);
            update.setLong(5, timestampMs);
            update.setLong(6, timestampMs);
            update.setString(7, normalizedUuid);
            update.setString(8, normalizedName);
            update.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO ig_player_name_history (
                    player_uuid, normalized_name, player_name, first_seen_ms, last_seen_ms
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, normalizedUuid);
            insert.setString(2, normalizedName);
            insert.setString(3, displayName);
            insert.setLong(4, timestampMs);
            insert.setLong(5, timestampMs);
            insert.executeUpdate();
        }
    }
}
