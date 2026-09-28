package com.itemgraph.fabric;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.FlowBrowserService;
import com.itemgraph.command.InspectionService;
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
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fabric-native non-quantity audit capture. Each callback copies only immutable
 * identifiers and text before handing the record to ItemGraph's bounded worker.
 */
public final class FabricNativeAuditEventListener {
    private static final int MAX_DETAIL_LENGTH = 16_384;
    private static final int MAX_DROP_CAPTURE_DEPTH = 32;
    private static final ThreadLocal<Deque<DropCapture>> PENDING_PLAYER_DROPS = new ThreadLocal<>();

    private record DropCapture(Set<ItemEntity> addedEntities, String actionType) {
    }

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
            FabricContainerSessionListener.onMenuClosing(player);
            InspectionService.getInstance().clear(player.getUUID());
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
                InteractionResult inspectionResult = tryOpenInspection(
                        InspectionService.getInstance(),
                        FabricNativeAuditEventListener::openFlowBrowser,
                        serverPlayer, serverLevel, hit.getBlockPos());
                if (inspectionResult != null) {
                    return inspectionResult;
                }
                FabricContainerSessionListener.rememberClick(serverPlayer, serverLevel, hit.getBlockPos());
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

    @FunctionalInterface
    interface BrowserOpener {
        int open(ServerPlayer player, ServerLevel level, BlockPos pos);
    }

    /**
     * Handles the Fabric equivalent of NeoForge's high-priority inspection
     * listener. A supported container click is consumed only after the async
     * flow-browser request is accepted; unsupported blocks remain ordinary
     * interactions and are still recorded as audit evidence.
     */
    static InteractionResult tryOpenInspection(InspectionService inspections,
                                                BrowserOpener browserOpener,
                                                ServerPlayer player,
                                                ServerLevel level,
                                                BlockPos pos) {
        if (inspections == null || browserOpener == null || player == null || level == null || pos == null
                || !inspections.isEnabled(player.getUUID())) {
            return null;
        }
        if (!player.createCommandSourceStack().hasPermission(2)) {
            inspections.clear(player.getUUID());
            return null;
        }
        if (!(level.getBlockEntity(pos) instanceof Container)) {
            return null;
        }
        return browserOpener.open(player, level, pos) == 0 ? null : InteractionResult.SUCCESS;
    }

    private static int openFlowBrowser(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return FlowBrowserService.openContainer(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), null);
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

    /** Starts a nested-safe drop capture until the addFreshEntity result is known. */
    public static void beginItemDropCapture(ServerPlayer player) {
        Deque<DropCapture> pending = PENDING_PLAYER_DROPS.get();
        if (pending == null) {
            pending = new java.util.ArrayDeque<>();
            PENDING_PLAYER_DROPS.set(pending);
        }
        if (pending.size() >= MAX_DROP_CAPTURE_DEPTH) {
            // A thrown mod callback can skip ServerPlayer.drop's RETURN hook.
            // Bound stale contexts so repeated failures cannot retain entities.
            pending.clear();
        }
        String actionType = player != null && player.isDeadOrDying() ? "DEATH_DROP" : "DROP_ITEM";
        pending.push(new DropCapture(Collections.newSetFromMap(new IdentityHashMap<>()), actionType));
    }

    /** Associates a successful server-level item insertion with the current drop call. */
    public static void onItemEntityAdded(Entity entity, boolean added) {
        if (!added || !(entity instanceof ItemEntity itemEntity)) {
            return;
        }
        Deque<DropCapture> pending = PENDING_PLAYER_DROPS.get();
        if (pending != null && !pending.isEmpty()) {
            pending.peek().addedEntities().add(itemEntity);
        }
    }

    /** Finishes a drop capture and records only an entity accepted by addFreshEntity. */
    public static String finishItemDropCapture(ServerPlayer player, ItemEntity itemEntity,
                                                ItemStack originalStack) {
        Deque<DropCapture> pending = PENDING_PLAYER_DROPS.get();
        DropCapture capture = pending == null || pending.isEmpty() ? null : pending.pop();
        if (pending != null && pending.isEmpty()) {
            PENDING_PLAYER_DROPS.remove();
        }
        if (capture == null || !capture.addedEntities().contains(itemEntity)) {
            return null;
        }
        onItemDropped(player, itemEntity, originalStack, capture.actionType());
        return capture.actionType();
    }

