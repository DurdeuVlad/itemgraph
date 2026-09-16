package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /ig trace item <fingerprintId> [limit] [sinceMinutes]}.
 *
 * <p>The behaviour under test is the merge: raw {@code ig_observations} rows and derived
 * {@code ig_inferred_edges} rows become one chronological timeline in which every hop
 * still carries its own provenance. Getting the merge right but the labelling wrong would
 * be worse than not merging at all, so both are asserted together.
 */
class TraceQueryServiceTest extends QueryTestBase {

    private final TraceQueryService service = new TraceQueryService();

    private static List<TraceHop.Kind> kinds(TraceResult result) {
        return result.hops().stream().map(TraceHop::kind).toList();
    }

    private static List<Long> refIds(TraceResult result) {
        return result.hops().stream().map(TraceHop::refId).toList();
    }

    /**
     * The charter's MVP chain, as an admin sees it:
     *
     * <pre>Chest A -&gt; Player A -&gt; Ground -&gt; Player B -&gt; Chest B</pre>
     *
     * <p>Four observed hops plus the one ground bridge that genuinely needed inference,
     * interleaved by time. The bridge and the two observations underneath it all appear:
     * hiding the observations would hide the evidence and hiding the bridge would hide
     * the claim.
     */
    @Test
    void testMergesObservedAndInferredHopsIntoOneChronologicalTimeline() throws Exception {
        long chestA = insertContainerNode(10, 64, 10);
        long chestB = insertContainerNode(30, 64, 30);
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor", "Old Reliable");

        long t0 = now - 600_000;
        long withdraw = insertObservation(t0, chestA, playerA, fp, "REMOVE_ITEM", 1);
        long drop = insertObservation(t0 + 10_000, playerA, ground, fp, "DROP_ITEM", 1);
        long pickup = insertObservation(t0 + 70_000, ground, playerB, fp, "PICKUP_ITEM", 1);
        long deposit = insertObservation(t0 + 80_000, playerB, chestB, fp, "ADD_ITEM", 1);

        long bridge = insertEdge(playerA, playerB, fp, 1, t0 + 10_000, t0 + 70_000, 0.9025, "ground bridge");
        linkEvidence(bridge, drop);
        linkEvidence(bridge, pickup);

        TraceResult result = service.trace(conn, fp, QueryLimits.DEFAULT_LIMIT, QueryWindow.unbounded());

        assertEquals(5, result.hops().size());
        assertEquals(4, result.observedCount());
        assertEquals(1, result.inferredCount());
        assertFalse(result.truncated());

        // Chronological, with the inferred bridge sorted in at its start time and tie-broken
        // after the observation that shares that timestamp.
        assertEquals(List.of(withdraw, drop, bridge, pickup, deposit), refIds(result));
        assertEquals(List.of(
                TraceHop.Kind.OBSERVED,
                TraceHop.Kind.OBSERVED,
                TraceHop.Kind.INFERRED,
                TraceHop.Kind.OBSERVED,
                TraceHop.Kind.OBSERVED), kinds(result));

        // Observed hops are not scored; inferred hops always are.
        TraceHop observedHop = result.hops().get(0);
        assertNull(observedHop.confidence(), "evidence is not scored");
        assertEquals("REMOVE_ITEM", observedHop.detail());
        assertEquals(observedHop.timestampMs(), observedHop.endMs(), "an observation happens at an instant");

        TraceHop inferredHop = result.hops().get(2);
        assertEquals(0.9025, inferredHop.confidence(), 1e-9);
        assertEquals(playerA, inferredHop.origin().id());
        assertEquals(playerB, inferredHop.destination().id());
        assertEquals(t0 + 10_000, inferredHop.timestampMs());
        assertEquals(t0 + 70_000, inferredHop.endMs(), "an inferred transfer spans time");
    }

    /** A trace is about one fingerprint. Another item's movement must not appear in it. */
    @Test
    void testOtherFingerprintsAreExcluded() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long diamond = insertFingerprint("minecraft:diamond", "hash-diamond");
        long emerald = insertFingerprint("minecraft:emerald", "hash-emerald");

