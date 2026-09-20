package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContainerCapabilityWrapper} (0.2.0 — Issues 3 &amp; 4).
 *
 * <p>A bare {@code gradlew test} JVM cannot bootstrap Minecraft item registries:
 * {@code Bootstrap.bootStrap()} dies inside {@code Blocks.<clinit>} on NeoForge's
 * FeatureFlagLoader (no LoadingModList outside a real mod launch), which poisons
 * {@code Blocks}/{@code Items}/{@code Item$Properties} permanently for the JVM.
 * Verified empirically: {@link ItemStack} and {@link ItemStackHandler} class-load
 * fine, but no non-empty {@link ItemStack} can be constructed.
 *
 * <p>Consequently these tests verify the wrapper through its package-private
 * seams — {@link ContainerCapabilityWrapper#shouldEmit} (the simulate/movement
 * guard) and {@link ContainerCapabilityWrapper#submitObservation} (action-type
 * mapping and attribution) — plus the real public {@code insertItem}/
 * {@code extractItem} path with {@link ItemStack#EMPTY}, which exercises
 * delegation and proves no observation is written when nothing moved.
 * The pending-observation queue is read via reflection; the worker thread is
 * never started, so {@code submit()} is a pure enqueue.
 */
class ContainerCapabilityWrapperTest {

    private static final BlockPos POS = new BlockPos(10, 64, -20);
    private static final BlockPos POS_B = new BlockPos(11, 64, -20);

    private static final CanonicalItem DIAMOND =
            new CanonicalItem("minecraft:diamond", "fingerprint-diamond", null, null, null);

    @BeforeAll
    static void initMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        // Same bootstrap pattern as ItemCanonicalizerTest: partial failure is expected
        // outside a real game launch; what this test touches (ItemStack.EMPTY,
        // ItemStackHandler, ResourceKey) class-loads before the failure point.
        try {
            Bootstrap.bootStrap();
        } catch (Throwable expectedOutsideRealGameLaunch) {
            // Intentionally swallowed — see ItemCanonicalizerTest for the full rationale.
        }
    }

    @BeforeEach
    void drainQueuesAndContexts() throws Exception {
        pendingObservations().clear();
        clearPlayerContexts();
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
    // Action mapping + attribution via submitObservation (Issues 3 & 4)
    // -------------------------------------------------------------------------

    @Test
    void insertWithoutPlayerContextProducesHopperInsert() throws Exception {
        ContainerCapabilityWrapper wrapper = wrapperAt(POS);

        wrapper.submitObservation("INSERT", 5, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs, "a real insertion must emit exactly one observation");
        assertEquals("HOPPER_INSERT", obs.actionType());
        assertEquals(ContainerCapabilityWrapper.AUTOMATION_UUID, obs.playerUuid());
        assertEquals(ContainerCapabilityWrapper.AUTOMATION_NAME, obs.playerName());
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
    void extractWithoutPlayerContextProducesHopperExtract() throws Exception {
        ContainerCapabilityWrapper wrapper = wrapperAt(POS);

        wrapper.submitObservation("EXTRACT", 4, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("HOPPER_EXTRACT", obs.actionType());
        assertEquals(ContainerCapabilityWrapper.AUTOMATION_UUID, obs.playerUuid());
        assertEquals(4, obs.amount());
        assertTrue(pendingObservations().isEmpty());
    }

    @Test
    void insertWithPlayerContextProducesAddItem() throws Exception {
        ContainerCapabilityWrapper wrapper = wrapperAt(POS);
        UUID playerUuid = UUID.randomUUID();
        ContainerInteractionTracker.getInstance()
                .setPlayerContext(Level.OVERWORLD, POS, playerUuid, "Steve");

        wrapper.submitObservation("INSERT", 3, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("ADD_ITEM", obs.actionType());
        assertEquals(playerUuid.toString(), obs.playerUuid());
        assertEquals("Steve", obs.playerName());
        assertEquals(3, obs.amount());
        assertEquals("CONTAINER", obs.targetType());
    }

    @Test
    void extractWithPlayerContextProducesRemoveItem() throws Exception {
        ContainerCapabilityWrapper wrapper = wrapperAt(POS);
        UUID playerUuid = UUID.randomUUID();
        ContainerInteractionTracker.getInstance()
                .setPlayerContext(Level.OVERWORLD, POS, playerUuid, "Alex");

        wrapper.submitObservation("EXTRACT", 7, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("REMOVE_ITEM", obs.actionType());
        assertEquals(playerUuid.toString(), obs.playerUuid());
        assertEquals("Alex", obs.playerName());
        assertEquals(7, obs.amount());
    }

    @Test
    void playerContextIsKeyedByDimensionAndPosition() throws Exception {
        // A player context registered at POS must not leak into observations at POS_B.
        ContainerInteractionTracker.getInstance()
                .setPlayerContext(Level.OVERWORLD, POS, UUID.randomUUID(), "Steve");

        wrapperAt(POS_B).submitObservation("INSERT", 2, DIAMOND);

        InternalObservationService.InternalObservation obs = pendingObservations().poll();
        assertNotNull(obs);
        assertEquals("HOPPER_INSERT", obs.actionType(),
                "transfers at a different block position must stay automation-attributed");
        assertEquals(ContainerCapabilityWrapper.AUTOMATION_UUID, obs.playerUuid());
    }

    // -------------------------------------------------------------------------
    // Real public path: ItemStack.EMPTY drives every branch that cannot emit
    // -------------------------------------------------------------------------

    @Test
    void simulatedCallsProduceNoObservation() {
        ItemStackHandler handler = new ItemStackHandler(1);
        ContainerCapabilityWrapper wrapper = new ContainerCapabilityWrapper(handler, POS, Level.OVERWORLD);

        ItemStack insertRemainder = wrapper.insertItem(0, ItemStack.EMPTY, true);
        ItemStack extractPreview = wrapper.extractItem(0, 4, true);

        assertTrue(insertRemainder.isEmpty());
        assertTrue(extractPreview.isEmpty());
        assertTrue(handler.getStackInSlot(0).isEmpty(), "simulation must not mutate the inventory");
        assertEquals(0, pendingObservations().size(), "simulate=true must not emit observations");
    }

    @Test
    void realCallsThatMoveNothingProduceNoObservation() {
        ItemStackHandler handler = new ItemStackHandler(1);
        ContainerCapabilityWrapper wrapper = new ContainerCapabilityWrapper(handler, POS, Level.OVERWORLD);

        // Empty stack into the handler: remainder == input == 0 → nothing moved.
        ItemStack remainder = wrapper.insertItem(0, ItemStack.EMPTY, false);
        // Empty extraction: extracted.isEmpty() → nothing moved.
        ItemStack extracted = wrapper.extractItem(0, 4, false);

        assertTrue(remainder.isEmpty());
        assertTrue(extracted.isEmpty());
        assertEquals(0, pendingObservations().size(),
                "a real call that moves zero items must not emit an observation");
    }

    @Test
    void readMethodsDelegateUnchanged() {
        ItemStackHandler handler = new ItemStackHandler(2);
        ContainerCapabilityWrapper wrapper = new ContainerCapabilityWrapper(handler, POS, Level.OVERWORLD);

        assertEquals(2, wrapper.getSlots());
        assertSame(handler.getStackInSlot(1), wrapper.getStackInSlot(1));
        assertEquals(handler.getSlotLimit(0), wrapper.getSlotLimit(0));
        assertEquals(handler.isItemValid(0, ItemStack.EMPTY),
                wrapper.isItemValid(0, ItemStack.EMPTY));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ContainerCapabilityWrapper wrapperAt(BlockPos pos) {
        return new ContainerCapabilityWrapper(new ItemStackHandler(1), pos, Level.OVERWORLD);
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

    private static void clearPlayerContexts() {
        try {
            Field f = ContainerInteractionTracker.class.getDeclaredField("openContainers");
            f.setAccessible(true);
            ((Map<?, ?>) f.get(ContainerInteractionTracker.getInstance())).clear();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reach ContainerInteractionTracker.openContainers", e);
        }
    }
}
