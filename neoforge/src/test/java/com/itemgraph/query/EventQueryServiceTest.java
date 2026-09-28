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
