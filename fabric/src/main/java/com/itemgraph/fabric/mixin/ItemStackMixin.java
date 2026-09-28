package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Captures the actual durability-break branch in ItemStack.hurtAndBreak. The
 * shrink invocation is reached only after the item has crossed max damage, so
 * this does not reinterpret ordinary durability damage as a break.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "hurtAndBreak",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void itemgraph$recordBreak(int amount, ServerLevel level, ServerPlayer player,
                                       Consumer<?> onBreak, CallbackInfo callback) {
        if (player == null || level == null || level.isClientSide()) {
            return;
        }
        FabricNativeAuditEventListener.onItemDestroyed(player, ((ItemStack) (Object) this).copy());
    }
}
