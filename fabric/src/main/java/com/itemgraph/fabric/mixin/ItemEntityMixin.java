package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.itemgraph.fabric.capture.ItemEntityPickupCapture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Fabric has no pickup event. ItemEntity.playerTouch mutates the entity stack
 * only when inventory insertion succeeds; comparing the before/after count
 * records full and partial pickups without an inventory scan.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    private static final int MAX_PICKUP_CAPTURE_DEPTH = 32;
    private static final ThreadLocal<Deque<ItemEntityPickupCapture>> ITEMGRAPH_PICKUP_STACKS = new ThreadLocal<>();

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void itemgraph$capturePickup(Player player, CallbackInfo callback) {
        if (player instanceof ServerPlayer && !player.level().isClientSide()) {
            ItemEntity entity = (ItemEntity) (Object) this;
            ItemStack stack = entity.getItem();
            Deque<ItemEntityPickupCapture> pending = ITEMGRAPH_PICKUP_STACKS.get();
            if (pending == null) {
                pending = new ArrayDeque<>();
                ITEMGRAPH_PICKUP_STACKS.set(pending);
            }
            if (pending.size() >= MAX_PICKUP_CAPTURE_DEPTH) {
                // A thrown mod callback can skip RETURN. Bound stale snapshots so
                // repeated failures cannot retain an unbounded server-thread stack.
                pending.clear();
            }
            pending.push(new ItemEntityPickupCapture(entity, (ServerPlayer) player,
                    stack == null ? ItemStack.EMPTY : stack.copy()));
        } else {
            ITEMGRAPH_PICKUP_STACKS.remove();
        }
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void itemgraph$recordPickup(Player player, CallbackInfo callback) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.level().isClientSide()) {
            ITEMGRAPH_PICKUP_STACKS.remove();
            return;
        }
        ItemEntity entity = (ItemEntity) (Object) this;
        Deque<ItemEntityPickupCapture> pending = ITEMGRAPH_PICKUP_STACKS.get();
        ItemEntityPickupCapture capture = removeMatching(pending, entity, serverPlayer);
        if (pending != null && pending.isEmpty()) {
            ITEMGRAPH_PICKUP_STACKS.remove();
        }
        if (capture == null || capture.original() == null || capture.original().isEmpty()) {
            return;
        }
        FabricNativeAuditEventListener.onItemPickedUp(serverPlayer, entity, capture.original(),
                entity.isRemoved() ? 0 : entity.getItem().getCount());
    }

    private static ItemEntityPickupCapture removeMatching(Deque<ItemEntityPickupCapture> pending,
                                                          ItemEntity entity, ServerPlayer player) {
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        ItemEntityPickupCapture match = null;
        for (ItemEntityPickupCapture candidate : pending) {
            if (candidate.entity() == entity && candidate.player() == player) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            // A callback without a matching HEAD means the thread-local state
            // no longer describes the invocation stack; discard it rather than
            // allowing a later entity to inherit a stale quantity snapshot.
            pending.clear();
            return null;
        }
        // If an inner invocation unwound without its RETURN callback, discard
        // captures above the matching outer invocation before consuming it.
        while (!pending.isEmpty()) {
            ItemEntityPickupCapture candidate = pending.pop();
            if (candidate == match) {
                return match;
            }
        }
        return null;
    }
}
