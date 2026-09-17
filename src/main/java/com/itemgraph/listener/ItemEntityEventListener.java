package com.itemgraph.listener;

import com.itemgraph.tracker.ItemEntityTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * NeoForge event listener for tracking Minecraft ItemEntity UUIDs across drops and pickups (Phase 8A).
 */
public class ItemEntityEventListener {

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getEntity();
        if (player == null || itemEntity == null) {
            return;
        }

        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int amount = stack.getCount();
        String level = player.level().dimension().location().toString();
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        long now = System.currentTimeMillis();

        ItemEntityTracker.getInstance().recordDrop(
                itemEntity.getUUID(),
                player.getUUID(),
                level,
                x, y, z,
                itemId,
                amount,
                now
        );
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Post event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getItemEntity();
        if (player == null || itemEntity == null) {
            return;
        }

        ItemStack stack = event.getOriginalStack();
        if (stack.isEmpty()) {
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int amount = stack.getCount();
        String level = player.level().dimension().location().toString();
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        long now = System.currentTimeMillis();

        ItemEntityTracker.getInstance().recordPickup(
                itemEntity.getUUID(),
                player.getUUID(),
                level,
                x, y, z,
                itemId,
                amount,
                now
        );
    }
}
