package com.itemgraph.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads only the explicit event UUID links recorded by administrative/creative mutation capture. */
final class AdminMutationEvidenceLinks {
    private AdminMutationEvidenceLinks() { }

    record ObservationLinks(String evidenceEventId, String mutationEventId, String commandAttemptEventId) { }

    static ObservationLinks observation(byte[] rawData, String actionType) {
        if (!isItemMutation(actionType)) return null;
        JsonObject payload = parse(rawData);
        if (payload == null) return null;
        return new ObservationLinks(string(payload, "event_id"),
                first(string(payload, "mutation_event_id"), string(payload, "parent_event_id")),
                string(payload, "command_attempt_event_id"));
    }

    static String appendAuditDetail(String eventType, String detail, byte[] rawData) {
        if (!isOutcomeEvent(eventType)) return detail;
        JsonObject payload = parse(rawData);
        if (payload == null) return detail;
        List<String> values = new ArrayList<>();
        if (detail != null && !detail.isBlank()) values.add(detail);
        add(values, "evidence_event_id", canonicalUuid(string(payload, "event_id")));
        add(values, "mutation_event_id", first(string(payload, "mutation_event_id"),
                string(payload, "parent_event_id")));
        add(values, "command_attempt_event_id", string(payload, "command_attempt_event_id"));
        add(values, "item_flow_link_status", string(payload, "item_flow_link_status"));
        addEventIds(values, payload, "related_observation_event_ids");
        addEventIds(values, payload, "related_transformation_event_ids");
        addEventIds(values, payload, "related_unresolved_event_ids");
        add(values, "related_observation_event_ids_omitted",
                integer(payload, "related_observation_event_ids_omitted"));
        add(values, "related_transformation_event_ids_omitted",
                integer(payload, "related_transformation_event_ids_omitted"));
        add(values, "related_unresolved_event_ids_omitted",
                integer(payload, "related_unresolved_event_ids_omitted"));
        if ("ADMIN_ITEM_COMMAND_UNRESOLVED".equals(eventType)) {
            add(values, "endpoint_kind", string(payload, "endpoint_kind"));
            add(values, "slot", string(payload, "slot"));
            add(values, "target_player_uuid", string(payload, "target_player_uuid"));
            add(values, "target_player_name", string(payload, "target_player_name"));
            add(values, "target_entity_uuid", string(payload, "target_entity_uuid"));
            add(values, "target_entity_type", string(payload, "target_entity_type"));
            appendStack(values, payload, "before");
            appendStack(values, payload, "after");
        }
        return String.join(" ", values);
    }

    private static void appendStack(List<String> values, JsonObject payload, String key) {
        if (!payload.has(key) || !payload.get(key).isJsonObject()) return;
        JsonObject stack = payload.getAsJsonObject(key);
        if (stack.has("empty") && stack.get("empty").isJsonPrimitive()
                && stack.getAsJsonPrimitive("empty").isBoolean()
                && stack.get("empty").getAsBoolean()) {
            add(values, key, "empty");
            return;
        }
        String item = string(stack, "item_id");
        String count = integer(stack, "count");
        if (item != null && count != null) add(values, key, item + " x" + count);
    }

    private static String canonicalUuid(String value) {
        return isCanonicalUuid(value) ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static void addEventIds(List<String> values, JsonObject payload, String key) {
        if (!payload.has(key) || !payload.get(key).isJsonArray()) return;
        JsonArray ids = payload.getAsJsonArray(key);
        List<String> validIds = new ArrayList<>();
        for (var id : ids) {
            if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                String value = id.getAsString();
                if (isCanonicalUuid(value)) validIds.add(value);
            }
        }
        if (!validIds.isEmpty()) add(values, key, String.join(",", validIds));
    }

    static String appendObservationDetail(String actionType, String detail, byte[] rawData) {
        ObservationLinks links = observation(rawData, actionType);
        if (links == null) return detail;
        List<String> values = new ArrayList<>();
        if (detail != null && !detail.isBlank()) values.add(detail);
        add(values, "evidence_event_id", links.evidenceEventId());
        add(values, "mutation_event_id", links.mutationEventId());
        add(values, "command_attempt_event_id", links.commandAttemptEventId());
        return String.join(" ", values);
    }

    static boolean isCanonicalUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return false;
        }
        try {
            return java.util.UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean isItemMutation(String actionType) {
        return actionType != null && (actionType.startsWith("ADMIN_ITEM_")
                || actionType.startsWith("CREATIVE_ITEM_"));
    }

    private static boolean isOutcomeEvent(String eventType) {
        return eventType != null && (eventType.startsWith("ADMIN_ITEM_COMMAND_")
                || eventType.startsWith("CREATIVE_SLOT_") || eventType.startsWith("CREATIVE_BLOCK_"));
    }

    private static void add(List<String> values, String key, String value) {
        if (value != null && !value.isBlank()) values.add(key + "=" + value);
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

    private static String integer(JsonObject payload, String key) {
        if (!payload.has(key) || !payload.get(key).isJsonPrimitive()
                || !payload.getAsJsonPrimitive(key).isNumber()) return null;
        String value = payload.get(key).getAsString();
        return value.matches("0|[1-9][0-9]*") ? value : null;
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
