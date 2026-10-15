package com.itemgraph.audit;

import com.google.gson.JsonParser;
import com.itemgraph.canon.CanonicalItem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformationOutputEvidenceTest {
    @Test
    void storesOnlyObservedOutputAndMarksTheInputUnknown() {
        var event = TransformationOutputEvidence.create(
                "craft_output_unresolved", 1234L,
                "00000000-0000-0000-0000-000000000001", "Alice", "minecraft:overworld",
                10.5, 64.0, 20.5,
                new CanonicalItem("example:steel_sword", "fp-123", "Steel Sword", "RARE", "damage=12"),
                2, "test result slot");

        assertEquals("CRAFT_OUTPUT_UNRESOLVED", event.eventType());
        assertEquals("example:steel_sword", event.subjectId());
        assertEquals(event.ingestEventUuid(), JsonParser.parseString(
                new String(event.rawData(), StandardCharsets.UTF_8)).getAsJsonObject().get("event_id").getAsString());
        assertTrue(event.detail().contains("quantity=2"));
        assertTrue(event.detail().contains("fingerprint=fp-123"));
        assertTrue(event.detail().contains("input=UNKNOWN"));
        assertTrue(event.detail().contains(TransformationOutputEvidence.REASON_CODE));

        var payload = JsonParser.parseString(new String(event.rawData(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("UNRESOLVED", payload.get("evidence_class").getAsString());
        assertEquals("UNKNOWN", payload.get("quantity_semantics").getAsString());
        assertEquals("example:steel_sword", payload.getAsJsonObject("observed_output").get("item_id").getAsString());
        assertEquals(2, payload.getAsJsonObject("observed_output").get("quantity").getAsInt());
        assertFalse(payload.has("inputs"));
    }

    @Test
    void rejectsInvalidOutputAndUnregisteredEventTypes() {
        CanonicalItem output = new CanonicalItem("minecraft:bread", "bread-hash", null, null, null);
        assertThrows(IllegalArgumentException.class, () -> TransformationOutputEvidence.create(
                "TRADE", 1, null, null, "minecraft:overworld", 0, 64, 0, output, 1, "test"));
        assertThrows(IllegalArgumentException.class, () -> TransformationOutputEvidence.create(
                "SMELT_OUTPUT_UNRESOLVED", 1, null, null, "minecraft:overworld", 0, 64, 0, output, 0, "test"));
    }

    @Test
    void truncatesLongModdedMetadataWithoutFailingCaptureOrChangingFingerprint() {
        String longMetadata = "lore=" + "x".repeat(2_000);
        var event = TransformationOutputEvidence.create(
                "SMELT_OUTPUT_UNRESOLVED", 1234L,
                "00000000-0000-0000-0000-000000000001", "Alice", "minecraft:overworld",
                10, 64, 20,
                new CanonicalItem("example:custom_item", "a".repeat(64), "n".repeat(800), "RARE", longMetadata),
                1, "test result slot");

        String detail = event.detail();
        var payload = JsonParser.parseString(new String(event.rawData(), StandardCharsets.UTF_8)).getAsJsonObject();
        var output = payload.getAsJsonObject("observed_output");
        assertTrue(detail.contains("…[truncated]"));
        assertTrue(output.get("fingerprint_hash").getAsString().equals("a".repeat(64)));
        assertTrue(output.get("custom_name").getAsString().endsWith("…[truncated]"));
        assertTrue(output.get("component_summary").getAsString().endsWith("…[truncated]"));
        assertFalse(payload.toString().contains("x".repeat(513)));
    }
}
