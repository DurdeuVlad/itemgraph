package com.itemgraph.config;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

public class ItemGraphConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DATABASE_PATH;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_BACKEND;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_HOST;
    public static final ModConfigSpec.IntValue DATABASE_PORT;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_NAME;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_USERNAME;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_PASSWORD;
    public static final ModConfigSpec.ConfigValue<String> DATABASE_SSL_MODE;
    public static final ModConfigSpec.IntValue DATABASE_CONNECTION_TIMEOUT_MS;
    public static final ModConfigSpec.BooleanValue USE_INDEXES;
    public static final ModConfigSpec.ConfigValue<String> GRIEFLOGGER_DATABASE_PATH;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.IntValue GROUND_BRIDGE_MAX_SECONDS;
    public static final ModConfigSpec.ConfigValue<Integer> MAX_PAGE_SIZE;
    public static final ModConfigSpec.BooleanValue SERVER_SIDE_ONLY;
    public static final ModConfigSpec.ConfigValue<String> RAW_EVIDENCE_RETENTION;
    public static final ModConfigSpec.ConfigValue<Integer> QUEUE_POLL_INTERVAL_MS;
    public static final ModConfigSpec.ConfigValue<Integer> MAX_BATCH_SIZE;
    public static final ModConfigSpec.ConfigValue<Integer> DATABASE_HEARTBEAT_INTERVAL_MS;
    public static final ModConfigSpec.BooleanValue CAPTURE_ENABLED;

    /**
     * Fallback used when the NeoForge config spec is not loaded (unit tests, or a
     * correlation pass that somehow runs before config load).
     */
    public static final int DEFAULT_GROUND_BRIDGE_MAX_SECONDS = 300;

    static {
        BUILDER.push("general");
        DATABASE_PATH = BUILDER
                .comment("Path to ItemGraph SQLite database relative to game directory, or absolute path")
                .define("database_path", "itemgraph/itemgraph.db");

        DATABASE_BACKEND = BUILDER
                .comment("ItemGraph storage backend: sqlite or mysql_mariadb")
                .define("database_backend", "sqlite");
        DATABASE_HOST = BUILDER
                .comment("MySQL/MariaDB host; ignored when database_backend=sqlite")
                .define("database_host", "127.0.0.1");
        DATABASE_PORT = BUILDER
                .comment("MySQL/MariaDB port; ignored when database_backend=sqlite")
                .defineInRange("database_port", 3306, 1, 65_535);
        DATABASE_NAME = BUILDER
                .comment("MySQL/MariaDB database name; ignored when database_backend=sqlite")
                .define("database_name", "itemgraph");
        DATABASE_USERNAME = BUILDER
                .comment("MySQL/MariaDB username; ignored when database_backend=sqlite")
                .define("database_username", "itemgraph");
        DATABASE_PASSWORD = BUILDER
                .comment("MySQL/MariaDB password; ignored when database_backend=sqlite")
                .define("database_password", "");
        DATABASE_SSL_MODE = BUILDER
                .comment("MySQL/MariaDB TLS mode: disable, trust, verify-ca, or verify-full")
                .define("database_ssl_mode", "disable");
        DATABASE_CONNECTION_TIMEOUT_MS = BUILDER
                .comment("Connection and socket timeout for MySQL/MariaDB in milliseconds")
                .defineInRange("database_connection_timeout_ms", 5_000, 250, 120_000);

        GRIEFLOGGER_DATABASE_PATH = BUILDER
                .comment("Path to GriefLogger SQLite database relative to game directory, or absolute path")
                .define("grieflogger_database_path", "database.db");

        DEBUG_LOGGING = BUILDER
                .comment("Enable verbose debug logging")
                .define("debug_logging", false);
        BUILDER.pop();

        BUILDER.push("ingestion");
        QUEUE_POLL_INTERVAL_MS = BUILDER
                .comment("Maximum idle wait between native queue polls in milliseconds; restart after changing")
                .define("poll_interval_ms", ItemGraphOperationalSettings.DEFAULT_QUEUE_POLL_INTERVAL_MS);
        MAX_BATCH_SIZE = BUILDER
                .comment("Maximum native records drained from each queue in one worker batch; restart after changing")
                .define("max_batch_size", ItemGraphOperationalSettings.DEFAULT_MAX_BATCH_SIZE);
        BUILDER.pop();

        BUILDER.push("capture");
        CAPTURE_ENABLED = BUILDER
                .comment("Capture new ItemGraph-native observations and audit events; restart after changing")
                .define("enabled", true);
        BUILDER.pop();

        BUILDER.push("storage");
        USE_INDEXES = BUILDER
                .comment("Create optional non-unique ItemGraph storage indexes for faster lookups; restart after changing")
                .define("use_indexes", true);
        BUILDER.pop();

        BUILDER.push("correlation");
        GROUND_BRIDGE_MAX_SECONDS = BUILDER
                .comment(
                        "Maximum number of seconds between a player dropping an item and another player",
                        "picking it up for ItemGraph to consider the two events as one ground bridge.",
                        "Default 300s (5 minutes): long enough to cover a realistic pickup delay (a player",
                        "walking back for a dropped stack, a death-drop being collected by a teammate),",
                        "short enough that unrelated items of the same type at the same block hours apart",
                        "are never matched. Dropped item entities despawn after 5 minutes of real time by",
                        "default in Minecraft, so a longer window mostly buys false positives. Raising it",
                        "widens the candidate set and therefore lowers confidence on ambiguous matches;",
                        "it does not make matches more certain.")
                .defineInRange("ground_bridge_max_seconds", DEFAULT_GROUND_BRIDGE_MAX_SECONDS, 1, 86_400);
        BUILDER.pop();

        BUILDER.push("query");
        MAX_PAGE_SIZE = BUILDER
                .comment("Maximum rows returned by any ItemGraph query; restart the server after changing")
                .define("max_page_size", ItemGraphOperationalSettings.DEFAULT_MAX_PAGE_SIZE);
        BUILDER.pop();

        BUILDER.push("operations");
        SERVER_SIDE_ONLY = BUILDER
                .comment("ItemGraph only operates on the server; false is rejected at startup")
                .define("server_side_only", true);
        DATABASE_HEARTBEAT_INTERVAL_MS = BUILDER
                .comment("Validate the network database connection at this interval on the ItemGraph worker; restart after changing")
                .define("database_heartbeat_interval_ms",
                        ItemGraphOperationalSettings.DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS);
        BUILDER.pop();

        BUILDER.push("retention");
        RAW_EVIDENCE_RETENTION = BUILDER
                .comment("Raw evidence retention policy. Only 'indefinite' is supported; ItemGraph never purges raw evidence")
                .define("raw_evidence", "indefinite");
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

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
