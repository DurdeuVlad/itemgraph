package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    @Test
    void sessionNetDeltaIsIncludedWhenItsIntervalOverlapsTheTraceWindow() throws Exception {
        long container = insertContainerNode(10, 64, 10);
        long player = insertPlayerNode("AlphaA");
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond", null);
        long observationId;
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_observations (source_type, timestamp_ms, timestamp_end_ms, node_id,
                    target_node_id, fingerprint_id, action_type, amount, raw_data)
                VALUES ('ITEMGRAPH_INTERNAL', 1000, 5000, ?, ?, ?, 'REMOVE_ITEM', 3,
                    '{"capture":"container_session_net_delta","sessionStartMs":1000,"sessionEndMs":5000}')
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, container);
            pstmt.setLong(2, player);
            pstmt.setLong(3, fp);
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                assertTrue(keys.next());
                observationId = keys.getLong(1);
            }
        }

        TraceResult result = service.trace(conn, fp, QueryLimits.DEFAULT_LIMIT,
                new QueryWindow(3_000L, 4_000L));

        assertEquals(List.of(observationId), refIds(result));
        assertEquals(1_000L, result.hops().get(0).timestampMs());
        assertEquals(5_000L, result.hops().get(0).endMs());
        assertTrue(result.hops().get(0).detail().contains("session net delta"));
    }

    @Test
    void itemPagesUseStableKeysetAcrossEqualTimestampSources() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long diamond = insertFingerprint("minecraft:diamond", "hash-diamond");
        long emerald = insertFingerprint("minecraft:emerald", "hash-emerald");
        long timestamp = now - 5_000;
        for (int i = 0; i < 43; i++) {
            insertObservation(timestamp, playerA, ground, diamond, "DROP_ITEM", 1);
        }
        for (int i = 0; i < 3; i++) {
            insertEdge(playerA, playerB, diamond, 1, timestamp, timestamp, 0.9, "same-time edge");
        }
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_item_transformations (transformation_type, player_node_id,
                    source_fingerprint_id, result_fingerprint_id, quantity, timestamp_ms, details)
                VALUES ('CRAFTING', ?, ?, ?, 1, ?, 'same-time transform')
                """)) {
            for (int i = 0; i < 2; i++) {
                pstmt.setLong(1, playerA);
                pstmt.setLong(2, diamond);
                pstmt.setLong(3, emerald);
                pstmt.setLong(4, timestamp);
                pstmt.executeUpdate();
            }
        }

        TracePage first = service.traceItemPage(conn, "minecraft:diamond", 45,
                QueryWindow.unbounded(), null);
        TracePage second = service.traceItemPage(conn, "minecraft:diamond", 45,
                QueryWindow.unbounded(), first.nextCursor());
        TracePage previous = service.traceItemPage(conn, "minecraft:diamond", 45,
                QueryWindow.unbounded(), second.previousCursor(), TracePage.Direction.BACKWARD);
        List<String> pagedKeys = java.util.stream.Stream.concat(first.hops().stream(), second.hops().stream())
                .map(TraceQueryServiceTest::stableKey)
                .toList();
        TraceResult full = service.trace(conn, diamond, QueryLimits.MAX_LIMIT, QueryWindow.unbounded());

        assertEquals(TracePage.Resolution.RESOLVED, first.resolution());
        assertEquals(45, first.hops().size());
        assertTrue(first.hasNext());
        assertEquals(3, second.hops().size());
        assertFalse(second.hasNext());
        assertTrue(second.hasPrevious());
        assertEquals(first.hops().stream().map(TraceQueryServiceTest::stableKey).toList(),
                previous.hops().stream().map(TraceQueryServiceTest::stableKey).toList());
        assertFalse(previous.hasPrevious());
        assertTrue(previous.hasNext());
        assertEquals(full.hops().stream().map(TraceQueryServiceTest::stableKey).toList(), pagedKeys);
        assertEquals(48, new java.util.HashSet<>(pagedKeys).size(), "every table row appears exactly once");
    }

    private static String stableKey(TraceHop hop) {
        return hop.kind() + ":" + hop.source() + ":" + hop.refId();
    }

    @Test
    void resolvedFingerprintPagesStayPinnedIfLaterIngestionAddsAnotherMatch() throws Exception {
        long player = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long diamond = insertFingerprint("minecraft:diamond", "hash-diamond");
        long timestamp = now - 5_000;
        for (int i = 0; i < 46; i++) {
            insertObservation(timestamp + i, player, ground, diamond, "DROP_ITEM", 1);
        }

        TracePage first = service.traceItemPage(conn, "minecraft:diamond", 45,
                QueryWindow.unbounded(), null);
        long laterMatch = insertFingerprint("minecraft:diamond_sword", "hash-sword");
        TracePage reResolved = service.traceItemPage(conn, "minecraft:diamond", 45,
                QueryWindow.unbounded(), first.nextCursor());
        TracePage pinned = service.traceFingerprintPage(conn, diamond, 45,
                QueryWindow.unbounded(), first.nextCursor(), TracePage.Direction.FORWARD);

        assertTrue(first.hasNext());
        assertEquals(TracePage.Resolution.AMBIGUOUS, reResolved.resolution());
        assertEquals(TracePage.Resolution.RESOLVED, pinned.resolution());
        assertEquals(diamond, pinned.fingerprint().id());
        assertEquals(1, pinned.hops().size());
        assertEquals(List.of(laterMatch, diamond),
                reResolved.candidates().stream().map(FingerprintRef::id).toList());
    }

    @Test
    void numericFingerprintQuerySurfacesConflictingTextMatch() throws Exception {
        long numericId = insertFingerprint("minecraft:diamond", "hash-diamond");
        long namedFingerprint = insertFingerprint("minecraft:iron_ingot", "hash-iron");
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_item_fingerprints SET custom_name = ? WHERE id = ?")) {
            pstmt.setString(1, Long.toString(numericId));
            pstmt.setLong(2, namedFingerprint);
            pstmt.executeUpdate();
        }

        TracePage page = service.traceItemPage(conn, Long.toString(numericId), 45,
                QueryWindow.unbounded(), null);
        TracePage forcedId = service.traceItemPage(conn, "id:" + numericId, 45,
                QueryWindow.unbounded(), null);

        assertEquals(TracePage.Resolution.AMBIGUOUS, page.resolution());
        assertEquals(List.of(numericId, namedFingerprint),
                page.candidates().stream().map(FingerprintRef::id).toList());
        assertEquals(TracePage.Resolution.RESOLVED, forcedId.resolution());
        assertEquals(numericId, forcedId.fingerprint().id());
    }

    @Test
    void itemPageSurfacesAmbiguousFingerprintCandidatesInsteadOfChoosingOne() throws Exception {
        long diamond = insertFingerprint("minecraft:diamond", "hash-diamond");
        long sword = insertFingerprint("minecraft:diamond_sword", "hash-sword");

        TracePage page = service.traceItemPage(conn, "minecraft:diamond", 45,
                QueryWindow.unbounded(), null);

        assertEquals(TracePage.Resolution.AMBIGUOUS, page.resolution());
        assertEquals(List.of(sword, diamond), page.candidates().stream().map(FingerprintRef::id).toList());
        assertTrue(page.hops().isEmpty());
    }

    @Test
    void duplicatePlayerAndContainerTargetsRemainAmbiguous() throws Exception {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_nodes (node_type, owner_uuid, level_id, custom_label)
                VALUES ('PLAYER', ?, 'minecraft:overworld', 'Alex')
                """)) {
            pstmt.setString(1, "uuid-alex-a");
            pstmt.executeUpdate();
            pstmt.setString(1, "uuid-alex-b");
            pstmt.executeUpdate();
        }
        insertContainerNode(10, 64, 10);
        insertContainerNode(10, 64, 10);

        TracePage playerPage = service.tracePlayerPage(conn, "Alex", 45,
                QueryWindow.unbounded(), null);
        TracePage containerPage = service.traceContainerPage(conn, "minecraft:overworld", 10, 64, 10, 45,
                QueryWindow.unbounded(), null);

        assertEquals(TracePage.Resolution.AMBIGUOUS, playerPage.resolution());
        assertEquals(2, playerPage.nodeCandidates().size());
        assertEquals(TracePage.Resolution.AMBIGUOUS, containerPage.resolution());
        assertEquals(2, containerPage.nodeCandidates().size());
        assertTrue(playerPage.hops().isEmpty());
        assertTrue(containerPage.hops().isEmpty());
    }

    @Test
    void playerLookupDoesNotResolveASubstringToAnotherPlayersNode() throws Exception {
        long alex2 = insertPlayerNode("Alex2");
        long ground = insertGroundNode(20, 64, 20);
        long fingerprint = insertFingerprint("minecraft:diamond", "hash-diamond");
        insertObservation(now - 1_000, alex2, ground, fingerprint, "DROP_ITEM", 1);

        TracePage page = service.tracePlayerPage(conn, "Alex", 45, QueryWindow.unbounded(), null);
        TraceResult trace = service.tracePlayer(conn, "Alex", QueryLimits.DEFAULT_LIMIT, QueryWindow.unbounded());

        assertEquals(TracePage.Resolution.NOT_FOUND, page.resolution());
        assertTrue(page.hops().isEmpty());
        assertTrue(trace.hops().isEmpty());
    }

    @Test
    void playerAndDimensionQualifiedContainerPagesMatchTheirTraceTargets() throws Exception {
        long player = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long playerObservation = insertObservation(now - 2_000, player, ground, fp, "DROP_ITEM", 2);
        long playerEdge = insertEdge(player, ground, fp, 2, now - 2_000, now - 1_000, 0.9, "player timeline");
        long overworldContainer = insertContainerNode(10, 64, 10);
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_nodes (node_type, level_id, x, y, z)
                VALUES ('CONTAINER', 'minecraft:the_nether', 10.49, 64, 10)
                """)) {
            pstmt.executeUpdate();
        }
        long netherContainer = insertContainerNode(10, 64, 10);
        try (PreparedStatement pstmt = conn.prepareStatement("UPDATE ig_nodes SET level_id = 'minecraft:the_nether' WHERE id = ?")) {
            pstmt.setLong(1, netherContainer);
            pstmt.executeUpdate();
        }
        long overworldEvent = insertObservation(now - 1_000, overworldContainer, player, fp, "REMOVE_ITEM", 1);
        long netherEvent = insertObservation(now - 900, netherContainer, player, fp, "REMOVE_ITEM", 1);

        TracePage playerPage = service.tracePlayerPage(conn, "AlphaA", 45,
                QueryWindow.unbounded(), null);
        TracePage netherPage = service.traceContainerPage(conn, "minecraft:the_nether", 10, 64, 10, 45,
                QueryWindow.unbounded(), null);
        TracePage pinnedPlayerPage = service.tracePlayerNodePage(conn, playerPage.targetNode().id(), 45,
                QueryWindow.unbounded(), null, TracePage.Direction.FORWARD);
        TracePage pinnedContainerPage = service.traceContainerNodePage(conn, netherPage.targetNode().id(), 45,
                QueryWindow.unbounded(), null, TracePage.Direction.FORWARD);

        assertEquals(player, playerPage.targetNode().id());
        assertEquals(netherContainer, netherPage.targetNode().id());
        assertEquals(playerPage.targetNode(), pinnedPlayerPage.targetNode());
        assertEquals(netherPage.targetNode(), pinnedContainerPage.targetNode());
        assertEquals(List.of(playerObservation, playerEdge, overworldEvent, netherEvent),
                playerPage.hops().stream().map(TraceHop::refId).toList());
        assertEquals(List.of(netherEvent), netherPage.hops().stream().map(TraceHop::refId).toList());
        assertFalse(netherPage.hops().stream().anyMatch(hop -> hop.refId() == overworldEvent));
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

        QueryWindow huge = QueryWindow.lastMinutes(Long.MAX_VALUE, now);
        assertEquals(now - Long.MAX_VALUE, huge.sinceMs());
        assertEquals(now, huge.untilMs());
        assertDoesNotThrow(huge::describe);
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
                assertTrue(line.contains("observation#" + hop.refId()), line);
            }
        }

        assertTrue(out.contains("2 observed hops, 1 inferred hop."), out);
        assertTrue(out.contains("/ig explain"), "the output must say how to see the evidence");
    }
}
