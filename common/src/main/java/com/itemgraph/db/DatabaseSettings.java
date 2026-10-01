package com.itemgraph.db;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable connection settings for ItemGraph's own storage.
 *
 * <p>The GriefLogger source database has a separate read-only adapter and is
 * deliberately not represented by this record.  The {@link Backend#MYSQL_MARIADB}
 * option uses MariaDB Connector/J, which supports both MySQL and MariaDB
 * servers through the same JDBC contract.</p>
 */
public record DatabaseSettings(
        Backend backend,
        Path sqlitePath,
        String host,
        int port,
        String database,
        String username,
        String password,
        int connectionTimeoutMs,
        boolean useIndexes,
        String sslMode) {

    public enum Backend {
        SQLITE,
        MYSQL_MARIADB
    }

    public DatabaseSettings {
        backend = Objects.requireNonNull(backend, "backend");
        if (backend == Backend.SQLITE) {
            sqlitePath = Objects.requireNonNull(sqlitePath, "sqlitePath");
            if (host == null) host = "";
            if (database == null) database = "";
            if (username == null) username = "";
            if (password == null) password = "";
            sslMode = "disable";
        } else {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("general.database_host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("general.database_port must be in [1,65535]");
            }
            if (database == null || database.isBlank()) {
                throw new IllegalArgumentException("general.database_name must not be blank");
            }
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("general.database_username must not be blank");
            }
            if (password == null) password = "";
            sslMode = normalizeSslMode(sslMode);
        }
        if (connectionTimeoutMs < 250 || connectionTimeoutMs > 120_000) {
            throw new IllegalArgumentException("general.database_connection_timeout_ms must be in [250,120000] ms");
        }
    }

    public static DatabaseSettings sqlite(Path path) {
        return sqlite(path, true);
    }

    public static DatabaseSettings sqlite(Path path, boolean useIndexes) {
        return new DatabaseSettings(Backend.SQLITE, path, "", 0, "", "", "", 5_000, useIndexes, "disable");
    }

    public static DatabaseSettings mysqlMariaDb(String host, int port, String database,
                                                String username, String password,
                                                int connectionTimeoutMs, boolean useIndexes) {
        return mysqlMariaDb(host, port, database, username, password, connectionTimeoutMs, useIndexes, "disable");
    }

    public static DatabaseSettings mysqlMariaDb(String host, int port, String database,
                                                String username, String password,
                                                int connectionTimeoutMs, boolean useIndexes,
                                                String sslMode) {
        return new DatabaseSettings(Backend.MYSQL_MARIADB, null, host, port, database,
                username, password, connectionTimeoutMs, useIndexes, sslMode);
    }

    public boolean isNetworkBackend() {
        return backend == Backend.MYSQL_MARIADB;
    }

    private static String normalizeSslMode(String value) {
        String normalized = value == null ? "disable" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("disable") && !normalized.equals("trust")
                && !normalized.equals("verify-ca") && !normalized.equals("verify-full")) {
            throw new IllegalArgumentException(
                    "general.database_ssl_mode must be disable, trust, verify-ca, or verify-full");
        }
        return normalized;
    }

    /**
     * Keeps credentials out of incidental log messages and exception context.
     * The password accessor remains available to the JDBC connection factory.
     */
    @Override
    public String toString() {
        return "DatabaseSettings[backend=" + backend
                + ", sqlitePath=" + sqlitePath
                + ", host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", username=" + username
                + ", password=<redacted>"
                + ", connectionTimeoutMs=" + connectionTimeoutMs
                + ", useIndexes=" + useIndexes
                + ", sslMode=" + sslMode + ']';
    }
}
