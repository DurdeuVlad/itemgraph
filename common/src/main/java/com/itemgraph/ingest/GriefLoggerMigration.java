package com.itemgraph.ingest;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides how the read-only GriefLogger source activates at server start.
 *
 * <p>Two enable paths exist: the explicit {@code grieflogger_integration_enabled}
 * config flag, and auto-migration when the running GriefLogger mod is detected
 * while {@code grieflogger_auto_migrate} stays enabled (default true). The
 * sentinel {@code grieflogger_database_path} value {@value #LEGACY_DEFAULT_PATH}
 * auto-resolves to GriefLogger's real default
 * {@code config/grieflogger/database.db}; an explicitly configured path is always
 * honored. The source stays strictly read-only in every path.
 */
public final class GriefLoggerMigration {
    public static final String LEGACY_DEFAULT_PATH = "database.db";
    public static final String DEFAULT_SOURCE_RELATIVE = "config/grieflogger/database.db";
    public static final String SERVER_CONFIG_RELATIVE = "config/grieflogger/grieflogger-server.toml";

    private GriefLoggerMigration() {}

    public record Decision(boolean enabled, boolean autoDetected,
                           Path databasePath, boolean liveMySql) {}

    /**
     * @param integrationEnabled explicit config opt-in
     * @param autoMigrateAllowed   {@code grieflogger_auto_migrate} config value
     * @param modDetected          whether the GriefLogger mod is loaded right now
     * @param configuredPath       raw {@code grieflogger_database_path} value
     * @param gameDir              server game directory (resolves relative paths)
     * @param configDir            server config directory
     */
    public static Decision decide(boolean integrationEnabled, boolean autoMigrateAllowed,
                                  boolean modDetected, String configuredPath,
                                  Path gameDir, Path configDir) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("general.grieflogger_database_path must not be blank");
        }
        boolean autoDetected = !integrationEnabled && autoMigrateAllowed && modDetected;
        boolean enabled = integrationEnabled || autoDetected;
        Path databasePath = resolveSourcePath(configuredPath, modDetected, gameDir, configDir);
        boolean liveMySql = modDetected && readsUseMysql(configDir);
        return new Decision(enabled, autoDetected, databasePath, liveMySql);
    }

    /**
     * The default {@value #LEGACY_DEFAULT_PATH} value means "find the GriefLogger
     * database where GriefLogger puts it": {@code config/grieflogger/database.db}.
     * That location is preferred whenever the mod is detected or the file exists
     * (covers servers that kept the database after removing the mod). Otherwise
     * the legacy {@code <gamedir>/database.db} result is preserved. An explicit
     * configured path always wins.
     */
    public static Path resolveSourcePath(String configuredPath, boolean modDetected,
                                         Path gameDir, Path configDir) {
        String raw = configuredPath == null || configuredPath.isBlank()
                ? LEGACY_DEFAULT_PATH : configuredPath.trim();
        Path configRelative = configDir.resolve("grieflogger").resolve("database.db");
        if (raw.equals(LEGACY_DEFAULT_PATH)) {
            if (modDetected || Files.isRegularFile(configRelative)) {
                return configRelative;
            }
            return gameDir.resolve(LEGACY_DEFAULT_PATH);
        }
        Path path = Path.of(raw);
        return path.isAbsolute() ? path : gameDir.resolve(path);
    }

    /**
     * True when the GriefLogger server config declares {@code useMysql = true}:
     * the live source is a MySQL server ItemGraph cannot open read-only from a
     * file path, so any on-disk SQLite file is stale history only.
     */
    public static boolean readsUseMysql(Path configDir) {
        Path serverConfig = configDir.resolve("grieflogger").resolve("grieflogger-server.toml");
        if (!Files.isRegularFile(serverConfig)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(serverConfig)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("[")) {
                    continue;
                }
                if (trimmed.regionMatches(true, 0, "useMysql", 0, 8)
                        && trimmed.substring(8).trim().startsWith("=")
                        && trimmed.substring(trimmed.indexOf('=') + 1).trim()
                                .equalsIgnoreCase("true")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
}
