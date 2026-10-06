package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;

/** Native NeoForge coverage for GriefLogger's item consume, break, throw, and projectile actions. */
public final class NativeItemActionEventListener {

    @SubscribeEvent
    public void onItemUseFinished(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        recordItemUseFinished(player, event.getItem(), event.getResultStack());
    }

    public static void recordItemUseFinished(ServerPlayer player, ItemStack original, ItemStack result) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        if (original == null || original.isEmpty() || result == null) {
            return;
        }
        UseAnim animation = original.getUseAnimation();
        if (animation != UseAnim.EAT && animation != UseAnim.DRINK) {
            return;
        }
        boolean consumed = result.isEmpty()
                || result.getCount() < original.getCount()
                || !ItemStack.isSameItemSameComponents(original, result);
        if (consumed) {
            submitUnknown(player, "CONSUME_ITEM", original.copy(), 1);
        }
    }

    @SubscribeEvent
    public void onItemDestroyed(PlayerDestroyItemEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        recordItemDestroyed(player, event.getOriginal());
    }

    public static void recordItemDestroyed(ServerPlayer player, ItemStack original) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        if (original != null && !original.isEmpty()) {
            submitUnknown(player, "BREAK_ITEM", original.copy(), 1);
        }
    }

    /** Records the same attempt boundary as GriefLogger's ProjectileMixin. */
    public static void onProjectileShootAttempt(Projectile projectile, Entity source) {
        if (!(source instanceof ServerPlayer player) || projectile == null
                || player.level().isClientSide()) {
            return;
        }
        ItemStack stack = projectile instanceof ThrowableItemProjectile throwable
                ? throwable.getItem()
                : projectile instanceof AbstractArrow arrow
                ? arrow.getPickupItemStackOrigin()
                : ItemStack.EMPTY;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        // GriefLogger classifies every arrow-family projectile, including a
        // thrown trident, as SHOOT_ITEM; only ThrowableItemProjectile is THROW_ITEM.
        String actionType = projectile instanceof ThrowableItemProjectile
                ? "THROW_ITEM" : "SHOOT_ITEM";
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack.copy());
        String level = player.level().dimension().location().toString();
        recordProjectileObservation(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                level, player.getX(), player.getY(), player.getZ(),
                projectile.getX(), projectile.getY(), projectile.getZ(), canonical, stack.getCount(),
                BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
    }

    private static void submitUnknown(ServerPlayer player, String actionType, ItemStack stack, int amount) {
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack);
        String level = player.level().dimension().location().toString();
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), actionType,
                player.getUUID().toString(), player.getGameProfile().getName(), level,
                player.getX(), player.getY(), player.getZ(),
                level, null, null, null, "UNKNOWN", canonical, amount, null));
    }

    /** Records accepted evidence after ServerLevel.addFreshEntity returns true. */
    public static void onProjectileAdded(Entity entity, boolean added) {
        if (!added || !(entity instanceof Projectile projectile)
                || projectile.level().isClientSide()
                || !(projectile.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = projectile instanceof AbstractArrow arrow
                ? arrow.getPickupItemStackOrigin()
                : projectile instanceof ItemSupplier supplier ? supplier.getItem() : ItemStack.EMPTY;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        submitProjectileSpawnAccepted(player, stack, projectile);
    }

    private static void submitProjectileSpawnAccepted(ServerPlayer player, ItemStack stack,
                                                       Projectile projectile) {
        String level = player.level().dimension().location().toString();
        String projectileId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString();
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack.copy());
        String actionType = projectile instanceof ThrowableItemProjectile
                ? "THROW_ITEM" : "SHOOT_ITEM";
        recordProjectileSpawnAccepted(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                level, projectile.getX(), projectile.getY(), projectile.getZ(), canonical,
                stack.getCount(), projectileId);
    }

    static void recordProjectileObservation(String actionType, String playerUuid, String playerName,
                                             String levelName, double playerX, double playerY, double playerZ,
                                             double projectileX, double projectileY, double projectileZ,
                                             CanonicalItem item, int amount, String projectileId) {
        if (item == null || amount <= 0) {
            return;
        }
        String eventId = java.util.UUID.randomUUID().toString();
        byte[] rawData = ("{\"capture\":\"projectile_shoot_attempt\",\"event_id\":\""
                + eventId + "\",\"projectile\":\"" + projectileId + "\",\"spawn_x\":" + projectileX
                + ",\"spawn_y\":" + projectileY + ",\"spawn_z\":" + projectileZ
                + ",\"outcome\":\"attempt\",\"evidence\":\"shoot_from_rotation\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InternalObservationService service = InternalObservationService.getInstance();
        InternalObservationService.InternalObservation observation = new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), actionType,
                playerUuid, playerName, levelName,
                playerX, playerY, playerZ, levelName,
                null, null, null, "UNKNOWN",
                item.itemId(), rawData, item, amount, null, null,
                InternalObservationService.sourceEventIdForUuid(eventId));
        if (service.submit(observation)) {
            // Keep the legacy native-audit lookup path readable while the
            // quantity observation remains the single unified source.
            service.submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                    System.currentTimeMillis(), actionType, playerUuid, playerName, levelName,
                    projectileX, projectileY, projectileZ, item.itemId(),
                    "projectile=" + projectileId + " event_id=" + eventId
                            + " outcome=attempt evidence=shoot_from_rotation quantity=" + amount,
                    rawData, InternalObservationService.sourceEventIdForUuid(eventId)));
        }
    }

    static void recordProjectileSpawnAccepted(String actionType, String playerUuid, String playerName,
                                               String levelName, double projectileX, double projectileY,
                                               double projectileZ, CanonicalItem item, int amount,
                                               String projectileId) {
        if (item == null || amount <= 0) {
            return;
        }
        String eventId = java.util.UUID.randomUUID().toString();
        byte[] rawData = ("{\"capture\":\"projectile_spawn\",\"event_id\":\""
                + eventId + "\",\"projectile\":\"" + projectileId + "\",\"spawn_x\":" + projectileX
                + ",\"spawn_y\":" + projectileY + ",\"spawn_z\":" + projectileZ
                + ",\"outcome\":\"accepted\",\"evidence\":\"spawned_by_player\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), "PROJECTILE_SPAWN_ACCEPTED", playerUuid, playerName,
                        levelName, projectileX, projectileY, projectileZ, item.itemId(),
                        "action=" + actionType + " projectile=" + projectileId + " event_id=" + eventId
                                + " outcome=accepted evidence=spawned_by_player",
                        rawData, InternalObservationService.sourceEventIdForUuid(eventId)));
    }
}
