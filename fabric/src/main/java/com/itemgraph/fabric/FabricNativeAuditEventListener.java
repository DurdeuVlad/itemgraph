package com.itemgraph.fabric;

import com.itemgraph.fabric.mixin.BucketItemAccessor;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.audit.AdminMutationCapture;
import com.itemgraph.audit.ContainerBreakCapture;
import com.itemgraph.audit.TransformationOutputEvidence;
import com.itemgraph.ingest.EntityInteractionEvidence;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.InspectionService;
import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.command.ItemGraphPermissions;
import com.itemgraph.command.BlockInspectionTargets;
import com.itemgraph.command.FlowBrowserService;
import com.itemgraph.i18n.ItemGraphLanguage;
import com.itemgraph.query.AuditEventQueryService;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import com.mojang.brigadier.ParseResults;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.StackWalker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric-native audit capture. Each callback copies only immutable identifiers,
 * item metadata, and text before handing the record to ItemGraph's bounded worker.
 */
public final class FabricNativeAuditEventListener {
    private static final int MAX_DETAIL_LENGTH = 16_384;
    private static final int MAX_DROP_CAPTURE_DEPTH = 32;
    private static final ThreadLocal<Deque<DropCapture>> PENDING_PLAYER_DROPS = new ThreadLocal<>();
    private static final int MAX_HOPPER_CAPTURE_DEPTH = 16;
    private static final StackWalker HOPPER_STACK_WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Deque<HopperCapture>> PENDING_HOPPER_TRANSFERS = new ThreadLocal<>();
    private static final int MAX_DEATH_CAPTURE_DEPTH = 8;
    private static final StackWalker DEATH_STACK_WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Deque<DeathCapture>> PENDING_PLAYER_DEATHS = new ThreadLocal<>();
    private static final ThreadLocal<Deque<ContainerBreakAttempt>> PENDING_CONTAINER_BREAKS = new ThreadLocal<>();

    private record ContainerBreakAttempt(ContainerBreakCapture.Snapshot snapshot) { }

    private record DropCapture(Set<ItemEntity> addedEntities, String actionType) {
    }

    private record HopperSite(StackTraceElement caller, int stackDepth) {
    }

    private record HopperCapture(ServerLevel level, HopperBlockEntity hopper, BlockPos hopperPos,
                                 HopperSite site,
                                 Map<BlockPos, Map<CanonicalItem, Integer>> before) {
    }

    private record DeathSite(String callerClass, String callerMethod, int stackDepth) {
    }

    private static final class DeathCapture {
        private final ServerPlayer player;
        private final DeathSite site;
        private final Set<ItemEntity> acceptedEntities = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<ItemEntity> recordedEntities = Collections.newSetFromMap(new IdentityHashMap<>());

        private DeathCapture(ServerPlayer player, DeathSite site) {
            this.player = player;
            this.site = site;
        }
    }

    record HopperDelta(BlockPos containerPos, CanonicalItem item, int amount, boolean inserted) {
    }

    private FabricNativeAuditEventListener() {
    }

    static void recordBlockInteractionAttempt(ServerPlayer player, ServerLevel level,
                                              BlockPos pos, net.minecraft.world.InteractionHand hand) {
        if (!BlockInspectionTargets.isGriefLoggerBlockInteraction(level, pos, hand)) {
            return;
        }
        submit("INTERACT_BLOCK_ATTEMPT", player, level, pos,
                BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString(),
                "outcome=attempt");
    }

