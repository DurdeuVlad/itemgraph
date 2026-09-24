package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link V9__InternalObservationDedup} (0.2.0 — Issue 2).
 *
 * <p>SQLite treats {@code NULL != NULL}, so the pre-V9 unique index on
 * {@code (source_type, source_event_id)} could never deduplicate
 * {@code ITEMGRAPH_INTERNAL} rows (their {@code source_event_id} is always NULL).
 * V9 adds a partial unique index on the logical identity of an internal
 * observation — {@code (source_type, timestamp_ms, node_id, fingerprint_id,
 * amount, action_type)} — scoped to {@code WHERE source_event_id IS NULL}.
 *
 * <p>V10 extends that key with {@code item_entity_uuid}: entity-tracked events
 * (drops, pickups, death drops) deduplicate on true re-submission while distinct
 * entities sharing every other column stay separate rows. These tests prove the
 * index exists, that uuid-bearing duplicate writes are idempotently discarded,
 * and that external-source rows are unaffected.
 */
class V9InternalObservationDedupTest {

    @TempDir
    Path tempDir;

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager dbManager = DatabaseManager.getInstance();
        dbManager.initialize(tempDir.resolve("itemgraph.db"));
        conn = dbManager.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (1, 'PLAYER', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (2, 'GROUND', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:diamond', 'hash123')");
        }
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.getInstance().close();
    }

    @Test
    void partialUniqueIndexIsCreatedByMigration() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE name = 'idx_obs_internal_dedup'")) {
            assertTrue(rs.next(), "idx_obs_internal_dedup must exist after V9 runs");
            String sql = rs.getString("sql");
            assertTrue(sql.contains("WHERE") && sql.contains("source_event_id IS NULL"),
                    "index must be partial, scoped to internal rows only: " + sql);
        }
    }

    @Test
    void duplicateInternalObservationIsDiscardedIdempotently() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Same real event re-submitted: same entity uuid, same identity columns.
            stmt.execute(internalInsert(1000, "DROP_ITEM", 5, "entity-aaa"));

            // Plain INSERT of an identical internal row must hit the unique index.
            assertThrows(SQLException.class, () -> stmt.execute(internalInsert(1000, "DROP_ITEM", 5, "entity-aaa")),
                    "duplicate internal observation must violate idx_obs_internal_dedup");

            // INSERT OR IGNORE (the path InternalObservationService.persistBatch uses)
            // must swallow the duplicate without throwing.
            int affected = stmt.executeUpdate(internalOrIgnoreInsert(1000, "DROP_ITEM", 5, "entity-aaa"));
            assertEquals(0, affected, "duplicate internal observation must be ignored, not stored");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ig_observations WHERE source_type = 'ITEMGRAPH_INTERNAL'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "the duplicate must not create a second row");
            }
        }
    }

    @Test
    void distinctInternalObservationsAreNotConflated() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Same identity except action_type — a drop and a pickup are different events.
            stmt.execute(internalInsert(1000, "DROP_ITEM", 5, "entity-aaa"));
            stmt.execute(internalInsert(1000, "PICKUP_ITEM", 5, "entity-aaa"));
            // Same identity except timestamp — the same event at a later time is new evidence.
            stmt.execute(internalInsert(2000, "DROP_ITEM", 5, "entity-aaa"));
            // Same identity except amount — a partial quantity moved is a different event.
            stmt.execute(internalInsert(1000, "DROP_ITEM", 3, "entity-aaa"));
            // Same identity except entity uuid — two distinct item entities.
            stmt.execute(internalInsert(1000, "DROP_ITEM", 5, "entity-bbb"));

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ig_observations WHERE source_type = 'ITEMGRAPH_INTERNAL'")) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1),
                        "observations differing in any identity column must all be stored");
            }
        }
    }

    @Test
    void externalSourceRowsAreUnaffectedByPartialIndex() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Two GRIEFLOGGER rows sharing every dedup-key column except source_event_id.
            // The partial index does not cover them (source_event_id IS NOT NULL), so both
            // are legitimate external evidence and must persist.
            stmt.execute("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) " +
                    "VALUES ('GRIEFLOGGER', 100, 1000, 1, 1, 'DROP_ITEM', 5)");
            stmt.execute("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) " +
                    "VALUES ('GRIEFLOGGER', 200, 1000, 1, 1, 'DROP_ITEM', 5)");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ig_observations WHERE source_type = 'GRIEFLOGGER'")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1), "external rows must not be deduplicated by the internal index");
            }
        }
    }

    @Test
    void migrationApplyIsIdempotent() throws Exception {
        // Re-applying V9 on an already-migrated database must not throw
        // (CREATE INDEX IF NOT EXISTS / CREATE UNIQUE INDEX IF NOT EXISTS).
        V9__InternalObservationDedup migration = new V9__InternalObservationDedup();
        assertEquals(9, migration.getVersion());
        assertDoesNotThrow(() -> migration.apply(conn));
        assertDoesNotThrow(() -> migration.apply(conn));
    }

    private static String internalInsert(long timestampMs, String actionType, int amount, String entityUuid) {
        String uuid = entityUuid == null ? "NULL" : "'" + entityUuid + "'";
        return "INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount, item_entity_uuid) " +
                "VALUES ('ITEMGRAPH_INTERNAL', NULL, " + timestampMs + ", 1, 1, '" + actionType + "', " + amount + ", " + uuid + ")";
    }

    private static String internalOrIgnoreInsert(long timestampMs, String actionType, int amount, String entityUuid) {
        String uuid = entityUuid == null ? "NULL" : "'" + entityUuid + "'";
        return "INSERT OR IGNORE INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount, item_entity_uuid) " +
                "VALUES ('ITEMGRAPH_INTERNAL', NULL, " + timestampMs + ", 1, 1, '" + actionType + "', " + amount + ", " + uuid + ")";
    }
}
