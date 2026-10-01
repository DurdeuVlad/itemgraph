package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.ingest.EntityInteractionEvidence;
import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.command.BlockInspectionTargets;
import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.neoforge.mixin.BucketItemAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.List;

/**
 * Native replacement coverage for GriefLogger's non-item audit categories.
 *
 * <p>Handlers run on NeoForge's server event bus and enqueue immutable values;
 * SQLite work remains on {@link InternalObservationService}'s worker thread.
 * Canceled events are excluded. Hooks that run before the game action completes
 * are labeled as attempts, so the ledger never presents a pre-action callback as
 * completed evidence.</p>
 */
public final class NativeAuditEventListener {
    private static final int MAX_DETAIL_LENGTH = 16_384;

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            submit("PLAYER_JOIN", player, player.level(), player.blockPosition(), null, null);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ItemGraphCommands.clearPageSession(player.getUUID());
            submit("PLAYER_QUIT", player, player.level(), player.blockPosition(), null, null);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player != null && !event.isCanceled()) {
            submit("CHAT_MESSAGE", player, player.level(), player.blockPosition(),
                    null, bounded(event.getRawText()));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCommand(CommandEvent event) {
        if (event.isCanceled()) {
            return;
        }
        var parse = event.getParseResults();
        if (parse == null || parse.getContext() == null || parse.getContext().getSource() == null
                || !(parse.getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        String command = parse.getReader().getString();
        submit("COMMAND_ATTEMPT", player, player.level(), player.blockPosition(),
                null, bounded(command));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<AuditEventQueryService.ExactPosition> supersessionPositions = BlockInspectionTargets
                .resolveBlockPositions(level, event.getPos(), event.getState()).stream()
                .map(pos -> new AuditEventQueryService.ExactPosition(pos.getX(), pos.getY(), pos.getZ()))
                .toList();
        submit("BREAK_BLOCK", player, level, event.getPos(), blockId(event.getState()), null,
                supersessionPositions);
    }

    /** Records a source fluid removed by a successful bucket pickup. */
    public static void recordBucketPickup(ServerPlayer player, LevelAccessor level,
                                          BlockPos pos, BlockState sourceState, ItemStack result) {
        if (player == null || !(level instanceof Level actualLevel) || actualLevel.isClientSide
                || pos == null
                || sourceState == null || sourceState.getFluidState().isEmpty()
                || result == null || result.isEmpty()
                || !(result.getItem() instanceof net.minecraft.world.item.BucketItem filledBucket)
                || !(actualLevel instanceof ServerLevel serverLevel)) {
            return;
        }
        var content = ((BucketItemAccessor) filledBucket).itemgraph$getContent();
        if (content.defaultFluidState().isEmpty()) {
            return;
        }
        BlockState fluidBlock = content.defaultFluidState().createLegacyBlock();
        if (fluidBlock.isAir()) {
            return;
        }
        List<AuditEventQueryService.ExactPosition> positions = BlockInspectionTargets
                .resolveBlockPositions(serverLevel, pos, fluidBlock).stream()
                .map(target -> new AuditEventQueryService.ExactPosition(
                        target.getX(), target.getY(), target.getZ()))
                .toList();
        submit("BREAK_BLOCK", player, serverLevel, pos, blockId(fluidBlock), null, positions);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (event instanceof BlockEvent.EntityMultiPlaceEvent multiPlace) {
            String placedBlock = blockId(event.getPlacedBlock());
            for (var snapshot : multiPlace.getReplacedBlockSnapshots()) {
                submit("PLACE_BLOCK", player, level, snapshot.getPos(), placedBlock, null);
            }
            return;
        }
        submit("PLACE_BLOCK", player, level, event.getPos(), blockId(event.getPlacedBlock()), null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!BlockInspectionTargets.isGriefLoggerBlockInteraction(level, event.getPos(), event.getHand())) {
            return;
        }
        submit("INTERACT_BLOCK_ATTEMPT", player, level, event.getPos(),
                blockId(level.getBlockState(event.getPos())), "outcome=attempt");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)
                || !event.isCanceled()) {
            // EntityInteractSpecific is posted first for every right-clicked
            // entity; its attempt row covers both handled and fallback paths.
            return;
        }
        Entity target = event.getTarget();
        if (target instanceof ArmorStand) {
            EntityInteractionEvidence.recordArmorStandCallbackCanceled(
                    player, (ArmorStand) target, event.getHand(),
                    player.getItemInHand(event.getHand()), "entity_interact");
            return;
        }
        submit("INTERACT_ENTITY_DENIED", player, level, target.blockPosition(),
                BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                EntityInteractionEvidence.canceledAttemptDetails(
                        target, event.getHand(), player.getItemInHand(event.getHand()),
                        "callback=entity_interact reason=LOADER_CALLBACK_CANCELED"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Entity target = event.getTarget();
        boolean canceled = event.isCanceled();
        String completion = target instanceof ArmorStand
                ? (canceled ? "callback_canceled" : "armor_stand_return_hook")
                : (canceled ? "callback_canceled" : "specific_result_unobserved");
        String detail = canceled
                ? EntityInteractionEvidence.canceledAttemptDetails(
                        target, event.getHand(), player.getItemInHand(event.getHand()), completion)
                : EntityInteractionEvidence.attemptDetails(
                        target, event.getHand(), player.getItemInHand(event.getHand()), completion);
        submit(canceled ? "INTERACT_ENTITY_DENIED" : "INTERACT_ENTITY", player, level,
                target.blockPosition(), BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(), detail);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()
                || !(event.getEntity() instanceof LivingEntity victim)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        submit("KILL_ENTITY", player, player.level(), victim.blockPosition(),
                BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString(), null);
    }

    private static void submit(String eventType, ServerPlayer player, Level level, BlockPos pos,
                                String subjectId, String detail) {
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), eventType,
                        player.getUUID().toString(), player.getGameProfile().getName(),
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(), subjectId, detail, null));
    }

    private static void submit(String eventType, ServerPlayer player, Level level, BlockPos pos,
                               String subjectId, String detail,
                               List<AuditEventQueryService.ExactPosition> supersessionPositions) {
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), eventType,
                        player.getUUID().toString(), player.getGameProfile().getName(),
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(), subjectId, detail, null, supersessionPositions));
    }

    private static String blockId(BlockState state) {
        if (state == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String bounded(String value) {
        if (value == null || value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH);
    }
}
