package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * NeoForge event listener for armor stand interactions (Phase 8B).
 *
 * <p>Captures equip and unequip actions on armor stands, closing a major forensic blind spot.
 */
public class ArmorStandEventListener {

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof ArmorStand armorStand)) {
            return;
        }

        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }

        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);

        // Armor stands support equipment in armor slots and hands
        // Check slots based on held item or click position
        String level = player.level().dimension().location().toString();
        double ax = armorStand.getX();
        double ay = armorStand.getY();
        double az = armorStand.getZ();
        long now = System.currentTimeMillis();

        if (!held.isEmpty()) {
            // Player is equipping or swapping an item onto the armor stand
            com.itemgraph.canon.CanonicalItem item = com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(held);
            InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                    now,
                    "EQUIP_ARMOR_STAND",
                    player.getUUID().toString(),
                    player.getGameProfile().getName(),
                    level,
                    player.getX(), player.getY(), player.getZ(),
                    level,
                    ax, ay, az,
                    "ARMOR_STAND",
                    item,
                    1,
                    null
            ));
        } else {
            // Player's hand is empty: taking/unequipping armor from the armor stand
            // Check if any armor slot has an item
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack equipped = armorStand.getItemBySlot(slot);
                if (!equipped.isEmpty()) {
                    com.itemgraph.canon.CanonicalItem item = com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(equipped);
                    InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                            now,
                            "UNEQUIP_ARMOR_STAND",
                            player.getUUID().toString(),
                            player.getGameProfile().getName(),
                            level,
                            player.getX(), player.getY(), player.getZ(),
                            level,
                            ax, ay, az,
                            "ARMOR_STAND",
                            item,
                            1,
                            null
                    ));
                    break;
                }
            }
        }
    }
}
