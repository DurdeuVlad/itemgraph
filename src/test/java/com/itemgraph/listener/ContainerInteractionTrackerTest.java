package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.listener.ContainerInteractionTracker.ContainerKey;
import com.itemgraph.listener.ContainerInteractionTracker.InventoryTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContainerInteractionTracker} (0.2.0 — Issue 3).
 *
 * <p>The tracker is pure Java: container contents are supplied as
 * {@link InventoryTotals} snapshots by the listener, so session binding, baseline
 * diffing, automation-credit subtraction, and ambiguous attribution are all
 * exercised here without a Minecraft runtime.
 */
class ContainerInteractionTrackerTest {

    private static final ContainerKey KEY = new ContainerKey("minecraft:overworld", 10, 64, -20);
    private static final ContainerKey KEY_B = new ContainerKey("minecraft:overworld", 11, 64, -20);

    private static final CanonicalItem DIAMOND =
            new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
    private static final CanonicalItem IRON =
            new CanonicalItem("minecraft:iron_ingot", "fp-iron", null, null, null);

    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID ALEX = UUID.randomUUID();

    private ContainerInteractionTracker tracker;

    @BeforeEach
    void reset() {
        tracker = ContainerInteractionTracker.getInstance();
        tracker.clearAll();
        pendingObservations().clear();
    }

