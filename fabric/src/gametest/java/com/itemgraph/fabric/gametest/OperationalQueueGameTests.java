package com.itemgraph.fabric.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        awaitDurableProbe(helper, service, detailPrefix, endTickCallbacksBefore, persistedBefore,
                droppedBefore, new AtomicBoolean(), flushEveryTicks, flushEveryTicks + 5,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    private static void awaitDurableProbe(GameTestHelper helper, InternalObservationService service,
                                          String detailPrefix, int endTickCallbacksBefore,
                                          long persistedBefore, long droppedBefore,
                                          AtomicBoolean earlyLedgerReadDone, int flushEveryTicks,
                                          int checkAtTick, long deadlineNanos) {
        helper.runAtTickTime(checkAtTick, () -> {
            long persistedDelta = service.getTotalPersisted() - persistedBefore;
            long droppedDelta = service.getTotalDropped() - droppedBefore;
            boolean deadlineReached = System.nanoTime() >= deadlineNanos;
            boolean countersSuggestPersisted = persistedDelta >= EVENT_COUNT;
            boolean shouldReadLedger = droppedDelta > 0 || deadlineReached
                    || (countersSuggestPersisted && earlyLedgerReadDone.compareAndSet(false, true));
            long durableRows = -1;
            if (shouldReadLedger) {
                try {
                    durableRows = countProbeRows(detailPrefix);
                } catch (RuntimeException failure) {
                    helper.fail("Fabric audit queue durability probe could not read its ledger: "
                            + failure.getMessage() + ", queueDepth=" + service.getQueueSize()
                            + ", endTickCallbacks=" + (END_TICK_CALLBACKS.get() - endTickCallbacksBefore)
                            + ", flushEveryTicks=" + flushEveryTicks
                            + ", persistedDelta=" + persistedDelta + ", droppedDelta=" + droppedDelta);
                    return;
                }
            }
            int callbacksSinceProbe = END_TICK_CALLBACKS.get() - endTickCallbacksBefore;
            boolean persisted = durableRows == EVENT_COUNT
                    && persistedDelta >= EVENT_COUNT
                    && droppedDelta == 0
                    && callbacksSinceProbe >= flushEveryTicks;
            if (persisted) {
                helper.succeed();
                return;
            }

            if (droppedDelta > 0 || deadlineReached) {
                helper.fail("Fabric audit queue did not durably flush within the 10-second worker window: "
                        + "durableRows=" + durableRows + ", persistedDelta=" + persistedDelta
                        + ", droppedDelta=" + droppedDelta + ", queueDepth=" + service.getQueueSize()
                        + ", endTickCallbacks=" + callbacksSinceProbe
                        + ", flushEveryTicks=" + flushEveryTicks + ", expectedRows=" + EVENT_COUNT);
                return;
            }

            awaitDurableProbe(helper, service, detailPrefix, endTickCallbacksBefore, persistedBefore,
                    droppedBefore, earlyLedgerReadDone, flushEveryTicks,
                    checkAtTick + flushEveryTicks, deadlineNanos);
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
