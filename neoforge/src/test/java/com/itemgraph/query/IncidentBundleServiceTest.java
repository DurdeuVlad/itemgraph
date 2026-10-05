package com.itemgraph.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itemgraph.command.IncidentBundleService;
import com.itemgraph.query.AuditLookupFilters;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "redacted.json",
                () -> false, () -> true, () -> true, ignored -> {});

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
        assertTrue(inferredPayload.get("candidate_evidence_available").getAsBoolean());
        assertFalse(redactedText.contains("observation#900"));
        assertTrue(IncidentBundleService.verify(exports, "redacted.json").valid());

        IncidentBundleService.export(conn, filters, "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.FULL, true, exports, "full.json",
                () -> false, () -> true, () -> true, ignored -> {});
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
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "direct.json",
                () -> false, () -> true, () -> true, ignored -> {});
        String directText = Files.readString(exports.resolve("direct.json"));
        assertFalse(directText.contains("private-entity-uuid"));
        JsonArray directRecords = JsonParser.parseString(directText).getAsJsonObject().getAsJsonArray("records");
        JsonObject directPayload = directRecords.asList().stream()
                .map(element -> element.getAsJsonObject().getAsJsonObject("payload"))
                .filter(payload -> payload.has("confidence") && payload.get("confidence").getAsDouble() == 0.95)
                .findFirst().orElseThrow();
        assertFalse(directPayload.get("candidate_evidence_available").getAsBoolean());
        assertEquals("CORRELATION_CANDIDATES_UNAVAILABLE", directPayload.get("reason_code").getAsString());
        JsonObject directFactors = directPayload.getAsJsonObject("scoring_factors");
        assertEquals(1, directFactors.get("drop_allocated_quantity").getAsInt());
        assertEquals(30, directFactors.get("correlation_window_seconds").getAsInt());
        assertEquals(1, directFactors.get("admissible_pickup_candidates").getAsInt());

        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100, IncidentBundleService.RedactionProfile.REDACTED,
                false, exports, "redacted.json", () -> false, () -> true, () -> true, ignored -> {}));
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100, IncidentBundleService.RedactionProfile.REDACTED,
                false, exports, "cancelled.json", () -> false, () -> true, () -> false, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("cancelled.json")));

        Path incompleteOutput = exports.resolve("incomplete.json");
        Files.writeString(incompleteOutput, "ITEMGRAPH_EXPORT_RESERVATION:"
                + java.util.UUID.randomUUID());
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "incomplete.json",
                () -> false, () -> true, () -> true, ignored -> {}));
    }

    @Test
    void protectedExportsRecheckLevelFourAtPublicationWhileOrdinaryRedactedExportRemainsAvailable() throws Exception {
        long player = insertPlayerNode("PermissionRevokedPlayer");
        long ground = insertGroundNode(10, 64, 10);
        long fingerprint = insertFingerprint("minecraft:diamond", "permission-revoked-diamond");
        insertObservation(now - 1_000, player, ground, fingerprint, "DROP_ITEM", 1);
        AuditLookupFilters filters = AuditLookupFilters.parse("action.drop_item radius.100", now);
        Path exports = tempDir.resolve("permission-recheck");
        AtomicBoolean levelFourPermission = new AtomicBoolean(true);

        assertThrows(IllegalArgumentException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.FULL, false, exports, "full-unprotected.json",
                () -> false, levelFourPermission::get, () -> true, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("full-unprotected.json")),
                "the service must reject FULL exports without an explicit level-four requirement");

        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.FULL, true, exports, "full-revoked.json",
                () -> false, levelFourPermission::get, () -> {
                    levelFourPermission.set(false);
                    return true;
                }, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("full-revoked.json")),
                "a full export must not publish after level-4 permission is revoked during serialization");

        // This branch does not yet contain PR #125's itemPredicates API. Exercise its
        // service-boundary contract directly with the explicit protected-export flag.
        AuditLookupFilters metadataFilters = filters;
        AtomicBoolean metadataPermission = new AtomicBoolean(true);
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, metadataFilters,
                "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.REDACTED, true, exports, "redacted-filter-revoked.json",
                () -> false, metadataPermission::get, () -> {
                    metadataPermission.set(false);
                    return true;
                }, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("redacted-filter-revoked.json")),
                "a redacted metadata-filtered export must not publish after level-4 permission is revoked");

        IncidentBundleService.export(conn, filters, "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "redacted-after-revoke.json",
                () -> false, levelFourPermission::get, () -> true, ignored -> {});
        Path redacted = exports.resolve("redacted-after-revoke.json");
        assertTrue(Files.exists(redacted), "ordinary redacted exports retain their permission-level-2 behavior");
        assertTrue(IncidentBundleService.verify(exports, "redacted-after-revoke.json").valid());
        assertFalse(Files.readString(redacted).contains("PermissionRevokedPlayer"));
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
    void verifierRejectsDuplicateKeysBeforeParsingBundle() throws Exception {
        Path exports = tempDir.resolve("duplicate-key");
        Files.createDirectories(exports);
        String validBundle = minimalBundle().toString();
        String evidenceId = "\"evidence_id\":\"observation#1\"";
        assertTrue(validBundle.contains(evidenceId));
        String duplicateEvidenceId = validBundle.replace(evidenceId,
                "\"evidence_id\":\"observation#999\"," + evidenceId);
        Files.writeString(exports.resolve("duplicate.json"), duplicateEvidenceId);

        IncidentBundleService.VerificationResult result = IncidentBundleService.verify(exports, "duplicate.json");
        assertFalse(result.valid());

        byte[] invalidUtf8 = validBundle.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int finalEvidenceIdByte = validBundle.indexOf("observation#1") + "observation#1".length() - 1;
        invalidUtf8[finalEvidenceIdByte] = (byte) 0xFF;
        Files.write(exports.resolve("invalid-utf8.json"), invalidUtf8);
        assertFalse(IncidentBundleService.verify(exports, "invalid-utf8.json").valid());

        String tooDeep = "[".repeat(65) + "null" + "]".repeat(65);
        Files.writeString(exports.resolve("too-deep.json"), tooDeep);
        IncidentBundleService.VerificationResult depthResult =
                IncidentBundleService.verify(exports, "too-deep.json");
        assertFalse(depthResult.valid());

        String atDepthLimit = "[".repeat(64) + "null" + "]".repeat(64);
        Files.writeString(exports.resolve("at-depth-limit.json"), atDepthLimit);
        IncidentBundleService.VerificationResult boundaryResult =
                IncidentBundleService.verify(exports, "at-depth-limit.json");
        assertEquals("bundle root must be a JSON object", boundaryResult.message());

        Files.writeString(exports.resolve("trailing-content.json"), validBundle + "{}");
        assertFalse(IncidentBundleService.verify(exports, "trailing-content.json").valid());
    }

    @Test
    void rejectsUnsafeNamesAndHonorsCancellation() {
        assertEquals("case-1.json", IncidentBundleService.normalizeFilename("case-1"));
        assertThrows(IllegalArgumentException.class,
                () -> IncidentBundleService.normalizeFilename("../outside"));
        assertThrows(java.io.IOException.class,
                () -> IncidentBundleService.verify(tempDir, "case.json"));
    }

    @Test
    void exportEnforcesEvidenceEdgeAndTotalRecordCaps() throws Exception {
        long player = insertPlayerNode("CapFixture");
        long ground = insertGroundNode(10, 64, 10);
        long fingerprint = insertFingerprint("minecraft:diamond", "cap-diamond");
        long lastObservation = -1;
        for (int i = 0; i < IncidentBundleService.MAX_EVIDENCE_RECORDS + 1; i++) {
            long observation = insertObservation(now - 20_000 + i, player, ground, fingerprint,
                    "DROP_ITEM", 1);
            lastObservation = observation;
        }
        try (var statement = conn.createStatement()) {
            statement.executeUpdate("UPDATE ig_observations SET source_type = 'ITEMGRAPH_INTERNAL'");
        }
        for (int i = 0; i < IncidentBundleService.MAX_EVIDENCE_RECORDS + 1; i++) {
            long edge = insertEdge(player, player, fingerprint, 1, now - 20_000, now - 19_000,
                    0.8, "cap fixture " + i);
            linkEvidence(edge, lastObservation);
        }

        Path exports = tempDir.resolve("caps");
        IncidentBundleService.export(conn,
                AuditLookupFilters.parse("action.drop_item include.minecraft:diamond radius.100", now),
                "minecraft:overworld", 10, 64, 10, 500,
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "caps.json",
                () -> false, () -> true, () -> true, ignored -> {});

        JsonObject bundle = JsonParser.parseString(Files.readString(exports.resolve("caps.json")))
                .getAsJsonObject();
        JsonObject manifest = bundle.getAsJsonObject("manifest");
        assertEquals(IncidentBundleService.MAX_RECORDS, manifest.get("record_count").getAsInt());
        assertEquals(IncidentBundleService.MAX_EVIDENCE_RECORDS,
                manifest.get("evidence_record_count").getAsInt());
        assertEquals(IncidentBundleService.MAX_EVIDENCE_RECORDS,
                manifest.get("inferred_edge_count").getAsInt());
        assertTrue(manifest.get("inferred_edges_truncated").getAsBoolean());
        assertEquals(IncidentBundleService.MAX_RECORDS, bundle.getAsJsonArray("records").size());
    }

    @Test
    void rejectsBundlesOverFourMiBBeforeBuildingTheFullRecordArray() throws Exception {
        long player = insertPlayerNode("LargeFixture");
        long ground = insertGroundNode(10, 64, 10);
        long fingerprint = insertFingerprint("example:" + "x".repeat(5 * 1024 * 1024), "large-diamond");
        insertObservation(now - 1_000, player, ground, fingerprint, "DROP_ITEM", 1);
        Path exports = tempDir.resolve("oversized");

        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn,
                AuditLookupFilters.parse("action.drop_item radius.100", now),
                "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.FULL, true, exports, "large.json",
                () -> false, () -> true, () -> true, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("large.json")));
    }

    @Test
    void rejectsWriteFailureAndCancellationBeforePublication() throws Exception {
        long player = insertPlayerNode("FailureFixture");
        long ground = insertGroundNode(10, 64, 10);
        long fingerprint = insertFingerprint("minecraft:diamond", "failure-diamond");
        insertObservation(now - 1_000, player, ground, fingerprint, "DROP_ITEM", 1);
        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.drop_item include.minecraft:diamond radius.100", now);
        Path blockingFile = tempDir.resolve("not-a-directory");
        Files.writeString(blockingFile, "block directory creation");
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.REDACTED, false, blockingFile.resolve("exports"), "disk.json",
                () -> false, () -> true, () -> true, ignored -> {}));

        AtomicInteger cancellationChecks = new AtomicInteger();
        Path exports = tempDir.resolve("cancelled");
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 1,
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "query-cancel.json",
                () -> cancellationChecks.incrementAndGet() >= 2, () -> true, () -> true, ignored -> {}));
        assertFalse(Files.exists(exports.resolve("query-cancel.json")));

        for (int i = 0; i < 26; i++) {
            insertObservation(now - 2_000 + i, player, ground, fingerprint, "DROP_ITEM", 1);
        }
        AtomicBoolean cancelDuringBuild = new AtomicBoolean();
        assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                "minecraft:overworld", 10, 64, 10, 100,
                IncidentBundleService.RedactionProfile.REDACTED, false, exports, "build-cancel.json",
                cancelDuringBuild::get, () -> true, () -> true, completed -> {
                    if (completed == 25) {
                        cancelDuringBuild.set(true);
                    }
                }));
        assertFalse(Files.exists(exports.resolve("build-cancel.json")));

        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.IOException.class, () -> IncidentBundleService.export(conn, filters,
                    "minecraft:overworld", 10, 64, 10, 1,
                    IncidentBundleService.RedactionProfile.REDACTED, false, exports, "shutdown-cancel.json",
                    () -> false, () -> true, () -> true, ignored -> {}));
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(exports.resolve("shutdown-cancel.json")));
    }

    @Test
    void concurrentSameFilenameExportsPublishExactlyOneBundle() throws Exception {
        long player = insertPlayerNode("ConcurrentFixture");
        long ground = insertGroundNode(10, 64, 10);
        long fingerprint = insertFingerprint("minecraft:diamond", "concurrent-diamond");
        insertObservation(now - 1_000, player, ground, fingerprint, "DROP_ITEM", 1);
        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.drop_item include.minecraft:diamond radius.100", now);
        Path exports = tempDir.resolve("concurrent");
        CountDownLatch atPublish = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> concurrentExport(filters, exports, atPublish, release));
            Future<Boolean> second = workers.submit(() -> concurrentExport(filters, exports, atPublish, release));
            assertTrue(atPublish.await(10, TimeUnit.SECONDS), "both jobs should reach the publication barrier");
            release.countDown();
            assertEquals(1, (first.get(10, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(10, TimeUnit.SECONDS) ? 1 : 0));
            assertTrue(IncidentBundleService.verify(exports, "same-name.json").valid());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private boolean concurrentExport(AuditLookupFilters filters, Path exports,
                                     CountDownLatch atPublish, CountDownLatch release) throws Exception {
        try (var readConnection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve("itemgraph.db"))) {
            IncidentBundleService.export(readConnection, filters, "minecraft:overworld", 10, 64, 10, 1,
                    IncidentBundleService.RedactionProfile.REDACTED, false, exports, "same-name.json",
                    () -> false, () -> true, () -> true, ignored -> {
                        atPublish.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("publication barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("publication barrier interrupted", interrupted);
                        }
                    });
            return true;
        } catch (java.io.IOException collision) {
            return false;
        }
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
