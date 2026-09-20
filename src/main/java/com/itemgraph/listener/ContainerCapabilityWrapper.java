package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-invasive {@link IItemHandler} wrapper that observes automated item insertions
 * and extractions on vanilla container block entities (0.2.0 — Issue 4).
 *
 * <h2>What this observes</h2>
 * <p>Only automation traffic reaches an {@code IItemHandler} capability: hoppers,
 * pipes, and other mods' transfer logic. Player GUI clicks mutate the menu's
 * {@code Container} directly ({@code AbstractContainerMenu.moveItemStackTo},
 * {@code Slot.set}) and never traverse this interface — player transfers are
 * observed by {@link ContainerInteractionTracker} session diffs instead.
 * Every call through this wrapper is therefore attributed to automation as
 * {@code HOPPER_INSERT}/{@code HOPPER_EXTRACT} unconditionally.
 *
 * <h2>Simulation guard</h2>
 * <p>IItemHandler callers first call with {@code simulate=true} to preview the
 * result. Only {@code simulate=false} with a real quantity moved emits.
 *
 * <h2>Automation credit</h2>
 * <p>While a player has the container open the wrapper additionally reports the
 * signed delta to {@link ContainerInteractionTracker}, so the session diff does
 * not attribute concurrent hopper traffic to the player or double-count it.
 */
public class ContainerCapabilityWrapper implements IItemHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCapabilityWrapper.class);

    /** Sentinel identity carried by automation-attributed observations. */
    public static final String AUTOMATION_UUID = "00000000-0000-0000-0000-000000000000";
    public static final String AUTOMATION_NAME = "[automation]";

    private final IItemHandler delegate;
    private final BlockPos pos;
    private final ResourceKey<Level> dimension;

    public ContainerCapabilityWrapper(IItemHandler delegate, BlockPos pos, ResourceKey<Level> dimension) {
        this.delegate = delegate;
        this.pos = pos;
        this.dimension = dimension;
    }

    // -------------------------------------------------------------------------
    // IItemHandler delegation — unmodified behaviour, observation-only side effects
    // -------------------------------------------------------------------------

    @Override
    public int getSlots() {
        return delegate.getSlots();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slot);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        ItemStack remainder = delegate.insertItem(slot, stack, simulate);
        if (shouldEmit(simulate, stack.getCount(), remainder.getCount())) {
            int movedCount = stack.getCount() - remainder.getCount();
            ItemStack movedStack = stack.copyWithCount(movedCount);
            emitObservation("INSERT", movedStack);
        }
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack extracted = delegate.extractItem(slot, amount, simulate);
        if (shouldEmit(simulate, extracted.getCount(), 0)) {
            emitObservation("EXTRACT", extracted);
        }
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return delegate.isItemValid(slot, stack);
    }

    // -------------------------------------------------------------------------
    // Observation writing
    // -------------------------------------------------------------------------

    /**
     * Whether a completed {@code insertItem}/{@code extractItem} call represents a
     * real transfer worth observing: not a simulation ({@code simulate=false}) and
     * the remainder/result count differs from the requested amount (items actually
     * moved). For extractions pass {@code remainder=0}: any non-empty result
     * counts as moved.
     */
    static boolean shouldEmit(boolean simulate, int requestedCount, int remainderCount) {
        return !simulate && requestedCount != remainderCount;
    }

    private void emitObservation(String direction, ItemStack stack) {
        try {
            CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack);
            // Report the signed automation delta so an open player session does not
            // take credit for machine traffic (or double-count it).
            long delta = "INSERT".equals(direction) ? stack.getCount() : -stack.getCount();
            ContainerInteractionTracker.getInstance().recordAutomationDelta(
                    new ContainerInteractionTracker.ContainerKey(
                            dimension.location().toString(), pos.getX(), pos.getY(), pos.getZ()),
                    canonical.fingerprintHash(), delta);
            submitObservation(direction, stack.getCount(), canonical);
        } catch (Exception e) {
            LOGGER.error("ContainerCapabilityWrapper: error submitting observation for {} at {}", direction, pos, e);
        }
    }

    /**
     * Builds and submits the observation for a completed automated transfer.
     * Capability invocations are always automation — the remote endpoint is not
     * knowable from an {@code IItemHandler} call, so persistence anchors the row to
     * this container and leaves the other endpoint UNKNOWN rather than fabricating
     * a self-referencing edge.
     *
     * <p>Package-private for unit tests: a bare {@code gradlew test} JVM cannot
     * bootstrap Minecraft items, so the {@link ItemStack}-based path cannot be
     * exercised directly. Tests drive this method with an explicit
     * {@link CanonicalItem} instead.
     */
    void submitObservation(String direction, int amount, CanonicalItem canonical) {
        String actionType = "INSERT".equals(direction) ? "HOPPER_INSERT" : "HOPPER_EXTRACT";
        String levelId = dimension.location().toString();

        InternalObservationService.getInstance().submit(
                new InternalObservationService.InternalObservation(
                        System.currentTimeMillis(),
                        actionType,
                        AUTOMATION_UUID,
                        AUTOMATION_NAME,
                        levelId,
                        pos.getX(), pos.getY(), pos.getZ(),
                        levelId,
                        (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(),
                        "CONTAINER",
                        canonical,
                        amount,
                        null  // no item entity UUID for container transfers
                )
        );
    }
}
