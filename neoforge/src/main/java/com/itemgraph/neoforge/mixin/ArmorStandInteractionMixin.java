package com.itemgraph.neoforge.mixin;

import com.itemgraph.ingest.EntityInteractionEvidence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The event bus callback is pre-use; this RETURN hook observes the authoritative result. */
@Mixin(ArmorStand.class)
public abstract class ArmorStandInteractionMixin {
    @Inject(method = "interactAt", at = @At("RETURN"), require = 1)
    private void itemgraph$recordInteractionResult(Player player, Vec3 hitPosition, InteractionHand hand,
                                                   CallbackInfoReturnable<InteractionResult> callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    serverPlayer, (ArmorStand) (Object) this, hand, callback.getReturnValue());
        }
    }
}
