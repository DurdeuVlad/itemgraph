package com.itemgraph.db.migration;

import com.itemgraph.query.EventQueryService;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceQueryService;
import com.itemgraph.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V20UnverifiedArmorStandInteractionEvidenceTest {
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        MigrationRunner.runMigrations(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
    }

    @Test
    void retainsRawRowsButExcludesUnverifiedClicksFromCurrentFlow() throws Exception {
        simulateVersion19Schema();
        long fingerprintId = insertFingerprint();
        long playerId = insertNode("PLAYER", "uuid-player", null);
        long standId = insertNode("ARMOR_STAND", null, "minecraft:overworld");
        long observationId = insertLegacyObservation(playerId, standId, fingerprintId);
        long edgeId = insertInferredEdge(playerId, standId, fingerprintId);
        insertAllocation(edgeId, observationId);

        assertEquals(20, MigrationRunner.runMigrations(connection),
                "an existing v19 database should receive the quarantine migration");
        V20__UnverifiedArmorStandInteractionEvidence migration =
                new V20__UnverifiedArmorStandInteractionEvidence();
        migration.apply(connection);
        migration.apply(connection);

        try (PreparedStatement query = connection.prepareStatement("""
                SELECT source_type, action_type, amount, raw_data, correlation_status
                FROM ig_observations WHERE id = ?
                """)) {
            query.setLong(1, observationId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals("ITEMGRAPH_INTERNAL", row.getString("source_type"));
                assertEquals("EQUIP_ARMOR_STAND", row.getString("action_type"));
                assertEquals(1, row.getInt("amount"));
                assertTrue(Arrays.equals(new byte[]{1, 2, 3}, row.getBytes("raw_data")));
                assertEquals("CLOSED_UNRESOLVED", row.getString("correlation_status"));
            }
        }
        assertEquals(1, scalar("SELECT COUNT(*) FROM ig_observation_dispositions WHERE observation_id = "
                + observationId));
        assertEquals("SUPERSEDED_UNVERIFIED_EVIDENCE", stringValue(
                "SELECT edge_state FROM ig_inferred_edges WHERE id = " + edgeId));
        assertTrue(new AuditService().audit(connection).healthy(),
                "quarantining legacy rows must leave /itemgraph audit healthy");

        var detail = new EventQueryService().findObservation(connection, observationId).orElseThrow();
        assertEquals("UNRESOLVED", detail.kindLabel());
        assertEquals(V20__UnverifiedArmorStandInteractionEvidence.REASON_CODE, detail.dispositionReason());
        var formatted = String.join("\n", QueryFormatter.formatEvent(detail));
        assertTrue(formatted.contains("excluded from current item flow"));
        assertTrue(formatted.contains("historical callback amount is not proof of a transfer"));

        var trace = new TraceQueryService().trace(
                connection, fingerprintId, 100, QueryWindow.unbounded());
        assertEquals(0, trace.observedCount());
        assertEquals(0, trace.inferredCount());
        assertFalse(trace.hops().stream().anyMatch(hop -> hop.refId() == observationId || hop.refId() == edgeId));
    }

    private void simulateVersion19Schema() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE ig_observation_dispositions");
            statement.execute("DELETE FROM ig_schema_migrations WHERE version = 20");
        }
    }

    private long insertFingerprint() throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ig_item_fingerprints (item_id, fingerprint_hash) "
                    + "VALUES ('minecraft:iron_helmet', 'legacy-armor-stand-test')");
            try (ResultSet row = statement.executeQuery("SELECT last_insert_rowid()")) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long insertNode(String type, String ownerUuid, String levelId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ig_nodes (node_type, owner_uuid, level_id) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, type);
            statement.setString(2, ownerUuid);
            statement.setString(3, levelId == null ? "minecraft:overworld" : levelId);
            statement.executeUpdate();
            try (Statement key = connection.createStatement();
                 ResultSet row = key.executeQuery("SELECT last_insert_rowid()")) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long insertLegacyObservation(long playerId, long standId, long fingerprintId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id,
                    fingerprint_id, action_type, amount, raw_data)
                VALUES ('ITEMGRAPH_INTERNAL', 1000, ?, ?, ?, 'EQUIP_ARMOR_STAND', 1, X'010203')
                """)) {
            statement.setLong(1, playerId);
            statement.setLong(2, standId);
            statement.setLong(3, fingerprintId);
            statement.executeUpdate();
            try (Statement key = connection.createStatement();
                 ResultSet row = key.executeQuery("SELECT last_insert_rowid()")) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private long insertInferredEdge(long playerId, long standId, long fingerprintId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ig_inferred_edges (from_node_id, to_node_id, fingerprint_id, amount,
                    time_start, time_end, confidence, explanation, created_at)
                VALUES (?, ?, ?, 1, 1000, 1000, 0.5, 'legacy unverified edge', 1000)
                """)) {
            statement.setLong(1, playerId);
            statement.setLong(2, standId);
            statement.setLong(3, fingerprintId);
            statement.executeUpdate();
            try (Statement key = connection.createStatement();
                 ResultSet row = key.executeQuery("SELECT last_insert_rowid()")) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private void insertAllocation(long edgeId, long observationId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount)
                VALUES (?, ?, 'SOURCE', 1)
                """)) {
            statement.setLong(1, edgeId);
            statement.setLong(2, observationId);
            statement.executeUpdate();
        }
    }

    private long scalar(String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private String stringValue(String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }
}
