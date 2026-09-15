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
