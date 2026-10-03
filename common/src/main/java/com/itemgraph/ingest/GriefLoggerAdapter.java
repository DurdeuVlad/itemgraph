package com.itemgraph.ingest;

import com.itemgraph.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class GriefLoggerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GriefLoggerAdapter.class);
    private static final Set<String> ALLOWED_TABLES = Set.of("items", "containers");
    private static final java.util.Map<String, Set<String>> REQUIRED_CORE_COLUMNS = java.util.Map.of(
            "items", Set.of("time", "user", "level", "x", "y", "z", "type", "data", "amount", "action"),
            "containers", Set.of("time", "user", "level", "x", "y", "z", "type", "data", "amount", "action"),
            "users", Set.of("id", "name", "uuid"),
            "levels", Set.of("id", "name"),
            "materials", Set.of("id", "name"));

    private final Path databasePath;
    private final boolean integrationEnabled;

    public GriefLoggerAdapter() {
        this(resolveConfiguredPath(), false);
    }

    public GriefLoggerAdapter(Path databasePath) {
        this(databasePath, true);
    }

    public GriefLoggerAdapter(Path databasePath, boolean integrationEnabled) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null");
        this.integrationEnabled = integrationEnabled;
    }

    private static Path resolveConfiguredPath() {
        return DatabaseManager.resolvePath("database.db");
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public boolean isIntegrationEnabled() {
        return integrationEnabled;
    }

    public boolean isDatabaseAvailable() {
        return integrationEnabled && Files.exists(databasePath) && Files.isReadable(databasePath);
    }

    /**
     * Returns true only when the read-only file contains the tables required by
     * the supported GriefLogger schema. A random SQLite file at the configured
     * path must not enter ingestion and produce one warning per table each cycle.
     */
    public boolean isSupportedSchemaAvailable() {
        if (!isDatabaseAvailable()) {
            return false;
        }
        try (Connection conn = openReadOnlyConnection()) {
            for (var required : REQUIRED_CORE_COLUMNS.entrySet()) {
                Set<String> columns = new HashSet<>();
                try (PreparedStatement stmt = conn.prepareStatement(
                        "PRAGMA table_info(" + quoteIdentifier(required.getKey()) + ")");
                     ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        columns.add(rs.getString("name").toLowerCase(java.util.Locale.ROOT));
                    }
                }
                if (!columns.containsAll(required.getValue())) {
                    LOGGER.debug("GriefLogger schema check rejected {}: missing columns {}",
                            required.getKey(), difference(required.getValue(), columns));
                    return false;
                }
            }
            return true;
        } catch (SQLException e) {
            LOGGER.debug("GriefLogger schema check failed for {}: {}", databasePath, e.getMessage());
            return false;
        }
    }

    private static Set<String> difference(Set<String> required, Set<String> actual) {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(actual);
        return missing;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Opens a strictly read-only JDBC connection to the GriefLogger database.
     * Enforces read-only via JDBC URI parameter (?mode=ro), SQLiteConfig read-only flag,
     * busy timeout, and PRAGMA query_only = true.
     */
    public Connection openReadOnlyConnection() throws SQLException {
        if (!integrationEnabled) {
            throw new SQLException("GriefLogger source integration is disabled");
        }
        if (!isDatabaseAvailable()) {
            throw new SQLException("GriefLogger database file not found or not readable: " + databasePath.toAbsolutePath());
        }

        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not available", e);
        }

        String url = "jdbc:sqlite:file:" + databasePath.toAbsolutePath() + "?mode=ro";

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5000);

        Connection conn = DriverManager.getConnection(url, config.toProperties());

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000;");
            stmt.execute("PRAGMA query_only = true;");
        } catch (SQLException e) {
            try {
                conn.close();
            } catch (SQLException ignored) {}
            throw e;
        }

        return conn;
    }

    /**
     * Fetches events from either the 'items' or 'containers' table ordered by rowid ascending.
     *
     * @param tableName "items" or "containers"
     * @param afterRowId fetch rows strictly after this rowid
     * @param limit maximum rows to return
     * @return list of GriefLoggerRawEvent
     */
    public List<GriefLoggerRawEvent> fetchEvents(String tableName, long afterRowId, int limit) throws SQLException {
        if (!ALLOWED_TABLES.contains(tableName)) {
            throw new IllegalArgumentException("Invalid GriefLogger table name: " + tableName + ". Must be 'items' or 'containers'.");
        }

        String sql = """
            SELECT t.rowid, t.time, u.name AS user_name, u.uuid AS user_uuid, l.name AS level_name,
                   t.x, t.y, t.z, m.name AS material_name, t.data, t.amount, t.action
            FROM %s t
            LEFT JOIN users u ON t.user = u.id
            LEFT JOIN levels l ON t.level = l.id
            LEFT JOIN materials m ON t.type = m.id
            WHERE t.rowid > ?
            ORDER BY t.rowid ASC
            LIMIT ?
        """.formatted(tableName);

        List<GriefLoggerRawEvent> events = new ArrayList<>();

        try (Connection conn = openReadOnlyConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, afterRowId);
            pstmt.setInt(2, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    long rowid = rs.getLong("rowid");
                    long timestamp = rs.getLong("time");
                    String userName = rs.getString("user_name");
                    String userUuid = rs.getString("user_uuid");
                    String levelName = rs.getString("level_name");
                    double x = rs.getDouble("x");
                    double y = rs.getDouble("y");
                    double z = rs.getDouble("z");
                    String materialName = rs.getString("material_name");
                    byte[] data = rs.getBytes("data");
                    int amount = rs.getInt("amount");
                    int action = rs.getInt("action");

                    events.add(new GriefLoggerRawEvent(
                            rowid,
                            timestamp,
                            userName,
                            userUuid,
                            levelName,
                            x,
                            y,
                            z,
                            materialName,
                            data,
                            amount,
                            action
                    ));
                }
            }
        }

        return events;
    }

    /**
     * Gets the current max rowid from the given table, or 0 if empty.
     */
    public long getMaxRowId(String tableName) throws SQLException {
        if (!ALLOWED_TABLES.contains(tableName)) {
            throw new IllegalArgumentException("Invalid GriefLogger table name: " + tableName);
        }

        String sql = "SELECT MAX(rowid) FROM " + tableName;
        try (Connection conn = openReadOnlyConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }
}
