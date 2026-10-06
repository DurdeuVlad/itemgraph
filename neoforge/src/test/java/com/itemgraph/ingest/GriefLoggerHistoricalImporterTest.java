package com.itemgraph.ingest;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GriefLoggerHistoricalImporterTest {
    @TempDir
    Path tempDir;

    private final DatabaseManager database = DatabaseManager.getInstance();

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void importsAllSourceTablesAndActionIdsWithoutMutatingSource() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger.db");
        createFixture(sourcePath);
        byte[] before = Files.readAllBytes(sourcePath);
        Path targetPath = tempDir.resolve("itemgraph.db");
        database.initialize(targetPath);

        GriefLoggerHistoricalImporter importer = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database);
        GriefLoggerHistoricalImporter.ImportReport first = importer.importAll();

        assertEquals("COMPLETE", first.status());
        assertEquals(28, first.rowsImported());
        assertEquals(28, first.rowsOpaque());
        assertEquals(11, first.tables().stream().filter(GriefLoggerHistoricalImporter.TableReport::present).count());
        assertArrayEquals(before, Files.readAllBytes(sourcePath));

        try (Statement stmt = database.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_grieflogger_rows")) {
            assertTrue(rs.next());
            assertEquals(28, rs.getInt(1));
        }
        try (PreparedStatement stmt = database.getConnection().prepareStatement(
                "SELECT action_id, payload_json, source_key FROM ig_grieflogger_rows "
                        + "WHERE table_name = 'items' AND source_key = 'pk:1'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt("action_id"));
                assertTrue(rs.getString("payload_json").contains("\"action\":0"));
                assertEquals("pk:1", rs.getString("source_key"));
            }
        }

        GriefLoggerHistoricalImporter.ImportReport second = importer.importAll();
        assertEquals("COMPLETE", second.status());
        assertEquals(0, second.rowsImported());
        assertArrayEquals(before, Files.readAllBytes(sourcePath));
    }

    @Test
    void hashesOversizedPrimaryKeysBeforeWritingPortableImportedRows() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger-long-primary-key.db");
        String longPrimaryKey = "🗡".repeat(616);
        createLongPrimaryKeyFixture(sourcePath, longPrimaryKey);
        database.initialize(tempDir.resolve("itemgraph-long-primary-key.db"));

        GriefLoggerHistoricalImporter.ImportReport report = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database).importAll();

        assertEquals("COMPLETE", report.status());
        String tail = "🗡".repeat(300);
        assertEquals("pk:x|y|z" + tail, "pk:" + String.join("|", java.util.List.of("x|y", "z" + tail)));
        assertFalse(GriefLoggerHistoricalImporter.sourceKeyForPrimaryValues(
                java.util.List.of("x|y", "z" + tail)).equals(
                GriefLoggerHistoricalImporter.sourceKeyForPrimaryValues(
                        java.util.List.of("x", "y|z" + tail))));
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT r.source_key, r.payload_json, l.source_key AS lookup_key
                FROM ig_grieflogger_rows r
                JOIN ig_grieflogger_lookup l
                  ON l.source_sha256 = r.source_sha256
                 AND l.table_name = r.table_name
                 AND l.source_key = r.source_key
                WHERE r.table_name = 'items' AND r.source_key LIKE 'pksha256:%'
                """); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            String sourceKey = rows.getString("source_key");
            assertTrue(sourceKey.codePointCount(0, sourceKey.length())
                    <= GriefLoggerHistoricalImporter.MAX_SOURCE_KEY_CODE_POINTS);
            assertEquals(sourceKey, rows.getString("lookup_key"));
            assertTrue(rows.getString("payload_json").contains(longPrimaryKey));
            assertFalse(rows.next());
        }
        GriefLoggerHistoricalImporter.ImportReport retry = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database).importAll();
        assertEquals("COMPLETE", retry.status());
        assertEquals(0, retry.rowsImported());
    }

    @Test
    void resumesLegacyLongPrimaryKeyCheckpointAndKeepsExistingLedgerIdentity() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger-legacy-long-checkpoint.db");
        String longPrimaryKey = "🗡".repeat(616);
        createLongPrimaryKeyFixture(sourcePath, longPrimaryKey, 2);
        database.initialize(tempDir.resolve("itemgraph-legacy-long-checkpoint.db"));
        GriefLoggerHistoricalImporter importer = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database);
        GriefLoggerHistoricalImporter.ImportReport initial = importer.importAll();
        String legacyKey = "pk:" + longPrimaryKey;
        String hashedKey = GriefLoggerHistoricalImporter.sourceKeyForPrimaryValues(
                java.util.List.of(longPrimaryKey));
        assertTrue(hashedKey.startsWith("pksha256:"));

        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                UPDATE ig_grieflogger_rows SET source_key = ?
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = ?
                """)) {
            statement.setString(1, legacyKey);
            statement.setString(2, initial.sourceSha256());
            statement.setString(3, hashedKey);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                UPDATE ig_grieflogger_lookup SET source_key = ?
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = ?
                """)) {
            statement.setString(1, legacyKey);
            statement.setString(2, initial.sourceSha256());
            statement.setString(3, hashedKey);
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                DELETE FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:pk-2'
                """)) {
            statement.setString(1, initial.sourceSha256());
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                DELETE FROM ig_grieflogger_lookup
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:pk-2'
                """)) {
            statement.setString(1, initial.sourceSha256());
            assertEquals(1, statement.executeUpdate());
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                UPDATE ig_grieflogger_import_checkpoints SET last_source_key = ?
                WHERE source_sha256 = ? AND table_name = 'items'
                """)) {
            statement.setString(1, legacyKey);
            statement.setString(2, initial.sourceSha256());
            assertEquals(1, statement.executeUpdate());
        }

        GriefLoggerHistoricalImporter.ImportReport resumed = importer.importAll();
        assertEquals("COMPLETE", resumed.status());
        assertEquals(1, resumed.rowsImported());
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT r.source_key, l.source_key AS lookup_key
                FROM ig_grieflogger_rows r
                JOIN ig_grieflogger_lookup l
                  ON l.source_sha256 = r.source_sha256
                 AND l.table_name = r.table_name
                 AND HEX(l.source_key) = HEX(r.source_key)
                WHERE r.source_sha256 = ? AND r.table_name = 'items'
                ORDER BY r.row_ordinal
                """)) {
            statement.setString(1, initial.sourceSha256());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(legacyKey, rows.getString("source_key"));
                assertEquals(legacyKey, rows.getString("lookup_key"));
                assertTrue(rows.next());
                assertEquals("pk:pk-2", rows.getString("source_key"));
                assertEquals("pk:pk-2", rows.getString("lookup_key"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void projectsEventTablesWithHistoricalReferencesAndProvenance() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger-lookup.db");
        createLookupFixture(sourcePath);
        byte[] before = Files.readAllBytes(sourcePath);
        database.initialize(tempDir.resolve("itemgraph-lookup.db"));

        GriefLoggerHistoricalImporter.ImportReport report = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database).importAll();

        assertEquals("COMPLETE", report.status());
        assertEquals(12, report.rowsImported());
        assertEquals(0, report.rowsOpaque());
        assertArrayEquals(before, Files.readAllBytes(sourcePath));

        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT table_name, action_type, timestamp_ms, level_name, player_name,
                       player_uuid, quantity, subject_id, evidence_class, detail
                FROM ig_grieflogger_lookup ORDER BY table_name
                """); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("blocks", rows.getString("table_name"));
            assertEquals("BREAK_BLOCK", rows.getString("action_type"));
            assertEquals("minecraft:overworld", rows.getString("level_name"));
            assertEquals("Alice (old)", rows.getString("player_name"));
            assertEquals("minecraft:diamond_sword", rows.getString("subject_id"));
            assertEquals("OBSERVED", rows.getString("evidence_class"));
            assertTrue(rows.getString("detail").contains("table=blocks"));

            assertTrue(rows.next());
            assertEquals("chats", rows.getString("table_name"));
            assertEquals("CHAT_MESSAGE", rows.getString("action_type"));
            assertTrue(rows.getString("detail").contains("text=hello"));

            assertTrue(rows.next());
            assertEquals("commands", rows.getString("table_name"));
            assertEquals("COMMAND_ATTEMPT", rows.getString("action_type"));

            assertTrue(rows.next());
            assertEquals("containers", rows.getString("table_name"));
            assertEquals("ADD_ITEM", rows.getString("action_type"));
            assertEquals(3, rows.getInt("quantity"));

            assertTrue(rows.next());
            assertEquals("items", rows.getString("table_name"));
            assertEquals("REMOVE_ITEM", rows.getString("action_type"));
            assertEquals("Alice (old)", rows.getString("player_name"));

            assertTrue(rows.next());
            assertEquals("sessions", rows.getString("table_name"));
            assertEquals("PLAYER_JOIN", rows.getString("action_type"));
            assertFalse(rows.next());
        }

        try (Statement statement = database.getConnection().createStatement()) {
            statement.executeUpdate("DELETE FROM ig_grieflogger_lookup");
        }
        GriefLoggerHistoricalImporter.ImportReport second = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database).importAll();
        assertEquals(0, second.rowsImported());
        try (Statement statement = database.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ig_grieflogger_lookup")) {
            assertTrue(rows.next());
            assertEquals(6, rows.getInt(1));
        }
    }

    @Test
    void unknownActionWithMalformedComponentBytesStaysOpaqueAndClaimsNoItemFlow() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger-unknown-action.db");
        createLookupFixture(sourcePath);
        byte[] malformedComponents = HexFormat.of().parseHex("0500000004");
        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + sourcePath.toAbsolutePath());
             PreparedStatement update = source.prepareStatement(
                     "UPDATE items SET action = 999, data = ? WHERE id = 1")) {
            update.setBytes(1, malformedComponents);
            assertEquals(1, update.executeUpdate());
            try (Statement statement = source.createStatement()) {
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO items (id, time, user, level, x, y, z, type, data, amount, action)
                        VALUES (2, 101, 1, 1, 10, 64, 10, 1, NULL, 4, NULL)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO items (id, time, user, level, x, y, z, type, data, amount, action)
                        VALUES (3, 102, 1, 1, 10, 64, 10, 1, NULL, 99, 2147483648)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO items (id, time, user, level, x, y, z, type, data, amount, action)
                        VALUES (4, 103, 1, 1, 10, 64, 10, 1, NULL, 7, 11)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO items (id, time, user, level, x, y, z, type, data, amount, action)
                        VALUES (5, 104, 1, 1, 10, 64, 10, 1, NULL, 8, 999.5)
                        """));
                assertEquals(1, statement.executeUpdate("""
                        INSERT INTO items (id, time, user, level, x, y, z, type, data, amount, action, action_id)
                        VALUES (6, 105, 1, 1, 10, 64, 10, 1, NULL, 12, 999.5, 1)
                        """));
            }
        }
        byte[] sourceBeforeImport = Files.readAllBytes(sourcePath);
        database.initialize(tempDir.resolve("itemgraph-unknown-action.db"));

        GriefLoggerHistoricalImporter.ImportReport report = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database).importAll();

        assertEquals("COMPLETE", report.status());
        assertEquals(6, report.rowsOpaque());
        assertArrayEquals(sourceBeforeImport, Files.readAllBytes(sourcePath),
                "import must leave the GriefLogger source byte-for-byte unchanged");
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_id, payload_blob, unresolved_reason
                FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:1'
                """)) {
            statement.setString(1, report.sourceSha256());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(999, rows.getInt("action_id"));
                assertArrayEquals(malformedComponents, rows.getBytes("payload_blob"));
                assertEquals("unknown_action_id:999", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_id, payload_blob, unresolved_reason
                FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:2'
                """)) {
            statement.setString(1, report.sourceSha256());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getObject("action_id"));
                assertNull(rows.getBytes("payload_blob"));
                assertEquals("missing_action_id", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_type, quantity, subject_id, evidence_class, unresolved_reason, raw_byte_hash
                FROM ig_grieflogger_lookup
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:1'
                """)) {
            statement.setString(1, report.sourceSha256());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("UNKNOWN_ACTION_999", rows.getString("action_type"));
                assertEquals(0, rows.getInt("quantity"));
                assertNull(rows.getString("subject_id"));
                assertEquals("UNRESOLVED", rows.getString("evidence_class"));
                assertEquals("unknown_action_id:999", rows.getString("unresolved_reason"));
                String expectedByteHash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(malformedComponents));
                assertEquals(expectedByteHash, rows.getString("raw_byte_hash"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_type, quantity, subject_id, evidence_class, unresolved_reason
                FROM ig_grieflogger_lookup
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:2'
                """)) {
            statement.setString(1, report.sourceSha256());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("HISTORICAL_UNRESOLVED", rows.getString("action_type"));
                assertEquals(0, rows.getInt("quantity"));
                assertNull(rows.getString("subject_id"));
                assertEquals("UNRESOLVED", rows.getString("evidence_class"));
                assertEquals("missing_action_id", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
        assertUnresolvedItemAction(report.sourceSha256(), "pk:3", 2_147_483_648L, null,
                "unknown_action_id:2147483648", "UNKNOWN_ACTION_2147483648", 99);
        assertUnresolvedItemAction(report.sourceSha256(), "pk:4", 11L, 11L,
                "unknown_action_id:11", "UNKNOWN_ACTION_11", 7);
        assertMalformedActionIdHasNoProjectionSemantics(report.sourceSha256());
        assertMalformedActionAliasPrecedenceIsConsistent(report.sourceSha256());
    }

    private void assertUnresolvedItemAction(String sourceHash, String sourceKey, long sourceActionId,
                                            Long indexedActionId,
                                            String reason, String actionType, int sourceAmount) throws Exception {
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_id, payload_json, unresolved_reason FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = ?
                """)) {
            statement.setString(1, sourceHash);
            statement.setString(2, sourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                if (indexedActionId == null) {
                    assertNull(rows.getObject("action_id"));
                } else {
                    assertEquals(indexedActionId.longValue(), rows.getLong("action_id"));
                }
                assertTrue(rows.getString("payload_json").contains("\"action\":" + sourceActionId));
                assertEquals(reason, rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_type, quantity, subject_id, evidence_class, unresolved_reason
                FROM ig_grieflogger_lookup
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = ?
                """)) {
            statement.setString(1, sourceHash);
            statement.setString(2, sourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(actionType, rows.getString("action_type"));
                assertEquals(0, rows.getInt("quantity"),
                        "source amount " + sourceAmount + " is not a flow claim");
                assertNull(rows.getString("subject_id"));
                assertEquals("UNRESOLVED", rows.getString("evidence_class"));
                assertEquals(reason, rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
    }

    private void assertMalformedActionIdHasNoProjectionSemantics(String sourceHash) throws Exception {
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_id, payload_json, unresolved_reason FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:5'
                """)) {
            statement.setString(1, sourceHash);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getObject("action_id"));
                assertTrue(rows.getString("payload_json").contains("\"action\":999.5"));
                assertEquals("invalid_action_id:999.5", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_type, quantity, subject_id, evidence_class, unresolved_reason
                FROM ig_grieflogger_lookup
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:5'
                """)) {
            statement.setString(1, sourceHash);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("HISTORICAL_UNRESOLVED", rows.getString("action_type"));
                assertEquals(0, rows.getInt("quantity"));
                assertNull(rows.getString("subject_id"));
                assertEquals("UNRESOLVED", rows.getString("evidence_class"));
                assertEquals("invalid_action_id:999.5", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
    }

    private void assertMalformedActionAliasPrecedenceIsConsistent(String sourceHash) throws Exception {
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_id, payload_json, unresolved_reason FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:6'
                """)) {
            statement.setString(1, sourceHash);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertNull(rows.getObject("action_id"));
                assertTrue(rows.getString("payload_json").contains("\"action\":999.5"));
                assertTrue(rows.getString("payload_json").contains("\"action_id\":1"));
                assertEquals("invalid_action_id:999.5", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
        try (PreparedStatement statement = database.getConnection().prepareStatement("""
                SELECT action_type, quantity, subject_id, evidence_class, unresolved_reason
                FROM ig_grieflogger_lookup
                WHERE source_sha256 = ? AND table_name = 'items' AND source_key = 'pk:6'
                """)) {
            statement.setString(1, sourceHash);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("HISTORICAL_UNRESOLVED", rows.getString("action_type"));
                assertEquals(0, rows.getInt("quantity"));
                assertNull(rows.getString("subject_id"));
                assertEquals("UNRESOLVED", rows.getString("evidence_class"));
                assertEquals("invalid_action_id:999.5", rows.getString("unresolved_reason"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void rejectsReadableDatabaseWithoutSupportedCoreSchema() throws Exception {
        Path sourcePath = tempDir.resolve("unrelated.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sourcePath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE unrelated (id INTEGER PRIMARY KEY)");
        }
        database.initialize(tempDir.resolve("itemgraph.db"));

        IOException failure = assertThrows(IOException.class,
                () -> new GriefLoggerHistoricalImporter(new GriefLoggerAdapter(sourcePath), database).importAll());
        assertTrue(failure.getMessage().contains("supported core schema"));
    }

    @Test
    void importUsesIndependentWriterConnectionInsteadOfLiveWriterMonitor() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger-concurrent.db");
        createFixture(sourcePath);
        database.initialize(tempDir.resolve("itemgraph-concurrent.db"));
        GriefLoggerHistoricalImporter importer = new GriefLoggerHistoricalImporter(
                new GriefLoggerAdapter(sourcePath), database);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Connection liveWriter = database.getConnection();
            synchronized (liveWriter) {
                var future = executor.submit(importer::importAll);
                GriefLoggerHistoricalImporter.ImportReport report = future.get(10, TimeUnit.SECONDS);
                assertEquals("COMPLETE", report.status());
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void failedRunPreservesRowsCommittedBeforeLaterTableFailure() throws Exception {
        Path sourcePath = tempDir.resolve("grieflogger-failure.db");
        createFixture(sourcePath, 501);
        database.initialize(tempDir.resolve("itemgraph-failure.db"));

        GriefLoggerAdapter failingSource = new GriefLoggerAdapter(sourcePath) {
            @Override
            public boolean isSupportedSchemaAvailable() {
                return true;
            }

            @Override
            public Connection openReadOnlyConnection() throws SQLException {
                Connection delegate = super.openReadOnlyConnection();
                return (Connection) Proxy.newProxyInstance(
                        Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if ("prepareStatement".equals(method.getName())
                                    && args != null && args.length > 0
                                    && args[0] instanceof String sql
                                    && sql.contains("FROM \"containers\"")) {
                                throw new SQLException("synthetic containers failure");
                            }
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException invocationFailure) {
                                throw invocationFailure.getCause();
                            }
                        });
            }
        };

        SQLException failure = assertThrows(SQLException.class,
                () -> new GriefLoggerHistoricalImporter(failingSource, database).importAll());
        assertTrue(failure.getMessage().contains("Historical import failed for table containers"));

        try (PreparedStatement stmt = database.getConnection().prepareStatement(
                "SELECT status, table_count, rows_imported, rows_opaque FROM ig_grieflogger_import_runs ORDER BY id DESC LIMIT 1");
             ResultSet rs = stmt.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("FAILED", rs.getString("status"));
            assertEquals(2, rs.getInt("table_count"));
            assertEquals(501, rs.getInt("rows_imported"));
            assertEquals(501, rs.getInt("rows_opaque"));
        }
        try (Statement stmt = database.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_grieflogger_rows")) {
            assertTrue(rs.next());
            assertEquals(501, rs.getInt(1));
        }
    }

    private static void createFixture(Path path) throws Exception {
        createFixture(path, 18);
    }

    private static void createFixture(Path path, int itemCount) throws Exception {
        createFixture(path, itemCount, null);
    }

    private static void createLongPrimaryKeyFixture(Path path, String primaryKey) throws Exception {
        createLongPrimaryKeyFixture(path, primaryKey, 1);
    }

    private static void createLongPrimaryKeyFixture(Path path, String primaryKey, int itemCount) throws Exception {
        createFixture(path, itemCount, primaryKey);
    }

    private static void createFixture(Path path, int itemCount, String itemPrimaryKey) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())) {
            for (String table : GriefLoggerHistoricalImporter.SOURCE_TABLES) {
                String definition = switch (table) {
                    case "items", "containers" ->
                            (table.equals("items") && itemPrimaryKey != null
                                    ? "id TEXT PRIMARY KEY, " : "id INTEGER PRIMARY KEY, ")
                                    + "time INTEGER, user INTEGER, level INTEGER, "
                                    + "x INTEGER, y INTEGER, z INTEGER, type INTEGER, data BLOB, amount INTEGER, "
                                    + "action INTEGER, payload BLOB, label TEXT";
                    case "users" -> "id INTEGER PRIMARY KEY, name TEXT, uuid TEXT, action INTEGER, payload BLOB, label TEXT";
                    case "levels", "materials" -> "id INTEGER PRIMARY KEY, name TEXT, action INTEGER, payload BLOB, label TEXT";
                    default -> "id INTEGER PRIMARY KEY, action INTEGER, payload BLOB, label TEXT";
                };
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE \"" + table + "\" (" + definition + ")");
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO items (id, action, payload, label) VALUES (?, ?, ?, ?)")) {
                for (int item = 0; item < itemCount; item++) {
                    int action = item % 18;
                    if (itemPrimaryKey != null) {
                        stmt.setString(1, item == 0 ? itemPrimaryKey : "pk-" + (item + 1));
                    } else {
                        stmt.setInt(1, item + 1);
                    }
                    stmt.setInt(2, action);
                    stmt.setBytes(3, new byte[]{(byte) action, 0x01, (byte) 0xff});
                    stmt.setString(4, "action-" + action);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            int id = 100;
            for (String table : GriefLoggerHistoricalImporter.SOURCE_TABLES) {
                if (table.equals("items")) continue;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO \"" + table + "\" (id, action, payload, label) VALUES (?, ?, ?, ?)")) {
                    stmt.setInt(1, id++);
                    stmt.setInt(2, table.equals("commands") ? 99 : 7);
                    stmt.setBytes(3, new byte[]{0x00, 0x01, 0x02});
                    stmt.setString(4, table);
                    stmt.executeUpdate();
                }
            }
        }
    }

    private static void createLookupFixture(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data BLOB, amount INTEGER, action INTEGER, action_id INTEGER)");
            stmt.execute("CREATE TABLE containers (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data BLOB, amount INTEGER, action INTEGER)");
            stmt.execute("CREATE TABLE blocks (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, action INTEGER)");
            stmt.execute("CREATE TABLE sessions (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER)");
            stmt.execute("CREATE TABLE chats (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT)");
            stmt.execute("CREATE TABLE commands (id INTEGER PRIMARY KEY, time INTEGER, user INTEGER, level INTEGER, x INTEGER, y INTEGER, z INTEGER, command TEXT)");
            stmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, uuid TEXT)");
            stmt.execute("CREATE TABLE usernames (id INTEGER PRIMARY KEY, time INTEGER, uuid TEXT, name TEXT)");
            stmt.execute("CREATE TABLE levels (id INTEGER PRIMARY KEY, name TEXT)");
            stmt.execute("CREATE TABLE materials (id INTEGER PRIMARY KEY, name TEXT)");
            stmt.execute("CREATE TABLE entities (id INTEGER PRIMARY KEY, name TEXT)");
            stmt.execute("INSERT INTO users VALUES (1, 'Alice', 'uuid-a')");
            stmt.execute("INSERT INTO usernames VALUES (1, 50, 'uuid-a', 'Alice (old)')");
            stmt.execute("INSERT INTO usernames VALUES (2, 150, 'uuid-a', 'Alice (new)')");
            stmt.execute("INSERT INTO levels VALUES (1, 'minecraft:overworld')");
            stmt.execute("INSERT INTO materials VALUES (1, 'minecraft:diamond_sword')");
            stmt.execute("INSERT INTO entities VALUES (1, 'minecraft:zombie')");
            stmt.execute("INSERT INTO items (id, time, user, level, x, y, z, type, data, amount, action) VALUES (1, 100, 1, 1, 10, 64, 10, 1, NULL, 2, 0)");
            stmt.execute("INSERT INTO containers VALUES (1, 110, 1, 1, 11, 64, 10, 1, NULL, 3, 1)");
            stmt.execute("INSERT INTO blocks VALUES (1, 120, 1, 1, 12, 64, 10, 1, 0)");
            stmt.execute("INSERT INTO sessions VALUES (1, 130, 1, 1, 12, 64, 10, 0)");
            stmt.execute("INSERT INTO chats VALUES (1, 140, 1, 1, 12, 64, 10, 'hello')");
            stmt.execute("INSERT INTO commands VALUES (1, 150, 1, 1, 12, 64, 10, '/itemgraph lookup')");
        }
    }
}
