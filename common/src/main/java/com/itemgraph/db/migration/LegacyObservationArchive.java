package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Preserves the pre-topology-fix observation rows before legacy migrations clear the
 * active ledger. These rows remain immutable evidence but are not queried as current
 * graph observations because their endpoints were recorded with obsolete semantics.
 */
final class LegacyObservationArchive {

    private LegacyObservationArchive() {}

    static void preserveBeforeReset(Connection connection, int migrationVersion) throws SQLException {
        ensureArchiveTable(connection);
        String archiveSql = """
                INSERT INTO ig_legacy_observation_evidence (
                    archive_migration_version, original_observation_id, source_type,
                    source_event_id, timestamp_ms, node_id, target_node_id,
                    fingerprint_id, fingerprint_item_id, fingerprint_hash,
                    fingerprint_custom_name, fingerprint_rarity, fingerprint_component_summary,
                    action_type, amount, raw_data
                )
                SELECT ?, o.id, o.source_type, o.source_event_id,
                       o.timestamp_ms, o.node_id, o.target_node_id,
                       o.fingerprint_id, fingerprint.item_id, fingerprint.fingerprint_hash,
                       fingerprint.custom_name, fingerprint.rarity, fingerprint.component_summary,
                       o.action_type, o.amount, o.raw_data
                FROM ig_observations o
                LEFT JOIN ig_item_fingerprints fingerprint ON fingerprint.id = o.fingerprint_id
                WHERE NOT EXISTS (
                    SELECT 1 FROM ig_legacy_observation_evidence archived
                    WHERE archived.archive_migration_version = ?
                      AND archived.original_observation_id = o.id
                )
                """;
        try (PreparedStatement statement = connection.prepareStatement(archiveSql)) {
            statement.setInt(1, migrationVersion);
            statement.setInt(2, migrationVersion);
            statement.executeUpdate();
        }
    }

    static void ensureArchiveTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ig_legacy_observation_evidence (
                        archive_migration_version INTEGER NOT NULL,
                        original_observation_id INTEGER NOT NULL,
                        source_type TEXT NOT NULL,
                        source_event_id INTEGER,
                        timestamp_ms INTEGER NOT NULL,
                        node_id INTEGER NOT NULL,
                        target_node_id INTEGER,
                        fingerprint_id INTEGER NOT NULL,
                        fingerprint_item_id TEXT,
                        fingerprint_hash TEXT,
                        fingerprint_custom_name TEXT,
                        fingerprint_rarity TEXT,
                        fingerprint_component_summary TEXT,
                        action_type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        raw_data BLOB,
                        PRIMARY KEY (archive_migration_version, original_observation_id)
                    )
                    """);
        }
    }
}
