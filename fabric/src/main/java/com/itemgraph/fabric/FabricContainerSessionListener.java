package com.itemgraph.fabric;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.listener.ContainerInteractionTracker;
import com.itemgraph.listener.ContainerInteractionTracker.ContainerKey;
import com.itemgraph.listener.ContainerInteractionTracker.InventoryTotals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.CompoundContainer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

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
        for (Slot slot : menu.slots) {
            Container container = slot.container;
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
        if (containerEntity != null) {
            BlockPos pos = containerEntity.getBlockPos();
            Container observed = blockContainer;
            ContainerInteractionTracker.getInstance().openSession(
                    player.getUUID(), player.getGameProfile().getName(),
                    new ContainerKey(levelId, pos.getX(), pos.getY(), pos.getZ()),
                    () -> snapshotTotals(observed), List.of());
            return;
        }
        if (merged == null || click == null || !click.levelId().equals(levelId)
                || player.level().getGameTime() - click.gameTime() > 1
                || !(player.level().getBlockEntity(click.pos()) instanceof Container)) {
            return;
        }

        List<ContainerKey> aliases = List.of();
        BlockState state = player.level().getBlockState(click.pos());
        if (state.getBlock() instanceof ChestBlock
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = click.pos().relative(ChestBlock.getConnectedDirection(state));
            aliases = List.of(new ContainerKey(levelId, partner.getX(), partner.getY(), partner.getZ()));
        }
        CompoundContainer observed = merged;
        ContainerInteractionTracker.getInstance().openSession(
                player.getUUID(), player.getGameProfile().getName(),
                new ContainerKey(levelId, click.pos().getX(), click.pos().getY(), click.pos().getZ()),
                () -> snapshotTotals(observed), aliases);
    }

    public static void onMenuClosing(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        PENDING_CLICKS.remove(player.getUUID());
        ContainerInteractionTracker.getInstance().closeSession(
                player.getUUID(), player.getX(), player.getY(), player.getZ());
    }

    public static void clearAll() {
        PENDING_CLICKS.clear();
        ContainerInteractionTracker.getInstance().clearAll();
    }

    public static void flushAll() {
        PENDING_CLICKS.clear();
        ContainerInteractionTracker.getInstance().closeAllSessions();
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
