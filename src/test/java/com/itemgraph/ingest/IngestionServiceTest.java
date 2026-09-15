package com.itemgraph.ingest;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class IngestionServiceTest {

    @TempDir
    Path tempDir;

    private Path griefLoggerDbPath;
    private Path itemgraphDbPath;
    private DatabaseManager dbManager;
    private GriefLoggerAdapter adapter;
    private IngestionService ingestionService;

    @BeforeEach
    void setUp() throws Exception {
        griefLoggerDbPath = tempDir.resolve("grieflogger.db");
        itemgraphDbPath = tempDir.resolve("itemgraph.db");

        // Set up GriefLogger DB
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + griefLoggerDbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE materials (id integer PRIMARY KEY, name text NOT NULL UNIQUE);");
            stmt.execute("CREATE TABLE users (id integer PRIMARY KEY, name text NOT NULL, uuid text DEFAULT NULL UNIQUE);");
            stmt.execute("CREATE TABLE levels (id integer PRIMARY KEY, name text NOT NULL UNIQUE);");
            stmt.execute("""
                CREATE TABLE items (
                    time integer NOT NULL,
                    user integer NOT NULL,
                    level integer NOT NULL,
                    x integer NOT NULL,
                    y integer NOT NULL,
                    z integer NOT NULL,
                    type integer NOT NULL,
                    data blob DEFAULT NULL,
                    amount integer NOT NULL,
                    action integer NOT NULL,
                    FOREIGN KEY(user) REFERENCES users(id),
                    FOREIGN KEY(level) REFERENCES levels(id),
                    FOREIGN KEY(type) REFERENCES materials(id)
                );
            """);
            stmt.execute("""
                CREATE TABLE containers (
                    time integer NOT NULL,
                    user integer NOT NULL,
                    level integer NOT NULL,
                    x integer NOT NULL,
                    y integer NOT NULL,
                    z integer NOT NULL,
                    type integer NOT NULL,
                    data blob DEFAULT NULL,
                    amount integer NOT NULL,
                    action integer NOT NULL,
                    FOREIGN KEY(user) REFERENCES users(id),
                    FOREIGN KEY(level) REFERENCES levels(id),
                    FOREIGN KEY(type) REFERENCES materials(id)
                );
            """);

            stmt.execute("INSERT INTO users (id, name, uuid) VALUES (1, 'zKampeR', 'f0b5d9d8-2cac-3599-aa08-a61237f4e827');");
            stmt.execute("INSERT INTO levels (id, name) VALUES (1, 'minecraft:overworld');");
            stmt.execute("INSERT INTO materials (id, name) VALUES (1, 'diamond_ore');");
            stmt.execute("INSERT INTO materials (id, name) VALUES (2, 'farmersdelight:smoked_ham');");

            // Seed item row (zKampeR diamond_ore drop)
            stmt.execute("INSERT INTO items (time, user, level, x, y, z, type, amount, action) " +
                    "VALUES (1789330407684, 1, 1, 1646, 78, -1475, 1, 1, 2);");
        }

        // Set up ItemGraph DB
        dbManager = DatabaseManager.getInstance();
        dbManager.initialize(itemgraphDbPath);

        adapter = new GriefLoggerAdapter(griefLoggerDbPath);
        ingestionService = new IngestionService(adapter, dbManager);
    }

    @AfterEach
    void tearDown() {
        if (ingestionService != null) {
            ingestionService.stop();
        }
        if (dbManager != null) {
            dbManager.close();
        }
    }

    @Test
    void testEndToEndIngestion() throws Exception {
        IngestionResult result = ingestionService.runIngestion();

        assertTrue(result.success());
        assertEquals(1, result.itemsIngested());
        assertEquals(0, result.containersIngested());
        assertNull(result.errorMessage());

        // Verify observation
        Connection conn = dbManager.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ig_observations")) {
            assertTrue(rs.next());
            assertEquals("GRIEFLOGGER", rs.getString("source_type"));
            assertEquals(1, rs.getLong("source_event_id"));
            assertEquals(1789330407684L, rs.getLong("timestamp_ms"));
            assertEquals("DROP_ITEM", rs.getString("action_type"));
            assertEquals(1, rs.getInt("amount"));
            assertFalse(rs.next());
        }

        // Verify player node
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ig_nodes WHERE node_type = 'PLAYER'")) {
            assertTrue(rs.next());
            assertEquals("f0b5d9d8-2cac-3599-aa08-a61237f4e827", rs.getString("owner_uuid"));
            assertEquals("zKampeR", rs.getString("custom_label"));
            assertEquals("minecraft:overworld", rs.getString("level_id"));
            assertEquals(1646, rs.getDouble("x"));
            assertEquals(78, rs.getDouble("y"));
            assertEquals(-1475, rs.getDouble("z"));
        }

        // Verify fingerprint normalized to minecraft:diamond_ore, canonicalized
        // via ItemCanonicalizer (Phase 3) rather than Phase 2's bare-id hash
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ig_item_fingerprints")) {
            assertTrue(rs.next());
            assertEquals("minecraft:diamond_ore", rs.getString("item_id"));
            var expected = com.itemgraph.canon.ItemCanonicalizer.canonicalize("minecraft:diamond_ore", null);
            assertEquals(expected.fingerprintHash(), rs.getString("fingerprint_hash"));
            assertNull(rs.getString("custom_name"));
            assertNull(rs.getString("component_summary"));
        }

        // Verify checkpoint
        SourceCheckpoint cp = ingestionService.getCheckpoint(conn, IngestionService.SOURCE_ITEMS);
        assertEquals(1, cp.lastSourceRowid());
        assertEquals(1789330407684L, cp.lastTimestamp());

        // Subsequent ingestion run should ingest 0 new rows
        IngestionResult secondResult = ingestionService.runIngestion();
        assertTrue(secondResult.success());
        assertEquals(0, secondResult.itemsIngested());
        assertEquals(1, ingestionService.getTotalObservationsCount());
    }

    @Test
    void testDeduplicationWhenCheckpointRewound() throws Exception {
        ingestionService.runIngestion();
        assertEquals(1, ingestionService.getTotalObservationsCount());

        // Rewind checkpoint to 0
        Connection conn = dbManager.getConnection();
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE ig_source_checkpoints SET last_source_rowid = 0 WHERE source_name = '" + IngestionService.SOURCE_ITEMS + "'");
        }

        // Run ingestion again
        IngestionResult rerunResult = ingestionService.runIngestion();
        assertTrue(rerunResult.success());
        // Deduplication prevents double insert
        assertEquals(1, ingestionService.getTotalObservationsCount());
    }

    @Test
    void testContainersIngestion() throws Exception {
        // Insert a container row into mock GriefLogger
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + griefLoggerDbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO containers (time, user, level, x, y, z, type, amount, action) " +
                    "VALUES (1789331000000, 1, 1, 100, 64, -200, 2, 5, 0);"); // REMOVE_ITEM 5x farmersdelight:smoked_ham
        }

        IngestionResult result = ingestionService.runIngestion();
        assertTrue(result.success());
        assertEquals(1, result.itemsIngested());
        assertEquals(1, result.containersIngested());

        Connection conn = dbManager.getConnection();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ig_observations WHERE action_type = 'REMOVE_ITEM'")) {
            assertTrue(rs.next());
            assertEquals("GRIEFLOGGER", rs.getString("source_type"));
            // Offset container event id
            assertEquals(10_000_000_001L, rs.getLong("source_event_id"));
            assertEquals(5, rs.getInt("amount"));
        }

        // Verify container node was created
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ig_nodes WHERE node_type = 'CONTAINER'")) {
            assertTrue(rs.next());
            assertEquals("minecraft:overworld", rs.getString("level_id"));
            assertEquals(100, rs.getDouble("x"));
            assertEquals(64, rs.getDouble("y"));
            assertEquals(-200, rs.getDouble("z"));
        }
    }

    @Test
    void testGracefulFailureWhenGriefLoggerMissing() {
        Path missing = tempDir.resolve("nonexistent.db");
        GriefLoggerAdapter missingAdapter = new GriefLoggerAdapter(missing);
        IngestionService service = new IngestionService(missingAdapter, dbManager);

        IngestionResult result = service.runIngestion();
        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("not found"));
    }
}