    static void recordEntityInteractionAttempt(ServerPlayer player, ServerLevel level,
                                               net.minecraft.world.InteractionHand hand, Entity entity) {
        String completion = entity instanceof net.minecraft.world.entity.decoration.ArmorStand
                ? "armor_stand_return_hook" : "unobserved_non_armor_entity";
        submit("INTERACT_ENTITY", player, level, entity.blockPosition(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                EntityInteractionEvidence.attemptDetails(
                        entity, hand, player.getItemInHand(hand), completion));
    }

    static InteractionResult handleBlockUse(InspectionService inspections,
                                            BlockHistoryOpener blockHistoryOpener,
                                            ServerPlayer player, ServerLevel level,
                                            net.minecraft.world.InteractionHand hand, BlockPos pos,
                                            Direction face) {
        return handleBlockUse(inspections, blockHistoryOpener,
                blockHistoryOpener::open, player, level, hand, pos, face);
    }

    static InteractionResult handleBlockUse(InspectionService inspections,
                                            BlockHistoryOpener blockHistoryOpener,
                                            ContainerFlowOpener containerFlowOpener,
                                            ServerPlayer player, ServerLevel level,
                                            net.minecraft.world.InteractionHand hand, BlockPos pos,
                                            Direction face) {
        if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
            // The off-hand twin of a consumed click must not place or use the
            // off-hand item on the inspected block.
            if (player != null && inspections != null
                    && (inspections.isEnabled(player.getUUID())
                            || inspections.consumedInteractionRecently(player.getUUID(), level.getGameTime()))) {
                inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }
        InteractionResult inspectionResult = tryOpenInspection(
                inspections, blockHistoryOpener, containerFlowOpener, player, level, pos, face);
        if (inspectionResult != null) {
            return inspectionResult;
        }
        if (BlockInspectionTargets.isInspectableRightClickTarget(level, pos)) {
            FabricContainerSessionListener.rememberClick(player, level, pos);
        }
        recordBlockInteractionAttempt(player, level, pos, hand);
        return InteractionResult.PASS;
    }

    static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            submit("PLAYER_JOIN", player, player.level(), player.blockPosition(), null, null);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.getPlayer()));
        ServerMessageEvents.CHAT_MESSAGE.register(FabricNativeAuditEventListener::onChat);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            Deque<ContainerBreakAttempt> attempts = PENDING_CONTAINER_BREAKS.get();
            if (attempts == null) {
                attempts = new ArrayDeque<>();
                PENDING_CONTAINER_BREAKS.set(attempts);
            }
            if (level instanceof ServerLevel serverLevel) {
                if (player instanceof ServerPlayer serverPlayer) {
                    AdminMutationCapture.beginCreativeBlockBreakSafely(serverPlayer,
                            BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), pos);
                }
                attempts.push(new ContainerBreakAttempt(ContainerBreakCapture.begin(
                        player instanceof ServerPlayer serverPlayer ? serverPlayer : null,
                        serverLevel, pos, state, blockEntity)));
            } else {
                attempts.push(new ContainerBreakAttempt(null));
            }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                AdminMutationCapture.finishCreativeBlockBreakSafely(serverPlayer, pos, true, true);
                List<AuditEventQueryService.ExactPosition> supersessionPositions = BlockInspectionTargets
                        .resolveBlockPositions(serverLevel, pos, state).stream()
                        .map(target -> new AuditEventQueryService.ExactPosition(
                                target.getX(), target.getY(), target.getZ()))
                        .toList();
                submit("BREAK_BLOCK", serverPlayer, serverLevel, pos,
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), null,
                        supersessionPositions);
            }
            completeContainerBreakAttempt(true);
        });
        PlayerBlockBreakEvents.CANCELED.register((level, player, pos, state, blockEntity) -> {
            completeContainerBreakAttempt(false);
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel) {
                AdminMutationCapture.finishCreativeBlockBreakSafely(serverPlayer, pos, true, false);
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                return handleBlockUse(InspectionService.getInstance(),
                        FabricNativeAuditEventListener::openBlockHistory,
                        FabricNativeAuditEventListener::openContainerFlow,
                        serverPlayer, serverLevel, hand, hit.getBlockPos(), hit.getDirection());
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, face) -> {
            if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)
                    || !InspectionService.getInstance().isEnabled(serverPlayer.getUUID())) {
                return InteractionResult.PASS;
            }
            if (!ItemGraphPermissions.canUse(serverPlayer.createCommandSourceStack(), ItemGraphPermissions.INSPECT)) {
                InspectionService.getInstance().clear(serverPlayer.getUUID());
                notifyInspectionRevoked(serverPlayer);
                return InteractionResult.FAIL;
            }
            InteractionResult inspectionResult = tryOpenLeftClickInspection(
                    InspectionService.getInstance(), FabricNativeAuditEventListener::openBlockHistory,
                    serverPlayer, serverLevel, pos);
            return inspectionResult == null ? InteractionResult.PASS : inspectionResult;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                return handleItemUse(InspectionService.getInstance(), serverPlayer, serverLevel, hand);
            }
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
                recordEntityInteractionAttempt(serverPlayer, serverLevel, hand, entity);
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

    private static void completeContainerBreakAttempt(boolean successful) {
        Deque<ContainerBreakAttempt> attempts = PENDING_CONTAINER_BREAKS.get();
        if (attempts == null || attempts.isEmpty()) return;
        ContainerBreakAttempt attempt = attempts.pop();
        if (attempts.isEmpty()) PENDING_CONTAINER_BREAKS.remove();
        ContainerBreakCapture.complete(attempt.snapshot(), successful);
    }

    public static void onDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        clearDisconnectState(playerId);
        submit("PLAYER_QUIT", player, player.level(), player.blockPosition(), null, null);
        FabricContainerSessionListener.onMenuClosing(player);
    }

    static void clearDisconnectState(UUID playerId) {
        ItemGraphCommands.clearPageSession(playerId);
        InspectionService.getInstance().clear(playerId);
    }

    @FunctionalInterface
    interface BlockHistoryOpener {
        int open(ServerPlayer player, ServerLevel level, BlockPos pos);
    }

    @FunctionalInterface
    interface ContainerFlowOpener {
        int open(ServerPlayer player, ServerLevel level, BlockPos pos);
    }

    /**
     * Handles the Fabric equivalent of NeoForge's high-priority inspection
     * listener. All targets use the same exact, mixed-source timeline. A click is
     * consumed when its read-only request is accepted, when the request is denied
     * for a missing permission grant (a stable, already-messaged denial must not
     * mutate the inspected scene), and when the click itself detects that
     * {@code itemgraph.command.inspect} was revoked — that click disables the mode,
     * notifies the player, and is consumed. Transient rejections and all rejected
     * container right-clicks keep vanilla behavior.
     */
    static InteractionResult tryOpenInspection(InspectionService inspections,
                                                BlockHistoryOpener blockHistoryOpener,
                                                ServerPlayer player,
                                                ServerLevel level,
                                                BlockPos pos,
                                                Direction face) {
        return tryOpenInspection(inspections, blockHistoryOpener,
                blockHistoryOpener::open, player, level, pos, face);
    }

    static InteractionResult tryOpenInspection(InspectionService inspections,
                                                BlockHistoryOpener blockHistoryOpener,
                                                ContainerFlowOpener containerFlowOpener,
                                                ServerPlayer player,
                                                ServerLevel level,
                                                BlockPos pos,
                                                Direction face) {
        if (inspections == null || blockHistoryOpener == null || containerFlowOpener == null
                || player == null || level == null || pos == null
                || !inspections.isEnabled(player.getUUID())) {
            return null;
        }
        if (!ItemGraphPermissions.canUse(player.createCommandSourceStack(), ItemGraphPermissions.INSPECT)) {
            inspections.clear(player.getUUID());
            inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
            notifyInspectionRevoked(player);
            return InteractionResult.FAIL;
        }
        BlockPos target = BlockInspectionTargets.resolveRightClickTarget(level, pos, face);
        boolean container = level.getBlockEntity(target) instanceof Container;
        BlockPos inspectionTarget = container
                ? BlockInspectionTargets.canonicalPosition(level, target)
                : target;
        int accepted = container
                ? containerFlowOpener.open(player, level, inspectionTarget)
                : blockHistoryOpener.open(player, level, inspectionTarget);
        if (accepted == 0) {
            // Container right-clicks always pass so a denied flow request still opens
            // the chest; a denied non-container request is consumed instead.
            if (!container && deniedByMissingGrant(player, ItemGraphPermissions.INSPECT, ItemGraphPermissions.AUDIT)) {
                inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
                return InteractionResult.FAIL;
            }
            return null;
        }
        inspections.markInteractionConsumed(player.getUUID(), level.getGameTime());
        return InteractionResult.SUCCESS;
    }

    /**
     * The Fabric equivalent of NeoForge's {@code RightClickItem} handling: while
     * inspection is active — or within
     * {@link InspectionService#CONSUMED_PACKET_WINDOW_TICKS} of a consumed click —
     * the item-use twin of a right-click must not use the held item (bucket,
     * ender pearl, food). The marker covers the revocation-detecting click, whose
     * twin arrives after the mode was already cleared.
     */
    static InteractionResultHolder<ItemStack> handleItemUse(
            InspectionService inspections, ServerPlayer player, ServerLevel level,
            net.minecraft.world.InteractionHand hand) {
        if (player == null || hand == null) {
            return InteractionResultHolder.pass(ItemStack.EMPTY);
        }
        ItemStack stack = player.getItemInHand(hand);
        if (inspections == null || level == null
                || (!inspections.isEnabled(player.getUUID())
                        && !inspections.consumedInteractionRecently(player.getUUID(), level.getGameTime()))) {
            return InteractionResultHolder.pass(stack);
        }
        // A FAIL result sends nothing by itself; resync so a client that already
        // predicted the use (pearl thrown, bucket swapped) reverts to server truth.
        player.containerMenu.sendAllDataToRemote();
        return InteractionResultHolder.fail(stack);
    }

    /**
     * Left-click inspection applies to every block. The click is consumed when the
     * query is accepted, when it is denied for a missing grant (a denied inspection
     * must not break the block), and when it detects grant revocation; transient
     * rejections keep vanilla breaking.
     */
    static InteractionResult tryOpenLeftClickInspection(InspectionService inspections,
                                                          BlockHistoryOpener blockHistoryOpener,
                                                          ServerPlayer player,
                                                          ServerLevel level,
                                                          BlockPos pos) {
        if (inspections == null || blockHistoryOpener == null || player == null || level == null || pos == null
                || !inspections.isEnabled(player.getUUID())) {
            return null;
        }
        if (!ItemGraphPermissions.canUse(player.createCommandSourceStack(), ItemGraphPermissions.INSPECT)) {
            inspections.clear(player.getUUID());
            notifyInspectionRevoked(player);
            return InteractionResult.FAIL;
        }
        if (blockHistoryOpener.open(player, level, pos) == 0) {
            return deniedByMissingGrant(player, ItemGraphPermissions.INSPECT, ItemGraphPermissions.AUDIT)
                    ? InteractionResult.FAIL : null;
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Whether an unaccepted inspection request failed on a missing permission grant rather
     * than a transient rejection. The route's whole node set is re-checked — not only the
     * downstream grant — so a grant revoked between the listener check and the dispatch
     * still reads as a stable denial.
     */
    private static boolean deniedByMissingGrant(ServerPlayer player, String... permissionNodes) {
        return !ItemGraphPermissions.canUseAll(player.createCommandSourceStack(), permissionNodes);
    }

    private static void notifyInspectionRevoked(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(ItemGraphLanguage.text("inspect.disabled_revoked",
                "[ItemGraph] Inspection disabled: the itemgraph.command.inspect permission was revoked.")));
    }

    private static int openBlockHistory(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return ItemGraphCommands.openBlockInspection(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    private static int openContainerFlow(ServerPlayer player, ServerLevel level, BlockPos pos) {
        return FlowBrowserService.openContainer(player.createCommandSourceStack(),
                level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), null);
    }

    /**
     * Records the command dispatch boundary exposed by the Fabric mixin.
     * Minecraft's command dispatcher has no result callback here, so this is
     * deliberately an attempt rather than completed command evidence.
     */
    public static void onCommandAttempt(ParseResults<CommandSourceStack> parse, String command) {
        if (parse == null || parse.getContext() == null || parse.getContext().getSource() == null) {
            return;
        }
        CommandSourceStack source = parse.getContext().getSource();
        AdminMutationCapture.recordCommandAttemptSafely(new AdminMutationCapture.ParseResultsAdapter(
                source, command, source.hasPermission(2), false,
                !parse.getExceptions().isEmpty(), commandNodeNames(parse)));
        if (AdminMutationCapture.shouldSuppressRawCommand(command)) return;
        if (!(source.getEntity() instanceof ServerPlayer player)) return;
        recordCommandAttempt(player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), player.blockPosition(), command);
    }

    private static List<String> commandNodeNames(ParseResults<CommandSourceStack> parse) {
        List<String> names = new ArrayList<>();
        var context = parse.getContext();
        while (context != null) {
            context.getNodes().forEach(node -> names.add(node.getNode().getName()));
            context = context.getChild();
        }
        return List.copyOf(names);
    }

    static void recordCommandAttempt(String playerUuid, String playerName, String levelName,
                                      BlockPos pos, String command) {
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), "COMMAND_ATTEMPT", playerUuid, playerName,
                        levelName, pos.getX(), pos.getY(), pos.getZ(), null, bounded(command), null));
    }

    /** Records the same attempt boundary as GriefLogger's ProjectileMixin. */
    public static void onProjectileShootAttempt(Projectile projectile, Entity source) {
        if (!(source instanceof ServerPlayer player) || projectile == null
                || player.level().isClientSide()) {
            return;
        }
        ItemStack stack = projectile instanceof ThrowableItemProjectile throwable
                ? throwable.getItem()
                : projectile instanceof AbstractArrow arrow
                ? arrow.getPickupItemStackOrigin()
                : ItemStack.EMPTY;
        if (stack == null || stack.isEmpty()) {
            return;
        }
        String actionType = projectile instanceof ThrowableItemProjectile
                ? "THROW_ITEM" : "SHOOT_ITEM";
        String level = player.level().dimension().location().toString();
        recordProjectileObservation(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                level, player.getX(), player.getY(), player.getZ(), projectile.getX(), projectile.getY(),
                projectile.getZ(), ItemCanonicalizer.canonicalizeStack(stack.copy()), stack.getCount(),
                BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString());
    }

    /**
     * Records a newly added server projectile as raw accepted-spawn evidence.
     * The GriefLogger-compatible quantity row is emitted by the attempt hook;
     * this outcome must not create a second quantity-flow observation.
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
        // GriefLogger classifies every arrow-family projectile, including a
        // thrown trident, as SHOOT_ITEM; only ThrowableItemProjectile is THROW_ITEM.
        String actionType = projectile instanceof ThrowableItemProjectile
                ? "THROW_ITEM" : "SHOOT_ITEM";
        recordProjectileSpawnAccepted(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), projectile.getX(), projectile.getY(),
                projectile.getZ(), ItemCanonicalizer.canonicalizeStack(stack.copy()), stack.getCount(),
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

    static void recordProjectileObservation(String actionType, String playerUuid, String playerName,
                                             String levelName, double playerX, double playerY, double playerZ,
                                             double projectileX, double projectileY, double projectileZ,
                                             CanonicalItem item, int amount, String projectileId) {
        if (item == null || amount <= 0) {
            return;
        }
        String eventId = java.util.UUID.randomUUID().toString();
        byte[] rawData = ("{\"capture\":\"projectile_shoot_attempt\",\"event_id\":\""
                + eventId + "\",\"projectile\":\"" + projectileId + "\",\"spawn_x\":" + projectileX
                + ",\"spawn_y\":" + projectileY + ",\"spawn_z\":" + projectileZ
                + ",\"outcome\":\"attempt\",\"evidence\":\"shoot_from_rotation\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InternalObservationService service = InternalObservationService.getInstance();
        InternalObservationService.InternalObservation observation = new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), actionType, playerUuid, playerName, levelName,
                playerX, playerY, playerZ, levelName,
                null, null, null, "UNKNOWN", item.itemId(), rawData,
                item, amount, null, null,
                InternalObservationService.sourceEventIdForUuid(eventId));
        if (service.submit(observation)) {
            // Keep the legacy native-audit lookup path readable while the
            // quantity observation remains the single unified source.
            service.submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                    System.currentTimeMillis(), actionType, playerUuid, playerName, levelName,
                    projectileX, projectileY, projectileZ, item.itemId(),
                    "projectile=" + projectileId + " event_id=" + eventId
                            + " outcome=attempt evidence=shoot_from_rotation quantity=" + amount,
                    rawData, InternalObservationService.sourceEventIdForUuid(eventId)));
        }
    }

    static void recordProjectileSpawnAccepted(String actionType, String playerUuid, String playerName,
                                               String levelName, double projectileX, double projectileY,
                                               double projectileZ, CanonicalItem item, int amount,
                                               String projectileId) {
        if (item == null || amount <= 0) {
            return;
        }
        String eventId = java.util.UUID.randomUUID().toString();
        byte[] rawData = ("{\"capture\":\"projectile_spawn\",\"event_id\":\""
                + eventId + "\",\"projectile\":\"" + projectileId + "\",\"spawn_x\":" + projectileX
                + ",\"spawn_y\":" + projectileY + ",\"spawn_z\":" + projectileZ
                + ",\"outcome\":\"accepted\",\"evidence\":\"spawned_by_player\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), "PROJECTILE_SPAWN_ACCEPTED", playerUuid, playerName,
                        levelName, projectileX, projectileY, projectileZ, item.itemId(),
                        "action=" + actionType + " projectile=" + projectileId + " event_id=" + eventId
                                + " outcome=accepted evidence=spawned_by_player",
                        rawData, InternalObservationService.sourceEventIdForUuid(eventId)));
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
        if (added && entity instanceof ItemEntity acceptedItem) {
            AdminMutationCapture.recordGiveDropEntityAdmissionSafely(acceptedItem, true);
        }
        if (!added || !(entity instanceof ItemEntity itemEntity)) {
            return;
        }
        Deque<DropCapture> pending = PENDING_PLAYER_DROPS.get();
        if (pending != null && !pending.isEmpty()) {
            pending.peek().addedEntities().add(itemEntity);
        }
        Deque<DeathCapture> deaths = PENDING_PLAYER_DEATHS.get();
        if (deaths != null && !deaths.isEmpty()) {
            deaths.peek().acceptedEntities.add(itemEntity);
        }
    }

    /** Starts a bounded capture for custom item entities spawned during player death handling. */
    public static void beginPlayerDeathCapture(ServerPlayer player) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        Deque<DeathCapture> pending = PENDING_PLAYER_DEATHS.get();
        if (pending == null) {
            pending = new java.util.ArrayDeque<>();
            PENDING_PLAYER_DEATHS.set(pending);
        }
        if (pending.size() >= MAX_DEATH_CAPTURE_DEPTH) {
            pending.clear();
        }
        pending.push(new DeathCapture(player, currentDeathSite()));
    }

    /** Finishes a death capture and records accepted custom entities not already seen by ServerPlayer.drop. */
    public static void finishPlayerDeathCapture(ServerPlayer player) {
        Deque<DeathCapture> pending = PENDING_PLAYER_DEATHS.get();
        DeathCapture capture = removeMatchingDeathCapture(pending, player, currentDeathSite());
        if (pending != null && pending.isEmpty()) {
            PENDING_PLAYER_DEATHS.remove();
        }
        if (capture == null) {
            return;
        }
        for (ItemEntity itemEntity : capture.acceptedEntities) {
            if (capture.recordedEntities.contains(itemEntity) || itemEntity.isRemoved()) {
                continue;
            }
            ItemStack stack = itemEntity.getItem();
            onItemDropped(capture.player, itemEntity,
                    stack == null ? ItemStack.EMPTY : stack.copy(), "DEATH_DROP");
        }
    }

    private static DeathCapture removeMatchingDeathCapture(Deque<DeathCapture> pending,
                                                            ServerPlayer player, DeathSite site) {
        if (pending == null || pending.isEmpty() || player == null) {
            if (pending != null) {
                pending.clear();
            }
            return null;
        }
        DeathCapture match = null;
        for (DeathCapture candidate : pending) {
            if (candidate.player == player && Objects.equals(candidate.site, site)) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            pending.clear();
            return null;
        }
        while (pending.peek() != match) {
            pending.pop();
        }
        return pending.pop();
    }

    private static DeathSite currentDeathSite() {
        return DEATH_STACK_WALKER.walk(frames -> {
            String callerClass = null;
            String callerMethod = null;
            int depth = 0;
            int index = 0;
            java.util.Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                // 0=currentDeathSite, 1=begin/finish, 2=mixin handler,
                // 3=ServerPlayer.die, 4=its caller. Use class/method rather
                // than a source line because HEAD and RETURN are different
                // injection sites in the same die method.
                if (index == 4) {
                    callerClass = frame.getClassName();
                    callerMethod = frame.getMethodName();
                }
                depth++;
                index++;
            }
            return new DeathSite(callerClass, callerMethod, depth);
        });
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
        if (AdminMutationCapture.captureCreativeDrop(player, itemEntity, true)) {
            return;
        }
        // Defer same-player drops until the wrapped GiveCommand call returns its
        // entity identity; reentrant drops are replayed as ordinary evidence.
        if (AdminMutationCapture.deferGiveDropSafely(player, itemEntity, originalStack)) {
            return;
        }
        if (player == null || itemEntity == null || originalStack == null || originalStack.isEmpty()
                || actionType == null || player.level().isClientSide() || itemEntity.isRemoved()) {
            return;
        }
        ItemStack entityStack = itemEntity.getItem();
        if (entityStack == null || entityStack.isEmpty()) {
            return;
        }
        int amount = entityStack.getCount();
        if (amount <= 0) {
            return;
        }
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(originalStack);
        boolean submitted = recordItemObservation(actionType, player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                "GROUND", canonical, amount, itemEntity.getUUID().toString());
        if (submitted && "DEATH_DROP".equals(actionType)) {
            markDeathEntityRecorded(itemEntity);
        }
    }

    /** Replays non-command item entities deferred until the wrapped /give drop returned. */
    public static void replayDeferredGiveDrops(
            java.util.List<AdminMutationCapture.DeferredGiveDrop> deferredDrops) {
        if (deferredDrops == null) return;
        for (AdminMutationCapture.DeferredGiveDrop deferred : deferredDrops) {
            if (!deferred.accepted()) continue;
            onItemDropped(deferred.player(), deferred.entity(), deferred.stack(), "DROP_ITEM");
        }
    }

    private static void markDeathEntityRecorded(ItemEntity itemEntity) {
        Deque<DeathCapture> pending = PENDING_PLAYER_DEATHS.get();
        if (pending == null || itemEntity == null) {
            return;
        }
        for (DeathCapture capture : pending) {
            if (capture.acceptedEntities.contains(itemEntity)) {
                capture.recordedEntities.add(itemEntity);
                return;
            }
        }
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

    /** Captures the bounded container neighborhood around one vanilla hopper. */
    public static void beginHopperTransferCapture(Level level, BlockPos hopperPos, HopperBlockEntity hopper) {
        if (!(level instanceof ServerLevel serverLevel) || hopperPos == null || hopper == null) {
            PENDING_HOPPER_TRANSFERS.remove();
            return;
        }
        Deque<HopperCapture> pending = PENDING_HOPPER_TRANSFERS.get();
        if (pending == null) {
            pending = new java.util.ArrayDeque<>();
            PENDING_HOPPER_TRANSFERS.set(pending);
        }
        if (pending.size() >= MAX_HOPPER_CAPTURE_DEPTH) {
            // A modded nested call can skip a RETURN injector. Bound stale
            // snapshots so automation cannot retain server-thread state.
            pending.clear();
        }
        pending.push(new HopperCapture(serverLevel, hopper,
                hopperPos.immutable(),
                currentHopperSite(),
                snapshotHopperContainers(serverLevel, hopperPos)));
    }

    /** Emits only net changes when the hopper transfer method reports success. */
    public static void finishHopperTransferCapture(Level level, BlockPos hopperPos,
                                                   HopperBlockEntity hopper, boolean moved) {
        Deque<HopperCapture> pending = PENDING_HOPPER_TRANSFERS.get();
        HopperCapture capture = removeMatchingHopperCapture(
                pending, level, hopperPos, hopper, currentHopperSite());
        if (pending != null && pending.isEmpty()) {
            PENDING_HOPPER_TRANSFERS.remove();
        }
        if (capture == null || !moved) {
            return;
        }
        Map<BlockPos, Map<CanonicalItem, Integer>> after = snapshotHopperContainers(
                capture.level(), capture.before().keySet());
        for (HopperDelta delta : computeHopperDeltas(capture.before(), after)) {
            recordHopperDelta(capture.level(), delta);
        }
    }

    private static HopperCapture removeMatchingHopperCapture(Deque<HopperCapture> pending,
                                                              Level level, BlockPos hopperPos,
                                                              HopperBlockEntity hopper,
                                                              HopperSite site) {
        if (pending == null || pending.isEmpty() || !(level instanceof ServerLevel serverLevel)
                || hopperPos == null || hopper == null) {
            if (pending != null) {
                pending.clear();
            }
            return null;
        }
        HopperCapture match = null;
        for (HopperCapture candidate : pending) {
            if (candidate.level() == serverLevel && candidate.hopper() == hopper
                    && candidate.hopperPos().equals(hopperPos)
                    && Objects.equals(candidate.site(), site)) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            // A throwing nested invocation has no RETURN callback. Do not let its
            // snapshot pair with a later, unrelated hopper transfer.
            pending.clear();
            return null;
        }
        while (pending.peek() != match) {
            pending.pop();
        }
        return pending.pop();
    }

    private static HopperSite currentHopperSite() {
        return HOPPER_STACK_WALKER.walk(frames -> {
            StackTraceElement caller = null;
            int depth = 0;
            int index = 0;
            java.util.Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                // 0=currentHopperSite, 1=begin/finish, 2=mixin handler,
                // 3=HopperBlockEntity.tryMoveItems, 4=its caller. The
                // target method's source line differs between HEAD and RETURN.
                if (index == 4) {
                    caller = frame.toStackTraceElement();
                }
                depth++;
                index++;
            }
            return new HopperSite(caller, depth);
        });
    }

    private static Map<BlockPos, Map<CanonicalItem, Integer>> snapshotHopperContainers(
            ServerLevel level, BlockPos hopperPos) {
        java.util.LinkedHashSet<BlockPos> positions = new java.util.LinkedHashSet<>();
        positions.add(hopperPos.immutable());
        for (Direction direction : Direction.values()) {
            positions.add(hopperPos.relative(direction).immutable());
        }
        return snapshotHopperContainers(level, positions);
    }

    private static Map<BlockPos, Map<CanonicalItem, Integer>> snapshotHopperContainers(
            ServerLevel level, java.util.Collection<BlockPos> positions) {
        Map<BlockPos, Map<CanonicalItem, Integer>> snapshot = new java.util.LinkedHashMap<>();
        for (BlockPos position : positions) {
            BlockEntity blockEntity = level.getBlockEntity(position);
            if (!(blockEntity instanceof Container container)) {
                continue;
            }
            Map<CanonicalItem, Integer> totals = new java.util.LinkedHashMap<>();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                CanonicalItem item = ItemCanonicalizer.canonicalizeStack(stack);
                totals.merge(item, stack.getCount(), Integer::sum);
            }
            snapshot.put(position.immutable(), Map.copyOf(totals));
        }
        return Map.copyOf(snapshot);
    }

    /** Computes per-container net deltas without observing intermediate slot mutations. */
    static List<HopperDelta> computeHopperDeltas(
            Map<BlockPos, Map<CanonicalItem, Integer>> before,
            Map<BlockPos, Map<CanonicalItem, Integer>> after) {
        if (before == null || after == null || before.isEmpty()) {
            return List.of();
        }
        List<HopperDelta> deltas = new ArrayList<>();
        for (Map.Entry<BlockPos, Map<CanonicalItem, Integer>> entry : before.entrySet()) {
            BlockPos position = entry.getKey();
            Map<CanonicalItem, Integer> beforeItems = entry.getValue();
            Map<CanonicalItem, Integer> afterItems = after.getOrDefault(position, Map.of());
            Set<CanonicalItem> items = new java.util.LinkedHashSet<>(beforeItems.keySet());
            items.addAll(afterItems.keySet());
            for (CanonicalItem item : items) {
                int previous = beforeItems.getOrDefault(item, 0);
                int current = afterItems.getOrDefault(item, 0);
                int delta = current - previous;
                if (delta > 0) {
                    deltas.add(new HopperDelta(position, item, delta, true));
                } else if (delta < 0) {
                    deltas.add(new HopperDelta(position, item, -delta, false));
                }
            }
        }
        return List.copyOf(deltas);
    }

    private static void recordHopperDelta(ServerLevel level, HopperDelta delta) {
        BlockPos containerPos = delta.containerPos();
        String levelName = level.dimension().location().toString();
        byte[] rawData = "{\"capture\":\"fabric_hopper_net_delta\",\"endpoint\":\"unknown\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), delta.inserted() ? "HOPPER_INSERT" : "HOPPER_EXTRACT",
                com.itemgraph.listener.ContainerInteractionTracker.UNKNOWN_CALLER_UUID,
                com.itemgraph.listener.ContainerInteractionTracker.UNKNOWN_CALLER_NAME, levelName,
                containerPos.getX(), containerPos.getY(), containerPos.getZ(),
                levelName, (double) containerPos.getX(), (double) containerPos.getY(),
                (double) containerPos.getZ(), "CONTAINER", delta.item().itemId(), rawData,
                delta.item(), delta.amount(), null, null));
    }

    /** Records a crafting result taken from a server-side crafting result slot. */
    public static void onCrafted(ServerPlayer player, Container matrix, ItemStack output) {
        if (player == null || output == null || output.isEmpty() || player.level().isClientSide()) {
            return;
        }
        CanonicalItem result = ItemCanonicalizer.canonicalizeStack(output);
        InternalObservationService.getInstance().submitAuditEvent(TransformationOutputEvidence.create(
                "CRAFT_OUTPUT_UNRESOLVED", System.currentTimeMillis(),
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(),
                result, output.getCount(), "Fabric crafting result slot before input consumption"));
    }

    /** Records a furnace, blast-furnace, or smoker result taken by a player. */
    public static void onSmelted(ServerPlayer player, ItemStack output) {
        if (player == null || output == null || output.isEmpty() || player.level().isClientSide()) {
            return;
        }
        CanonicalItem result = ItemCanonicalizer.canonicalizeStack(output);
        InternalObservationService.getInstance().submitAuditEvent(TransformationOutputEvidence.create(
                "SMELT_OUTPUT_UNRESOLVED", System.currentTimeMillis(),
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(),
                result, output.getCount(), "Fabric furnace result slot before input consumption"));
    }

    /** Records an anvil rename or repair before the input slots are consumed. */
    public static void onAnvilResult(ServerPlayer player, Container inputs, ItemStack output) {
        if (player == null || inputs == null || output == null || output.isEmpty()
                || player.level().isClientSide()) {
            return;
        }
        ItemStack left = inputs.getItem(0);
        if (left == null || left.isEmpty()) {
            return;
        }
        CanonicalItem source = ItemCanonicalizer.canonicalizeStack(left);
        CanonicalItem result = ItemCanonicalizer.canonicalizeStack(output);
        String sourceName = source.customName();
        String resultName = result.customName();
        boolean renamed = sourceName == null ? resultName != null : !sourceName.equals(resultName);
        String type = renamed ? "ANVIL_RENAME" : "ANVIL_REPAIR";
        String details = renamed
                ? "Renamed '" + (sourceName == null ? source.itemId() : sourceName)
                + "' -> '" + (resultName == null ? result.itemId() : resultName) + "'"
                : "Anvil repair/combine on " + output.getItem();
        submitTransformation(player, type, source, result, output.getCount(), details);
    }

    private static void submitTransformation(ServerPlayer player, String type, CanonicalItem source,
                                              CanonicalItem result, int quantity, String details) {
        String level = player.level().dimension().location().toString();
        InternalObservationService.getInstance().submitTransformation(
                new InternalObservationService.InternalTransformation(
                        System.currentTimeMillis(), type,
                        player.getUUID().toString(), player.getGameProfile().getName(), level,
                        player.getX(), player.getY(), player.getZ(), source, result, quantity, details));
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

    static boolean recordItemObservation(String actionType, String playerUuid, String playerName,
                                       String levelName, double sourceX, double sourceY, double sourceZ,
                                       double targetX, double targetY, double targetZ, String targetType,
                                       CanonicalItem canonical, int amount, String itemEntityUuid) {
        if (actionType == null || playerUuid == null || levelName == null || canonical == null || amount <= 0) {
            return false;
        }
        return InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
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
                                         Map<BlockPos, BlockState> beforeStates,
                                         String mutationEventId) {
        if (context == null || item == null || result == null || beforeStates == null
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
        List<BlockPos> changedPositions = changedBlockPositions(beforeStates, afterStates, item.getBlock(),
                context.getClickedPos());
        for (BlockPos pos : changedPositions) {
            BlockState state = afterStates.get(pos);
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (mutationEventId != null) {
                if (result.consumesAction()) {
                    AdminMutationCapture.recordCreativeBlockConfirmedSafely(player, "place", blockId, pos,
                            "fabric_block_item_after_state_diff", mutationEventId);
                } else {
                    AdminMutationCapture.recordCreativeBlockUnresolvedSafely(player, "place", blockId, pos,
                            "fabric_state_changed_without_consumed_result", mutationEventId);
                }
            }
            if (result.consumesAction()) {
                recordBlockPlacement(player.getUUID().toString(), player.getGameProfile().getName(),
                        level.dimension().location().toString(), pos, blockId);
            }
        }
        if (changedPositions.isEmpty() && mutationEventId != null) {
            String intendedBlockId = BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString();
            AdminMutationCapture.recordCreativeBlockNoChangeSafely(player, "place", intendedBlockId,
                    context.getClickedPos(), "fabric_block_item_return_no_state_change", mutationEventId);
        }
    }

    public static void onBlockItemPlacedSafely(BlockPlaceContext context, BlockItem item,
                                               InteractionResult result,
                                               Map<BlockPos, BlockState> beforeStates,
                                               String mutationEventId) {
        try {
            onBlockItemPlaced(context, item, result, beforeStates, mutationEventId);
        } catch (Throwable failure) {
            AdminMutationCapture.recordCreativeBlockCaptureFailureSafely("fabric_placement_result", failure);
        }
    }

    /**
     * Returns every before/after cell changed to the placed block. This pure
     * helper keeps multi-cell placement coverage deterministic and testable.
     */
    static List<BlockPos> changedBlockPositions(Map<BlockPos, BlockState> beforeStates,
                                                 Map<BlockPos, BlockState> afterStates,
                                                 Block placedBlock) {
        return changedBlockPositions(beforeStates, afterStates, placedBlock, null);
    }

    static List<BlockPos> changedBlockPositions(Map<BlockPos, BlockState> beforeStates,
                                                 Map<BlockPos, BlockState> afterStates,
                                                 Block placedBlock, BlockPos clickedPos) {
        if (beforeStates == null || afterStates == null || placedBlock == null) {
            return List.of();
        }
        List<BlockPos> changed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : afterStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState after = entry.getValue();
            BlockState before = beforeStates.get(pos);
            if (AdminMutationCapture.isCreativePlacedBlockChange(before, after, placedBlock,
                    pos.equals(clickedPos))) {
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

    public static void recordBucketPickup(ServerPlayer player, net.minecraft.world.level.Level level,
                                          BlockPos pos, net.minecraft.world.level.block.state.BlockState sourceState,
                                          net.minecraft.world.item.ItemStack result) {
        if (player == null || level == null || level.isClientSide || pos == null
                || sourceState == null || sourceState.getFluidState().isEmpty()
                || result == null || result.isEmpty()
                || !(result.getItem() instanceof net.minecraft.world.item.BucketItem filledBucket)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        var content = ((BucketItemAccessor) filledBucket).itemgraph$getContent();
        if (content.defaultFluidState().isEmpty()) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState fluidBlock =
                content.defaultFluidState().createLegacyBlock();
        if (fluidBlock.isAir()) {
            return;
        }
        java.util.List<net.minecraft.core.BlockPos> targets = BlockInspectionTargets
                .resolveBlockPositions(serverLevel, pos, fluidBlock);
        java.util.List<AuditEventQueryService.ExactPosition> supersessionPositions = targets.stream()
                .map(target -> new AuditEventQueryService.ExactPosition(
                        target.getX(), target.getY(), target.getZ()))
                .toList();
        submit("BREAK_BLOCK", player, serverLevel, pos,
                BuiltInRegistries.BLOCK.getKey(fluidBlock.getBlock()).toString(), null,
                supersessionPositions);
    }

    private static void onChat(PlayerChatMessage message, ServerPlayer player, ChatType.Bound boundType) {
        recordChatMessage(player, message.signedContent());
    }

    /** Records an accepted server chat payload through the same path used by Fabric's native callback. */
    public static void recordChatMessage(ServerPlayer player, String message) {
        if (player != null && !player.level().isClientSide()) {
            submit("CHAT_MESSAGE", player, player.level(), player.blockPosition(), null, bounded(message));
        }
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
        String linkedBreakEventId = eventType.equals("BREAK_BLOCK") && level instanceof ServerLevel serverLevel
                ? ContainerBreakCapture.activeBreakEventId(player, serverLevel, pos) : null;
        InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                        System.currentTimeMillis(), eventType,
                        player.getUUID().toString(), player.getGameProfile().getName(),
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(), subjectId, detail, null,
                        null, linkedBreakEventId, supersessionPositions));
    }

    private static String bounded(String value) {
        if (value == null || value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH);
    }
}
