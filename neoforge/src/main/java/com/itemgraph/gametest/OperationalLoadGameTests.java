package com.itemgraph.gametest;

import com.itemgraph.ItemGraph;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.gametest.CorrelationPerformanceFixture;
import com.itemgraph.gametest.IdlePerformanceFixture;
import com.itemgraph.gametest.PerformanceReportFixture;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Development-only operational checks. NeoForge release jar tasks exclude this package. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class OperationalLoadGameTests {
    private static final int EVENTS_PER_BATCH = 400;
    private static final int BATCH_COUNT = 20;
    private static final int EVENT_COUNT = EVENTS_PER_BATCH * BATCH_COUNT;
    private static final long SERVER_TICK_BUDGET_NANOS = 50_000_000L;

    private OperationalLoadGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "zzzzzz_itemgraph_idle_baseline",
            timeoutTicks = 60_000)
    public static void idleSqliteWorkerProducesNoEvidenceWork(GameTestHelper helper) {
        boolean griefLoggerInstalled = ModList.get().isLoaded("grieflogger");
        helper.assertFalse(griefLoggerInstalled, "The isolated idle probe must run without GriefLogger installed");
        IdlePerformanceFixture.run(helper, "neoforge", griefLoggerInstalled ? "present" : "absent");
    }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "zzzzz_itemgraph_correlation_burst",
            timeoutTicks = 60_000)
    public static void correlationThroughputUsesPersistedQuantityEvidence(GameTestHelper helper) {
        boolean griefLoggerInstalled = ModList.get().isLoaded("grieflogger");
        helper.assertFalse(griefLoggerInstalled,
                "The isolated correlation probe must run without GriefLogger installed");
        CorrelationPerformanceFixture.run(helper, "neoforge", griefLoggerInstalled ? "present" : "absent");
    }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "zz_itemgraph_queue_burst",
            timeoutTicks = 20_000)
    public static void nativeAuditQueueBurstPersistsOnWorkerWithoutBlockingServerThread(GameTestHelper helper) {
        helper.assertFalse(ModList.get().isLoaded("grieflogger"),
                "The isolated operational probe must run without GriefLogger installed");
        InternalObservationService observations = InternalObservationService.getInstance();
        observations.stop();
        observations.clear();
        observations.start();
        String detailPrefix = "issue30-load-probe:" + UUID.randomUUID() + ":";

        long persistedBefore = observations.getTotalPersisted();
        long droppedBefore = observations.getTotalDropped();
        AtomicInteger submitted = new AtomicInteger();
        long[] acceptedDurationNanos = {0L};
        long[] maxBatchDurationNanos = {0L};
        int[] peakQueueSize = {observations.getQueueSize()};
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
                            detailPrefix + sequence,
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

        long drainDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        awaitQueueDrain(helper, observations, detailPrefix, submitted, persistedBefore, droppedBefore,
                maxBatchDurationNanos, drainDeadlineNanos, BATCH_COUNT + 1);
    }

    private static void awaitQueueDrain(GameTestHelper helper, InternalObservationService observations,
                                        String detailPrefix, AtomicInteger submitted,
                                        long persistedBefore, long droppedBefore,
                                        long[] maxBatchDurationNanos, long drainDeadlineNanos, int checkAtTick) {
        helper.runAtTickTime(checkAtTick, () -> {
            int submittedCount = submitted.get();
            if (submittedCount != EVENT_COUNT) {
                if (System.nanoTime() >= drainDeadlineNanos) {
                    helper.fail("server-thread load probe submitted " + submittedCount + "/" + EVENT_COUNT
                            + " batches before the 30-second deadline");
                    return;
                }
                awaitQueueDrain(helper, observations, detailPrefix, submitted, persistedBefore,
                        droppedBefore, maxBatchDurationNanos, drainDeadlineNanos, checkAtTick + 20);
                return;
            }
            long persistedDelta = observations.getTotalPersisted() - persistedBefore;
            long droppedDelta = observations.getTotalDropped() - droppedBefore;
            int queueRemaining = observations.getQueueSize();
            if (droppedDelta > 0) {
                helper.fail("native audit queue lost " + droppedDelta + " load-probe events");
                return;
            }
            if (persistedDelta < EVENT_COUNT || queueRemaining != 0) {
                if (System.nanoTime() >= drainDeadlineNanos) {
                    helper.fail("background worker exceeded the 30-second drain deadline: persisted="
                            + persistedDelta + "/" + EVENT_COUNT + " dropped=" + droppedDelta
                            + " queueRemaining=" + queueRemaining);
                    return;
                }
                awaitQueueDrain(helper, observations, detailPrefix, submitted, persistedBefore,
                        droppedBefore, maxBatchDurationNanos, drainDeadlineNanos, checkAtTick + 20);
                return;
            }

            CompletableFuture<Long> durableProbe = CompletableFuture.supplyAsync(
                    () -> countProbeRows(detailPrefix));
            awaitDurableProbe(helper, maxBatchDurationNanos,
                    persistedDelta, droppedDelta, queueRemaining, durableProbe,
                    drainDeadlineNanos, checkAtTick + 1);
        });
    }

    private static void awaitDurableProbe(GameTestHelper helper, long[] maxBatchDurationNanos,
                                          long persistedDelta, long droppedDelta, int queueRemaining,
                                          CompletableFuture<Long> durableProbe, long deadlineNanos,
                                          int checkAtTick) {
        helper.runAtTickTime(checkAtTick, () -> {
            if (!durableProbe.isDone()) {
                if (System.nanoTime() >= deadlineNanos) {
                    durableProbe.cancel(true);
                    helper.fail("ItemGraph SQLite durability read exceeded the 30-second worker deadline");
                    return;
                }
                awaitDurableProbe(helper, maxBatchDurationNanos,
                        persistedDelta, droppedDelta, queueRemaining, durableProbe,
                        deadlineNanos, checkAtTick + 20);
                return;
            }

            final long durableRows;
            try {
                durableRows = durableProbe.join();
            } catch (RuntimeException readFailure) {
                helper.fail("Could not read durable probe rows off the server thread: "
                        + readFailure.getMessage());
                return;
            }
            completeDurableProbe(helper, maxBatchDurationNanos,
                    persistedDelta, droppedDelta, queueRemaining, durableRows);
        });
    }

    private static void completeDurableProbe(GameTestHelper helper, long[] maxBatchDurationNanos,
                                             long persistedDelta, long droppedDelta, int queueRemaining,
                                             long durableRows) {
        ItemGraph.LOGGER.info(
                "Issue #30 local NeoForge probe after worker drain: persistedDelta={} "
                        + "droppedDelta={} queueRemaining={} durableProbeRows={}",
                persistedDelta, droppedDelta, queueRemaining, durableRows);
        helper.assertTrue(maxBatchDurationNanos[0] < SERVER_TICK_BUDGET_NANOS,
                "one batch of bounded queue submissions exceeded the 50 ms server tick budget");
        helper.assertValueEqual((long) EVENT_COUNT, durableRows,
                "all accepted load-probe rows must be durable in ItemGraph's SQLite ledger");
        PerformanceReportFixture.writeIfRequested("neoforge", "queue_burst", Map.of(
                "accepted_events", (long) EVENT_COUNT,
                "batch_count", (long) BATCH_COUNT,
                "batch_size", (long) EVENTS_PER_BATCH,
                "persisted_counter_delta", persistedDelta,
                "dropped_counter_delta", droppedDelta,
                "durable_rows", durableRows,
                "queue_remaining", (long) queueRemaining,
                "max_server_thread_batch_ns", maxBatchDurationNanos[0]),
                ModList.get().isLoaded("grieflogger") ? "present" : "absent");
        helper.succeed();
    }

    private static long countProbeRows(String detailPrefix) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ig_audit_events WHERE detail LIKE ?")) {
            statement.setString(1, detailPrefix + "%");
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
