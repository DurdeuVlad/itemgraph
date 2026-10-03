package com.itemgraph.gametest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.metrics.OperationalMetrics;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;

import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-loader, SQLite-backed correlation throughput probe. */
public final class CorrelationPerformanceFixture {
    private static final int PAIRS_PER_PASS = 50;
    private static final int PASS_COUNT = 5;
    private static final int OBSERVATION_COUNT = PAIRS_PER_PASS * PASS_COUNT * 2;
    private static final int MALFORMED_PAYLOAD_REPETITIONS = 500;
    private static final long SOURCE_EVENT_FLOOR = 91_000_000_000L;
    private static final long CLOSED_WINDOW_AGE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long COMPLETION_DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final long CLEANUP_GRACE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private static final String SOURCE_PLAYER_UUID = "10000000-0000-4000-8000-000000000001";
    private static final String DESTINATION_PLAYER_UUID = "10000000-0000-4000-8000-000000000002";
    private record DurableSnapshot(long observations, long finalizedObservations, long edges,
                                   long edgeUnits, long sourceAllocatedUnits, long destinationAllocatedUnits,
                                   long sourceAllocationMismatches, long destinationAllocationMismatches) { }
    private record Completion(String failure) { }

    private CorrelationPerformanceFixture() { }

    public static void run(GameTestHelper helper, String loader, String griefLoggerRuntimeState) {
        var server = helper.getLevel().getServer();
        Probe probe = new Probe(helper, loader, griefLoggerRuntimeState);
        helper.runAfterDelay(1, probe::completeOnGameTestTick);
        CompletableFuture.runAsync(probe::prepare)
                .whenComplete((ignored, failure) -> server.execute(() -> {
                    if (probe.failureCleanupStarted.get()) {
                        return;
                    }
                    if (failure != null) {
                        probe.fail("could not prepare isolated correlation workload", failure);
                        return;
                    }
                    probe.schedulePass(1);
                }));
    }

    private static final class Probe {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final String loader;
        private final String griefLoggerRuntimeState;
        private final RegistryAccess registryAccess;
        private final InternalObservationService observations = InternalObservationService.getInstance();
        private final IngestionService ingestion = IngestionService.getInstance();
        private final long startedNanos = System.nanoTime();
        private final AtomicReference<Completion> completion = new AtomicReference<>();
        private final AtomicBoolean failureCleanupStarted = new AtomicBoolean();
        private final AtomicLong failureCleanupStartedNanos = new AtomicLong();
        private final String runIdentity = UUID.randomUUID().toString();
        private final long sourceEventBase = SOURCE_EVENT_FLOOR
                + (UUID.randomUUID().getLeastSignificantBits() & 0x0000ffffffffffffL);
        private int nextPair;
        private long persistedRows;
        private long droppedRows;
        private long durableRows;
        private long malformedPayloadElapsedNanos;
        private long malformedFirstDecodeNanos;
        private long malformedCacheInsertions;
        private long malformedCacheHits;

        private Probe(GameTestHelper helper, String loader, String griefLoggerRuntimeState) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.loader = loader;
            this.griefLoggerRuntimeState = griefLoggerRuntimeState;
            this.registryAccess = helper.getLevel().registryAccess();
        }

        private void prepare() {
            if (!DatabaseManager.getInstance().isInitialized()) {
                throw new IllegalStateException("ItemGraph SQLite database is not initialized");
            }
            ingestion.stop();
            observations.stop();
            observations.clear();
            OperationalMetrics metrics = OperationalMetrics.getInstance();
            ItemCanonicalizer.setRegistryAccess(registryAccess);
            observations.start();
            // start() resets the shared metrics. Run after it so the report retains
            // the malformed-decode insertion and cache-hit measurements.
            runMalformedPayloadProbe(metrics);
        }

        private void runMalformedPayloadProbe(OperationalMetrics metrics) {
            byte[] malformedPayload = HexFormat.of().parseHex("0500000004");
            long insertionsBefore = metrics.snapshot().decodeFailureCacheInsertions();
            long hitsBefore = metrics.snapshot().decodeCacheHits();
            long startedNanos = System.nanoTime();
            String expectedFingerprint = null;
            for (int iteration = 0; iteration < MALFORMED_PAYLOAD_REPETITIONS; iteration++) {
                long decodeStartedNanos = System.nanoTime();
                CanonicalItem item = ItemCanonicalizer.canonicalize(
                        "minecraft:diamond_sword", malformedPayload, registryAccess);
                long decodeElapsedNanos = System.nanoTime() - decodeStartedNanos;
                if (iteration == 0) {
                    malformedFirstDecodeNanos = decodeElapsedNanos;
                }
                if (item.componentSummary() == null
                        || !item.componentSummary().contains("component_decode=UNRESOLVED")) {
                    throw new IllegalStateException("malformed component payload did not remain unresolved");
                }
                if (expectedFingerprint == null) {
                    expectedFingerprint = item.fingerprintHash();
                } else if (!expectedFingerprint.equals(item.fingerprintHash())) {
                    throw new IllegalStateException("cached malformed payload changed its opaque fingerprint");
                }
            }
            malformedPayloadElapsedNanos = System.nanoTime() - startedNanos;
            malformedCacheInsertions = metrics.snapshot().decodeFailureCacheInsertions() - insertionsBefore;
            malformedCacheHits = metrics.snapshot().decodeCacheHits() - hitsBefore;
            if (malformedCacheInsertions != 1
                    || malformedCacheHits != MALFORMED_PAYLOAD_REPETITIONS - 1L) {
                throw new IllegalStateException("malformed payload cache workload did not decode once: insertions="
                        + malformedCacheInsertions + ", hits=" + malformedCacheHits);
            }
        }

