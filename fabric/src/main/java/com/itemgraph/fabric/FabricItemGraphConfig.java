package com.itemgraph.fabric;

import com.itemgraph.db.DatabaseSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Simple editable properties file; Fabric does not provide a server config system. */
record FabricItemGraphConfig(DatabaseSettings databaseSettings, Path griefLoggerDatabasePath,
                             int groundBridgeMaxSeconds) {
    static FabricItemGraphConfig load(Path gameDirectory, Path configDirectory) throws IOException {
        Path configFile = configDirectory.resolve("itemgraph.properties");
        Properties properties = new Properties();
        if (Files.exists(configFile)) {
            try (InputStream input = Files.newInputStream(configFile)) {
                properties.load(input);
            }
        } else {
            Files.createDirectories(configDirectory);
            properties.setProperty("database_path", "itemgraph/itemgraph.db");
            properties.setProperty("database_backend", "sqlite");
            properties.setProperty("database_host", "127.0.0.1");
            properties.setProperty("database_port", "3306");
            properties.setProperty("database_name", "itemgraph");
            properties.setProperty("database_username", "itemgraph");
            properties.setProperty("database_password", "");
            properties.setProperty("database_connection_timeout_ms", "5000");
            properties.setProperty("grieflogger_database_path", "database.db");
            properties.setProperty("ground_bridge_max_seconds", "300");
            try (OutputStream output = Files.newOutputStream(configFile)) {
                properties.store(output, "ItemGraph server configuration");
            }
        }

        int groundBridgeMaxSeconds;
        try {
            groundBridgeMaxSeconds = Integer.parseInt(properties.getProperty("ground_bridge_max_seconds", "300"));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer in " + configFile + ": ground_bridge_max_seconds", e);
        }
        if (groundBridgeMaxSeconds < 1 || groundBridgeMaxSeconds > 86_400) {
            throw new IOException("ground_bridge_max_seconds must be in [1, 86400] in " + configFile);
        }

        String backend = properties.getProperty("database_backend", "sqlite").trim()
                .toLowerCase(java.util.Locale.ROOT);
        DatabaseSettings databaseSettings;
        try {
            if (backend.equals("sqlite")) {
                databaseSettings = DatabaseSettings.sqlite(
                        resolve(gameDirectory, properties.getProperty("database_path", "itemgraph/itemgraph.db")));
            } else if (backend.equals("mysql") || backend.equals("mariadb") || backend.equals("mysql_mariadb")) {
                databaseSettings = DatabaseSettings.mysqlMariaDb(
                        properties.getProperty("database_host", "127.0.0.1"),
                        parseInt(properties, "database_port", 3306, configFile),
                        properties.getProperty("database_name", "itemgraph"),
                        properties.getProperty("database_username", "itemgraph"),
                        properties.getProperty("database_password", ""),
                        parseInt(properties, "database_connection_timeout_ms", 5_000, configFile),
                        true);
            } else {
                throw new IOException("database_backend must be sqlite or mysql_mariadb in " + configFile);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid database settings in " + configFile + ": " + e.getMessage(), e);
        }

        return new FabricItemGraphConfig(
                databaseSettings,
                resolve(gameDirectory, properties.getProperty("grieflogger_database_path", "database.db")),
                groundBridgeMaxSeconds);
    }

    private static int parseInt(Properties properties, String key, int defaultValue, Path configFile)
            throws IOException {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)).trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer in " + configFile + ": " + key, e);
        }
    }

    private static Path resolve(Path gameDirectory, String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("ItemGraph database paths must not be blank");
        }
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : gameDirectory.resolve(path)).normalize();
    }
}
