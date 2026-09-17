package com.itemgraph.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseManagerTest {

    @AfterEach
    void tearDown() {
        DatabaseManager.getInstance().close();
    }

    @Test
    void testDatabaseInitializationAndMigrations(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("sub/itemgraph.db");
        DatabaseManager dbManager = DatabaseManager.getInstance();

        assertFalse(dbManager.isInitialized());
        dbManager.initialize(dbPath);

        assertTrue(dbManager.isInitialized());
        assertTrue(dbManager.isConnected());
        assertEquals(8, dbManager.getCurrentSchemaVersion());
        assertTrue(Files.exists(dbPath));

        Connection conn = dbManager.getConnection();
        assertNotNull(conn);

        // Verify tables exist
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }
        }

        assertTrue(tables.contains("ig_schema_migrations"), "ig_schema_migrations table must exist");
        assertTrue(tables.contains("ig_source_checkpoints"), "ig_source_checkpoints table must exist");
        assertTrue(tables.contains("ig_nodes"), "ig_nodes table must exist");
        assertTrue(tables.contains("ig_item_fingerprints"), "ig_item_fingerprints table must exist");
        assertTrue(tables.contains("ig_observations"), "ig_observations table must exist");
        assertTrue(tables.contains("ig_inferred_edges"), "ig_inferred_edges table must exist");
        assertTrue(tables.contains("ig_edge_evidence"), "ig_edge_evidence table must exist");
        assertTrue(tables.contains("ig_edge_allocations"), "ig_edge_allocations table must exist");

        // Verify unique constraint on ig_observations(source_type, source_event_id)
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (1, 'PLAYER', 'minecraft:overworld')");
            stmt.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) VALUES (1, 'minecraft:diamond', 'hash123')");
            stmt.execute("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) " +
                    "VALUES ('GRIEFLOGGER', 100, 1000, 1, 1, 'DROP_ITEM', 1)");

            // Attempting duplicate insert should fail due to unique constraint
            assertThrows(SQLException.class, () -> {
                stmt.execute("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) " +
                        "VALUES ('GRIEFLOGGER', 100, 2000, 1, 1, 'DROP_ITEM', 1)");
            });

            // INSERT OR IGNORE should succeed without error
            int affected = stmt.executeUpdate("INSERT OR IGNORE INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) " +
                    "VALUES ('GRIEFLOGGER', 100, 2000, 1, 1, 'DROP_ITEM', 1)");
            assertEquals(0, affected);
        }

        // V6/V7 adds the correlation bookkeeping columns the engine's incremental scan is
        // driven by; a fresh observation must start life unevaluated (NULL).
        List<String> observationColumns = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(ig_observations)")) {
            while (rs.next()) {
                observationColumns.add(rs.getString("name"));
            }
        }
        assertTrue(observationColumns.contains("correlated_at"), "ig_observations.correlated_at must exist");
        assertTrue(observationColumns.contains("correlation_status"), "ig_observations.correlation_status must exist");

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT correlated_at, correlation_status FROM ig_observations")) {
            assertTrue(rs.next());
            rs.getLong("correlated_at");
            assertTrue(rs.wasNull(), "a newly ingested observation must not be marked correlated");
            assertEquals("PENDING", rs.getString("correlation_status"));
        }

        // Verify re-initializing does not fail and migrations are idempotent
        dbManager.close();
        dbManager.initialize(dbPath);
        assertEquals(8, dbManager.getCurrentSchemaVersion());
    }

    /**
     * Phase 6 query commands read through their own connection rather than the shared
     * writer one, so a lookup running on the query worker cannot observe rows inside an
     * ingestion transaction that is about to roll back. The read-only intent is enforced
     * by SQLite ({@code PRAGMA query_only}), not merely documented — a query path that
     * could write would make "reads only" an unverified claim.
     */
    @Test
    void testReadOnlyConnectionSeesCommittedDataAndRefusesWrites(@TempDir Path tempDir) throws Exception {
        DatabaseManager dbManager = DatabaseManager.getInstance();

        // Before initialization there is nothing to read, and that must be an explicit
        // failure rather than a connection to an empty file created as a side effect.
        assertThrows(SQLException.class, dbManager::openReadOnlyConnection);

        dbManager.initialize(tempDir.resolve("itemgraph.db"));
        try (Statement stmt = dbManager.getConnection().createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (7, 'PLAYER', 'minecraft:overworld')");
        }

        try (Connection readConn = dbManager.openReadOnlyConnection()) {
            assertNotSame(dbManager.getConnection(), readConn, "queries must not borrow the writer connection");

            try (Statement stmt = readConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT node_type FROM ig_nodes WHERE id = 7")) {
                assertTrue(rs.next(), "committed rows must be visible to the reader");
                assertEquals("PLAYER", rs.getString(1));
            }

            assertThrows(SQLException.class, () -> {
                try (Statement stmt = readConn.createStatement()) {
                    stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (8, 'PLAYER', 'minecraft:overworld')");
                }
            }, "a query connection must not be able to write");
        }

        // Closing the reader must not disturb the shared writer connection.
        assertTrue(dbManager.isInitialized());
        try (Statement stmt = dbManager.getConnection().createStatement()) {
            stmt.execute("INSERT INTO ig_nodes (id, node_type, level_id) VALUES (9, 'PLAYER', 'minecraft:overworld')");
        }
    }
}
