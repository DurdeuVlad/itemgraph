package com.itemgraph.db;

import com.itemgraph.db.migration.MigrationRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseManager.class);
    private static final DatabaseManager INSTANCE = new DatabaseManager();

    private Connection connection;
    private Path databasePath;
    private DatabaseSettings settings;
    private DatabaseDialect dialect = DatabaseDialect.SQLITE;
    private int currentSchemaVersion = 0;
    private String lastError = null;
    private boolean initialized = false;

    private DatabaseManager() {}

    public static DatabaseManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initialize() {
        initialize(DatabaseSettings.sqlite(resolvePath("itemgraph/itemgraph.db")));
    }

    public synchronized void initialize(Path path) {
        initialize(DatabaseSettings.sqlite(path));
    }

    /** Initializes ItemGraph-owned storage using the selected backend. */
    public synchronized void initialize(DatabaseSettings requestedSettings) {
        if (requestedSettings == null) {
            throw new IllegalArgumentException("database settings must not be null");
        }
        close();
        this.settings = requestedSettings;
        this.dialect = DatabaseDialect.fromSettings(requestedSettings);
        this.databasePath = requestedSettings.sqlitePath();
        this.lastError = null;
        try {
            Connection raw;
            if (requestedSettings.backend() == DatabaseSettings.Backend.SQLITE) {
                Path path = requestedSettings.sqlitePath();
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Class.forName("org.sqlite.JDBC");
                String url = "jdbc:sqlite:" + path.toAbsolutePath();
                LOGGER.info("Connecting to ItemGraph SQLite database at {}", path.toAbsolutePath());
                raw = DriverManager.getConnection(url);
                try (Statement stmt = raw.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                    stmt.execute("PRAGMA journal_mode = WAL;");
                    stmt.execute("PRAGMA busy_timeout = " + requestedSettings.connectionTimeoutMs() + ";");
                }
            } else {
                Class.forName("org.mariadb.jdbc.Driver");
                String url = "jdbc:mariadb://" + requestedSettings.host() + ":"
                        + requestedSettings.port() + "/" + requestedSettings.database()
                        + "?connectTimeout=" + requestedSettings.connectionTimeoutMs()
                        + "&socketTimeout=" + requestedSettings.connectionTimeoutMs()
                        + "&allowPublicKeyRetrieval=true";
                Properties properties = new Properties();
                properties.setProperty("user", requestedSettings.username());
                properties.setProperty("password", requestedSettings.password());
                LOGGER.info("Connecting to ItemGraph {} database at {}:{}/{}",
                        requestedSettings.backend(), requestedSettings.host(),
                        requestedSettings.port(), requestedSettings.database());
                raw = DriverManager.getConnection(url, properties);
                raw.setReadOnly(false);
            }
            this.connection = DialectConnection.wrap(raw, this.dialect);
            this.currentSchemaVersion = MigrationRunner.runMigrations(this.connection, this.dialect);
            this.initialized = true;
            LOGGER.info("ItemGraph database initialized successfully (schema version: {}).", this.currentSchemaVersion);
        } catch (ClassNotFoundException e) {
            this.lastError = "JDBC driver not found for backend " + requestedSettings.backend();
            LOGGER.error("Failed to load JDBC driver for ItemGraph backend {}", requestedSettings.backend(), e);
        } catch (SQLException | IOException e) {
            this.lastError = "Database initialization error: " + e.getMessage();
            LOGGER.error("Failed to initialize ItemGraph {} database", requestedSettings.backend(), e);
        }
    }

    public static Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = Path.of(".").resolve(path);
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
            if (settings != null) {
                initialize(settings);
            } else {
                initialize();
            }
        }
        return connection;
    }

    /**
     * Opens a short-lived, read-only connection to the configured ItemGraph backend,
     * for queries that run off the server thread.
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
     * <p>SQLite runs in WAL mode and network backends use independent sessions, so an
     * independent reader sees committed data without borrowing the ingestion handle.
     *
     * <p>{@code PRAGMA query_only = ON} enforces the read-only intent for SQLite;
     * MySQL/MariaDB receives JDBC read-only mode on the independent session. Callers
     * must close the returned connection; it is theirs, not the shared one.
     *
     * @throws SQLException if the database has not been initialized yet, or the
     *                      connection could not be opened
     */
    public Connection openReadOnlyConnection() throws SQLException {
        DatabaseSettings current;
        synchronized (this) {
            if (!initialized || settings == null) {
                throw new SQLException("ItemGraph database is not initialized"
                        + (lastError != null ? " (" + lastError + ")" : ""));
            }
            current = settings;
        }
        Connection readConn = openIndependentConnection(current, true);
        return DialectConnection.wrap(readConn, DatabaseDialect.fromSettings(current));
    }

    /**
     * Opens an independent writer connection to the initialized ItemGraph database.
     *
     * <p>The normal {@link #getConnection()} handle is shared by live observation
     * persistence and is deliberately serialized by {@code InternalObservationService}.
     * Long-running maintenance jobs must use this handle instead so they can commit
     * bounded batches without holding the live writer's monitor or delaying evidence
     * queue draining. SQLite WAL mode permits the two writers to make progress; the
     * busy timeout provides bounded lock contention handling.</p>
     *
     * <p>The caller owns the returned connection and must close it.</p>
     */
    public Connection openWriteConnection() throws SQLException {
        DatabaseSettings current;
        synchronized (this) {
            if (!initialized || settings == null) {
                throw new SQLException("ItemGraph database is not initialized"
                        + (lastError != null ? " (" + lastError + ")" : ""));
            }
            current = settings;
        }
        Connection writeConn = openIndependentConnection(current, false);
        return DialectConnection.wrap(writeConn, DatabaseDialect.fromSettings(current));
    }

    private static Connection openIndependentConnection(DatabaseSettings current, boolean readOnly)
            throws SQLException {
        if (current.backend() == DatabaseSettings.Backend.SQLITE) {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + current.sqlitePath().toAbsolutePath());
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA busy_timeout = " + current.connectionTimeoutMs() + ";");
                if (readOnly) {
                    stmt.execute("PRAGMA query_only = ON;");
                } else {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                }
            }
            return connection;
        }
        String url = "jdbc:mariadb://" + current.host() + ":" + current.port() + "/" + current.database()
                + "?connectTimeout=" + current.connectionTimeoutMs()
                + "&socketTimeout=" + current.connectionTimeoutMs()
                + "&allowPublicKeyRetrieval=true";
        Properties properties = new Properties();
        properties.setProperty("user", current.username());
        properties.setProperty("password", current.password());
        Connection connection = DriverManager.getConnection(url, properties);
        connection.setReadOnly(readOnly);
        return connection;
    }

    public synchronized Path getDatabasePath() {
        return databasePath;
    }

    public synchronized DatabaseSettings getSettings() {
        return settings;
    }

    public synchronized DatabaseDialect getDialect() {
        return dialect;
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
