package com.itemgraph.ingest;

import com.itemgraph.config.ItemGraphConfig;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class GriefLoggerAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GriefLoggerAdapter.class);
    private static final Set<String> ALLOWED_TABLES = Set.of("items", "containers");

    private final Path databasePath;

    public GriefLoggerAdapter() {
        this(resolveConfiguredPath());
    }

    public GriefLoggerAdapter(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath must not be null");
    }

    private static Path resolveConfiguredPath() {
        String pathStr = "database.db";
        try {
            if (ItemGraphConfig.GRIEFLOGGER_DATABASE_PATH != null) {
                pathStr = ItemGraphConfig.GRIEFLOGGER_DATABASE_PATH.get();
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read GriefLogger database path from config, using default: {}", pathStr);
        }
        return DatabaseManager.resolvePath(pathStr);
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public boolean isDatabaseAvailable() {
        return Files.exists(databasePath) && Files.isReadable(databasePath);
    }

    /**
     * Opens a strictly read-only JDBC connection to the GriefLogger database.
     * Enforces read-only via JDBC URI parameter (?mode=ro), SQLiteConfig read-only flag,
     * busy timeout, and PRAGMA query_only = true.
     */
    public Connection openReadOnlyConnection() throws SQLException {
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
