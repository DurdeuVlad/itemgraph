package com.itemgraph.fabric.metrics;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.ingest.InternalObservationService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** CI-only throughput comparison against disposable loopback MySQL and MariaDB services. */
class FabricNetworkBackendPerformanceIntegrationTest {
    private static final int EVENT_COUNT = 512;
    private static final int LOOKUP_THREADS = 4;
    private static final int LOOKUPS_PER_THREAD = 5;
    private record TimeInterval(long startedNanos, long finishedNanos) { }

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
                    // Start readers after the first bounded write batch has been released, then
                    // keep producing seven more batches while those reads are in flight.
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
                    // Release actual SQL lookups after event submission has resumed, then verify
                    // their completed query intervals overlap the remaining producer window.
                    allowFirstLookup.countDown();
                    assertTrue(firstLookupsReady.await(5, TimeUnit.SECONDS),
                            "concurrent lookup workers must reach their first query");
                    submissionWindowStartNanos = System.nanoTime();
                }
                if ((sequence + 1) % 64 == 0) {
                    service.onServerTick();
                }
            }
            submissionWindowEndNanos = System.nanoTime();

            for (Future<Long> lookupCount : lookupCounts) {
                assertTrue(lookupCount.get(30, TimeUnit.SECONDS) >= 64,
                        "read-only lookup workers must return committed probe rows");
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

            assertEquals(EVENT_COUNT, durableRows, "network backend must persist every unique probe row");
            assertEquals(0, dropped, "network backend probe must not reject or drop evidence");
            assertEquals(0, queueRemaining, "network backend queue must drain");
            assertTrue(persisted >= EVENT_COUNT, "worker counter must include all durable probe rows");
            assertTrue(overlappingLookups > 0,
                    "at least one completed read-only lookup must overlap the remaining submissions");
            FabricPerformanceReportFixture.writeIfRequested("fabric", "backend_fabric_" + flavor + "_matrix",
                    Map.ofEntries(
                            Map.entry("accepted_events", (long) EVENT_COUNT),
                            Map.entry("persisted_counter_delta", persisted),
                            Map.entry("dropped_counter_delta", dropped),
                            Map.entry("durable_rows", durableRows),
                            Map.entry("queue_remaining", (long) queueRemaining),
                            Map.entry("concurrent_lookups", (long) (LOOKUP_THREADS * LOOKUPS_PER_THREAD)),
                            Map.entry("overlapping_lookups", overlappingLookups),
                            Map.entry("automation_events", (long) (EVENT_COUNT / 3)),
                            Map.entry("modded_inventory_events", (long) (EVENT_COUNT / 3 + 1)),
                            Map.entry("enqueue_total_ns", enqueueNanos),
                            Map.entry("elapsed_ms", elapsedMillis)));
        } finally {
            try {
                beginReadersCleanup(readers);
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
