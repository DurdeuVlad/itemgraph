package com.itemgraph.audit;

import com.google.gson.JsonObject;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.query.AuditEventQueryService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Captures confirmed administrative and creative inventory mutations at their mutation boundary. */
public final class AdminMutationCapture {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(AdminMutationCapture.class);
    private static final int MAX_TARGETS = 128;
    private static final int MAX_RELATED_ITEM_EVENT_IDS = 32;
    private static final AtomicLong UNPERSISTED_OUTCOME_DIAGNOSTICS = new AtomicLong();
    private static final AtomicLong CAPTURE_FAILURE_DIAGNOSTICS = new AtomicLong();
    private static final ThreadLocal<Deque<MutationScope>> SCOPES = new ThreadLocal<>();
    private static final ThreadLocal<Deque<GiveDropInvocation>> GIVE_DROP_INVOCATIONS = new ThreadLocal<>();
    private static final ThreadLocal<PendingCommand> PENDING_COMMAND = new ThreadLocal<>();
    private static final ThreadLocal<Endpoint> PENDING_ITEM_COPY_SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Deque<PendingCreativeBlockBreak>> CREATIVE_BLOCK_BREAKS = new ThreadLocal<>();

    private AdminMutationCapture() { }

    public record StackState(CanonicalItem item, int count) {
        public StackState {
            Objects.requireNonNull(item, "item");
            if (count <= 0) throw new IllegalArgumentException("stack count must be positive");
        }
    }

    public record SlotState(String slot, StackState stack) { }

    private record AcceptedDrop(ItemEntity entity, String targetPlayerUuid) { }
    public record DeferredGiveDrop(ServerPlayer player, ItemEntity entity, ItemStack stack,
                                   boolean accepted, boolean canceled) { }

    private record Endpoint(String kind, String level, double x, double y, double z,
                            ServerPlayer player, Entity entity, Container container,
                            Map<String, StackState> slots) { }

    private record PendingCommand(String root, String actorUuid, String actorName, String actorKind,
                                  String actorEntityUuid,
                                  Boolean actorPermission, String attemptEventId, String mutationEventId,
                                  boolean nestedExecute, boolean attemptPersisted) { }

    private record PendingCreativeBlockBreak(ServerPlayer player, String subjectId, BlockPos position,
                                             String level, String eventId) { }

    private static final class GiveDropInvocation {
        private final ServerPlayer player;
        private final ItemStack stack;
        private final MutationScope scope;
        private final Set<ItemEntity> acceptedEntities = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<DeferredGiveDrop> deferredDrops = new ArrayList<>();

        private GiveDropInvocation(ServerPlayer player, ItemStack stack, MutationScope scope) {
            this.player = player;
            this.stack = stack;
            this.scope = scope;
        }
    }

    private static final class MutationScope {
        private final String operation;
        private final String sourceKind;
        private final CommandSourceStack source;
        private final ServerPlayer creativePlayer;
        private final String parentEventId;
        private final String commandAttemptEventId;
        private final boolean hasCommandActor;
        private final String commandActorUuid;
        private final String commandActorName;
        private final String commandActorKind;
        private final String commandActorEntityUuid;
        private final Boolean commandActorPermission;
        private final Endpoint copySource;
        private final List<Endpoint> endpoints;
        private final List<AcceptedDrop> acceptedDrops = new ArrayList<>();
        private final List<JsonObject> giveOutputRejectionDetails = new ArrayList<>();
        private final List<String> relatedObservationEventIds = new ArrayList<>();
        private final List<String> relatedTransformationEventIds = new ArrayList<>();
        private final List<String> relatedUnresolvedEventIds = new ArrayList<>();
        private int giveOutputRejectionOmitted;
        private int relatedObservationEventIdsOmitted;
        private int relatedTransformationEventIdsOmitted;
        private int relatedUnresolvedEventIdsOmitted;
        private int rejectedEvidence;
        private String incompleteReason;

        private MutationScope(String operation, String sourceKind, CommandSourceStack source,
                              ServerPlayer creativePlayer, List<Endpoint> endpoints,
                              String parentEventId, String commandAttemptEventId) {
            this(operation, sourceKind, source, creativePlayer, endpoints, parentEventId,
                    commandAttemptEventId, null, PENDING_ITEM_COPY_SOURCE.get());
        }

        private MutationScope(String operation, String sourceKind, CommandSourceStack source,
                              ServerPlayer creativePlayer, List<Endpoint> endpoints,
                              String parentEventId, String commandAttemptEventId,
                              PendingCommand command, Endpoint copySource) {
            this.operation = operation;
            this.sourceKind = sourceKind;
            this.source = source;
            this.creativePlayer = creativePlayer;
            this.endpoints = endpoints;
            this.parentEventId = parentEventId;
            this.commandAttemptEventId = commandAttemptEventId;
            this.hasCommandActor = command != null;
            this.commandActorUuid = command == null ? null : command.actorUuid();
            this.commandActorName = command == null ? null : command.actorName();
            this.commandActorKind = command == null ? null : command.actorKind();
            this.commandActorEntityUuid = command == null ? null : command.actorEntityUuid();
            this.commandActorPermission = command == null ? null : command.actorPermission();
            this.copySource = copySource;
        }
    }

    public static void beginPlayerCommand(CommandSourceStack source, String operation,
                                          Collection<ServerPlayer> targets) {
        PendingCommand pending = claimPendingCommand(source, operation);
        List<Endpoint> endpoints = new ArrayList<>();
        String captureFailure = null;
        try {
            if (targets != null && targets.size() <= MAX_TARGETS) {
                for (ServerPlayer target : targets) {
                    if (target == null) continue;
                    if (!target.level().isClientSide()) {
                        endpoints.add(snapshotPlayer(target));
                    }
                }
            }
        } catch (RuntimeException failure) {
            endpoints.clear();
            if (captureFailure == null) {
                captureFailure = "snapshot_failed:" + failure.getClass().getSimpleName();
            }
        }
        MutationScope scope = commandScope(operation, source, endpoints, pending);
        if (pending != null && !pending.attemptPersisted()) {
            scope.incompleteReason = "command_attempt_queue_rejected";
        }
        push(scope);
        if (captureFailure != null) {
            scope.incompleteReason = captureFailure;
        } else if (targets != null && targets.size() > MAX_TARGETS) {
            scope.incompleteReason = "target_limit:" + targets.size();
        }
    }

    private static MutationScope commandScope(String operation, CommandSourceStack source,
                                              List<Endpoint> endpoints, PendingCommand pending) {
        MutationScope scope = new MutationScope(operation, "admin_command", source, null, endpoints,
                pending == null ? UUID.randomUUID().toString() : pending.mutationEventId(),
                pending == null ? null : pending.attemptEventId(), pending, PENDING_ITEM_COPY_SOURCE.get());
        if (pending != null && !pending.attemptPersisted()) {
            scope.incompleteReason = "command_attempt_queue_rejected";
        }
        return scope;
    }

    private static PendingCommand claimPendingCommand(CommandSourceStack source, String operation) {
        PendingCommand pending = PENDING_COMMAND.get();
        if (pending == null) return null;
        if (!pending.root().equals(commandRoot(operation))
                || (!pending.nestedExecute() && !Objects.equals(pending.actorUuid(), actorUuid(source)))) {
            PENDING_COMMAND.remove();
            return null;
        }
        if (!pending.nestedExecute()) PENDING_COMMAND.remove();
        return pending;
    }

    public static void beginBlockSlotCommand(CommandSourceStack source, String operation,
                                             BlockPos position, int slot) {
        PendingCommand pending = claimPendingCommand(source, operation);
        List<Endpoint> endpoints = List.of();
        String captureFailure = null;
        try {
            ServerLevel level = source.getLevel();
            Container container = level.getBlockEntity(position) instanceof Container found ? found : null;
            if (container != null) {
                endpoints = List.of(snapshotContainer(container, level.dimension().location().toString(),
                        position, Map.of(slot, "slot:" + slot)));
            }
        } catch (RuntimeException failure) {
            captureFailure = "snapshot_failed:" + failure.getClass().getSimpleName();
        }
        MutationScope scope = commandScope(operation, source, endpoints, pending);
        push(scope);
        if (captureFailure != null) scope.incompleteReason = captureFailure;
    }

    public static void beginEntitySlotCommand(CommandSourceStack source, String operation,
                                              Collection<? extends Entity> targets, int slot) {
        PendingCommand pending = claimPendingCommand(source, operation);
        List<Endpoint> endpoints = new ArrayList<>();
        String captureFailure = null;
        try {
            if (targets != null && targets.size() <= MAX_TARGETS) {
                for (Entity target : targets) {
                    if (target == null || target.level().isClientSide()) continue;
                    ItemStack value = target.getSlot(slot).get();
                    String level = target.level().dimension().location().toString();
                    if (target instanceof ServerPlayer player) {
                        endpoints.add(snapshotEntitySlot("player_slot", level, player.getX(), player.getY(), player.getZ(),
                                player, target, slotMap("slot:" + slot, state(value))));
                    } else {
                        endpoints.add(snapshotEntitySlot("entity", level, target.getX(), target.getY(), target.getZ(),
                                null, target, slotMap("slot:" + slot, state(value))));
                    }
                }
            }
        } catch (RuntimeException failure) {
            endpoints.clear();
            captureFailure = "snapshot_failed:" + failure.getClass().getSimpleName();
        }
        MutationScope scope = commandScope(operation, source, endpoints, pending);
        if (pending != null && !pending.attemptPersisted()) {
            scope.incompleteReason = "command_attempt_queue_rejected";
        }
        push(scope);
        if (captureFailure != null) {
            scope.incompleteReason = captureFailure;
        } else if (targets != null && targets.size() > MAX_TARGETS) {
            scope.incompleteReason = "target_limit:" + targets.size();
        }
    }

