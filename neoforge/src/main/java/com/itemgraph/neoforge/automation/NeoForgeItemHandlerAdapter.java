package com.itemgraph.neoforge.automation;

import com.itemgraph.api.AutomationEndpoint;
import com.itemgraph.api.AutomationTransferAdapter;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemGraphService;
import com.itemgraph.api.ItemSnapshot;
import com.itemgraph.api.SourceHandle;
import com.itemgraph.api.SubmissionResult;
import com.itemgraph.api.SubmissionStatus;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in observer for a NeoForge item capability. Delegation is unchanged;
 * simulations are ignored and real mutations retain their exact slot and delta.
 */
public final class NeoForgeItemHandlerAdapter implements IItemHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeItemHandlerAdapter.class);
    private static final AtomicLong REJECTED_SUBMISSIONS = new AtomicLong();

    private final IItemHandler delegate;
    private final ItemGraphService service;
    private final SourceHandle source;
    private final String ownerModId;
    private final String displayName;
    private final ExternalInventoryEndpoint externalInventory;
    private final ResourceKey<Level> dimension;
    private final BlockPos position;
    private final Direction side;
    private final String externalSide;
    private final String automationModId;

    public NeoForgeItemHandlerAdapter(IItemHandler delegate, ItemGraphService service, SourceHandle source,
                                      String ownerModId, String displayName,
                                      ResourceKey<Level> dimension, BlockPos position,
                                      Direction side, String automationModId) {
        if (delegate == null || service == null || source == null || dimension == null || position == null
                || ownerModId == null || automationModId == null || !source.modId().equals(automationModId)) {
            throw new IllegalArgumentException("delegate, service, source, owner, location, and automation mod are required");
        }
        this.delegate = delegate;
        this.service = service;
        this.source = source;
        this.ownerModId = ownerModId;
        this.displayName = displayName;
        this.externalInventory = null;
        this.dimension = dimension;
        this.position = position.immutable();
        this.side = side;
        this.externalSide = null;
        this.automationModId = automationModId;
    }

    /**
     * Wraps a portable inventory capability such as a backpack item's
     * {@code Capabilities.ItemHandler.ITEM}. The provider must supply a stable,
     * opaque inventory ID; the adapter adds the exact slot policy to each delta.
     */
    public NeoForgeItemHandlerAdapter(IItemHandler delegate, ItemGraphService service, SourceHandle source,
                                      ExternalInventoryEndpoint externalInventory, String side,
                                      ResourceKey<Level> dimension, String automationModId) {
        if (delegate == null || service == null || source == null || externalInventory == null
                || externalInventory.lastKnownLocation() != null || dimension == null || automationModId == null
                || !source.modId().equals(automationModId)) {
            throw new IllegalArgumentException("delegate, service, source, external inventory, dimension, and automation mod are required");
        }
        // Reuse the public validation contract for provider-owned endpoint identifiers.
        AutomationEndpoint.externalInventory(externalInventory.ownerModId(), externalInventory.inventoryId(),
                externalInventory.displayName(), "aggregate", side);
        this.delegate = delegate;
        this.service = service;
        this.source = source;
        this.ownerModId = null;
        this.displayName = null;
        this.externalInventory = externalInventory;
        this.dimension = dimension;
        this.position = null;
        this.side = null;
        this.automationModId = automationModId;
        this.externalSide = side == null ? "unsided" : side;
    }

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
        int requested = stack == null ? 0 : stack.getCount();
        ItemStack evidenceStack = stack == null ? ItemStack.EMPTY : stack.copy();
        ItemStack remainder = delegate.insertItem(slot, stack, simulate);
        int remainderCount = remainder == null ? requested : remainder.getCount();
        int moved = requested - remainderCount;
        if (!simulate && requested > 0 && moved > 0 && moved <= requested) {
            evidenceStack.setCount(moved);
            record(slot, evidenceStack, requested, moved, true);
        }
        return remainder;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack extracted = delegate.extractItem(slot, amount, simulate);
        if (!simulate && amount > 0 && extracted != null && !extracted.isEmpty()) {
            record(slot, extracted.copy(), amount, extracted.getCount(), false);
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

    private void record(int slot, ItemStack stack, int requested, int moved, boolean inserted) {
        try {
            AutomationEndpoint endpoint = externalInventory == null
                    ? AutomationEndpoint.blockInventory(ownerModId, displayName,
                            dimension, position, "slot:" + slot, side)
                    : AutomationEndpoint.externalInventory(externalInventory.ownerModId(),
                            externalInventory.inventoryId(), externalInventory.displayName(),
                            "slot:" + slot, externalSide);
            var result = AutomationTransferAdapter.reportCommittedEndpointDelta(
                    service, source, sourceEventId(), System.currentTimeMillis(), UUID.randomUUID().toString(),
                    endpoint, dimension, ItemSnapshot.of(stack), requested, moved, inserted, true, false,
                    automationModId, slot);
            result.ifPresent(this::reportSubmissionResult);
        } catch (RuntimeException failure) {
            reportCaptureFailure(failure);
        }
    }

    private void reportSubmissionResult(CompletableFuture<SubmissionResult> future) {
        future.whenComplete((result, failure) -> {
            if (failure != null) {
                reportCaptureFailure(failure);
            } else if (result.status() != SubmissionStatus.PERSISTED
                    && result.status() != SubmissionStatus.DUPLICATE) {
                long failures = REJECTED_SUBMISSIONS.incrementAndGet();
                if (failures == 1 || failures % 100 == 0) {
                    LOGGER.warn("ItemGraph NeoForge automation evidence was not persisted ({} total): status={}, code={}",
                            failures, result.status(), result.errorCode());
                }
            }
        });
    }

    private static void reportCaptureFailure(Throwable failure) {
        long failures = REJECTED_SUBMISSIONS.incrementAndGet();
        if (failures == 1 || failures % 100 == 0) {
            LOGGER.warn("ItemGraph NeoForge automation capture failed ({} total): {}",
                    failures, failure.toString());
        }
    }

    private static long sourceEventId() {
        long eventId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return eventId == 0 ? 1 : eventId;
    }
}
