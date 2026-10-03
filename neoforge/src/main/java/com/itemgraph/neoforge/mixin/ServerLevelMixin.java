package com.itemgraph.neoforge.mixin;

import com.itemgraph.listener.NativeItemActionEventListener;
import com.itemgraph.audit.AdminMutationCapture;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures accepted projectile spawns only after the server's boolean
 * addFreshEntity result is known. This avoids treating a cancellable join event
 * as proof that an entity entered the level.
 */
@Mixin(ServerLevel.class)
public final class ServerLevelMixin {
    @Inject(method = "addFreshEntity", at = @At("RETURN"), require = 1)
    private void itemgraph$recordFreshEntity(Entity entity,
                                              CallbackInfoReturnable<Boolean> callback) {
        if (entity instanceof ItemEntity itemEntity) {
            AdminMutationCapture.recordGiveDropEntityAdmissionSafely(itemEntity, callback.getReturnValueZ());
        }
        NativeItemActionEventListener.onProjectileAdded(entity, callback.getReturnValueZ());
    }
}
