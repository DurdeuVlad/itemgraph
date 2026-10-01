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
import java.util.concurrent.atomic.AtomicInteger;

/** Fabric runtime proof for the configured server-tick queue flush adapter. */
public final class OperationalQueueGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemGraphGameTests");
    private static final int EVENT_COUNT = 32;

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "zz_itemgraph_queue_flush", timeoutTicks = 400)
    public void endServerTickFlushPersistsAcceptedAuditEvents(GameTestHelper helper) {
        InternalObservationService service = InternalObservationService.getInstance();
        AtomicInteger endTickCallbacks = new AtomicInteger();
        ServerTickEvents.END_SERVER_TICK.register(server -> endTickCallbacks.incrementAndGet());
        String detailPrefix = "issue30-fabric-queue:" + UUID.randomUUID() + ":";
        long persistedBefore = service.getTotalPersisted();
        long droppedBefore = service.getTotalDropped();
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
        helper.runAtTickTime(flushEveryTicks + 5, () -> {
            helper.assertTrue(endTickCallbacks.get() >= flushEveryTicks,
                    "Fabric server-end-tick callback did not run through the configured flush cadence");
            long durableRows = countProbeRows(detailPrefix);
            helper.assertValueEqual((long) EVENT_COUNT, durableRows,
                    "Fabric server-end-tick callback did not flush every accepted audit event after "
                            + endTickCallbacks.get() + " ticks");
            helper.assertValueEqual(persistedBefore + EVENT_COUNT, service.getTotalPersisted(),
                    "persisted counter must match the durable Fabric probe rows");
            helper.assertValueEqual(droppedBefore, service.getTotalDropped(),
                    "queue flush must not lose an accepted Fabric probe event");
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
