package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Adds the normalized, queryable projection of imported GriefLogger event rows.
 * The immutable generic ledger remains the source of truth; this table is a
 * rebuildable lookup index keyed by the source database hash, table, and row key.
 */
public final class V15__GriefLoggerHistoricalLookup implements SchemaMigration {
    @Override
    public int getVersion() {
        return 15;
    }

    @Override
    public String getDescription() {
        return "Add normalized GriefLogger historical event lookup projection";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_grieflogger_lookup (
                    source_sha256 TEXT NOT NULL,
                    table_name TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    source_rowid INTEGER,
                    timestamp_ms INTEGER,
                    level_name TEXT,
                    x REAL,
                    y REAL,
                    z REAL,
                    player_name TEXT,
                    player_uuid TEXT,
                    action_type TEXT NOT NULL,
                    quantity INTEGER NOT NULL DEFAULT 0,
                    subject_id TEXT,
                    detail TEXT,
                    evidence_class TEXT NOT NULL,
                    unresolved_reason TEXT,
                    raw_byte_hash TEXT,
                    PRIMARY KEY (source_sha256, table_name, source_key)
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_lookup_time
                ON ig_grieflogger_lookup(timestamp_ms DESC, source_sha256, table_name, source_key)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_lookup_action
                ON ig_grieflogger_lookup(action_type, timestamp_ms DESC)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_lookup_actor
                ON ig_grieflogger_lookup(player_uuid, player_name, timestamp_ms DESC)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_lookup_location
                ON ig_grieflogger_lookup(level_name, x, y, z, timestamp_ms DESC)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_lookup_subject
                ON ig_grieflogger_lookup(subject_id, timestamp_ms DESC)
            """);
        }
    }
}
