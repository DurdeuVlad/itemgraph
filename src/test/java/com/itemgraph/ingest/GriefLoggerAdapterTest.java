package com.itemgraph.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GriefLoggerAdapterTest {

    @TempDir
    Path tempDir;

    private Path dbPath;

    @BeforeEach
    void setupDatabase() throws Exception {
        dbPath = tempDir.resolve("grieflogger.db");

        // Create standard GriefLogger schema and seed test data
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE materials (
                    id integer PRIMARY KEY,
                    name text NOT NULL UNIQUE
                );
            """);
            stmt.execute("""
                CREATE TABLE users (
                    id integer PRIMARY KEY,
                    name text NOT NULL,
                    uuid text DEFAULT NULL UNIQUE
                );
            """);
            stmt.execute("""
                CREATE TABLE levels (
                    id integer PRIMARY KEY,
                    name text NOT NULL UNIQUE
                );
            """);
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

            // Seed reference data
            stmt.execute("INSERT INTO users (id, name, uuid) VALUES (1, 'zKampeR', 'f0b5d9d8-2cac-3599-aa08-a61237f4e827');");
            stmt.execute("INSERT INTO levels (id, name) VALUES (1, 'minecraft:overworld');");
            stmt.execute("INSERT INTO materials (id, name) VALUES (1, 'diamond_ore');");

            // Seed items row
            stmt.execute("INSERT INTO items (time, user, level, x, y, z, type, amount, action) " +
                    "VALUES (1789330407684, 1, 1, 1646, 78, -1475, 1, 1, 2);");
        }
    }

    @Test
    void testReadOnlyModeStrictlyEnforced() throws Exception {
        GriefLoggerAdapter adapter = new GriefLoggerAdapter(dbPath);
        assertTrue(adapter.isDatabaseAvailable());

        try (Connection conn = adapter.openReadOnlyConnection();
             Statement stmt = conn.createStatement()) {

            // Any write attempt must fail
            SQLException ex = assertThrows(SQLException.class, () -> {
                stmt.executeUpdate("INSERT INTO items (time, user, level, x, y, z, type, amount, action) " +
                        "VALUES (1000, 1, 1, 0, 0, 0, 1, 1, 1)");
            });

            assertTrue(ex.getMessage().toLowerCase().contains("readonly") ||
                            ex.getMessage().toLowerCase().contains("read-only"),
                    "Expected read-only rejection, got: " + ex.getMessage());

            // Schema modification must also fail
            assertThrows(SQLException.class, () -> {
                stmt.execute("CREATE TABLE test_write (id INT)");
            });
        }
    }

    @Test
    void testFetchItemsEvents() throws Exception {
        GriefLoggerAdapter adapter = new GriefLoggerAdapter(dbPath);
        List<GriefLoggerRawEvent> events = adapter.fetchEvents("items", 0, 100);

        assertEquals(1, events.size());
        GriefLoggerRawEvent event = events.get(0);
        assertEquals(1, event.rowid());
        assertEquals(1789330407684L, event.timestampMs());
        assertEquals("zKampeR", event.userName());
        assertEquals("f0b5d9d8-2cac-3599-aa08-a61237f4e827", event.userUuid());
        assertEquals("minecraft:overworld", event.levelName());
        assertEquals(1646, event.x());
        assertEquals(78, event.y());
        assertEquals(-1475, event.z());
        assertEquals("diamond_ore", event.materialName());
        assertEquals(1, event.amount());
        assertEquals(2, event.actionId()); // DROP_ITEM
    }

    @Test
    void testEmptyContainersTable() throws Exception {
        GriefLoggerAdapter adapter = new GriefLoggerAdapter(dbPath);
        List<GriefLoggerRawEvent> events = adapter.fetchEvents("containers", 0, 100);

        assertTrue(events.isEmpty(), "Containers table should return empty list when no rows present");
        assertEquals(0, adapter.getMaxRowId("containers"));
    }

    @Test
    void testInvalidTableRejected() {
        GriefLoggerAdapter adapter = new GriefLoggerAdapter(dbPath);
        assertThrows(IllegalArgumentException.class, () -> {
            adapter.fetchEvents("users", 0, 10);
        });
    }

    @Test
    void testMissingDatabaseThrowsSQLException() {
        Path nonexistent = tempDir.resolve("missing.db");
        GriefLoggerAdapter adapter = new GriefLoggerAdapter(nonexistent);
        assertFalse(adapter.isDatabaseAvailable());
        assertThrows(SQLException.class, adapter::openReadOnlyConnection);
    }
}
