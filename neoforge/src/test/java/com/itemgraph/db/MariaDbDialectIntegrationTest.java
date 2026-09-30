package com.itemgraph.db;

import com.itemgraph.db.migration.MigrationRunner;
import com.itemgraph.ingest.GriefLoggerAdapter;
import com.itemgraph.ingest.GriefLoggerHistoricalImporter;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.UnifiedEvidenceQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared migration and JDBC translation contract against disposable
 * MySQL/MariaDB services supplied by CI. The test is skipped for ordinary local
 * runs unless an endpoint environment variable is present.
 */
class MariaDbDialectIntegrationTest {
    @Test
    void mariaDbMigrationAndEvidenceContract(@TempDir Path tempDir) throws Exception {
        runConfigured("ITEMGRAPH_TEST_MARIADB_URL", "ITEMGRAPH_TEST_MARIADB_USER", "ITEMGRAPH_TEST_MARIADB_PASSWORD", tempDir);
    }

    @Test
    void mysqlMigrationAndEvidenceContract(@TempDir Path tempDir) throws Exception {
        runConfigured("ITEMGRAPH_TEST_MYSQL_URL", "ITEMGRAPH_TEST_MYSQL_USER", "ITEMGRAPH_TEST_MYSQL_PASSWORD", tempDir);
    }

    private static void runConfigured(String urlKey, String userKey, String passwordKey, Path tempDir) throws Exception {
        String url = System.getenv(urlKey);
        assumeTrue(url != null && !url.isBlank(), "No endpoint configured for " + urlKey);
        runContract(new Endpoint(url, System.getenv().getOrDefault(userKey, "itemgraph"),
                System.getenv().getOrDefault(passwordKey, "itemgraph")), tempDir);
    }