    public static void beginCreativeSlot(ServerPlayer player, int slot) {
        Map<String, StackState> before = new LinkedHashMap<>();
        if (player != null && slot >= 0 && slot < player.inventoryMenu.slots.size()) {
            before.put("slot:" + slot, state(player.inventoryMenu.getSlot(slot).getItem()));
        }
        String level = player == null ? null : player.level().dimension().location().toString();
        List<Endpoint> endpoints = player == null ? List.of() : List.of(snapshotEntitySlot(
                "creative_slot", level, player.getX(), player.getY(), player.getZ(), player, player, before));
        MutationScope scope = new MutationScope("creative_slot", "creative", null, player, endpoints,
                UUID.randomUUID().toString(), null);
        push(scope);
        if (!recordCreativeOutcome(scope, "CREATIVE_SLOT_ATTEMPT", "slot=" + slot + " outcome=attempt")) {
            scope.incompleteReason = "creative_attempt_queue_rejected";
        }
    }

    public static void beginCreativeSlotSafely(ServerPlayer player, int slot) {
        beginSafely("creative_slot", () -> beginCreativeSlot(player, slot));
    }

    public static void beginGive(CommandSourceStack source, Collection<ServerPlayer> targets) {
        beginPlayerCommand(source, "give", targets);
    }

    public static void beginGiveSafely(CommandSourceStack source, Collection<ServerPlayer> targets) {
        beginSafely("give", () -> beginGive(source, targets));
    }

    public static void beginClear(CommandSourceStack source, Collection<ServerPlayer> targets) {
        beginPlayerCommand(source, "clear", targets);
    }

    public static void beginClearSafely(CommandSourceStack source, Collection<ServerPlayer> targets) {
        beginSafely("clear", () -> beginClear(source, targets));
    }

    public static void beginBlockItemCommand(CommandSourceStack source, String operation,
                                             BlockPos position, int slot) {
        beginBlockSlotCommand(source, operation, position, slot);
    }

    public static void beginBlockItemCommandSafely(CommandSourceStack source, String operation,
                                                   BlockPos position, int slot) {
        beginSafely(operation, () -> beginBlockItemCommand(source, operation, position, slot));
    }

    public static void beginEntityItemCommand(CommandSourceStack source, String operation,
                                              Collection<? extends Entity> targets, int slot) {
        beginEntitySlotCommand(source, operation, targets, slot);
    }

    public static void beginEntityItemCommandSafely(CommandSourceStack source, String operation,
                                                    Collection<? extends Entity> targets, int slot) {
        beginSafely(operation, () -> beginEntityItemCommand(source, operation, targets, slot));
    }

    /** Retains the authoritative source slot for vanilla `/item ... from block` copies until the target mutator runs. */
    public static void recordBlockCopySourceSafely(CommandSourceStack source, BlockPos position, int slot) {
        try {
            PendingCommand pending = PENDING_COMMAND.get();
            if (pending == null || !"item".equals(pending.root()) || source == null || position == null) return;
            ServerLevel level = source.getLevel();
            Container container = level.getBlockEntity(position) instanceof Container found ? found : null;
            if (container == null) return;
            PENDING_ITEM_COPY_SOURCE.set(snapshotContainer(container, level.dimension().location().toString(),
                    position, Map.of(slot, "slot:" + slot)));
        } catch (Throwable failure) {
            recordCaptureFailure("source_snapshot", "item", failure);
        }
    }

    /** Retains the authoritative source slot for vanilla `/item ... from entity` copies until the target mutator runs. */
    public static void recordEntityCopySourceSafely(Entity entity, int slot) {
        try {
            PendingCommand pending = PENDING_COMMAND.get();
            if (pending == null || !"item".equals(pending.root()) || entity == null
                    || entity.level().isClientSide()) return;
            ItemStack value = entity.getSlot(slot).get();
            String level = entity.level().dimension().location().toString();
            ServerPlayer player = entity instanceof ServerPlayer serverPlayer ? serverPlayer : null;
            PENDING_ITEM_COPY_SOURCE.set(snapshotEntitySlot(player == null ? "entity" : "player_slot", level,
                    entity.getX(), entity.getY(), entity.getZ(), player, entity,
                    slotMap("slot:" + slot, state(value))));
        } catch (Throwable failure) {
            recordCaptureFailure("source_snapshot", "item", failure);
        }
    }

    public static boolean isGiveInProgress() {
        Deque<MutationScope> scopes = SCOPES.get();
        return scopes != null && scopes.stream().anyMatch(scope -> "give".equals(scope.operation));
    }

