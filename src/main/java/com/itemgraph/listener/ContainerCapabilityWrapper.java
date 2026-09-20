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
 * Non-invasive {@link IItemHandler} wrapper that observes item insertions and extractions
 * from vanilla container block entities (Phase 0.2.0 — Issue 3 &amp; 4).
 *
 * <h2>How it works</h2>
 * <p>Every inventory access in NeoForge — whether triggered by a player clicking a GUI
 * or a hopper's automation tick — ultimately calls {@code insertItem} or {@code extractItem}
 * on the block entity's {@link IItemHandler} capability. By wrapping the capability, we
 * intercept all transfers without polling, ticking, or mixins.
 *
 * <h2>Simulation guard</h2>
 * <p>All IItemHandler callers (hoppers, pipes, player GUIs) first call with
 * {@code simulate=true} to preview the result. Only {@code simulate=false} represents
 * a real transfer. Observations are ONLY written when {@code simulate=false} AND a
 * real quantity actually moved ({@code remainder.getCount() != stack.getCount()}).
 *
 * <h2>Player vs automation attribution</h2>
 * <p>When a player has the container open, {@link ContainerInteractionTracker} holds their
 * UUID for the container's DimPos. If no player context is found, the transfer is
 * attributed to automation (hopper, pipe, etc.) with a synthetic "AUTOMATION" UUID sentinel.
 *
 * <h2>Action types written</h2>
 * <ul>
 *   <li>{@code ADD_ITEM} — player deposits into container</li>
 *   <li>{@code REMOVE_ITEM} — player withdraws from container</li>
 *   <li>{@code HOPPER_INSERT} — automation inserts into container</li>
 *   <li>{@code HOPPER_EXTRACT} — automation extracts from container</li>
 * </ul>
 */
public class ContainerCapabilityWrapper implements IItemHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCapabilityWrapper.class);

    /** Sentinel UUID used for automation-attributed observations (no player involved). */
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
            // Real items were inserted
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
     * Whether a completed {@code insertItem}/{@code extractItem} call represents a real
     * transfer worth observing: not a simulation ({@code simulate=false}) and the
     * remainder/result count differs from the requested amount (items actually moved).
     * For extractions pass {@code remainder=0}: any non-empty result counts as moved.
     */
    static boolean shouldEmit(boolean simulate, int requestedCount, int remainderCount) {
        return !simulate && requestedCount != remainderCount;
    }

    private void emitObservation(String direction, ItemStack stack) {
        try {
            submitObservation(direction, stack.getCount(), ItemCanonicalizer.canonicalizeStack(stack));
        } catch (Exception e) {
            LOGGER.error("ContainerCapabilityWrapper: error submitting observation for {} at {}", direction, pos, e);
        }
    }

    /**
     * Builds and submits the observation for a completed transfer, resolving
     * player-vs-automation attribution via {@link ContainerInteractionTracker}.
     *
     * <p>Package-private for unit tests: a bare {@code gradlew test} JVM cannot
     * bootstrap Minecraft items (NeoForge's FeatureFlagLoader requires a live mod
     * list), so the {@link ItemStack}-based path cannot be exercised directly.
     * Tests drive this method with an explicit {@link CanonicalItem} instead.
     */
    void submitObservation(String direction, int amount, CanonicalItem canonical) {
        ContainerInteractionTracker.PlayerContext ctx =
                ContainerInteractionTracker.getInstance().getPlayerContext(dimension, pos);

        boolean isPlayer = ctx != null;
        String actionType;
        String playerUuid;
        String playerName;

        if (isPlayer) {
            playerUuid = ctx.uuid().toString();
            playerName = ctx.name();
            actionType = "INSERT".equals(direction) ? "ADD_ITEM" : "REMOVE_ITEM";
        } else {
            playerUuid = AUTOMATION_UUID;
            playerName = AUTOMATION_NAME;
            actionType = "INSERT".equals(direction) ? "HOPPER_INSERT" : "HOPPER_EXTRACT";
        }

        String levelId = dimension.location().toString();

        InternalObservationService.getInstance().submit(
                new InternalObservationService.InternalObservation(
                        System.currentTimeMillis(),
                        actionType,
                        playerUuid,
                        playerName,
                        levelId,
                        pos.getX(), pos.getY(), pos.getZ(), // player coords = container coords for automation
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