        private void schedulePass(int passNumber) {
            server.execute(() -> submitPass(passNumber));
        }

        private void submitPass(int passNumber) {
            if (failureCleanupStarted.get()) {
                return;
            }
            try {
                long passStart = (long) (passNumber - 1) * PAIRS_PER_PASS;
                for (int offset = 0; offset < PAIRS_PER_PASS; offset++) {
                    int pair = Math.toIntExact(passStart + offset);
                    long timestamp = System.currentTimeMillis() - CLOSED_WINDOW_AGE_MS + pair * 2L;
                    CanonicalItem item = benchmarkItem(runIdentity, pair);
                    String entityUuid = UUID.nameUUIDFromBytes(
                            ("itemgraph-correlation-performance:" + runIdentity + ":" + pair)
                                    .getBytes(StandardCharsets.UTF_8))
                            .toString();
                    var drop = observation(pair * 2L, timestamp, "DROP_ITEM", SOURCE_PLAYER_UUID,
                            "Correlation Probe Source", item, entityUuid, sourceEventBase);
                    var pickup = observation(pair * 2L + 1L, timestamp + 1L, "PICKUP_ITEM", DESTINATION_PLAYER_UUID,
                            "Correlation Probe Destination", item, entityUuid, sourceEventBase);
                    helper.assertTrue(observations.submit(drop), "correlation probe queue rejected drop " + pair);
                    helper.assertTrue(observations.submit(pickup), "correlation probe queue rejected pickup " + pair);
                }
                nextPair += PAIRS_PER_PASS;
                awaitPersisted(passNumber, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
            } catch (Throwable failure) {
                fail("could not submit correlation probe pass " + passNumber, failure);
            }
        }

        private void awaitPersisted(int passNumber, long deadlineNanos) {
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() ->
                    server.execute(() -> {
                if (failureCleanupStarted.get()) {
                    return;
                }
                if (observations.getTotalPersisted() >= nextPair * 2L
                        && observations.getQueueSize() == 0) {
                    persistedRows = observations.getTotalPersisted();
                    droppedRows = observations.getTotalDropped();
                    if (droppedRows != 0) {
                        fail("correlation probe rejected observations: dropped=" + droppedRows, null);
                        return;
                    }
                    CompletableFuture.supplyAsync(ingestion::runCorrelation)
                            .whenComplete((result, failure) -> server.execute(() -> {
                                if (failureCleanupStarted.get()) {
                                    return;
                                }
                                if (failure != null) {
                                    fail("correlation pass threw", failure);
                                    return;
                                }
                                if (!result.success() || result.observationsFinalised() < PAIRS_PER_PASS * 2
                                        || result.edgesCreated() < PAIRS_PER_PASS) {
                                    fail("correlation pass did not preserve the pinned workload result: "
                                            + "success=" + result.success()
                                            + ", finalized=" + result.observationsFinalised()
                                            + ", edges=" + result.edgesCreated()
                                            + ", error=" + result.errorMessage(), null);
                                    return;
                                }
                                if (passNumber < PASS_COUNT) {
                                    schedulePass(passNumber + 1);
                                } else {
                                    verifyAndWriteReport();
                                }
                            }));
                    return;
                }
                if (observations.getTotalDropped() != 0 || System.nanoTime() >= deadlineNanos) {
                    fail("correlation probe observations did not drain: persisted="
                            + observations.getTotalPersisted() + ", dropped=" + observations.getTotalDropped()
                            + ", queue=" + observations.getQueueSize(), null);
                    return;
                }
                awaitPersisted(passNumber, deadlineNanos);
            }));
        }

