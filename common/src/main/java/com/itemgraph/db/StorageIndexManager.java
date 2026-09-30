package com.itemgraph.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Applies the configured policy to ItemGraph's optional, non-unique indexes. */
final class StorageIndexManager {
    private record IndexKey(String table, String name) {
        IndexKey {
            table = normalize(table);
            name = normalize(name);
        }
    }

    private record Index(String table, String name, String columns) {
        String createSql() {
            return "CREATE INDEX " + name + " ON " + table + "(" + columns + ")";
        }
    }

    // Unique indexes and constraints are deliberately migration-owned and never
    // appear here: disabling performance indexes must not weaken deduplication.
    private static final List<Index> OPTIONAL_INDEXES = List.of(
            index("ig_observations", "idx_obs_time_fp", "timestamp_ms, fingerprint_id"),
            index("ig_observations", "idx_obs_correlation_pending", "correlated_at, timestamp_ms"),
            index("ig_observations", "idx_obs_bridge_lookup", "node_id, fingerprint_id, timestamp_ms"),
            index("ig_observations", "idx_obs_bridge_target_lookup", "target_node_id, fingerprint_id, timestamp_ms"),
            index("ig_edge_evidence", "idx_edge_evidence_obs", "observation_id"),
            index("ig_edge_allocations", "idx_alloc_obs_role", "observation_id, allocation_role"),
            index("ig_edge_allocations", "idx_alloc_edge", "edge_id"),
            index("ig_observations", "idx_obs_corr_status_time", "correlation_status, timestamp_ms"),
            index("ig_observations", "idx_obs_entity_uuid", "item_entity_uuid"),
            index("ig_item_transformations", "idx_trans_source_fp", "source_fingerprint_id"),
            index("ig_item_transformations", "idx_trans_result_fp", "result_fingerprint_id"),
            index("ig_item_transformations", "idx_trans_player", "player_node_id, timestamp_ms"),
            index("ig_observations", "idx_obs_action_type", "action_type"),
            index("ig_inferred_edges", "idx_edges_state_time", "edge_state, time_start"),
            index("ig_observation_group_members", "idx_obs_group_members_group", "group_id, member_role"),
            index("ig_observations", "idx_obs_source_match", "source_type, action_type, fingerprint_id, timestamp_ms"),
            index("ig_audit_events", "idx_audit_events_type_time", "event_type, timestamp_ms"),
            index("ig_audit_events", "idx_audit_events_player_time", "player_uuid, timestamp_ms"),
            index("ig_audit_events", "idx_audit_events_location_time", "level_id, x, y, z, timestamp_ms"),
            index("ig_grieflogger_rows", "idx_gl_import_rows_table_key", "table_name, source_key"),
            index("ig_grieflogger_rows", "idx_gl_import_rows_action", "action_id, table_name"),
            index("ig_grieflogger_lookup", "idx_gl_lookup_time", "timestamp_ms DESC, source_sha256, table_name, source_key"),
            index("ig_grieflogger_lookup", "idx_gl_lookup_action", "action_type, timestamp_ms DESC"),
            index("ig_grieflogger_lookup", "idx_gl_lookup_actor", "player_uuid, player_name, timestamp_ms DESC"),
            index("ig_grieflogger_lookup", "idx_gl_lookup_location", "level_name, x, y, z, timestamp_ms DESC"),
            index("ig_grieflogger_lookup", "idx_gl_lookup_subject", "subject_id, timestamp_ms DESC")
    );

    private StorageIndexManager() {
    }

    static void apply(Connection connection, DatabaseDialect dialect, boolean useIndexes) throws SQLException {
        Set<IndexKey> existing = existingIndexNames(connection);
        try (Statement statement = connection.createStatement()) {
            for (Index index : OPTIONAL_INDEXES) {
                boolean present = existing.contains(new IndexKey(index.table(), index.name()));
                if (useIndexes && !present) {
                    statement.execute(index.createSql());
                } else if (!useIndexes && present) {
                    statement.execute(dialect == DatabaseDialect.SQLITE
                            ? "DROP INDEX " + index.name()
                            : "DROP INDEX " + index.name() + " ON " + index.table());
                }
            }
        }
    }

    private static Set<IndexKey> existingIndexNames(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Set<IndexKey> indexes = new LinkedHashSet<>();
        Set<String> tables = new LinkedHashSet<>();
        for (Index index : OPTIONAL_INDEXES) tables.add(index.table());
        for (String expectedTable : tables) {
            for (String tablePattern : List.of(expectedTable, expectedTable.toUpperCase(Locale.ROOT))) {
                try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), null, tablePattern, false, false)) {
                    while (result.next()) {
                        String name = result.getString("INDEX_NAME");
                        String actualTable = result.getString("TABLE_NAME");
                        if (name != null && actualTable != null) indexes.add(new IndexKey(actualTable, name));
                    }
                }
            }
        }
        return indexes;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static Index index(String table, String name, String columns) {
        return new Index(table, name, columns);
    }
}
