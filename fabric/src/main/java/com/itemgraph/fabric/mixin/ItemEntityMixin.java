package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
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
    private static final ThreadLocal<Deque<ItemStack>> ITEMGRAPH_PICKUP_STACKS = new ThreadLocal<>();

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void itemgraph$capturePickup(Player player, CallbackInfo callback) {
        if (player instanceof ServerPlayer && !player.level().isClientSide()) {
            ItemStack stack = ((ItemEntity) (Object) this).getItem();
            Deque<ItemStack> pending = ITEMGRAPH_PICKUP_STACKS.get();
            if (pending == null) {
                pending = new ArrayDeque<>();
                ITEMGRAPH_PICKUP_STACKS.set(pending);
            }
            if (pending.size() >= MAX_PICKUP_CAPTURE_DEPTH) {
                // A thrown mod callback can skip RETURN. Bound stale snapshots so
                // repeated failures cannot retain an unbounded server-thread stack.
                pending.clear();
            }
            pending.push(stack == null ? ItemStack.EMPTY : stack.copy());
        } else {
            ITEMGRAPH_PICKUP_STACKS.remove();
        }
    }

    @Inject(method = "playerTouch", at = @At("RETURN"))
    private void itemgraph$recordPickup(Player player, CallbackInfo callback) {
        Deque<ItemStack> pending = ITEMGRAPH_PICKUP_STACKS.get();
        ItemStack original = pending == null || pending.isEmpty() ? ItemStack.EMPTY : pending.pop();
        if (pending != null && pending.isEmpty()) {
            ITEMGRAPH_PICKUP_STACKS.remove();
        }
        if (!(player instanceof ServerPlayer serverPlayer) || original == null || original.isEmpty()) {
            return;
        }
        ItemEntity entity = (ItemEntity) (Object) this;
        FabricNativeAuditEventListener.onItemPickedUp(serverPlayer, entity, original,
                entity.isRemoved() ? 0 : entity.getItem().getCount());
    }
}
