package com.itemgraph.db;

import com.itemgraph.db.migration.MigrationRunner;
import com.itemgraph.db.migration.V5__ContainerFlowTopology;
import com.itemgraph.db.migration.V20__UnverifiedArmorStandInteractionEvidence;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.ingest.GriefLoggerAdapter;
import com.itemgraph.ingest.GriefLoggerHistoricalImporter;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.UnifiedEvidenceQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
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
    void mariaDbNetworkHeartbeatRunsOnTheBackgroundWorker() throws Exception {
        assertNetworkHeartbeatRunsOnBackgroundWorker(
                "ITEMGRAPH_TEST_MARIADB_URL", "ITEMGRAPH_TEST_MARIADB_USER", "ITEMGRAPH_TEST_MARIADB_PASSWORD",
                "disable");
    }

    @Test
    void mysqlNetworkHeartbeatRunsOnTheBackgroundWorker() throws Exception {
        // The CI MySQL account uses caching_sha2_password. Exercise the normal
        // database-manager path over TLS instead of enabling RSA key retrieval
        // on an unencrypted connection just for this test.
        assertNetworkHeartbeatRunsOnBackgroundWorker(
                "ITEMGRAPH_TEST_MYSQL_URL", "ITEMGRAPH_TEST_MYSQL_USER", "ITEMGRAPH_TEST_MYSQL_PASSWORD",
                "trust");
    }

    private void assertNetworkHeartbeatRunsOnBackgroundWorker(String urlVariable, String userVariable,
                                                               String passwordVariable, String sslMode) throws Exception {
        String url = System.getenv(urlVariable);
        assumeTrue(url != null && !url.isBlank(), "No endpoint configured for " + urlVariable);
        URI endpoint = URI.create(url.substring("jdbc:".length()));
        String databaseName = endpoint.getPath().replaceFirst("^/", "");
        DatabaseManager database = DatabaseManager.getInstance();
        InternalObservationService service = InternalObservationService.getInstance();
        try {
            database.initialize(DatabaseSettings.mysqlMariaDb(endpoint.getHost(), endpoint.getPort(), databaseName,
                    System.getenv().getOrDefault(userVariable, "itemgraph"),
                    System.getenv().getOrDefault(passwordVariable, "itemgraph"),
                    5_000, true, sslMode));
            assertTrue(database.isInitialized(), "the CI endpoint should initialize ItemGraph storage: " + urlVariable);
            assertTrue(database.validateNetworkConnection(5), "the JDBC protocol ping should report a valid connection");
            database.getConnection().close();
            assertTrue(database.validateNetworkConnection(5),
                    "the heartbeat should reinitialize a closed network connection");

            service.stop();
            service.clear();
            service.configureOperations(10, 1, 100, 1_000, true);
            service.start();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (service.getTotalDatabaseHeartbeats() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            service.stop();

            assertTrue(service.getTotalDatabaseHeartbeats() >= 1,
                    "the worker should send a successful idle network heartbeat");
            assertEquals(0, service.getTotalDatabaseHeartbeatFailures());
        } finally {
            service.stop();
            service.clear();
            service.configureOperations(250, 20, 100, 30_000, true);
            database.close();
        }
    }

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
            // The V5 archive check deletes active observations. Run it before inserting
            // the V20 disposition fixture, which intentionally holds an FK to its row.
            assertPopulatedLegacyObservationArchive(conn);
            assertLegacyArmorStandDispositionIsPortable(conn);
            assertEquals("longtext", columnDataType(conn, "ig_grieflogger_row_supersessions", "source_key"),
                    "raw imported keys must not be truncated by the bounded supersession index");
            assertEquals(64L, columnCharacterLength(conn,
                            "ig_grieflogger_row_supersessions", "source_key_hash"),
                    "the primary key must use the fixed-length digest");
            try (Statement statement = conn.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT HEX('Source-Key') = HEX('source-key')")) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1),
                        "HEX key equality must remain case-sensitive under the server collation");
            }
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

    private static void assertLegacyArmorStandDispositionIsPortable(Connection conn) throws Exception {
        long stamp = System.currentTimeMillis();
        long playerId;
        long standId;
        long fingerprintId;
        try (PreparedStatement node = conn.prepareStatement(
                "INSERT INTO ig_nodes (node_type, owner_uuid, level_id) VALUES ('PLAYER', ?, 'minecraft:overworld')",
                Statement.RETURN_GENERATED_KEYS)) {
            node.setString(1, "migration-audit-player-" + stamp);
            node.executeUpdate();
            playerId = generatedKey(node);
        }
        try (PreparedStatement node = conn.prepareStatement(
                "INSERT INTO ig_nodes (node_type, level_id, x, y, z) "
                        + "VALUES ('ARMOR_STAND', 'minecraft:overworld', ?, 64, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            node.setDouble(1, stamp % 100000);
            node.executeUpdate();
            standId = generatedKey(node);
        }
        try (PreparedStatement fingerprint = conn.prepareStatement(
                "INSERT INTO ig_item_fingerprints (item_id, fingerprint_hash) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            fingerprint.setString(1, "minecraft:iron_helmet");
            fingerprint.setString(2, "v20-legacy-armor-stand-" + stamp);
            fingerprint.executeUpdate();
            fingerprintId = generatedKey(fingerprint);
        }

        long observationId;
        try (PreparedStatement observation = conn.prepareStatement("""
                INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id,
                    fingerprint_id, action_type, amount, raw_data)
                VALUES ('ITEMGRAPH_INTERNAL', ?, ?, ?, ?, 'EQUIP_ARMOR_STAND', 1, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            observation.setLong(1, stamp);
            observation.setLong(2, playerId);
            observation.setLong(3, standId);
            observation.setLong(4, fingerprintId);
            observation.setBytes(5, new byte[]{1, 2, 3});
            observation.executeUpdate();
            observationId = generatedKey(observation);
        }
        long edgeId;
        try (PreparedStatement edge = conn.prepareStatement("""
                INSERT INTO ig_inferred_edges (from_node_id, to_node_id, fingerprint_id, amount,
                    time_start, time_end, confidence, explanation, created_at)
                VALUES (?, ?, ?, 1, ?, ?, 0.5, 'legacy unverified edge', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            edge.setLong(1, playerId);
            edge.setLong(2, standId);
            edge.setLong(3, fingerprintId);
            edge.setLong(4, stamp);
            edge.setLong(5, stamp);
            edge.setLong(6, stamp);
            edge.executeUpdate();
            edgeId = generatedKey(edge);
        }
        try (PreparedStatement allocation = conn.prepareStatement("""
                INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount)
                VALUES (?, ?, 'SOURCE', 1)
                """)) {
            allocation.setLong(1, edgeId);
            allocation.setLong(2, observationId);
            allocation.executeUpdate();
        }

        new V20__UnverifiedArmorStandInteractionEvidence().apply(conn, DatabaseDialect.MYSQL_MARIADB);
        try (PreparedStatement check = conn.prepareStatement("""
                SELECT o.correlation_status, disposition.reason_code, edge.edge_state
                FROM ig_observations o
                JOIN ig_observation_dispositions disposition ON disposition.observation_id = o.id
                JOIN ig_inferred_edges edge ON edge.id = ?
                WHERE o.id = ?
                """)) {
            check.setLong(1, edgeId);
            check.setLong(2, observationId);
            try (ResultSet row = check.executeQuery()) {
                assertTrue(row.next(), "V20 should disposition legacy evidence on MySQL/MariaDB");
                assertEquals("CLOSED_UNRESOLVED", row.getString(1));
                assertEquals(V20__UnverifiedArmorStandInteractionEvidence.REASON_CODE, row.getString(2));
                assertEquals("SUPERSEDED_UNVERIFIED_EVIDENCE", row.getString(3));
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws Exception {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            assertTrue(keys.next(), "insert must yield a generated key");
            return keys.getLong(1);
        }
    }

    private static void assertPopulatedLegacyObservationArchive(Connection conn) throws Exception {
        int id = 900_002 + ThreadLocalRandom.current().nextInt(100_000);
        byte[] rawData = ("dialect-legacy-evidence-" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String componentSummary = "component-" + "x".repeat(1_024);
        try (Statement statement = conn.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS ig_legacy_observation_evidence");
        }
        try (PreparedStatement node = conn.prepareStatement(
                "INSERT INTO ig_nodes (id, node_type, level_id) VALUES (?, 'PLAYER', 'minecraft:overworld')");
             PreparedStatement fingerprint = conn.prepareStatement("""
                     INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash, component_summary)
                     VALUES (?, 'minecraft:diamond', ?, ?)
                     """);
             PreparedStatement observation = conn.prepareStatement("""
                     INSERT INTO ig_observations (id, source_type, source_event_id, timestamp_ms,
                         node_id, target_node_id, fingerprint_id, action_type, amount, raw_data)
                     VALUES (?, 'MYSQL_DIALECT_TEST', ?, 1200, ?, NULL, ?, 'DROP_ITEM', 2, ?)
                     """)) {
            node.setInt(1, id);
            node.executeUpdate();
            fingerprint.setInt(1, id);
            fingerprint.setString(2, "dialect-legacy-hash-" + id);
            fingerprint.setString(3, componentSummary);
            fingerprint.executeUpdate();
            observation.setInt(1, id);
            observation.setInt(2, id);
            observation.setInt(3, id);
            observation.setInt(4, id);
            observation.setBytes(5, rawData);
            observation.executeUpdate();
        }

        new V5__ContainerFlowTopology().apply(conn, DatabaseDialect.MYSQL_MARIADB);

        try (PreparedStatement query = conn.prepareStatement("""
                SELECT fingerprint_item_id, fingerprint_hash, fingerprint_component_summary, raw_data
                FROM ig_legacy_observation_evidence
                WHERE archive_migration_version = 5 AND original_observation_id = ?
                """)) {
            query.setInt(1, id);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("minecraft:diamond", rows.getString("fingerprint_item_id"));
                assertEquals("dialect-legacy-hash-" + id, rows.getString("fingerprint_hash"));
                assertEquals(componentSummary, rows.getString("fingerprint_component_summary"));
                assertArrayEquals(rawData, rows.getBytes("raw_data"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement query = conn.prepareStatement(
                "SELECT COUNT(*) FROM ig_observations WHERE id = ?")) {
            query.setInt(1, id);
            try (ResultSet rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1), "archived obsolete rows must not remain in current graph queries");
            }
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

    private static String columnDataType(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "missing expected column " + table + "." + column);
                return rows.getString(1).toLowerCase(java.util.Locale.ROOT);
            }
        }
    }

    private static long columnCharacterLength(Connection connection, String table, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "missing expected column " + table + "." + column);
                return rows.getLong(1);
            }
        }
    }

    private record Endpoint(String url, String user, String password) {
    }
}
