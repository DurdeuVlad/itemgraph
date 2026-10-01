package com.itemgraph.command;

import com.mojang.brigadier.CommandDispatcher;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemGraphStatusSecurityTest {
    @BeforeAll
    static void initMinecraft() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @AfterEach
    void cleanup() {
        QueryDispatcher.shutdown();
        DatabaseManager.getInstance().close();
    }

    @Test
    void statusReportsBackendAndSchemaWithoutLeakingDatabasePathOrDriverError(@TempDir Path tempDir) throws Exception {
        Path privateParent = tempDir.resolve("private-connection-location");
        Files.writeString(privateParent, "not a directory");
        Path privatePath = privateParent.resolve("itemgraph.db");
        DatabaseManager.getInstance().initialize(privatePath);
        assertFalse(DatabaseManager.getInstance().isInitialized(), "file as a parent should make SQLite initialization fail");

        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        List<String> successes = new ArrayList<>();
        doAnswer(invocation -> {
            Supplier<Component> message = invocation.getArgument(0);
            successes.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        List<String> failures = new ArrayList<>();
        doAnswer(invocation -> {
            failures.add(invocation.<Component>getArgument(0).getString());
            return null;
        }).when(source).sendFailure(any());

        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        assertEquals(0, dispatcher.execute("ig status", source));

        assertEquals(1, successes.size());
        String status = successes.get(0);
        assertTrue(status.contains("backend=sqlite"));
        assertTrue(status.contains("schemaVersion=0"));
        assertFalse(status.contains(privatePath.toString()));
        assertFalse(status.contains("lastError="));
        assertEquals(List.of("[ItemGraph] Database statistics unavailable; inspect the server log for connection details."), failures);
    }

    @Test
    void networkStatusReportsBackendAndSchemaWithoutLeakingConnectionSecrets() throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isInitialized()).thenReturn(false);
        when(database.getSettings()).thenReturn(DatabaseSettings.mysqlMariaDb(
                "sentinel-host", 3306, "sentinel-database", "sentinel-user", "sentinel-password", 5_000, true));
        when(database.getCurrentSchemaVersion()).thenReturn(20);

        try (var databaseManager = mockStatic(DatabaseManager.class)) {
            databaseManager.when(DatabaseManager::getInstance).thenReturn(database);

            CommandSourceStack source = mock(CommandSourceStack.class);
            when(source.hasPermission(2)).thenReturn(true);
            List<String> successes = new ArrayList<>();
            doAnswer(invocation -> {
                Supplier<Component> message = invocation.getArgument(0);
                successes.add(message.get().getString());
                return null;
            }).when(source).sendSuccess(any(), anyBoolean());

            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            ItemGraphCommands.register(dispatcher);
            assertEquals(0, dispatcher.execute("ig status", source));

            assertEquals(1, successes.size());
            String status = successes.get(0);
            assertTrue(status.contains("backend=mysql_mariadb"));
            assertTrue(status.contains("schemaVersion=20"));
            assertFalse(status.contains("sentinel-host"));
            assertFalse(status.contains("sentinel-database"));
            assertFalse(status.contains("sentinel-user"));
            assertFalse(status.contains("sentinel-password"));
        }
    }
}
