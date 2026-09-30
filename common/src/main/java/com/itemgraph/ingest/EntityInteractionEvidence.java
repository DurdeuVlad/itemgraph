package com.itemgraph.ingest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;



/** Records the authoritative return value of the armor stand interaction method. */
public final class EntityInteractionEvidence {
    private EntityInteractionEvidence() { }

    /**
     * Builds immutable attempt details while still on the server thread. Only the
     * held stack's registry ID, count, and deterministic fingerprint leave the
     * callback; custom component values are not copied into the audit text.
     */
    public static String attemptDetails(Entity target, InteractionHand hand, ItemStack heldStack,
                                        String completion) {
        return interactionDetails("attempt", target, hand, heldStack, completion);
    }

    public static String canceledAttemptDetails(Entity target, InteractionHand hand, ItemStack heldStack,
                                                String completion) {
        return interactionDetails("canceled", target, hand, heldStack, completion);
    }

    private static String interactionDetails(String outcome, Entity target, InteractionHand hand,
                                             ItemStack heldStack, String completion) {
        StringBuilder detail = new StringBuilder("outcome=").append(outcome);
        appendTargetAndHand(detail, target, hand);
        if (completion != null && !completion.isBlank()) {
            detail.append(" completion=").append(completion);
        }
        appendHeldStack(detail, heldStack);
        return detail.toString();
    }

    /** Records a callback rejection, which is distinct from a game-method result. */
    public static void recordArmorStandCallbackCanceled(ServerPlayer player, ArmorStand target,
                                                         InteractionHand hand, ItemStack heldStack,
                                                         String callback) {
        StringBuilder detail = new StringBuilder("outcome=canceled callback=").append(callback)
                .append(" reason=LOADER_CALLBACK_CANCELED");
        appendHeldStack(detail, heldStack);
        record(player, target, hand, "INTERACT_ENTITY_DENIED", detail.toString());
    }

    /** Captures mutable interaction context before Fabric's callback listeners execute. */
    public static FabricCallbackContext captureFabricCallbackContext(ServerPlayer player, Entity target,
                                                                      InteractionHand hand, ItemStack heldStack) {
        if (player == null || target == null || hand == null || player.level().isClientSide()) {
            return null;
        }
        Level level = target.level();
        if (level.isClientSide() || player.level() != level) {
            return null;
        }

        StringBuilder fields = new StringBuilder();
        appendTargetAndHand(fields, target, hand);
        appendHeldStack(fields, heldStack == null ? null : heldStack.copy());
        return new FabricCallbackContext(System.currentTimeMillis(), player.getUUID().toString(),
                player.getGameProfile().getName(), level.dimension().location().toString(),
                target.blockPosition().getX(), target.blockPosition().getY(), target.blockPosition().getZ(),
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                fields.toString());
    }

    /** Records the aggregate Fabric callback result using the pre-callback context snapshot. */
    public static void recordFabricCallbackResult(FabricCallbackContext context,
                                                  InteractionResult callbackResult) {
        if (context == null || callbackResult == null
                || callbackResult == InteractionResult.PASS) {
            return;
        }

        boolean denied = callbackResult == InteractionResult.FAIL;
        String eventType = denied ? "INTERACT_ENTITY_DENIED" : "INTERACT_ENTITY_UNRESOLVED";
        StringBuilder detail = new StringBuilder("outcome=")
                .append(denied ? "denied" : "unresolved")
                .append(" callback=fabric_use_entity callback_result=")
                .append(callbackResult.name().toLowerCase(java.util.Locale.ROOT))
                .append(" reason=FABRIC_USE_ENTITY_CALLBACK_SHORT_CIRCUITED")
                .append(context.detailFields());
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(context.timestampMs(), eventType,
                        context.playerUuid(), context.playerName(), context.dimension(),
                        context.x(), context.y(), context.z(), context.subjectId(), detail.toString(), null));
    }

    public record FabricCallbackContext(long timestampMs, String playerUuid, String playerName,
                                        String dimension, int x, int y, int z, String subjectId,
                                        String detailFields) { }

    public static void recordArmorStandHandledResult(ServerPlayer player, Entity target,
                                                      InteractionHand hand, InteractionResult result,
                                                      String method, boolean recordPass) {
        if (player == null || !(target instanceof ArmorStand) || hand == null || result == null
                || (!result.consumesAction() && result != InteractionResult.FAIL
                    && !(recordPass && result == InteractionResult.PASS))
                || player.level().isClientSide()) {
            return;
        }
        Level level = target.level();
        if (level.isClientSide() || player.level() != level) {
            return;
        }

        boolean denied = result == InteractionResult.FAIL;
        String eventType = denied ? "INTERACT_ENTITY_DENIED"
                : result == InteractionResult.PASS ? "INTERACT_ENTITY_UNRESOLVED" : "INTERACT_ENTITY_COMPLETED";
        String outcome = denied ? "denied" : result == InteractionResult.PASS ? "unresolved" : "handled";
        String detail = "outcome=" + outcome
                + " result=" + result.name().toLowerCase(java.util.Locale.ROOT)
                + " method=" + method
                + (result == InteractionResult.PASS ? " reason=ENTITY_INTERACTION_METHOD_PASSED" : "");
        record(player, target, hand, eventType, detail);
    }

    private static void record(ServerPlayer player, Entity target, InteractionHand hand,
                               String eventType, String outcomeDetail) {
        if (player == null || target == null || hand == null || player.level().isClientSide()) {
            return;
        }
        Level level = target.level();
        if (level.isClientSide() || player.level() != level) {
            return;
        }
        StringBuilder detail = new StringBuilder(outcomeDetail);
        appendTargetAndHand(detail, target, hand);
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), eventType,
                        player.getUUID().toString(), player.getGameProfile().getName(),
                        level.dimension().location().toString(),
                        target.blockPosition().getX(), target.blockPosition().getY(), target.blockPosition().getZ(),
                        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                        detail.toString(), null));
    }

    private static void appendHeldStack(StringBuilder detail, ItemStack heldStack) {
        if (heldStack == null) {
            return;
        }
        CanonicalItem held = ItemCanonicalizer.canonicalizeStack(heldStack);
        detail.append(" held_item=").append(held.itemId())
                .append(" held_count=").append(heldStack.getCount())
                .append(" held_fingerprint=").append(held.fingerprintHash());
    }

    private static void appendTargetAndHand(StringBuilder detail, Entity target, InteractionHand hand) {
        if (hand != null) {
            detail.append(" hand=").append(hand.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (target != null && target.getUUID() != null) {
            detail.append(" target_uuid=").append(target.getUUID());
        }
    }
}
