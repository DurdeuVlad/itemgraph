package com.itemgraph.fabric.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.gametest.PerformanceReportFixture;
import com.itemgraph.ingest.InternalObservationService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fabric runtime proof for the configured server-tick queue flush adapter. */
public final class OperationalQueueGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemGraphGameTests");
    private static final int EVENT_COUNT = 32;
    private static final AtomicInteger END_TICK_CALLBACKS = new AtomicInteger();

    static {
        ServerTickEvents.END_SERVER_TICK.register(server -> END_TICK_CALLBACKS.incrementAndGet());
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "zz_itemgraph_queue_flush", timeoutTicks = 300_000)
    public void endServerTickFlushPersistsAcceptedAuditEvents(GameTestHelper helper) {
        InternalObservationService service = InternalObservationService.getInstance();
        String detailPrefix = "issue30-fabric-queue:" + UUID.randomUUID() + ":";
        long persistedBefore = service.getTotalPersisted();
        long droppedBefore = service.getTotalDropped();
        int endTickCallbacksBefore = END_TICK_CALLBACKS.get();
        long startedAt = System.nanoTime();

        for (int sequence = 0; sequence < EVENT_COUNT; sequence++) {
            var event = new InternalObservationService.InternalAuditEvent(
                    System.currentTimeMillis(), "ISSUE30_FABRIC_QUEUE_PROBE", null, null,
                    "minecraft:overworld", 0, 64, 0, "itemgraph:issue30-fabric-queue",
                    detailPrefix + sequence, null);
            helper.assertTrue(service.submitAuditEvent(event),
                    "bounded Fabric audit queue rejected probe event " + sequence);
        }

        long enqueueNanos = System.nanoTime() - startedAt;
        helper.assertTrue(enqueueNanos < 50_000_000L,
                "32 Fabric queue submissions exceeded the 50 ms server tick budget");

        int flushEveryTicks = service.getQueueFrequencyTicks();
        LOGGER.info("Issue #30 Fabric queue probe: flushEveryTicks={} accepted={}", flushEveryTicks, EVENT_COUNT);
        awaitDurableProbe(helper, service, detailPrefix, endTickCallbacksBefore, persistedBefore, droppedBefore,
                flushEveryTicks, flushEveryTicks + 5,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10), enqueueNanos);
    }

    private static void awaitDurableProbe(GameTestHelper helper, InternalObservationService service,
                                          String detailPrefix, int endTickCallbacksBefore, long persistedBefore,
                                          long droppedBefore, int flushEveryTicks, int checkAtTick,
                                          long deadlineNanos, long enqueueNanos) {
        helper.runAtTickTime(checkAtTick, () -> {
            long persistedDelta = service.getTotalPersisted() - persistedBefore;
            long droppedDelta = service.getTotalDropped() - droppedBefore;
            int callbacksSinceProbe = END_TICK_CALLBACKS.get() - endTickCallbacksBefore;
            boolean persisted = persistedDelta >= EVENT_COUNT && droppedDelta == 0
                    && service.getQueueSize() == 0 && callbacksSinceProbe >= flushEveryTicks;
            if (persisted) {
                CompletableFuture<Long> ledgerRead = CompletableFuture.supplyAsync(
                        () -> countProbeRows(detailPrefix));
                awaitLedgerRead(helper, service, detailPrefix, ledgerRead, flushEveryTicks,
                        checkAtTick + 1, deadlineNanos, enqueueNanos, persistedDelta,
                        droppedDelta, callbacksSinceProbe);
                return;
            }
            if (droppedDelta > 0 || System.nanoTime() >= deadlineNanos) {
                helper.fail("Fabric audit queue did not durably flush within the 10-second worker window: "
                        + "persistedDelta=" + persistedDelta + ", droppedDelta=" + droppedDelta
                        + ", queueDepth=" + service.getQueueSize()
                        + ", endTickCallbacks=" + callbacksSinceProbe
                        + ", flushEveryTicks=" + flushEveryTicks + ", expectedRows=" + EVENT_COUNT);
                return;
            }
            awaitDurableProbe(helper, service, detailPrefix, endTickCallbacksBefore, persistedBefore,
                    droppedBefore, flushEveryTicks, checkAtTick + flushEveryTicks,
                    deadlineNanos, enqueueNanos);
        });
    }

    private static void awaitLedgerRead(GameTestHelper helper, InternalObservationService service,
                                        String detailPrefix, CompletableFuture<Long> ledgerRead,
                                        int flushEveryTicks, int checkAtTick, long deadlineNanos,
                                        long enqueueNanos, long persistedDelta, long droppedDelta,
                                        int callbacksSinceProbe) {
        helper.runAtTickTime(checkAtTick, () -> {
            if (!ledgerRead.isDone()) {
                if (System.nanoTime() >= deadlineNanos) {
                    helper.fail("Fabric queue ledger read exceeded the 10-second worker window");
                    return;
                }
                awaitLedgerRead(helper, service, detailPrefix, ledgerRead, flushEveryTicks,
                        checkAtTick + 1, deadlineNanos, enqueueNanos, persistedDelta,
                        droppedDelta, callbacksSinceProbe);
                return;
            }
            long durableRows;
            try {
                durableRows = ledgerRead.join();
            } catch (RuntimeException failure) {
                helper.fail("Fabric queue durability probe could not read its ledger off the server thread: "
                        + failure.getMessage());
                return;
            }
            if (durableRows != EVENT_COUNT) {
                helper.fail("Fabric queue ledger contains " + durableRows + " probe rows; expected "
                        + EVENT_COUNT + ", persistedCounterDelta=" + persistedDelta);
                return;
            }
            PerformanceReportFixture.writeIfRequested("fabric", "queue_flush_durability", Map.of(
                    "accepted_events", (long) EVENT_COUNT,
                    "persisted_counter_delta", persistedDelta,
                    "dropped_counter_delta", droppedDelta,
                    "durable_rows", durableRows,
                    "queue_remaining", (long) service.getQueueSize(),
                    "end_tick_callbacks", (long) callbacksSinceProbe,
                    "flush_every_ticks", (long) flushEveryTicks,
                    "enqueue_total_ns", enqueueNanos));
            helper.succeed();
        });
    }

    private static long countProbeRows(String detailPrefix) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM ig_audit_events WHERE detail LIKE ?")) {
            statement.setString(1, detailPrefix + "%");
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("SQLite did not return the Fabric queue-probe count");
                }
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read the Fabric queue-probe ledger", failure);
        }
    }
}
