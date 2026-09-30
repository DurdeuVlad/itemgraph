package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Matches GriefLogger's item-action boundary. The HEAD hook records an attempt
 * before any later spawn acceptance or cancellation can change the outcome.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method = "shootFromRotation", at = @At("HEAD"), require = 1)
    private void itemgraph$recordShootAttempt(Entity source, float xRot, float yRot,
                                               float yOffset, float power, float uncertainty,
                                               CallbackInfo callback) {
        FabricNativeAuditEventListener.onProjectileShootAttempt(
                (Projectile) (Object) this, source);
    }
}
