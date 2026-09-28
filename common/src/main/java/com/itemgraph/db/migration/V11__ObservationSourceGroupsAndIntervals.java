package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class V11__ObservationSourceGroupsAndIntervals implements SchemaMigration {

    @Override
    public int getVersion() {
        return 11;
    }

    @Override
    public String getDescription() {
        return "Add observation source groups and session time intervals; include target node in internal deduplication";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (!hasColumn(conn, "ig_observations", "timestamp_end_ms")) {
                stmt.execute("ALTER TABLE ig_observations ADD COLUMN timestamp_end_ms INTEGER DEFAULT NULL;");
            }
            if (!hasColumn(conn, "ig_inferred_edges", "edge_state")) {
                stmt.execute("ALTER TABLE ig_inferred_edges ADD COLUMN edge_state TEXT NOT NULL DEFAULT 'ACTIVE';");
            }
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_edges_state_time ON ig_inferred_edges(edge_state, time_start);");
            stmt.execute("DROP INDEX IF EXISTS idx_obs_internal_dedup;");
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_internal_dedup
                ON ig_observations(source_type, timestamp_ms, node_id, COALESCE(target_node_id, -1), fingerprint_id, amount, action_type, item_entity_uuid)
                WHERE source_event_id IS NULL;
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_observation_groups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    state TEXT NOT NULL CHECK(state IN ('CONFIRMED', 'AMBIGUOUS')),
                    match_basis TEXT NOT NULL,
                    explanation TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL
                );
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_observation_group_members (
                    group_id INTEGER NOT NULL REFERENCES ig_observation_groups(id),
                    observation_id INTEGER NOT NULL UNIQUE REFERENCES ig_observations(id),
                    member_role TEXT NOT NULL CHECK(member_role IN ('CANONICAL', 'CORROBORATING', 'CANDIDATE')),
                    PRIMARY KEY(group_id, observation_id)
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_group_members_group ON ig_observation_group_members(group_id, member_role);");
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_observation_match_checks (
                    observation_id INTEGER PRIMARY KEY REFERENCES ig_observations(id),
                    checked_at_ms INTEGER NOT NULL,
                    result TEXT NOT NULL CHECK(result IN ('NO_MATCH', 'CONFIRMED', 'AMBIGUOUS'))
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_source_match ON ig_observations(source_type, action_type, fingerprint_id, timestamp_ms);");
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
        }
        return false;
    }
}
