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
 * Unit tests for {@link V10__InternalDedupEntityUuid} (0.2.0 — Issue 2 follow-up).
 *
 * <p>V9's dedup key {@code (source_type, timestamp_ms, node_id, fingerprint_id,
 * amount, action_type)} had no per-event discriminator, so distinct real events
 * collapsed into one row: two identical stacks dropped by the same death batch,
 * identical pile pickups in one millisecond, identical same-ms machine transfers.
 * V10 adds {@code item_entity_uuid} to the index. Rows carrying an entity uuid
 * still deduplicate on re-submission; distinct entities and NULL-uuid container
 * rows (NULLs are always distinct in SQLite unique indexes) are preserved.
 */
class V10InternalDedupEntityUuidTest {

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
            stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (3, 'PLAYER', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:cobblestone', 'hash-cobble')");
        }
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.getInstance().close();
    }

    @Test
    void indexIncludesItemEntityUuidAndDestinationAfterV11() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE name = 'idx_obs_internal_dedup'")) {
            assertTrue(rs.next(), "idx_obs_internal_dedup must exist after migrations");
            String sql = rs.getString("sql");
            assertTrue(sql.contains("item_entity_uuid"),
                    "the dedup key must include item_entity_uuid: " + sql);
            assertTrue(sql.contains("target_node_id"),
                    "V11 must distinguish item transfers to different recipients: " + sql);
            assertTrue(sql.contains("source_event_id IS NULL"),
                    "index must stay partial, scoped to internal rows: " + sql);
        }
    }

    @Test
    void identicalEntityUuidRowsStillDeduplicate() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(internalInsert(1000, "DROP_ITEM", 64, "entity-aaa"));

            // A true re-submission of the same event (same entity uuid) dedups.
            int affected = stmt.executeUpdate(internalOrIgnoreInsert(1000, "DROP_ITEM", 64, "entity-aaa"));
            assertEquals(0, affected, "same entity uuid + same identity must be ignored");
            assertEquals(1, internalRowCount(), "re-submitted identical event must not create a second row");
        }
    }

    @Test
    void identicalDeathDropStacksWithDistinctEntitiesSurvive() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Player dies holding two identical cobblestone stacks: one timestamp for
            // the whole LivingDropsEvent batch, same node/fingerprint/amount/action —
            // but two different ItemEntity uuids. Both must persist.
            stmt.execute(internalInsert(1000, "DEATH_DROP", 64, "entity-aaa"));
            stmt.execute(internalInsert(1000, "DEATH_DROP", 64, "entity-bbb"));

            assertEquals(2, internalRowCount(),
                    "distinct item entities must not collapse under the dedup index");
        }
    }

    @Test
    void nullUuidContainerRowsAreNotConflated() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            // Two identical same-millisecond machine inserts into the same container:
            // separate real events (no entity uuid), both must be stored.
            stmt.execute(internalInsert(5000, "CAPABILITY_INSERT", 1, null));
            stmt.execute(internalInsert(5000, "CAPABILITY_INSERT", 1, null));

            assertEquals(2, internalRowCount(),
                    "NULL-uuid rows are distinct real events, not duplicates");
        }
    }

    @Test
    void sameEntityUuidPickupsForDifferentPlayersBothPersist() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT OR IGNORE INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) "
                    + "VALUES ('ITEMGRAPH_INTERNAL', NULL, 1000, 2, 1, 1, 'PICKUP_ITEM', 1, 'entity-shared')");
            stmt.execute("INSERT OR IGNORE INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) "
                    + "VALUES ('ITEMGRAPH_INTERNAL', NULL, 1000, 2, 3, 1, 'PICKUP_ITEM', 1, 'entity-shared')");
        }

        assertEquals(2, internalRowCount(), "different pickup recipients are distinct events for one entity UUID");
    }

    @Test
    void migrationApplyIsIdempotent() throws Exception {
        V10__InternalDedupEntityUuid v10 = new V10__InternalDedupEntityUuid();
        V11__ObservationSourceGroupsAndIntervals v11 = new V11__ObservationSourceGroupsAndIntervals();
        assertEquals(10, v10.getVersion());
        assertEquals(11, v11.getVersion());
        assertDoesNotThrow(() -> v10.apply(conn));
        assertDoesNotThrow(() -> v10.apply(conn));
        assertDoesNotThrow(() -> v11.apply(conn));
        assertDoesNotThrow(() -> v11.apply(conn));
    }

    private int internalRowCount() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM ig_observations WHERE source_type = 'ITEMGRAPH_INTERNAL'")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static String internalInsert(long timestampMs, String actionType, int amount, String entityUuid) {
        String uuid = entityUuid == null ? "NULL" : "'" + entityUuid + "'";
        return "INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) " +
                "VALUES ('ITEMGRAPH_INTERNAL', NULL, " + timestampMs + ", 1, 2, 1, '" + actionType + "', " + amount + ", " + uuid + ")";
    }

    private static String internalOrIgnoreInsert(long timestampMs, String actionType, int amount, String entityUuid) {
        String uuid = entityUuid == null ? "NULL" : "'" + entityUuid + "'";
        return "INSERT OR IGNORE INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) " +
                "VALUES ('ITEMGRAPH_INTERNAL', NULL, " + timestampMs + ", 1, 2, 1, '" + actionType + "', " + amount + ", " + uuid + ")";
    }
}
