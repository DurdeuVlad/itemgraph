package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures the server's fresh-entity boundary for player-owned projectiles.
 * Loaded entities use a separate addWithUUID path, so this hook does not
 * reinterpret chunk loading as a new projectile spawn.
 */
@Mixin(ServerLevel.class)
public final class ServerLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void itemgraph$recordProjectileSpawn(Entity entity,
                                                  CallbackInfoReturnable<Boolean> callback) {
        FabricNativeAuditEventListener.onProjectileAdded(entity, callback.getReturnValueZ());
    }
}
