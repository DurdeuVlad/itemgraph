package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /ig explain <edgeId>}: the charter's forensic-integrity requirement, executed.
 *
 * <p>An admin asking "why does ItemGraph think this transfer happened?" must get back the
 * exact supporting observations and the scoring factors. These tests assert that the
 * evidence link is actually resolved all the way through {@code ig_edge_evidence} into
 * observation detail, and that an edge which cites nothing says so loudly rather than
 * rendering as a confident-looking claim with an empty section.
 */
class ExplainQueryServiceTest extends QueryTestBase {

    private final ExplainQueryService service = new ExplainQueryService();

    /** The MVP ground bridge: one edge, two cited observations, both fully resolved. */
    @Test
    void testFoundEdgeResolvesEveryEvidenceRowBackToObservationDetail() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor", "Old Reliable");

        long dropTime = now - 120_000;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        long pickupObs = insertObservation(dropTime + 60_000, ground, playerB, fp, "PICKUP_ITEM", 1);

        long edgeId = insertEdge(playerA, playerB, fp, 1, dropTime, dropTime + 60_000, 0.9025,
                "AlphaA dropped 1x at 20,64,20 (observation " + dropObs + "); BetaB recovered it "
                        + "(observation " + pickupObs + "). Confidence 0.9025.");
        linkEvidence(edgeId, dropObs);
        linkEvidence(edgeId, pickupObs);

        EdgeExplanation edge = service.findEdge(conn, edgeId).orElseThrow();

        assertEquals(edgeId, edge.id());
        assertEquals(playerA, edge.from().id());
        assertEquals("AlphaA", edge.from().label());
        assertEquals(playerB, edge.to().id());
        assertEquals("BetaB", edge.to().label());
        assertEquals(fp, edge.fingerprint().id());
        assertEquals(1, edge.amount());
        assertEquals(dropTime, edge.timeStart());
        assertEquals(dropTime + 60_000, edge.timeEnd());
        assertEquals(60_000, edge.spanMs());
        assertEquals(0.9025, edge.confidence(), 1e-9);
        assertEquals("INFERRED", edge.kindLabel());

        // The stored narrative is read, never recomputed: a regenerated explanation could
        // silently disagree with the confidence actually stored on the row.
        assertTrue(edge.explanation().contains("Confidence 0.9025"), edge.explanation());

        // Both evidence rows, chronological, resolved to the same detail /ig event shows.
        assertEquals(2, edge.evidence().size());
        assertFalse(edge.evidenceTruncated());

