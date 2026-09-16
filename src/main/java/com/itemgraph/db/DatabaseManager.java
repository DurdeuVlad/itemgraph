package com.itemgraph.db;

import com.itemgraph.config.ItemGraphConfig;
import com.itemgraph.db.migration.MigrationRunner;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);
    private static final DatabaseManager INSTANCE = new DatabaseManager();

    private Connection connection;
    private Path databasePath;
    private int currentSchemaVersion = 0;
    private String lastError = null;
    private boolean initialized = false;

    private DatabaseManager() {}

    public static DatabaseManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        String pathStr = "itemgraph/itemgraph.db";
        try {
            if (ItemGraphConfig.DATABASE_PATH != null) {
                pathStr = ItemGraphConfig.DATABASE_PATH.get();
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read database path from config, using default: {}", pathStr);
        }
        initialize(resolvePath(pathStr));
    }

    public synchronized void initialize(Path path) {
        this.databasePath = path;
        this.lastError = null;
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            Class.forName("org.sqlite.JDBC");

            String url = "jdbc:sqlite:" + path.toAbsolutePath();
            LOGGER.info("Connecting to ItemGraph database at {}", url);
            this.connection = DriverManager.getConnection(url);

            try (Statement stmt = this.connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
                stmt.execute("PRAGMA journal_mode = WAL;");
                stmt.execute("PRAGMA busy_timeout = 5000;");
            }

            this.currentSchemaVersion = MigrationRunner.runMigrations(this.connection);
            this.initialized = true;
            LOGGER.info("ItemGraph database initialized successfully (schema version: {}).", this.currentSchemaVersion);
        } catch (ClassNotFoundException e) {
            this.lastError = "SQLite JDBC driver not found: " + e.getMessage();
            LOGGER.error("Failed to load SQLite JDBC driver", e);
        } catch (SQLException | IOException e) {
            this.lastError = "Database initialization error: " + e.getMessage();
            LOGGER.error("Failed to initialize ItemGraph database at {}", path, e);
        }
    }

    public static Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            try {
                path = FMLPaths.GAMEDIR.get().resolve(path);
            } catch (Throwable ignored) {
                path = Path.of(".").resolve(path);
            }
        }
        return path;
    }

    public synchronized boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized boolean isInitialized() {
        return initialized && isConnected();
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            if (databasePath != null) {
                initialize(databasePath);
            } else {
                initialize();
            }
        }
        return connection;
    }

    /**
     * Opens a short-lived, read-only connection to the same database file, for queries
     * that run off the server thread.
     *
     * <h2>Why not just reuse {@link #getConnection()}</h2>
     *
     * <p>{@link #getConnection()} hands out the one shared connection the ingestion
     * worker writes through, in explicit transactions ({@code setAutoCommit(false)},
     * batch, {@code commit()}). A query issued on another thread against that same
     * connection would execute <em>inside</em> the writer's open transaction and could
     * read rows that are about to be rolled back. For a forensic tool that is not a
     * performance detail: an admin could be shown an observation that never existed.
     *
     * <p>The database runs in WAL mode (set in {@link #initialize(Path)}), so an
     * independent reader sees a consistent committed snapshot and never blocks the
     * writer — which is exactly the property a command-triggered historical query needs.
     *
     * <p>{@code PRAGMA query_only = ON} is set so the read-only intent is enforced by
     * SQLite rather than only asserted in javadoc. Callers must close the returned
     * connection; it is theirs, not the shared one.
     *
     * @throws SQLException if the database has not been initialized yet, or the
     *                      connection could not be opened
     */
    public Connection openReadOnlyConnection() throws SQLException {
        Path path;
        synchronized (this) {
            if (!initialized || databasePath == null) {
                throw new SQLException("ItemGraph database is not initialized"
                        + (lastError != null ? " (" + lastError + ")" : ""));
            }
            path = databasePath;
        }

        Connection readConn = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement stmt = readConn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout = 5000;");
            stmt.execute("PRAGMA query_only = ON;");
        } catch (SQLException e) {
            try {
                readConn.close();
            } catch (SQLException ignored) {
                // The open failure is the interesting one.
            }
            throw e;
        }
        return readConn;
    }

    public synchronized Path getDatabasePath() {
        return databasePath;
    }

    public synchronized int getCurrentSchemaVersion() {
        return currentSchemaVersion;
    }

    public synchronized String getLastError() {
        return lastError;
    }

    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    LOGGER.info("ItemGraph database connection closed.");
                }
            } catch (SQLException e) {
                LOGGER.error("Error closing ItemGraph database connection", e);
            } finally {
                connection = null;
                initialized = false;
            }
        }
    }
}