        long mine = insertObservation(now - 1_000, playerA, ground, diamond, "DROP_ITEM", 1);
        insertObservation(now - 1_000, playerA, ground, emerald, "DROP_ITEM", 1);
        long mineEdge = insertEdge(playerA, playerB, diamond, 1, now - 1_000, now, 0.9, "diamond bridge");
        insertEdge(playerA, playerB, emerald, 1, now - 1_000, now, 0.9, "emerald bridge");

        TraceResult result = service.trace(conn, diamond, QueryLimits.DEFAULT_LIMIT, QueryWindow.unbounded());
        assertEquals(List.of(mine, mineEdge), refIds(result));
    }

    /**
     * A fingerprint id that does not exist has to be distinguishable from a real item
     * with no recorded movement. Conflating them would let a typo read as a negative
     * finding.
     */
    @Test
    void testUnknownFingerprintIsReportedAsUnresolved() throws Exception {
        TraceResult result = service.trace(conn, 777L, QueryLimits.DEFAULT_LIMIT, QueryWindow.unbounded());
        assertFalse(result.fingerprint().resolved());
        assertTrue(result.hops().isEmpty());
        assertTrue(QueryFormatter.traceNoSuchFingerprint(777L).contains("No item fingerprint #777"));
    }

    @Test
    void testKnownFingerprintWithNoMovementIsAnEmptyButResolvedTrace() throws Exception {
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        TraceResult result = service.trace(conn, fp, QueryLimits.DEFAULT_LIMIT, QueryWindow.unbounded());

        assertTrue(result.fingerprint().resolved());
        assertTrue(result.hops().isEmpty());
        assertFalse(result.truncated());
        assertTrue(String.join("\n", QueryFormatter.formatTrace(result))
                .contains("No observed or inferred movement recorded"));
    }

    // ------------------------------------------------------------------
    // Bounding
    // ------------------------------------------------------------------

    /**
     * The hard cap must be enforced by the service, not merely offered as advice. A
     * caller asking for ten times the ceiling gets the ceiling, plus an honest statement
     * that the request was capped — capping rather than refusing means an admin chasing
     * an incident still gets real output.
     */
    @Test
    void testRequestedLimitIsCappedAtTheHardCeiling() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        int seeded = QueryLimits.MAX_LIMIT + 25;
        for (int i = 0; i < seeded; i++) {
            insertObservation(now - seeded * 1_000L + i * 1_000L, playerA, ground, fp, "DROP_ITEM", 1);
        }

        TraceResult result = service.trace(conn, fp, QueryLimits.MAX_LIMIT * 10, QueryWindow.unbounded());

        assertEquals(QueryLimits.MAX_LIMIT, result.appliedLimit());
        assertEquals(QueryLimits.MAX_LIMIT * 10, result.requestedLimit());
        assertTrue(result.limitWasCapped());
        assertEquals(QueryLimits.MAX_LIMIT, result.hops().size(),
                "more rows exist, but the query must never return more than the ceiling");
        assertTrue(result.truncated());

        String out = String.join("\n", QueryFormatter.formatTrace(result));
        assertTrue(out.contains("capped from " + (QueryLimits.MAX_LIMIT * 10)), out);
        assertTrue(out.contains("TRUNCATED at " + QueryLimits.MAX_LIMIT), out);
    }

    /** {@link QueryLimits#clampLimit(int)} is the single place the ceiling is applied. */
    @Test
    void testClampLimitBoundsBothEnds() {
        assertEquals(1, QueryLimits.clampLimit(0));
        assertEquals(1, QueryLimits.clampLimit(-50));
        assertEquals(1, QueryLimits.clampLimit(1));
        assertEquals(QueryLimits.DEFAULT_LIMIT, QueryLimits.clampLimit(QueryLimits.DEFAULT_LIMIT));
        assertEquals(QueryLimits.MAX_LIMIT, QueryLimits.clampLimit(QueryLimits.MAX_LIMIT));
        assertEquals(QueryLimits.MAX_LIMIT, QueryLimits.clampLimit(QueryLimits.MAX_LIMIT + 1));
        assertEquals(QueryLimits.MAX_LIMIT, QueryLimits.clampLimit(Integer.MAX_VALUE));
        assertTrue(QueryLimits.DEFAULT_LIMIT <= QueryLimits.MAX_LIMIT);
    }

    /**
     * Truncation must report the earliest hops, not an arbitrary subset: both sides are
     * queried with {@code LIMIT applied + 1} and ordered ascending, so the first
     * {@code applied} of the merge really are the earliest overall.
     */
    @Test
    void testTruncationKeepsTheEarliestHopsAcrossBothSources() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long base = now - 100_000;
        long obs1 = insertObservation(base, playerA, ground, fp, "DROP_ITEM", 1);
        long edge1 = insertEdge(playerA, playerB, fp, 1, base + 1_000, base + 2_000, 0.9, "early bridge");
        long obs2 = insertObservation(base + 3_000, ground, playerB, fp, "PICKUP_ITEM", 1);
        insertEdge(playerA, playerB, fp, 1, base + 4_000, base + 5_000, 0.9, "late bridge");
        insertObservation(base + 6_000, playerB, ground, fp, "DROP_ITEM", 1);

        TraceResult result = service.trace(conn, fp, 3, QueryWindow.unbounded());

        assertEquals(3, result.appliedLimit());
        assertFalse(result.limitWasCapped(), "3 is below the ceiling, so nothing was capped");
        assertTrue(result.truncated());
        assertEquals(List.of(obs1, edge1, obs2), refIds(result));
        assertEquals(2, result.observedCount());
        assertEquals(1, result.inferredCount());
    }

    /** Exactly as many hops as the limit is a complete answer, not a truncated one. */
    @Test
    void testResultExactlyAtTheLimitIsNotReportedAsTruncated() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        insertObservation(now - 3_000, playerA, ground, fp, "DROP_ITEM", 1);
        insertObservation(now - 2_000, playerA, ground, fp, "DROP_ITEM", 1);

        TraceResult result = service.trace(conn, fp, 2, QueryWindow.unbounded());
        assertEquals(2, result.hops().size());
        assertFalse(result.truncated());
        assertFalse(String.join("\n", QueryFormatter.formatTrace(result)).contains("TRUNCATED"));
    }

    // ------------------------------------------------------------------
    // Time window
    // ------------------------------------------------------------------

    /**
     * The window has to actually filter in SQL. An observation outside it must not come
     * back, or "what happened in the last hour" silently becomes "everything".
     */
    @Test
    void testWindowExcludesObservationsOutsideTheRange() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long tooOld = insertObservation(now - 3_600_000, playerA, ground, fp, "DROP_ITEM", 1);
        long inside = insertObservation(now - 60_000, playerA, ground, fp, "DROP_ITEM", 1);
        long onLowerEdge = insertObservation(now - 600_000, playerA, ground, fp, "DROP_ITEM", 1);
        long inFuture = insertObservation(now + 60_000, playerA, ground, fp, "DROP_ITEM", 1);

        QueryWindow window = QueryWindow.lastMinutes(10, now);
        assertEquals(now - 600_000, window.sinceMs());
        assertEquals(now, window.untilMs());

        TraceResult result = service.trace(conn, fp, QueryLimits.MAX_LIMIT, window);
        List<Long> returned = refIds(result);

        assertTrue(returned.contains(onLowerEdge), "the window is inclusive on the lower bound");
        assertTrue(returned.contains(inside));
        assertFalse(returned.contains(tooOld), "an observation before the window must be excluded");
        assertFalse(returned.contains(inFuture), "an observation after the window must be excluded");
        assertEquals(2, returned.size());
    }

    /**
     * Inferred edges span time, so the test is an overlap. An edge whose drop predates
     * the window but whose pickup falls inside it really did happen during the window;
     * dropping it would leave a visible gap in the reconstructed path.
     */
    @Test
    void testWindowUsesOverlapForInferredEdgesNotContainment() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long straddling = insertEdge(playerA, playerB, fp, 1, now - 900_000, now - 300_000, 0.9, "straddles the start");
        long wholly = insertEdge(playerA, playerB, fp, 1, now - 400_000, now - 200_000, 0.9, "inside");
        long before = insertEdge(playerA, playerB, fp, 1, now - 1_800_000, now - 1_200_000, 0.9, "entirely before");
        long after = insertEdge(playerA, playerB, fp, 1, now + 60_000, now + 120_000, 0.9, "entirely after");

        QueryWindow window = QueryWindow.lastMinutes(10, now);
        List<Long> returned = refIds(service.trace(conn, fp, QueryLimits.MAX_LIMIT, window));

        assertTrue(returned.contains(straddling), "an edge ending inside the window is part of it");
        assertTrue(returned.contains(wholly));
        assertFalse(returned.contains(before));
        assertFalse(returned.contains(after));
        assertEquals(2, returned.size());
    }

    /** The predicate helpers must agree with what the SQL does. */
    @Test
    void testQueryWindowPredicates() {
        QueryWindow unbounded = QueryWindow.unbounded();
        assertFalse(unbounded.bounded());
        assertTrue(unbounded.contains(Long.MIN_VALUE));
        assertTrue(unbounded.contains(Long.MAX_VALUE));
        assertTrue(unbounded.overlaps(0, 1));
        assertEquals("all time", unbounded.describe());

        QueryWindow window = QueryWindow.lastMinutes(10, now);
        assertTrue(window.bounded());
        assertTrue(window.contains(now - 600_000), "inclusive lower bound");
        assertTrue(window.contains(now), "inclusive upper bound");
        assertFalse(window.contains(now - 600_001));
        assertFalse(window.contains(now + 1));

        assertTrue(window.overlaps(now - 900_000, now - 300_000), "straddling the start overlaps");
        assertTrue(window.overlaps(now - 300_000, now + 900_000), "straddling the end overlaps");
        assertTrue(window.overlaps(now - 900_000, now + 900_000), "containing the window overlaps");
        assertFalse(window.overlaps(now - 900_000, now - 600_001));
        assertFalse(window.overlaps(now + 1, now + 900_000));

        // A nonsensical window size is clamped rather than producing an inverted range.
        QueryWindow clamped = QueryWindow.lastMinutes(0, now);
        assertEquals(now - 60_000, clamped.sinceMs());
        assertEquals(now, clamped.untilMs());
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    /**
     * The timeline's correctness is its labelling. Every movement line must state its
     * provenance first, and an inferred line must always carry a confidence.
     */
    @Test
    void testEveryTimelineLineIsLabelledWithItsProvenance() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor", "Old Reliable");

        long drop = insertObservation(now - 60_000, playerA, ground, fp, "DROP_ITEM", 1);
        long pickup = insertObservation(now, ground, playerB, fp, "PICKUP_ITEM", 1);
        long bridge = insertEdge(playerA, playerB, fp, 1, now - 60_000, now, 0.9025, "ground bridge");
        linkEvidence(bridge, drop);
        linkEvidence(bridge, pickup);

        TraceResult result = service.trace(conn, fp, QueryLimits.DEFAULT_LIMIT, QueryWindow.unbounded());
        List<String> lines = QueryFormatter.formatTrace(result);
        String out = String.join("\n", lines);

        assertTrue(lines.get(0).contains("'Old Reliable' (minecraft:netherite_chestplate)"), lines.get(0));
        assertTrue(out.contains("window: all time"), out);

        for (TraceHop hop : result.hops()) {
            String line = QueryFormatter.formatHop(hop);
            assertTrue(line.startsWith("[OBSERVED] ") || line.startsWith("[INFERRED conf="),
                    "every movement line must declare its provenance first: " + line);
            if (hop.kind() == TraceHop.Kind.INFERRED) {
                assertTrue(line.contains("conf=0.9025"), line);
                assertTrue(line.contains("edge#" + hop.refId()), line);
            } else {
                assertTrue(line.contains("event#" + hop.refId()), line);
            }
        }

        assertTrue(out.contains("2 observed hops, 1 inferred hop."), out);
        assertTrue(out.contains("/ig explain"), "the output must say how to see the evidence");
    }
}
