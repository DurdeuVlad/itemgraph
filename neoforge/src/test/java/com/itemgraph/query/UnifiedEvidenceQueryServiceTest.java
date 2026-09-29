package com.itemgraph.query;

import com.itemgraph.db.migration.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedEvidenceQueryServiceTest {
    private Connection conn;
    private UnifiedEvidenceQueryService service;
    private long playerNode;
    private long groundNode;
    private long stoneFingerprint;
    private long resultFingerprint;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        MigrationRunner.runMigrations(conn);
        service = new UnifiedEvidenceQueryService();
        playerNode = node("PLAYER", 10, 64, 10, "Alex", "uuid-alex");
        groundNode = node("GROUND", 10, 64, 10, null, null);
        stoneFingerprint = fingerprint("minecraft:stone", "hash-stone");
        resultFingerprint = fingerprint("minecraft:stone_bricks", "hash-bricks");
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void mergesAuditObservationTransformationAndImportedEvidenceInStableOrder() throws Exception {
        audit("BREAK_BLOCK", 1_000L, "minecraft:stone");
        observation("ITEMGRAPH_INTERNAL", 2_000L, "DROP_ITEM", stoneFingerprint, 3);
        transformation("CRAFT", 3_000L);
        observation("GRIEFLOGGER", 4_000L, "PICKUP_ITEM", stoneFingerprint, 3);

        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.break_block,drop_item,craft,pickup_item radius.100", 10_000L);
        List<UnifiedEvidenceDetail> rows = service.findFiltered(
                conn, filters, "minecraft:overworld", 10, 64, 10, 100, 0);

        assertEquals(4, rows.size());
        assertEquals(List.of("GRIEFLOGGER", "TRANSFORMATION", "OBSERVATION", "ITEMGRAPH_INTERNAL"),
                rows.stream().map(UnifiedEvidenceDetail::source).toList());
        assertEquals(List.of("PICKUP_ITEM", "CRAFT", "DROP_ITEM", "BREAK_BLOCK"),
                rows.stream().map(UnifiedEvidenceDetail::actionType).toList());
        assertEquals("observation#2", rows.get(0).evidenceId());
        assertEquals("OBSERVED", rows.get(0).evidenceClass());
    }

    @Test
    void appliesUserSubjectTimeRadiusAndGlobalPagingAcrossSources() throws Exception {
        audit("BREAK_BLOCK", 7_000L, "minecraft:stone");
        observation("ITEMGRAPH_INTERNAL", 6_000L, "DROP_ITEM", stoneFingerprint, 2);
        transformation("CRAFT", 5_000L);
        observation("ITEMGRAPH_INTERNAL", 4_000L, "DROP_ITEM", resultFingerprint, 1);

        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.break_block,drop_item,craft user.uuid-alex include.stone time.1h radius.5", 10_000L);
        List<UnifiedEvidenceDetail> firstPage = service.findFiltered(
                conn, filters, "minecraft:overworld", 10, 64, 10, 2, 0);
        List<UnifiedEvidenceDetail> secondPage = service.findFiltered(
                conn, filters, "minecraft:overworld", 10, 64, 10, 2, 2);

        assertEquals(List.of("audit#1", "observation#1"),
                firstPage.stream().map(UnifiedEvidenceDetail::evidenceId).toList());
        assertEquals(List.of("transformation#1"),
                secondPage.stream().map(UnifiedEvidenceDetail::evidenceId).toList());
        assertEquals("minecraft:stone", firstPage.get(1).subjectId());
    }

    @Test
    void excludeSubjectRemovesMatchingItemsAndPreservesImportedSource() throws Exception {
        observation("GRIEFLOGGER", 2_000L, "PICKUP_ITEM", stoneFingerprint, 3);
        observation("ITEMGRAPH_INTERNAL", 1_000L, "DROP_ITEM", resultFingerprint, 1);

        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.drop_item,pickup_item exclude.stone radius.100", 10_000L);
        List<UnifiedEvidenceDetail> rows = service.findFiltered(
                conn, filters, "minecraft:overworld", 10, 64, 10, 100, 0);

        assertEquals(1, rows.size());
        assertEquals("OBSERVATION", rows.get(0).source());
        assertEquals("minecraft:stone_bricks", rows.get(0).subjectId());
    }

    private long node(String type, double x, double y, double z, String label, String owner) throws Exception {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO ig_nodes (node_type, owner_uuid, level_id, x, y, z, custom_label)
                VALUES (?, ?, 'minecraft:overworld', ?, ?, ?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, type);
            statement.setString(2, owner);
            statement.setDouble(3, x);
            statement.setDouble(4, y);
            statement.setDouble(5, z);
            statement.setString(6, label);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long fingerprint(String item, String hash) throws Exception {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO ig_item_fingerprints (item_id, fingerprint_hash)
                VALUES (?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, item);
            statement.setString(2, hash);
            statement.executeUpdate();
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void audit(String action, long timestamp, String subject) throws Exception {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO ig_audit_events
                    (event_type, timestamp_ms, player_uuid, player_name, level_id, x, y, z, subject_id, source_type)
                VALUES (?, ?, 'uuid-alex', 'Alex', 'minecraft:overworld', 10, 64, 10, ?, 'ITEMGRAPH_INTERNAL')
                """)) {
            statement.setString(1, action);
            statement.setLong(2, timestamp);
            statement.setString(3, subject);
            statement.executeUpdate();
        }
    }

    private void observation(String source, long timestamp, String action, long fingerprint, int amount)
            throws Exception {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO ig_observations
                    (source_type, source_event_id, timestamp_ms, node_id, target_node_id,
                     fingerprint_id, action_type, amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, source);
            statement.setLong(2, timestamp);
            statement.setLong(3, timestamp);
            statement.setLong(4, playerNode);
            statement.setLong(5, groundNode);
            statement.setLong(6, fingerprint);
            statement.setString(7, action);
            statement.setInt(8, amount);
            statement.executeUpdate();
        }
    }

    private void transformation(String type, long timestamp) throws Exception {
        try (PreparedStatement statement = conn.prepareStatement("""
                INSERT INTO ig_item_transformations
                    (transformation_type, player_node_id, source_fingerprint_id,
                     result_fingerprint_id, quantity, timestamp_ms, details)
                VALUES (?, ?, ?, ?, 1, ?, 'fixture')
                """)) {
            statement.setString(1, type);
            statement.setLong(2, playerNode);
            statement.setLong(3, stoneFingerprint);
            statement.setLong(4, resultFingerprint);
            statement.setLong(5, timestamp);
            statement.executeUpdate();
        }
    }
}
