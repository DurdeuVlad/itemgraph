package com.itemgraph.fabric.metrics;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.command.QueryDispatcher;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.ingest.InternalObservationService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** CI-only throughput comparison against disposable loopback MySQL and MariaDB services. */
class FabricNetworkBackendPerformanceIntegrationTest {
    private static final int EVENT_COUNT = 512;
    private static final int LOOKUP_THREADS = 4;
    private static final int LOOKUPS_PER_THREAD = 5;
    private static final int REGISTERED_COMMANDS_PER_THREAD = 5;
    private record TimeInterval(long startedNanos, long finishedNanos) { }

    private static final class CommandProbe {
        private final CommandSourceStack source;
        private final String expectedDetailPrefix;
        private volatile long startedNanos;
        private final AtomicInteger evidenceLines = new AtomicInteger();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile long finishedNanos;
        private volatile boolean failed;

        private CommandProbe(CommandSourceStack source, String expectedDetailPrefix) {
            this.source = source;
            this.expectedDetailPrefix = expectedDetailPrefix;
        }

        private void successfulMessage(String renderedLine, ThreadLocal<CommandProbe> callbackProbe) {
            callbackProbe.set(this);
            // Synthetic benchmark action names are intentionally absent from the
            // production taxonomy, so lookup correctly labels them UNCLASSIFIED.
            // Match this run's unique raw audit detail instead of assuming a class.
            if (renderedLine.contains("audit#")
                    && renderedLine.contains("detail=" + expectedDetailPrefix)) {
                evidenceLines.incrementAndGet();
            }
        }

        private void failedMessage(ThreadLocal<CommandProbe> callbackProbe) {
            failed = true;
            callbackProbe.set(this);
        }

        private void finishCallback() {
            if (completed.compareAndSet(false, true)) {
                finishedNanos = System.nanoTime();
            }
        }
    }

    @Test
    void mariadbQueueAndConcurrentLookupProbe() throws Exception {
        runBackendProbe("ITEMGRAPH_TEST_MARIADB_URL", "ITEMGRAPH_TEST_MARIADB_USER",
                "ITEMGRAPH_TEST_MARIADB_PASSWORD", "mariadb", "disable");
    }

    @Test
    void mysqlQueueAndConcurrentLookupProbe() throws Exception {
        runBackendProbe("ITEMGRAPH_TEST_MYSQL_URL", "ITEMGRAPH_TEST_MYSQL_USER",
                "ITEMGRAPH_TEST_MYSQL_PASSWORD", "mysql", "trust");
    }

