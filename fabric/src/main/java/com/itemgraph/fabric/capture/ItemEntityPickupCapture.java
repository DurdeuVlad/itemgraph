package com.itemgraph.fabric.capture;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Thread-local snapshot for one Fabric ItemEntity.playerTouch invocation. */
public record ItemEntityPickupCapture(ItemEntity entity, ServerPlayer player, ItemStack original) {
}
