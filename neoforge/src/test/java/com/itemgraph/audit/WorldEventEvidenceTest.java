package com.itemgraph.audit;

import com.google.gson.JsonParser;
import com.itemgraph.audit.WorldEventEvidence.Outcome;
import com.itemgraph.ingest.InternalObservationService.InternalAuditEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorldEventEvidenceTest {
    @Test
    void createsNonQuantityEvidenceWithUnknownActorAndStableRowIdentity() {
        InternalAuditEvent event = WorldEventEvidence.create(
                "explosion_block_change", 1234L, "minecraft:overworld",
                10.0, 64.0, -3.0, "minecraft:stone", Outcome.CONFIRMED_CHANGE,
                null,
                "minecraft:creeper", "minecraft:stone", "minecraft:air",
                null, null, 77L, Map.of("explosion_source", "minecraft:creeper"));

        var payload = JsonParser.parseString(new String(event.rawData(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("EXPLOSION_BLOCK_CHANGE", event.eventType());
        assertEquals("minecraft:overworld", event.levelName());
        assertEquals("minecraft:stone", event.subjectId());
        assertNull(event.playerUuid(), "environmental evidence must not invent a player actor");
        assertNull(event.playerName(), "environmental evidence must not invent a player name");
        assertEquals("NONE", payload.get("quantity_semantics").getAsString());
        assertEquals("CONFIRMED_CHANGE", payload.get("outcome").getAsString());
        assertFalse(payload.has("reason_code"));
        assertEquals("minecraft:stone", payload.get("before_state").getAsString());
        assertEquals("minecraft:air", payload.get("after_state").getAsString());
        assertEquals("minecraft:creeper", payload.getAsJsonObject("metadata")
                .get("explosion_source").getAsString());
        assertEquals(UUID.fromString(event.ingestEventUuid()).toString(),
                payload.get("event_id").getAsString());
        assertEquals(77L, event.sourceEventId());
        assertEquals("minecraft:overworld", payload.getAsJsonObject("affected_endpoint")
                .get("dimension").getAsString());
    }

    @Test
    void rejectsMissingCauseNonFinitePositionAndOversizedMetadata() {
        Map<String, String> oversizedMetadata = new HashMap<>();
        for (int i = 0; i <= 32; i++) {
            oversizedMetadata.put("key_" + i, "value");
        }
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "WORLD_EFFECT_UNRESOLVED", 1L, "minecraft:overworld", 0, 0, 0,
                null, Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL", " ", null, null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "WORLD_EFFECT_UNRESOLVED", 1L, "minecraft:overworld", Double.NaN, 0, 0,
                null, Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL", "unknown", null, null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "WORLD_EFFECT_UNRESOLVED", 1L, "minecraft:overworld", 0, 0, 0,
                null, Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL", "unknown", null, null, null, null, null,
                oversizedMetadata));
    }

    @Test
    void refusesUnknownOrQuantityBearingEventTypes() {
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "NOT_A_WORLD_EVENT", 1L, "minecraft:overworld", 0, 0, 0,
                null, Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL", "unknown", null, null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "ADD_ITEM", 1L, "minecraft:overworld", 0, 0, 0,
                null, Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL", "unknown", null, null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "EXPLOSION_BLOCK_CHANGE", 1L, "minecraft:overworld", 0, 0, 0,
                null, Outcome.UNRESOLVED, "WORLD_EFFECT_PARTIAL", "explosion", null, null,
                null, null, null, Map.of()));
    }

    @Test
    void directStateDeltaTypesRequireAConfirmedNonEmptyStateChange() {
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "PISTON_BLOCK_MOVE", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:piston", Outcome.UNCHANGED, null, "piston", null, null,
                null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> WorldEventEvidence.create(
                "PISTON_BLOCK_MOVE", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:piston", Outcome.CONFIRMED_CHANGE, null, "piston", "same", "same",
                null, null, null, Map.of()));

        InternalAuditEvent attempt = WorldEventEvidence.create(
                "PISTON_BLOCK_ATTEMPT", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:piston", Outcome.UNCHANGED, null, "piston", null, null,
                null, null, null, Map.of());
        var payload = JsonParser.parseString(new String(attempt.rawData(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("GAME_CALLBACK_ATTEMPT", payload.get("source_reliability").getAsString());
        assertEquals("UNCHANGED", payload.get("outcome").getAsString());
        assertFalse(payload.has("before_state"));
        assertFalse(payload.has("after_state"));
    }
}
