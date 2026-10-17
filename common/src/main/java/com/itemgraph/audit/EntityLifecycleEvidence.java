package com.itemgraph.audit;

import com.google.gson.JsonObject;
import com.itemgraph.ingest.InternalObservationService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Locale;

/**
 * Builds immutable audit rows for entity, projectile, and item-entity
 * lifecycle evidence. Rows carry no player actor: the responsible cause is
 * either unknown or lives in the typed actor JSON written from
 * {@code actor_kind}/{@code actor_entity_*}/{@code actor_world_cause}
 * metadata. Rows whose taxonomy semantics are {@code SIGNED_DELTA} must
 * supply the exact observed count; {@code NONE} rows reject a quantity.
 */
public final class EntityLifecycleEvidence {
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_VALUE_LENGTH = 512;
    private static final Set<String> FAMILIES = Set.of("entity_lifecycle", "projectile");

    private EntityLifecycleEvidence() {}

    public static InternalObservationService.InternalAuditEvent create(
            String eventType,
            long timestampMs,
            String dimension,
            double x,
            double y,
            double z,
            String subjectId,
            String subjectUuid,
            Integer quantity,
            String reasonCode,
            String cause,
            String actorUuid,
            String actorName,
            Map<String, String> metadata
    ) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("lifecycle audit event type is required");
        }
        final String canonicalEventType = eventType.toUpperCase(Locale.ROOT);
        EventTaxonomy.Definition definition = EventTaxonomy.find(
                canonicalEventType, EventTaxonomy.Surface.AUDIT_EVENT)
                .orElseThrow(() -> new IllegalArgumentException("unknown lifecycle audit event type: " + canonicalEventType));
        if (!FAMILIES.contains(definition.family())) {
            throw new IllegalArgumentException("event type is not owned by the entity_lifecycle or projectile family: "
                    + canonicalEventType);
        }
        if (timestampMs < 0 || dimension == null || dimension.isBlank() || dimension.length() > MAX_VALUE_LENGTH
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || cause == null || cause.isBlank()) {
            throw new IllegalArgumentException("lifecycle audit event requires a timestamp, dimension, position, and cause");
        }
        switch (definition.quantity()) {
            case SIGNED_DELTA -> {
                if (quantity == null || quantity < 0) {
                    throw new IllegalArgumentException(canonicalEventType
                            + " requires the exact observed item count");
                }
            }
            case NONE -> {
                if (quantity != null) {
                    throw new IllegalArgumentException(canonicalEventType + " cannot carry item quantity");
                }
            }
            default -> {
                if (quantity != null && quantity < 0) {
                    throw new IllegalArgumentException("quantity must be non-negative when supplied");
                }
            }
        }
        boolean unresolved = definition.evidenceClass() == EventTaxonomy.EvidenceClass.UNRESOLVED;
        if (unresolved) {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("unresolved lifecycle evidence requires a reason code");
            }
            String requiredReasonCode = reasonCode;
            if (EventTaxonomy.unresolvedReasonCodes().stream().noneMatch(reason -> reason.id().equals(requiredReasonCode))) {
                throw new IllegalArgumentException("unknown lifecycle reason code: " + requiredReasonCode);
            }
        } else if (reasonCode != null) {
            throw new IllegalArgumentException("a reason code is only valid for unresolved lifecycle evidence");
        }
        if (subjectId != null && (subjectId.isBlank() || subjectId.length() > MAX_VALUE_LENGTH)) {
            throw new IllegalArgumentException("subjectId must be non-blank and at most " + MAX_VALUE_LENGTH + " characters");
        }
        if (subjectUuid != null) {
            UUID.fromString(subjectUuid);
        }
        if (actorUuid != null) {
            UUID.fromString(actorUuid);
        }

        UUID rowId = UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("event_id", rowId.toString());
        payload.addProperty("event_type", canonicalEventType);
        payload.addProperty("evidence_class", definition.evidenceClass().name());
        payload.addProperty("source_reliability", definition.sourceReliability().name());
        payload.addProperty("quantity_semantics", definition.quantity().name());
        putOptional(payload, "reason_code", reasonCode);
        payload.addProperty("cause", boundedRequired(cause, "cause"));
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("dimension", dimension);
        endpoint.addProperty("x", x);
        endpoint.addProperty("y", y);
        endpoint.addProperty("z", z);
        putOptional(endpoint, "subject_id", subjectId);
        putOptional(endpoint, "subject_uuid", subjectUuid);
        payload.add("affected_endpoint", endpoint);
        if (quantity != null) {
            payload.addProperty("quantity", quantity);
        }
        putOptional(payload, "actor_uuid", actorUuid);
        putOptional(payload, "actor_name", actorName);
        if (metadata != null && !metadata.isEmpty()) {
            if (metadata.size() > MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException("lifecycle audit metadata exceeds " + MAX_METADATA_ENTRIES + " entries");
            }
            JsonObject values = new JsonObject();
            Map<String, String> orderedMetadata = new TreeMap<>(metadata);
            addActor(payload, orderedMetadata);
            orderedMetadata.forEach((key, value) -> {
                String safeKey = boundedRequired(key, "metadata key");
                if (value != null) {
                    values.addProperty(safeKey, boundedRequired(value, "metadata value"));
                }
            });
            payload.add("metadata", values);
        }

        byte[] rawData = payload.toString().getBytes(StandardCharsets.UTF_8);
        String detail = payload.toString();
        return new InternalObservationService.InternalAuditEvent(
                timestampMs,
                canonicalEventType,
                actorUuid,
                actorName,
                dimension,
                x,
                y,
                z,
                subjectId,
                detail,
                rawData,
                null,
                rowId.toString(),
                java.util.List.of());
    }

    /** Writes the typed actor object; kind WORLD carries the damage-type cause. */
    private static void addActor(JsonObject payload, Map<String, String> metadata) {
        String actorKind = metadata.get("actor_kind");
        if (actorKind == null) {
            return;
        }
        JsonObject actor = new JsonObject();
        actor.addProperty("kind", boundedRequired(actorKind, "actor kind"));
        putOptional(actor, "entity_uuid", metadata.get("actor_entity_uuid"));
        putOptional(actor, "entity_type", metadata.get("actor_entity_type"));
        putOptional(actor, "cause", metadata.get("actor_world_cause"));
        payload.add("actor", actor);
    }

    private static void putOptional(JsonObject target, String key, String value) {
        if (value != null) {
            target.addProperty(key, boundedRequired(value, key));
        }
    }

    private static String boundedRequired(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(label + " must be non-blank and at most " + MAX_VALUE_LENGTH + " characters");
        }
        return value;
    }
}
