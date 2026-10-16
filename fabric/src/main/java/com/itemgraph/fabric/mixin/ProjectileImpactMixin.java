package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin justification (#56): Fabric exposes no projectile-impact event.
 * {@code hitTargetOrDeflectSelf} is the server's single dispatch point that
 * receives the raytrace result before {@code onHit} applies effects, so a
 * HEAD capture observes every authoritative impact the game resolves without
 * re-implementing collision detection.
 */
@Mixin(Projectile.class)
public abstract class ProjectileImpactMixin {
    @Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), require = 1)
    private void itemgraph$recordImpact(HitResult hitResult,
                                        CallbackInfoReturnable<Boolean> callback) {
        FabricNativeAuditEventListener.onProjectileImpact((Projectile) (Object) this, hitResult);
    }
}
