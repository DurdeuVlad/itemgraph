package com.itemgraph.fabric;

import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.config.ItemGraphOperationalSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Simple editable properties file; Fabric does not provide a server config system. */
record FabricItemGraphConfig(DatabaseSettings databaseSettings, Path griefLoggerDatabasePath,
                             int groundBridgeMaxSeconds, ItemGraphOperationalSettings operationalSettings) {
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
            properties.setProperty("database_ssl_mode", "disable");
            properties.setProperty("database_connection_timeout_ms", "5000");
            properties.setProperty("use_indexes", "true");
            properties.setProperty("grieflogger_database_path", "database.db");
            properties.setProperty("ground_bridge_max_seconds", "300");
            properties.setProperty("max_page_size", Integer.toString(ItemGraphOperationalSettings.DEFAULT_MAX_PAGE_SIZE));
            properties.setProperty("server_side_only", "true");
            properties.setProperty("poll_interval_ms", Integer.toString(ItemGraphOperationalSettings.DEFAULT_QUEUE_POLL_INTERVAL_MS));
            properties.setProperty("max_batch_size", Integer.toString(ItemGraphOperationalSettings.DEFAULT_MAX_BATCH_SIZE));
            properties.setProperty("database_heartbeat_interval_ms",
                    Integer.toString(ItemGraphOperationalSettings.DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS));
            properties.setProperty("capture_enabled", "true");
            properties.setProperty("raw_evidence_retention", "indefinite");
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

        ItemGraphOperationalSettings operationalSettings;
        try {
            operationalSettings = new ItemGraphOperationalSettings(
                    parseInt(properties, "max_page_size", ItemGraphOperationalSettings.DEFAULT_MAX_PAGE_SIZE, configFile),
                    parseBoolean(properties, "server_side_only", true, configFile),
                    parseInt(properties, "poll_interval_ms", ItemGraphOperationalSettings.DEFAULT_QUEUE_POLL_INTERVAL_MS, configFile),
                    parseInt(properties, "max_batch_size", ItemGraphOperationalSettings.DEFAULT_MAX_BATCH_SIZE, configFile),
                    parseInt(properties, "database_heartbeat_interval_ms",
                            ItemGraphOperationalSettings.DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS, configFile),
                    parseBoolean(properties, "capture_enabled", true, configFile),
                    properties.getProperty("raw_evidence_retention", "indefinite"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid ItemGraph operations settings in " + configFile + ": " + e.getMessage(), e);
        }

        String backend = properties.getProperty("database_backend", "sqlite").trim()
                .toLowerCase(java.util.Locale.ROOT);
        DatabaseSettings databaseSettings;
        try {
            if (backend.equals("sqlite")) {
                databaseSettings = DatabaseSettings.sqlite(
                        resolve(gameDirectory, properties.getProperty("database_path", "itemgraph/itemgraph.db")),
                        parseBoolean(properties, "use_indexes", true, configFile));
            } else if (backend.equals("mysql") || backend.equals("mariadb") || backend.equals("mysql_mariadb")) {
                databaseSettings = DatabaseSettings.mysqlMariaDb(
                        properties.getProperty("database_host", "127.0.0.1"),
                        parseInt(properties, "database_port", 3306, configFile),
                        properties.getProperty("database_name", "itemgraph"),
                        properties.getProperty("database_username", "itemgraph"),
                        properties.getProperty("database_password", ""),
                        parseInt(properties, "database_connection_timeout_ms", 5_000, configFile),
                        parseBoolean(properties, "use_indexes", true, configFile),
                        properties.getProperty("database_ssl_mode", "disable"));
            } else {
                throw new IOException("database_backend must be sqlite or mysql_mariadb in " + configFile);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid database settings in " + configFile + ": " + e.getMessage(), e);
        }

        return new FabricItemGraphConfig(
                databaseSettings,
                resolve(gameDirectory, properties.getProperty("grieflogger_database_path", "database.db")),
                groundBridgeMaxSeconds,
                operationalSettings);
    }

    private static int parseInt(Properties properties, String key, int defaultValue, Path configFile)
            throws IOException {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)).trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer in " + configFile + ": " + key, e);
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean defaultValue, Path configFile)
            throws IOException {
        String value = properties.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IOException(key + " must be true or false in " + configFile);
    }

    private static Path resolve(Path gameDirectory, String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("ItemGraph database paths must not be blank");
        }
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : gameDirectory.resolve(path)).normalize();
    }
}
