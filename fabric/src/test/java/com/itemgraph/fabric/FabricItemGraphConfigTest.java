package com.itemgraph.fabric;

import com.itemgraph.db.DatabaseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FabricItemGraphConfigTest {
    @Test
    void loadsNetworkStorageSettingsWithoutLoggingOrRewritingPassword(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("itemgraph.properties"), """
                database_backend=mysql_mariadb
                database_host=db.example.test
                database_port=3307
                database_name=itemgraph_test
                database_username=ig_user
                database_password=secret-value
                database_ssl_mode=verify-full
                database_connection_timeout_ms=7500
                use_indexes=false
                grieflogger_integration_enabled=true
                grieflogger_database_path=database.db
                ground_bridge_max_seconds=300
                max_page_size=25
                server_side_only=true
                poll_interval_ms=100
                queue_frequency_ticks=35
                max_batch_size=500
                database_heartbeat_interval_ms=45000
                capture_enabled=false
                raw_evidence_retention=indefinite
                """);

        FabricItemGraphConfig loaded = FabricItemGraphConfig.load(tempDir, config);

        assertEquals(DatabaseSettings.Backend.MYSQL_MARIADB, loaded.databaseSettings().backend());
        assertEquals("db.example.test", loaded.databaseSettings().host());
        assertEquals(3307, loaded.databaseSettings().port());
        assertEquals("itemgraph_test", loaded.databaseSettings().database());
        assertEquals("ig_user", loaded.databaseSettings().username());
        assertEquals("secret-value", loaded.databaseSettings().password());
        assertEquals(7500, loaded.databaseSettings().connectionTimeoutMs());
        assertEquals("verify-full", loaded.databaseSettings().sslMode());
        assertFalse(loaded.databaseSettings().useIndexes());
        assertTrue(loaded.griefLoggerIntegrationEnabled());
        assertEquals(25, loaded.operationalSettings().maxPageSize());
        assertTrue(loaded.operationalSettings().serverSideOnly());
        assertEquals(100, loaded.operationalSettings().queuePollIntervalMs());
        assertEquals(35, loaded.operationalSettings().queueFrequencyTicks());
        assertEquals(500, loaded.operationalSettings().maxBatchSize());
        assertEquals(45_000, loaded.operationalSettings().databaseHeartbeatIntervalMs());
        assertFalse(loaded.operationalSettings().captureEnabled());
        assertEquals("indefinite", loaded.operationalSettings().rawEvidenceRetention());
        assertTrue(Files.exists(config.resolve("itemgraph.properties")));
    }

    @Test
    void defaultsOperationalControlsToTenRowsServerOnlyAndIndefiniteRetention(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        FabricItemGraphConfig loaded = FabricItemGraphConfig.load(tempDir, config);

        assertEquals(10, loaded.operationalSettings().maxPageSize());
        assertTrue(loaded.operationalSettings().serverSideOnly());
        assertEquals(250, loaded.operationalSettings().queuePollIntervalMs());
        assertEquals(20, loaded.operationalSettings().queueFrequencyTicks());
        assertEquals(100, loaded.operationalSettings().maxBatchSize());
        assertEquals(30_000, loaded.operationalSettings().databaseHeartbeatIntervalMs());
        assertTrue(loaded.operationalSettings().captureEnabled());
        assertFalse(loaded.griefLoggerIntegrationEnabled());
        assertEquals("indefinite", loaded.operationalSettings().rawEvidenceRetention());
    }

    @Test
    void configChangesTakeEffectWhenReloadedForTheNextServerStart(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("itemgraph.properties");
        Files.writeString(file, "max_page_size=10\npoll_interval_ms=250\nqueue_frequency_ticks=20\n");

        FabricItemGraphConfig runningServerSnapshot = FabricItemGraphConfig.load(tempDir, config);
        Files.writeString(file, "max_page_size=25\npoll_interval_ms=500\nqueue_frequency_ticks=35\n");

        assertEquals(10, runningServerSnapshot.operationalSettings().maxPageSize(),
                "editing the file must not mutate the config snapshot already used by the running server");
        assertEquals(250, runningServerSnapshot.operationalSettings().queuePollIntervalMs());
        FabricItemGraphConfig restartedServerConfig = FabricItemGraphConfig.load(tempDir, config);
        assertEquals(25, restartedServerConfig.operationalSettings().maxPageSize());
        assertEquals(500, restartedServerConfig.operationalSettings().queuePollIntervalMs());
        assertEquals(20, runningServerSnapshot.operationalSettings().queueFrequencyTicks());
        assertEquals(35, restartedServerConfig.operationalSettings().queueFrequencyTicks());
    }

    @Test
    void rejectsOutOfRangePageSizeUnsupportedClientModeAndFiniteRetention(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);

        Files.writeString(config.resolve("itemgraph.properties"), "max_page_size=101\n");
        IOException pageSizeError = assertThrows(IOException.class,
                () -> FabricItemGraphConfig.load(tempDir, config));
        assertTrue(pageSizeError.getMessage().contains("query.max_page_size must be in [1,100]"));

        Files.writeString(config.resolve("itemgraph.properties"), "server_side_only=false\n");
        IOException serverModeError = assertThrows(IOException.class,
                () -> FabricItemGraphConfig.load(tempDir, config));
        assertTrue(serverModeError.getMessage().contains("operations.server_side_only=false is unsupported"));

        Files.writeString(config.resolve("itemgraph.properties"), "raw_evidence_retention=30d\n");
        IOException retentionError = assertThrows(IOException.class,
                () -> FabricItemGraphConfig.load(tempDir, config));
        assertTrue(retentionError.getMessage().contains("retention.raw_evidence must be 'indefinite'"));
    }

    @Test
    void rejectsMalformedIndexPolicyInsteadOfSilentlyTreatingItAsFalse(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("itemgraph.properties"), "use_indexes=enabled\n");

        IOException error = assertThrows(IOException.class, () -> FabricItemGraphConfig.load(tempDir, config));
        assertTrue(error.getMessage().contains("storage.use_indexes must be true or false"));
    }

    @Test
    void rejectsBlankDatabasePathsWithTheirConfigKeys(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("itemgraph.properties");

        Files.writeString(file, "database_path=\n");
        IOException databasePathError = assertThrows(IOException.class,
                () -> FabricItemGraphConfig.load(tempDir, config));
        assertTrue(databasePathError.getMessage().contains("general.database_path must not be blank"));

        Files.writeString(file, "grieflogger_database_path=\n");
        FabricItemGraphConfig nativeOnly = FabricItemGraphConfig.load(tempDir, config);
        assertFalse(nativeOnly.griefLoggerIntegrationEnabled());
        assertEquals(tempDir.resolve("database.db"), nativeOnly.griefLoggerDatabasePath());

        Files.writeString(file, "grieflogger_database_path=\\u0000-invalid-path\n");
        FabricItemGraphConfig invalidSourcePathIgnored = FabricItemGraphConfig.load(tempDir, config);
        assertEquals(tempDir.resolve("database.db"), invalidSourcePathIgnored.griefLoggerDatabasePath());

        Files.writeString(file, "grieflogger_integration_enabled=true\ngrieflogger_database_path=\n");
        IOException griefLoggerPathError = assertThrows(IOException.class,
                () -> FabricItemGraphConfig.load(tempDir, config));
        assertTrue(griefLoggerPathError.getMessage().contains("general.grieflogger_database_path must not be blank"));
    }

    @Test
    void rejectsInvalidNetworkDatabaseSettingsWithTheirConfigKeys(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("itemgraph.properties");
        List<InvalidSetting> invalidSettings = List.of(
                new InvalidSetting("database_backend=jdbc:mysql://user:backend-secret@db/test\n",
                        "general.database_backend", "must be sqlite, mysql, mariadb, or mysql_mariadb"),
                new InvalidSetting("database_backend=mysql_mariadb\ndatabase_host=   \n",
                        "general.database_host", "must not be blank"),
                new InvalidSetting("database_backend=mysql_mariadb\ndatabase_name=\n",
                        "general.database_name", "must not be blank"),
                new InvalidSetting("database_backend=mysql_mariadb\ndatabase_username=\n",
                        "general.database_username", "must not be blank"),
                new InvalidSetting("database_backend=mysql_mariadb\ndatabase_ssl_mode=invalid\n",
                        "general.database_ssl_mode", "must be disable, trust, verify-ca, or verify-full"));

        for (InvalidSetting invalid : invalidSettings) {
            Files.writeString(file, "database_password=secret-value\n" + invalid.properties());
            IOException error = assertThrows(IOException.class,
                    () -> FabricItemGraphConfig.load(tempDir, config), invalid.key());
            assertTrue(error.getMessage().contains(invalid.key()), error.getMessage());
            assertTrue(error.getMessage().contains(invalid.expectedDetail()), error.getMessage());
            assertFalse(error.getMessage().contains("secret-value"), "database errors must not expose passwords");
            assertFalse(error.getMessage().contains("backend-secret"), "database errors must not expose backend text");
        }
    }

    @Test
    void rejectsMalformedBooleanSettingsWithTheirConfigKeys(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("itemgraph.properties");
        List<InvalidSetting> invalidSettings = List.of(
                new InvalidSetting("server_side_only=1\n", "operations.server_side_only", "must be true or false"),
                new InvalidSetting("capture_enabled=yes\n", "capture.enabled", "must be true or false"),
                new InvalidSetting("grieflogger_integration_enabled=enabled\n",
                        "general.grieflogger_integration_enabled", "must be true or false"));

        for (InvalidSetting invalid : invalidSettings) {
            Files.writeString(file, invalid.properties());
            IOException error = assertThrows(IOException.class,
                    () -> FabricItemGraphConfig.load(tempDir, config), invalid.key());
            assertTrue(error.getMessage().contains(invalid.key()), error.getMessage());
            assertTrue(error.getMessage().contains(invalid.expectedDetail()), error.getMessage());
        }
    }

    private record InvalidSetting(String properties, String key, String expectedDetail) { }

    @Test
    void validatesEveryNumericConfigRangeBeforeApplyingBackendSpecificSettings(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("itemgraph.properties");

        List<NumericRange> ranges = List.of(
                new NumericRange("database_port", "general.database_port", 1, 65_535),
                new NumericRange("database_connection_timeout_ms", "general.database_connection_timeout_ms", 250, 120_000),
                new NumericRange("ground_bridge_max_seconds", "correlation.ground_bridge_max_seconds", 1, 86_400),
                new NumericRange("max_page_size", "query.max_page_size", 1, 100),
                new NumericRange("poll_interval_ms", "ingestion.poll_interval_ms", 10, 5_000),
                new NumericRange("queue_frequency_ticks", "ingestion.queue_frequency_ticks", 1, 100),
                new NumericRange("max_batch_size", "ingestion.max_batch_size", 1, 1_000),
                new NumericRange("database_heartbeat_interval_ms", "operations.database_heartbeat_interval_ms",
                        1_000, 3_600_000));

        for (NumericRange range : ranges) {
            Files.writeString(file, range.key() + "=" + range.minimum() + "\n");
            FabricItemGraphConfig.load(tempDir, config);
            Files.writeString(file, range.key() + "=" + range.maximum() + "\n");
            FabricItemGraphConfig.load(tempDir, config);

            assertRejectedNumericValue(tempDir, config, range.key(), range.configKey(), range.minimum() - 1);
            assertRejectedNumericValue(tempDir, config, range.key(), range.configKey(), range.maximum() + 1);
            assertRejectedNumericValue(tempDir, config, range.key(), range.configKey(), "not-an-integer");
            assertRejectedNumericValue(tempDir, config, range.key(), range.configKey(), "999999999999999");
        }
    }

    private static void assertRejectedNumericValue(Path gameDirectory, Path configDirectory, String propertyKey,
            String configKey, Object value) throws Exception {
        Path file = configDirectory.resolve("itemgraph.properties");
        Files.writeString(file, propertyKey + "=" + value + "\n");
        IOException error = assertThrows(IOException.class,
                () -> FabricItemGraphConfig.load(gameDirectory, configDirectory), propertyKey + " must reject " + value);
        assertTrue(error.getMessage().contains(configKey), configKey + " error must identify the full config key");
        if (value instanceof String string) {
            assertTrue(error.getMessage().contains("must be an integer"),
                    configKey + " error must identify invalid integer input");
            assertTrue(error.getMessage().contains(string), configKey + " error must include the rejected input");
        } else {
            assertTrue(error.getMessage().contains(value.toString()), configKey + " error must identify rejected value");
        }
    }

    private record NumericRange(String key, String configKey, int minimum, int maximum) { }
}
