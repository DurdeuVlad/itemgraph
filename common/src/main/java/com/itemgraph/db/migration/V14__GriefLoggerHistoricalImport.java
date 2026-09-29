package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Stores immutable, provenance-preserving rows imported from a GriefLogger
 * database. The importer keeps these rows separate from the quantity ledger so
 * unknown actions and opaque payloads remain queryable without being reinterpreted.
 */
public class V14__GriefLoggerHistoricalImport implements SchemaMigration {
    @Override
    public int getVersion() {
        return 14;
    }

    @Override
    public String getDescription() {
        return "Add immutable GriefLogger historical import ledger and checkpoints";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_grieflogger_import_runs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_path TEXT NOT NULL,
                    source_sha256 TEXT NOT NULL,
                    schema_fingerprint TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    completed_at INTEGER,
                    status TEXT NOT NULL,
                    table_count INTEGER NOT NULL DEFAULT 0,
                    rows_imported INTEGER NOT NULL DEFAULT 0,
                    rows_opaque INTEGER NOT NULL DEFAULT 0,
                    report_json TEXT
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_grieflogger_import_checkpoints (
                    source_sha256 TEXT NOT NULL,
                    table_name TEXT NOT NULL,
                    last_source_key TEXT NOT NULL,
                    rows_seen INTEGER NOT NULL DEFAULT 0,
                    rows_imported INTEGER NOT NULL DEFAULT 0,
                    rows_opaque INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (source_sha256, table_name)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_grieflogger_rows (
                    source_sha256 TEXT NOT NULL,
                    schema_fingerprint TEXT NOT NULL,
                    table_name TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    source_rowid INTEGER,
                    row_ordinal INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    payload_blob BLOB,
                    action_id INTEGER,
                    imported_at INTEGER NOT NULL,
                    unresolved_reason TEXT,
                    PRIMARY KEY (source_sha256, table_name, source_key)
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_import_rows_table_key
                ON ig_grieflogger_rows(table_name, source_key)
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_gl_import_rows_action
                ON ig_grieflogger_rows(action_id, table_name)
            """);
        }
    }
}
