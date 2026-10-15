package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin justification (#56): Fabric exposes no entity-removal event that
 * reports a {@link Entity.RemovalReason}. {@code setRemoved} is the single
 * game boundary every removal flows through, and the reason parameter
 * distinguishes genuine removal ({@code KILLED}/{@code DISCARDED}) from
 * transient chunk unloads, which are not lifecycle endpoints.
 */
@Mixin(Entity.class)
public abstract class EntityRemovalMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), require = 1)
    private void itemgraph$recordRemoval(Entity.RemovalReason reason, CallbackInfo callback) {
        FabricNativeAuditEventListener.onEntityRemoved((Entity) (Object) this, reason);
    }
}
