package com.itemgraph.fabric;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.BlockInspectionTargets;
import com.itemgraph.listener.ContainerInteractionTracker;
import com.itemgraph.listener.ContainerInteractionTracker.ContainerKey;
import com.itemgraph.listener.ContainerInteractionTracker.InventoryTotals;
import com.itemgraph.listener.EnderChestInteractionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric's server menu lifecycle equivalent of NeoForge's
 * {@code PlayerContainerEvent}. The menu mixin calls this only when a server
 * menu is initialized or closed; snapshots therefore occur at session
 * boundaries instead of on every tick.
 */
public final class FabricContainerSessionListener {
    private record PendingClick(String levelId, BlockPos pos, long gameTime) {
    }

    private static final Map<UUID, PendingClick> PENDING_CLICKS = new ConcurrentHashMap<>();

    private FabricContainerSessionListener() {
    }

    public static void rememberClick(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player == null || level == null || pos == null || level.isClientSide()
                || !(level.getBlockEntity(pos) instanceof Container)) {
            return;
        }
        PENDING_CLICKS.put(player.getUUID(), new PendingClick(
                level.dimension().location().toString(), pos.immutable(), level.getGameTime()));
    }

    public static void onMenuOpened(ServerPlayer player, AbstractContainerMenu menu) {
        if (player == null || menu == null || player.level().isClientSide()) {
            return;
        }
        PendingClick click = PENDING_CLICKS.remove(player.getUUID());
        Container blockContainer = null;
        BlockEntity containerEntity = null;
        CompoundContainer merged = null;
        PlayerEnderChestContainer enderContainer = null;
        for (Slot slot : menu.slots) {
            Container container = slot.container;
            if (container instanceof PlayerEnderChestContainer ender) {
                enderContainer = ender;
                break;
            }
            if (container instanceof BlockEntity blockEntity) {
                containerEntity = blockEntity;
                blockContainer = container;
                break;
            }
            if (container instanceof CompoundContainer compound && merged == null) {
                merged = compound;
            }
        }

        String levelId = player.level().dimension().location().toString();
        if (enderContainer != null) {
            Container observed = enderContainer;
            EnderChestInteractionTracker.getInstance().openSession(
                    player.getUUID(), player.getGameProfile().getName(), levelId,
                    player.getX(), player.getY(), player.getZ(),
                    () -> snapshotTotals(observed));
            return;
        }
        if (containerEntity != null) {
            BlockPos pos = containerEntity.getBlockPos();
            Container observed = blockContainer;
            BlockPos canonical = BlockInspectionTargets.canonicalPosition(player.level(), pos);
            ContainerInteractionTracker.getInstance().openSession(
                    player.getUUID(), player.getGameProfile().getName(),
                    key(levelId, canonical),
                    () -> snapshotTotals(observed), aliases(levelId, pos, canonical, player.level()));
            return;
        }
        if (merged == null || click == null || !click.levelId().equals(levelId)
                || player.level().getGameTime() - click.gameTime() > 1
                || !(player.level().getBlockEntity(click.pos()) instanceof Container)) {
            return;
        }

        BlockPos canonical = BlockInspectionTargets.canonicalPosition(player.level(), click.pos());
        CompoundContainer observed = merged;
        ContainerInteractionTracker.getInstance().openSession(
                player.getUUID(), player.getGameProfile().getName(),
                key(levelId, canonical),
                () -> snapshotTotals(observed), aliases(levelId, click.pos(), canonical, player.level()));
    }

    private static ContainerKey key(String levelId, BlockPos pos) {
        return new ContainerKey(levelId, pos.getX(), pos.getY(), pos.getZ());
    }

    private static List<ContainerKey> aliases(String levelId, BlockPos clicked, BlockPos canonical,
                                              ServerLevel level) {
        return BlockInspectionTargets.resolveBlockPositions(level, clicked).stream()
                .filter(pos -> !pos.equals(canonical))
                .map(pos -> key(levelId, pos))
                .toList();
    }

    public static void onMenuClosing(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        PENDING_CLICKS.remove(player.getUUID());
        ContainerInteractionTracker.getInstance().closeSession(
                player.getUUID(), player.getX(), player.getY(), player.getZ());
        EnderChestInteractionTracker.getInstance().closeSession(
                player.getUUID(), player.getX(), player.getY(), player.getZ());
    }

    public static void clearAll() {
        PENDING_CLICKS.clear();
        ContainerInteractionTracker.getInstance().clearAll();
        EnderChestInteractionTracker.getInstance().clearAll();
    }

    public static void flushAll() {
        PENDING_CLICKS.clear();
        ContainerInteractionTracker.getInstance().closeAllSessions();
        EnderChestInteractionTracker.getInstance().closeAllSessions();
    }

    static InventoryTotals snapshotTotals(Container container) {
        Map<String, Long> counts = new HashMap<>();
        Map<String, CanonicalItem> exemplars = new HashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack);
            counts.merge(canonical.fingerprintHash(), (long) stack.getCount(), Long::sum);
            exemplars.putIfAbsent(canonical.fingerprintHash(), canonical);
        }
        return new InventoryTotals(counts, exemplars);
    }
}
