package com.itemgraph.fabric.mixin;

import com.itemgraph.audit.WorldEventCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Confirms explosion block changes by comparing state around the final effect method. */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow @Final private Level level;

    @WrapMethod(method = "finalizeExplosion")
    private void itemgraph$captureExplosionResults(boolean spawnParticles, Operation<Void> original) throws Throwable {
        WorldEventCapture.ExplosionSnapshot beforeStates = level instanceof ServerLevel serverLevel
                ? WorldEventCapture.safeSnapshotExplosion(serverLevel, ((Explosion) (Object) this).getToBlow())
                : null;
        boolean returned = false;
        try {
            original.call(spawnParticles);
            returned = true;
        } finally {
            if (level instanceof ServerLevel serverLevel && beforeStates != null) {
                WorldEventCapture.recordExplosionResults(serverLevel, (Explosion) (Object) this,
                        beforeStates, returned ? WorldEventCapture.ExplosionExit.RETURNED
                                : WorldEventCapture.ExplosionExit.THREW);
            }
        }
    }
}
