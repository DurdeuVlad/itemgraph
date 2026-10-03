package com.itemgraph.audit;

import com.google.gson.JsonObject;
import com.itemgraph.ingest.InternalObservationService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Locale;

/** Builds immutable, non-quantity audit rows for world and environmental causes. */
public final class WorldEventEvidence {
    private static final int MAX_METADATA_ENTRIES = 32;
    private static final int MAX_VALUE_LENGTH = 512;

    public enum Outcome {
        CONFIRMED_CHANGE(EventTaxonomy.EvidenceClass.OBSERVED),
        CANDIDATE(EventTaxonomy.EvidenceClass.AMBIGUOUS),
        CANCELLED(EventTaxonomy.EvidenceClass.OBSERVED),
        UNCHANGED(EventTaxonomy.EvidenceClass.OBSERVED),
        PARTIAL(EventTaxonomy.EvidenceClass.AMBIGUOUS),
        UNRESOLVED(EventTaxonomy.EvidenceClass.UNRESOLVED);

        private final EventTaxonomy.EvidenceClass evidenceClass;

        Outcome(EventTaxonomy.EvidenceClass evidenceClass) {
            this.evidenceClass = evidenceClass;
        }
    }

    private WorldEventEvidence() {}

    public static InternalObservationService.InternalAuditEvent create(
            String eventType,
            long timestampMs,
            String dimension,
            double x,
            double y,
            double z,
            String subjectId,
            Outcome outcome,
            String reasonCode,
            String cause,
            String beforeState,
            String afterState,
            String actorUuid,
            String actorName,
            Long sourceEventId,
            Map<String, String> metadata
    ) {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("world audit event type is required");
        }
        final String canonicalEventType = eventType.toUpperCase(Locale.ROOT);
        EventTaxonomy.Definition definition = EventTaxonomy.find(
                canonicalEventType, EventTaxonomy.Surface.AUDIT_EVENT)
                .orElseThrow(() -> new IllegalArgumentException("unknown world audit event type: " + canonicalEventType));
        if (!"world_environment".equals(definition.family())) {
            throw new IllegalArgumentException("event type is not owned by the world_environment family: " + canonicalEventType);
        }
        if (definition.quantity() != EventTaxonomy.QuantitySemantics.NONE) {
            throw new IllegalArgumentException("world audit events cannot carry item quantity");
        }
        if (timestampMs < 0 || dimension == null || dimension.isBlank() || dimension.length() > MAX_VALUE_LENGTH
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || outcome == null || cause == null || cause.isBlank()) {
            throw new IllegalArgumentException("world audit event requires a timestamp, dimension, position, outcome, and cause");
        }
        if (outcome.evidenceClass != definition.evidenceClass()) {
            throw new IllegalArgumentException("outcome " + outcome + " conflicts with taxonomy evidence class "
                    + definition.evidenceClass() + " for " + canonicalEventType);
        }
        if (definition.sourceReliability() == EventTaxonomy.SourceReliability.DIRECT_STATE_DELTA
                && (outcome != Outcome.CONFIRMED_CHANGE || beforeState == null || afterState == null
                || beforeState.equals(afterState))) {
            throw new IllegalArgumentException("direct state-delta evidence requires a confirmed, non-empty state change for "
                    + canonicalEventType);
        }
        if (outcome == Outcome.UNRESOLVED || outcome == Outcome.CANDIDATE || outcome == Outcome.PARTIAL) {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("unresolved, candidate, and partial world evidence requires a reason code");
            }
            String requiredReasonCode = reasonCode;
            if (EventTaxonomy.unresolvedReasonCodes().stream().noneMatch(reason -> reason.id().equals(requiredReasonCode))) {
                throw new IllegalArgumentException("unknown world-event reason code: " + requiredReasonCode);
            }
        } else if (reasonCode != null) {
            throw new IllegalArgumentException("a reason code is only valid for unresolved, candidate, or partial evidence");
        }
        if (subjectId != null && (subjectId.isBlank() || subjectId.length() > MAX_VALUE_LENGTH)) {
            throw new IllegalArgumentException("subjectId must be non-blank and at most " + MAX_VALUE_LENGTH + " characters");
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
        payload.addProperty("outcome", outcome.name());
        putOptional(payload, "reason_code", reasonCode);
        payload.addProperty("cause", boundedRequired(cause, "cause"));
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("dimension", dimension);
        endpoint.addProperty("x", x);
        endpoint.addProperty("y", y);
        endpoint.addProperty("z", z);
        putOptional(endpoint, "subject_id", subjectId);
        payload.add("affected_endpoint", endpoint);
        putOptional(payload, "before_state", beforeState);
        putOptional(payload, "after_state", afterState);
        putOptional(payload, "actor_uuid", actorUuid);
        putOptional(payload, "actor_name", actorName);
        if (metadata != null && !metadata.isEmpty()) {
            if (metadata.size() > MAX_METADATA_ENTRIES) {
                throw new IllegalArgumentException("world audit metadata exceeds " + MAX_METADATA_ENTRIES + " entries");
            }
            JsonObject values = new JsonObject();
            new TreeMap<>(metadata).forEach((key, value) -> {
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
                sourceEventId,
                rowId.toString(),
                java.util.List.of());
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
