package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin justification (#56): Fabric's death callbacks report the victim and
 * damage source but never enumerate the dropped item entities.
 * {@code dropAllDeathLoot} is the lexical server-side scope in which the game
 * spawns those entities, so bounding it attributes each spawned ItemEntity to
 * the dead entity without a world scan or a permanent item UUID.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathDropMixin {
    @Inject(method = "dropAllDeathLoot", at = @At("HEAD"), require = 1)
    private void itemgraph$beginDeathDropScope(ServerLevel level, DamageSource source,
                                               CallbackInfo callback) {
        FabricNativeAuditEventListener.beginNonPlayerDeathDropScope(
                (LivingEntity) (Object) this);
    }

    @Inject(method = "dropAllDeathLoot", at = @At("RETURN"), require = 1)
    private void itemgraph$endDeathDropScope(ServerLevel level, DamageSource source,
                                             CallbackInfo callback) {
        FabricNativeAuditEventListener.endNonPlayerDeathDropScope(
                (LivingEntity) (Object) this);
    }
}
