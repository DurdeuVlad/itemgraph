package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.metrics.OperationalMetrics;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Measures idle worker activity while the loader's server tick hook continues to run. */
public final class IdlePerformanceFixture {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdlePerformanceFixture.class);
    private static final long IDLE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int IDLE_CHECK_INTERVAL_TICKS = 20;

    private IdlePerformanceFixture() { }

    public static void run(GameTestHelper helper, String loader, String griefLoggerRuntimeState) {
        Probe probe = new Probe(helper, loader, griefLoggerRuntimeState);
        CompletableFuture.runAsync(probe.ingestion::stop)
                .thenApplyAsync(ignored -> probe.stopObservationServiceAndCountDurableRows())
                .whenComplete((durableRowsBefore, failure) -> probe.server.execute(() -> {
                    if (failure != null) {
                        probe.finish("could not stop workers and read the SQLite ledger before idle sample: "
                                + failure);
                        return;
                    }
                    probe.beginSample(durableRowsBefore);
                }));
    }

    private static final class Probe {
        private final GameTestHelper helper;
        private final MinecraftServer server;
        private final String loader;
        private final String griefLoggerRuntimeState;
        private final IngestionService ingestion = IngestionService.getInstance();
        private final InternalObservationService service = InternalObservationService.getInstance();
        private OperationalMetrics.Snapshot before;
        private long enqueuedBefore;
        private long persistedBefore;
        private long droppedBefore;
        private long heartbeatsBefore;
        private long durableRowsBefore;
        private int queueBefore;
        private long startedNanos;
        private String failureMessage;

        private Probe(GameTestHelper helper, String loader, String griefLoggerRuntimeState) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.loader = loader;
            this.griefLoggerRuntimeState = griefLoggerRuntimeState;
        }

        private long stopObservationServiceAndCountDurableRows() {
            int queuedBeforeStop = service.getQueueSize();
            long droppedBeforeStop = service.getTotalDropped();
            long unknownBeforeStop = service.getTotalPersistenceOutcomeUnknown();
            service.stop();
            if (queuedBeforeStop != 0 || service.getQueueSize() != 0
                    || service.getTotalDropped() != droppedBeforeStop
                    || service.getTotalPersistenceOutcomeUnknown() != unknownBeforeStop) {
                throw new IllegalStateException("setup drain did not preserve queued evidence: queuedBefore="
                        + queuedBeforeStop + ", queuedAfter=" + service.getQueueSize()
                        + ", droppedDelta=" + (service.getTotalDropped() - droppedBeforeStop)
                        + ", uncertainDelta=" + (service.getTotalPersistenceOutcomeUnknown()
                        - unknownBeforeStop));
            }
            return countDurableEvidenceRows();
        }

        private void beginSample(long ledgerRowsBefore) {
            try {
                service.clear();
                service.start();
                durableRowsBefore = ledgerRowsBefore;
                before = OperationalMetrics.getInstance().snapshot();
                enqueuedBefore = service.getTotalEnqueued();
                persistedBefore = service.getTotalPersisted();
                droppedBefore = service.getTotalDropped();
                heartbeatsBefore = service.getTotalDatabaseHeartbeats();
                queueBefore = service.getQueueSize();
                startedNanos = System.nanoTime();
                LOGGER.info("Started one-second SQLite idle probe; raw evidence rows before sample={}",
                        durableRowsBefore);
                awaitIdleWindow();
            } catch (Throwable failure) {
                finish("could not begin isolated idle SQLite sample: " + failure);
            }
        }

        private void awaitIdleWindow() {
            helper.runAfterDelay(IDLE_CHECK_INTERVAL_TICKS, () -> {
                long elapsedNanos = System.nanoTime() - startedNanos;
                if (elapsedNanos < IDLE_WINDOW_NANOS) {
                    awaitIdleWindow();
                    return;
                }
                LOGGER.info("SQLite idle window ended after {} ms; reading raw evidence ledger",
                        TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                try {
                    captureAndValidate(elapsedNanos);
                } catch (Throwable failure) {
                    finish("idle SQLite sample failed: " + failure);
                }
            });
        }

        private void captureAndValidate(long elapsedNanos) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            var after = OperationalMetrics.getInstance().snapshot();
            long enqueuedDelta = service.getTotalEnqueued() - enqueuedBefore;
            long persistedDelta = service.getTotalPersisted() - persistedBefore;
            long droppedDelta = service.getTotalDropped() - droppedBefore;
            long heartbeatDelta = service.getTotalDatabaseHeartbeats() - heartbeatsBefore;
            int queueRemaining = service.getQueueSize();

            String counterFailure = firstFailure(
                    elapsedMs < 1_000L, "idle SQLite baseline covered less than one second",
                    queueBefore != 0, "idle SQLite baseline began with pending evidence",
                    queueRemaining != 0, "idle SQLite baseline ended with pending evidence",
                    enqueuedDelta != 0, "idle SQLite baseline enqueued evidence",
                    persistedDelta != 0, "idle SQLite baseline persisted evidence",
                    droppedDelta != 0, "idle SQLite baseline rejected evidence",
                    heartbeatDelta != 0, "idle SQLite baseline ran database heartbeats",
                    after.enqueue().count() != before.enqueue().count(), "idle SQLite baseline recorded enqueue work",
                    after.persistenceCommit().count() != before.persistenceCommit().count(),
                    "idle SQLite baseline ran persistence batches",
                    after.query().count() != before.query().count(), "idle SQLite baseline ran ItemGraph lookups",
                    after.correlation().count() != before.correlation().count(),
                    "idle SQLite baseline ran correlation work",
                    after.persistenceFailures() != before.persistenceFailures(),
                    "idle SQLite baseline failed persistence");

            CompletableFuture.supplyAsync(this::countDurableEvidenceRows)
                    .whenComplete((durableRowsAfter, ledgerFailure) -> server.execute(() -> {
                        if (ledgerFailure != null) {
                            finish("could not read the SQLite ledger after the idle sample: " + ledgerFailure);
                            return;
                        }
                        long durableRowsDelta = durableRowsAfter - durableRowsBefore;
                        LOGGER.info("SQLite idle probe raw evidence row delta={}", durableRowsDelta);
                        failureMessage = firstFailure(
                                durableRowsDelta != 0, "idle SQLite baseline changed durable evidence rows by "
                                        + durableRowsDelta,
                                counterFailure != null, counterFailure);
                        if (failureMessage == null) {
                            PerformanceReportFixture.writeIfRequested(loader, "idle_sqlite_baseline", Map.ofEntries(
                                    Map.entry("idle_window_ms", elapsedMs),
                                    Map.entry("accepted_events", enqueuedDelta),
                                    Map.entry("persisted_counter_delta", persistedDelta),
                                    Map.entry("dropped_counter_delta", droppedDelta),
                                    Map.entry("durable_rows_delta", durableRowsDelta),
                                    Map.entry("queue_remaining", (long) queueRemaining),
                                    Map.entry("database_heartbeat_delta", heartbeatDelta),
                                    Map.entry("enqueue_samples", after.enqueue().count()
                                            - before.enqueue().count()),
                                    Map.entry("persistence_samples", after.persistenceCommit().count()
                                            - before.persistenceCommit().count()),
                                    Map.entry("query_samples", after.query().count() - before.query().count()),
                                    Map.entry("correlation_samples", after.correlation().count()
                                            - before.correlation().count()),
                                    Map.entry("heap_used_before_bytes", before.heapUsedBytes()),
                                    Map.entry("heap_used_after_bytes", after.heapUsedBytes())),
                                    griefLoggerRuntimeState);
                        }
                        finish(failureMessage);
                    }));
        }

        private long countDurableEvidenceRows() {
            try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT (SELECT COUNT(*) FROM ig_observations) + "
                         + "(SELECT COUNT(*) FROM ig_audit_events) + "
                         + "(SELECT COUNT(*) FROM ig_item_transformations)")) {
                if (!rows.next()) {
                    throw new SQLException("SQLite did not return the durable evidence row count");
                }
                return rows.getLong(1);
            } catch (SQLException failure) {
                throw new IllegalStateException("Could not read ItemGraph's raw SQLite evidence ledger", failure);
            }
        }

        private void finish(String failure) {
            failureMessage = failure;
            LOGGER.info("Completing SQLite idle GameTest: {}", failureMessage == null ? "passed" : failureMessage);
            // The queue-flush GameTest runs in a later batch and starts its own
            // observation worker. It does not need the scheduled ingestion worker.
            // Keep ingestion stopped through this test server's shutdown so it cannot
            // add correlation work to the measured idle window.
            if (failureMessage != null) {
                helper.fail(failureMessage);
            } else {
                helper.succeed();
            }
        }

        private static String firstFailure(Object... conditionsAndMessages) {
            for (int i = 0; i < conditionsAndMessages.length; i += 2) {
                if ((boolean) conditionsAndMessages[i]) {
                    return (String) conditionsAndMessages[i + 1];
                }
            }
            return null;
        }
    }
}
