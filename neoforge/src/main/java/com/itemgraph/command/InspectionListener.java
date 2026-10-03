package com.itemgraph.command;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Opens the read-only history views when inspection mode is active.
 *
 * <p>The listener runs before normal interaction listeners. Cancellation prevents
 * the vanilla block and item interaction paths, so the held item is not consumed
 * and no audit or container-transfer event is created for the inspection request.</p>
 */
public class InspectionListener {

    @FunctionalInterface
    interface BlockHistoryOpener {
        int open(ServerPlayer player, Level level, BlockPos pos);
    }

    private final InspectionService inspections;
    private final BlockHistoryOpener blockHistoryOpener;

    public InspectionListener() {
        this(InspectionService.getInstance(), InspectionListener::openBlockHistory);
    }

    InspectionListener(InspectionService inspections, BlockHistoryOpener blockHistoryOpener) {
        this.inspections = inspections;
        this.blockHistoryOpener = blockHistoryOpener;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
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
        BlockPos target = BlockInspectionTargets.resolveRightClickTarget(
                level, event.getPos(), event.getHitVec().getDirection());
        if (blockHistoryOpener.open(player, level, target) == 0) {
            return;
        }
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
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

        if (blockHistoryOpener.open(player, level, event.getPos()) == 0) {
            return;
        }
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

    private static int openBlockHistory(ServerPlayer player, Level level, BlockPos pos) {
        return ItemGraphCommands.openBlockInspection(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }
}
