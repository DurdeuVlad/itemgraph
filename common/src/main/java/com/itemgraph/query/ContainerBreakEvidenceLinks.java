package com.itemgraph.query;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Whitelisted event identities exposed by ItemGraph's container-break evidence. */
final class ContainerBreakEvidenceLinks {
    private ContainerBreakEvidenceLinks() { }

    record ObservationLinks(String eventId, String parentEventId, String breakEventId) { }

    static ObservationLinks observation(byte[] rawData) {
        JsonObject payload = parse(rawData);
        if (payload == null || !"player_container_break_contents".equals(string(payload, "capture"))) {
            return null;
        }
        return new ObservationLinks(string(payload, "event_id"), string(payload, "cause_event_id"),
                string(payload, "break_event_id"));
    }

    static String appendObservationDetail(String detail, byte[] rawData) {
        ObservationLinks links = observation(rawData);
        if (links == null) return detail;
        return append(detail, "evidence_event_id", links.eventId(),
                "parent_event_id", links.parentEventId(), "break_event_id", links.breakEventId());
    }

    static String appendAuditDetail(String eventType, String detail, byte[] rawData) {
        if (eventType == null || !eventType.startsWith("CONTAINER_BREAK_")) return detail;
        JsonObject payload = parse(rawData);
        if (payload == null) return detail;
        return append(detail, "evidence_event_id", string(payload, "event_id"),
                "parent_event_id", string(payload, "parent_event_id"),
                "break_event_id", string(payload, "break_event_id"),
                "reason_code", string(payload, "reason_code"));
    }

    private static String append(String detail, String... keyValues) {
        List<String> values = new ArrayList<>();
        if (detail != null && !detail.isBlank()) values.add(detail);
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null && !keyValues[i + 1].isBlank()) {
                values.add(keyValues[i] + "=" + keyValues[i + 1]);
            }
        }
        return String.join(" ", values);
    }

    private static JsonObject parse(byte[] rawData) {
        if (rawData == null) return null;
        try {
            return JsonParser.parseString(new String(rawData, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String string(JsonObject payload, String key) {
        return payload.has(key) && payload.get(key).isJsonPrimitive()
                && payload.getAsJsonPrimitive(key).isString() ? payload.get(key).getAsString() : null;
    }
}
