package com.itemgraph.audit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Builds validated input/output transformation evidence for the item-processing
 * stations promoted by issue #57 (trade, enchanting, brewing, smithing,
 * grindstone, loot generation).
 *
 * <p>Every record carries canonical item endpoints plus a bounded JSON detail
 * payload describing the station, outcome, observed inputs, and any explicitly
 * unobserved inputs. Cancelled operations never appear as successful effects:
 * they persist with {@code outcome=CANCELLED}, zero output quantity, and the
 * {@value EventTaxonomy#REASON_TRANSFORMATION_CANCELLED} reason code.
 *
 * <p>An absent item endpoint (loot-table generation has no item input; a
 * cancelled operation has no output) is encoded with the
 * {@link #NON_ITEM_ENDPOINT} sentinel — an explicit non-item endpoint marker,
 * never a fabricated item.
 */
public final class TransformationEvidence {

    public static final String REASON_CANCELLED = "TRANSFORMATION_CANCELLED";

    private static final Set<String> EVENT_TYPES = Set.of(
            "TRADE", "ENCHANTING", "BREWING", "SMITHING", "GRINDSTONE", "LOOT_GENERATION");
    private static final int MAX_VALUE_LENGTH = 512;
    private static final int MAX_INPUTS = 8;
    private static final int MAX_EXTRA_DETAILS = 8;

    /** Sentinel endpoint for "no item on this endpoint" (loot inputs, cancelled outputs). */
    public static final CanonicalItem NON_ITEM_ENDPOINT = new CanonicalItem(
            "itemgraph:non_item_endpoint",
            ItemCanonicalizer.sha256Hex("id=itemgraph:non_item_endpoint"),
            "(non-item endpoint)", null, null);

    public enum Outcome { COMPLETED, CANCELLED, PARTIAL }

    /** One observed stack on an input or output endpoint. */
    public record ItemAmount(CanonicalItem item, int quantity) {
        public ItemAmount {
            if (item == null || item.itemId() == null || item.itemId().isBlank()
                    || item.fingerprintHash() == null || item.fingerprintHash().isBlank()) {
                throw new IllegalArgumentException("transformation endpoints require a canonical item");
            }
            if (quantity <= 0) {
                throw new IllegalArgumentException("transformation endpoint quantity must be positive");
            }
        }
    }

    private TransformationEvidence() { }

    /**
     * Builds a validated transformation record. {@code inputs} may be empty for
     * transformations with no observed item inputs (loot generation). Pass
     * {@code unobservedInputs=true} when the boundary cannot observe the full
     * input set — the record then stays honest about hidden inputs instead of
     * pretending completeness.
     */
    public static InternalObservationService.InternalTransformation create(
            String eventType,
            long timestampMs,
            String playerUuid,
            String playerName,
            String dimension,
            double x,
            double y,
            double z,
            List<ItemAmount> inputs,
            boolean unobservedInputs,
            ItemAmount output,
            Outcome outcome,
            String station,
            String captureBoundary,
            JsonObject extraDetails
    ) {
        return create(eventType, timestampMs, playerUuid, playerName, dimension, x, y, z,
                inputs, unobservedInputs, output, false, outcome, station, captureBoundary, extraDetails);
    }

    /**
     * Full form. {@code unobservedOutput=true} permits a completed operation
     * whose result endpoint the boundary cannot expose (e.g. the NeoForge
     * grindstone take event carries only the consumed inputs and XP); the
     * result then persists as the non-item endpoint with an explicit
     * {@code unobserved_output} detail instead of a fabricated stack.
     */
    public static InternalObservationService.InternalTransformation create(
            String eventType,
            long timestampMs,
            String playerUuid,
            String playerName,
            String dimension,
            double x,
            double y,
            double z,
            List<ItemAmount> inputs,
            boolean unobservedInputs,
            ItemAmount output,
            boolean unobservedOutput,
            Outcome outcome,
            String station,
            String captureBoundary,
            JsonObject extraDetails
    ) {
        String type = required(eventType, "event type").toUpperCase(Locale.ROOT);
        if (!EVENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("unsupported transformation type: " + type);
        }
        EventTaxonomy.Definition definition = EventTaxonomy.find(type, EventTaxonomy.Surface.TRANSFORMATION)
                .orElseThrow(() -> new IllegalArgumentException("missing taxonomy entry: " + type));
        if (definition.privacy() == null) {
            throw new IllegalArgumentException("transformation taxonomy must declare a privacy class");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("transformation outcome is required");
        }
        if (timestampMs < 0 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("transformation requires a valid time and position");
        }
        String safeDimension = required(dimension, "dimension");
        String safeStation = bounded(required(station, "station"), "station");
        String safeBoundary = bounded(required(captureBoundary, "capture boundary"), "capture boundary");
        List<ItemAmount> safeInputs = inputs == null ? List.of() : List.copyOf(inputs);
        if (safeInputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException("transformation inputs exceed bound " + MAX_INPUTS);
        }
        if (outcome == Outcome.COMPLETED && output == null && !unobservedOutput) {
            throw new IllegalArgumentException("completed transformation requires an output endpoint");
        }

        CanonicalItem source = safeInputs.isEmpty() ? NON_ITEM_ENDPOINT : safeInputs.get(0).item();
        CanonicalItem result = outcome == Outcome.CANCELLED || output == null
                ? NON_ITEM_ENDPOINT : output.item();
        int quantity = outcome == Outcome.CANCELLED || output == null ? 0 : output.quantity();

        JsonObject detail = new JsonObject();
        detail.addProperty("outcome", outcome.name());
        detail.addProperty("station", safeStation);
        detail.addProperty("capture_boundary", safeBoundary);
        if (outcome == Outcome.CANCELLED) {
            detail.addProperty("reason_code", REASON_CANCELLED);
        }
        if (unobservedInputs) {
            detail.addProperty("unobserved_inputs", true);
            detail.addProperty("reason_code", outcome == Outcome.CANCELLED
                    ? REASON_CANCELLED : "TRANSFORMATION_INPUTS_NOT_OBSERVED");
        }
        JsonArray inputArray = new JsonArray();
        for (ItemAmount input : safeInputs) {
            JsonObject in = new JsonObject();
            in.addProperty("item", input.item().itemId());
            in.addProperty("fingerprint", input.item().fingerprintHash());
            in.addProperty("qty", input.quantity());
            if (input.item().customName() != null) {
                in.addProperty("name", bounded(input.item().customName(), "input name"));
            }
            inputArray.add(in);
        }
        detail.add("inputs", inputArray);
        if (unobservedOutput && outcome != Outcome.CANCELLED) {
            detail.addProperty("unobserved_output", true);
        }
        if (output != null && outcome != Outcome.CANCELLED) {
            JsonObject out = new JsonObject();
            out.addProperty("item", output.item().itemId());
            out.addProperty("fingerprint", output.item().fingerprintHash());
            out.addProperty("qty", output.quantity());
            if (output.item().customName() != null) {
                out.addProperty("name", bounded(output.item().customName(), "output name"));
            }
            detail.add("output", out);
        }
        if (extraDetails != null) {
            if (extraDetails.size() > MAX_EXTRA_DETAILS) {
                throw new IllegalArgumentException("extra transformation details exceed bound");
            }
            for (String key : extraDetails.keySet()) {
                if (detail.has(key)) {
                    continue;
                }
                var value = extraDetails.get(key);
                detail.add(bounded(key, "detail key"), value);
            }
        }

        return new InternalObservationService.InternalTransformation(
                timestampMs, type, playerUuid, playerName, safeDimension, x, y, z,
                source, result, quantity, detail.toString());
    }

    /**
     * Unresolved audit fallback for a transformation whose evidence could not be
     * submitted (queue rejection) or whose callback failed — preserves the fact
     * that a station effect was observed but not persisted as a transformation.
     */
    public static InternalObservationService.InternalAuditEvent createUnresolved(
            long timestampMs,
            String playerUuid,
            String playerName,
            String dimension,
            double x,
            double y,
            double z,
            String station,
            String reason
    ) {
        UUID eventId = UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("event_id", eventId.toString());
        payload.addProperty("event_type", "TRANSFORMATION_UNRESOLVED");
        payload.addProperty("evidence_class", EventTaxonomy.EvidenceClass.UNRESOLVED.name());
        payload.addProperty("outcome", "UNRESOLVED");
        payload.addProperty("reason_code", "TRANSFORMATION_EVIDENCE_NOT_SUBMITTED");
        payload.addProperty("station", bounded(station == null ? "unknown" : station, "station"));
        payload.addProperty("reason", bounded(reason == null ? "unknown" : reason, "reason"));
        return new InternalObservationService.InternalAuditEvent(
                timestampMs, "TRANSFORMATION_UNRESOLVED", playerUuid, playerName,
                required(dimension, "dimension"), x, y, z,
                "transformation:" + eventId,
                payload.toString(),
                payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String bounded(String value, String label) {
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(label + " exceeds " + MAX_VALUE_LENGTH + " chars");
        }
        return value;
    }
}
