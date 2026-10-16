package com.itemgraph.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GriefLoggerMigrationTest {
    @Test
    void autoDetectsRunningModAndResolvesTheRealDefaultPath(@TempDir Path dir) {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");

        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                false, true, true, "database.db", gameDir, configDir);

        assertTrue(decision.enabled());
        assertTrue(decision.autoDetected());
        assertEquals(configDir.resolve("grieflogger").resolve("database.db"), decision.databasePath());
    }

    @Test
    void staysDisabledWhenAutoMigrateOffAndIntegrationUnset(@TempDir Path dir) {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");

        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                false, false, true, "database.db", gameDir, configDir);

        assertFalse(decision.enabled());
        assertFalse(decision.autoDetected());
    }

    @Test
    void staysDisabledWhenNoModPresent(@TempDir Path dir) {
        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                false, true, false, "database.db", dir, dir.resolve("config"));

        assertFalse(decision.enabled());
        assertFalse(decision.autoDetected());
    }

    @Test
    void explicitEnablementIsNotReportedAsAutoDetected(@TempDir Path dir) {
        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                true, true, true, "database.db", dir, dir.resolve("config"));

        assertTrue(decision.enabled());
        assertFalse(decision.autoDetected());
    }

    @Test
    void explicitPathAlwaysWinsOverAutodetection(@TempDir Path dir) throws Exception {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");
        Files.createDirectories(gameDir);

        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                true, true, true, "sources/grieflogger.db", gameDir, configDir);

        assertEquals(gameDir.resolve("sources").resolve("grieflogger.db"), decision.databasePath());
    }

    @Test
    void leftoverDatabaseAtTheGriefLoggerLocationIsFoundWithoutTheMod(@TempDir Path dir) throws Exception {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");
        Path leftover = configDir.resolve("grieflogger").resolve("database.db");
        Files.createDirectories(leftover.getParent());
        Files.writeString(leftover, "not a real database");

        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                true, true, false, "database.db", gameDir, configDir);

        assertEquals(leftover, decision.databasePath(),
                "an explicit opt-in must still find the real GriefLogger default location");
    }

    @Test
    void defaultWithoutModOrFileKeepsTheLegacyGameDirPath(@TempDir Path dir) {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");

        GriefLoggerMigration.Decision decision = GriefLoggerMigration.decide(
                true, true, false, "database.db", gameDir, configDir);

        assertEquals(gameDir.resolve("database.db"), decision.databasePath());
    }

    @Test
    void useMysqlIsOnlyReadWhenTheModIsPresent(@TempDir Path dir) throws Exception {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");
        Path serverConfig = configDir.resolve("grieflogger").resolve("grieflogger-server.toml");
        Files.createDirectories(serverConfig.getParent());
        Files.writeString(serverConfig, "database:\n  useMysql: true\nuseMysql = true\n");

        GriefLoggerMigration.Decision withMod = GriefLoggerMigration.decide(
                false, true, true, "database.db", gameDir, configDir);
        GriefLoggerMigration.Decision withoutMod = GriefLoggerMigration.decide(
                false, true, false, "database.db", gameDir, configDir);

        assertTrue(withMod.liveMySql());
        assertFalse(withoutMod.liveMySql());
    }

    @Test
    void blankConfiguredPathIsRejectedBeforeAnyDetection(@TempDir Path dir) {
        Path gameDir = dir.resolve("game");
        Path configDir = dir.resolve("config");

        for (boolean[] flags : new boolean[][]{
                {true, true, true}, {true, true, false}, {false, true, true}, {false, false, true}}) {
            IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> GriefLoggerMigration.decide(flags[0], flags[1], flags[2], "", gameDir, configDir));
            org.junit.jupiter.api.Assertions.assertEquals(
                    "general.grieflogger_database_path must not be blank", failure.getMessage());
        }
    }

    @Test
    void useMysqlFalseOrMissingIsNotLiveMySql(@TempDir Path dir) throws Exception {
        Path configDir = dir.resolve("config");
        assertFalse(GriefLoggerMigration.readsUseMysql(configDir));
        Path serverConfig = configDir.resolve("grieflogger").resolve("grieflogger-server.toml");
        Files.createDirectories(serverConfig.getParent());
        Files.writeString(serverConfig, "useMysql = false\n");
        assertFalse(GriefLoggerMigration.readsUseMysql(configDir));
    }
}
