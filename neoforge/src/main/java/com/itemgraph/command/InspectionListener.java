package com.itemgraph.command;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Opens the read-only flow browser when inspection mode is active.
 *
 * <p>The listener runs before normal right-click listeners and cancels only a
 * supported container click. Cancellation prevents the vanilla block and item
 * interaction paths, so the held item is not consumed and no container-transfer
 * session is opened for the inspection request itself.</p>
 */
public class InspectionListener {

    @FunctionalInterface
    interface BrowserOpener {
        int open(ServerPlayer player, Level level, BlockPos pos);
    }

    private final InspectionService inspections;
    private final BrowserOpener browserOpener;

    public InspectionListener() {
        this(InspectionService.getInstance(), InspectionListener::openFlowBrowser);
    }

    InspectionListener(InspectionService inspections, BrowserOpener browserOpener) {
        this.inspections = inspections;
        this.browserOpener = browserOpener;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide() || !inspections.isEnabled(player.getUUID())) {
            return;
        }
        if (!player.createCommandSourceStack().hasPermission(2)) {
            inspections.clear(player.getUUID());
            return;
        }
        if (!(level.getBlockEntity(event.getPos()) instanceof Container)) {
            return;
        }

        int accepted = browserOpener.open(player, level, event.getPos());
        if (accepted == 0) {
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            inspections.clear(player.getUUID());
        }
    }

    public void clearAll() {
        inspections.clear();
    }

    private static int openFlowBrowser(ServerPlayer player, Level level, BlockPos pos) {
        return FlowBrowserService.openContainer(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), null);
    }
}
