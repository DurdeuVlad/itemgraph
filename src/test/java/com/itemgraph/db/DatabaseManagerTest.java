package com.itemgraph.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
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
        assertEquals(1, dbManager.getCurrentSchemaVersion());
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

        // Verify re-initializing does not fail and migrations are idempotent
        dbManager.close();
        dbManager.initialize(dbPath);
        assertEquals(1, dbManager.getCurrentSchemaVersion());
    }
}
