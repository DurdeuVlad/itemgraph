package com.itemgraph.ingest;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static void createFixture(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath())) {
            for (String table : GriefLoggerHistoricalImporter.SOURCE_TABLES) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE \"" + table + "\" (id INTEGER PRIMARY KEY, action INTEGER, payload BLOB, label TEXT)");
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO items (id, action, payload, label) VALUES (?, ?, ?, ?)")) {
                for (int action = 0; action <= 17; action++) {
                    stmt.setInt(1, action + 1);
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
}
