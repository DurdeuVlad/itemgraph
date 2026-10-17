package com.itemgraph.audit;

import com.google.gson.JsonParser;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService.InternalTransformation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransformationEvidenceTest {

    private static CanonicalItem item(String id, String name) {
        return new CanonicalItem(id,
                com.itemgraph.canon.ItemCanonicalizer.sha256Hex("id=" + id), name, null, null);
    }

    @Test
    void tradeRecordsObservedInputsOutputAndMerchant() {
        var detail = new com.google.gson.JsonObject();
        detail.addProperty("merchant_entity", "entity:minecraft:villager id=" + UUID.randomUUID());
        InternalTransformation t = TransformationEvidence.create(
                "TRADE", 1000L, UUID.randomUUID().toString(), "Trader",
                "minecraft:overworld", 1.0, 64.0, 2.0,
                List.of(new TransformationEvidence.ItemAmount(item("minecraft:emerald", null), 5),
                        new TransformationEvidence.ItemAmount(item("minecraft:book", null), 1)),
                false,
                new TransformationEvidence.ItemAmount(item("minecraft:enchanted_book", "Mending"), 1),
                TransformationEvidence.Outcome.COMPLETED, "merchant",
                "test boundary", detail);

        assertEquals("TRADE", t.transformationType());
        assertEquals("minecraft:emerald", t.sourceItem().itemId());
        assertEquals("minecraft:enchanted_book", t.resultItem().itemId());
        assertEquals(1, t.quantity());
        var payload = JsonParser.parseString(t.details()).getAsJsonObject();
        assertEquals("COMPLETED", payload.get("outcome").getAsString());
        assertEquals("merchant", payload.get("station").getAsString());
        assertEquals(2, payload.getAsJsonArray("inputs").size());
        assertEquals(5, payload.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .get("qty").getAsInt());
        assertFalse(payload.has("unobserved_inputs"));
        assertFalse(payload.has("reason_code"));
    }

    @Test
    void cancelledOperationPersistsNoOutputQuantityAndReason() {
        InternalTransformation t = TransformationEvidence.create(
                "ENCHANTING", 1000L, null, null, "minecraft:overworld", 0, 0, 0,
                List.of(new TransformationEvidence.ItemAmount(item("minecraft:book", null), 1)),
                false, null, TransformationEvidence.Outcome.CANCELLED,
                "enchanting_table", "test boundary", null);

        assertEquals(TransformationEvidence.NON_ITEM_ENDPOINT, t.resultItem());
        assertEquals(0, t.quantity());
        var payload = JsonParser.parseString(t.details()).getAsJsonObject();
        assertEquals("CANCELLED", payload.get("outcome").getAsString());
        assertEquals("TRANSFORMATION_CANCELLED", payload.get("reason_code").getAsString());
        assertFalse(payload.has("output"));
    }

    @Test
    void lootGenerationUsesNonItemEndpointAndUnobservedInputs() {
        InternalTransformation t = TransformationEvidence.create(
                "LOOT_GENERATION", 1000L, null, null, "minecraft:overworld", 0, 0, 0,
                List.of(), true,
                new TransformationEvidence.ItemAmount(item("minecraft:cod", null), 3),
                TransformationEvidence.Outcome.COMPLETED, "loot_table",
                "test boundary", null);

        assertEquals(TransformationEvidence.NON_ITEM_ENDPOINT, t.sourceItem());
        assertEquals("minecraft:cod", t.resultItem().itemId());
        var payload = JsonParser.parseString(t.details()).getAsJsonObject();
        assertTrue(payload.get("unobserved_inputs").getAsBoolean());
        assertEquals("TRANSFORMATION_INPUTS_NOT_OBSERVED", payload.get("reason_code").getAsString());
        assertTrue(payload.getAsJsonArray("inputs").isEmpty());
    }

    @Test
    void unobservedOutputRequiresExplicitFlag() {
        assertThrows(IllegalArgumentException.class, () -> TransformationEvidence.create(
                "GRINDSTONE", 1L, null, null, "minecraft:overworld", 0, 0, 0,
                List.of(new TransformationEvidence.ItemAmount(item("minecraft:stone_sword", null), 1)),
                false, null, TransformationEvidence.Outcome.COMPLETED,
                "grindstone", "test", null),
                "completed transformations cannot silently drop the output endpoint");

        InternalTransformation t = TransformationEvidence.create(
                "GRINDSTONE", 1L, null, null, "minecraft:overworld", 0, 0, 0,
                List.of(new TransformationEvidence.ItemAmount(item("minecraft:stone_sword", null), 1)),
                false, null, true, TransformationEvidence.Outcome.COMPLETED,
                "grindstone", "test", null);
        var payload = JsonParser.parseString(t.details()).getAsJsonObject();
        assertTrue(payload.get("unobserved_output").getAsBoolean());
        assertEquals(TransformationEvidence.NON_ITEM_ENDPOINT, t.resultItem());
    }

    @Test
    void rejectsUnsupportedTypesAndInvalidEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> TransformationEvidence.create(
                "CRAFT", 1L, null, null, "minecraft:overworld", 0, 0, 0,
                List.of(), false, null, TransformationEvidence.Outcome.COMPLETED,
                "crafting_table", "test", null),
                "legacy types outside the #57 surface are rejected");
        assertThrows(IllegalArgumentException.class, () -> TransformationEvidence.create(
                "TRADE", -1L, null, null, "minecraft:overworld", 0, 0, 0,
                List.of(), false, null, TransformationEvidence.Outcome.COMPLETED,
                "merchant", "test", null));
        assertThrows(IllegalArgumentException.class, () -> TransformationEvidence.create(
                "TRADE", 1L, null, null, "minecraft:overworld", Double.NaN, 0, 0,
                List.of(), false, null, TransformationEvidence.Outcome.COMPLETED,
                "merchant", "test", null));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationEvidence.ItemAmount(item("minecraft:stone", null), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TransformationEvidence.ItemAmount(
                        new CanonicalItem("minecraft:stone", null, null, null, null), 1));
    }

    @Test
    void unresolvedFallbackCarriesStationAndReason() {
        var event = TransformationEvidence.createUnresolved(
                500L, "uuid", "name", "minecraft:overworld",
                1, 2, 3, "smithing_table", "queue_rejected");
        assertEquals("TRANSFORMATION_UNRESOLVED", event.eventType());
        var payload = JsonParser.parseString(event.detail()).getAsJsonObject();
        assertEquals("UNRESOLVED", payload.get("outcome").getAsString());
        assertEquals("TRANSFORMATION_EVIDENCE_NOT_SUBMITTED", payload.get("reason_code").getAsString());
        assertEquals("smithing_table", payload.get("station").getAsString());
    }

    @Test
    void nonItemEndpointSentinelIsCanonicalAndBounded() {
        assertEquals("itemgraph:non_item_endpoint",
                TransformationEvidence.NON_ITEM_ENDPOINT.itemId());
        assertNotNull(TransformationEvidence.NON_ITEM_ENDPOINT.fingerprintHash());
        assertFalse(TransformationEvidence.NON_ITEM_ENDPOINT.fingerprintHash().isBlank());
        // A second construction produces the identical fingerprint — the
        // sentinel is a deterministic non-item endpoint marker.
        assertEquals(TransformationEvidence.NON_ITEM_ENDPOINT.fingerprintHash(),
                com.itemgraph.canon.ItemCanonicalizer.sha256Hex("id=itemgraph:non_item_endpoint"));
    }
}
