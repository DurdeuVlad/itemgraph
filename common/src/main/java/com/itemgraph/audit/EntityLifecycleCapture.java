package com.itemgraph.audit;

import com.itemgraph.ingest.InternalObservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared submission path for entity, projectile, and item-entity lifecycle
 * audit rows on both loaders. Loader hooks pass already-extracted facts
 * (registry IDs, UUIDs, positions, removal/impact causes); this class owns
 * actor metadata shaping, bounded queue submission, and the unresolved
 * fallback used when a callback cannot produce complete evidence.
 */
public final class EntityLifecycleCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntityLifecycleCapture.class);
    private static final AtomicBoolean WARNED_QUEUE_REJECTION = new AtomicBoolean();

    private EntityLifecycleCapture() {}

    /** Typed-entity actor metadata rendered as {@code entity:<type> id=<uuid>} in lookup output. */
    public static Map<String, String> entityActor(String entityUuid, String entityType) {
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("actor_kind", "ENTITY");
        metadata.put("actor_entity_uuid", entityUuid);
        metadata.put("actor_entity_type", entityType);
        return metadata;
    }

    /** Non-entity cause metadata (lava, fall damage, environment) rendered as a world cause. */
    public static Map<String, String> worldActor(String worldCause) {
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("actor_kind", "WORLD");
        metadata.put("actor_world_cause", worldCause);
        return metadata;
    }

    /**
     * Submits one lifecycle audit row. {@code quantity} is required when the
     * taxonomy definition is {@code SIGNED_DELTA} and forbidden when it is
     * {@code NONE}; {@code cause} names the capture boundary
     * (e.g. {@code entity_join_level}, {@code entity_removal},
     * {@code projectile_impact}). Submission rejection or builder failure
     * emits {@code ENTITY_LIFECYCLE_UNRESOLVED} instead of dropping evidence.
     */
    public static void submit(String eventType, String dimension, double x, double y, double z,
                              String subjectId, String subjectUuid, Integer quantity,
                              String cause, Map<String, String> metadata) {
        submit(eventType, dimension, x, y, z, subjectId, subjectUuid, quantity,
                cause, null, null, metadata);
    }

    /**
     * Same as {@link #submit} but records a known player actor on the row —
     * used when an authoritative event supplies the owning player (for
     * example a player-shot projectile launch).
     */
    public static void submit(String eventType, String dimension, double x, double y, double z,
                              String subjectId, String subjectUuid, Integer quantity,
                              String cause, String actorUuid, String actorName,
                              Map<String, String> metadata) {
        Map<String, String> safeMetadata = new TreeMap<>(metadata == null ? Map.of() : metadata);
        try {
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                    EntityLifecycleEvidence.create(eventType, System.currentTimeMillis(), dimension,
                            x, y, z, subjectId, subjectUuid, quantity, null, cause,
                            actorUuid, actorName, safeMetadata));
            if (!accepted) {
                safeMetadata.put("queue_rejected_count", "1");
                submitUnresolved(dimension, x, y, z, subjectId, subjectUuid, cause, safeMetadata);
            }
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not record {} lifecycle evidence at {},{},{} in {}",
                    eventType, x, y, z, dimension, captureFailure);
            safeMetadata.put("result_failure_count", "1");
            submitUnresolved(dimension, x, y, z, subjectId, subjectUuid, cause, safeMetadata);
        }
    }

    /** Emits the unresolved lifecycle row; only queue loss can still drop it. */
    private static void submitUnresolved(String dimension, double x, double y, double z,
                                         String subjectId, String subjectUuid, String cause,
                                         Map<String, String> metadata) {
        try {
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                    EntityLifecycleEvidence.create("ENTITY_LIFECYCLE_UNRESOLVED",
                            System.currentTimeMillis(), dimension, x, y, z, subjectId, subjectUuid,
                            null, "ENTITY_CAUSE_NOT_REPORTED", cause, null, null, metadata));
            if (!accepted) {
                warnQueueRejectionOnce(dimension, x, y, z);
            }
        } catch (RuntimeException unresolvedFailure) {
            LOGGER.error("Could not record unresolved lifecycle evidence at {},{},{} in {}",
                    x, y, z, dimension, unresolvedFailure);
        }
    }

    /** Direct unresolved submission for callbacks that cannot establish a complete outcome. */
    public static void submitUnresolved(String dimension, double x, double y, double z,
                                        String subjectId, String subjectUuid, String cause,
                                        String reasonCode, Map<String, String> metadata) {
        try {
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                    EntityLifecycleEvidence.create("ENTITY_LIFECYCLE_UNRESOLVED",
                            System.currentTimeMillis(), dimension, x, y, z, subjectId, subjectUuid,
                            null, reasonCode, cause, null, null, new TreeMap<>(metadata)));
            if (!accepted) {
                warnQueueRejectionOnce(dimension, x, y, z);
            }
        } catch (RuntimeException unresolvedFailure) {
            LOGGER.error("Could not record unresolved lifecycle evidence at {},{},{} in {}",
                    x, y, z, dimension, unresolvedFailure);
        }
    }

    private static void warnQueueRejectionOnce(String dimension, double x, double y, double z) {
        if (WARNED_QUEUE_REJECTION.compareAndSet(false, true)) {
            LOGGER.error("ItemGraph rejected lifecycle audit evidence at {},{},{} in {}",
                    x, y, z, dimension);
        }
    }
}