    /** Records the authoritative addFreshEntity result for the active Give drop invocation. */
    public static void recordGiveDropEntityAdmissionSafely(ItemEntity entity, boolean accepted) {
        try {
            if (entity == null) return;
            Deque<GiveDropInvocation> invocations = GIVE_DROP_INVOCATIONS.get();
            if (accepted && invocations != null && !invocations.isEmpty()) {
                invocations.peek().acceptedEntities.add(entity);
            }
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath fatal) throw fatal;
            recordCaptureFailure("give_drop_acceptance", "give", failure);
        }
    }

    /** Returns true only when addFreshEntity accepted the exact entity returned by this Give call. */
    public static boolean wasGiveDropAcceptedSafely(ServerPlayer player, ItemStack stack, ItemEntity entity) {
        if (player == null || stack == null || entity == null) return false;
        Deque<GiveDropInvocation> invocations = GIVE_DROP_INVOCATIONS.get();
        if (invocations == null || invocations.isEmpty()) return false;
        GiveDropInvocation invocation = invocations.peek();
        return invocation.player == player && invocation.stack == stack
                && invocation.acceptedEntities.contains(entity);
    }

    /** Defers a same-player drop until the exact GiveCommand return entity is known. */
    public static boolean deferGiveDropSafely(ServerPlayer player, ItemEntity entity, ItemStack stack) {
        return deferGiveDropSafely(player, entity, stack, false);
    }

    /** Defers a same-player drop and preserves cancellation while the Give result is unresolved. */
    public static boolean deferGiveDropSafely(ServerPlayer player, ItemEntity entity, ItemStack stack,
                                              boolean canceled) {
        try {
            if (player == null || entity == null || stack == null || stack.isEmpty()) return false;
            Deque<GiveDropInvocation> invocations = GIVE_DROP_INVOCATIONS.get();
            if (invocations == null || invocations.isEmpty()) return false;
            GiveDropInvocation invocation = invocations.peek();
            if (invocation.player != player) return false;
            invocation.deferredDrops.add(new DeferredGiveDrop(player, entity, stack.copy(), false, canceled));
            return true;
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath fatal) throw fatal;
            recordCaptureFailure("give_drop_defer", "give", failure);
            return false;
        }
    }

    /** Returns whether a creative packet for this exact player may produce a drop. */
    public static boolean isCreativeSlotDropInProgress(ServerPlayer player) {
        if (player == null) return false;
        Deque<MutationScope> scopes = SCOPES.get();
        if (scopes == null) return false;
        return scopes.stream().anyMatch(scope -> "creative_slot".equals(scope.operation)
                && scope.creativePlayer == player);
    }

    /** Marks the exact GiveCommand call-site drop before the loader enters Player.drop. */
    public static boolean beginGiveDropSafely(ServerPlayer player, ItemStack stack) {
        try {
            if (player == null || stack == null || stack.isEmpty()) return false;
            Deque<MutationScope> scopes = SCOPES.get();
            if (scopes == null) return false;
            MutationScope scope = scopes.stream()
                    .filter(candidate -> "give".equals(candidate.operation))
                    .findFirst().orElse(null);
            if (scope == null) return false;
            Deque<GiveDropInvocation> invocations = GIVE_DROP_INVOCATIONS.get();
            if (invocations == null) {
                invocations = new ArrayDeque<>();
                GIVE_DROP_INVOCATIONS.set(invocations);
            }
            invocations.push(new GiveDropInvocation(player, stack, scope));
            return true;
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath fatal) throw fatal;
            recordCaptureFailure("give_drop_begin", "give", failure);
            return false;
        }
    }

    /** Stores only the entity returned by GiveCommand's exact drop invocation. */
    public static List<DeferredGiveDrop> finishGiveDropSafely(boolean began, ServerPlayer player, ItemStack stack,
                                            ItemEntity itemEntity, boolean acceptedByWorld) {
        try {
            if (!began) return List.of();
            Deque<GiveDropInvocation> invocations = GIVE_DROP_INVOCATIONS.get();
            if (invocations == null || invocations.isEmpty()) return List.of();
            GiveDropInvocation invocation = invocations.peek();
            if (invocation.player != player || invocation.stack != stack) return List.of();
            invocations.pop();
            if (invocations.isEmpty()) GIVE_DROP_INVOCATIONS.remove();
            if (itemEntity != null && acceptedByWorld) {
                invocation.scope.acceptedDrops.add(new AcceptedDrop(itemEntity, player.getUUID().toString()));
            } else if (itemEntity != null) {
                boolean canceled = invocation.deferredDrops.stream()
                        .anyMatch(deferred -> deferred.entity() == itemEntity && deferred.canceled());
                recordGiveOutputRejection(invocation.scope, player, stack, itemEntity,
                        canceled ? "item_toss_event_canceled"
                                : "server_level_add_fresh_entity_returned_false");
            }
            List<DeferredGiveDrop> replay = new ArrayList<>();
            for (DeferredGiveDrop deferred : invocation.deferredDrops) {
                if (deferred.entity() != itemEntity) {
                    replay.add(new DeferredGiveDrop(deferred.player(), deferred.entity(), deferred.stack(),
                            invocation.acceptedEntities.contains(deferred.entity()), deferred.canceled()));
                }
            }
            return List.copyOf(replay);
        } catch (Throwable failure) {
            if (failure instanceof ThreadDeath fatal) throw fatal;
            recordCaptureFailure("give_drop_finish", "give", failure);
            return List.of();
        }
    }

    private static void recordGiveOutputRejection(MutationScope scope, ServerPlayer player,
                                                   ItemStack stack, ItemEntity entity, String reason) {
        CanonicalItem rejectedItem = ItemCanonicalizer.canonicalizeStack(stack.copy());
        JsonObject rejection = new JsonObject();
        rejection.addProperty("outcome", "rejected_ground_output");
        rejection.addProperty("reason", reason);
        rejection.addProperty("item_id", rejectedItem.itemId());
        rejection.addProperty("quantity", stack.getCount());
        rejection.addProperty("fingerprint", rejectedItem.fingerprintHash());
        rejection.addProperty("item_entity_uuid", entity.getUUID().toString());
        rejection.addProperty("target_player_uuid", player.getUUID().toString());
        if (scope.giveOutputRejectionDetails.size() < MAX_TARGETS) {
            scope.giveOutputRejectionDetails.add(rejection);
        } else {
            scope.giveOutputRejectionOmitted++;
        }
        if (scope.incompleteReason == null) {
            scope.incompleteReason = "give_overflow_output_rejected";
        }
    }

    /** Captures a creative slot packet's negative-slot item drop after world insertion. */
    public static boolean captureCreativeDrop(ServerPlayer player, ItemEntity itemEntity,
                                              boolean acceptedByWorld) {
        if (player == null || itemEntity == null || !acceptedByWorld) return false;
        Deque<MutationScope> scopes = SCOPES.get();
        if (scopes == null) return false;
        for (MutationScope scope : scopes) {
            if (!"creative_slot".equals(scope.operation) || scope.creativePlayer != player) continue;
            scope.acceptedDrops.add(new AcceptedDrop(itemEntity, player.getUUID().toString()));
            return true;
        }
        return false;
    }

    public static void finishCurrent(boolean completed, int result) {
        MutationScope scope = pop();
        if (scope == null) return;
        try {
            finish(scope, completed, result);
        } finally {
            PENDING_ITEM_COPY_SOURCE.remove();
        }
    }

    /** Capture completion must never change a vanilla command or packet result. */
    public static void finishCurrentSafely(String operation, boolean completed, int result) {
        if (!isCurrentOperation(operation)) return;
        try {
            finishCurrent(completed, result);
        } catch (Throwable failure) {
            recordCaptureFailure("finish", operation, failure);
        }
    }

    public static void failCurrent(String reason) {
        MutationScope scope = pop();
        if (scope == null) return;
        try {
            finish(scope, false, 0, reason);
        } finally {
            PENDING_ITEM_COPY_SOURCE.remove();
        }
    }

    /** Preserve the original vanilla exception if recording its failure also fails. */
    public static void failCurrentSafely(String operation, String reason) {
        if (!isCurrentOperation(operation)) return;
        try {
            failCurrent(reason);
        } catch (Throwable failure) {
            recordCaptureFailure("failure", operation, failure);
        }
    }

    private static void beginSafely(String operation, Runnable begin) {
        try {
            begin.run();
        } catch (Throwable failure) {
            Deque<MutationScope> scopes = SCOPES.get();
            if (scopes != null && !scopes.isEmpty() && scopes.peek().operation.equals(operation)) {
                scopes.peek().incompleteReason = "capture_start_failed";
            }
            recordCaptureFailure("start", operation, failure);
        }
    }

    private static boolean isCurrentOperation(String operation) {
        Deque<MutationScope> scopes = SCOPES.get();
        return scopes != null && !scopes.isEmpty() && scopes.peek().operation.equals(operation);
    }

    private static void recordCaptureFailure(String stage, String operation, Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) throw fatal;
        if (failure instanceof ThreadDeath fatal) throw fatal;
        long count = CAPTURE_FAILURE_DIAGNOSTICS.incrementAndGet();
        if (count == 1 || count % 1000 == 0) {
            LOGGER.warn("ItemGraph could not capture {} {} boundary ({} capture failures)",
                    operation, stage, count, failure);
        }
    }

    /** Called from both loader command-dispatch wrappers so nested /execute context cannot leak to a later command. */
    public static void finishCommandDispatch() {
        PENDING_COMMAND.remove();
        PENDING_ITEM_COPY_SOURCE.remove();
    }

    /** Records a typed attempt without retaining selector expressions or raw command arguments. */
    public static void recordCommandAttempt(ParseResultsAdapter attempt) {
        if (attempt == null) return;
        PENDING_COMMAND.remove();
        String mutationRoot = itemMutationRoot(attempt.command(), attempt.parsedNodeNames());
        if (attempt.source() == null || mutationRoot == null) return;
        boolean nestedExecute = !isItemCommand(attempt.command());
        String outcome = Boolean.FALSE.equals(attempt.permissionGranted()) ? "permission_denied"
                : attempt.cancelled() ? "cancelled"
                : attempt.parseFailed() ? "parse_failed" : "attempt";
        String eventId = UUID.randomUUID().toString();
        String attemptEventId = eventId;
        String mutationEventId = UUID.randomUUID().toString();
        boolean attemptPersisted = recordEvent(attempt.source(), "ADMIN_ITEM_COMMAND_ATTEMPT", mutationRoot,
                "outcome=" + outcome + " permission="
                        + (attempt.permissionGranted() == null ? "unknown" : attempt.permissionGranted()),
                eventId, mutationEventId, null, null);
        if (!attemptPersisted) {
            reportUnpersistedOutcome("ADMIN_ITEM_COMMAND_ATTEMPT", eventId);
        }
        if (!Boolean.FALSE.equals(attempt.permissionGranted())
                && !attempt.cancelled() && !attempt.parseFailed()) {
            PENDING_COMMAND.set(new PendingCommand(mutationRoot, actorUuid(attempt.source()),
                    actorName(attempt.source()), actorKind(attempt.source()), entityUuid(attempt.source()),
                    attempt.permissionGranted(),
                    eventId, mutationEventId, nestedExecute, attemptPersisted));
        }
        if (Boolean.FALSE.equals(attempt.permissionGranted()) || attempt.cancelled() || attempt.parseFailed()) {
            eventId = UUID.randomUUID().toString();
            boolean failurePersisted = attemptPersisted && recordEvent(
                    attempt.source(), "ADMIN_ITEM_COMMAND_FAILURE", mutationRoot,
                    "outcome=" + outcome + " permission="
                            + (attempt.permissionGranted() == null ? "unknown" : attempt.permissionGranted()),
                    eventId, mutationEventId, mutationEventId, attemptEventId);
            if (!failurePersisted) {
                reportUnpersistedOutcome("ADMIN_ITEM_COMMAND_FAILURE", eventId);
            }
        }
    }

    public static void recordCommandAttemptSafely(ParseResultsAdapter attempt) {
        try {
            recordCommandAttempt(attempt);
        } catch (Throwable failure) {
            recordCaptureFailure("attempt", "command", failure);
        }
    }

    /** Small adapter keeps Brigadier's implementation details out of this capture API. */
    public record ParseResultsAdapter(CommandSourceStack source, String command,
                                      Boolean permissionGranted, boolean cancelled, boolean parseFailed,
                                      List<String> parsedNodeNames) {
        public ParseResultsAdapter {
            parsedNodeNames = List.copyOf(parsedNodeNames == null ? List.of() : parsedNodeNames);
        }
    }

    public static String recordCreativeBlockAttempt(ServerPlayer player, String action, String subjectId,
                                                      BlockPos position, boolean callbackReturned) {
        if (player == null || position == null || !player.getAbilities().instabuild) return null;
        String attemptEventId = UUID.randomUUID().toString();
        boolean accepted = recordCreativeBlockOutcome(player, action, subjectId, position,
                callbackReturned ? "callback_observed" : "attempt", "game_callback",
                player.level().dimension().location().toString(), attemptEventId, attemptEventId);
        return accepted ? attemptEventId : null;
    }

    public static String recordCreativeBlockAttemptSafely(ServerPlayer player, String action, String subjectId,
                                                            BlockPos position, boolean callbackReturned) {
        try {
            return recordCreativeBlockAttempt(player, action, subjectId, position, callbackReturned);
        } catch (Throwable failure) {
            recordCaptureFailure("attempt", "creative_block", failure);
            return null;
        }
    }

    public static void recordCreativeBlockConfirmed(ServerPlayer player, String action, String subjectId,
                                                    BlockPos position, String causeStatus) {
        if (player == null || position == null || !player.getAbilities().instabuild) return;
        recordCreativeBlockOutcome(player, action, subjectId, position, "confirmed_success", causeStatus);
    }

    public static void recordCreativeBlockConfirmedSafely(ServerPlayer player, String action, String subjectId,
                                                           BlockPos position, String causeStatus,
                                                           String mutationEventId) {
        try {
            if (player == null || position == null || !player.getAbilities().instabuild) return;
            recordCreativeBlockOutcome(player, action, subjectId, position, "confirmed_success", causeStatus,
                    player.level().dimension().location().toString(), UUID.randomUUID().toString(),
                    mutationEventId);
        } catch (Throwable failure) {
            recordCaptureFailure("result", "creative_block", failure);
        }
    }

    public static void recordCreativeBlockNoChangeSafely(ServerPlayer player, String action, String subjectId,
                                                          BlockPos position, String causeStatus,
                                                          String mutationEventId) {
        try {
            if (player == null || position == null || !player.getAbilities().instabuild) return;
            recordCreativeBlockOutcome(player, action, subjectId, position, "confirmed_no_change", causeStatus,
                    player.level().dimension().location().toString(), UUID.randomUUID().toString(),
                    mutationEventId);
        } catch (Throwable failure) {
            recordCaptureFailure("result", "creative_block", failure);
        }
    }

    public static void recordCreativeBlockConfirmedSafely(ServerPlayer player, String action, String subjectId,
                                                           BlockPos position, String causeStatus) {
        try {
            recordCreativeBlockConfirmed(player, action, subjectId, position, causeStatus);
        } catch (Throwable failure) {
            recordCaptureFailure("result", "creative_block", failure);
        }
    }

    public static void recordCreativeBlockUnresolvedSafely(ServerPlayer player, String action, String subjectId,
                                                            BlockPos position, String causeStatus) {
        try {
            if (player == null || position == null || !player.getAbilities().instabuild) return;
            recordCreativeBlockOutcome(player, action, subjectId, position,
                    "exception_after_callback", causeStatus);
        } catch (Throwable failure) {
            recordCaptureFailure("unresolved", "creative_block", failure);
        }
    }

    public static void recordCreativeBlockUnresolvedSafely(ServerPlayer player, String action, String subjectId,
                                                            BlockPos position, String causeStatus,
                                                            String mutationEventId) {
        try {
            if (player == null || position == null || !player.getAbilities().instabuild) return;
            recordCreativeBlockOutcome(player, action, subjectId, position,
                    "exception_after_callback", causeStatus,
                    player.level().dimension().location().toString(), UUID.randomUUID().toString(),
                    mutationEventId);
        } catch (Throwable failure) {
            recordCaptureFailure("unresolved", "creative_block", failure);
        }
    }

    /** Shared loader predicate for a changed block cell produced by a completed placement. */
    public static boolean isCreativePlacedBlockChange(BlockState before, BlockState after, Block placedBlock) {
        return isCreativePlacedBlockChange(before, after, placedBlock, false);
    }

    /**
     * Same-type state changes count only at the clicked placement position. This captures slab merges
     * without treating nearby same-type neighbor updates as additional creative placements.
     */
    public static boolean isCreativePlacedBlockChange(BlockState before, BlockState after, Block placedBlock,
                                                        boolean clickedPosition) {
        return before != null && after != null && placedBlock != null
                && after.getBlock() == placedBlock && !after.equals(before)
                && (before.getBlock() != placedBlock || clickedPosition);
    }

    public static void recordCreativeBlockCaptureFailureSafely(String stage, Throwable failure) {
        try {
            recordCaptureFailure(stage, "creative_block", failure);
        } catch (Throwable diagnosticFailure) {
            if (diagnosticFailure instanceof VirtualMachineError fatal) throw fatal;
            if (diagnosticFailure instanceof ThreadDeath fatal) throw fatal;
        }
    }

    /** NeoForge exposes block break before mutation; retain it until the vanilla return value is known. */
    public static void beginCreativeBlockBreak(ServerPlayer player, String subjectId, BlockPos position) {
        if (player == null || position == null || !player.getAbilities().instabuild) return;
        Deque<PendingCreativeBlockBreak> breaks = CREATIVE_BLOCK_BREAKS.get();
        if (breaks == null) {
            breaks = new ArrayDeque<>();
            CREATIVE_BLOCK_BREAKS.set(breaks);
        }
        String attemptEventId = recordCreativeBlockAttempt(player, "break", subjectId, position, false);
        if (attemptEventId == null) {
            if (breaks.isEmpty()) CREATIVE_BLOCK_BREAKS.remove();
            return;
        }
        breaks.push(new PendingCreativeBlockBreak(player, bounded(subjectId), position.immutable(),
                player.level().dimension().location().toString(), attemptEventId));
    }

    public static void beginCreativeBlockBreakSafely(ServerPlayer player, String subjectId, BlockPos position) {
        try {
            beginCreativeBlockBreak(player, subjectId, position);
        } catch (Throwable failure) {
            recordCaptureFailure("start", "creative_block_break", failure);
        }
    }

    public static void finishCreativeBlockBreak(ServerPlayer player, BlockPos position,
                                               boolean completed, boolean broken) {
        Deque<PendingCreativeBlockBreak> breaks = CREATIVE_BLOCK_BREAKS.get();
        if (breaks == null || breaks.isEmpty()) return;
        PendingCreativeBlockBreak pending = breaks.peek();
        if (pending.player() != player || !pending.position().equals(position)) return;
        breaks.pop();
        if (breaks.isEmpty()) CREATIVE_BLOCK_BREAKS.remove();
        String outcome = !completed ? "exception_after_callback"
                : broken ? "confirmed_success" : "confirmed_no_change";
        String causeStatus = completed ? "server_player_game_mode_result"
                : "server_player_game_mode_exception";
        recordCreativeBlockOutcome(player, "break", pending.subjectId(), position,
                outcome, causeStatus, pending.level(), UUID.randomUUID().toString(), pending.eventId());
    }

    public static void finishCreativeBlockBreakSafely(ServerPlayer player, BlockPos position,
                                                      boolean completed, boolean broken) {
        try {
            finishCreativeBlockBreak(player, position, completed, broken);
        } catch (Throwable failure) {
            recordCaptureFailure("finish", "creative_block_break", failure);
        }
    }

    private static boolean recordCreativeBlockOutcome(ServerPlayer player, String action, String subjectId,
                                                    BlockPos position, String outcome, String causeStatus) {
        return recordCreativeBlockOutcome(player, action, subjectId, position, outcome, causeStatus,
                player.level().dimension().location().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }

    private static boolean recordCreativeBlockOutcome(ServerPlayer player, String action, String subjectId,
                                                    BlockPos position, String outcome, String causeStatus,
                                                    String level, String eventId, String mutationEventId) {
        String eventType = switch (outcome) {
            case "confirmed_success", "confirmed_no_change" -> "CREATIVE_BLOCK_RESULT";
            case "exception_after_callback" -> "CREATIVE_BLOCK_UNRESOLVED";
            default -> "CREATIVE_BLOCK_ATTEMPT";
        };
        JsonObject raw = new JsonObject();
        raw.addProperty("event_id", eventId);
        raw.addProperty("mutation_event_id", mutationEventId);
        if (!"CREATIVE_BLOCK_ATTEMPT".equals(eventType)) raw.addProperty("attempt_event_id", mutationEventId);
        raw.addProperty("evidence", eventType.toLowerCase(java.util.Locale.ROOT));
        raw.addProperty("action", bounded(action));
        raw.addProperty("outcome", outcome);
        raw.addProperty("cause_status", causeStatus);
        // Creative placement does not consume the held stack; creative break does not drop it.
        // Record the exact player-inventory delta without manufacturing item flow.
        raw.addProperty("player_inventory_quantity_delta", 0);
        raw.addProperty("item_flow_link_status", "NO_ITEM_EVIDENCE_RECORDED");
        raw.addProperty("staff_private", true);
        boolean accepted = InternalObservationService.getInstance().submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                System.currentTimeMillis(), eventType, player.getUUID().toString(),
                player.getGameProfile().getName(), level,
                position.getX(), position.getY(), position.getZ(), bounded(subjectId),
                "action=" + bounded(action) + " outcome=" + outcome + " cause_status=" + causeStatus,
                raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                InternalObservationService.sourceEventIdForUuid(eventId), eventId, List.of()));
        if (!accepted) reportUnpersistedOutcome(eventType, eventId);
        return accepted;
    }

    private static void finish(MutationScope scope, boolean completed, int result) {
        finish(scope, completed, result, completed ? "method_returned" : "method_failed");
    }

    private static void finish(MutationScope scope, boolean completed, int result, String reason) {
        int observedChanges = 0;
        for (Endpoint endpoint : scope.endpoints) {
            Map<String, StackState> after = current(endpoint);
            observedChanges += recordEndpointDiff(scope, endpoint, after);
        }
        if ("give".equals(scope.operation) || "creative_slot".equals(scope.operation)) {
            for (AcceptedDrop acceptedDrop : scope.acceptedDrops) {
                ItemEntity entity = acceptedDrop.entity();
                ItemStack stack = entity.getItem();
                if (stack == null || stack.isEmpty()) continue;
                if (entity.hasPickUpDelay() && entity.getAge() > 0) {
                    continue; // GiveCommand marks its fake display entity never-pickup and near expiry.
                }
                recordGroundCreation(scope, entity, stack, "creative_slot".equals(scope.operation),
                        acceptedDrop.targetPlayerUuid());
                observedChanges++;
            }
        }
        if ("creative_slot".equals(scope.operation)) {
            if (scope.rejectedEvidence > 0 || scope.incompleteReason != null) {
                recordCreativeOutcome(scope, "CREATIVE_SLOT_ATTEMPT",
                        "outcome=unresolved_evidence_incomplete reason="
                                + (scope.incompleteReason == null ? "effect_queue_rejected" : scope.incompleteReason)
                                + " rejected_evidence=" + scope.rejectedEvidence);
                return;
            }
            if (!completed) {
                recordCreativeOutcome(scope, "CREATIVE_SLOT_ATTEMPT",
                        "outcome=unresolved_packet_handler_exception mutation_count=" + observedChanges);
                return;
            }
            if (observedChanges > 0) {
                recordCreativeOutcome(scope, "CREATIVE_SLOT_EFFECT",
                        "outcome=confirmed mutation_count=" + observedChanges);
            } else {
                recordCreativeOutcome(scope, "CREATIVE_SLOT_ATTEMPT",
                        "outcome=unchanged_or_rejected");
            }
            return;
        }
        if (scope.incompleteReason != null || scope.rejectedEvidence > 0) {
            recordScopedOutcome(scope, "ADMIN_ITEM_COMMAND_UNRESOLVED", commandRoot(scope.operation),
                    "outcome=unresolved reason=" + (scope.incompleteReason == null
                            ? "evidence_queue_rejected:" + scope.rejectedEvidence : scope.incompleteReason)
                            + " result=" + result + " target_count=" + scope.endpoints.size()
                            + " observed_mutations=" + observedChanges);
            return;
        }
        if ((completed && result > 0 && observedChanges == 0) || (!completed && observedChanges > 0)) {
            String unresolvedReason = completed
                    ? "command_reported_effect_without_captured_delta"
                    : "mutation_observed_before_command_exception";
            recordScopedOutcome(scope, "ADMIN_ITEM_COMMAND_UNRESOLVED", commandRoot(scope.operation),
                    "outcome=unresolved reason=" + unresolvedReason + " result=" + result
                            + " target_count=" + scope.endpoints.size()
                            + " observed_mutations=" + observedChanges);
            return;
        }
        String eventType = completed && observedChanges > 0
                ? "ADMIN_ITEM_COMMAND_EFFECT" : "ADMIN_ITEM_COMMAND_FAILURE";
        recordScopedOutcome(scope, eventType, commandRoot(scope.operation),
                "outcome=" + (completed ? "confirmed_effect" : reason)
                        + " result=" + result + " target_count=" + scope.endpoints.size()
                        + " mutation_count=" + observedChanges);
    }

    private static int recordEndpointDiff(MutationScope scope, Endpoint endpoint,
                                          Map<String, StackState> after) {
        int changes = 0;
        java.util.LinkedHashSet<String> slots = new java.util.LinkedHashSet<>(endpoint.slots().keySet());
        slots.addAll(after.keySet());
        for (String slot : slots) {
            StackState beforeState = endpoint.slots().get(slot);
            StackState afterState = after.get(slot);
            if (same(beforeState, afterState)) continue;
            changes++;
            recordSlotChange(scope, endpoint, slot, beforeState, afterState);
        }
        return changes;
    }

    private static void recordSlotChange(MutationScope scope, Endpoint endpoint, String slot,
                                         StackState before, StackState after) {
        String sourceEventId = scope.parentEventId;
        if (endpoint.player() == null && "entity".equals(endpoint.kind())) {
            JsonObject details = eventJson(scope, "confirmed_unresolved_endpoint");
            String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(endpoint.entity().getType()).toString();
            details.addProperty("target_entity_uuid", endpoint.entity().getUUID().toString());
            details.addProperty("target_entity_type", entityType);
            details.addProperty("endpoint_kind", endpoint.kind());
            details.addProperty("slot", slot);
            addStack(details, "before", before);
            addStack(details, "after", after);
            String eventId = UUID.randomUUID().toString();
            details.addProperty("event_id", eventId);
            details.addProperty("item_flow_link_status", "UNRESOLVED_EVIDENCE_RECORDED");
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                    new InternalObservationService.InternalAuditEvent(
                    System.currentTimeMillis(), "ADMIN_ITEM_COMMAND_UNRESOLVED",
                    scopeActorUuid(scope), scopeActorName(scope), endpoint.level(), endpoint.x(), endpoint.y(),
                    endpoint.z(), entityType, "slot=" + slot + " endpoint=unresolved",
                    details.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    InternalObservationService.sourceEventIdForUuid(eventId), eventId, List.of()));
            if (!accepted) {
                scope.rejectedEvidence++;
            } else {
                recordRelatedUnresolved(scope, eventId);
            }
            return;
        }
        if (before != null && after != null && !before.item().fingerprintHash().equals(after.item().fingerprintHash())) {
            // A completed /item modify operation establishes a transformation. A
            // replacement command or creative packet proves only the before/after
            // slot deltas, so retain independent removal and creation evidence.
            int transformed = "item_modify".equals(scope.operation)
                    ? Math.min(before.count(), after.count()) : 0;
            if (transformed > 0 && endpoint.player() != null) {
                UUID event = UUID.randomUUID();
                String transformationDetails = "cause=" + scope.operation + " slot=" + slot
                        + " mutation_event_id=" + scope.parentEventId
                        + " parent_event_id=" + sourceEventId
                        + (scope.commandAttemptEventId == null ? ""
                        : " command_attempt_event_id=" + scope.commandAttemptEventId);
                if (endpoint.player() != null) {
                    transformationDetails += " target_player_uuid=" + endpoint.player().getUUID()
                            + " target_player_name=" + endpoint.player().getGameProfile().getName();
                }
                if (endpoint.entity() != null) {
                    transformationDetails += " target_entity_uuid=" + endpoint.entity().getUUID()
                            + " target_entity_type=" + BuiltInRegistries.ENTITY_TYPE.getKey(endpoint.entity().getType());
                }
                boolean accepted = InternalObservationService.getInstance().submitTransformation(
                        new InternalObservationService.InternalTransformation(System.currentTimeMillis(),
                                "ADMIN_ITEM_TRANSFORM",
                                scopeActorUuid(scope), scopeActorName(scope),
                                endpoint.level(), endpoint.x(), endpoint.y(), endpoint.z(),
                                before.item(), after.item(), transformed,
                                transformationDetails,
                                event.toString()));
                if (!accepted) {
                    scope.rejectedEvidence++;
                } else {
                    recordRelatedTransformation(scope, event.toString());
                }
            } else if (transformed > 0) {
                recordUnresolvedTransformation(scope, endpoint, slot, before, after);
            }
            if (before.count() > transformed) {
                recordQuantity(scope, endpoint, slot, before.item(), before.count() - transformed, false);
            }
            if (after.count() > transformed) {
                recordQuantity(scope, endpoint, slot, after.item(), after.count() - transformed, true);
            }
        } else if (before == null && after != null) {
            recordQuantity(scope, endpoint, slot, after.item(), after.count(), true);
        } else if (before != null && after == null) {
            recordQuantity(scope, endpoint, slot, before.item(), before.count(), false);
        } else if (before != null) {
            int delta = after.count() - before.count();
            if (delta != 0) recordQuantity(scope, endpoint, slot, before.item(), Math.abs(delta), delta > 0);
        }
    }

    private static void recordUnresolvedTransformation(MutationScope scope, Endpoint endpoint, String slot,
                                                       StackState before, StackState after) {
        JsonObject details = eventJson(scope, "confirmed_unresolved_transformation");
        String eventId = UUID.randomUUID().toString();
        details.addProperty("event_id", eventId);
        details.addProperty("item_flow_link_status", "UNRESOLVED_EVIDENCE_RECORDED");
        details.addProperty("endpoint_kind", endpoint.kind());
        details.addProperty("slot", slot);
        if (endpoint.player() != null) {
            details.addProperty("target_player_uuid", endpoint.player().getUUID().toString());
            details.addProperty("target_player_name", endpoint.player().getGameProfile().getName());
        }
        if (endpoint.entity() != null) {
            details.addProperty("target_entity_uuid", endpoint.entity().getUUID().toString());
            details.addProperty("target_entity_type",
                    BuiltInRegistries.ENTITY_TYPE.getKey(endpoint.entity().getType()).toString());
        }
        addStack(details, "before", before);
        addStack(details, "after", after);
        boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                new InternalObservationService.InternalAuditEvent(
                System.currentTimeMillis(), "ADMIN_ITEM_COMMAND_UNRESOLVED", scopeActorUuid(scope),
                scopeActorName(scope), endpoint.level(), endpoint.x(), endpoint.y(), endpoint.z(),
                null, "outcome=confirmed_unresolved_transformation slot=" + slot,
                details.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                InternalObservationService.sourceEventIdForUuid(eventId), eventId, List.of()));
        if (!accepted) {
            scope.rejectedEvidence++;
        } else {
            recordRelatedUnresolved(scope, eventId);
        }
    }

    private static void recordQuantity(MutationScope scope, Endpoint endpoint, String slot,
                                       CanonicalItem item, int amount, boolean created) {
        if (amount <= 0) return;
        UUID eventId = UUID.randomUUID();
        boolean creative = "creative_slot".equals(scope.operation);
        String action = (creative ? "CREATIVE_ITEM_" : "ADMIN_ITEM_") + (created ? "CREATE" : "REMOVE");
        String endpointType = endpoint.player() != null
                ? (creative
                    ? (created ? "CREATIVE_CREATE_PLAYER" : "CREATIVE_REMOVE_PLAYER")
                    : (created ? "ADMIN_CREATE_PLAYER" : "ADMIN_REMOVE_PLAYER"))
                : "container".equals(endpoint.kind())
                ? (created ? "ADMIN_CREATE_CONTAINER" : "ADMIN_REMOVE_CONTAINER")
                : null;
        if (endpointType == null) return;
        JsonObject raw = eventJson(scope, "confirmed_effect");
        raw.addProperty("event_id", eventId.toString());
        raw.addProperty("parent_event_id", scope.parentEventId);
        raw.addProperty("action", action);
        raw.addProperty("quantity", amount);
        raw.addProperty("item_id", item.itemId());
        raw.addProperty("fingerprint", item.fingerprintHash());
        raw.addProperty("slot", slot);
        raw.addProperty("endpoint_kind", endpoint.kind());
        raw.addProperty("actor_uuid", scopeActorUuid(scope));
        raw.addProperty("actor_name", scopeActorName(scope));
        if (endpoint.player() != null) {
            raw.addProperty("target_player_uuid", endpoint.player().getUUID().toString());
            raw.addProperty("target_player_name", endpoint.player().getGameProfile().getName());
        }
        if (endpoint.entity() != null) {
            raw.addProperty("target_entity", endpoint.entity().getUUID().toString());
            raw.addProperty("target_type", BuiltInRegistries.ENTITY_TYPE.getKey(endpoint.entity().getType()).toString());
        }
        boolean accepted = InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), action,
                endpoint.player() == null ? scopeActorUuid(scope) : endpoint.player().getUUID().toString(),
                endpoint.player() == null ? scopeActorName(scope) : endpoint.player().getGameProfile().getName(),
                endpoint.level(), endpoint.x(), endpoint.y(), endpoint.z(),
                endpoint.level(), endpoint.x(), endpoint.y(), endpoint.z(), endpointType,
                item.itemId(), raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), item,
                amount, null, null, InternalObservationService.sourceEventIdForUuid(eventId.toString()),
                eventId.toString()));
        if (!accepted) {
            scope.rejectedEvidence++;
        } else {
            recordRelatedObservation(scope, eventId.toString());
        }
    }

    private static void recordGroundCreation(MutationScope scope, ItemEntity entity, ItemStack stack,
                                             boolean creative, String targetPlayerUuid) {
        CanonicalItem item = ItemCanonicalizer.canonicalizeStack(stack.copy());
        UUID eventId = UUID.randomUUID();
        String action = creative ? "CREATIVE_ITEM_CREATE" : "ADMIN_ITEM_CREATE";
        JsonObject raw = eventJson(scope, "confirmed_ground_output");
        raw.addProperty("event_id", eventId.toString());
        raw.addProperty("action", action);
        raw.addProperty("item_id", item.itemId());
        raw.addProperty("fingerprint", item.fingerprintHash());
        raw.addProperty("quantity", stack.getCount());
        raw.addProperty("item_entity_uuid", entity.getUUID().toString());
        raw.addProperty("target_type", "GROUND");
        raw.addProperty("target_player_uuid", targetPlayerUuid);
        boolean accepted = InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(), action, scopeActorUuid(scope), scopeActorName(scope),
                entity.level().dimension().location().toString(), entity.getX(), entity.getY(), entity.getZ(),
                entity.level().dimension().location().toString(), entity.getX(), entity.getY(), entity.getZ(),
                creative ? "CREATIVE_CREATE_GROUND" : "ADMIN_CREATE_GROUND", item.itemId(),
                raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                item, stack.getCount(), entity.getUUID().toString(), null,
                InternalObservationService.sourceEventIdForUuid(eventId.toString()), eventId.toString()));
        if (!accepted) {
            scope.rejectedEvidence++;
        } else {
            recordRelatedObservation(scope, eventId.toString());
        }
    }

    private static Endpoint snapshotPlayer(ServerPlayer player) {
        Map<String, StackState> slots = new LinkedHashMap<>();
        Container inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            StackState state = state(inventory.getItem(index));
            if (state != null) slots.put("inventory:" + index, state);
        }
        Container crafting = player.inventoryMenu.getCraftSlots();
        for (int index = 0; index < crafting.getContainerSize(); index++) {
            StackState state = state(crafting.getItem(index));
            if (state != null) slots.put("crafting:" + index, state);
        }
        return snapshotEntitySlot("player", player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), player, player, slots);
    }

    private static Endpoint snapshotContainer(Container container, String level, BlockPos position,
                                              Map<Integer, String> selectedSlots) {
        Map<String, StackState> values = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : selectedSlots.entrySet()) {
            int slot = entry.getKey();
            if (slot >= 0 && slot < container.getContainerSize()) {
                values.put(entry.getValue(), state(container.getItem(slot)));
            }
        }
        return new Endpoint("container", level, position.getX(), position.getY(), position.getZ(),
                null, null, container, values);
    }

    private static Endpoint snapshotEntitySlot(String kind, String level, double x, double y, double z,
                                               ServerPlayer player, Entity entity,
                                               Map<String, StackState> slots) {
        return new Endpoint(kind, level, x, y, z, player, entity, null,
                Collections.unmodifiableMap(new LinkedHashMap<>(slots)));
    }

    private static Map<String, StackState> current(Endpoint endpoint) {
        Map<String, StackState> values = new LinkedHashMap<>();
        if (endpoint.player() != null && "player".equals(endpoint.kind())) {
            Container inventory = endpoint.player().getInventory();
            for (int index = 0; index < inventory.getContainerSize(); index++) {
                StackState state = state(inventory.getItem(index));
                if (state != null) values.put("inventory:" + index, state);
            }
            Container crafting = endpoint.player().inventoryMenu.getCraftSlots();
            for (int index = 0; index < crafting.getContainerSize(); index++) {
                StackState state = state(crafting.getItem(index));
                if (state != null) values.put("crafting:" + index, state);
            }
        } else if (endpoint.container() != null) {
            // A command scope for block slots snapshots only its named slot(s).
            for (String key : endpoint.slots().keySet()) {
                int separator = key.lastIndexOf(':');
                int slot = Integer.parseInt(key.substring(separator + 1));
                if (slot >= 0 && slot < endpoint.container().getContainerSize()) {
                    StackState state = state(endpoint.container().getItem(slot));
                    if (state != null) values.put(key, state);
                }
            }
        } else if (endpoint.entity() != null && endpoint.player() != null
                && "player_slot".equals(endpoint.kind())) {
            for (String key : endpoint.slots().keySet()) {
                int separator = key.lastIndexOf(':');
                int slot = Integer.parseInt(key.substring(separator + 1));
                ItemStack stack = endpoint.entity().getSlot(slot).get();
                StackState state = state(stack);
                values.put(key, state);
            }
        } else if (endpoint.entity() != null && "creative_slot".equals(endpoint.kind())) {
            for (String key : endpoint.slots().keySet()) {
                int separator = key.lastIndexOf(':');
                int slot = Integer.parseInt(key.substring(separator + 1));
                ItemStack stack = endpoint.player().inventoryMenu.getSlot(slot).getItem();
                values.put(key, state(stack));
            }
        } else if (endpoint.entity() != null && "entity".equals(endpoint.kind())) {
            for (String key : endpoint.slots().keySet()) {
                int separator = key.lastIndexOf(':');
                int slot = Integer.parseInt(key.substring(separator + 1));
                values.put(key, state(endpoint.entity().getSlot(slot).get()));
            }
        }
        return values;
    }

    private static StackState state(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null
                : new StackState(ItemCanonicalizer.canonicalizeStack(stack.copy()), stack.getCount());
    }

    private static boolean same(StackState left, StackState right) {
        return left == right || (left != null && right != null && left.count() == right.count()
                && left.item().fingerprintHash().equals(right.item().fingerprintHash()));
    }

    private static void push(MutationScope scope) {
        Deque<MutationScope> scopes = SCOPES.get();
        if (scopes == null) {
            scopes = new ArrayDeque<>();
            SCOPES.set(scopes);
        }
        scopes.push(scope);
    }

    private static MutationScope pop() {
        Deque<MutationScope> scopes = SCOPES.get();
        if (scopes == null || scopes.isEmpty()) return null;
        MutationScope scope = scopes.pop();
        if (scopes.isEmpty()) SCOPES.remove();
        return scope;
    }

    private static boolean recordEvent(CommandSourceStack source, String eventType, String subject,
                                       String detail, String eventId, String mutationEventId,
                                       String parentEventId, String attemptEventId) {
        return recordEvent(source, eventType, subject, detail, eventId, mutationEventId,
                parentEventId, attemptEventId, null);
    }

    private static boolean recordEvent(CommandSourceStack source, String eventType, String subject,
                                       String detail, String eventId, String mutationEventId,
                                       String parentEventId, String attemptEventId, MutationScope scope) {
        if (source == null) return false;
        var position = source.getPosition();
        var level = source.getLevel();
        JsonObject raw = new JsonObject();
        raw.addProperty("event_id", eventId);
        if (mutationEventId != null) raw.addProperty("mutation_event_id", mutationEventId);
        if (parentEventId != null) raw.addProperty("parent_event_id", parentEventId);
        if (attemptEventId != null) raw.addProperty("command_attempt_event_id", attemptEventId);
        raw.addProperty("evidence", eventType.toLowerCase(java.util.Locale.ROOT));
        raw.addProperty("subject", bounded(subject));
        raw.addProperty("detail", bounded(detail));
        if (scope != null) {
            raw.addProperty("operation", commandRoot(scope.operation));
            if (!commandRoot(scope.operation).equals(scope.operation)) {
                raw.addProperty("mutation_kind", scope.operation);
            }
        }
        raw.addProperty("has_operator_permission", scope != null && scope.commandActorPermission != null
                ? scope.commandActorPermission : source.hasPermission(2));
        raw.addProperty("actor_kind", scope == null ? actorKind(source) : scopeActorKind(scope));
        raw.addProperty("actor_uuid", scope == null ? actorUuid(source) : scopeActorUuid(scope));
        raw.addProperty("actor_name", scope == null ? actorName(source) : scopeActorName(scope));
        if (scope != null && !scope.giveOutputRejectionDetails.isEmpty()) {
            com.google.gson.JsonArray rejectedOutputs = new com.google.gson.JsonArray();
            scope.giveOutputRejectionDetails.forEach(details -> rejectedOutputs.add(details.deepCopy()));
            raw.add("give_output_rejections", rejectedOutputs);
            if (scope.giveOutputRejectionOmitted > 0) {
                raw.addProperty("give_output_rejection_omitted_count", scope.giveOutputRejectionOmitted);
            }
        }
        if (scope != null) {
            addRelatedObservationIds(raw, scope);
        }
        addCommandEntityIdentity(raw, source, scope);
        addExecutionContext(raw, scope);
        if (scope != null && scope.copySource != null) {
            raw.add("copy_source", endpointEvidenceJson(scope.copySource));
        }
        raw.addProperty("staff_private", true);
        raw.addProperty("location_resolved", level != null && position != null);
        String levelName = level == null ? null : level.dimension().location().toString();
        double x = position == null ? 0.0 : position.x();
        double y = position == null ? 0.0 : position.y();
        double z = position == null ? 0.0 : position.z();
        return InternalObservationService.getInstance().submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                System.currentTimeMillis(), eventType,
                scope == null ? actorUuid(source) : scopeActorUuid(scope),
                scope == null ? actorName(source) : scopeActorName(scope),
                levelName, x, y, z,
                bounded(subject), bounded(detail), raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                InternalObservationService.sourceEventIdForUuid(eventId), eventId, List.of()));
    }

    private static boolean recordPlayerEvent(ServerPlayer player, String eventType, String detail,
                                             String eventId, MutationScope scope) {
        if (player == null) return false;
        JsonObject raw = new JsonObject();
        raw.addProperty("event_id", eventId);
        raw.addProperty("mutation_event_id", scope.parentEventId);
        raw.addProperty("parent_event_id", scope.parentEventId);
        addRelatedObservationIds(raw, scope);
        raw.addProperty("evidence", eventType.toLowerCase(java.util.Locale.ROOT));
        raw.addProperty("detail", bounded(detail));
        raw.addProperty("has_operator_permission", player.createCommandSourceStack().hasPermission(2));
        raw.addProperty("actor_kind", "player");
        raw.addProperty("actor_uuid", player.getUUID().toString());
        raw.addProperty("staff_private", true);
        return InternalObservationService.getInstance().submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                System.currentTimeMillis(), eventType, player.getUUID().toString(),
                player.getGameProfile().getName(), player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), null, bounded(detail),
                raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                InternalObservationService.sourceEventIdForUuid(eventId), eventId, List.of()));
    }

    private static void recordScopedOutcome(MutationScope scope, String eventType, String subject, String detail) {
        String eventId = UUID.randomUUID().toString();
        if (!recordEvent(scope.source, eventType, subject, detail, eventId,
                scope.parentEventId, scope.parentEventId, scope.commandAttemptEventId, scope)) {
            reportUnpersistedOutcome(eventType, eventId);
        }
    }

    private static boolean recordCreativeOutcome(MutationScope scope, String eventType, String detail) {
        String eventId = UUID.randomUUID().toString();
        String causeDetail = "cause=creative_inventory_packet cause_status=undifferentiated " + detail;
        if (!recordPlayerEvent(scope.creativePlayer, eventType, causeDetail, eventId, scope)) {
            reportUnpersistedOutcome(eventType, eventId);
            return false;
        }
        return true;
    }

    private static void reportUnpersistedOutcome(String eventType, String eventId) {
        long rejected = UNPERSISTED_OUTCOME_DIAGNOSTICS.incrementAndGet();
        if (rejected == 1 || rejected % 1000 == 0) {
            LOGGER.warn("ItemGraph could not persist {} outcome event {} after audit queue rejection ({} outcomes rejected)",
                    eventType, eventId, rejected);
        }
    }

    private static void recordRelatedObservation(MutationScope scope, String eventId) {
        if (scope.relatedObservationEventIds.size() < MAX_RELATED_ITEM_EVENT_IDS) {
            scope.relatedObservationEventIds.add(eventId);
        } else {
            scope.relatedObservationEventIdsOmitted++;
        }
    }

    private static void recordRelatedTransformation(MutationScope scope, String eventId) {
        if (scope.relatedTransformationEventIds.size() < MAX_RELATED_ITEM_EVENT_IDS) {
            scope.relatedTransformationEventIds.add(eventId);
        } else {
            scope.relatedTransformationEventIdsOmitted++;
        }
    }

    private static void recordRelatedUnresolved(MutationScope scope, String eventId) {
        if (scope.relatedUnresolvedEventIds.size() < MAX_RELATED_ITEM_EVENT_IDS) {
            scope.relatedUnresolvedEventIds.add(eventId);
        } else {
            scope.relatedUnresolvedEventIdsOmitted++;
        }
    }

    private static void addRelatedObservationIds(JsonObject raw, MutationScope scope) {
        if (scope.relatedObservationEventIds.isEmpty() && scope.relatedObservationEventIdsOmitted == 0
                && scope.relatedTransformationEventIds.isEmpty()
                && scope.relatedTransformationEventIdsOmitted == 0
                && scope.relatedUnresolvedEventIds.isEmpty()
                && scope.relatedUnresolvedEventIdsOmitted == 0) {
            raw.addProperty("item_flow_link_status", "NO_ITEM_EVIDENCE_RECORDED");
            return;
        }
        if (!scope.relatedObservationEventIds.isEmpty()) {
            com.google.gson.JsonArray eventIds = new com.google.gson.JsonArray();
            scope.relatedObservationEventIds.forEach(eventIds::add);
            raw.add("related_observation_event_ids", eventIds);
        }
        if (!scope.relatedTransformationEventIds.isEmpty()) {
            com.google.gson.JsonArray eventIds = new com.google.gson.JsonArray();
            scope.relatedTransformationEventIds.forEach(eventIds::add);
            raw.add("related_transformation_event_ids", eventIds);
        }
        if (!scope.relatedUnresolvedEventIds.isEmpty()) {
            com.google.gson.JsonArray eventIds = new com.google.gson.JsonArray();
            scope.relatedUnresolvedEventIds.forEach(eventIds::add);
            raw.add("related_unresolved_event_ids", eventIds);
        }
        boolean partial = scope.relatedObservationEventIdsOmitted > 0
                || scope.relatedTransformationEventIdsOmitted > 0
                || scope.relatedUnresolvedEventIdsOmitted > 0;
        boolean hasFlowEvidence = !scope.relatedObservationEventIds.isEmpty()
                || !scope.relatedTransformationEventIds.isEmpty();
        raw.addProperty("item_flow_link_status", partial ? "PARTIAL_LINK_LIST"
                : hasFlowEvidence ? "LINKED" : "UNRESOLVED_EVIDENCE_RECORDED");
        if (scope.relatedObservationEventIdsOmitted > 0) {
            raw.addProperty("related_observation_event_ids_omitted", scope.relatedObservationEventIdsOmitted);
        }
        if (scope.relatedTransformationEventIdsOmitted > 0) {
            raw.addProperty("related_transformation_event_ids_omitted", scope.relatedTransformationEventIdsOmitted);
        }
        if (scope.relatedUnresolvedEventIdsOmitted > 0) {
            raw.addProperty("related_unresolved_event_ids_omitted", scope.relatedUnresolvedEventIdsOmitted);
        }
    }

    private static JsonObject eventJson(MutationScope scope, String outcome) {
        JsonObject raw = new JsonObject();
        raw.addProperty("event_id", UUID.randomUUID().toString());
        raw.addProperty("mutation_event_id", scope.parentEventId);
        raw.addProperty("parent_event_id", scope.parentEventId);
        if (scope.commandAttemptEventId != null) {
            raw.addProperty("command_attempt_event_id", scope.commandAttemptEventId);
        }
        raw.addProperty("operation", commandRoot(scope.operation));
        if (!commandRoot(scope.operation).equals(scope.operation)) {
            raw.addProperty("mutation_kind", scope.operation);
        }
        if ("creative_slot".equals(scope.operation)) {
            raw.addProperty("cause", "creative_inventory_packet");
            raw.addProperty("cause_status", "undifferentiated");
        }
        raw.addProperty("outcome", outcome);
        raw.addProperty("source", scope.sourceKind);
        raw.addProperty("actor_kind", scopeActorKind(scope));
        raw.addProperty("actor_uuid", scopeActorUuid(scope));
        raw.addProperty("actor_name", scopeActorName(scope));
        addCommandEntityIdentity(raw, scope.source, scope);
        raw.addProperty("has_operator_permission", scope.commandActorPermission != null
                ? scope.commandActorPermission : scope.source == null
                    ? scope.creativePlayer.createCommandSourceStack().hasPermission(2)
                    : scope.source.hasPermission(2));
        addExecutionContext(raw, scope);
        if (scope.copySource != null) raw.add("copy_source", endpointEvidenceJson(scope.copySource));
        raw.addProperty("staff_private", true);
        return raw;
    }

    private static String commandRoot(String operation) {
        return "item_replace".equals(operation) || "item_modify".equals(operation) ? "item" : operation;
    }

    private static JsonObject endpointEvidenceJson(Endpoint endpoint) {
        JsonObject value = new JsonObject();
        value.addProperty("kind", endpoint.kind());
        value.addProperty("level", endpoint.level());
        value.addProperty("x", endpoint.x());
        value.addProperty("y", endpoint.y());
        value.addProperty("z", endpoint.z());
        if (endpoint.entity() != null) {
            value.addProperty("entity_uuid", endpoint.entity().getUUID().toString());
            value.addProperty("entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(endpoint.entity().getType()).toString());
        }
        JsonObject slots = new JsonObject();
        endpoint.slots().forEach((slot, stack) -> {
            JsonObject item = new JsonObject();
            addStack(item, "stack", stack);
            slots.add(slot, item.get("stack"));
        });
        value.add("slots", slots);
        return value;
    }

    private static void addExecutionContext(JsonObject raw, MutationScope scope) {
        if (scope == null || !scope.hasCommandActor) return;
        Entity effectiveEntity = scope.source.getEntity();
        String effectiveActorUuid = effectiveEntity == null ? null : effectiveEntity.getUUID().toString();
        String effectiveActorName = effectiveEntity instanceof ServerPlayer player
                ? player.getGameProfile().getName()
                : effectiveEntity == null ? null : effectiveEntity.getName().getString();
        if (Objects.equals(scope.commandActorKind, actorKind(scope.source))
                && Objects.equals(scope.commandActorEntityUuid, effectiveActorUuid)) return;
        raw.addProperty("execution_context_actor_kind", actorKind(scope.source));
        if (effectiveActorUuid == null) raw.add("execution_context_actor_uuid", com.google.gson.JsonNull.INSTANCE);
        else raw.addProperty("execution_context_actor_uuid", effectiveActorUuid);
        if (effectiveActorName == null) raw.add("execution_context_actor_name", com.google.gson.JsonNull.INSTANCE);
        else raw.addProperty("execution_context_actor_name", effectiveActorName);
        if (effectiveEntity != null && !(effectiveEntity instanceof ServerPlayer)) {
            raw.addProperty("execution_context_entity_type",
                    BuiltInRegistries.ENTITY_TYPE.getKey(effectiveEntity.getType()).toString());
        }
        raw.addProperty("execution_context_has_operator_permission", scope.source.hasPermission(2));
    }

    private static void addCommandEntityIdentity(JsonObject raw, CommandSourceStack source, MutationScope scope) {
        String kind = scope == null ? actorKind(source) : scopeActorKind(scope);
        if (!"entity".equals(kind)) return;
        String uuid = scope == null ? entityUuid(source) : scope.commandActorEntityUuid;
        if (uuid != null) raw.addProperty("actor_entity_uuid", uuid);
    }

    private static void addStack(JsonObject target, String key, StackState state) {
        JsonObject value = new JsonObject();
        if (state == null) {
            value.addProperty("empty", true);
            target.add(key, value);
            return;
        }
        value.addProperty("item_id", state.item().itemId());
        value.addProperty("fingerprint", state.item().fingerprintHash());
        value.addProperty("count", state.count());
        target.add(key, value);
    }

    private static String actorUuid(CommandSourceStack source) {
        return source != null && source.getEntity() instanceof ServerPlayer player
                ? player.getUUID().toString() : null;
    }

    private static String actorName(CommandSourceStack source) {
        return source != null && source.getEntity() instanceof ServerPlayer player
                ? player.getGameProfile().getName() : null;
    }

    private static String entityUuid(CommandSourceStack source) {
        return source == null || source.getEntity() == null ? null : source.getEntity().getUUID().toString();
    }

    private static String actorKind(CommandSourceStack source) {
        return source != null && source.getEntity() instanceof ServerPlayer ? "player"
                : source == null || source.getEntity() == null ? "server_or_console" : "entity";
    }

    private static String scopeActorKind(MutationScope scope) {
        if (scope.hasCommandActor) return scope.commandActorKind;
        return scope.source == null ? "player" : actorKind(scope.source);
    }

    private static String scopeActorUuid(MutationScope scope) {
        if (scope.hasCommandActor) return scope.commandActorUuid;
        return scope.source == null && scope.creativePlayer != null
                ? scope.creativePlayer.getUUID().toString() : actorUuid(scope.source);
    }

    private static String scopeActorName(MutationScope scope) {
        if (scope.hasCommandActor) return scope.commandActorName;
        return scope.source == null && scope.creativePlayer != null
                ? scope.creativePlayer.getGameProfile().getName() : actorName(scope.source);
    }

    public static boolean isItemCommand(String command) {
        return vanillaItemRoot(command) != null;
    }

    /** Raw /execute text can contain nested selectors and item arguments, so never mirror it to generic command history. */
    public static boolean shouldSuppressRawCommand(String command) {
        String root = rootCommand(command);
        return isItemCommand(command) || isVanillaRoot(root, "execute");
    }

    private static String rootCommand(String command) {
        if (command == null) return "unknown";
        String value = command.strip();
        if (value.startsWith("/")) value = value.substring(1);
        int end = 0;
        while (end < value.length() && !Character.isWhitespace(value.charAt(end))) end++;
        String root = value.substring(0, end);
        return root.toLowerCase(java.util.Locale.ROOT);
    }

    private static String itemMutationRoot(String command, List<String> parsedNodeNames) {
        String direct = vanillaItemRoot(command);
        if (direct != null) return direct;
        if (!isVanillaRoot(rootCommand(command), "execute")) return null;
        for (int index = 0; index + 1 < parsedNodeNames.size(); index++) {
            if (!"run".equals(parsedNodeNames.get(index))) continue;
            String nestedNode = parsedNodeNames.get(index + 1);
            String nested = vanillaItemRoot(nestedNode);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean isItemRoot(String root) {
        return "give".equals(root) || "clear".equals(root) || "item".equals(root);
    }

    private static String vanillaItemRoot(String command) {
        String root = rootCommand(command);
        if (isItemRoot(root)) return root;
        for (String itemRoot : List.of("give", "clear", "item")) {
            if (isVanillaRoot(root, itemRoot)) return itemRoot;
        }
        return null;
    }

    private static boolean isVanillaRoot(String root, String command) {
        return command.equals(root) || ("minecraft:" + command).equals(root);
    }

    private static String bounded(String value) {
        if (value == null) return null;
        return value.length() <= 1024 ? value : value.substring(0, 1024);
    }

    private static Map<String, StackState> slotMap(String key, StackState value) {
        Map<String, StackState> result = new LinkedHashMap<>();
        result.put(key, value);
        return result;
    }
}
