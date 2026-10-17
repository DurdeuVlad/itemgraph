package com.itemgraph.audit;

import com.google.gson.JsonParser;
import com.itemgraph.ingest.InternalObservationService.InternalAuditEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EntityLifecycleEvidenceTest {
    @Test
    void itemEntitySpawnCarriesExactObservedQuantityAndSubject() {
        String entityUuid = UUID.randomUUID().toString();
        InternalAuditEvent event = EntityLifecycleEvidence.create(
                "item_entity_spawn", 1234L, "minecraft:overworld",
                10.0, 64.0, -3.0, "minecraft:diamond_sword", entityUuid, 12,
                null, "entity_join_level", null, null,
                Map.of("subject_entity_type", "minecraft:item"));

        var payload = JsonParser.parseString(new String(event.rawData(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("ITEM_ENTITY_SPAWN", event.eventType());
        assertNull(event.playerUuid(), "lifecycle evidence must not invent a player actor");
        assertEquals("SIGNED_DELTA", payload.get("quantity_semantics").getAsString());
        assertEquals(12, payload.get("quantity").getAsInt());
        assertEquals(entityUuid, payload.getAsJsonObject("affected_endpoint")
                .get("subject_uuid").getAsString());
        assertEquals("minecraft:diamond_sword", payload.getAsJsonObject("affected_endpoint")
                .get("subject_id").getAsString());
        assertEquals("entity_join_level", payload.get("cause").getAsString());
        assertEquals("OBSERVED", payload.get("evidence_class").getAsString());
        assertFalse(payload.has("reason_code"));
    }

    @Test
    void nonQuantityLifecycleRowsRejectQuantityAndUnknownEventTypesFail() {
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "ENTITY_SPAWN", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:zombie", null, 4, null, "entity_join_level",
                null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "ITEM_ENTITY_DESPAWN", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:stone", null, null, null, "entity_removal",
                null, null, Map.of()),
                "signed-delta rows cannot silently drop the observed count");
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "BREAK_BLOCK", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:stone", null, null, null, "entity_removal",
                null, null, Map.of()),
                "rows outside the lifecycle/projectile families are rejected");
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "NOT_A_REAL_EVENT", 1L, "minecraft:overworld", 0, 0, 0,
                null, null, null, null, "entity_removal", null, null, Map.of()));
    }

    @Test
    void unresolvedRowsRequireARegisteredReasonCode() {
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "ENTITY_LIFECYCLE_UNRESOLVED", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:zombie", null, null, null, "entity_removal", null, null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "ENTITY_LIFECYCLE_UNRESOLVED", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:zombie", null, null, "NOT_A_REASON", "entity_removal",
                null, null, Map.of()));

        InternalAuditEvent unresolved = EntityLifecycleEvidence.create(
                "ENTITY_LIFECYCLE_UNRESOLVED", 2L, "minecraft:overworld", 1, 64, 1,
                "minecraft:zombie", null, null, "ENTITY_CAUSE_NOT_REPORTED", "entity_removal",
                null, null, Map.of());
        var payload = JsonParser.parseString(new String(unresolved.rawData(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("UNRESOLVED", payload.get("evidence_class").getAsString());
        assertEquals("ENTITY_CAUSE_NOT_REPORTED", payload.get("reason_code").getAsString());
        assertEquals("UNRESOLVED_CAUSE", payload.get("source_reliability").getAsString());
    }

    @Test
    void observedRowsRejectReasonCodesAndEntityActorsStayTyped() {
        assertThrows(IllegalArgumentException.class, () -> EntityLifecycleEvidence.create(
                "ENTITY_KILL", 1L, "minecraft:overworld", 0, 0, 0,
                "minecraft:zombie", null, null, "ENTITY_CAUSE_NOT_REPORTED", "living_death",
                null, null, Map.of()));

        String killerUuid = UUID.randomUUID().toString();
        Map<String, String> metadata = new HashMap<>(EntityLifecycleCapture.entityActor(
                killerUuid, "minecraft:skeleton"));
        InternalAuditEvent kill = EntityLifecycleEvidence.create(
                "ENTITY_KILL", 3L, "minecraft:overworld", 1, 64, 1,
                "minecraft:zombie", UUID.randomUUID().toString(), null, null, "living_death",
                null, null, metadata);
        var payload = JsonParser.parseString(new String(kill.rawData(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertNull(kill.playerUuid(), "an entity killer must not be stored as a player");
        assertEquals("ENTITY", payload.getAsJsonObject("actor").get("kind").getAsString());
        assertEquals("minecraft:skeleton", payload.getAsJsonObject("actor")
                .get("entity_type").getAsString());
        assertEquals(killerUuid, payload.getAsJsonObject("actor").get("entity_uuid").getAsString());

        InternalAuditEvent worldKill = EntityLifecycleEvidence.create(
                "ENTITY_KILL", 4L, "minecraft:overworld", 1, 64, 1,
                "minecraft:zombie", null, null, null, "living_death", null, null,
                EntityLifecycleCapture.worldActor("minecraft:lava"));
        var worldPayload = JsonParser.parseString(new String(worldKill.rawData(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("WORLD", worldPayload.getAsJsonObject("actor").get("kind").getAsString());
        assertEquals("minecraft:lava", worldPayload.getAsJsonObject("actor")
                .get("cause").getAsString());
    }

    @Test
    void playerOwnedProjectileRowsKeepThePlayerActor() {
        String playerUuid = UUID.randomUUID().toString();
        InternalAuditEvent launch = EntityLifecycleEvidence.create(
                "PROJECTILE_LAUNCH", 5L, "minecraft:overworld", 1, 64, 1,
                "minecraft:arrow", UUID.randomUUID().toString(), null, null, "entity_join_level",
                playerUuid, "Steve", Map.of("owner_entity_uuid", playerUuid));
        var payload = JsonParser.parseString(new String(launch.rawData(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(playerUuid, launch.playerUuid());
        assertEquals("Steve", launch.playerName());
        assertEquals(playerUuid, payload.get("actor_uuid").getAsString());
        assertEquals("minecraft:arrow", payload.getAsJsonObject("affected_endpoint")
                .get("subject_id").getAsString());
    }
}
