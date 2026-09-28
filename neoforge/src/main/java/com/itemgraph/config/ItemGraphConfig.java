package com.itemgraph.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ItemGraphConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> DATABASE_PATH;
    public static final ModConfigSpec.ConfigValue<String> GRIEFLOGGER_DATABASE_PATH;
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;
    public static final ModConfigSpec.IntValue GROUND_BRIDGE_MAX_SECONDS;

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

        GRIEFLOGGER_DATABASE_PATH = BUILDER
                .comment("Path to GriefLogger SQLite database relative to game directory, or absolute path")
                .define("grieflogger_database_path", "database.db");

        DEBUG_LOGGING = BUILDER
                .comment("Enable verbose debug logging")
                .define("debug_logging", false);
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
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
