package com.itemgraph.fabric;

import com.itemgraph.ingest.InternalObservationService;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /**
     * Records a newly added server projectile as non-quantity audit evidence.
     * The source item is copied only when the projectile exposes it; no
     * inventory decrement or ground-flow edge is inferred here.
     */
    public static void onProjectileSpawn(Entity entity) {
        if (!(entity instanceof Projectile projectile)
                || entity.level().isClientSide()
                || !(projectile.getOwner() instanceof ServerPlayer player)) {
            return;
        }
        ItemStack stack = projectile instanceof AbstractArrow arrow
                ? arrow.getPickupItemStackOrigin()
                : projectile instanceof ItemSupplier supplier ? supplier.getItem() : ItemStack.EMPTY;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String actionType = projectile instanceof ThrowableItemProjectile || projectile instanceof ThrownTrident
                ? "THROW_ITEM" : "SHOOT_ITEM";
        recordProjectileAudit(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), projectile.getX(), projectile.getY(),
                projectile.getZ(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
    }

    /**
     * Applies the addFreshEntity result before recording spawn evidence. A
     * rejected or duplicate registration is not a durable spawn observation.
     */
    public static void onProjectileAdded(Entity entity, boolean added) {
        if (added) {
            onProjectileSpawn(entity);
        }
    }

    static void recordProjectileAudit(String actionType, String playerUuid, String playerName,
                                      String levelName, double x, double y, double z,
                                      String itemId, String projectileId) {
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), actionType, playerUuid, playerName, levelName,
                        x, y, z, itemId, "projectile=" + projectileId + " evidence=spawned_by_player", null));
    }

    /**
     * Records every newly occupied cell in a completed BlockItem placement. The
     * before-state snapshot is bounded by the mixin and includes the adjacent
     * cells used by vanilla two-cell and multi-cell blocks.
     */
    public static void onBlockItemPlaced(BlockPlaceContext context, BlockItem item,
                                         InteractionResult result,
                                         Map<BlockPos, BlockState> beforeStates) {
        if (context == null || item == null || result == null || !result.consumesAction()
                || beforeStates == null || beforeStates.isEmpty()
                || !(context.getPlayer() instanceof ServerPlayer player)
                || !(context.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, BlockState> afterStates = beforeStates.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        BlockPos::immutable,
                        level::getBlockState,
                        (left, right) -> right,
                        java.util.LinkedHashMap::new));
        for (BlockPos pos : changedBlockPositions(beforeStates, afterStates, item.getBlock())) {
            BlockState state = afterStates.get(pos);
            recordBlockPlacement(player.getUUID().toString(), player.getGameProfile().getName(),
                    level.dimension().location().toString(), pos,
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        }
    }

    /**
     * Returns every before/after cell changed to the placed block. This pure
     * helper keeps multi-cell placement coverage deterministic and testable.
     */
    static List<BlockPos> changedBlockPositions(Map<BlockPos, BlockState> beforeStates,
                                                 Map<BlockPos, BlockState> afterStates,
                                                 Block placedBlock) {
        if (beforeStates == null || afterStates == null || placedBlock == null) {
            return List.of();
        }
        List<BlockPos> changed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : afterStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState after = entry.getValue();
            BlockState before = beforeStates.get(pos);
            if (after != null && after.getBlock() == placedBlock
                    && (before == null || before.getBlock() != placedBlock)
                    && !after.equals(before)) {
                changed.add(pos.immutable());
            }
        }
        return List.copyOf(changed);
    }

    static void recordBlockPlacement(String playerUuid, String playerName, String levelName,
                                      BlockPos pos, String blockId) {
        if (pos == null || blockId == null || blockId.isBlank()) {
            return;
        }
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), "PLACE_BLOCK", playerUuid, playerName, levelName,
                        pos.getX(), pos.getY(), pos.getZ(), blockId, null, null));
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