        ObservationDetail first = edge.evidence().get(0);
        ObservationDetail second = edge.evidence().get(1);
        assertEquals(dropObs, first.id());
        assertEquals("DROP_ITEM", first.actionType());
        assertEquals(playerA, first.origin().id());
        assertEquals(ground, first.destination().id());
        assertEquals(pickupObs, second.id());
        assertEquals("PICKUP_ITEM", second.actionType());
        assertEquals(ground, second.origin().id());
        assertEquals(playerB, second.destination().id());
        assertTrue(first.timestampMs() < second.timestampMs(), "evidence must read chronologically");
    }

    /** Evidence belonging to a different edge must not leak into this one. */
    @Test
    void testEvidenceOfOtherEdgesIsNotIncluded() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long mineObs = insertObservation(now - 10_000, playerA, ground, fp, "DROP_ITEM", 1);
        long theirsObs = insertObservation(now - 9_000, playerB, ground, fp, "DROP_ITEM", 1);

        long mine = insertEdge(playerA, playerB, fp, 1, now - 10_000, now - 5_000, 0.9, "mine");
        long theirs = insertEdge(playerB, playerA, fp, 1, now - 9_000, now - 4_000, 0.8, "theirs");
        linkEvidence(mine, mineObs);
        linkEvidence(theirs, theirsObs);

        EdgeExplanation edge = service.findEdge(conn, mine).orElseThrow();
        assertEquals(List.of(mineObs), edge.evidence().stream().map(ObservationDetail::id).toList());
    }

    @Test
    void testMissingEdgeIsEmptyRatherThanAnException() throws Exception {
        assertTrue(service.findEdge(conn, 4242L).isEmpty());
        assertTrue(QueryFormatter.explainNotFound(4242L).contains("No inferred edge #4242"));
    }

    /**
     * An edge citing no evidence cannot be justified. That is a finding about the data,
     * so the output has to state it rather than print an empty "supporting evidence"
     * heading that reads as though the section merely scrolled off.
     */
    @Test
    void testEdgeWithNoEvidenceIsReportedAsUnjustifiable() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long edgeId = insertEdge(playerA, playerB, fp, 1, now - 1_000, now, 0.5, "no evidence linked");

        EdgeExplanation edge = service.findEdge(conn, edgeId).orElseThrow();
        assertTrue(edge.evidence().isEmpty());

        String out = String.join("\n", QueryFormatter.formatExplain(edge));
        assertTrue(out.contains("NONE RECORDED"), out);
        assertTrue(out.contains("cannot be justified"), out);
    }

    /**
     * The evidence listing is hard-capped. Ground bridges cite two observations today,
     * but the command must not become an unbounded query if a future pattern cites many.
     * One row past the cap is fetched so truncation is a reported fact, not silence.
     */
    @Test
    void testEvidenceListingIsCappedAndTruncationIsReported() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long edgeId = insertEdge(playerA, playerB, fp, 1, now - 100_000, now, 0.9, "many-evidence edge");
        int overCap = QueryLimits.MAX_EVIDENCE_ROWS + 5;
        for (int i = 0; i < overCap; i++) {
            long obs = insertObservation(now - 100_000 + i, playerA, ground, fp, "DROP_ITEM", 1);
            linkEvidence(edgeId, obs);
        }

        EdgeExplanation edge = service.findEdge(conn, edgeId).orElseThrow();
        assertEquals(QueryLimits.MAX_EVIDENCE_ROWS, edge.evidence().size(),
                "the cap must be enforced in the service, not left to the caller");
        assertTrue(edge.evidenceTruncated());
        assertTrue(String.join("\n", QueryFormatter.formatExplain(edge)).contains("truncated at "
                + QueryLimits.MAX_EVIDENCE_ROWS));
    }

    /** Exactly at the cap is not truncation, and must not be reported as such. */
    @Test
    void testEvidenceExactlyAtTheCapIsNotReportedAsTruncated() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long edgeId = insertEdge(playerA, playerB, fp, 1, now - 100_000, now, 0.9, "exactly at cap");
        for (int i = 0; i < QueryLimits.MAX_EVIDENCE_ROWS; i++) {
            linkEvidence(edgeId, insertObservation(now - 100_000 + i, playerA, ground, fp, "DROP_ITEM", 1));
        }

        EdgeExplanation edge = service.findEdge(conn, edgeId).orElseThrow();
        assertEquals(QueryLimits.MAX_EVIDENCE_ROWS, edge.evidence().size());
        assertFalse(edge.evidenceTruncated());
    }

    /**
     * The rendered output must make the evidence/inference boundary impossible to miss:
     * the edge is labelled INFERRED with its confidence, each cited row is labelled
     * OBSERVED, and the warning is not optional.
     */
    @Test
    void testFormattedOutputSeparatesTheClaimFromItsEvidence() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropObs = insertObservation(now - 60_000, playerA, ground, fp, "DROP_ITEM", 1);
        long pickupObs = insertObservation(now, ground, playerB, fp, "PICKUP_ITEM", 1);
        long edgeId = insertEdge(playerA, playerB, fp, 1, now - 60_000, now, 0.9025, "scored narrative here");
        linkEvidence(edgeId, dropObs);
        linkEvidence(edgeId, pickupObs);

        List<String> lines = QueryFormatter.formatExplain(service.findEdge(conn, edgeId).orElseThrow());
        String out = String.join("\n", lines);

        assertTrue(lines.get(0).contains("INFERRED EDGE #" + edgeId), lines.get(0));
        assertTrue(lines.get(0).contains("[INFERRED conf=0.9025]"), lines.get(0));
        assertTrue(out.contains("this is a reconstruction, not direct evidence"), out);
        assertTrue(out.contains("why:         scored narrative here"), out);
        assertTrue(out.contains("[OBSERVED] observation #" + dropObs), out);
        assertTrue(out.contains("[OBSERVED] observation #" + pickupObs), out);
        // Cross-references so the admin can drill into each cited row.
        assertTrue(out.contains("/ig event " + dropObs), out);
    }
}
