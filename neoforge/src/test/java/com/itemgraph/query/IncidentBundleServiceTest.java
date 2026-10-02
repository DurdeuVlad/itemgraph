package com.itemgraph.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itemgraph.command.IncidentBundleService;
import com.itemgraph.query.AuditLookupFilters;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentBundleServiceTest extends QueryTestBase {

    @Test
    void redactedAndFullBundlesRetainEvidenceAndExplainLinkedInference() throws Exception {
        long player = insertPlayerNode("PrivatePlayer");
        long ground = insertGroundNode(10, 64, 10);
        long fingerprint = insertFingerprint("minecraft:diamond", "diamond-hash", "Private Name");
        long time = now - 2_000;
        long drop = insertObservation(time, player, ground, fingerprint, "DROP_ITEM", 1);
        long pickup = insertObservation(time + 1_000, ground, player, fingerprint, "PICKUP_ITEM", 1);
        String explanation = "Ground bridge (exact transfer): PrivatePlayer dropped 1x diamond "
                + "at 10,64,10 (observation " + drop + "); PrivatePlayer picked up 1x there later. "
                + "Allocated 1 unit (drop: 1/1 allocated, residual 0; pickup: 1/1 allocated, residual 0). "
                + "Matched on exact item fingerprint and available quantity capacity, same ground block, "
                + "pickup strictly after the drop, within the 30s correlation window. "
                + "Candidates: 1 admissible pickup for this drop; 1 admissible drop for that pickup. "
                + "Confidence 0.9025 = base 0.95 x proximity 0.9500 x pickup-ambiguity 1.0000 "
                + "x drop-ambiguity 1.0000.";
        long edge = insertEdge(player, player, fingerprint, 1, time, time + 1_000, 0.9025, explanation);
        try (var statement = conn.prepareStatement("""
                UPDATE ig_inferred_edges
                SET competing_observation_ids = ?, competing_candidates_truncated = 1
                WHERE id = ?
                """)) {
            statement.setString(1, pickup + ",900");
            statement.setLong(2, edge);
            statement.executeUpdate();
        }
        try (var statement = conn.prepareStatement("UPDATE ig_observations SET source_type = 'GRIEFLOGGER' WHERE id = ?")) {
            statement.setLong(1, pickup);
            statement.executeUpdate();
        }
        linkEvidence(edge, drop);
        linkEvidence(edge, pickup);
        insertHistoricalLookup("source-database-sha256", "items", "private-source-key",
                "raw-byte-payload-sha256", time + 500);

        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.drop_item,pickup_item user.PrivatePlayer include.diamond radius.100", now);
        Path exports = tempDir.resolve("world").resolve("itemgraph").resolve("exports");
        IncidentBundleService.export(conn, filters, "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.REDACTED, exports, "redacted.json",
                () -> false, () -> true, ignored -> {});

        Path redactedPath = exports.resolve("redacted.json");
        String redactedText = Files.readString(redactedPath);
        JsonObject redacted = JsonParser.parseString(redactedText).getAsJsonObject();
        JsonArray redactedRecords = redacted.getAsJsonArray("records");
        assertEquals(4, redactedRecords.size());
        assertFalse(redactedText.contains("PrivatePlayer"));
        assertFalse(redactedText.contains("Private Name"));
        assertFalse(redactedText.contains("private-source-key"));
        assertTrue(redactedText.contains("source_database_sha256"));
        assertTrue(redactedText.contains("source_raw_payload_sha256"));
        assertFalse(redactedText.contains("center_x\":10"));
        var redactedDimension = redacted.getAsJsonObject("manifest").get("dimension");
        assertTrue(redactedDimension == null || redactedDimension.isJsonNull(),
                "redacted bundles must omit or null out the dimension");
        assertFalse(redacted.getAsJsonObject("manifest").get("query").getAsString().contains("PrivatePlayer"));
        assertFalse(redacted.getAsJsonObject("manifest").get("query").getAsString().contains("diamond"));
        JsonObject selectedGriefLoggerObservation = redactedRecords.get(0).getAsJsonObject()
                .getAsJsonObject("payload");
        assertEquals("GRIEFLOGGER", selectedGriefLoggerObservation.get("source").getAsString());
        assertEquals("evidence#1", selectedGriefLoggerObservation.get("evidence_id").getAsString());
        JsonObject inferredPayload = redactedRecords.get(3).getAsJsonObject().getAsJsonObject("payload");
        assertEquals("INFERRED_EDGE", inferredPayload.get("record_type").getAsString());
        assertEquals(0.9025, inferredPayload.get("confidence").getAsDouble(), 0.00001);
        JsonObject scoringFactors = inferredPayload.getAsJsonObject("scoring_factors");
        assertEquals(1, scoringFactors.get("drop_allocated_quantity").getAsInt());
        assertEquals(1, scoringFactors.get("pickup_allocated_quantity").getAsInt());
        assertEquals(30, scoringFactors.get("correlation_window_seconds").getAsInt());
        assertEquals(1, scoringFactors.get("admissible_pickup_candidates").getAsInt());
        assertEquals(1, scoringFactors.get("admissible_drop_candidates").getAsInt());
        assertEquals(2, inferredPayload.getAsJsonArray("supporting_evidence_ids").size());
        assertEquals(2, inferredPayload.getAsJsonArray("supporting_observations").size());
        JsonArray candidateRefs = inferredPayload.getAsJsonArray("candidate_evidence_ids");
        assertEquals(2, candidateRefs.size());
        assertEquals("evidence#1", candidateRefs.get(0).getAsString(),
                "selected GriefLogger-backed observation IDs should resolve to their included evidence row");
        assertEquals("external-candidate#1", candidateRefs.get(1).getAsString(),
                "unselected candidate evidence should be explicitly external");
        assertTrue(inferredPayload.get("candidate_evidence_truncated").getAsBoolean());
        assertEquals("CORRELATION_COMPETING_CANDIDATES",
                inferredPayload.get("reason_code").getAsString());
        assertFalse(redactedText.contains("observation#900"));
        assertTrue(IncidentBundleService.verify(exports, "redacted.json").valid());

        IncidentBundleService.export(conn, filters, "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.FULL, exports, "full.json",
                () -> false, () -> true, ignored -> {});
        String fullText = Files.readString(exports.resolve("full.json"));
        assertTrue(fullText.contains("PrivatePlayer"));
        assertTrue(fullText.contains("Private Name"));
        assertTrue(fullText.contains("private-source-key"));
        assertTrue(fullText.contains("stored_explanation"));
        assertTrue(IncidentBundleService.verify(exports, "full.json").valid());

        String uuidExplanation = "Allocated 1 unit (drop: 1/1 allocated, residual 0; "
                + "pickup: 1/1 allocated, residual 0). Matched on exact item fingerprint and available "
                + "quantity capacity, same ground block, pickup strictly after the drop, within the "
                + "30s correlation window. Candidates: 1 admissible pickup for this drop; "
                + "1 admissible drop for that pickup. Authoritative Minecraft ItemEntity UUID match "
                + "(private-entity-uuid) establishes direct entity continuity on the ground. Confidence 0.95.";
        long directEdge = insertEdge(player, player, fingerprint, 1, time, time + 1_000, 0.95,
                uuidExplanation);
        linkEvidence(directEdge, drop);
        linkEvidence(directEdge, pickup);
        IncidentBundleService.export(conn, filters, "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.REDACTED, exports, "direct.json",
                () -> false, () -> true, ignored -> {});
        String directText = Files.readString(exports.resolve("direct.json"));
        assertFalse(directText.contains("private-entity-uuid"));
        JsonArray directRecords = JsonParser.parseString(directText).getAsJsonObject().getAsJsonArray("records");
        JsonObject directPayload = directRecords.get(3).getAsJsonObject().getAsJsonObject("payload");
        JsonObject directFactors = directPayload.getAsJsonObject("scoring_factors");
        assertEquals(1, directFactors.get("drop_allocated_quantity").getAsInt());
        assertEquals(30, directFactors.get("correlation_window_seconds").getAsInt());
        assertEquals(1, directFactors.get("admissible_pickup_candidates").getAsInt());

        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100, IncidentBundleService.RedactionProfile.REDACTED,
                exports, "redacted.json", () -> false, () -> true, ignored -> {}));
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100, IncidentBundleService.RedactionProfile.REDACTED,
                exports, "cancelled.json", () -> false, () -> false, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("cancelled.json")));

        Path incompleteOutput = exports.resolve("incomplete.json");
        Files.writeString(incompleteOutput, "ITEMGRAPH_EXPORT_RESERVATION:"
                + java.util.UUID.randomUUID());
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.REDACTED, exports, "incomplete.json",
                () -> false, () -> true, ignored -> {}));
    }

    @Test
    void verifierRejectsModifiedReorderedMissingAndExtraRecords() throws Exception {
        Path exports = tempDir.resolve("exports");
        JsonObject bundle = minimalBundle();
        Path original = exports.resolve("original.json");
        Files.createDirectories(exports);
        Files.writeString(original, bundle.toString());
        assertTrue(IncidentBundleService.verify(exports, "original.json").valid());

        JsonObject modified = bundle.deepCopy();
        modified.getAsJsonArray("records").get(0).getAsJsonObject()
                .getAsJsonObject("payload").addProperty("quantity", 99);
        Files.writeString(exports.resolve("modified.json"), modified.toString());
        assertFalse(IncidentBundleService.verify(exports, "modified.json").valid());

        JsonObject reordered = bundle.deepCopy();
        JsonArray records = reordered.getAsJsonArray("records");
        JsonObject first = records.get(0).getAsJsonObject().deepCopy();
        JsonObject second = records.get(1).getAsJsonObject().deepCopy();
        records.set(0, second);
        records.set(1, first);
        Files.writeString(exports.resolve("reordered.json"), reordered.toString());
        assertFalse(IncidentBundleService.verify(exports, "reordered.json").valid());

        JsonObject missing = bundle.deepCopy();
        missing.getAsJsonArray("records").remove(1);
        Files.writeString(exports.resolve("missing.json"), missing.toString());
        assertFalse(IncidentBundleService.verify(exports, "missing.json").valid());

        JsonObject extra = bundle.deepCopy();
        extra.getAsJsonArray("records").add(bundle.getAsJsonArray("records").get(0).deepCopy());
        Files.writeString(exports.resolve("extra.json"), extra.toString());
        assertFalse(IncidentBundleService.verify(exports, "extra.json").valid());
    }

    @Test
    void rejectsUnsafeNamesAndHonorsCancellation() {
        assertEquals("case-1.json", IncidentBundleService.normalizeFilename("case-1"));
        assertThrows(IllegalArgumentException.class,
                () -> IncidentBundleService.normalizeFilename("../outside"));
        assertThrows(java.io.IOException.class,
                () -> IncidentBundleService.verify(tempDir, "case.json"));
    }

    private void insertHistoricalLookup(String sourceHash, String table, String sourceKey,
                                        String rawByteHash, long timestamp) throws Exception {
        try (var statement = conn.prepareStatement("""
                INSERT INTO ig_grieflogger_lookup
                    (source_sha256, raw_byte_hash, table_name, source_key, timestamp_ms,
                     level_name, x, y, z, player_name, player_uuid, action_type, quantity,
                     subject_id, detail, evidence_class)
                VALUES (?, ?, ?, ?, ?, 'minecraft:overworld', 10, 64, 10, 'PrivatePlayer',
                        'private-uuid', 'DROP_ITEM', 1, 'minecraft:diamond', 'private detail', 'OBSERVED')
                """)) {
            statement.setString(1, sourceHash);
            statement.setString(2, rawByteHash);
            statement.setString(3, table);
            statement.setString(4, sourceKey);
            statement.setLong(5, timestamp);
            statement.executeUpdate();
        }
    }

    private JsonObject minimalBundle() {
        JsonObject payloadA = new JsonObject();
        payloadA.addProperty("record_type", "OBSERVED_EVIDENCE");
        payloadA.addProperty("evidence_id", "observation#1");
        JsonObject payloadB = new JsonObject();
        payloadB.addProperty("record_type", "INFERRED_EDGE");
        payloadB.addProperty("inference_id", 1);
        String hashA = IncidentTestHashes.sha256(payloadA.toString());
        String hashB = IncidentTestHashes.sha256(payloadB.toString());
        String genesis = "0".repeat(64);
        String chainA = IncidentTestHashes.sha256(genesis + hashA);
        String chainB = IncidentTestHashes.sha256(chainA + hashB);
        JsonObject recordA = record(payloadA, hashA, genesis, chainA);
        JsonObject recordB = record(payloadB, hashB, chainA, chainB);
        JsonArray hashes = new JsonArray();
        hashes.add(hashA);
        hashes.add(hashB);
        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", IncidentBundleService.SCHEMA);
        manifest.addProperty("schema_version", IncidentBundleService.SCHEMA_VERSION);
        manifest.addProperty("hash_algorithm", "SHA-256");
        manifest.addProperty("chain_formula", "SHA-256(previous_hash_hex || payload_sha256_hex)");
        manifest.addProperty("genesis_hash", genesis);
        manifest.addProperty("record_count", 2);
        manifest.addProperty("evidence_record_count", 1);
        manifest.addProperty("inferred_edge_count", 1);
        manifest.addProperty("inferred_edges_truncated", false);
        manifest.addProperty("final_hash", chainB);
        manifest.add("payload_hashes", hashes);
        JsonObject bundle = new JsonObject();
        bundle.add("manifest", manifest);
        bundle.addProperty("manifest_sha256", IncidentTestHashes.sha256(manifest.toString()));
        JsonArray records = new JsonArray();
        records.add(recordA);
        records.add(recordB);
        bundle.add("records", records);
        return bundle;
    }

    private JsonObject record(JsonObject payload, String payloadHash, String previousHash, String chainHash) {
        JsonObject record = new JsonObject();
        record.add("payload", payload);
        record.addProperty("payload_sha256", payloadHash);
        record.addProperty("previous_hash", previousHash);
        record.addProperty("chain_hash", chainHash);
        return record;
    }

    private static final class IncidentTestHashes {
        private static String sha256(String json) {
            try {
                return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new AssertionError(impossible);
            }
        }
    }
}
