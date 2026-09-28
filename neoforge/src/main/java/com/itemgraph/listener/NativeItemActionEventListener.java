package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent;

/** Native NeoForge coverage for GriefLogger's item consume, break, throw, and projectile actions. */
public final class NativeItemActionEventListener {

    @SubscribeEvent
    public void onItemUseFinished(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack original = event.getItem();
        ItemStack result = event.getResultStack();
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
        ItemStack original = event.getOriginal();
        if (original != null && !original.isEmpty()) {
            submitUnknown(player, "BREAK_ITEM", original.copy(), 1);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onProjectileSpawned(EntityJoinLevelEvent event) {
        if (event.isCanceled() || event.loadedFromDisk() || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof Projectile projectile)
                || !(projectile.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = projectile instanceof AbstractArrow arrow
                ? arrow.getPickupItemStackOrigin()
                : projectile instanceof ItemSupplier supplier ? supplier.getItem() : ItemStack.EMPTY;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String actionType = projectile instanceof ThrowableItemProjectile || projectile instanceof ThrownTrident
                ? "THROW_ITEM" : "SHOOT_ITEM";
        submitGround(player, actionType, stack.copy(), 1,
                projectile.getX(), projectile.getY(), projectile.getZ());
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

    private static void submitGround(ServerPlayer player, String actionType, ItemStack stack, int amount,
                                      double x, double y, double z) {
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack);
        String level = player.level().dimension().location().toString();
        BlockPos pos = BlockPos.containing(x, y, z);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), actionType,
                player.getUUID().toString(), player.getGameProfile().getName(), level,
                player.getX(), player.getY(), player.getZ(),
                level, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(),
                "GROUND", canonical, amount, null));
    }
}
