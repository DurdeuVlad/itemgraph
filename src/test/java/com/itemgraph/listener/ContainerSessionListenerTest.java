package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.listener.ContainerInteractionTracker.ContainerKey;
import com.itemgraph.listener.ContainerInteractionTracker.InventoryTotals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ContainerSessionListenerTest {

    private static final ContainerKey KEY = new ContainerKey("minecraft:overworld", 10, 64, -20);
    private static final CanonicalItem DIAMOND =
            new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
    private final ContainerInteractionTracker tracker = ContainerInteractionTracker.getInstance();

    @BeforeEach
    void setUp() {
        tracker.clearAll();
        InternalObservationService.getInstance().clear();
    }

    @AfterEach
    void tearDown() {
        tracker.clearAll();
        InternalObservationService.getInstance().clear();
    }

    @Test
    void sessionNetDeltaCarriesItsOpenCloseInterval() throws Exception {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(10));
        UUID playerUuid = UUID.randomUUID();
        tracker.openSession(playerUuid, "TestPlayer", KEY, live::get, List.of());
        live.set(totals(7));
        tracker.closeSession(playerUuid, 1, 64, 1);

        InternalObservationService.InternalObservation observation = pendingObservations().poll();
        assertNotNull(observation);
        assertEquals("REMOVE_ITEM", observation.actionType());
        assertEquals(3, observation.amount());
        assertNotNull(observation.timestampEndMs());
        assertTrue(observation.timestampEndMs() >= observation.timestampMs());
        String rawData = new String(observation.rawData(), StandardCharsets.UTF_8);
        assertTrue(rawData.contains("\"capture\":\"container_session_net_delta\""));
        assertTrue(rawData.contains("\"sessionStartMs\":" + observation.timestampMs()));
        assertTrue(rawData.contains("\"sessionEndMs\":" + observation.timestampEndMs()));
    }

    private InventoryTotals totals(long amount) {
        return new InventoryTotals(
                Map.of(DIAMOND.fingerprintHash(), amount),
                Map.of(DIAMOND.fingerprintHash(), DIAMOND));
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<InternalObservationService.InternalObservation> pendingObservations() {
        try {
            Field field = InternalObservationService.class.getDeclaredField("queue");
            field.setAccessible(true);
            return (BlockingQueue<InternalObservationService.InternalObservation>)
                    field.get(InternalObservationService.getInstance());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reach InternalObservationService.queue", e);
        }
    }
}
