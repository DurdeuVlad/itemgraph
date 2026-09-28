package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContainerCapabilityWrapper} (0.2.0 — Issue 4).
 *
 * <p>These tests avoid bootstrapping global item registries and use explicit canonical
 * fingerprints for completed transfers. Delegation is verified with an {@link IItemHandler}
 * mock; the pending-observation queue is read via reflection without starting the worker.
 *
 * <p>{@code IItemHandler} does not identify its caller, so capability observations retain
 * an UNKNOWN remote endpoint and do not claim hopper or automation identity.
 */
class ContainerCapabilityWrapperTest {

    private static final BlockPos POS = new BlockPos(10, 64, -20);
    private static final BlockPos POS_B = new BlockPos(11, 64, -20);

    private static final CanonicalItem DIAMOND =
            new CanonicalItem("minecraft:diamond", "fingerprint-diamond", null, null, null);

    @BeforeEach
    void drainQueuesAndContexts() {
        InternalObservationService.getInstance().clear();
        ContainerInteractionTracker.getInstance().clearAll();
    }

    // -------------------------------------------------------------------------
    // Simulate/movement guard — the predicate behind insertItem/extractItem
    // -------------------------------------------------------------------------

    @Test
    void shouldEmitIsFalseForSimulatedCalls() {
        assertFalse(ContainerCapabilityWrapper.shouldEmit(true, 8, 0),
                "simulate=true must suppress observation even when items would move");
        assertFalse(ContainerCapabilityWrapper.shouldEmit(true, 8, 8));
    }

    @Test
    void shouldEmitIsFalseWhenNothingMoved() {
        assertFalse(ContainerCapabilityWrapper.shouldEmit(false, 8, 8),
                "a real call whose remainder equals the input moved nothing");
        assertFalse(ContainerCapabilityWrapper.shouldEmit(false, 0, 0));
    }

    @Test
    void shouldEmitIsTrueForRealTransfers() {
        assertTrue(ContainerCapabilityWrapper.shouldEmit(false, 8, 0),
                "full insert: requested 8, remainder 0");
        assertTrue(ContainerCapabilityWrapper.shouldEmit(false, 8, 3),
                "partial insert: 5 of 8 moved");
        assertTrue(ContainerCapabilityWrapper.shouldEmit(false, 4, 0),
                "extract passes remainder=0: any non-empty extraction counts");
    }

    // -------------------------------------------------------------------------
    // Action mapping via submitObservation — caller and remote endpoint remain unknown
    // -------------------------------------------------------------------------

    @Test
    void insertProducesCapabilityInsertWithUnknownCaller() throws Exception {
        ContainerCapabilityWrapper wrapper = wrapperAt(POS);

        wrapper.submitObservation("INSERT", 5, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs, "a real insertion must emit exactly one observation");
        assertEquals("CAPABILITY_INSERT", obs.actionType());
        assertEquals("00000000-0000-0000-0000-000000000000", obs.playerUuid());
        assertEquals("[capability caller unknown]", obs.playerName());
        assertEquals("CONTAINER", obs.targetType());
        assertEquals(5, obs.amount());
        assertEquals("minecraft:diamond", obs.itemId());
        assertSame(DIAMOND, obs.item());
        assertEquals("minecraft:overworld", obs.levelName());
        assertEquals("minecraft:overworld", obs.targetLevelName());
        assertEquals((double) POS.getX(), obs.targetX());
        assertEquals((double) POS.getY(), obs.targetY());
        assertEquals((double) POS.getZ(), obs.targetZ());
        assertNull(obs.itemEntityUuid());
        assertTrue(pendingObservations().isEmpty(), "one insertion must emit exactly one observation");
    }