        private void verifyAndWriteReport() {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return readDurableSnapshot();
                } catch (SQLException failure) {
                    throw new IllegalStateException("Could not read the durable correlation probe rows", failure);
                }
            }).whenComplete((rowCount, failure) -> server.execute(() -> {
                if (failureCleanupStarted.get()) {
                    return;
                }
                if (failure != null) {
                    fail("correlation ledger read failed", failure);
                    return;
                }
                durableRows = rowCount.observations();
                try {
                    helper.assertValueEqual((long) OBSERVATION_COUNT, persistedRows,
                            "worker must persist every accepted correlation input");
                    helper.assertValueEqual(0L, droppedRows, "correlation probe must not lose evidence");
                    helper.assertValueEqual((long) OBSERVATION_COUNT, durableRows,
                            "SQLite must contain every correlation input row");
                    helper.assertValueEqual((long) OBSERVATION_COUNT, rowCount.finalizedObservations(),
                            "correlation must finalize every benchmark observation");
                    long expectedUnits = PASS_COUNT * PAIRS_PER_PASS;
                    helper.assertValueEqual(expectedUnits, rowCount.edges(),
                            "SQLite must contain every inferred correlation edge");
                    helper.assertValueEqual(expectedUnits, rowCount.edgeUnits(),
                            "inferred edge quantities must conserve the accepted item count");
                    helper.assertValueEqual(expectedUnits, rowCount.sourceAllocatedUnits(),
                            "source allocations must conserve the accepted item count");
                    helper.assertValueEqual(expectedUnits, rowCount.destinationAllocatedUnits(),
                            "destination allocations must conserve the accepted item count");
                    helper.assertValueEqual(0L, rowCount.sourceAllocationMismatches(),
                            "each inferred edge must have source allocations equal to its quantity");
                    helper.assertValueEqual(0L, rowCount.destinationAllocationMismatches(),
                            "each inferred edge must have destination allocations equal to its quantity");
                    var metrics = OperationalMetrics.getInstance().snapshot();
                    helper.assertValueEqual((long) PASS_COUNT, metrics.correlation().count(),
                            "correlation report must contain exactly five measured passes");
                    helper.assertValueEqual(0L, metrics.correlation().failed(),
                            "all measured correlation passes must succeed");
                    PerformanceReportFixture.writeIfRequested(loader, "correlation_burst", Map.ofEntries(
                            Map.entry("accepted_events", (long) OBSERVATION_COUNT),
                            Map.entry("persisted_counter_delta", persistedRows),
                            Map.entry("dropped_counter_delta", droppedRows),
                            Map.entry("durable_rows", durableRows),
                            Map.entry("queue_remaining", (long) observations.getQueueSize()),
                            Map.entry("correlation_pairs", (long) (PASS_COUNT * PAIRS_PER_PASS)),
                            Map.entry("correlation_passes", (long) PASS_COUNT),
                            Map.entry("correlation_edges", rowCount.edges()),
                            Map.entry("malformed_payload_repetitions", (long) MALFORMED_PAYLOAD_REPETITIONS),
                            Map.entry("malformed_cache_insertions", malformedCacheInsertions),
                            Map.entry("malformed_cache_hits", malformedCacheHits),
                            Map.entry("malformed_first_decode_ns", malformedFirstDecodeNanos),
                            Map.entry("malformed_total_elapsed_ns", malformedPayloadElapsedNanos),
                            Map.entry("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))),
                            griefLoggerRuntimeState);
                    restoreWorkers();
                } catch (Throwable verificationFailure) {
                    fail("correlation throughput report failed verification", verificationFailure);
                }
            }));
        }

        private void restoreWorkers() {
            CompletableFuture.runAsync(() -> {
                observations.start();
                ingestion.start();
            }).whenComplete((ignored, failure) -> {
                if (failureCleanupStarted.get()) {
                    return;
                }
                completion.compareAndSet(null, failure == null
                        ? new Completion(null)
                        : new Completion("could not restart ItemGraph workers after correlation probe: " + failure));
            });
        }

        private void fail(String message, Throwable failure) {
            if (completion.get() != null || !failureCleanupStarted.compareAndSet(false, true)) {
                return;
            }
            failureCleanupStartedNanos.set(System.nanoTime());
            CompletableFuture.runAsync(() -> {
                observations.stop();
                observations.clear();
                observations.start();
                ingestion.start();
            }).whenComplete((ignored, cleanupFailure) -> server.execute(() -> {
                String detail = failure == null ? message : message + ": " + failure;
                if (cleanupFailure != null) {
                    detail += "; worker restart failed: " + cleanupFailure;
                }
                completion.compareAndSet(null, new Completion(detail));
            }));
        }

        private void completeOnGameTestTick() {
            Completion result = completion.get();
            if (result == null) {
                if (System.nanoTime() - startedNanos >= COMPLETION_DEADLINE_NANOS) {
                    if (failureCleanupStarted.get()) {
                        if (System.nanoTime() - failureCleanupStartedNanos.get() >= CLEANUP_GRACE_NANOS) {
                            helper.fail("correlation probe worker cleanup exceeded its 15-second grace period");
                            return;
                        }
                    } else {
                        fail("correlation probe exceeded its three-minute completion deadline", null);
                    }
                }
                helper.runAfterDelay(1, this::completeOnGameTestTick);
            } else if (result.failure() == null) {
                if (System.nanoTime() - startedNanos >= COMPLETION_DEADLINE_NANOS) {
                    helper.fail("correlation probe completed after its three-minute deadline");
                } else {
                    helper.succeed();
                }
            } else {
                helper.fail(result.failure());
            }
        }

        private DurableSnapshot readDurableSnapshot() throws SQLException {
            try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                 var statement = connection.prepareStatement(
                         """
                         WITH benchmark_edges AS (
                             SELECT DISTINCT e.id, e.amount
                             FROM ig_inferred_edges e
                             JOIN ig_edge_evidence evidence ON evidence.edge_id = e.id
                             JOIN ig_observations observation ON observation.id = evidence.observation_id
                             WHERE observation.source_event_id BETWEEN ? AND ?
                         ), allocated AS (
                             SELECT allocation.edge_id,
                                    SUM(CASE WHEN allocation.allocation_role = 'SOURCE' THEN allocation.amount ELSE 0 END) AS source_amount,
                                    SUM(CASE WHEN allocation.allocation_role = 'DESTINATION' THEN allocation.amount ELSE 0 END) AS destination_amount
                             FROM ig_edge_allocations allocation
                             JOIN benchmark_edges edge ON edge.id = allocation.edge_id
                             GROUP BY allocation.edge_id
                         )
                         SELECT
                             (SELECT COUNT(*) FROM ig_observations
                                WHERE source_event_id BETWEEN ? AND ?) AS observations,
                             (SELECT COUNT(*) FROM ig_observations
                                WHERE source_event_id BETWEEN ? AND ? AND correlated_at IS NOT NULL) AS finalized,
                             (SELECT COUNT(*) FROM benchmark_edges) AS edges,
                             (SELECT COALESCE(SUM(amount), 0) FROM benchmark_edges) AS edge_units,
                             (SELECT COALESCE(SUM(source_amount), 0) FROM allocated) AS source_allocated,
                             (SELECT COALESCE(SUM(destination_amount), 0) FROM allocated) AS destination_allocated,
                             (SELECT COUNT(*) FROM benchmark_edges edge
                                LEFT JOIN allocated allocation ON allocation.edge_id = edge.id
                                WHERE COALESCE(allocation.source_amount, 0) <> edge.amount) AS source_mismatches,
                             (SELECT COUNT(*) FROM benchmark_edges edge
                                LEFT JOIN allocated allocation ON allocation.edge_id = edge.id
                                WHERE COALESCE(allocation.destination_amount, 0) <> edge.amount) AS destination_mismatches
                         """)) {
                statement.setLong(1, sourceEventBase);
                statement.setLong(2, sourceEventBase + OBSERVATION_COUNT - 1L);
                statement.setLong(3, sourceEventBase);
                statement.setLong(4, sourceEventBase + OBSERVATION_COUNT - 1L);
                statement.setLong(5, sourceEventBase);
                statement.setLong(6, sourceEventBase + OBSERVATION_COUNT - 1L);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new SQLException("SQLite did not return the correlation probe row count");
                    }
                    return new DurableSnapshot(rows.getLong("observations"), rows.getLong("finalized"),
                            rows.getLong("edges"), rows.getLong("edge_units"),
                            rows.getLong("source_allocated"), rows.getLong("destination_allocated"),
                            rows.getLong("source_mismatches"), rows.getLong("destination_mismatches"));
                }
            }
        }
    }

    private static InternalObservationService.InternalObservation observation(
            long eventOffset, long timestamp, String action, String playerUuid, String playerName,
            CanonicalItem item, String itemEntityUuid, long sourceEventBase) {
        String eventId = UUID.nameUUIDFromBytes(
                Long.toString(sourceEventBase + eventOffset).getBytes(StandardCharsets.UTF_8)).toString();
        byte[] rawData = ("{\"event_id\":\"" + eventId + "\"}").getBytes(StandardCharsets.UTF_8);
        return new InternalObservationService.InternalObservation(
                timestamp, action, playerUuid, playerName, "minecraft:overworld", 0, 64, 0,
                "minecraft:overworld", 1.0, 64.0, 0.0, "GROUND", item.itemId(), rawData,
                item, 1, itemEntityUuid, null, sourceEventBase + eventOffset);
    }

    private static CanonicalItem benchmarkItem(String runIdentity, int pair) {
        String stableKey = "itemgraph-correlation-performance:" + runIdentity + ":" + pair;
        return new CanonicalItem("minecraft:diamond", ItemCanonicalizer.sha256Hex(stableKey),
                null, "COMMON", "synthetic throughput probe");
    }

}
