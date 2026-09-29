package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.BlockInspectionTargets;
import com.itemgraph.listener.ContainerInteractionTracker.ContainerKey;
import com.itemgraph.listener.ContainerInteractionTracker.InventoryTotals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Binds real {@link PlayerContainerEvent} open/close lifecycle to
 * {@link ContainerInteractionTracker} session watches (0.2.0 — Issue 3).
 *
 * <p>Player-driven container transfers are not observable through the
 * {@code IItemHandler} capability (menus mutate {@code Container} directly), so
 * this listener snapshots a fingerprint-level view of the container at
 * {@code Open} and diffs it at {@code Close}; the residual session net delta — after
 * capability-mediated changes are removed — is recorded as {@code ADD_ITEM}/{@code REMOVE_ITEM}
 * with explicit start/end timestamps, not as click-time evidence.
 *
 * <h2>Container resolution</h2>
 * <p>The observed {@code Container} is located by scanning
 * {@code menu.slots}: a slot backed by a {@link BlockEntity} resolves directly to
 * that block position. A {@link CompoundContainer} (double chest) has no
 * accessible block entity, so the position is recovered from the
 * {@link PlayerInteractEvent.RightClickBlock} that opened the menu — recorded the
 * same tick — and the connected chest half is registered as an alias so
 * capability calls against either half hit the same watch. Menus whose slots are not
 * backed by a block-entity container (crafting grids, ender chests, anvils) are
 * ignored.
 */
public class ContainerSessionListener {

    private record PendingClick(String levelId, BlockPos pos, long gameTime) {}

    /** Last observed container-block click per player, consumed by the next Open. */
    private final Map<UUID, PendingClick> pendingClicks = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide() || event.isCanceled()) {
            return;
        }
        // Only remember clicks on blocks that actually hold an inventory; this
        // merely biases double-chest resolution, never creates a watch by itself.
        if (level.getBlockEntity(event.getPos()) instanceof Container) {
            pendingClicks.put(event.getEntity().getUUID(),
                    new PendingClick(level.dimension().location().toString(),
                            event.getPos().immutable(), level.getGameTime()));
        }
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        resolve(player, event.getContainer());
    }

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        Player player = event.getEntity();
        if (player.level().isClientSide()) {
            return;
        }
        ContainerInteractionTracker.getInstance().closeSession(
                player.getUUID(), player.getX(), player.getY(), player.getZ());
        EnderChestInteractionTracker.getInstance().closeSession(
                player.getUUID(), player.getX(), player.getY(), player.getZ());
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // A disconnecting player may bypass the Close event; never leave a stale watch.
        ContainerInteractionTracker.getInstance().closeSession(
                event.getEntity().getUUID(),
                event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ());
        EnderChestInteractionTracker.getInstance().closeSession(
                event.getEntity().getUUID(),
                event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ());
    }

    private void resolve(Player player, AbstractContainerMenu menu) {
        // Consume any pending click up front: a stale click must never pair with
        // a later menu open (e.g. a mod programmatically opening a menu the same
        // tick after an unrelated container click).
        PendingClick click = pendingClicks.remove(player.getUUID());

        Container blockContainer = null;
        BlockEntity containerEntity = null;
        CompoundContainer merged = null;
        PlayerEnderChestContainer enderContainer = null;

        for (Slot slot : menu.slots) {
            Container c = slot.container;
            if (c instanceof PlayerEnderChestContainer ender) {
                enderContainer = ender;
                break;
            }
            if (c instanceof BlockEntity be) {
                containerEntity = be;
                blockContainer = c;
                break;
            }
            if (c instanceof CompoundContainer cc && merged == null) {
                merged = cc;
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
                    () -> snapshotTotals(observed),
                    aliases(levelId, pos, canonical, player.level()));
            return;
        }

        // Double chest (or any merged container): no block entity in the slot view —
        // recover the clicked chest position recorded this tick.
        if (merged == null || click == null
                || !click.levelId().equals(levelId)
                || player.level().getGameTime() - click.gameTime() > 1) {
            return;
        }
        if (!(player.level().getBlockEntity(click.pos()) instanceof Container)) {
            return;
        }

        CompoundContainer observed = merged;
        BlockPos canonical = BlockInspectionTargets.canonicalPosition(player.level(), click.pos());

        ContainerInteractionTracker.getInstance().openSession(
                player.getUUID(), player.getGameProfile().getName(),
                key(levelId, canonical),
                () -> snapshotTotals(observed),
                aliases(levelId, click.pos(), canonical, player.level()));
    }

    private static ContainerKey key(String levelId, BlockPos pos) {
        return new ContainerKey(levelId, pos.getX(), pos.getY(), pos.getZ());
    }

    private static List<ContainerKey> aliases(String levelId, BlockPos clicked, BlockPos canonical, Level level) {
        return BlockInspectionTargets.resolveBlockPositions(level, clicked).stream()
                .filter(pos -> !pos.equals(canonical))
                .map(pos -> key(levelId, pos))
                .toList();
    }

    /**
     * Fingerprint-level totals over a container's live contents: summed count per
     * fingerprint plus one representative {@link CanonicalItem} per fingerprint.
     * Called on the server thread at Open and Close.
     */
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
