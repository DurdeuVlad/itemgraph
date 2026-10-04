package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** ItemGraph-owned UUID/name history learned from native player-session evidence. */
public final class V21__PlayerNameHistory implements SchemaMigration {
    @Override
    public int getVersion() {
        return 21;
    }

    @Override
    public String getDescription() {
        return "Add ItemGraph-owned player UUID and historical-name index";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ig_player_name_history (
                        player_uuid VARCHAR(36) NOT NULL,
                        normalized_name VARCHAR(16) NOT NULL,
                        player_name VARCHAR(16) NOT NULL,
                        first_seen_ms BIGINT NOT NULL,
                        last_seen_ms BIGINT NOT NULL,
                        PRIMARY KEY (player_uuid, normalized_name)
                    )
                    """);
            statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_player_name_history_name
                    ON ig_player_name_history(normalized_name, player_uuid)
                    """);
            statement.execute("""
                    INSERT OR IGNORE INTO ig_player_name_history (
                        player_uuid, normalized_name, player_name, first_seen_ms, last_seen_ms
                    )
                    SELECT TRIM(player_uuid), LOWER(TRIM(player_name)), MAX(TRIM(player_name)),
                           MIN(timestamp_ms), MAX(timestamp_ms)
                    FROM ig_audit_events
                    WHERE event_type = 'PLAYER_JOIN'
                      AND player_uuid IS NOT NULL AND TRIM(player_uuid) <> ''
                      AND player_name IS NOT NULL AND TRIM(player_name) <> ''
                      AND LENGTH(TRIM(player_uuid)) <= 36
                      AND LENGTH(TRIM(player_name)) <= 16
                    GROUP BY TRIM(player_uuid), LOWER(TRIM(player_name))
                    """);
        }
    }
}
