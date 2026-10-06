package com.itemgraph.command;

import com.mojang.brigadier.CommandDispatcher;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.db.migration.MigrationRunner;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.query.QueryLimits;
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
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemGraphStatusSecurityTest {
    private int originalPageSize;

    @Test
    void statusActionLinePrioritizesConcreteFailuresWithoutHealthThresholds() {
        String previousLocale = com.itemgraph.i18n.ItemGraphLanguage.getLocale();
        com.itemgraph.i18n.ItemGraphLanguage.setLocale("en_us");
        try {
            assertTrue(ItemGraphCommands.statusActionLine(false, InternalObservationService.CaptureState.ACTIVE, 0)
                    .contains("DB disconnected"));
            assertTrue(ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.RECOVERY_BLOCKED, 0)
                    .contains("Recovery blocked"));
            assertTrue(ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.RECOVERY_LOADING, 0)
                    .contains("Wait before reading queue totals"));
            assertTrue(ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.STOPPED, 0)
                    .contains("Capture stopped"));
            assertTrue(ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.ACTIVE, 3)
                    .contains("Records dropped"));
            String active = ItemGraphCommands.statusActionLine(true,
                    InternalObservationService.CaptureState.ACTIVE, 0);
            assertTrue(active.contains("coverage is not proven"));
            assertFalse(active.contains("healthy"), "status must not invent an unmeasured health score");
            assertTrue(List.of(
                            ItemGraphCommands.statusActionLine(false, InternalObservationService.CaptureState.ACTIVE, 0),
                            ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.RECOVERY_BLOCKED, 0),
                            ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.RECOVERY_LOADING, 0),
                            ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.STOPPED, 0),
                            ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.DISABLED_BY_CONFIG, 0),
                            ItemGraphCommands.statusActionLine(true, InternalObservationService.CaptureState.ACTIVE, 3),
                            active)
                    .stream().allMatch(line -> line.length() <= 80),
                    "action guidance should fit narrow Minecraft chat");
        } finally {
            com.itemgraph.i18n.ItemGraphLanguage.setLocale(previousLocale);
        }
    }

    @Test
    void statusActionMessagesHaveDutchAndTraditionalChineseTranslations() {
        String previousLocale = com.itemgraph.i18n.ItemGraphLanguage.getLocale();
        Set<String> actionKeys = Set.of(
                "status.action.db_disconnected", "status.action.recovery_blocked",
                "status.action.recovery_loading", "status.action.capture_stopped",
                "status.action.capture_disabled", "status.action.dropped_records",
                "status.action.no_stop_reported");
        try {
            for (String locale : List.of("nl_nl", "zh_tw")) {
                com.itemgraph.i18n.ItemGraphLanguage.setLocale(locale);
                Set<String> missing = com.itemgraph.i18n.ItemGraphLanguage.keyFallbackInventory(locale);
                assertTrue(java.util.Collections.disjoint(missing, actionKeys),
                        () -> locale + " is missing status action translations: " + missing);
                String action = ItemGraphCommands.statusActionLine(true,
                        InternalObservationService.CaptureState.ACTIVE, 0);
                assertFalse(action.contains("No reported DB/capture stop"),
                        () -> locale + " unexpectedly fell back to English: " + action);
            }
        } finally {
            com.itemgraph.i18n.ItemGraphLanguage.setLocale(previousLocale);
        }
    }

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
        QueryLimits.configureMaxPageSize(originalPageSize);
    }

    @org.junit.jupiter.api.BeforeEach
    void setDefaultPageSize() {
        originalPageSize = QueryLimits.getConfiguredMaxPageSize();
        QueryLimits.configureMaxPageSize(10);
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

        assertTrue(successes.size() > 1, "runtime diagnostics must still print when database statistics are unavailable");
        String status = String.join("\n", successes);
        assertTrue(status.contains("backend=sqlite"));
        assertTrue(status.contains("schemaVersion=0"));
        assertTrue(status.contains("maxPageSize=10"));
        assertTrue(status.contains("databaseConnectionTimeoutMs=5000"));
        assertTrue(status.contains("useIndexes=true"));
        assertFalse(status.contains(privatePath.toString()));
        assertFalse(status.contains("lastError="));
        assertTrue(successes.stream().anyMatch(line -> line.contains("captureState=")),
                "disconnected database output must retain the native capture state");
        assertTrue(successes.stream().anyMatch(line -> line.contains("recoveryPendingRecords=")),
                "disconnected database output must retain the recovery count state");
        assertTrue(successes.stream().anyMatch(line -> line.contains("dropped=")),
                "disconnected database output must retain dropped-record diagnostics");
        assertEquals(List.of("[ItemGraph] Database statistics unavailable; inspect the server log for connection details."), failures);
    }

    @Test
    void networkStatusReportsBackendAndSchemaWithoutLeakingConnectionSecrets() throws Exception {
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isInitialized()).thenReturn(false);
        when(database.getSettings()).thenReturn(DatabaseSettings.mysqlMariaDb(
                "sentinel-host", 3306, "sentinel-database", "sentinel-user", "sentinel-password", 5_000, true));
        when(database.getCurrentSchemaVersion()).thenReturn(MigrationRunner.LATEST_VERSION);
        QueryLimits.configureMaxPageSize(25);

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

            assertTrue(successes.size() > 1, "runtime diagnostics must remain available without a database connection");
            String status = String.join("\n", successes);
            assertTrue(status.contains("backend=mysql_mariadb"));
            assertTrue(status.contains("schemaVersion=" + MigrationRunner.LATEST_VERSION));
            assertTrue(status.contains("maxPageSize=25"));
            assertTrue(status.contains("databaseConnectionTimeoutMs=5000"));
            assertTrue(status.contains("useIndexes=true"));
            assertFalse(status.contains("sentinel-host"));
            assertFalse(status.contains("sentinel-database"));
            assertFalse(status.contains("sentinel-user"));
            assertFalse(status.contains("sentinel-password"));
            assertTrue(successes.stream().anyMatch(line -> line.contains("captureState=")));
        }
    }

    @Test
    void statusEmitsRuntimeDiagnosticsBeforeAsyncDatabaseStatistics() throws Exception {
        com.itemgraph.ingest.IngestionService.getInstance();
        DatabaseManager database = mock(DatabaseManager.class);
        when(database.isInitialized()).thenReturn(true);
        when(database.getSettings()).thenReturn(DatabaseSettings.sqlite(Path.of("unused-test-path"), true));
        when(database.getCurrentSchemaVersion()).thenReturn(MigrationRunner.LATEST_VERSION);

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
            assertEquals(1, dispatcher.execute("ig status", source),
                    "the async database-statistics query is accepted after runtime status is printed");

            assertTrue(successes.stream().anyMatch(line -> line.contains("captureState=")),
                    "runtime state must be visible before an asynchronous stats query can fail");
            assertTrue(successes.stream().anyMatch(line -> line.contains("recoveryPendingRecords=")));
            assertTrue(successes.stream().anyMatch(line -> line.contains("heapUsedBytes=")));
        }
    }
}
