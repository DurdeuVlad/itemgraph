package com.itemgraph.audit;

import com.itemgraph.db.migration.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    private Connection conn;
    private AuditService auditService;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        MigrationRunner.runMigrations(conn);
        auditService = new AuditService();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    @Test
    void testEmptyDatabaseIsHealthy() throws Exception {
        AuditReport report = auditService.audit(conn);
        assertTrue(report.healthy());
        assertEquals(0, report.totalObservations());
        assertEquals(0, report.totalEdges());
        assertEquals(0, report.overAllocatedObservations());
        assertEquals(0, report.nonPositiveQuantities());
        assertEquals(0, report.orphanedAllocations());
    }

    @Test
    void testValidGraphIsHealthy() throws Exception {
        // Seed valid node, fingerprint, observation, edge, and allocation
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id) VALUES (1, 'PLAYER', 'PlayerA', 'minecraft:overworld'), (2, 'GROUND', '0,64,0', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:diamond', 'hash1')");
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status) " +
                    "VALUES (1, 'TEST', 1000, 1, 2, 1, 'DROP_ITEM', 10, 'FULLY_ALLOCATED')");
            stmt.execute("INSERT INTO ig_inferred_edges (id, from_node_id, to_node_id, fingerprint_id, amount, time_start, time_end, confidence, explanation, created_at) " +
                    "VALUES (1, 1, 1, 1, 10, 1000, 2000, 0.9, 'test', 2000)");
            stmt.execute("INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount) " +
                    "VALUES (1, 1, 'DROP', 10)");
        }

        AuditReport report = auditService.audit(conn);
        assertTrue(report.healthy());
        assertEquals(1, report.totalObservations());
        assertEquals(1, report.totalEdges());
        assertEquals(1, report.totalAllocations());
        assertEquals(0, report.overAllocatedObservations());
    }

    @Test
    void testDetectsConservationOverAllocation() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id) VALUES (1, 'PLAYER', 'PlayerA', 'minecraft:overworld'), (2, 'GROUND', '0,64,0', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:diamond', 'hash1')");
            // Obs capacity = 10
            stmt.execute("INSERT INTO ig_observations (id, source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, correlation_status) " +
                    "VALUES (1, 'TEST', 1000, 1, 2, 1, 'DROP_ITEM', 10, 'FULLY_ALLOCATED')");
            // Edge 1 allocates 8, Edge 2 allocates 8 => total allocated = 16 > 10 (violation!)
            stmt.execute("INSERT INTO ig_inferred_edges (id, from_node_id, to_node_id, fingerprint_id, amount, time_start, time_end, confidence, explanation, created_at) " +
                    "VALUES (1, 1, 1, 1, 8, 1000, 2000, 0.9, 'test1', 2000), (2, 1, 1, 1, 8, 1000, 2000, 0.9, 'test2', 2000)");
            stmt.execute("INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount) " +
                    "VALUES (1, 1, 'DROP', 8), (2, 1, 'DROP', 8)");
        }

        AuditReport report = auditService.audit(conn);
        assertFalse(report.healthy());
        assertEquals(1, report.overAllocatedObservations());
        assertTrue(report.violationDetails().get(0).contains("Conservation violation on obs#1"));
    }

    @Test
    void testDetectsOrphanedAllocations() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, custom_label, level_id) VALUES (1, 'PLAYER', 'PlayerA', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:diamond', 'hash1')");
            stmt.execute("INSERT INTO ig_inferred_edges (id, from_node_id, to_node_id, fingerprint_id, amount, time_start, time_end, confidence, explanation, created_at) " +
                    "VALUES (1, 1, 1, 1, 5, 1000, 2000, 0.9, 'test', 2000)");
            // Allocation references non-existent observation_id 999
            stmt.execute("INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount) " +
                    "VALUES (1, 999, 'DROP', 5)");
        }

        AuditReport report = auditService.audit(conn);
        assertFalse(report.healthy());
        assertEquals(1, report.orphanedAllocations());
    }

    @Test
    void testDetectsInvalidEdgeNodes() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:diamond', 'hash1')");
            // Edge references non-existent node 888 and 999
            stmt.execute("INSERT INTO ig_inferred_edges (id, from_node_id, to_node_id, fingerprint_id, amount, time_start, time_end, confidence, explanation, created_at) " +
                    "VALUES (1, 888, 999, 1, 5, 1000, 2000, 0.9, 'test', 2000)");
        }

        AuditReport report = auditService.audit(conn);
        assertFalse(report.healthy());
        assertEquals(1, report.invalidEdgeNodes());
    }
}
