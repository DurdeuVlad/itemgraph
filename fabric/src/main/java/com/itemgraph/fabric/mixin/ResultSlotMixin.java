package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures crafting results at the server's result-slot take boundary. */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
    @Shadow
    private CraftingContainer craftSlots;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void itemgraph$recordCraft(Player player, ItemStack output, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            Container matrix = craftSlots;
            FabricNativeAuditEventListener.onCrafted(serverPlayer, matrix, output);
        }
    }
}
