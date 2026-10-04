package com.itemgraph.fabric;

import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.config.ItemGraphOperationalSettings;
import com.itemgraph.i18n.ItemGraphLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Simple editable properties file; Fabric does not provide a server config system. */
record FabricItemGraphConfig(DatabaseSettings databaseSettings, Path griefLoggerDatabasePath,
                             boolean griefLoggerIntegrationEnabled,
                             int groundBridgeMaxSeconds, ItemGraphOperationalSettings operationalSettings,
                             String language) {
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
            properties.setProperty("language", ItemGraphLanguage.DEFAULT_LOCALE);
            properties.setProperty("database_backend", "sqlite");
            properties.setProperty("database_host", "127.0.0.1");
            properties.setProperty("database_port", "3306");
            properties.setProperty("database_name", "itemgraph");
            properties.setProperty("database_username", "itemgraph");
            properties.setProperty("database_password", "");
            properties.setProperty("database_ssl_mode", "disable");
            properties.setProperty("database_connection_timeout_ms", "5000");
            properties.setProperty("use_indexes", "true");
            properties.setProperty("grieflogger_integration_enabled", "false");
            properties.setProperty("grieflogger_database_path", "database.db");
            properties.setProperty("ground_bridge_max_seconds", "300");
            properties.setProperty("max_page_size", Integer.toString(ItemGraphOperationalSettings.DEFAULT_MAX_PAGE_SIZE));
            properties.setProperty("server_side_only", "true");
            properties.setProperty("poll_interval_ms", Integer.toString(ItemGraphOperationalSettings.DEFAULT_QUEUE_POLL_INTERVAL_MS));
            properties.setProperty("queue_frequency_ticks", Integer.toString(ItemGraphOperationalSettings.DEFAULT_QUEUE_FREQUENCY_TICKS));
            properties.setProperty("max_batch_size", Integer.toString(ItemGraphOperationalSettings.DEFAULT_MAX_BATCH_SIZE));
            properties.setProperty("database_heartbeat_interval_ms",
                    Integer.toString(ItemGraphOperationalSettings.DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS));
            properties.setProperty("capture_enabled", "true");
            properties.setProperty("raw_evidence_retention", "indefinite");
            try (OutputStream output = Files.newOutputStream(configFile)) {
                properties.store(output, "ItemGraph server configuration");
            }
        }

        int groundBridgeMaxSeconds = parseBoundedInt(properties, "ground_bridge_max_seconds", 300,
                1, 86_400, configFile);
        String language;
        try {
            language = ItemGraphLanguage.validateLocale(properties.getProperty("language", ItemGraphLanguage.DEFAULT_LOCALE));
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage() + " in " + configFile, invalid);
        }
        int databasePort = parseBoundedInt(properties, "database_port", 3306, 1, 65_535, configFile);
        int databaseConnectionTimeoutMs = parseBoundedInt(properties, "database_connection_timeout_ms",
                5_000, 250, 120_000, configFile);

        ItemGraphOperationalSettings operationalSettings;
        try {
            operationalSettings = new ItemGraphOperationalSettings(
                    parseBoundedInt(properties, "max_page_size", ItemGraphOperationalSettings.DEFAULT_MAX_PAGE_SIZE,
                            1, 100, configFile),
                    parseBoolean(properties, "server_side_only", true, configFile),
                    parseBoundedInt(properties, "poll_interval_ms",
                            ItemGraphOperationalSettings.DEFAULT_QUEUE_POLL_INTERVAL_MS, 10, 5_000, configFile),
                    parseBoundedInt(properties, "queue_frequency_ticks",
                            ItemGraphOperationalSettings.DEFAULT_QUEUE_FREQUENCY_TICKS, 1, 100, configFile),
                    parseBoundedInt(properties, "max_batch_size",
                            ItemGraphOperationalSettings.DEFAULT_MAX_BATCH_SIZE, 1, 1_000, configFile),
                    parseBoundedInt(properties, "database_heartbeat_interval_ms",
                            ItemGraphOperationalSettings.DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS,
                            1_000, 3_600_000, configFile),
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
                        resolve(gameDirectory, properties.getProperty("database_path", "itemgraph/itemgraph.db"),
                                "general.database_path", configFile),
                        parseBoolean(properties, "use_indexes", true, configFile));
            } else if (backend.equals("mysql") || backend.equals("mariadb") || backend.equals("mysql_mariadb")) {
                databaseSettings = DatabaseSettings.mysqlMariaDb(
                        properties.getProperty("database_host", "127.0.0.1"),
                        databasePort,
                        properties.getProperty("database_name", "itemgraph"),
                        properties.getProperty("database_username", "itemgraph"),
                        properties.getProperty("database_password", ""),
                        databaseConnectionTimeoutMs,
                        parseBoolean(properties, "use_indexes", true, configFile),
                        properties.getProperty("database_ssl_mode", "disable"));
            } else {
                throw new IOException("general.database_backend must be sqlite, mysql, mariadb, or mysql_mariadb in "
                        + configFile);
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid database settings in " + configFile + ": " + e.getMessage(), e);
        }

        boolean griefLoggerIntegrationEnabled = parseBoolean(
                properties, "grieflogger_integration_enabled", false, configFile);
        Path griefLoggerDatabasePath = griefLoggerIntegrationEnabled
                ? resolve(gameDirectory, properties.getProperty("grieflogger_database_path", "database.db"),
                        "general.grieflogger_database_path", configFile)
                : gameDirectory.resolve("database.db");

        return new FabricItemGraphConfig(
                databaseSettings,
                griefLoggerDatabasePath,
                griefLoggerIntegrationEnabled,
                groundBridgeMaxSeconds,
                operationalSettings,
                language);
    }

    private static int parseInt(Properties properties, String key, int defaultValue, Path configFile)
            throws IOException {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(defaultValue)).trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer in " + configFile + ": " + key, e);
        }
    }

    private static int parseBoundedInt(Properties properties, String key, int defaultValue,
                                       int minimum, int maximum, Path configFile) throws IOException {
        int value;
        try {
            value = parseInt(properties, key, defaultValue, configFile);
        } catch (IOException e) {
            throw new IOException(configKey(key) + " must be an integer, got: "
                    + properties.getProperty(key) + " in " + configFile, e);
        }
        if (value < minimum || value > maximum) {
            throw new IOException(configKey(key) + " must be in [" + minimum + "," + maximum + "], got: " + value
                    + " in " + configFile);
        }
        return value;
    }

    private static String configKey(String propertyKey) {
        return switch (propertyKey) {
            case "database_port" -> "general.database_port";
            case "database_connection_timeout_ms" -> "general.database_connection_timeout_ms";
            case "use_indexes" -> "storage.use_indexes";
            case "grieflogger_integration_enabled" -> "general.grieflogger_integration_enabled";
            case "ground_bridge_max_seconds" -> "correlation.ground_bridge_max_seconds";
            case "max_page_size" -> "query.max_page_size";
            case "poll_interval_ms" -> "ingestion.poll_interval_ms";
            case "queue_frequency_ticks" -> "ingestion.queue_frequency_ticks";
            case "max_batch_size" -> "ingestion.max_batch_size";
            case "database_heartbeat_interval_ms" -> "operations.database_heartbeat_interval_ms";
            case "server_side_only" -> "operations.server_side_only";
            case "capture_enabled" -> "capture.enabled";
            default -> propertyKey;
        };
    }

    private static boolean parseBoolean(Properties properties, String key, boolean defaultValue, Path configFile)
            throws IOException {
        String value = properties.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IOException(configKey(key) + " must be true or false in " + configFile);
    }

    private static Path resolve(Path gameDirectory, String value, String configKey, Path configFile) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(configKey + " must not be blank in " + configFile);
        }
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : gameDirectory.resolve(path)).normalize();
    }
}
