package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures furnace, blast-furnace, and smoker outputs taken by a player. */
@Mixin(FurnaceResultSlot.class)
public abstract class FurnaceResultSlotMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void itemgraph$recordSmelt(Player player, ItemStack output, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            FabricNativeAuditEventListener.onSmelted(serverPlayer, output);
        }
    }
}