    private static void runBackendProbe(String urlKey, String userKey, String passwordKey,
                                       String flavor, String sslMode) throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                        && System.getenv("ITEMGRAPH_PERFORMANCE_REPORT_DIR") != null
                        && !System.getenv("ITEMGRAPH_PERFORMANCE_REPORT_DIR").isBlank(),
                "network performance probes run only against disposable GitHub Actions services");
        String jdbcUrl = System.getenv(urlKey);
        assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(), "No endpoint configured for " + urlKey);

        URI endpoint = URI.create(jdbcUrl.substring("jdbc:".length()));
        String databaseName = endpoint.getPath().replaceFirst("^/", "");
        int expectedPort = "mysql".equals(flavor) ? 3307 : 3306;
        assertEquals("127.0.0.1", endpoint.getHost(), "CI benchmark endpoint must be loopback-only");
        assertEquals(expectedPort, endpoint.getPort(), "CI benchmark must use the disposable service port");
        assertEquals("itemgraph", databaseName, "CI benchmark must use its disposable database name");
        DatabaseSettings settings = DatabaseSettings.mysqlMariaDb(endpoint.getHost(), endpoint.getPort(), databaseName,
                System.getenv().getOrDefault(userKey, "itemgraph"),
                System.getenv().getOrDefault(passwordKey, "itemgraph"), 5_000, true, sslMode);
        DatabaseManager database = DatabaseManager.getInstance();
        InternalObservationService service = InternalObservationService.getInstance();
        ExecutorService readers = Executors.newFixedThreadPool(LOOKUP_THREADS);
        ConcurrentLinkedQueue<TimeInterval> lookupIntervals = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<CommandProbe> commandProbes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Runnable> serverCallbacks = new ConcurrentLinkedQueue<>();
        LongAdder commandCallbackFailures = new LongAdder();
        ThreadLocal<CommandProbe> callbackProbeContext = new ThreadLocal<>();
        String prefix = "issue32-" + flavor + ":" + UUID.randomUUID() + ":";
        try {
            service.stop();
            service.clear();
            database.initialize(settings);
            assertTrue(database.isInitialized(), "CI " + flavor + " storage must initialize");
            String product = database.getConnection().getMetaData().getDatabaseProductName()
                    .toLowerCase(java.util.Locale.ROOT);
            assertTrue("mariadb".equals(flavor) ? product.contains("maria")
                            : product.contains("mysql") && !product.contains("maria"),
                    "CI service must report the expected " + flavor + " database engine");
            service.configureOperations(10, 1, 100, 30_000, true);
            service.start();
            long runStarted = System.nanoTime();

            CountDownLatch readersReady = new CountDownLatch(LOOKUP_THREADS);
            CountDownLatch beginLookups = new CountDownLatch(1);
            CountDownLatch firstLookupsReady = new CountDownLatch(LOOKUP_THREADS);
            CountDownLatch allowFirstLookup = new CountDownLatch(1);
            Thread modeledServerThread = Thread.currentThread();
            CommandDispatcher<CommandSourceStack> commandDispatcher = registeredCommandDispatcher();
            MinecraftServer commandServer = mock(MinecraftServer.class);
            when(commandServer.getRunningThread()).thenReturn(modeledServerThread);
            when(commandServer.isStopped()).thenReturn(false);
            doAnswer(invocation -> {
                Runnable callback = invocation.getArgument(0);
                serverCallbacks.add(() -> {
                    assertSame(modeledServerThread, Thread.currentThread(),
                            "QueryDispatcher delivery must run on the modeled server thread");
                    callbackProbeContext.remove();
                    try {
                        callback.run();
                    } finally {
                        CommandProbe callbackProbe = callbackProbeContext.get();
                        if (callbackProbe != null) {
                            callbackProbe.finishCallback();
                        }
                        callbackProbeContext.remove();
                    }
                });
                return null;
            }).when(commandServer).execute(any(Runnable.class));
            List<Future<Long>> lookupCounts = java.util.stream.IntStream.range(0, LOOKUP_THREADS)
                    .mapToObj(index -> readers.submit(() -> {
                        readersReady.countDown();
                        if (!beginLookups.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("lookup workers did not receive their start signal");
                        }
                        long found = 0;
                        for (int query = 0; query < LOOKUPS_PER_THREAD; query++) {
                            if (query == 0) {
                                if (!allowFirstLookup.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("concurrent query start was not released");
                                }
                                firstLookupsReady.countDown();
                            }
                            long started = System.nanoTime();
                            boolean succeeded = false;
                            try (var connection = database.openReadOnlyConnection();
                                 var statement = connection.prepareStatement(
                                         "SELECT COUNT(*) FROM ig_audit_events WHERE detail LIKE ?")) {
                                statement.setString(1, prefix + "%");
                                try (var rows = statement.executeQuery()) {
                                    if (!rows.next()) {
                                        throw new SQLException("lookup returned no count row");
                                    }
                                    long rowCount = rows.getLong(1);
                                    if (rowCount < 64) {
                                        throw new IllegalStateException(
                                                "read-only lookup did not see the first committed queue batch");
                                    }
                                    found = Math.max(found, rowCount);
                                }
                                succeeded = true;
                            } finally {
                                long finished = System.nanoTime();
                                OperationalMetrics.getInstance().recordQuery(finished - started, succeeded);
                                lookupIntervals.add(new TimeInterval(started, finished));
                            }
                        }
                        return found;
                    })).toList();
            assertTrue(readersReady.await(5, TimeUnit.SECONDS), "concurrent lookup workers must be ready");

            long enqueueNanos = 0L;
            long submissionWindowStartNanos = 0L;
            long submissionWindowEndNanos;
            for (int sequence = 0; sequence < EVENT_COUNT; sequence++) {
                if (sequence == 64) {
                    // Start SQL readers after the first bounded write batch is durable.
                    awaitPersisted(service, 64);
                    beginLookups.countDown();
                }
                String eventType = switch (sequence % 3) {
                    case 0 -> "HOPPER_INSERT";
                    case 1 -> "MODDED_INVENTORY_PROBE";
                    default -> "AUTOMATION_TRANSFER_PROBE";
                };
                var event = new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), eventType, null, null, "minecraft:overworld",
                        sequence % 256, 64, (sequence / 256) % 256,
                        "itemgraph:issue32-backend-probe", prefix + sequence, null);
                long singleEnqueueStarted = System.nanoTime();
                assertTrue(service.submitAuditEvent(event), "bounded audit queue rejected probe row " + sequence);
                enqueueNanos += System.nanoTime() - singleEnqueueStarted;
                if (sequence == 65) {
                    allowFirstLookup.countDown();
                    assertTrue(firstLookupsReady.await(5, TimeUnit.SECONDS),
                            "concurrent lookup workers must reach their first query");
                    submissionWindowStartNanos = System.nanoTime();
                    assertSame(modeledServerThread, Thread.currentThread(),
                            "Brigadier parsing and player position reads must stay on the modeled server thread");
                    for (int command = 0; command < LOOKUP_THREADS * REGISTERED_COMMANDS_PER_THREAD; command++) {
                        CommandProbe probe = newCommandProbe(commandServer, callbackProbeContext, prefix);
                        commandProbes.add(probe);
                        probe.startedNanos = System.nanoTime();
                        int accepted = commandDispatcher.execute("ig lookup radius.20", probe.source);
                        assertEquals(1, accepted, "registered /ig lookup alias command must be accepted");
                    }
                }
                if ((sequence + 1) % 64 == 0) {
                    assertSame(modeledServerThread, Thread.currentThread(),
                            "queue flush ticks must remain on the modeled server thread");
                    service.onServerTick();
                }
                if (sequence >= 65) {
                    // Leave the producer active long enough for async SQL to complete,
                    // then deliver each callback on this modeled server thread.
                    Thread.sleep(1);
                    Runnable callback;
                    while ((callback = serverCallbacks.poll()) != null) {
                        callback.run();
                    }
                }
            }
            submissionWindowEndNanos = System.nanoTime();

            for (Future<Long> lookupCount : lookupCounts) {
                assertTrue(lookupCount.get(30, TimeUnit.SECONDS) >= 64,
                        "read-only lookup workers must return committed probe rows");
            }
            long commandCallbackDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (completedCommands(commandProbes) < LOOKUP_THREADS * REGISTERED_COMMANDS_PER_THREAD
                    && System.nanoTime() < commandCallbackDeadline) {
                Runnable callback;
                while ((callback = serverCallbacks.poll()) != null) {
                    callback.run();
                }
                Thread.sleep(1);
            }
            Runnable remainingCallback;
            while ((remainingCallback = serverCallbacks.poll()) != null) {
                remainingCallback.run();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while ((service.getQueueSize() != 0 || service.getTotalPersisted() < EVENT_COUNT)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            service.stop();
            long durableRows = countProbeRows(database, prefix);
            long dropped = service.getTotalDropped();
            long persisted = service.getTotalPersisted();
            int queueRemaining = service.getQueueSize();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - runStarted);
            long overlappingLookups = 0L;
            for (TimeInterval interval : lookupIntervals) {
                if (interval.startedNanos() < submissionWindowEndNanos
                        && interval.finishedNanos() > submissionWindowStartNanos) {
                    overlappingLookups++;
                }
            }
            List<Long> registeredCommandLatencies = new ArrayList<>();
            long overlappingRegisteredCommands = 0L;
            long registeredCommandLatencyTotalNanos = 0L;
            long registeredCommandLatencyMaxNanos = 0L;
            for (CommandProbe probe : commandProbes) {
                assertTrue(probe.completed.get(), "every registered lookup must complete its server-thread callback");
                assertTrue(probe.evidenceLines.get() > 0,
                        "registered radius lookup must return a matching synthetic audit row");
                if (probe.failed) {
                    commandCallbackFailures.increment();
                }
                long latency = probe.finishedNanos - probe.startedNanos;
                registeredCommandLatencies.add(latency);
                registeredCommandLatencyTotalNanos += latency;
                registeredCommandLatencyMaxNanos = Math.max(registeredCommandLatencyMaxNanos, latency);
                if (probe.startedNanos < submissionWindowEndNanos
                        && probe.finishedNanos >= submissionWindowStartNanos
                        && probe.finishedNanos <= submissionWindowEndNanos) {
                    overlappingRegisteredCommands++;
                }
            }
            registeredCommandLatencies.sort(Comparator.naturalOrder());
            long registeredCommandLatencyP95Nanos = registeredCommandLatencies.get(
                    (int) Math.ceil(registeredCommandLatencies.size() * 0.95) - 1);

            assertEquals(EVENT_COUNT, durableRows, "network backend must persist every unique probe row");
            assertEquals(0, dropped, "network backend probe must not reject or drop evidence");
            assertEquals(0, queueRemaining, "network backend queue must drain");
            assertTrue(persisted >= EVENT_COUNT, "worker counter must include all durable probe rows");
            assertTrue(overlappingLookups > 0,
                    "at least one completed read-only lookup must overlap the remaining submissions");
            assertEquals(LOOKUP_THREADS * REGISTERED_COMMANDS_PER_THREAD, commandProbes.size(),
                    "the bounded registered command workload must submit exactly twenty lookups");
            assertEquals(0, commandCallbackFailures.sum(), "registered command callbacks must not fail");
            assertTrue(overlappingRegisteredCommands > 0,
                    "a registered lookup callback must complete while events are being submitted");
            FabricPerformanceReportFixture.writeIfRequested("fabric", "backend_fabric_" + flavor + "_matrix",
                    Map.ofEntries(
                            Map.entry("accepted_events", (long) EVENT_COUNT),
                            Map.entry("persisted_counter_delta", persisted),
                            Map.entry("dropped_counter_delta", dropped),
                            Map.entry("durable_rows", durableRows),
                            Map.entry("queue_remaining", (long) queueRemaining),
                            Map.entry("concurrent_lookups", (long) (LOOKUP_THREADS * LOOKUPS_PER_THREAD)),
                            Map.entry("overlapping_lookups", overlappingLookups),
                            Map.entry("registered_lookup_commands", (long) commandProbes.size()),
                            Map.entry("registered_lookup_callbacks_completed", (long) completedCommands(commandProbes)),
                            Map.entry("registered_lookup_callbacks_failed", commandCallbackFailures.sum()),
                            Map.entry("registered_lookup_callbacks_completed_during_submissions",
                                    overlappingRegisteredCommands),
                            Map.entry("registered_lookup_dispatch_callback_total_ns", registeredCommandLatencyTotalNanos),
                            Map.entry("registered_lookup_dispatch_callback_max_ns", registeredCommandLatencyMaxNanos),
                            Map.entry("registered_lookup_dispatch_callback_p95_ns", registeredCommandLatencyP95Nanos),
                            Map.entry("automation_events", (long) (EVENT_COUNT / 3)),
                            Map.entry("modded_inventory_events", (long) (EVENT_COUNT / 3 + 1)),
                            Map.entry("enqueue_total_ns", enqueueNanos),
                            Map.entry("elapsed_ms", elapsedMillis)));
        } finally {
            try {
                beginReadersCleanup(readers);
            } finally {
                try {
                    QueryDispatcher.shutdown();
                } finally {
                    try {
                        service.stop();
                    } finally {
                        try {
                            service.clear();
                            service.configureOperations(250, 20, 100, 30_000, true);
                        } finally {
                            database.close();
                        }
                    }
                }
            }
        }
    }

    private static CommandDispatcher<CommandSourceStack> registeredCommandDispatcher() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Another test may already have bootstrapped the shared Minecraft registries.
        }
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        return dispatcher;
    }

    private static CommandProbe newCommandProbe(MinecraftServer server,
                                                ThreadLocal<CommandProbe> callbackProbeContext,
                                                String expectedDetailPrefix) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        CommandProbe probe = new CommandProbe(source, expectedDetailPrefix);
        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(source.hasPermission(2)).thenReturn(true);
        when(player.hasDisconnected()).thenReturn(false);
        when(player.level()).thenReturn(level);
        // Near the synthetic event cluster so /ig lookup returns benchmark audit rows.
        when(player.getX()).thenReturn(32.0);
        when(player.getY()).thenReturn(64.0);
        when(player.getZ()).thenReturn(0.0);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        doAnswer(invocation -> {
            java.util.function.Supplier<Component> message = invocation.getArgument(0);
            probe.successfulMessage(message.get().getString(), callbackProbeContext);
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        doAnswer(invocation -> {
            probe.failedMessage(callbackProbeContext);
            return null;
        }).when(source).sendFailure(any());
        return probe;
    }

    private static long completedCommands(ConcurrentLinkedQueue<CommandProbe> probes) {
        return probes.stream().filter(probe -> probe.completed.get()).count();
    }

    private static long countProbeRows(DatabaseManager database, String prefix) {
        try (var connection = database.openReadOnlyConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ig_audit_events WHERE detail LIKE ?")) {
            statement.setString(1, prefix + "%");
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("database did not return the probe row count");
                }
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not count the isolated ItemGraph backend probe", failure);
        }
    }

    private static void awaitPersisted(InternalObservationService service, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (service.getTotalPersisted() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(service.getTotalPersisted() >= expected,
                "the first queue batch did not become durable before concurrent lookups started");
    }

    private static void beginReadersCleanup(ExecutorService readers) {
        readers.shutdownNow();
        try {
            if (!readers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("performance lookup workers did not stop");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stopping performance lookup workers", interrupted);
        }
    }
}