    @Test
    void extractProducesCapabilityExtractWithUnknownDestination() throws Exception {
        ContainerCapabilityWrapper wrapper = wrapperAt(POS);

        wrapper.submitObservation("EXTRACT", 4, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("CAPABILITY_EXTRACT", obs.actionType());
        assertEquals("00000000-0000-0000-0000-000000000000", obs.playerUuid());
        assertEquals(4, obs.amount());
        assertTrue(pendingObservations().isEmpty());
    }

    @Test
    void capabilityObservationPreventsTheSameDeltaFromBeingAttributedToAnOpenPlayer() throws Exception {
        ContainerInteractionTracker tracker = ContainerInteractionTracker.getInstance();
        var key = new ContainerInteractionTracker.ContainerKey("minecraft:overworld", POS.getX(), POS.getY(), POS.getZ());
        AtomicReference<ContainerInteractionTracker.InventoryTotals> live = new AtomicReference<>(
                new ContainerInteractionTracker.InventoryTotals(java.util.Map.of(), java.util.Map.of()));
        java.util.UUID playerUuid = java.util.UUID.randomUUID();
        tracker.openSession(playerUuid, "Steve", key, live::get, java.util.List.of());

        ContainerCapabilityWrapper wrapper = wrapperAt(POS);
        wrapper.submitObservation("INSERT", 3, DIAMOND);
        live.set(new ContainerInteractionTracker.InventoryTotals(
                java.util.Map.of(DIAMOND.fingerprintHash(), 3L),
                java.util.Map.of(DIAMOND.fingerprintHash(), DIAMOND)));
        tracker.closeSession(playerUuid, 1, 64, 1);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("CAPABILITY_INSERT", obs.actionType(),
                "capability traffic remains a separate observation while a player session is open");
        assertEquals("00000000-0000-0000-0000-000000000000", obs.playerUuid());
        assertEquals("[capability caller unknown]", obs.playerName());
        assertNull(pendingObservations().poll(), "the same inventory delta must not also become a player transfer");
    }

    @Test
    void queueRejectionDoesNotAttributeCapabilityDeltaToTheOpenPlayer() {
        ContainerInteractionTracker tracker = ContainerInteractionTracker.getInstance();
        var key = new ContainerInteractionTracker.ContainerKey("minecraft:overworld", POS.getX(), POS.getY(), POS.getZ());
        AtomicReference<ContainerInteractionTracker.InventoryTotals> live = new AtomicReference<>(
                new ContainerInteractionTracker.InventoryTotals(java.util.Map.of(), java.util.Map.of()));
        java.util.UUID playerUuid = java.util.UUID.randomUUID();
        tracker.openSession(playerUuid, "Steve", key, live::get, java.util.List.of());

        InternalObservationService.InternalObservation filler = new InternalObservationService.InternalObservation(
                1000, "EQUIP_ARMOR_STAND", null, null,
                "minecraft:overworld", 0, 64, 0,
                "minecraft:overworld", 0.0, 64.0, 0.0,
                "CONTAINER", DIAMOND, 1, null);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(pendingObservations().offer(filler));
        }

        wrapperAt(POS).submitObservation("INSERT", 5, DIAMOND);
        assertEquals(1, InternalObservationService.getInstance().getTotalDropped());
        assertEquals(1, tracker.getTotalCapabilityQueueRejections());
        pendingObservations().clear();
        live.set(new ContainerInteractionTracker.InventoryTotals(
                java.util.Map.of(DIAMOND.fingerprintHash(), 5L),
                java.util.Map.of(DIAMOND.fingerprintHash(), DIAMOND)));
        tracker.closeSession(playerUuid, 1, 64, 1);

        InternalObservationService.InternalObservation unresolved = pendingObservations().poll();
        assertNotNull(unresolved, "the lost capability transfer must be retried as unresolved once queue capacity returns");
        assertEquals("CAPABILITY_INSERT", unresolved.actionType());
        assertEquals(ContainerCapabilityWrapper.UNKNOWN_CALLER_UUID, unresolved.playerUuid());
        assertEquals(5, unresolved.amount());
        assertNotNull(unresolved.timestampEndMs());
        assertTrue(new String(unresolved.rawData(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("queue_overflow_recovery"));
        assertNull(pendingObservations().poll(), "queue rejection must not turn the lost capability delta into a player transfer");
        assertEquals(1, InternalObservationService.getInstance().getTotalDropped());
    }

    @Test
    void observationsAreKeyedToTheirOwnPosition() throws Exception {
        // Each wrapper anchors its observation to its own block position.
        wrapperAt(POS_B).submitObservation("INSERT", 2, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("CAPABILITY_INSERT", obs.actionType());
        assertEquals((double) POS_B.getX(), obs.targetX());
        assertEquals((double) POS_B.getZ(), obs.targetZ());
    }

    @Test
    void readMethodsDelegateUnchanged() {
        IItemHandler delegate = mock(IItemHandler.class);
        when(delegate.getSlots()).thenReturn(2);
        when(delegate.getStackInSlot(1)).thenReturn(null);
        when(delegate.getSlotLimit(0)).thenReturn(64);
        when(delegate.isItemValid(0, null)).thenReturn(true);
        ContainerCapabilityWrapper wrapper = new ContainerCapabilityWrapper(delegate, POS, Level.OVERWORLD);

        assertEquals(2, wrapper.getSlots());
        assertNull(wrapper.getStackInSlot(1));
        assertEquals(64, wrapper.getSlotLimit(0));
        assertTrue(wrapper.isItemValid(0, null));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ContainerCapabilityWrapper wrapperAt(BlockPos pos) {
        return new ContainerCapabilityWrapper(mock(IItemHandler.class), pos, Level.OVERWORLD);
    }

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
