package com.itemgraph.fabric;

import com.itemgraph.db.DatabaseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
                grieflogger_database_path=database.db
                ground_bridge_max_seconds=300
                max_page_size=25
                server_side_only=true
                poll_interval_ms=100
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
        assertEquals(25, loaded.operationalSettings().maxPageSize());
        assertTrue(loaded.operationalSettings().serverSideOnly());
        assertEquals(100, loaded.operationalSettings().queuePollIntervalMs());
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
        assertEquals(100, loaded.operationalSettings().maxBatchSize());
        assertEquals(30_000, loaded.operationalSettings().databaseHeartbeatIntervalMs());
        assertTrue(loaded.operationalSettings().captureEnabled());
        assertEquals("indefinite", loaded.operationalSettings().rawEvidenceRetention());
    }

    @Test
    void configChangesTakeEffectWhenReloadedForTheNextServerStart(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("config");
        Files.createDirectories(config);
        Path file = config.resolve("itemgraph.properties");
        Files.writeString(file, "max_page_size=10\npoll_interval_ms=250\n");

        FabricItemGraphConfig runningServerSnapshot = FabricItemGraphConfig.load(tempDir, config);
        Files.writeString(file, "max_page_size=25\npoll_interval_ms=500\n");

        assertEquals(10, runningServerSnapshot.operationalSettings().maxPageSize(),
                "editing the file must not mutate the config snapshot already used by the running server");
        assertEquals(250, runningServerSnapshot.operationalSettings().queuePollIntervalMs());
        FabricItemGraphConfig restartedServerConfig = FabricItemGraphConfig.load(tempDir, config);
        assertEquals(25, restartedServerConfig.operationalSettings().maxPageSize());
        assertEquals(500, restartedServerConfig.operationalSettings().queuePollIntervalMs());
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
        assertTrue(error.getMessage().contains("use_indexes must be true or false"));
    }
}
