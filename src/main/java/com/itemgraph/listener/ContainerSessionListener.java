package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.listener.ContainerInteractionTracker.ContainerKey;
import com.itemgraph.listener.ContainerInteractionTracker.InventoryTotals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
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
 * {@code Open} and diffs it at {@code Close}; the residual delta — after
 * automation credits reported by {@link ContainerCapabilityWrapper} are removed —
 * is recorded as {@code ADD_ITEM}/{@code REMOVE_ITEM}.
 *
 * <h2>Container resolution</h2>
 * <p>The observed {@code Container} is located by scanning
 * {@code menu.slots}: a slot backed by a {@link BlockEntity} resolves directly to
 * that block position. A {@link CompoundContainer} (double chest) has no
 * accessible block entity, so the position is recovered from the
 * {@link PlayerInteractEvent.RightClickBlock} that opened the menu — recorded the
 * same tick — and the connected chest half is registered as an alias so
 * automation against either half hits the same watch. Menus whose slots are not
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
        if (level.isClientSide()) {
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
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        // A disconnecting player may bypass the Close event; never leave a stale watch.
        ContainerInteractionTracker.getInstance().closeSession(
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

        for (Slot slot : menu.slots) {
            Container c = slot.container;
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
        if (containerEntity != null) {
            BlockPos pos = containerEntity.getBlockPos();
            Container observed = blockContainer;
            ContainerInteractionTracker.getInstance().openSession(
                    player.getUUID(), player.getGameProfile().getName(),
                    new ContainerKey(levelId, pos.getX(), pos.getY(), pos.getZ()),
                    () -> snapshotTotals(observed),
                    List.of());
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
        List<ContainerKey> aliases = List.of();
        BlockState state = player.level().getBlockState(click.pos());
        if (state.getBlock() instanceof ChestBlock
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos partner = click.pos().relative(ChestBlock.getConnectedDirection(state));
            aliases = List.of(new ContainerKey(levelId, partner.getX(), partner.getY(), partner.getZ()));
        }

        ContainerInteractionTracker.getInstance().openSession(
                player.getUUID(), player.getGameProfile().getName(),
                new ContainerKey(levelId, click.pos().getX(), click.pos().getY(), click.pos().getZ()),
                () -> snapshotTotals(observed),
                aliases);
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
