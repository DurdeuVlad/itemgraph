package com.itemgraph.db.migration;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyObservationArchiveTest {

    @Test
    void topologyResetMigrationsPreserveRawObservationRowsBeforeClearingActiveLedger() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            new V1__InitialSchema().apply(connection);
            new V2__DeduplicationConstraint().apply(connection);

            for (int migrationVersion = 3; migrationVersion <= 5; migrationVersion++) {
                byte[] rawData = ("raw-evidence-v" + migrationVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String componentSummary = "component-" + "x".repeat(1_024);
                insertObservation(connection, migrationVersion, rawData, componentSummary);
                switch (migrationVersion) {
                    case 3 -> new V3__ResetForCanonicalization().apply(connection);
                    case 4 -> new V4__ItemFlowTopology().apply(connection);
                    case 5 -> new V5__ContainerFlowTopology().apply(connection);
                    default -> throw new AssertionError("unexpected migration version");
                }

                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT source_type, timestamp_ms, fingerprint_item_id,
                               fingerprint_hash, fingerprint_component_summary,
                               action_type, amount, raw_data
                        FROM ig_legacy_observation_evidence
                        WHERE archive_migration_version = ?
                        """)) {
                    query.setInt(1, migrationVersion);
                    try (ResultSet rows = query.executeQuery()) {
                        assertTrue(rows.next());
                        assertEquals("ITEMGRAPH_INTERNAL", rows.getString("source_type"));
                        assertEquals(migrationVersion * 1_000L, rows.getLong("timestamp_ms"));
                        assertEquals("minecraft:diamond", rows.getString("fingerprint_item_id"));
                        assertEquals("hash-" + migrationVersion, rows.getString("fingerprint_hash"));
                        assertEquals(componentSummary, rows.getString("fingerprint_component_summary"));
                        assertEquals("DROP_ITEM", rows.getString("action_type"));
                        assertEquals(2, rows.getInt("amount"));
                        assertArrayEquals(rawData, rows.getBytes("raw_data"));
                        assertFalse(rows.next());
                    }
                }

                try (Statement query = connection.createStatement();
                     ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1), "obsolete endpoint rows must leave the active ledger");
                }
            }

            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM ig_legacy_observation_evidence")) {
                assertTrue(rows.next());
                assertEquals(3, rows.getInt(1));
            }
        }
    }

    private static void insertObservation(Connection connection, int id, byte[] rawData,
                                          String componentSummary) throws Exception {
        try (PreparedStatement node = connection.prepareStatement(
                "INSERT INTO ig_nodes (id, node_type, level_id) VALUES (?, 'PLAYER', 'minecraft:overworld')")) {
            node.setInt(1, id);
            node.executeUpdate();
        }
        try (PreparedStatement fingerprint = connection.prepareStatement("""
                INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash, component_summary)
                VALUES (?, 'minecraft:diamond', ?, ?)
                """)) {
            fingerprint.setInt(1, id);
            fingerprint.setString(2, "hash-" + id);
            fingerprint.setString(3, componentSummary);
            fingerprint.executeUpdate();
        }
        try (PreparedStatement observation = connection.prepareStatement("""
                INSERT INTO ig_observations (id, source_type, source_event_id, timestamp_ms,
                    node_id, target_node_id, fingerprint_id, action_type, amount, raw_data)
                VALUES (?, 'ITEMGRAPH_INTERNAL', ?, ?, ?, NULL, ?, 'DROP_ITEM', 2, ?)
                """)) {
            observation.setInt(1, id);
            observation.setInt(2, id);
            observation.setLong(3, id * 1_000L);
            observation.setInt(4, id);
            observation.setInt(5, id);
            observation.setBytes(6, rawData);
            observation.executeUpdate();
        }
    }
}
