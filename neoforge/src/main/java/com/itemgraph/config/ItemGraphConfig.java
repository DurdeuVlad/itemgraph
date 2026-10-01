package com.itemgraph.config;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

public class ItemGraphConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DATABASE_PATH;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_BACKEND;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_HOST;
    public static final ModConfigSpec.ConfigValue<Integer> DATABASE_PORT;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_NAME;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_USERNAME;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_PASSWORD;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_SSL_MODE;
    public static final ModConfigSpec.ConfigValue<Integer> DATABASE_CONNECTION_TIMEOUT_MS;
    public static final ModConfigSpec.ConfigValue<Boolean> USE_INDEXES;
    public static final ModConfigSpec.ConfigValue<String> GRIEFLOGGER_DATABASE_PATH;
    public static final ModConfigSpec.ConfigValue<Boolean> DEBUG_LOGGING;
    public static final ModConfigSpec.ConfigValue<Integer> GROUND_BRIDGE_MAX_SECONDS;
    public static final ModConfigSpec.ConfigValue<Integer> MAX_PAGE_SIZE;
    public static final ModConfigSpec.ConfigValue<Boolean> SERVER_SIDE_ONLY;
    public static final ModConfigSpec.ConfigValue<String> RAW_EVIDENCE_RETENTION;
    public static final ModConfigSpec.ConfigValue<Integer> QUEUE_POLL_INTERVAL_MS;
    public static final ModConfigSpec.ConfigValue<Integer> MAX_BATCH_SIZE;
    public static final ModConfigSpec.ConfigValue<Integer> DATABASE_HEARTBEAT_INTERVAL_MS;
    public static final ModConfigSpec.ConfigValue<Boolean> CAPTURE_ENABLED;

    /**
     * Fallback used when the NeoForge config spec is not loaded (unit tests, or a
     * correlation pass that somehow runs before config load).
     */
    public static final int DEFAULT_GROUND_BRIDGE_MAX_SECONDS = 300;

    static {
        BUILDER.push("general");
        DATABASE_PATH = defineString(
                BUILDER.comment("Path to ItemGraph SQLite database relative to game directory, or absolute path"),
                "database_path", "general.database_path", "itemgraph/itemgraph.db");

        DATABASE_BACKEND = defineString(BUILDER.comment("ItemGraph storage backend: sqlite or mysql_mariadb"),
                "database_backend", "general.database_backend", "sqlite");
        DATABASE_HOST = defineString(BUILDER.comment("MySQL/MariaDB host; ignored when database_backend=sqlite"),
                "database_host", "general.database_host", "127.0.0.1");
        DATABASE_PORT = defineInteger(BUILDER.comment("MySQL/MariaDB port; ignored when database_backend=sqlite"),
                "database_port", "general.database_port", 3306, 1, 65_535);
        DATABASE_NAME = defineString(BUILDER.comment("MySQL/MariaDB database name; ignored when database_backend=sqlite"),
                "database_name", "general.database_name", "itemgraph");
        DATABASE_USERNAME = defineString(BUILDER.comment("MySQL/MariaDB username; ignored when database_backend=sqlite"),
                "database_username", "general.database_username", "itemgraph");
        DATABASE_PASSWORD = defineString(BUILDER.comment("MySQL/MariaDB password; ignored when database_backend=sqlite"),
                "database_password", "general.database_password", "");
        DATABASE_SSL_MODE = defineString(
                BUILDER.comment("MySQL/MariaDB TLS mode: disable, trust, verify-ca, or verify-full"),
                "database_ssl_mode", "general.database_ssl_mode", "disable");
        DATABASE_CONNECTION_TIMEOUT_MS = defineInteger(
                BUILDER.comment("Connection and socket timeout for MySQL/MariaDB in milliseconds"),
                "database_connection_timeout_ms", "general.database_connection_timeout_ms", 5_000, 250, 120_000);

        GRIEFLOGGER_DATABASE_PATH = defineString(
                BUILDER.comment("Path to GriefLogger SQLite database relative to game directory, or absolute path"),
                "grieflogger_database_path", "general.grieflogger_database_path", "database.db");

        DEBUG_LOGGING = defineBoolean(BUILDER.comment("Enable verbose debug logging"),
                "debug_logging", "general.debug_logging", false);
        BUILDER.pop();

        BUILDER.push("ingestion");
        QUEUE_POLL_INTERVAL_MS = defineInteger(
                BUILDER.comment("Maximum idle wait between native queue polls in milliseconds; restart after changing"),
                "poll_interval_ms", "ingestion.poll_interval_ms",
                ItemGraphOperationalSettings.DEFAULT_QUEUE_POLL_INTERVAL_MS, 10, 5_000);
        MAX_BATCH_SIZE = defineInteger(
                BUILDER.comment("Maximum native records drained from each queue in one worker batch; restart after changing"),
                "max_batch_size", "ingestion.max_batch_size",
                ItemGraphOperationalSettings.DEFAULT_MAX_BATCH_SIZE, 1, 1_000);
        BUILDER.pop();

        BUILDER.push("capture");
        CAPTURE_ENABLED = defineBoolean(
                BUILDER.comment("Capture new ItemGraph-native observations and audit events; restart after changing"),
                "enabled", "capture.enabled", true);
        BUILDER.pop();

        BUILDER.push("storage");
        USE_INDEXES = defineBoolean(
                BUILDER.comment("Create optional non-unique ItemGraph storage indexes for faster lookups; restart after changing"),
                "use_indexes", "storage.use_indexes", true);
        BUILDER.pop();

        BUILDER.push("correlation");
        GROUND_BRIDGE_MAX_SECONDS = defineInteger(BUILDER.comment(
                        "Maximum number of seconds between a player dropping an item and another player",
                        "picking it up for ItemGraph to consider the two events as one ground bridge.",
                        "Default 300s (5 minutes): long enough to cover a realistic pickup delay (a player",
                        "walking back for a dropped stack, a death-drop being collected by a teammate),",
                        "short enough that unrelated items of the same type at the same block hours apart",
                        "are never matched. Dropped item entities despawn after 5 minutes of real time by",
                        "default in Minecraft, so a longer window mostly buys false positives. Raising it",
                        "widens the candidate set and therefore lowers confidence on ambiguous matches;",
                        "it does not make matches more certain."),
                "ground_bridge_max_seconds", "correlation.ground_bridge_max_seconds",
                DEFAULT_GROUND_BRIDGE_MAX_SECONDS, 1, 86_400);
        BUILDER.pop();

        BUILDER.push("query");
        MAX_PAGE_SIZE = defineInteger(
                BUILDER.comment("Maximum rows returned by any ItemGraph query; restart the server after changing"),
                "max_page_size", "query.max_page_size", ItemGraphOperationalSettings.DEFAULT_MAX_PAGE_SIZE, 1, 100);
        BUILDER.pop();

        BUILDER.push("operations");
        SERVER_SIDE_ONLY = defineBoolean(
                BUILDER.comment("ItemGraph only operates on the server; false is rejected at startup"),
                "server_side_only", "operations.server_side_only", true);
        DATABASE_HEARTBEAT_INTERVAL_MS = defineInteger(BUILDER.comment(
                "Validate the network database connection at this interval on the ItemGraph worker; restart after changing"),
                "database_heartbeat_interval_ms", "operations.database_heartbeat_interval_ms",
                ItemGraphOperationalSettings.DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS, 1_000, 3_600_000);
        BUILDER.pop();

        BUILDER.push("retention");
        RAW_EVIDENCE_RETENTION = defineString(BUILDER.comment(
                "Raw evidence retention policy. Only 'indefinite' is supported; ItemGraph never purges raw evidence"),
                "raw_evidence", "retention.raw_evidence", "indefinite");
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private static ModConfigSpec.ConfigValue<String> defineString(ModConfigSpec.Builder builder, String path,
            String key, String defaultValue) {
        return builder.define(java.util.List.of(path), () -> defaultValue, value -> {
            if (value == null) {
                return false;
            }
            if (!(value instanceof String)) {
                throw invalidType(key, "string", value);
            }
            return true;
        }, String.class);
    }

    private static ModConfigSpec.ConfigValue<Boolean> defineBoolean(ModConfigSpec.Builder builder, String path,
            String key, boolean defaultValue) {
        return builder.define(java.util.List.of(path), () -> defaultValue, value -> {
            if (value == null) {
                return false;
            }
            if (!(value instanceof Boolean)) {
                throw invalidType(key, "boolean", value);
            }
            return true;
        }, Boolean.class);
    }

    private static ModConfigSpec.ConfigValue<Integer> defineInteger(ModConfigSpec.Builder builder, String path,
            String key, int defaultValue, int minimum, int maximum) {
        return builder.define(java.util.List.of(path), () -> defaultValue, value -> {
            if (value == null) {
                return false;
            }
            if (!(value instanceof Integer integer)) {
                throw invalidType(key, "integer", value);
            }
            if (integer < minimum || integer > maximum) {
                throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum
                        + ", got: " + integer);
            }
            return true;
        }, Integer.class);
    }

    private static IllegalArgumentException invalidType(String key, String expected, Object actual) {
        return new IllegalArgumentException(key + " must be a " + expected + ", got: "
                + (actual == null ? "null" : actual.getClass().getSimpleName()));
    }

    /** Builds validated storage settings without exposing the password in logs. */
    public static DatabaseSettings databaseSettings() {
        String backend = DATABASE_BACKEND.get().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (backend) {
            case "sqlite" -> DatabaseSettings.sqlite(DatabaseManager.resolvePath(DATABASE_PATH.get()), USE_INDEXES.get());
            case "mysql", "mariadb", "mysql_mariadb" -> DatabaseSettings.mysqlMariaDb(
                    DATABASE_HOST.get(), DATABASE_PORT.get(), DATABASE_NAME.get(),
                    DATABASE_USERNAME.get(), DATABASE_PASSWORD.get(),
                    DATABASE_CONNECTION_TIMEOUT_MS.get(), USE_INDEXES.get(), DATABASE_SSL_MODE.get());
            default -> throw new IllegalArgumentException(
                    "database_backend must be sqlite or mysql_mariadb, got: " + DATABASE_BACKEND.get());
        };
    }

    /** Validates controls whose invalid values must fail startup rather than be clamped. */
    public static ItemGraphOperationalSettings operationalSettings() {
        return new ItemGraphOperationalSettings(MAX_PAGE_SIZE.get(), SERVER_SIDE_ONLY.get(),
                QUEUE_POLL_INTERVAL_MS.get(), MAX_BATCH_SIZE.get(), DATABASE_HEARTBEAT_INTERVAL_MS.get(), CAPTURE_ENABLED.get(),
                RAW_EVIDENCE_RETENTION.get());
    }
}
