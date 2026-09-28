package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PREVIEW_1 API storage: durable source registrations and durable external
 * inventory node identity. This migration only changes ItemGraph's own schema.
 */
public class V12__PreviewApiSourcesAndExternalNodes implements SchemaMigration {

    @Override
    public int getVersion() {
        return 12;
    }

    @Override
    public String getDescription() {
        return "Add preview API source registry and external inventory node keys";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS ig_api_sources (
                        source_mod_id TEXT PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        api_version INTEGER NOT NULL,
                        registered_at_ms INTEGER NOT NULL,
                        last_seen_ms INTEGER NOT NULL
                    )
                    """);
        }

        if (!hasColumn(conn, "ig_nodes", "external_key")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE ig_nodes ADD COLUMN external_key TEXT");
            }
        }

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_nodes_external_key
                    ON ig_nodes(node_type, external_key)
                    WHERE external_key IS NOT NULL
                    """);
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
