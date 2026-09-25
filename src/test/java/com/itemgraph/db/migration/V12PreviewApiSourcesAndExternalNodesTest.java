package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseManager;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class V12PreviewApiSourcesAndExternalNodesTest {
    @TempDir
    Path tempDir;

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("itemgraph.db"));
        conn = DatabaseManager.getInstance().getConnection();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.getInstance().close();
    }

    @Test
    void apiSourceRegistryPersistsRegistrationIdentity() throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO ig_api_sources
                        (source_mod_id, display_name, api_version, registered_at_ms, last_seen_ms)
                    VALUES ('storage_mod', 'Storage Mod', 1, 10, 10)
                    """);
            assertThrows(SQLException.class, () -> stmt.execute("""
                    INSERT INTO ig_api_sources
                        (source_mod_id, display_name, api_version, registered_at_ms, last_seen_ms)
                    VALUES ('storage_mod', 'Changed', 1, 11, 11)
                    """));
            stmt.execute("""
                    UPDATE ig_api_sources SET display_name = 'Storage Mod Plus', last_seen_ms = 11
                    WHERE source_mod_id = 'storage_mod'
                    """);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT display_name, api_version, registered_at_ms, last_seen_ms "
                            + "FROM ig_api_sources WHERE source_mod_id = 'storage_mod'")) {
                assertTrue(rs.next());
                assertEquals("Storage Mod Plus", rs.getString(1));
                assertEquals(1, rs.getInt(2));
                assertEquals(10, rs.getLong(3));
                assertEquals(11, rs.getLong(4));
            }
        }
    }

    @Test
    void externalInventoryIdentityIsUniqueAndCoordinateLess() throws Exception {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE name = 'idx_nodes_external_key'")) {
            assertTrue(rs.next(), "V12 must create the durable external identity index");
            assertTrue(rs.getString(1).contains("external_key IS NOT NULL"));
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    INSERT INTO ig_nodes
                        (node_type, level_id, x, y, z, custom_label, external_key)
                    VALUES ('EXTERNAL_INVENTORY', 'external:storage_mod', NULL, NULL, NULL,
                            'Remote Inventory', 'storage_mod/remote-1')
                    """);
            assertThrows(SQLException.class, () -> stmt.execute("""
                    INSERT INTO ig_nodes
                        (node_type, level_id, x, y, z, custom_label, external_key)
                    VALUES ('EXTERNAL_INVENTORY', 'external:storage_mod', NULL, NULL, NULL,
                            'Duplicate', 'storage_mod/remote-1')
                    """));
            stmt.execute("""
                    INSERT INTO ig_nodes
                        (node_type, level_id, x, y, z, custom_label, external_key)
                    VALUES ('EXTERNAL_INVENTORY', 'external:other_mod', NULL, NULL, NULL,
                            'Other', 'other_mod/remote-1')
                    """);
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM ig_nodes WHERE node_type = 'EXTERNAL_INVENTORY'")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        }
    }

    @Test
    void migrationApplyIsIdempotent() {
        V12__PreviewApiSourcesAndExternalNodes migration = new V12__PreviewApiSourcesAndExternalNodes();
        assertEquals(12, migration.getVersion());
        assertDoesNotThrow(() -> migration.apply(conn));
        assertDoesNotThrow(() -> migration.apply(conn));
    }
}
