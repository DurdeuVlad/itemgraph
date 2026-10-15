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
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE, false, 0, false, false)
                    .contains("Check database settings and logs"));
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.RECOVERY_BLOCKED, true, 0, false, false)
                    .contains("Preserve recovery files"));
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.RECOVERY_LOADING, true, 0, false, false)
                    .contains("Wait for recovery loading"));
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.STOPPED, true, 0, false, false)
                    .contains("restore the capture worker"));
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE, true, 3, false, false)
                    .contains("Evidence was rejected or lost"));
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE, true, 0, true, false)
                    .contains("failed source-ingestion cycle"));
            assertTrue(ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE, true, 0, false, true)
                    .contains("failed correlation pass"));
            String active = ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE,
                    true, 0, false, false);
            assertTrue(active.contains("confirm capture was active"));
            assertFalse(active.contains("healthy"), "status must not invent an unmeasured health score");
            assertTrue(List.of(
                            ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE, false, 0, false, false),
                            ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.RECOVERY_BLOCKED, true, 0, false, false),
                            ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.RECOVERY_LOADING, true, 0, false, false),
                            ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.STOPPED, true, 0, false, false),
                            ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.DISABLED_BY_CONFIG, true, 0, false, false),
                            ItemGraphCommands.statusActionLine(InternalObservationService.CaptureState.ACTIVE, true, 3, false, false),
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
                "status.action.database_disconnected", "status.action.recovery_blocked",
                "status.action.recovery_loading", "status.action.capture_stopped",
                "status.action.capture_disabled", "status.action.dropped",
                "status.action.ingestion_failed", "status.action.correlation_failed",
                "status.action.verify_coverage");
        try {
            for (String locale : List.of("nl_nl", "zh_tw")) {
                com.itemgraph.i18n.ItemGraphLanguage.setLocale(locale);
                Set<String> missing = com.itemgraph.i18n.ItemGraphLanguage.keyFallbackInventory(locale);
                assertTrue(java.util.Collections.disjoint(missing, actionKeys),
                        () -> locale + " is missing status action translations: " + missing);
                String action = ItemGraphCommands.statusActionLine(
                        InternalObservationService.CaptureState.ACTIVE, true, 0, false, false);
                assertFalse(action.contains("No stop reported"),
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
        assertTrue(successes.get(0).contains("ACTION:"), "status must lead with its actionable next step");
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
