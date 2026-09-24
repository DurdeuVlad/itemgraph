package com.itemgraph.query;

import com.itemgraph.audit.AuditReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryFormatterTest {

    @Test
    void testFormatTime() {
        assertEquals("1970-01-01 00:00:00 UTC", QueryFormatter.formatTime(0L));
        assertEquals("2023-11-14 22:13:20 UTC", QueryFormatter.formatTime(1_700_000_000_000L));
    }

    @Test
    void testFormatDuration() {
        assertEquals("0ms", QueryFormatter.formatDuration(0));
        assertEquals("999ms", QueryFormatter.formatDuration(999));
        assertEquals("1s", QueryFormatter.formatDuration(1000));
        assertEquals("59s", QueryFormatter.formatDuration(59_000));
        assertEquals("1m0s", QueryFormatter.formatDuration(60_000));
        assertEquals("2m35s", QueryFormatter.formatDuration(155_000));
        assertEquals("1h0m", QueryFormatter.formatDuration(3_600_000));
        assertEquals("2h30m", QueryFormatter.formatDuration(9_000_000));

        // Negative duration branch
        assertEquals("-5s", QueryFormatter.formatDuration(-5000));
        assertEquals("-1m30s", QueryFormatter.formatDuration(-90_000));
    }

    @Test
    void testFormatConfidence() {
        assertEquals("0.9990", QueryFormatter.formatConfidence(0.999));
        assertEquals("0.5000", QueryFormatter.formatConfidence(0.5));
        assertEquals("1.0000", QueryFormatter.formatConfidence(1.0));
        assertEquals("0.0000", QueryFormatter.formatConfidence(0.0));
        assertEquals("0.1235", QueryFormatter.formatConfidence(0.123456)); // Rounded to 4 decimals
    }

    @Test
    void testFormatEventFullDetail() {
        NodeRef origin = new NodeRef(1, "PLAYER", "Alice", "minecraft:overworld", null, null, null);
        NodeRef dest = new NodeRef(2, "GROUND", null, "minecraft:overworld", 10.0, 64.0, 20.0);
        FingerprintRef fp = new FingerprintRef(1, "minecraft:diamond_sword", "Excalibur", "hash123");

        ObservationDetail obs = new ObservationDetail(
                42L, "GRIEFLOGGER", 999L, 1_000_000L, origin, dest, fp,
                "DROP_ITEM", 1, 2_000_000L, "CORRELATED", "uuid-item-1"
        );

        List<String> lines = QueryFormatter.formatEvent(obs);
        assertNotNull(lines);
        assertTrue(lines.get(0).contains("=== OBSERVATION #42 [OBSERVED] ==="));
        assertTrue(lines.stream().anyMatch(l -> l.contains("event#999")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("action:      DROP_ITEM")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Excalibur")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("entity UUID: uuid-item-1")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("correlated:  ") && !l.contains("not yet evaluated")));
    }

    @Test
    void testFormatEventUncorrelatedNoEntityUuid() {
        NodeRef origin = new NodeRef(1, "PLAYER", "Bob", "minecraft:overworld", null, null, null);
        FingerprintRef fp = new FingerprintRef(2, "minecraft:iron_ingot", null, "hash456");

        ObservationDetail obs = new ObservationDetail(
                10L, "ITEMGRAPH_INTERNAL", null, 1_000_000L, origin, null, fp,
                "EQUIP_ARMOR_STAND", 1, null, "PENDING", null
        );

        List<String> lines = QueryFormatter.formatEvent(obs);
        assertTrue(lines.stream().anyMatch(l -> l.contains("(no source event id)")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("destination: (none recorded)")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("entity UUID:")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("not yet evaluated by the correlation engine")));
    }

    @Test
    void testEventNotFound() {
        String msg = QueryFormatter.eventNotFound(105L);
        assertEquals("[ItemGraph] No observation #105 exists in ig_observations.", msg);
    }

    @Test
    void testFormatExplainWithEvidenceAndTruncation() {
        NodeRef from = new NodeRef(1, "PLAYER", "Alice", "minecraft:overworld", null, null, null);
        NodeRef to = new NodeRef(2, "PLAYER", "Bob", "minecraft:overworld", null, null, null);
        FingerprintRef fp = new FingerprintRef(1, "minecraft:diamond", null, "hash1");

        ObservationDetail obs1 = new ObservationDetail(1L, "GRIEFLOGGER", 1L, 1000L, from, null, fp, "DROP_ITEM", 1, null, null, null);
        ObservationDetail obs2 = new ObservationDetail(2L, "GRIEFLOGGER", 2L, 2000L, null, to, fp, "PICKUP_ITEM", 1, null, null, null);

        EdgeExplanation edge = new EdgeExplanation(
                15L, from, to, fp, 1, 1000L, 2000L, 0.95,
                "Ground bridge match", 3000L, List.of(obs1, obs2), true
        );

        List<String> lines = QueryFormatter.formatExplain(edge);
        assertTrue(lines.get(0).contains("=== INFERRED EDGE #15 [INFERRED conf=0.9500] ==="));
        assertTrue(lines.stream().anyMatch(l -> l.contains("WARNING: this is a reconstruction")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("supporting evidence (2 observed rows):")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("observation #1")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("observation #2")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("evidence listing truncated at " + QueryLimits.MAX_EVIDENCE_ROWS + " rows")));
    }

    @Test
    void testFormatExplainNoEvidence() {
        NodeRef from = new NodeRef(1, "PLAYER", "Alice", "minecraft:overworld", null, null, null);
        NodeRef to = new NodeRef(2, "PLAYER", "Bob", "minecraft:overworld", null, null, null);
        FingerprintRef fp = new FingerprintRef(1, "minecraft:diamond", null, "hash1");

        EdgeExplanation edge = new EdgeExplanation(
                20L, from, to, fp, 1, 1000L, 2000L, 0.50,
                null, 3000L, List.of(), false
        );

        List<String> lines = QueryFormatter.formatExplain(edge);
        assertTrue(lines.stream().anyMatch(l -> l.contains("supporting evidence: NONE RECORDED")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("why:         (no explanation stored)")));
    }

    @Test
    void testExplainNotFound() {
        String msg = QueryFormatter.explainNotFound(404L);
        assertEquals("[ItemGraph] No inferred edge #404 exists in ig_inferred_edges.", msg);
    }

    @Test
    void testFormatTraceEmpty() {
        QueryWindow window = QueryWindow.unbounded();
        TraceResult result = new TraceResult("item #1", null, List.of(), window, 50, 50, false);

        List<String> lines = QueryFormatter.formatTrace(result);
        assertTrue(lines.stream().anyMatch(l -> l.contains("No observed or inferred movement recorded")));
    }

    @Test
    void sessionNetDeltaTraceHopShowsItsFullTimeInterval() {
        NodeRef container = new NodeRef(1, "CONTAINER", null, "minecraft:overworld", 10.0, 64.0, -20.0);
        NodeRef player = new NodeRef(2, "PLAYER", "Steve", "minecraft:overworld", null, null, null);
        TraceHop sessionDelta = new TraceHop(
                TraceHop.Kind.OBSERVED, 42L, container, player, 3,
                1_000L, 5_000L, null, "REMOVE_ITEM session net delta",
                new FingerprintRef(1, "minecraft:diamond", null, "hash1"));
        TraceResult result = new TraceResult("container at 10,64,-20", null,
                List.of(sessionDelta), QueryWindow.unbounded(), 50, 50, false);

        List<String> lines = QueryFormatter.formatTrace(result);

        assertTrue(lines.stream().anyMatch(line -> line.contains("session net delta")
                && line.contains("1970-01-01 00:00:01 UTC")
                && line.contains("1970-01-01 00:00:05 UTC")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("net container deltas")
                && line.contains("intra-session order is unknown")));
    }

    @Test
    void queueOverflowRecoveryIsNotLabeledAsPlayerSessionNetDelta() {
        NodeRef unknown = NodeRef.missing(2);
        ObservationDetail observation = new ObservationDetail(
                9L, "ITEMGRAPH_INTERNAL", null, 1_000L, unknown, unknown,
                new FingerprintRef(1, "minecraft:diamond", null, "hash1"),
                "CAPABILITY_INSERT", 3, null, "PENDING", null,
                5_000L, "queue_overflow_recovery", null);

        List<String> lines = QueryFormatter.formatEvent(observation);
        String formatted = String.join("\n", lines);

        assertTrue(formatted.contains("time window:"));
        assertTrue(formatted.contains("coalesced capability transfers recovered after queue rejection"));
        assertFalse(formatted.contains("session net delta"));
    }

    @Test
    void testFormatTraceWithCappingAndTruncation() {
        QueryWindow window = QueryWindow.unbounded();
        NodeRef p1 = new NodeRef(1, "PLAYER", "Alice", "minecraft:overworld", null, null, null);
        NodeRef p2 = new NodeRef(2, "PLAYER", "Bob", "minecraft:overworld", null, null, null);
        FingerprintRef fp = new FingerprintRef(1, "minecraft:diamond", null, "hash1");

        TraceHop hopObs = new TraceHop(TraceHop.Kind.OBSERVED, 1L, p1, p2, 1, 1000L, 1000L, null, "DROP", fp);
        TraceHop hopInf = new TraceHop(TraceHop.Kind.INFERRED, 2L, p1, p2, 1, 2000L, 3000L, 0.9990, "UUID continuity", fp);

        TraceResult result = new TraceResult("item #1", fp, List.of(hopObs, hopInf), window, 50, 100, true);

        List<String> lines = QueryFormatter.formatTrace(result);
        assertTrue(lines.stream().anyMatch(l -> l.contains("(capped from 100)")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("[OBSERVED]")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("[INFERRED conf=0.9990]")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("1 observed hop, 1 inferred hop.")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("TRUNCATED at 50 hops")));
    }

    @Test
    void testFormatAuditHealthy() {
        AuditReport report = new AuditReport(true, 10, 5, 5, 0, 0, 0, 0, 0, 0, List.of());
        List<String> lines = QueryFormatter.formatAudit(report);
        assertTrue(lines.stream().anyMatch(l -> l.contains("HEALTHY (ALL INVARIANTS SATISFIED)")));
        assertTrue(lines.stream().noneMatch(l -> l.contains("Violations:")));
    }

    @Test
    void testFormatAuditViolations() {
        AuditReport report = new AuditReport(false, 10, 5, 5, 0, 2, 0, 0, 0, 0,
                List.of("Conservation violation on obs#1: allocated 12 > capacity 10"));
        List<String> lines = QueryFormatter.formatAudit(report);
        assertTrue(lines.stream().anyMatch(l -> l.contains("VIOLATIONS DETECTED")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Conservation violations: 2")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Conservation violation on obs#1")));
    }

    @Test
    void testMiscellaneousQueries() {
        assertEquals("[ItemGraph] Query failed: connection error", QueryFormatter.queryFailed("connection error"));
        assertEquals("[ItemGraph] No item fingerprint #99 exists in ig_item_fingerprints.", QueryFormatter.traceNoSuchFingerprint(99L));
        assertEquals("[ItemGraph] No item fingerprint matching 'sword' exists in ig_item_fingerprints.", QueryFormatter.traceNoSuchFingerprint("sword"));
        assertEquals("[ItemGraph] No recorded history found for player Alice.", QueryFormatter.traceNoSuchTarget("player Alice"));

        FingerprintRef c1 = new FingerprintRef(1, "minecraft:diamond_sword", "Sword1", "h1");
        List<String> candidates = QueryFormatter.formatFingerprintCandidates("sword", List.of(c1));
        assertTrue(candidates.get(0).contains("Query 'sword' matched 1 item fingerprints:"));
        assertTrue(candidates.get(1).contains("#1: 'Sword1' (minecraft:diamond_sword) [hash=h1]"));
    }
}
