package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.AdminMutationCapture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures only administrative and creative drops that the server actually added. */
@Mixin(ServerPlayer.class)
public abstract class AdminGiveDropMixin {
    @Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",
            at = @At("RETURN"))
    private void itemgraph$captureAcceptedDropWithName(ItemStack stack, boolean dropAround,
                                                        boolean includeName,
                                                        CallbackInfoReturnable<ItemEntity> callback) {
        itemgraph$captureAcceptedDrop(callback.getReturnValue());
    }

    private void itemgraph$captureAcceptedDrop(ItemEntity entity) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        boolean accepted = entity != null && entity.isAddedToLevel();
        AdminMutationCapture.captureCreativeDrop(player, entity, accepted);
    }
}
