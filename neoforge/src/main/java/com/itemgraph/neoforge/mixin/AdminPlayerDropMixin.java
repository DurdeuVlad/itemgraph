package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.AdminMutationCapture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures accepted two-argument drops used by creative inventory mutation. */
@Mixin(Player.class)
abstract class AdminPlayerDropMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("RETURN"))
    private void itemgraph$captureCreativeDrop(ItemStack stack, boolean dropAround,
                                               CallbackInfoReturnable<ItemEntity> callback) {
        if (!((Object) this instanceof ServerPlayer player)) return;
        ItemEntity entity = callback.getReturnValue();
        boolean accepted = entity != null && entity.isAddedToLevel();
        AdminMutationCapture.captureCreativeDrop(player, entity, accepted);
    }
}
