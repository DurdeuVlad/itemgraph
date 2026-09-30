package com.itemgraph.fabric.mixin;

import com.itemgraph.ingest.EntityInteractionEvidence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the inherited Entity.interact fallback result for armor stands. */
@Mixin(Entity.class)
public abstract class EntityInteractionMixin {
    @Inject(method = "interact(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"), require = 1)
    private void itemgraph$recordGenericInteractionResult(Player player, InteractionHand hand,
                                                          CallbackInfoReturnable<InteractionResult> callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    serverPlayer, (Entity) (Object) this, hand, callback.getReturnValue(), "interact", true);
        }
    }
}
