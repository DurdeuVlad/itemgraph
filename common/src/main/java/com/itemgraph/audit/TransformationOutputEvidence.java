package com.itemgraph.audit;

import com.google.gson.JsonObject;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Builds unresolved, result-only evidence when a loader cannot observe every input. */
public final class TransformationOutputEvidence {
    public static final String REASON_CODE = "TRANSFORMATION_INPUTS_NOT_OBSERVED";
    private static final Set<String> EVENT_TYPES = Set.of("CRAFT_OUTPUT_UNRESOLVED", "SMELT_OUTPUT_UNRESOLVED");
    private static final int MAX_VALUE_LENGTH = 512;

    private TransformationOutputEvidence() { }

    public static InternalObservationService.InternalAuditEvent create(
            String eventType,
            long timestampMs,
            String playerUuid,
            String playerName,
            String dimension,
            double x,
            double y,
            double z,
            CanonicalItem output,
            int outputQuantity,
            String captureBoundary
    ) {
        String type = required(eventType, "event type").toUpperCase(Locale.ROOT);
        if (!EVENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported result-only transformation type: " + type);
        }
        EventTaxonomy.Definition definition = EventTaxonomy.find(type, EventTaxonomy.Surface.AUDIT_EVENT)
                .orElseThrow(() -> new IllegalArgumentException("missing taxonomy entry: " + type));
        if (definition.evidenceClass() != EventTaxonomy.EvidenceClass.UNRESOLVED
                || definition.sourceReliability() != EventTaxonomy.SourceReliability.UNRESOLVED_CAUSE
                || definition.privacy() != EventTaxonomy.PrivacyClass.SENSITIVE_LOCATION) {
            throw new IllegalArgumentException("result-only transformation taxonomy must be unresolved and protected");
        }
        if (EventTaxonomy.unresolvedReasonCodes().stream().noneMatch(reason -> REASON_CODE.equals(reason.id()))) {
            throw new IllegalArgumentException("missing unresolved reason code: " + REASON_CODE);
        }
        if (timestampMs < 0 || output == null || output.itemId() == null || output.itemId().isBlank()
                || output.fingerprintHash() == null || output.fingerprintHash().isBlank() || outputQuantity <= 0
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("result-only transformation requires a valid output, quantity, time, and position");
        }
        String safeDimension = required(dimension, "dimension");
        String safeBoundary = required(captureBoundary, "capture boundary");
        String canonicalPlayerUuid = playerUuid == null ? null : UUID.fromString(playerUuid).toString();

        UUID eventId = UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("event_id", eventId.toString());
        payload.addProperty("event_type", type);
        payload.addProperty("evidence_class", EventTaxonomy.EvidenceClass.UNRESOLVED.name());
        payload.addProperty("source_reliability", EventTaxonomy.SourceReliability.UNRESOLVED_CAUSE.name());
        payload.addProperty("reason_code", REASON_CODE);
        payload.addProperty("quantity_semantics", EventTaxonomy.QuantitySemantics.UNKNOWN.name());
        payload.addProperty("capture_boundary", safeBoundary);
        JsonObject actor = new JsonObject();
        if (canonicalPlayerUuid != null) actor.addProperty("uuid", canonicalPlayerUuid);
        if (playerName != null && !playerName.isBlank()) actor.addProperty("name", bounded(playerName, "player name"));
        payload.add("actor", actor);
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("dimension", safeDimension);
        endpoint.addProperty("x", x);
        endpoint.addProperty("y", y);
        endpoint.addProperty("z", z);
        payload.add("endpoint", endpoint);
        JsonObject result = new JsonObject();
        result.addProperty("item_id", bounded(output.itemId(), "output item ID"));
        result.addProperty("fingerprint_hash", bounded(output.fingerprintHash(), "output fingerprint"));
        result.addProperty("quantity", outputQuantity);
        putOptional(result, "custom_name", output.customName());
        putOptional(result, "rarity", output.rarity());
        putOptional(result, "component_summary", output.componentSummary());
        payload.add("observed_output", result);

        String detail = type + ": output=" + output.itemId() + " quantity=" + outputQuantity
                + " fingerprint=" + output.fingerprintHash()
                + " input=UNKNOWN lineage=NOT_ESTABLISHED evidence=UNRESOLVED reason=" + REASON_CODE;
        detail += " evidence_event_id=" + eventId;
        if (output.customName() != null) detail += " custom_name=" + bounded(output.customName(), "custom name");
        if (output.rarity() != null) detail += " rarity=" + bounded(output.rarity(), "rarity");
        if (output.componentSummary() != null) detail += " components=" + bounded(output.componentSummary(), "component summary");
        return new InternalObservationService.InternalAuditEvent(timestampMs, type,
                canonicalPlayerUuid, playerName, safeDimension, x, y, z, output.itemId(), detail,
                payload.toString().getBytes(StandardCharsets.UTF_8), null, eventId.toString(),
                java.util.List.of());
    }

    private static void putOptional(JsonObject target, String key, String value) {
        if (value != null && !value.isBlank()) target.addProperty(key, bounded(value, key));
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        return bounded(value, label);
    }

    private static String bounded(String value, String label) {
        if (value.length() <= MAX_VALUE_LENGTH) return value;
        String marker = "…[truncated]";
        return value.substring(0, MAX_VALUE_LENGTH - marker.length()) + marker;
    }
}