    private static void runContract(Endpoint endpoint, Path tempDir) throws Exception {
        try (Connection raw = DriverManager.getConnection(endpoint.url(), endpoint.user(), endpoint.password());
             Connection conn = DialectConnection.wrap(raw, DatabaseDialect.MYSQL_MARIADB)) {
            assertEquals(DatabaseDialect.MYSQL_MARIADB, DatabaseDialect.fromConnection(conn));
            assertEquals(MigrationRunner.LATEST_VERSION,
                    MigrationRunner.runMigrations(conn, DatabaseDialect.MYSQL_MARIADB));
            if (hasIndex(conn, "ig_observations", "idx_obs_time_fp")) {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("DROP INDEX idx_obs_time_fp ON ig_observations");
                }
            }
            boolean createdDuplicateName = false;
            if (!hasIndex(conn, "ig_audit_events", "idx_obs_time_fp")) {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("CREATE INDEX idx_obs_time_fp ON ig_audit_events(event_type)");
                }
                createdDuplicateName = true;
            }
            StorageIndexManager.apply(conn, DatabaseDialect.MYSQL_MARIADB, false);
            assertFalse(hasIndex(conn, "ig_observations", "idx_obs_time_fp"));
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_bridge_lookup"),
                    "the sole index supporting the node_id foreign key must remain present");
            assertTrue(hasIndex(conn, "ig_audit_events", "idx_obs_time_fp"),
                    "same-named index on another table must not be dropped by policy");
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_source_unique"));
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_internal_dedup"));
            assertTrue(hasIndex(conn, "ig_nodes", "idx_nodes_external_key"));
            assertTrue(hasIndex(conn, "ig_audit_events", "idx_audit_events_source_unique"));
            String alternateForeignKeyIndex = "idx_fk_alt_"
                    + Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
            boolean createdAlternateForeignKeyIndex = false;
            try {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("CREATE INDEX " + alternateForeignKeyIndex
                            + " ON ig_observations(node_id, timestamp_ms)");
                }
                createdAlternateForeignKeyIndex = true;
                StorageIndexManager.apply(conn, DatabaseDialect.MYSQL_MARIADB, false);
                assertFalse(hasIndex(conn, "ig_observations", "idx_obs_bridge_lookup"),
                        "the managed index should be dropped when another same-table index supports the foreign key");
                assertTrue(hasIndex(conn, "ig_observations", alternateForeignKeyIndex),
                        "the alternate foreign-key support index must remain");
            } finally {
                if (createdAlternateForeignKeyIndex) {
                    try {
                        // Restore a managed node_id-leading index before removing
                        // the temporary index that currently supports the FK.
                        StorageIndexManager.apply(conn, DatabaseDialect.MYSQL_MARIADB, true);
                    } finally {
                        try (Statement statement = conn.createStatement()) {
                            statement.execute("DROP INDEX " + alternateForeignKeyIndex + " ON ig_observations");
                        }
                    }
                }
            }
            StorageIndexManager.apply(conn, DatabaseDialect.MYSQL_MARIADB, true);
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_time_fp"));
            assertTrue(hasIndex(conn, "ig_grieflogger_lookup", "idx_gl_lookup_subject"));
            StorageIndexManager.apply(conn, DatabaseDialect.MYSQL_MARIADB, false);
            assertFalse(hasIndex(conn, "ig_grieflogger_lookup", "idx_gl_lookup_subject"));
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_bridge_lookup"),
                    "the sole index supporting the node_id foreign key must remain present after re-disable");
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_source_unique"));
            assertTrue(hasIndex(conn, "ig_observations", "idx_obs_internal_dedup"));
            assertTrue(hasIndex(conn, "ig_nodes", "idx_nodes_external_key"));
            assertTrue(hasIndex(conn, "ig_audit_events", "idx_audit_events_source_unique"));
            if (createdDuplicateName) {
                try (Statement statement = conn.createStatement()) {
                    statement.execute("DROP INDEX idx_obs_time_fp ON ig_audit_events");
                }
            }
            assertEquals(MigrationRunner.LATEST_VERSION,
                    MigrationRunner.runMigrations(conn, DatabaseDialect.MYSQL_MARIADB));

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                         + "WHERE table_schema = DATABASE() AND table_name = 'ig_observations'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT IGNORE INTO ig_nodes (id, node_type, level_id) "
                        + "VALUES (900001, 'PLAYER', 'minecraft:overworld')");
                stmt.executeUpdate("INSERT IGNORE INTO ig_item_fingerprints (id, item_id, fingerprint_hash) "
                        + "VALUES (900001, 'minecraft:diamond', 'dialect-test-hash')");
                stmt.executeUpdate("INSERT IGNORE INTO ig_observations "
                        + "(source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) "
                        + "VALUES ('MYSQL_DIALECT_TEST', 900001, 1000, 900001, 900001, 'DROP_ITEM', 1)");
            }

            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO ig_source_checkpoints "
                    + "(source_name, last_source_rowid, last_timestamp, updated_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(source_name) DO UPDATE SET last_source_rowid = excluded.last_source_rowid")) {
                stmt.setString(1, "dialect-test");
                stmt.setLong(2, 2);
                stmt.setLong(3, 3);
                stmt.setLong(4, 4);
                stmt.executeUpdate();
            }

            assertHistoricalImportAndLookup(endpoint, conn, tempDir);
        }
    }

    private static void assertHistoricalImportAndLookup(Endpoint endpoint, Connection queryConnection,
                                                         Path tempDir) throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger.db");
        int testX = 1_000_000 + ThreadLocalRandom.current().nextInt(1_000_000);
        createLookupFixture(sourcePath, testX);
        byte[] originalSource = Files.readAllBytes(sourcePath);

        DatabaseManager target = mock(DatabaseManager.class);
        when(target.isInitialized()).thenReturn(true);
        when(target.openWriteConnection()).thenAnswer(ignored -> DialectConnection.wrap(
                DriverManager.getConnection(endpoint.url(), endpoint.user(), endpoint.password()),
                DatabaseDialect.MYSQL_MARIADB));

        GriefLoggerHistoricalImporter.ImportReport report = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), target).importAll();
        assertEquals("COMPLETE", report.status());
        assertEquals(11, report.tables().stream().filter(GriefLoggerHistoricalImporter.TableReport::present).count());
        assertTrue(report.rowsImported() > 0);
        assertArrayEquals(originalSource, Files.readAllBytes(sourcePath),
                "the historical importer must leave the GriefLogger source byte-for-byte unchanged");

        GriefLoggerHistoricalImporter.ImportReport repeated = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), target).importAll();
        assertEquals("COMPLETE", repeated.status());
        assertEquals(0, repeated.rowsImported(), "restarting the importer must remain idempotent");

        var rows = new UnifiedEvidenceQueryService().findFiltered(
                queryConnection,
                AuditLookupFilters.parse("action.break_block radius.100", 1_000L),
                "minecraft:overworld", testX, 64, 10, 10, 0);
        assertEquals(List.of("BREAK_BLOCK", "BREAK_BLOCK"), rows.stream().map(row -> row.actionType()).toList());
        assertEquals(List.of(130L, 120L), rows.stream().map(row -> row.timestampMs()).toList());
    }

    private static void createLookupFixture(Path path, int x) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data BLOB, amount INTEGER, action INTEGER)");
            statement.execute("CREATE TABLE containers (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data BLOB, amount INTEGER, action INTEGER)");
            statement.execute("CREATE TABLE blocks (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, action INTEGER)");
            statement.execute("CREATE TABLE sessions (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)");
            statement.execute("CREATE TABLE chats (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)");
            statement.execute("CREATE TABLE commands (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, command TEXT)");
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, uuid TEXT)");
            statement.execute("CREATE TABLE usernames (id INTEGER PRIMARY KEY, time INTEGER, uuid TEXT, name TEXT)");
            statement.execute("CREATE TABLE levels (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE materials (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("CREATE TABLE entities (id INTEGER PRIMARY KEY, name TEXT)");
            statement.execute("INSERT INTO users VALUES (1, 'Alice', 'uuid-a')");
            statement.execute("INSERT INTO usernames VALUES (1, 50, 'uuid-a', 'Alice')");
            statement.execute("INSERT INTO levels VALUES (1, 'minecraft:overworld')");
            statement.execute("INSERT INTO materials VALUES (1, 'minecraft:diamond_sword')");
            statement.execute("INSERT INTO entities VALUES (1, 'minecraft:zombie')");
            statement.execute("INSERT INTO blocks VALUES (1, 120, 1, 1, " + x + ", 64, 10, 1, 0)");
            statement.execute("INSERT INTO blocks VALUES (2, 130, 1, 1, " + x + ", 64, 10, 1, 0)");
        }
    }

    private static boolean hasIndex(Connection connection, String table, String indexName) throws Exception {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null,
                table, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }

    private record Endpoint(String url, String user, String password) {
    }
}
