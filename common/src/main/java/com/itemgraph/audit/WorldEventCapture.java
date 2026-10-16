package com.itemgraph.audit;

import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Captures bounded before/after state for authoritative world-event boundaries. */
public final class WorldEventCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldEventCapture.class);
    private static final int MAX_EXPLOSION_POSITIONS = 4_096;
    private static final int MAX_EXPLOSION_EVIDENCE_ROWS = 256;
    private static final int MAX_PISTON_POSITIONS = 64;
    private static final int MAX_PISTON_EVIDENCE_ROWS = 64;
    private static final Set<String> QUEUE_REJECTION_WARNED_FAMILIES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Deque<PistonInvocation>> PISTON_INVOCATIONS =
            ThreadLocal.withInitial(ArrayDeque::new);

    private record PistonSnapshot(ServerLevel level, BlockPos pistonPos, String causeEventId,
                                  String movement, Direction pushDirection,
                                  Map<BlockPos, BlockState> beforeStates,
                                  int candidatePositionCount, int unavailablePositionCount,
                                  boolean resolverSucceeded) {
        private PistonSnapshot {
            beforeStates = Map.copyOf(beforeStates);
        }
    }

    private record PistonInvocation(PistonSnapshot snapshot) { }

    public record ExplosionSnapshot(Map<BlockPos, BlockState> beforeStates,
                                    int affectedPositionCount,
                                    int unavailablePositionCount,
                                    int snapshotFailureCount,
                                    boolean candidateCoverageTruncated,
                                    boolean affectedPositionCountKnown) {
        public ExplosionSnapshot {
            beforeStates = Map.copyOf(beforeStates);
        }

        public boolean isPartial() {
            return !affectedPositionCountKnown || candidateCoverageTruncated
                    || unavailablePositionCount > 0 || snapshotFailureCount > 0;
        }
    }

    public enum ExplosionExit { RETURNED, THREW }

    private WorldEventCapture() { }

    /**
     * Starts capture around PistonBaseBlock.triggerEvent. The thread-local stack
     * preserves nested neighbor-triggered piston events without retaining worlds
     * or positions after a completed invocation.
     */
    public static void beginPiston(Level level, BlockState pistonState, BlockPos pistonPos, int eventId) {
        PistonSnapshot snapshot = null;
        if (level instanceof ServerLevel serverLevel
                && (eventId == PistonBaseBlock.TRIGGER_EXTEND
                || eventId == PistonBaseBlock.TRIGGER_CONTRACT)) {
            boolean extending = eventId == PistonBaseBlock.TRIGGER_EXTEND;
            String causeEventId = UUID.randomUUID().toString();
            try {
                snapshot = snapshotPiston(serverLevel, pistonState, pistonPos, extending, causeEventId);
            } catch (RuntimeException captureFailure) {
                LOGGER.error("Could not snapshot piston state at {} in {}",
                        pistonPos, serverLevel.dimension().location(), captureFailure);
                recordPistonCaptureFailure(serverLevel, pistonPos, causeEventId,
                        extending ? "PISTON_EXTEND" : "PISTON_RETRACT", "PISTON_BEFORE_STATE_SNAPSHOT");
            }
        }
        PISTON_INVOCATIONS.get().push(new PistonInvocation(snapshot));
    }

    /** Completes the most recent triggerEvent capture with its returned result. */
    public static void finishPiston(Level level, BlockPos pistonPos, boolean returnedSuccess) {
        Deque<PistonInvocation> invocations = PISTON_INVOCATIONS.get();
        if (invocations.isEmpty()) {
            PISTON_INVOCATIONS.remove();
            return;
        }
        PistonSnapshot snapshot = invocations.pop().snapshot();
        if (invocations.isEmpty()) {
            PISTON_INVOCATIONS.remove();
        }
        if (snapshot != null && level == snapshot.level() && pistonPos.equals(snapshot.pistonPos())) {
            try {
                recordPistonResults(snapshot, returnedSuccess, "RETURN");
            } catch (RuntimeException captureFailure) {
                LOGGER.error("Could not record piston state after trigger for cause {}",
                        snapshot.causeEventId(), captureFailure);
                recordPistonCaptureFailure(snapshot.level(), snapshot.pistonPos(), snapshot.causeEventId(),
                        snapshot.movement(), "PISTON_AFTER_STATE_CAPTURE");
            }
        }
    }

    /** Completes capture when vanilla exits triggerEvent exceptionally. */
    public static void abortPiston(Level level, BlockPos pistonPos) {
        Deque<PistonInvocation> invocations = PISTON_INVOCATIONS.get();
        if (invocations.isEmpty()) {
            PISTON_INVOCATIONS.remove();
            return;
        }
        PistonSnapshot snapshot = invocations.pop().snapshot();
        if (invocations.isEmpty()) {
            PISTON_INVOCATIONS.remove();
        }
        if (snapshot != null && level == snapshot.level() && pistonPos.equals(snapshot.pistonPos())) {
            try {
                recordPistonResults(snapshot, false, "THROW");
            } catch (RuntimeException captureFailure) {
                LOGGER.error("Could not record piston state after exceptional trigger for cause {}",
                        snapshot.causeEventId(), captureFailure);
                recordPistonCaptureFailure(snapshot.level(), snapshot.pistonPos(), snapshot.causeEventId(),
                        snapshot.movement(), "PISTON_EXCEPTION_RESULT_CAPTURE");
            }
        }
    }

    private static PistonSnapshot snapshotPiston(ServerLevel level, BlockState pistonState,
                                                 BlockPos pistonPos, boolean extending, String causeEventId) {
        BlockPos immutablePistonPos = pistonPos.immutable();
        Direction facing = pistonState.getValue(PistonBaseBlock.FACING);
        Direction pushDirection = extending ? facing : facing.getOpposite();
        String movement = extending ? "PISTON_EXTEND" : "PISTON_RETRACT";
        PistonStructureResolver resolver = new PistonStructureResolver(level, immutablePistonPos, facing, extending);
        boolean resolved = resolver.resolve();
        if (!resolved) {
            return new PistonSnapshot(level, immutablePistonPos, causeEventId, movement,
                    pushDirection, Map.of(), 0, 0, false);
        }

        pushDirection = resolver.getPushDirection();
        Set<BlockPos> candidates = new LinkedHashSet<>();
        candidates.add(immutablePistonPos);
        candidates.add(immutablePistonPos.relative(facing).immutable());
        for (BlockPos pushed : resolver.getToPush()) {
            candidates.add(pushed.immutable());
            candidates.add(pushed.relative(pushDirection).immutable());
        }
        for (BlockPos destroyed : resolver.getToDestroy()) {
            candidates.add(destroyed.immutable());
        }

        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        int unavailable = 0;
        int visited = 0;
        for (BlockPos candidate : candidates) {
            if (visited++ >= MAX_PISTON_POSITIONS) {
                break;
            }
            if (!level.hasChunkAt(candidate)) {
                unavailable++;
                continue;
            }
            before.put(candidate, level.getBlockState(candidate));
        }
        return new PistonSnapshot(level, immutablePistonPos, causeEventId, movement,
                pushDirection, before, candidates.size(), unavailable, true);
    }

    private static void recordPistonResults(PistonSnapshot snapshot, boolean returnedSuccess, String exitBoundary) {
        ServerLevel level = snapshot.level();
        long timestampMs = System.currentTimeMillis();
        Map<String, String> sourceMetadata = new TreeMap<>();
        sourceMetadata.put("cause_event_id", snapshot.causeEventId());
        sourceMetadata.put("cause_family", "piston");
        sourceMetadata.put("movement", snapshot.movement());
        sourceMetadata.put("piston_position", snapshot.pistonPos().toShortString());
        sourceMetadata.put("push_direction", snapshot.pushDirection().getName());
        sourceMetadata.put("callback_result", "THROW".equals(exitBoundary)
                ? "THREW" : Boolean.toString(returnedSuccess));
        sourceMetadata.put("capture_boundary", "PistonBaseBlock.triggerEvent." + exitBoundary.toLowerCase(java.util.Locale.ROOT));
        sourceMetadata.put("resolver_succeeded", Boolean.toString(snapshot.resolverSucceeded()));

        int changedPositionCount = 0;
        int representedPositionCount = 0;
        int unavailableAfterCount = 0;
        int rejectedEvidenceCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : new TreeMap<>(snapshot.beforeStates()).entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos)) {
                unavailableAfterCount++;
                continue;
            }
            BlockState before = entry.getValue();
            BlockState after = level.getBlockState(pos);
            if (before.equals(after)) {
                continue;
            }
            changedPositionCount++;
            if (changedPositionCount > MAX_PISTON_EVIDENCE_ROWS) {
                continue;
            }
            String subjectId = BuiltInRegistries.BLOCK.getKey(
                    before.isAir() ? after.getBlock() : before.getBlock()).toString();
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(WorldEventEvidence.create(
                    "PISTON_BLOCK_MOVE", timestampMs, level.dimension().location().toString(),
                    pos.getX(), pos.getY(), pos.getZ(), subjectId,
                    WorldEventEvidence.Outcome.CONFIRMED_CHANGE, null, snapshot.movement(),
                    before.toString(), after.toString(), null, null, null, sourceMetadata));
            if (!accepted) {
                rejectedEvidenceCount++;
            } else {
                representedPositionCount++;
            }
        }

        boolean completeCoverage = hasCompletePistonCoverage(snapshot.resolverSucceeded(),
                snapshot.candidatePositionCount(), snapshot.beforeStates().size(),
                snapshot.unavailablePositionCount(), unavailableAfterCount);
        boolean blockedByResolver = !snapshot.resolverSucceeded() && !returnedSuccess;
        if (changedPositionCount == 0 && canRecordPistonUnchangedAttempt(snapshot.resolverSucceeded(),
                returnedSuccess, "RETURN".equals(exitBoundary), snapshot.candidatePositionCount(),
                snapshot.beforeStates().size(),
                snapshot.unavailablePositionCount(), unavailableAfterCount)) {
            // A failed resolver plus a false vanilla result directly establishes a
            // blocked attempt. Other no-change results require a complete state scan.
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(WorldEventEvidence.create(
                    "PISTON_BLOCK_ATTEMPT", timestampMs, level.dimension().location().toString(),
                    snapshot.pistonPos().getX(), snapshot.pistonPos().getY(), snapshot.pistonPos().getZ(),
                    "minecraft:piston", WorldEventEvidence.Outcome.UNCHANGED, null, snapshot.movement(),
                    null, null, null, null, null, sourceMetadata));
            if (!accepted) {
                rejectedEvidenceCount++;
            }
        }

        boolean exceptionalExit = "THROW".equals(exitBoundary);
        boolean partialCoverage = exceptionalExit || (!completeCoverage && !blockedByResolver)
                || changedPositionCount > MAX_PISTON_EVIDENCE_ROWS || rejectedEvidenceCount > 0;
        if (partialCoverage) {
            Map<String, String> partialMetadata = new TreeMap<>(sourceMetadata);
            if (exceptionalExit) {
                partialMetadata.put("callback_exception_count", "1");
            }
            partialMetadata.put("candidate_position_count", Integer.toString(snapshot.candidatePositionCount()));
            partialMetadata.put("captured_position_count", Integer.toString(snapshot.beforeStates().size()));
            partialMetadata.put("unavailable_position_count", Integer.toString(snapshot.unavailablePositionCount()));
            partialMetadata.put("unavailable_after_count", Integer.toString(unavailableAfterCount));
            partialMetadata.put("changed_position_count", Integer.toString(changedPositionCount));
            partialMetadata.put("represented_position_count", Integer.toString(Math.min(
                    representedPositionCount, MAX_PISTON_EVIDENCE_ROWS)));
            partialMetadata.put("queue_rejected_count", Integer.toString(rejectedEvidenceCount));
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(WorldEventEvidence.create(
                    "WORLD_EFFECT_UNRESOLVED", timestampMs, level.dimension().location().toString(),
                    snapshot.pistonPos().getX(), snapshot.pistonPos().getY(), snapshot.pistonPos().getZ(),
                    "minecraft:piston", WorldEventEvidence.Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL",
                    snapshot.movement(), null, null, null, null, null, partialMetadata));
            if (!accepted) {
                rejectedEvidenceCount++;
            }
        }
        if (rejectedEvidenceCount > 0) {
            LOGGER.error("ItemGraph rejected {} audit record(s) for piston cause {} because the bounded queue was full",
                    rejectedEvidenceCount, snapshot.causeEventId());
        }
    }

    static boolean hasCompletePistonCoverage(boolean resolverSucceeded, int candidatePositionCount,
                                             int capturedPositionCount, int unavailableBeforeCount,
                                             int unavailableAfterCount) {
        return resolverSucceeded
                && candidatePositionCount >= 0
                && candidatePositionCount <= MAX_PISTON_POSITIONS
                && capturedPositionCount == candidatePositionCount
                && unavailableBeforeCount == 0
                && unavailableAfterCount == 0;
    }

    static boolean canRecordPistonUnchangedAttempt(boolean resolverSucceeded, boolean callbackReturnedSuccess,
                                                   boolean returnedNormally, int candidatePositionCount,
                                                   int capturedPositionCount,
                                                   int unavailableBeforeCount, int unavailableAfterCount) {
        return returnedNormally && (hasCompletePistonCoverage(resolverSucceeded,
                candidatePositionCount, capturedPositionCount,
                unavailableBeforeCount, unavailableAfterCount)
                || (!resolverSucceeded && !callbackReturnedSuccess));
    }

    private static void recordPistonCaptureFailure(ServerLevel level, BlockPos pistonPos, String causeEventId,
                                                   String movement, String captureStage) {
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("cause_event_id", causeEventId);
        metadata.put("movement", movement);
        metadata.put("capture_stage", captureStage);
        metadata.put("capture_failure_count", "1");
        if (!submitWorldEffectUnresolved(level, pistonPos, "piston", "WORLD_EFFECT_PARTIAL", metadata)) {
            warnQueueRejectionOnce("piston", pistonPos);
        }
    }

    /**
     * Called at explosion finalization entry. Copies at most 4,096 immutable
     * positions and records missing loaded chunks instead of forcing chunk loads.
     */
    public static ExplosionSnapshot snapshotExplosion(ServerLevel level, List<BlockPos> affectedPositions) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        int unavailableCount = 0;
        int snapshotFailureCount = 0;
        int visitedPositions = 0;
        boolean truncated = false;
        for (BlockPos affected : affectedPositions) {
            if (visitedPositions >= MAX_EXPLOSION_POSITIONS) {
                truncated = true;
                break;
            }
            visitedPositions++;
            try {
                BlockPos immutablePos = affected.immutable();
                if (!level.hasChunkAt(immutablePos)) {
                    unavailableCount++;
                    continue;
                }
                before.putIfAbsent(immutablePos, level.getBlockState(immutablePos));
            } catch (RuntimeException candidateFailure) {
                snapshotFailureCount++;
            }
        }
        return new ExplosionSnapshot(before, affectedPositions.size(), unavailableCount,
                snapshotFailureCount, truncated, true);
    }

    /** Runtime-safe snapshot entry used by loader hooks; audit failures must not cancel gameplay. */
    public static ExplosionSnapshot safeSnapshotExplosion(ServerLevel level, List<BlockPos> affectedPositions) {
        try {
            return snapshotExplosion(level, affectedPositions);
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not snapshot explosion block state in {}", level.dimension().location(), captureFailure);
            return new ExplosionSnapshot(Map.of(), 0, 0, 1, false, false);
        }
    }

    /** Called at explosion finalization return; only confirmed state deltas are emitted as observed. */
    public static void recordExplosionResults(ServerLevel level, Explosion explosion,
                                              ExplosionSnapshot snapshot) {
        recordExplosionResults(level, explosion, snapshot, ExplosionExit.RETURNED);
    }

    /** Records bounded partial evidence when another mod throws during explosion finalization. */
    public static void recordExplosionResults(ServerLevel level, Explosion explosion,
                                              ExplosionSnapshot snapshot, ExplosionExit exit) {
        try {
            recordExplosionResultsInternal(level, explosion, snapshot, exit);
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not record explosion block state in {}", level.dimension().location(), captureFailure);
        }
    }

    private static void recordExplosionResultsInternal(ServerLevel level, Explosion explosion,
                                                        ExplosionSnapshot snapshot, ExplosionExit exit) {
        if (snapshot == null) {
            return;
        }
        boolean exceptionalExit = exit == ExplosionExit.THREW;
        String interaction = explosion.getBlockInteraction().name();
        Entity directSource = explosion.getDirectSourceEntity();
        Entity indirectSource = explosion.getIndirectSourceEntity();
        long timestampMs = System.currentTimeMillis();
        String causeEventId = UUID.randomUUID().toString();
        int unavailableAfterCount = 0;
        Map<String, String> sourceMetadata = new TreeMap<>();
        sourceMetadata.put("cause_event_id", causeEventId);
        sourceMetadata.put("cause_family", "explosion");
        sourceMetadata.put("finalize_exit", exit.name());
        sourceMetadata.put("block_interaction", interaction);
        sourceMetadata.put("explosion_center", explosion.center().toString());
        sourceMetadata.put("explosion_radius", Float.toString(explosion.radius()));
        putEntity(sourceMetadata, "direct_source", directSource);
        putEntity(sourceMetadata, "indirect_source", indirectSource);

        int changedPositionCount = 0;
        int representedPositionCount = 0;
        int rejectedEvidenceCount = 0;
        int resultFailureCount = 0;
        for (Map.Entry<BlockPos, BlockState> entry : new TreeMap<>(snapshot.beforeStates()).entrySet()) {
            try {
                BlockPos pos = entry.getKey();
                if (!level.hasChunkAt(pos)) {
                    unavailableAfterCount++;
                    continue;
                }
                BlockState before = entry.getValue();
                BlockState after = level.getBlockState(pos);
                if (before.equals(after)) {
                    continue;
                }
                changedPositionCount++;
                if (changedPositionCount > MAX_EXPLOSION_EVIDENCE_ROWS) {
                    continue;
                }
                String blockId = BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString();
                boolean accepted = InternalObservationService.getInstance().submitAuditEvent(WorldEventEvidence.create(
                        "EXPLOSION_BLOCK_CHANGE",
                        timestampMs,
                        level.dimension().location().toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        blockId,
                        WorldEventEvidence.Outcome.CONFIRMED_CHANGE,
                        null,
                        "EXPLOSION_" + interaction,
                        before.toString(),
                        after.toString(),
                        null,
                        null,
                        null,
                        sourceMetadata));
                if (!accepted) {
                    rejectedEvidenceCount++;
                } else {
                    representedPositionCount++;
                }
            } catch (RuntimeException candidateFailure) {
                resultFailureCount++;
            }
        }

        boolean partialCoverage = snapshot.isPartial()
                || unavailableAfterCount > 0
                || changedPositionCount > MAX_EXPLOSION_EVIDENCE_ROWS
                || rejectedEvidenceCount > 0 || resultFailureCount > 0 || exceptionalExit;
        if (partialCoverage) {
            BlockPos center = BlockPos.containing(explosion.center());
            Map<String, String> partialMetadata = new TreeMap<>(sourceMetadata);
            partialMetadata.put("affected_position_count", Integer.toString(snapshot.affectedPositionCount()));
            partialMetadata.put("affected_position_count_known",
                    Boolean.toString(snapshot.affectedPositionCountKnown()));
            partialMetadata.put("captured_position_count", Integer.toString(snapshot.beforeStates().size()));
            partialMetadata.put("unavailable_position_count", Integer.toString(snapshot.unavailablePositionCount()));
            partialMetadata.put("snapshot_failure_count", Integer.toString(snapshot.snapshotFailureCount()));
            partialMetadata.put("candidate_coverage_truncated",
                    Boolean.toString(snapshot.candidateCoverageTruncated()));
            partialMetadata.put("unavailable_after_count", Integer.toString(unavailableAfterCount));
            partialMetadata.put("changed_position_count", Integer.toString(changedPositionCount));
            partialMetadata.put("represented_position_count", Integer.toString(representedPositionCount));
            partialMetadata.put("queue_rejected_count", Integer.toString(rejectedEvidenceCount));
            partialMetadata.put("result_failure_count", Integer.toString(resultFailureCount));
            partialMetadata.put("finalize_exception_count", exceptionalExit ? "1" : "0");
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(WorldEventEvidence.create(
                    "WORLD_EFFECT_UNRESOLVED",
                    timestampMs,
                    level.dimension().location().toString(),
                    center.getX(), center.getY(), center.getZ(),
                    "minecraft:explosion",
                    WorldEventEvidence.Outcome.UNRESOLVED,
                    "WORLD_EFFECT_PARTIAL",
                    "EXPLOSION_" + interaction,
                    null,
                    null,
                    null,
                    null,
                    null,
                    partialMetadata));
            if (!accepted) {
                rejectedEvidenceCount++;
            }
        }
        if (rejectedEvidenceCount > 0) {
            LOGGER.error("ItemGraph rejected {} audit record(s) for explosion cause {} because the bounded queue was full",
                    rejectedEvidenceCount, causeEventId);
        }
    }

    private static void putEntity(Map<String, String> metadata, String prefix, Entity entity) {
        if (entity == null) {
            return;
        }
        metadata.put(prefix + "_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        metadata.put(prefix + "_uuid", entity.getUUID().toString());
    }

    /** Returns separate, bounded actor and owner fields for a known non-player entity cause. */
    public static Map<String, String> entityProvenanceMetadata(Entity actor, Entity owner,
                                                               boolean ownershipApplies) {
        if (actor == null) {
            return Map.of("actor_kind", "UNKNOWN",
                    "owner_provenance_status", ownershipApplies ? "UNAVAILABLE" : "NOT_APPLICABLE");
        }
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("actor_kind", "ENTITY");
        metadata.put("actor_entity_uuid", actor.getUUID().toString());
        metadata.put("actor_entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(actor.getType()).toString());
        if (!ownershipApplies) {
            metadata.put("owner_provenance_status", "NOT_APPLICABLE");
        } else if (owner == null) {
            metadata.put("owner_provenance_status", "UNAVAILABLE");
        } else {
            metadata.put("owner_provenance_status", "RESOLVED");
            metadata.put("owner_entity_uuid", owner.getUUID().toString());
            metadata.put("owner_entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(owner.getType()).toString());
        }
        return metadata;
    }

    /** Records one direct, single-position state-write boundary without scanning nearby blocks. */
    public static void recordDirectBlockChange(LevelAccessor level, BlockPos pos, BlockState before,
                                               String eventType, String family, String cause,
                                               String causeEventId, Map<String, String> sourceMetadata,
                                               boolean blockTypeOnly) {
        recordDirectBlockChange(level, pos, before, eventType, family, cause, causeEventId,
                sourceMetadata, blockTypeOnly, null, false);
    }

    /** Records a direct state write and preserves a failed or exceptional callback with no state delta. */
    public static void recordDirectBlockChange(LevelAccessor level, BlockPos pos, BlockState before,
                                               String eventType, String family, String cause,
                                               String causeEventId, Map<String, String> sourceMetadata,
                                               boolean blockTypeOnly, Boolean callbackResult,
                                               boolean callbackThrew) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Map<String, String> metadata = new TreeMap<>(sourceMetadata == null ? Map.of() : sourceMetadata);
        metadata.putIfAbsent("cause_event_id", causeEventId == null ? UUID.randomUUID().toString() : causeEventId);
        metadata.put("cause_family", family);
        metadata.put("capture_boundary", cause);
        try {
            if (before == null || !serverLevel.hasChunkAt(pos)) {
                metadata.put("snapshot_failure_count", "1");
                if (!submitWorldEffectUnresolved(serverLevel, pos, family, "WORLD_EFFECT_PARTIAL", metadata)) {
                    LOGGER.error("ItemGraph rejected unresolved {} world-effect evidence at {}", family, pos);
                }
                return;
            }
            BlockState after = serverLevel.getBlockState(pos);
            boolean changed = blockTypeOnly ? before.getBlock() != after.getBlock() : !before.equals(after);
            if (!changed) {
                if (callbackThrew) {
                    metadata.put("callback_exception_count", "1");
                    metadata.put("callback_result", "THREW");
                    if (!submitWorldEffectUnresolved(serverLevel, pos, family, "WORLD_EFFECT_PARTIAL", metadata)) {
                        warnQueueRejectionOnce(family, pos);
                    }
                } else if (Boolean.FALSE.equals(callbackResult)) {
                    metadata.put("callback_result", "RETURNED_FALSE");
                    boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                            WorldEventEvidence.create("WORLD_EFFECT_ATTEMPT", System.currentTimeMillis(),
                                    serverLevel.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                                    null, WorldEventEvidence.Outcome.UNCHANGED, null, cause,
                                    null, null, null, null, null, metadata));
                    if (!accepted) {
                        metadata.put("queue_rejected_count", "1");
                        if (!submitWorldEffectUnresolved(serverLevel, pos, family, "WORLD_EFFECT_PARTIAL", metadata)) {
                            warnQueueRejectionOnce(family, pos);
                        }
                    }
                }
                return;
            }
            String subject = BuiltInRegistries.BLOCK.getKey(
                    before.isAir() ? after.getBlock() : before.getBlock()).toString();
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                    WorldEventEvidence.create(eventType, System.currentTimeMillis(),
                            serverLevel.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                            subject, WorldEventEvidence.Outcome.CONFIRMED_CHANGE, null, cause,
                            before.toString(), after.toString(), null, null, null, metadata));
            if (!accepted) {
                metadata.put("queue_rejected_count", "1");
                if (!submitWorldEffectUnresolved(serverLevel, pos, family, "WORLD_EFFECT_PARTIAL", metadata)) {
                    warnQueueRejectionOnce(family, pos);
                }
            }
            if (callbackThrew) {
                metadata.put("callback_exception_count", "1");
                metadata.put("callback_result", "THREW");
                if (!submitWorldEffectUnresolved(serverLevel, pos, family, "WORLD_EFFECT_PARTIAL", metadata)) {
                    warnQueueRejectionOnce(family, pos);
                }
            }
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not record {} block change at {} in {}", family, pos,
                    serverLevel.dimension().location(), captureFailure);
            metadata.put("result_failure_count", "1");
            if (!submitWorldEffectUnresolved(serverLevel, pos, family, "WORLD_EFFECT_PARTIAL", metadata)) {
                warnQueueRejectionOnce(family, pos);
            }
        }
    }

    /**
     * Runs and records one wrapped boolean block-write callback. Keep the callback result
     * and exception boundary in this shared helper so loader wrappers and deterministic
     * conformance fixtures exercise the same false/throw handling.
     */
    public static boolean captureDirectBlockWrite(Level level, BlockPos pos, String eventType,
                                                   String family, String boundary, boolean blockTypeOnly,
                                                   java.util.function.BooleanSupplier write) {
        return captureDirectBlockWrite(level, pos, eventType, family, boundary, blockTypeOnly, Map.of(), write);
    }

    /** Runs and records a boolean block write while retaining source metadata on attempts and deltas. */
    public static boolean captureDirectBlockWrite(Level level, BlockPos pos, String eventType,
                                                   String family, String boundary, boolean blockTypeOnly,
                                                   Map<String, String> sourceMetadata,
                                                   java.util.function.BooleanSupplier write) {
        BlockState before = safeSnapshotBlockState(level, pos);
        Boolean result = null;
        boolean returned = false;
        try {
            result = write.getAsBoolean();
            returned = true;
            return result;
        } finally {
            recordDirectBlockChange(level, pos, before, eventType, family, boundary,
                    UUID.randomUUID().toString(), sourceMetadata, blockTypeOnly, result, !returned);
        }
    }

    /** Captures one fluid target around FlowingFluid.spreadTo. */
    public static void recordFluidSpread(LevelAccessor level, BlockPos pos, BlockState before,
                                         Direction direction, FluidState fluidState, boolean callbackThrew) {
        String causeEventId = UUID.randomUUID().toString();
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("cause_event_id", causeEventId);
        metadata.put("fluid_id", BuiltInRegistries.FLUID.getKey(fluidState.getType()).toString());
        metadata.put("direction", direction.getName());
        metadata.put("callback_result", callbackThrew ? "THREW" : "RETURNED");
        if (callbackThrew) {
            metadata.put("callback_exception_count", "1");
        }
        recordDirectBlockChange(level, pos, before, "FLUID_BLOCK_CHANGE", "fluid",
                "FlowingFluid.spreadTo." + (callbackThrew ? "throw" : "return"), causeEventId, metadata, false);
        if (callbackThrew && level instanceof ServerLevel serverLevel
                && !submitWorldEffectUnresolved(serverLevel, pos, "fluid", "WORLD_EFFECT_PARTIAL", metadata)) {
            warnQueueRejectionOnce("fluid", pos);
        }
    }

    /** Captures source removal when FallingBlockEntity.fall throws before returning an entity. */
    public static void recordFallingBlockSourceFailure(LevelAccessor level, BlockPos pos, BlockState before,
                                                       BlockState fallingState) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        String causeEventId = UUID.randomUUID().toString();
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("cause_event_id", causeEventId);
        metadata.put("callback_result", "THREW");
        metadata.put("callback_exception_count", "1");
        metadata.put("block_id", BuiltInRegistries.BLOCK.getKey(fallingState.getBlock()).toString());
        recordDirectBlockChange(level, pos, before, "FALLING_BLOCK_CHANGE", "falling_block",
                "FallingBlockEntity.fall.throw", causeEventId, metadata, false);
        if (!submitWorldEffectUnresolved(serverLevel, pos, "falling_block", "WORLD_EFFECT_PARTIAL", metadata)) {
            warnQueueRejectionOnce("falling_block", pos);
        }
    }

    public static BlockState safeSnapshotBlockState(LevelAccessor level, BlockPos pos) {
        try {
            return level instanceof ServerLevel serverLevel && serverLevel.hasChunkAt(pos)
                    ? serverLevel.getBlockState(pos) : null;
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not snapshot world-event block state at {}", pos, captureFailure);
            return null;
        }
    }

    private static boolean submitWorldEffectUnresolved(ServerLevel level, BlockPos pos, String family,
                                                        String reason, Map<String, String> metadata) {
        try {
            Map<String, String> values = new TreeMap<>(metadata);
            values.put("cause_family", family);
            values.putIfAbsent("cause_event_id", UUID.randomUUID().toString());
            return InternalObservationService.getInstance().submitAuditEvent(WorldEventEvidence.create(
                    "WORLD_EFFECT_UNRESOLVED", System.currentTimeMillis(),
                    level.dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(),
                    "minecraft:" + family, WorldEventEvidence.Outcome.UNRESOLVED, reason,
                    family.toUpperCase(java.util.Locale.ROOT), null, null, null, null, null, values));
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not persist unresolved {} world-effect evidence at {} in {}",
                    family, pos, level.dimension().location(), captureFailure);
            return false;
        }
    }

    private static void warnQueueRejectionOnce(String family, BlockPos pos) {
        if (QUEUE_REJECTION_WARNED_FAMILIES.add(family)) {
            LOGGER.error("ItemGraph cannot persist {} world-effect evidence at {}; the bounded audit queue is rejecting records",
                    family, pos);
        }
    }
}
