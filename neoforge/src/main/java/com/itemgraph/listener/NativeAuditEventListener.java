package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

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
        submit("BREAK_BLOCK", player, level, event.getPos(), blockId(event.getState()), null);
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
        submit("INTERACT_BLOCK_ATTEMPT", player, level, event.getPos(),
                blockId(level.getBlockState(event.getPos())), null);
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
