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
        assertTrue(Files.exists(config.resolve("itemgraph.properties")));
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
