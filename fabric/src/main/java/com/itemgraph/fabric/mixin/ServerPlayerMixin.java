package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.itemgraph.fabric.FabricContainerSessionListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric has no public post-drop event. ItemGraph pairs the ServerPlayer.drop
 * return with ServerLevel.addFreshEntity's boolean result so a returned entity
 * is recorded only when the server accepted that entity into the world.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    @Inject(method = "drop", at = @At("HEAD"))
    private void itemgraph$captureDrop(ItemStack stack, boolean dropAround, boolean includeName,
                                        CallbackInfoReturnable<ItemEntity> callback) {
        FabricNativeAuditEventListener.beginItemDropCapture((ServerPlayer) (Object) this);
    }

    @Inject(method = "drop", at = @At("RETURN"))
    private void itemgraph$recordDrop(ItemStack stack, boolean dropAround, boolean includeName,
                                      CallbackInfoReturnable<ItemEntity> callback) {
        ItemEntity itemEntity = callback.getReturnValue();
        ItemStack original = itemEntity == null || itemEntity.getItem() == null
                ? ItemStack.EMPTY : itemEntity.getItem().copy();
        FabricNativeAuditEventListener.finishItemDropCapture((ServerPlayer) (Object) this,
                itemEntity, original);
    }

    @Inject(method = "initMenu", at = @At("RETURN"))
    private void itemgraph$openContainerMenu(AbstractContainerMenu menu, CallbackInfo callback) {
        FabricContainerSessionListener.onMenuOpened((ServerPlayer) (Object) this, menu);
    }

    @Inject(method = "doCloseContainer", at = @At("HEAD"))
    private void itemgraph$closeContainerMenu(CallbackInfo callback) {
        FabricContainerSessionListener.onMenuClosing((ServerPlayer) (Object) this);
    }
}
