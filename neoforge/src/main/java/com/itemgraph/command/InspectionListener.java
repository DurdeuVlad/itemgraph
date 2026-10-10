package com.itemgraph.command;

import com.itemgraph.i18n.ItemGraphLanguage;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Opens the read-only history views when inspection mode is active.
 *
 * <p>The listener runs before normal interaction listeners. Cancellation prevents
 * the vanilla block and item interaction paths, so the held item is not consumed
 * and no audit or container-transfer event is created for the inspection request.</p>
 *
 * <p>A click is consumed when its read-only request is accepted, when the request is
 * denied for a missing permission grant (a stable, already-messaged denial must not
 * mutate the inspected scene), and when the click itself detects that
 * {@code itemgraph.command.inspect} was revoked — that click disables the mode,
 * notifies the player, and is consumed. Transient rejections (queue full, database
 * unavailable) and all rejected container right-clicks keep vanilla behavior so a
 * missing {@code itemgraph.gui} grant still opens the chest normally.</p>
 *
 * <p>A consumed right-click produces more than the block-use packet: the client
 * also sends the off-hand {@code useItemOn} and, when its predicted result was
 * not consuming, a separate item-use packet ({@code ServerboundUseItemPacket}).
 * Both are consumed while inspection is on — or within
 * {@link InspectionService#CONSUMED_PACKET_WINDOW_TICKS} of a consumed click —
 * so a held bucket, pearl, or off-hand item cannot mutate the inspected scene.
 * The marker, not {@code isEnabled}, covers the revocation-detecting click: it
 * clears the mode before its twin packets arrive.</p>
 */
public class InspectionListener {

    @FunctionalInterface
    interface BlockHistoryOpener {
        int open(ServerPlayer player, Level level, BlockPos pos);
    }

    @FunctionalInterface
    interface ContainerFlowOpener {
        int open(ServerPlayer player, Level level, BlockPos pos);
    }

    private final InspectionService inspections;
    private final BlockHistoryOpener blockHistoryOpener;
    private final ContainerFlowOpener containerFlowOpener;

    public InspectionListener() {
        this(InspectionService.getInstance(), InspectionListener::openBlockHistory,
                InspectionListener::openContainerFlow);
    }

    InspectionListener(InspectionService inspections, BlockHistoryOpener blockHistoryOpener) {
        this(inspections, blockHistoryOpener,
                (player, level, pos) -> blockHistoryOpener.open(player, level, pos));
    }

    InspectionListener(InspectionService inspections, BlockHistoryOpener blockHistoryOpener,
                       ContainerFlowOpener containerFlowOpener) {
        this.inspections = inspections;
        this.blockHistoryOpener = blockHistoryOpener;
        this.containerFlowOpener = containerFlowOpener;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            // The off-hand twin of a consumed click must not place or use the
            // off-hand item on the inspected block.
            if (inspections.isEnabled(player.getUUID())
                    || inspections.consumedInteractionRecently(player.getUUID(), level.getGameTime())) {
                inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
                consumeDenied(event);
            }
            return;
        }
        if (!inspections.isEnabled(player.getUUID())) {
            return;
        }
        if (!ItemGraphPermissions.canUse(player.createCommandSourceStack(), ItemGraphPermissions.INSPECT)) {
            revokeInspection(player);
            inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
            consumeDenied(event);
            return;
        }
        BlockPos target = BlockInspectionTargets.resolveRightClickTarget(
                level, event.getPos(), event.getHitVec().getDirection());
        boolean container = level.getBlockEntity(target) instanceof Container;
        BlockPos inspectionTarget = container
                ? BlockInspectionTargets.canonicalPosition(level, target)
                : target;
        int accepted = container
                ? containerFlowOpener.open(player, level, inspectionTarget)
                : blockHistoryOpener.open(player, level, inspectionTarget);
        if (accepted == 0) {
            // Container right-clicks always fall through so a denied flow request still
            // opens the chest; a denied non-container request is consumed instead.
            if (!container && deniedByMissingGrant(player, ItemGraphPermissions.INSPECT, ItemGraphPermissions.AUDIT)) {
                inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
                consumeDenied(event);
            }
            return;
        }
        inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * Consumes the item-use twin of a right-click while inspection is active —
     * or within the twin-packet window of a consumed click — so a held bucket,
     * ender pearl, or food item is never used on an inspected scene. The marker
     * covers the revocation-detecting click, whose twin arrives after the mode
     * was already cleared.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        if (!inspections.isEnabled(player.getUUID())
                && !inspections.consumedInteractionRecently(player.getUUID(), level.getGameTime())) {
            return;
        }
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        // A FAIL result sends nothing by itself; resync so a client that already
        // predicted the use (pearl thrown, bucket swapped) reverts to server truth.
        player.containerMenu.sendAllDataToRemote();
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
        if (!ItemGraphPermissions.canUse(player.createCommandSourceStack(), ItemGraphPermissions.INSPECT)) {
            revokeInspection(player);
            event.setCanceled(true);
            return;
        }

        if (blockHistoryOpener.open(player, level, event.getPos()) == 0) {
            if (deniedByMissingGrant(player, ItemGraphPermissions.INSPECT, ItemGraphPermissions.AUDIT)) {
                event.setCanceled(true);
            }
            return;
        }
        event.setCanceled(true);
    }

    /**
     * Consumes entity right-clicks while inspection is active (or within the
     * twin-packet window of a consumed click) so armor-stand equipping, shearing,
     * milking, and other entity interactions cannot mutate the inspected scene.
     * The denial remains audit evidence: {@code NativeAuditEventListener}'s
     * LOWEST-priority entity handlers record {@code INTERACT_ENTITY_DENIED} for
     * canceled entity interactions.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!shouldConsumeEntityInteraction(event)) {
            return;
        }
        denyEntityInteraction((ServerPlayer) event.getEntity(), event.getLevel(), event.getTarget());
        // FAIL, not the default PASS: the cancellation result is returned to
        // vanilla's packet handler, and PASS would report the interaction as
        // unhandled rather than denied.
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!shouldConsumeEntityInteraction(event)) {
            return;
        }
        denyEntityInteraction((ServerPlayer) event.getEntity(), event.getLevel(), event.getTarget());
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    /**
     * Consumes entity left-clicks while inspection is active (or within the
     * twin-packet window of a consumed click) so a predicted attack cannot
     * damage, knock back, or pop the inspected entity. The denial remains audit
     * evidence: {@code NativeAuditEventListener}'s LOWEST-priority attack
     * handler records {@code ATTACK_ENTITY_DENIED} for the canceled event.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !shouldConsumeEntityInteraction(player, player.level())) {
            return;
        }
        denyEntityInteraction(player, player.level(), event.getTarget());
        event.setCanceled(true);
    }

    private boolean shouldConsumeEntityInteraction(PlayerInteractEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return false;
        }
        return shouldConsumeEntityInteraction(player, event.getLevel());
    }

    private boolean shouldConsumeEntityInteraction(ServerPlayer player, Level level) {
        return !level.isClientSide()
                && (inspections.isEnabled(player.getUUID())
                        || inspections.consumedInteractionRecently(player.getUUID(), level.getGameTime()));
    }

    private void denyEntityInteraction(ServerPlayer player, Level level, Entity target) {
        // Multipart entities are not networked under their part id; resync the
        // parent instead (the audit row still names the packet's actual target).
        Entity resyncTarget = target instanceof net.neoforged.neoforge.entity.PartEntity<?> part
                ? part.getParent() : target;
        // Mark the consume: a predicted non-consuming entity interact can emit a
        // useItem twin packet, which must stay covered if the mode clears first.
        inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
        // A denied interaction predicts client-side; resync the held stacks and
        // the target's visible state so the prediction reverts to server truth.
        // This mirrors ServerEntity's pairing resync: entity data is nullable
        // when every value is default, equipment ships all slots (including
        // empty ones to clear a predicted equip), link/passengers cover
        // predicted leashes and mounts.
        player.containerMenu.sendAllDataToRemote();
        List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> dataValues =
                resyncTarget.getEntityData().getNonDefaultValues();
        if (dataValues != null) {
            player.connection.send(new ClientboundSetEntityDataPacket(resyncTarget.getId(), dataValues));
        }
        if (resyncTarget instanceof LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                equipment.add(Pair.of(slot, living.getItemBySlot(slot)));
            }
            player.connection.send(new ClientboundSetEquipmentPacket(resyncTarget.getId(), equipment));
        }
        if (resyncTarget instanceof Leashable leashable) {
            player.connection.send(new ClientboundSetEntityLinkPacket(resyncTarget, leashable.getLeashHolder()));
        }
        player.connection.send(new ClientboundSetPassengersPacket(resyncTarget));
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

    /**
     * Whether an unaccepted inspection request failed on a missing permission grant rather
     * than a transient rejection. The route's whole node set is re-checked — not only the
     * downstream grant — so a grant revoked between the listener check and the dispatch
     * still reads as a stable denial. The denial message was already sent by the dispatch
     * layer, so the only question left is whether vanilla interaction is safe to allow.
     */
    private static boolean deniedByMissingGrant(ServerPlayer player, String... permissionNodes) {
        return !ItemGraphPermissions.canUseAll(player.createCommandSourceStack(), permissionNodes);
    }

    private void revokeInspection(ServerPlayer player) {
        inspections.clear(player.getUUID());
        player.sendSystemMessage(Component.literal(ItemGraphLanguage.text("inspect.disabled_revoked",
                "[ItemGraph] Inspection disabled: the itemgraph.command.inspect permission was revoked.")));
    }

    private static void consumeDenied(PlayerInteractEvent.RightClickBlock event) {
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    private static int openBlockHistory(ServerPlayer player, Level level, BlockPos pos) {
        return ItemGraphCommands.openBlockInspection(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    private static int openContainerFlow(ServerPlayer player, Level level, BlockPos pos) {
        return FlowBrowserService.openContainer(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), null);
    }
}
