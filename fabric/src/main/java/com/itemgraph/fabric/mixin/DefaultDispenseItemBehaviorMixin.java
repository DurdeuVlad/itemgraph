package com.itemgraph.fabric.mixin;

import com.itemgraph.automation.VanillaDispenserCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observes only item entities accepted by the default dispenser item behavior. */
@Mixin(DefaultDispenseItemBehavior.class)
public abstract class DefaultDispenseItemBehaviorMixin {
    @WrapOperation(method = "spawnItem",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean itemgraph$captureAcceptedSpawn(Level level, Entity entity,
                                                           Operation<Boolean> original) {
        boolean accepted = original.call(level, entity);
        if (accepted && level instanceof ServerLevel serverLevel && entity instanceof ItemEntity itemEntity) {
            VanillaDispenserCapture.onAcceptedItemEntity(serverLevel, itemEntity);
        }
        return accepted;
    }
}
