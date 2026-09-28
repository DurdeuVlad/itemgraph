package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Native audit records that do not describe item quantity flow.
 *
 * <p>ItemGraph's item graph remains in {@code ig_observations}; this table is
 * the loader-independent evidence ledger for GriefLogger parity categories
 * such as block actions, player sessions, chat, and commands.</p>
 */
public class V13__AuditEvents implements SchemaMigration {
    @Override
    public int getVersion() {
        return 13;
    }

    @Override
    public String getDescription() {
        return "Add native audit event ledger for block, session, chat, and command evidence";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_audit_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    player_uuid TEXT,
                    player_name TEXT,
                    level_id TEXT,
                    x REAL,
                    y REAL,
                    z REAL,
                    subject_id TEXT,
                    detail TEXT,
                    source_type TEXT NOT NULL DEFAULT 'ITEMGRAPH_INTERNAL',
                    source_event_id INTEGER,
                    raw_data BLOB
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_events_type_time
                ON ig_audit_events(event_type, timestamp_ms)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_events_player_time
                ON ig_audit_events(player_uuid, timestamp_ms)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_audit_events_location_time
                ON ig_audit_events(level_id, x, y, z, timestamp_ms)
            """);
        }
    }
}
