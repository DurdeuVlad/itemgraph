package com.itemgraph.fabric;

import com.itemgraph.ingest.InternalObservationService;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Fabric-native non-quantity audit capture. Each callback copies only immutable
 * identifiers and text before handing the record to ItemGraph's bounded worker.
 */
public final class FabricNativeAuditEventListener {
    private static final int MAX_DETAIL_LENGTH = 16_384;

    private FabricNativeAuditEventListener() {
    }

    static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            submit("PLAYER_JOIN", player, player.level(), player.blockPosition(), null, null);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            submit("PLAYER_QUIT", player, player.level(), player.blockPosition(), null, null);
        });
        ServerMessageEvents.CHAT_MESSAGE.register(FabricNativeAuditEventListener::onChat);
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                submit("BREAK_BLOCK", serverPlayer, serverLevel, pos,
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), null);
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                submit("INTERACT_BLOCK_ATTEMPT", serverPlayer, serverLevel, hit.getBlockPos(),
                        BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.getBlockPos()).getBlock()).toString(), null);
            }
            return InteractionResult.PASS;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof LivingEntity victim
                    && source.getEntity() instanceof ServerPlayer player) {
                submit("KILL_ENTITY", player, player.level(), victim.blockPosition(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString(), null);
            }
        });
    }

    /**
     * Records the command dispatch boundary exposed by the Fabric mixin.
     * Minecraft's command dispatcher has no result callback here, so this is
     * deliberately an attempt rather than completed command evidence.
     */
    public static void onCommandAttempt(ParseResults<CommandSourceStack> parse, String command) {
        if (parse == null || parse.getContext() == null || parse.getContext().getSource() == null
                || !(parse.getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        recordCommandAttempt(player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), player.blockPosition(), command);
    }

    static void recordCommandAttempt(String playerUuid, String playerName, String levelName,
                                      BlockPos pos, String command) {
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), "COMMAND_ATTEMPT", playerUuid, playerName,
                        levelName, pos.getX(), pos.getY(), pos.getZ(), null, bounded(command), null));
    }

    private static void onChat(PlayerChatMessage message, ServerPlayer player, ChatType.Bound boundType) {
        submit("CHAT_MESSAGE", player, player.level(), player.blockPosition(), null,
                bounded(message.signedContent()));
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

    private static String bounded(String value) {
        if (value == null || value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH);
    }
}
