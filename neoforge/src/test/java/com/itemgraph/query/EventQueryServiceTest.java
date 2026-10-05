package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code /ig event <observationId>}: one raw observation, fully resolved. */
class EventQueryServiceTest extends QueryTestBase {

    private final EventQueryService service = new EventQueryService();

    /**
     * The whole point of the command: every field of the row, with both endpoints and the
     * item resolved through joins rather than returned as bare ids an admin cannot read.
     */
    @Test
    void testFoundObservationResolvesBothNodesAndTheFingerprint() throws Exception {
        long chest = insertContainerNode(10, 64, 10);
        long player = insertPlayerNode("AlphaA");
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor", "Old Reliable");

        long obsId = insertObservation(now - 5_000, chest, player, fp, "REMOVE_ITEM", 1);
        markCorrelated(obsId, now - 1_000);

        Optional<ObservationDetail> found = service.findObservation(conn, obsId);
        assertTrue(found.isPresent());
        ObservationDetail obs = found.get();

        assertEquals(obsId, obs.id());
        assertEquals("GRIEFLOGGER", obs.sourceType());
        assertNotNull(obs.sourceEventId());
        assertEquals(now - 5_000, obs.timestampMs());
        assertEquals("REMOVE_ITEM", obs.actionType());
        assertEquals(1, obs.amount());
        assertEquals(now - 1_000, obs.correlatedAtMs());

        assertEquals(chest, obs.origin().id());
        assertEquals("CONTAINER", obs.origin().nodeType());
        assertEquals(player, obs.destination().id());
        assertEquals("PLAYER", obs.destination().nodeType());
        assertEquals("AlphaA", obs.destination().label());

        assertEquals(fp, obs.fingerprint().id());
        assertEquals("minecraft:netherite_chestplate", obs.fingerprint().itemId());
        assertEquals("Old Reliable", obs.fingerprint().customName());
        assertEquals("hash-armor", obs.fingerprint().fingerprintHash());

        // A raw row can never be relabelled as an inference.
        assertEquals("OBSERVED", obs.kindLabel());
    }

    /**
     * "There is no observation 9999" is a legitimate forensic answer, not an error
     * condition. It must come back as an empty Optional so the command can say so
     * plainly instead of surfacing a stack trace.
     */
    @Test
    void testMissingObservationIsEmptyRatherThanAnException() throws Exception {
        assertTrue(service.findObservation(conn, 9999L).isEmpty());

        String line = QueryFormatter.eventNotFound(9999L);
        assertTrue(line.contains("No observation #9999"), line);
    }