    /** Records a drop only after ServerPlayer.drop and addFreshEntity both succeed. */
    public static void onItemDropped(ServerPlayer player, ItemEntity itemEntity, ItemStack originalStack) {
        onItemDropped(player, itemEntity, originalStack, "DROP_ITEM");
    }

    private static void onItemDropped(ServerPlayer player, ItemEntity itemEntity,
                                      ItemStack originalStack, String actionType) {
        if (player == null || itemEntity == null || originalStack == null || originalStack.isEmpty()
                || actionType == null || player.level().isClientSide() || itemEntity.isRemoved()) {
            return;
        }
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(originalStack);
        recordItemObservation(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                "GROUND", canonical, itemEntity.getItem().getCount(), itemEntity.getUUID().toString());
    }

    /** Records the exact count delta consumed by a successful item-entity pickup. */
    public static void onItemPickedUp(ServerPlayer player, ItemEntity itemEntity,
                                      ItemStack originalStack, int currentCount) {
        if (player == null || itemEntity == null || originalStack == null || originalStack.isEmpty()
                || player.level().isClientSide()) {
            return;
        }
        int amount = originalStack.getCount() - Math.max(0, currentCount);
        if (amount <= 0) {
            return;
        }
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(originalStack);
        recordItemObservation("PICKUP_ITEM", player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                player.getX(), player.getY(), player.getZ(),
                "GROUND", canonical, amount, itemEntity.getUUID().toString());
    }

    /** Records a completed eat/drink action at LivingEntity.completeUsingItem's return boundary. */
    public static void onItemUseFinished(ServerPlayer player, ItemStack originalStack, ItemStack resultStack) {
        if (player == null || originalStack == null || originalStack.isEmpty()
                || resultStack == null || player.level().isClientSide()) {
            return;
        }
        UseAnim animation = originalStack.getUseAnimation();
        if (animation != UseAnim.EAT && animation != UseAnim.DRINK) {
            return;
        }
        boolean consumed = resultStack.isEmpty()
                || resultStack.getCount() < originalStack.getCount()
                || !ItemStack.isSameItemSameComponents(originalStack, resultStack);
        if (!consumed) {
            return;
        }
        recordUnknownItemObservation(player, "CONSUME_ITEM", originalStack.copy(), 1);
    }

    /** Records the authoritative durability-break boundary before ItemStack shrinks the stack. */
    public static void onItemDestroyed(ServerPlayer player, ItemStack originalStack) {
        if (player == null || originalStack == null || originalStack.isEmpty()
                || player.level().isClientSide()) {
            return;
        }
        recordUnknownItemObservation(player, "BREAK_ITEM", originalStack.copy(), 1);
    }

    static void recordUnknownItemObservation(ServerPlayer player, String actionType,
                                              ItemStack stack, int amount) {
        if (player == null || stack == null || stack.isEmpty() || amount <= 0
                || player.level().isClientSide()) {
            return;
        }
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack);
        String level = player.level().dimension().location().toString();
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), actionType,
                player.getUUID().toString(), player.getGameProfile().getName(), level,
                player.getX(), player.getY(), player.getZ(),
                level, null, null, null, "UNKNOWN", canonical, amount, null));
    }

    static void recordItemObservation(String actionType, String playerUuid, String playerName,
                                       String levelName, double sourceX, double sourceY, double sourceZ,
                                       double targetX, double targetY, double targetZ, String targetType,
                                       CanonicalItem canonical, int amount, String itemEntityUuid) {
        if (actionType == null || playerUuid == null || levelName == null || canonical == null || amount <= 0) {
            return;
        }
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), actionType, playerUuid, playerName, levelName,
                sourceX, sourceY, sourceZ, levelName, targetX, targetY, targetZ,
                targetType, canonical, amount, itemEntityUuid));
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
