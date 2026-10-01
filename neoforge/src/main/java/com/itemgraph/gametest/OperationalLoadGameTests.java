package com.itemgraph.gametest;

import com.itemgraph.ItemGraph;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Development-only operational checks. NeoForge release jar tasks exclude this package. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class OperationalLoadGameTests {
    private static final String PROBE_PREFIX = "issue30-load-probe:";
    private static final int EVENTS_PER_BATCH = 400;
    private static final int BATCH_COUNT = 20;
    private static final int EVENT_COUNT = EVENTS_PER_BATCH * BATCH_COUNT;
    private static final long SERVER_TICK_BUDGET_NANOS = 50_000_000L;

    private OperationalLoadGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty", timeoutTicks = 300_000)
    public static void nativeAuditQueueBurstPersistsOnWorkerWithoutBlockingServerThread(GameTestHelper helper) {
        helper.assertFalse(ModList.get().isLoaded("grieflogger"),
                "The isolated operational probe must run without GriefLogger installed");

        InternalObservationService observations = InternalObservationService.getInstance();
        observations.start();
        long priorRows = countProbeRows();

        long persistedBefore = observations.getTotalPersisted();
        long droppedBefore = observations.getTotalDropped();
        AtomicInteger submitted = new AtomicInteger();
        long[] acceptedDurationNanos = {0L};
        long[] maxBatchDurationNanos = {0L};
        int[] peakQueueSize = {observations.getQueueSize()};
        String serverThreadName = Thread.currentThread().getName();

        for (int batch = 0; batch < BATCH_COUNT; batch++) {
            final int batchNumber = batch;
            helper.runAtTickTime(batch + 1, () -> {
                long startedAt = System.nanoTime();
                for (int offset = 0; offset < EVENTS_PER_BATCH; offset++) {
                    int sequence = batchNumber * EVENTS_PER_BATCH + offset;
                    var event = new InternalObservationService.InternalAuditEvent(
                            System.currentTimeMillis(),
                            "ISSUE30_LOAD_PROBE",
                            null,
                            null,
                            "minecraft:overworld",
                            0,
                            64,
                            0,
                            "itemgraph:issue30-load-probe",
                            PROBE_PREFIX + (priorRows + sequence),
                            null);
                    helper.assertTrue(observations.submitAuditEvent(event),
                            "bounded native audit queue rejected probe event " + sequence);
                    submitted.incrementAndGet();
                }
                long batchDuration = System.nanoTime() - startedAt;
                acceptedDurationNanos[0] += batchDuration;
                maxBatchDurationNanos[0] = Math.max(maxBatchDurationNanos[0], batchDuration);
                peakQueueSize[0] = Math.max(peakQueueSize[0], observations.getQueueSize());
                if (batchNumber == BATCH_COUNT - 1) {
                    ItemGraph.LOGGER.info(
                            "Issue #30 local NeoForge probe: loader=NeoForge 21.1.248 mc=1.21.1 "
                                    + "grieflogger=false accepted={} batches={} batchSize={} "
                                    + "totalEnqueueMs={} maxServerThreadBatchMs={} peakQueue={} "
                                    + "queueCapacityPerType=10000 flushEveryTicks={} idlePollMs={} maxBatchSize={}",
                            EVENT_COUNT,
                            BATCH_COUNT,
                            EVENTS_PER_BATCH,
                            acceptedDurationNanos[0] / 1_000_000.0,
                            maxBatchDurationNanos[0] / 1_000_000.0,
                            peakQueueSize[0],
                            observations.getQueueFrequencyTicks(),
                            observations.getQueuePollIntervalMs(),
                            observations.getMaxBatchSize());
                }
            });
        }

        var server = helper.getLevel().getServer();
        CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(() -> server.execute(() -> {
            try {
                helper.assertValueEqual(EVENT_COUNT, submitted.get(),
                        "all scheduled server-thread batches must be submitted");
                long persistedDelta = observations.getTotalPersisted() - persistedBefore;
                long droppedDelta = observations.getTotalDropped() - droppedBefore;
                int queueRemaining = observations.getQueueSize();
                long durableRows = countProbeRows();
                ItemGraph.LOGGER.info(
                        "Issue #30 local NeoForge probe after worker-drain window: persistedDelta={} "
                                + "droppedDelta={} queueRemaining={} durableProbeRows={}",
                        persistedDelta, droppedDelta, queueRemaining, durableRows);
                helper.assertTrue(maxBatchDurationNanos[0] < SERVER_TICK_BUDGET_NANOS,
                        "one batch of bounded queue submissions exceeded the 50 ms server tick budget");
                helper.assertValueEqual(0L, droppedDelta,
                        "native audit queue load must not lose evidence");
                helper.assertTrue(persistedDelta >= EVENT_COUNT,
                        "background worker persisted only " + persistedDelta + " of " + EVENT_COUNT
                                + " load-probe events; queueRemaining=" + queueRemaining);
                helper.assertValueEqual(0, queueRemaining,
                        "all native queues must drain after the probe");
                helper.assertValueEqual(priorRows + EVENT_COUNT, durableRows,
                        "all accepted load-probe rows must be durable in ItemGraph's SQLite ledger");
                helper.succeed();
            } catch (Throwable failure) {
                helper.fail("Issue #30 load probe failed: " + failure.getMessage());
            }
        }));
    }

    private static long countProbeRows() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ig_audit_events WHERE detail LIKE ?")) {
            statement.setString(1, PROBE_PREFIX + "%");
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("SQLite did not return the load-probe count");
                }
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read the ItemGraph load-probe ledger", failure);
        }
    }
}
