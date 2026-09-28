package com.itemgraph.query;

import com.itemgraph.db.migration.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlayerAndContainerTraceTest {

    private Connection conn;
    private TraceQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        MigrationRunner.runMigrations(conn);
        service = new TraceQueryService();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    void testTracePlayerReturnsChronologicalMovement() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) " +
                    "VALUES (1, 'PLAYER', 'vlad', 'minecraft:overworld', 0, 64, 0)");
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) " +
                    "VALUES (2, 'CONTAINER', 'chest', 'minecraft:overworld', 10, 64, 10)");
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) " +
                    "VALUES (3, 'GROUND', '0,64,0', 'minecraft:overworld', 0, 64, 0)");

            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, custom_name, fingerprint_hash) " +
                    "VALUES (1, 'minecraft:diamond_sword', 'Excalibur', 'hash1')");

            // Event 1: Withdraw from chest at t=1000
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status) " +
                    "VALUES (1, 'GRIEFLOGGER', 1000, 2, 1, 1, 'CONTAINER_WITHDRAW', 1, 'PENDING')");
            // Event 2: Drop on ground at t=2000
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status) " +
                    "VALUES (2, 'GRIEFLOGGER', 2000, 1, 3, 1, 'DROP_ITEM', 1, 'PENDING')");
        }

        TraceResult result = service.tracePlayer(conn, "vlad", 50, QueryWindow.unbounded());

        assertEquals("player vlad", result.targetDescription());
        assertEquals(2, result.hops().size());
        assertEquals(1, result.hops().get(0).refId());
        assertEquals(2, result.hops().get(1).refId());
        assertNotNull(result.hops().get(0).item());
        assertEquals("Excalibur", result.hops().get(0).item().customName());

        List<String> lines = QueryFormatter.formatTrace(result);
        String formatted = String.join("\n", lines);
        assertTrue(formatted.contains("TRACE player vlad"));
        assertTrue(formatted.contains("Excalibur"));
    }

    @Test
    void testTraceContainerReturnsEventsAtCoordinates() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) " +
                    "VALUES (1, 'PLAYER', 'vlad', 'minecraft:overworld', 0, 64, 0)");
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) " +
                    "VALUES (2, 'CONTAINER', 'chest', 'minecraft:overworld', 100.0, 64.0, -200.0)");

            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) " +
                    "VALUES (1, 'minecraft:gold_ingot', 'hash-gold')");

            // Event: Deposit into container at t=5000
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status) " +
                    "VALUES (1, 'GRIEFLOGGER', 5000, 1, 2, 1, 'CONTAINER_DEPOSIT', 32, 'PENDING')");
        }

        TraceResult result = service.traceContainer(conn, null, 100, 64, -200, 50, QueryWindow.unbounded());

        assertNotNull(result);
        assertEquals(1, result.hops().size());
        assertEquals(32, result.hops().get(0).amount());
        assertTrue(result.targetDescription().contains("chest"));
    }

    @Test
    void testResolveFingerprints() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, custom_name, fingerprint_hash) " +
                    "VALUES (10, 'minecraft:diamond_sword', 'Excalibur', 'hash-excal')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, custom_name, fingerprint_hash) " +
                    "VALUES (20, 'minecraft:iron_ingot', NULL, 'hash-iron')");
        }

        // Exact ID lookup
        List<FingerprintRef> byId = service.resolveFingerprints(conn, "10");
        assertEquals(1, byId.size());
        assertEquals(10L, byId.get(0).id());

        // Substring registry ID lookup
        List<FingerprintRef> byItem = service.resolveFingerprints(conn, "diamond");
        assertEquals(1, byItem.size());
        assertEquals(10L, byItem.get(0).id());

        // Custom name lookup
        List<FingerprintRef> byName = service.resolveFingerprints(conn, "Excalibur");
        assertEquals(1, byName.size());
        assertEquals("Excalibur", byName.get(0).customName());

        // No match
        List<FingerprintRef> none = service.resolveFingerprints(conn, "netherite");
        assertTrue(none.isEmpty());
    }

    @Test
    void testTraceItemSurfacesTransformations() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id, x, y, z) " +
                    "VALUES (1, 'PLAYER', 'vlad', 'minecraft:overworld', 0, 64, 0)");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, custom_name, fingerprint_hash) " +
                    "VALUES (1, 'minecraft:diamond_sword', NULL, 'hash-plain')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, custom_name, fingerprint_hash) " +
                    "VALUES (2, 'minecraft:diamond_sword', 'Excalibur', 'hash-named')");

            // Transformation: renamed at t=1500
            stmt.execute("INSERT INTO ig_item_transformations (transformation_type, player_node_id, source_fingerprint_id, result_fingerprint_id, quantity, timestamp_ms, details) " +
                    "VALUES ('ANVIL_RENAME', 1, 1, 2, 1, 1500, 'Renamed Diamond Sword -> Excalibur')");
        }

        // Trace source item
        TraceResult sourceTrace = service.trace(conn, 1, 50, QueryWindow.unbounded());
        assertEquals(1, sourceTrace.hops().size());
        assertTrue(sourceTrace.hops().get(0).detail().contains("TRANSFORMATION ANVIL_RENAME"));
        assertTrue(sourceTrace.hops().get(0).detail().contains("Excalibur"));

        // Trace result item
        TraceResult resultTrace = service.trace(conn, 2, 50, QueryWindow.unbounded());
        assertEquals(1, resultTrace.hops().size());
        assertTrue(resultTrace.hops().get(0).detail().contains("TRANSFORMATION ANVIL_RENAME"));
        assertTrue(resultTrace.hops().get(0).detail().contains("minecraft:diamond_sword"));
    }
}
