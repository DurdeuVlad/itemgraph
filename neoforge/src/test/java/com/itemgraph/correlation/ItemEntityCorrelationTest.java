package com.itemgraph.correlation;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ItemEntityCorrelationTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private Connection conn;
    private CorrelationEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        dbManager = DatabaseManager.getInstance();
        dbManager.initialize(tempDir.resolve("itemgraph.db"));
        conn = dbManager.getConnection();
        engine = new CorrelationEngine(dbManager, 300L); // 5 min window
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void testExactItemEntityUuidMatchProducesDirectContinuityConfidence() throws Exception {
        // Events comfortably older than 300s window so correlation finalizes
        long now = System.currentTimeMillis() - 400_000L;
        String entityUuid = UUID.randomUUID().toString();

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) VALUES " +
                    "(1, 'PLAYER', 'PlayerA', 'world', 0, 64, 0), " +
                    "(2, 'PLAYER', 'PlayerB', 'world', 2, 64, 2), " +
                    "(3, 'GROUND', '0,64,0', 'world', 0, 64, 0)");

            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) " +
                    "VALUES (1, 'minecraft:diamond_sword', 'hash-sword')");

            // Drop by Player A with entity UUID
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status, item_entity_uuid) " +
                    "VALUES (1, 'GRIEFLOGGER', " + (now - 10_000) + ", 1, 3, 1, 'DROP_ITEM', 1, 'PENDING', '" + entityUuid + "')");

            // Competing drop by Player C at same ground node (would normally penalize confidence)
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status, item_entity_uuid) " +
                    "VALUES (2, 'GRIEFLOGGER', " + (now - 8_000) + ", 1, 3, 1, 'DROP_ITEM', 1, 'PENDING', 'different-uuid')");

            // Pickup by Player B with matching entity UUID
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status, item_entity_uuid) " +
                    "VALUES (3, 'GRIEFLOGGER', " + now + ", 3, 2, 1, 'PICKUP_ITEM', 1, 'PENDING', '" + entityUuid + "')");
        }

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success());
        assertEquals(1, result.edgesCreated());

        // Verify edge confidence and explanation
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT confidence, explanation FROM ig_inferred_edges LIMIT 1");
             ResultSet rs = pstmt.executeQuery()) {
            assertTrue(rs.next());
            double confidence = rs.getDouble("confidence");
            String explanation = rs.getString("explanation");

            assertEquals(0.9990, confidence, 0.0001, "Exact ItemEntity UUID match must receive 0.9990 confidence");
            assertTrue(explanation.contains("Authoritative Minecraft ItemEntity UUID match"),
                    "Explanation must cite ItemEntity UUID match: " + explanation);
            assertTrue(explanation.contains(entityUuid));
        }
    }
}
