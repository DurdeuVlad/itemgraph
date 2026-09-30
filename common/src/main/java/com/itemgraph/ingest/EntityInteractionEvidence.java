package com.itemgraph.ingest;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Records the authoritative return value of the armor stand interaction method. */
public final class EntityInteractionEvidence {
    private EntityInteractionEvidence() { }

    public static void recordArmorStandHandledResult(ServerPlayer player, Entity target,
                                                      InteractionHand hand, InteractionResult result) {
        if (player == null || !(target instanceof ArmorStand) || hand == null || result == null
                || (!result.consumesAction() && result != InteractionResult.FAIL) || player.level().isClientSide()) {
            return;
        }
        Level level = target.level();
        if (level.isClientSide() || player.level() != level) {
            return;
        }

        UUID targetUuid = target.getUUID();
        boolean denied = result == InteractionResult.FAIL;
        String detail = "outcome=" + (denied ? "denied" : "handled")
                + " result=" + result.name().toLowerCase(java.util.Locale.ROOT)
                + " hand=" + hand.name().toLowerCase(java.util.Locale.ROOT)
                + (targetUuid == null ? "" : " target_uuid=" + targetUuid);
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), denied ? "INTERACT_ENTITY_DENIED" : "INTERACT_ENTITY_COMPLETED",
                        player.getUUID().toString(), player.getGameProfile().getName(),
                        level.dimension().location().toString(),
                        target.blockPosition().getX(), target.blockPosition().getY(), target.blockPosition().getZ(),
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                        detail,
                        null));
    }
}