    private static InventoryTotals totals(Object... fpCounts) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, CanonicalItem> exemplars = new HashMap<>();
        for (int i = 0; i < fpCounts.length; i += 2) {
            CanonicalItem item = (CanonicalItem) fpCounts[i];
            counts.put(item.fingerprintHash(), ((Number) fpCounts[i + 1]).longValue());
            exemplars.put(item.fingerprintHash(), item);
        }
        return new InventoryTotals(counts, exemplars);
    }

    private static InventoryTotals empty() {
        return new InventoryTotals(Map.of(), Map.of());
    }

    // -------------------------------------------------------------------------
    // Sole-player attribution
    // -------------------------------------------------------------------------

    @Test
    void solePlayerDepositEmitsAddItem() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());

        live.set(totals(DIAMOND, 13)); // player put 8 diamonds in
        tracker.closeSession(STEVE, 1, 64, 1);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs, "a +8 residual must emit one observation");
        assertEquals("ADD_ITEM", obs.actionType());
        assertEquals(STEVE.toString(), obs.playerUuid());
        assertEquals("Steve", obs.playerName());
        assertEquals(8, obs.amount());
        assertEquals("CONTAINER", obs.targetType());
        assertEquals((double) KEY.x(), obs.targetX());
        assertSame(DIAMOND, obs.item());
        assertNull(obs.rawData(), "unambiguous attribution carries no candidate list");
        assertTrue(pendingObservations().isEmpty());
    }

    @Test
    void solePlayerWithdrawalEmitsRemoveItem() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 10));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());

        live.set(totals(DIAMOND, 3));
        tracker.closeSession(STEVE, 1, 64, 1);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("REMOVE_ITEM", obs.actionType());
        assertEquals(STEVE.toString(), obs.playerUuid());
        assertEquals(7, obs.amount());
    }

    @Test
    void noDeltaEmitsNothing() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());
        tracker.closeSession(STEVE, 1, 64, 1);

        assertTrue(pendingObservations().isEmpty(), "unchanged contents must not emit");
    }

    @Test
    void removedItemKeepsBaselineExemplar() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(IRON, 4));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());

        live.set(empty()); // all iron removed
        tracker.closeSession(STEVE, 1, 64, 1);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs, "a fully-emptied fingerprint must still emit (exemplar from baseline)");
        assertEquals("REMOVE_ITEM", obs.actionType());
        assertEquals(4, obs.amount());
        assertSame(IRON, obs.item());
    }

    // -------------------------------------------------------------------------
    // Automation credits — concurrent machine traffic is not attributed to players
    // -------------------------------------------------------------------------

    @Test
    void automationCreditFullyOffsetsMachineDelta() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());

        live.set(totals(DIAMOND, 10));           // hopper pushed +5 while open
        tracker.recordAutomationDelta(KEY, "fp-diamond", 5);
        tracker.closeSession(STEVE, 1, 64, 1);

        assertTrue(pendingObservations().isEmpty(),
                "a delta fully explained by automation credits must emit nothing");
    }

    @Test
    void automationCreditLeavesPlayerResidual() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());

        live.set(totals(DIAMOND, 13));           // +8 total: hopper +5, player +3
        tracker.recordAutomationDelta(KEY, "fp-diamond", 5);
        tracker.closeSession(STEVE, 1, 64, 1);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("ADD_ITEM", obs.actionType());
        assertEquals(3, obs.amount(), "only the residual beyond the automation credit is the player's");
    }

    @Test
    void creditOnUnwatchedContainerIsIgnored() {
        tracker.recordAutomationDelta(KEY, "fp-diamond", 5);
        assertEquals(0, tracker.watchCount(), "credits must never create a watch");
    }

    @Test
    void aliasedPositionFeedsSameWatch() {
        // Double chest: watch keyed at clicked half, automation on partner half.
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of(KEY_B));

        live.set(totals(DIAMOND, 9));
        tracker.recordAutomationDelta(KEY_B, "fp-diamond", 4); // hopper hit the other half
        tracker.closeSession(STEVE, 1, 64, 1);

        assertTrue(pendingObservations().isEmpty(),
                "automation on the aliased half must credit the shared watch");
        assertEquals(0, tracker.watchCount(), "watch must be destroyed after last close");
    }

    // -------------------------------------------------------------------------
    // Ambiguous attribution
    // -------------------------------------------------------------------------

    @Test
    void multipleParticipantsProduceAmbiguousObservation() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());
        tracker.openSession(ALEX, "Alex", KEY, live::get, List.of());

        live.set(totals(DIAMOND, 9));
        tracker.closeSession(STEVE, 1, 64, 1);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("ADD_ITEM", obs.actionType());
        assertEquals(ContainerInteractionTracker.AMBIGUOUS_UUID, obs.playerUuid(),
                "two participants during the window must not guess one actor");
        assertEquals(ContainerInteractionTracker.AMBIGUOUS_NAME, obs.playerName());
        String raw = new String(obs.rawData(), StandardCharsets.UTF_8);
        assertTrue(raw.contains("Steve") && raw.contains("Alex"),
                "raw_data must preserve the candidate list: " + raw);
        assertEquals(4, obs.amount(), "exactly one row — quantity must not multiply per candidate");
        assertTrue(pendingObservations().isEmpty());

        // Alex still holds the container: her close diff covers only the new window.
        tracker.closeSession(ALEX, 1, 64, 1);
        assertTrue(pendingObservations().isEmpty(),
                "baseline reset at first close must prevent double-counting");
    }

    @Test
    void sequentialSessionsAttributeIndependently() {
        AtomicReference<InventoryTotals> live = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, live::get, List.of());
        live.set(totals(DIAMOND, 7));
        tracker.closeSession(STEVE, 1, 64, 1);   // +2 → Steve

        tracker.openSession(ALEX, "Alex", KEY, live::get, List.of());
        live.set(totals(DIAMOND, 4));
        tracker.closeSession(ALEX, 1, 64, 1);    // -3 → Alex

        InternalObservationService.InternalObservation first = pendingObservations().poll();
        InternalObservationService.InternalObservation second = pendingObservations().poll();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(STEVE.toString(), first.playerUuid());
        assertEquals("ADD_ITEM", first.actionType());
        assertEquals(ALEX.toString(), second.playerUuid());
        assertEquals("REMOVE_ITEM", second.actionType());
        assertEquals(3, second.amount());
    }

    // -------------------------------------------------------------------------
    // Session bookkeeping
    // -------------------------------------------------------------------------

    @Test
    void closeWithoutSessionIsNoop() {
        tracker.closeSession(STEVE, 0, 0, 0);
        assertTrue(pendingObservations().isEmpty());
    }

    @Test
    void reopeningWithoutCloseImplicitlyClosesOldSession() {
        AtomicReference<InventoryTotals> liveA = new AtomicReference<>(totals(DIAMOND, 5));
        tracker.openSession(STEVE, "Steve", KEY, liveA::get, List.of());
        liveA.set(totals(DIAMOND, 8));

        AtomicReference<InventoryTotals> liveB = new AtomicReference<>(empty());
        tracker.openSession(STEVE, "Steve", KEY_B, liveB::get, List.of());

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs, "the abandoned session must flush its delta before rebinding");
        assertEquals("ADD_ITEM", obs.actionType());
        assertEquals(3, obs.amount());
        assertEquals(1, tracker.sessionCount(), "one player can hold exactly one session");
    }

    @Test
    void computePlayerDeltaHandlesMixedFingerprints() {
        Map<String, Long> delta = ContainerInteractionTracker.computePlayerDelta(
                Map.of("a", 10L, "b", 4L),
                Map.of("a", 13L, "c", 2L),
                Map.of("a", 1L));
        assertEquals(Map.of("a", 2L, "b", -4L, "c", 2L), delta);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static BlockingQueue<InternalObservationService.InternalObservation> pendingObservations() {
        try {
            Field f = InternalObservationService.class.getDeclaredField("queue");
            f.setAccessible(true);
            return (BlockingQueue<InternalObservationService.InternalObservation>)
                    f.get(InternalObservationService.getInstance());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reach InternalObservationService.queue", e);
        }
    }
}