    @Test
    void testCanonicalEventUuidOpensLinkedAdminObservationAndRendersMutationIds() throws Exception {
        long player = insertPlayerNode("AlphaA");
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long obsId = insertObservation(now, player, null, fp, "ADMIN_ITEM_CREATE", 3);
        String observationUuid = "123e4567-e89b-12d3-a456-426614174000";
        String mutationUuid = "123e4567-e89b-12d3-a456-426614174001";
        String attemptUuid = "123e4567-e89b-12d3-a456-426614174002";
        String raw = "{\"event_id\":\"" + observationUuid + "\",\"mutation_event_id\":\""
                + mutationUuid + "\",\"command_attempt_event_id\":\"" + attemptUuid + "\"}";
        try (var update = conn.prepareStatement(
                "UPDATE ig_observations SET source_type='ITEMGRAPH_INTERNAL', ingest_event_uuid=?, raw_data=? WHERE id=?")) {
            update.setString(1, observationUuid);
            update.setBytes(2, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            update.setLong(3, obsId);
            update.executeUpdate();
        }

        ObservationDetail obs = service.findObservationByEventUuid(conn, observationUuid).orElseThrow();
        assertEquals(obsId, obs.id());
        assertEquals(observationUuid, obs.evidenceEventUuid());
        assertEquals(mutationUuid, obs.mutationEventUuid());
        assertEquals(attemptUuid, obs.commandAttemptEventUuid());
        String rendered = String.join("\n", QueryFormatter.formatEvent(obs));
        assertTrue(rendered.contains("mutation event: " + mutationUuid), rendered);
        assertTrue(rendered.contains("command attempt: " + attemptUuid), rendered);
    }

    @Test
    void testMalformedEventUuidDoesNotQueryOrMatch() throws Exception {
        assertTrue(service.findObservationByEventUuid(conn, "not-an-event-uuid").isEmpty());
        assertTrue(QueryFormatter.eventUuidNotFound("not-an-event-uuid").contains("Invalid event UUID"));
    }

    @Test
    void testOutcomeDetailExposesBoundedLinksAndPartialStatusWithoutAcceptingMalformedIds() {
        String raw = "{\"mutation_event_id\":\"123e4567-e89b-12d3-a456-426614174001\","
                + "\"command_attempt_event_id\":\"123e4567-e89b-12d3-a456-426614174002\","
                + "\"item_flow_link_status\":\"PARTIAL_LINK_LIST\","
                + "\"related_observation_event_ids\":[\"123e4567-e89b-12d3-a456-426614174000\",\"invalid\"],"
                + "\"related_observation_event_ids_omitted\":2,"
                + "\"related_transformation_event_ids\":[\"123e4567-e89b-12d3-a456-426614174003\"],"
                + "\"related_unresolved_event_ids\":[\"123e4567-e89b-12d3-a456-426614174004\"]}";
        String rendered = AdminMutationEvidenceLinks.appendAuditDetail("ADMIN_ITEM_COMMAND_EFFECT", "outcome=confirmed",
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(rendered.contains("item_flow_link_status=PARTIAL_LINK_LIST"), rendered);
        assertTrue(rendered.contains("related_observation_event_ids=123e4567-e89b-12d3-a456-426614174000"), rendered);
        assertFalse(rendered.contains("invalid"), rendered);
        assertTrue(rendered.contains("related_observation_event_ids_omitted=2"), rendered);
        assertTrue(rendered.contains("related_transformation_event_ids=123e4567-e89b-12d3-a456-426614174003"), rendered);
        assertTrue(rendered.contains("related_unresolved_event_ids=123e4567-e89b-12d3-a456-426614174004"), rendered);
    }

    @Test
    void testMalformedUnresolvedStackSummaryIsIgnoredSafely() {
        String raw = "{\"before\":{\"empty\":12,\"item_id\":false,\"count\":1.5}}";
        String rendered = AdminMutationEvidenceLinks.appendAuditDetail("ADMIN_ITEM_COMMAND_UNRESOLVED", "outcome=unresolved",
                raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(rendered.startsWith("outcome=unresolved"), rendered);
        assertFalse(rendered.contains("before="), rendered);
    }

    @Test
    void testCanonicalEventUuidOpensAdminTransformation() throws Exception {
        long actor = insertPlayerNode("Moderator");
        long before = insertFingerprint("minecraft:iron_sword", "before-hash");
        long after = insertFingerprint("minecraft:diamond_sword", "after-hash");
        String transformationUuid = "123e4567-e89b-12d3-a456-426614174010";
        try (var insert = conn.prepareStatement("""
                INSERT INTO ig_item_transformations
                    (transformation_type, player_node_id, source_fingerprint_id, result_fingerprint_id,
                     quantity, timestamp_ms, details, ingest_event_uuid)
                VALUES ('ADMIN_ITEM_TRANSFORM', ?, ?, ?, 1, ?, 'cause=item_modify target_player_uuid=target-uuid target_player_name=Target', ?)
                """)) {
            insert.setLong(1, actor);
            insert.setLong(2, before);
            insert.setLong(3, after);
            insert.setLong(4, now);
            insert.setString(5, transformationUuid);
            insert.executeUpdate();
        }

        UnifiedEvidenceDetail event = service.findTransformationByEventUuid(conn, transformationUuid).orElseThrow();
        assertEquals("TRANSFORMATION", event.source());
        assertEquals("ADMIN_ITEM_TRANSFORM", event.actionType());
        assertEquals(1, event.quantity());
        String output = String.join("\n", QueryFormatter.formatUnifiedEvidence(List.of(event), "exact event UUID"));
        assertTrue(output.contains("evidence_event_id=" + transformationUuid), output);
        assertTrue(output.contains("minecraft:iron_sword -> minecraft:diamond_sword"), output);
        assertTrue(output.contains("target_player_uuid=target-uuid target_player_name=Target"), output);
    }

    @Test
    void testCanonicalEventUuidOpensUnresolvedAuditEvidenceAndBeforeAfterSummary() throws Exception {
        String eventUuid = "123e4567-e89b-12d3-a456-426614174020";
        String raw = "{\"event_id\":\"" + eventUuid + "\",\"mutation_event_id\":\"123e4567-e89b-12d3-a456-426614174021\","
                + "\"endpoint_kind\":\"entity\",\"slot\":\"weapon.mainhand\","
                + "\"target_entity_uuid\":\"target-entity\",\"target_entity_type\":\"minecraft:zombie\","
                + "\"before\":{\"item_id\":\"minecraft:iron_sword\",\"fingerprint\":\"before\",\"count\":1},"
                + "\"after\":{\"item_id\":\"example:custom_sword\",\"fingerprint\":\"after\",\"count\":1}}";
        try (var insert = conn.prepareStatement("""
                INSERT INTO ig_audit_events
                    (event_type, timestamp_ms, player_uuid, player_name, level_id, x, y, z,
                     subject_id, detail, source_type, source_event_id, raw_data, ingest_event_uuid)
                VALUES ('ADMIN_ITEM_COMMAND_UNRESOLVED', ?, NULL, NULL, 'minecraft:overworld', 10, 64, 10,
                        'item_modify', 'outcome=confirmed_unresolved_transformation',
                        'ITEMGRAPH_INTERNAL', 1, ?, ?)
                """)) {
            insert.setLong(1, now);
            insert.setBytes(2, raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            insert.setString(3, eventUuid);
            insert.executeUpdate();
        }

        UnifiedEvidenceDetail event = service.findAuditEventByEventUuid(conn, eventUuid).orElseThrow();
        assertEquals("UNRESOLVED", event.evidenceClass());
        String output = String.join("\n", QueryFormatter.formatUnifiedEvidence(List.of(event), "exact event UUID"));
        assertTrue(output.contains("evidence_event_id=" + eventUuid), output);
        assertTrue(output.contains("endpoint_kind=entity slot=weapon.mainhand"), output);
        assertTrue(output.contains("target_entity_uuid=target-entity target_entity_type=minecraft:zombie"), output);
        assertTrue(output.contains("before=minecraft:iron_sword x1"), output);
        assertTrue(output.contains("after=example:custom_sword x1"), output);
    }

    /** A row the source recorded no destination for must still be returned, not dropped. */
    @Test
    void testObservationWithNoDestinationIsStillReturned() throws Exception {
        long player = insertPlayerNode("AlphaA");
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long obsId = insertObservation(now, player, null, fp, "WEIRD_UNMAPPED_ACTION", 3);

        ObservationDetail obs = service.findObservation(conn, obsId).orElseThrow();
        assertEquals(player, obs.origin().id());
        assertNull(obs.destination(), "no target node recorded means no destination claimed");
        assertNull(obs.correlatedAtMs(), "not yet evaluated by the correlation engine");
    }

    /**
     * A dangling node reference must surface as an explicit "missing" marker. Silently
     * omitting the observation from a forensic result is far more dangerous than
     * rendering an ugly one — the projection uses LEFT joins specifically for this.
     */
    @Test
    void testDanglingNodeReferenceRendersAsMissingRatherThanDroppingTheRow() throws Exception {
        long player = insertPlayerNode("AlphaA");
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long obsId = insertObservation(now, player, null, fp, "DROP_ITEM", 1);

        // Simulate corruption: point the row at a node id that does not exist.
        try (var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF;");
            stmt.execute("UPDATE ig_observations SET node_id = 424242 WHERE id = " + obsId);
            stmt.execute("PRAGMA foreign_keys = ON;");
        }

        ObservationDetail obs = service.findObservation(conn, obsId).orElseThrow();
        assertNotNull(obs.origin());
        assertEquals(424242L, obs.origin().id());
        assertFalse(obs.origin().resolved());
        assertTrue(obs.origin().describe().contains("no such row"), obs.origin().describe());
    }

    /** The rendered detail view is the actual deliverable, so pin its content. */
    @Test
    void testFormattedOutputLabelsTheRowObserved() throws Exception {
        long chest = insertContainerNode(10, 64, 10);
        long player = insertPlayerNode("AlphaA");
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor", "Old Reliable");
        long obsId = insertObservation(now, chest, player, fp, "REMOVE_ITEM", 1);

        List<String> lines = QueryFormatter.formatEvent(service.findObservation(conn, obsId).orElseThrow());
        String all = String.join("\n", lines);

        assertTrue(lines.get(0).contains("[OBSERVED]"), lines.get(0));
        assertTrue(all.contains("OBSERVATION #" + obsId), all);
        assertTrue(all.contains("'Old Reliable' (minecraft:netherite_chestplate)"), all);
        assertTrue(all.contains("CONTAINER minecraft:overworld 10,64,10"), all);
        assertTrue(all.contains("AlphaA"), all);
        assertFalse(all.contains("[INFERRED]"), "a raw observation must never carry an inference label");
    }
}
