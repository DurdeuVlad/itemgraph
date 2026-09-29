package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.itemgraph.fabric.capture.LivingEntityUseCapture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the completed item-use boundary shared by food and drinks. A
 * bounded nested stack protects the server thread if a modded implementation
 * throws before the return hook runs. The capture state lives outside the mixin
 * package because Mixin must not transform helper records into LivingEntity.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void itemgraph$captureUse(CallbackInfo callback) {
        LivingEntityUseCapture.begin(this);
    }

    @Inject(method = "completeUsingItem", at = @At("RETURN"))
    private void itemgraph$recordUse(CallbackInfo callback) {
        LivingEntityUseCapture.Capture capture = LivingEntityUseCapture.finish(this);
        if (capture == null) {
            return;
        }
        if (capture.player() == null || capture.original() == null || capture.original().isEmpty()) {
            return;
        }
        ItemStack current = capture.player().getItemInHand(capture.hand());
        FabricNativeAuditEventListener.onItemUseFinished(capture.player(), capture.original(), current);
    }
}
