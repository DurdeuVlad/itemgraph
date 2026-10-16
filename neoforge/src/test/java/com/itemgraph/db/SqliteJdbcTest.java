package com.itemgraph.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteJdbcTest {
    // initialize() extracts the bundled driver jar into the supplied directory;
    // a loaded jar cannot be deleted on Windows, so tests share a stable build
    // directory instead of JUnit's @TempDir cleanup.
    private static final Path SHARED_LIB_DIR = Path.of("build", "iglibs-test");

    @Test
    void loadsTheDriverAndExecutesAgainstAnInMemoryDatabase() throws Exception {
        SqliteJdbc.initialize(SHARED_LIB_DIR);
        try (Connection conn = SqliteJdbc.connect("jdbc:sqlite::memory:", new Properties());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        assertTrue(SqliteJdbc.isIsolated() || "classpath".equals(SqliteJdbc.status()),
                "driver must come from the isolated bundle or the test classpath, got: "
                        + SqliteJdbc.status());
    }

    @Test
    void isolatedDriverClassDoesNotShareTheModClassloader() throws Exception {
        SqliteJdbc.initialize(SHARED_LIB_DIR);
        if (!SqliteJdbc.isIsolated()) {
            return;
        }
        try (Connection conn = SqliteJdbc.connect("jdbc:sqlite::memory:", new Properties())) {
            ClassLoader connectionLoader = conn.getClass().getClassLoader();
            assertTrue(connectionLoader != SqliteJdbcTest.class.getClassLoader(),
                    "the isolated org.sqlite.* classes must not be the mod classpath classes");
            assertTrue(conn.getClass().getName().startsWith("org.sqlite."));
        }
    }

    @Test
    void readOnlyUriModeRejectsWrites(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("source.db");
        SqliteJdbc.initialize(SHARED_LIB_DIR);
        try (Connection conn = SqliteJdbc.connect("jdbc:sqlite:" + db.toAbsolutePath(), new Properties());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE items (id INTEGER)");
        }
        try (Connection ro = SqliteJdbc.connect(
                "jdbc:sqlite:file:" + db.toAbsolutePath() + "?mode=ro", new Properties());
             Statement stmt = ro.createStatement()) {
            assertThrows(SQLException.class, () -> stmt.execute("INSERT INTO items VALUES (1)"));
        }
        assertTrue(Files.exists(db));
    }
}
