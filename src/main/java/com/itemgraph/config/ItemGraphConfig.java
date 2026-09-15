package com.itemgraph.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ItemGraphConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DATABASE_PATH;
    public static final ModConfigSpec.ConfigValue<String> GRIEFLOGGER_DATABASE_PATH;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        BUILDER.push("general");
        DATABASE_PATH = BUILDER
                .comment("Path to ItemGraph SQLite database relative to game directory, or absolute path")
                .define("database_path", "itemgraph/itemgraph.db");

        GRIEFLOGGER_DATABASE_PATH = BUILDER
                .comment("Path to GriefLogger SQLite database relative to game directory, or absolute path")
                .define("grieflogger_database_path", "database.db");

        DEBUG_LOGGING = BUILDER
                .comment("Enable verbose debug logging")
                .define("debug_logging", false);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
