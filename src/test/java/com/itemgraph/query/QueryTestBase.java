package com.itemgraph.query;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared temp-database fixture for the Phase 6 query services.
 *
 * <p>Same setup as {@code CorrelationEngineTest} and {@code NodeManagerTest}: a real
 * SQLite file in a {@code @TempDir} with the real migrations run against it. The query
 * services are SQL, so testing them against anything other than the actual schema would
 * only prove that a mock agrees with itself — a projection that silently drops a row on
 * a LEFT-vs-INNER join mistake is exactly the class of bug these tests exist to catch.
 *
 * <p>Rows are seeded directly rather than through ingestion. These services are defined
 * over table contents, and hand-built rows are what lets a test state "this edge cites
 * these two observations and nothing else" precisely — including the malformed shapes
 * (dangling node ids, edges with no evidence) that ingestion would never produce but a
 * forensic tool still has to render honestly.
 */
abstract class QueryTestBase {

    @TempDir
    Path tempDir;

    DatabaseManager dbManager;
    Connection conn;
    long now;

    private long sourceEventSeq = 1;

    @BeforeEach
    void setUpDatabase() throws Exception {
        dbManager = DatabaseManager.getInstance();
        dbManager.initialize(tempDir.resolve("itemgraph.db"));
        conn = dbManager.getConnection();
        now = System.currentTimeMillis();
    }

    @AfterEach
    void tearDownDatabase() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    long insertPlayerNode(String label) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, custom_label) "
                        + "VALUES ('PLAYER', ?, 'minecraft:overworld', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, "uuid-" + label);
            pstmt.setString(2, label);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    long insertNodeAt(String nodeType, int x, int y, int z) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_nodes (node_type, level_id, x, y, z) "
                        + "VALUES (?, 'minecraft:overworld', ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, nodeType);
            pstmt.setInt(2, x);
            pstmt.setInt(3, y);
            pstmt.setInt(4, z);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    long insertGroundNode(int x, int y, int z) throws SQLException {
        return insertNodeAt("GROUND", x, y, z);
    }

    long insertContainerNode(int x, int y, int z) throws SQLException {
        return insertNodeAt("CONTAINER", x, y, z);
    }

    long insertFingerprint(String itemId, String hash) throws SQLException {
        return insertFingerprint(itemId, hash, null);
    }

    long insertFingerprint(String itemId, String hash, String customName) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_item_fingerprints (item_id, fingerprint_hash, custom_name) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, itemId);
            pstmt.setString(2, hash);
            pstmt.setString(3, customName);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    long insertObservation(long timestampMs, long nodeId, Long targetNodeId, long fingerprintId,
                           String actionType, int amount) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id,
                                             target_node_id, fingerprint_id, action_type, amount)
                VALUES ('GRIEFLOGGER', ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, sourceEventSeq++);
            pstmt.setLong(2, timestampMs);
            pstmt.setLong(3, nodeId);
            if (targetNodeId != null) {
                pstmt.setLong(4, targetNodeId);
            } else {
                pstmt.setNull(4, Types.INTEGER);
            }
            pstmt.setLong(5, fingerprintId);
            pstmt.setString(6, actionType);
            pstmt.setInt(7, amount);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    long insertEdge(long fromNodeId, long toNodeId, long fingerprintId, int amount,
                    long timeStart, long timeEnd, double confidence, String explanation) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_inferred_edges (from_node_id, to_node_id, fingerprint_id, amount,
                                               time_start, time_end, confidence, explanation, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, fromNodeId);
            pstmt.setLong(2, toNodeId);
            pstmt.setLong(3, fingerprintId);
            pstmt.setInt(4, amount);
            pstmt.setLong(5, timeStart);
            pstmt.setLong(6, timeEnd);
            pstmt.setDouble(7, confidence);
            pstmt.setString(8, explanation);
            pstmt.setLong(9, timeEnd);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    void linkEvidence(long edgeId, long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_edge_evidence (edge_id, observation_id) VALUES (?, ?)")) {
            pstmt.setLong(1, edgeId);
            pstmt.setLong(2, observationId);
            pstmt.executeUpdate();
        }
    }

    void markCorrelated(long observationId, long correlatedAtMs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlated_at = ? WHERE id = ?")) {
            pstmt.setLong(1, correlatedAtMs);
            pstmt.setLong(2, observationId);
            pstmt.executeUpdate();
        }
    }

    static long generatedKey(PreparedStatement pstmt) throws SQLException {
        try (ResultSet keys = pstmt.getGeneratedKeys()) {
            assertTrue(keys.next(), "insert must yield a generated key");
            return keys.getLong(1);
        }
    }
}
