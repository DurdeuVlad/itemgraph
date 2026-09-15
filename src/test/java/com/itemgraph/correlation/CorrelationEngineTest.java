package com.itemgraph.correlation;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.GriefLoggerAdapter;
import com.itemgraph.ingest.IngestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 5 correlation behaviour.
 *
 * <p>Most scenarios seed {@code ig_observations} directly rather than going through
 * ingestion: the engine's contract is defined over observation rows, and building them
 * by hand is what lets a test state "these two rows and nothing else" precisely. The
 * MVP chain test at the bottom deliberately does the opposite and drives the whole
 * pipeline from a mock GriefLogger database, because the thing it has to prove is that
 * ingestion direction and correlation agree end to end.
 *
 * <p>Timestamps are expressed relative to {@code now} so that "the correlation window
 * has closed" is a real property of the data rather than a sleep.
 */
class CorrelationEngineTest {

    private static final long WINDOW_SECONDS = 300L;
    private static final long WINDOW_MS = WINDOW_SECONDS * 1000L;

    /** Comfortably older than the window, so a pass finalises rather than defers. */
    private static final long CLOSED = 10L * 60L * 1000L;

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private CorrelationEngine engine;
    private Connection conn;
    private long now;

    private long sourceEventSeq = 1;

    @BeforeEach
    void setUp() throws Exception {
        dbManager = DatabaseManager.getInstance();
        dbManager.initialize(tempDir.resolve("itemgraph.db"));
        conn = dbManager.getConnection();
        engine = new CorrelationEngine(dbManager, WINDOW_SECONDS);
        now = System.currentTimeMillis();
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    /**
     * The MVP link: A drops a stack, B picks the same stack up off the same block a
     * minute later. Nothing else competes. This must produce exactly one inferred edge
     * A -> B, citing both observations, at the unambiguous confidence.
     */
    @Test
    void testDropThenPickupProducesGroundBridge() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        long pickupObs = insertObservation(dropTime + 60_000, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        assertEquals(2, result.observationsFinalised(), "both the drop and the pickup are evaluated");
        assertEquals(0, result.deferred());

        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size());
        Edge edge = edges.get(0);

        assertEquals(playerA, edge.fromNodeId(), "the edge bridges OVER the ground node, from dropper...");
        assertEquals(playerB, edge.toNodeId(), "...to the player who recovered the stack");
        assertEquals(fp, edge.fingerprintId());
        assertEquals(1, edge.amount());
        assertEquals(dropTime, edge.timeStart());
        assertEquals(dropTime + 60_000, edge.timeEnd());

        // base 0.95 x proximity(60s of a 300s window) 0.95 x 1.0 x 1.0
        assertEquals(0.9025, edge.confidence(), 1e-9);

        // The explanation must let an admin answer "why" without re-running the engine.
        assertTrue(edge.explanation().contains("AlphaA"), edge.explanation());
        assertTrue(edge.explanation().contains("BetaB"), edge.explanation());
        assertTrue(edge.explanation().contains("observation " + dropObs), edge.explanation());
        assertTrue(edge.explanation().contains("observation " + pickupObs), edge.explanation());
        assertTrue(edge.explanation().contains("Confidence 0.9025"), edge.explanation());

        assertEquals(List.of(dropObs, pickupObs), evidenceFor(edge.id()));

        assertNotNull(correlatedAt(dropObs));
        assertNotNull(correlatedAt(pickupObs));
    }

    /**
     * Two players pick up an identical stack off the same block after one drop. Only one
     * of them can be holding the dropped items, so the engine picks the temporally
     * closest and must report materially lower confidence than the unopposed case —
     * competing candidates weaken a claim, they do not get discarded silently.
     */
    @Test
    void testCompetingPickupsLowerConfidence() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        insertObservation(dropTime + 60_000, ground, playerB, fp, "PICKUP_ITEM", 1);
        insertObservation(dropTime + 120_000, ground, playerC, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated(), "one drop can only explain one pickup");

        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size());
        Edge edge = edges.get(0);

        assertEquals(playerB, edge.toNodeId(), "the temporally closest pickup wins");

        // base 0.95 x proximity 0.95 x pickupAmbiguity (0.5 + 0.5 * 60s/300s = 0.6) x 1.0
        assertEquals(0.5415, edge.confidence(), 1e-9);
        assertTrue(edge.confidence() < 0.9025,
                "an ambiguous match must score below the unopposed equivalent");
        assertTrue(edge.explanation().contains("2 admissible pickups"), edge.explanation());
        assertTrue(edge.explanation().contains("not the only one"), edge.explanation());
    }

    /**
     * Mirror image: one pickup that two separate drops could equally explain. The
     * reverse ambiguity is just as real, so it must reduce confidence too — and the
     * second drop must not be allowed to claim the same pickup as evidence, because
     * one stack cannot be picked up twice.
     */
    @Test
    void testCompetingDropsLowerConfidenceAndPickupIsNotReused() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerD = insertPlayerNode("DeltaD");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        long dropA = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        long dropD = insertObservation(dropTime + 10_000, playerD, ground, fp, "DROP_ITEM", 1);
        long pickup = insertObservation(dropTime + 60_000, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated(), "the single pickup can only support one bridge");
        assertEquals(3, result.observationsFinalised());

        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size());
        Edge edge = edges.get(0);
        assertEquals(playerA, edge.fromNodeId(), "the earlier drop is evaluated first and claims the pickup");

        // base 0.95 x proximity 0.95 x 1.0 x dropAmbiguity (0.5 + 0.5 * 10s/300s)
        assertEquals(0.4663, edge.confidence(), 1e-9);
        assertTrue(edge.explanation().contains("2 admissible drops"), edge.explanation());

        assertEquals(List.of(dropA, pickup), evidenceFor(edge.id()));

        // The losing drop is still finalised: it was evaluated, it just has no match left.
        assertNotNull(correlatedAt(dropD));
        assertTrue(evidenceObservationIds().stream().noneMatch(id -> id == dropD),
                "the second drop must not cite an already-consumed pickup");
    }

    /**
     * A drop nobody ever recovered. The correct outcome is a recorded negative: the
     * observation is stamped so it is never re-evaluated, no edge is invented, and a
     * second pass does no work at all.
     */
    @Test
    void testNoCandidateStampsObservationAndSecondPassDoesNoWork() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropObs = insertObservation(now - CLOSED, playerA, ground, fp, "DROP_ITEM", 1);

        CorrelationResult first = engine.runCorrelation();
        assertTrue(first.success(), first.errorMessage());
        assertEquals(0, first.edgesCreated());
        assertEquals(1, first.observationsFinalised());
        assertEquals(0, first.deferred());

        Long stamp = correlatedAt(dropObs);
        assertNotNull(stamp, "a window that closed with no candidate is still an evaluated observation");
        assertEquals(0, countEdges());

        CorrelationResult second = engine.runCorrelation();
        assertTrue(second.success(), second.errorMessage());
        assertEquals(0, second.observationsFinalised(), "already-evaluated observations must not be rescanned");
        assertEquals(0, second.edgesCreated());
        assertEquals(0, second.deferred());
        assertEquals(stamp, correlatedAt(dropObs), "the stamp must not be rewritten by a later pass");
    }

    /**
     * A drop whose window is still open is deliberately left pending: the pickup that
     * explains it may simply not have been ingested yet. Writing it off here would
     * permanently lose the link for any drop that lands near a cycle boundary.
     */
    @Test
    void testRecentDropWithoutCandidateIsDeferredNotFinalised() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropObs = insertObservation(now - 1_000, playerA, ground, fp, "DROP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals(0, result.observationsFinalised());
        assertEquals(1, result.deferred());
        assertNull(correlatedAt(dropObs), "a still-open window must stay pending");
    }

    /**
     * Temporal ordering: a pickup that happened BEFORE the drop cannot be what the drop
     * turned into. A later observation may explain an earlier event, never the reverse.
     */
    @Test
    void testPickupBeforeDropIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        // Well inside the window in absolute distance - only the direction is wrong.
        insertObservation(dropTime - 60_000, ground, playerB, fp, "PICKUP_ITEM", 1);
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals(0, countEdges());
        assertNotNull(correlatedAt(dropObs), "the drop was evaluated and found nothing admissible");
    }

    /**
     * A simultaneous pickup is rejected for the same reason: the engine requires the
     * pickup to be strictly later, since an equal timestamp evidences no ordering.
     */
    @Test
    void testSimultaneousPickupIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        insertObservation(dropTime, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals(0, countEdges());
    }

    /** A pickup past the far edge of the window is not a candidate. */
    @Test
    void testPickupOutsideWindowIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - (2 * CLOSED);
        insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        insertObservation(dropTime + WINDOW_MS + 1, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals(0, countEdges());
    }

    /**
     * Quantity mismatch. Phase 5 has no split/merge model, so a partial pickup is
     * deliberately left unmatched rather than guessed at: attributing 1 of 5 dropped
     * items to a pickup without a conservation model would manufacture a flow the
     * evidence does not support.
     */
    @Test
    void testQuantityMismatchIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 5);
        insertObservation(dropTime + 30_000, ground, playerB, fp, "PICKUP_ITEM", 3);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals(0, countEdges());
        assertNotNull(correlatedAt(dropObs));
    }

    /** A different item at the same block and time is not the same stack. */
    @Test
    void testFingerprintMismatchIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long diamond = insertFingerprint("minecraft:diamond", "hash-diamond");
        long emerald = insertFingerprint("minecraft:emerald", "hash-emerald");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, ground, diamond, "DROP_ITEM", 1);
        insertObservation(dropTime + 30_000, ground, playerB, emerald, "PICKUP_ITEM", 1);

        assertTrue(engine.runCorrelation().success());
        assertEquals(0, countEdges());
    }

    /** A pickup at a different block cannot be explained by this drop. */
    @Test
    void testDifferentGroundNodeIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long groundHere = insertGroundNode(20, 64, 20);
        long groundThere = insertGroundNode(500, 64, 500);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, groundHere, fp, "DROP_ITEM", 1);
        insertObservation(dropTime + 30_000, groundThere, playerB, fp, "PICKUP_ITEM", 1);

        assertTrue(engine.runCorrelation().success());
        assertEquals(0, countEdges());
    }

    /**
     * A pickup ingested in a later cycle than the drop it explains must still be
     * matched. This is the case the "defer while the window is open" rule exists for,
     * and it is the realistic one: ingestion runs every 60s, the window is minutes.
     */
    @Test
    void testPickupIngestedAfterDropIsStillMatchedOnALaterPass() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - 1_000;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);

        assertEquals(1, engine.runCorrelation().deferred());
        assertNull(correlatedAt(dropObs));

        // Next ingestion cycle brings in the pickup.
        long pickupObs = insertObservation(dropTime + 500, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult second = engine.runCorrelation();
        assertTrue(second.success(), second.errorMessage());
        assertEquals(1, second.edgesCreated());
        assertEquals(1, countEdges());
        assertEquals(List.of(dropObs, pickupObs), evidenceFor(loadEdges().get(0).id()));
    }

    /** THROW_ITEM and SHOOT_ITEM put items on the ground too and must bridge as well. */
    @Test
    void testThrowIsTreatedAsADrop() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:ender_pearl", "hash-pearl");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, ground, fp, "THROW_ITEM", 1);
        insertObservation(dropTime + 5_000, ground, playerB, fp, "PICKUP_ITEM", 1);

        assertEquals(1, engine.runCorrelation().edgesCreated());
        assertEquals(playerB, loadEdges().get(0).toNodeId());
    }

    // ------------------------------------------------------------------
    // Scoring functions
    // ------------------------------------------------------------------

    @Test
    void testConfidenceFactorsAreDeterministicAndBounded() {
        // proximity: 1.0 at zero delay, 1 - PROXIMITY_WEIGHT at the far edge.
        assertEquals(1.0, engine.proximityFactor(0), 1e-9);
        assertEquals(1.0 - CorrelationEngine.PROXIMITY_WEIGHT, engine.proximityFactor(WINDOW_MS), 1e-9);
        assertEquals(0.875, engine.proximityFactor(WINDOW_MS / 2), 1e-9);
        assertEquals(1.0 - CorrelationEngine.PROXIMITY_WEIGHT, engine.proximityFactor(WINDOW_MS * 10), 1e-9,
                "the penalty is clamped, never negative");

        // ambiguity: no competition costs nothing; n indistinguishable candidates
        // bottom out at the honest 1/n prior.
        assertEquals(1.0, engine.ambiguityFactor(1, 0), 1e-9);
        assertEquals(0.5, engine.ambiguityFactor(2, 0), 1e-9);
        assertEquals(0.25, engine.ambiguityFactor(4, 0), 1e-9);

        // Temporal separation is a real discriminator, so it recovers the score...
        assertEquals(0.6, engine.ambiguityFactor(2, WINDOW_MS / 5), 1e-9);
        // ...but only up to SEPARATION_CAP: a demonstrated alternative never vanishes.
        double capped = 0.5 + 0.5 * CorrelationEngine.SEPARATION_CAP;
        assertEquals(capped, engine.ambiguityFactor(2, WINDOW_MS), 1e-9);
        assertTrue(capped < 1.0, "an ambiguous match can never score as high as an unambiguous one");

        // Deterministic: same inputs, same output, every time.
        for (int i = 0; i < 5; i++) {
            assertEquals(0.6, engine.ambiguityFactor(2, WINDOW_MS / 5), 0.0);
            assertEquals(0.95, engine.proximityFactor(60_000), 0.0);
        }
    }

    @Test
    void testCorrelationFailsCleanlyWhenDatabaseIsClosed() {
        dbManager.close();
        CorrelationResult result = engine.runCorrelation();
        assertFalse(result.success());
        assertEquals(0, result.edgesCreated());
        assertNotNull(result.errorMessage());
    }

    // ------------------------------------------------------------------
    // Wiring: correlation must actually run on the ingestion worker
    // ------------------------------------------------------------------

    /**
     * Requirement, not decoration: the engine has to run by itself. This starts the real
     * {@link IngestionService} and waits for its scheduled executor to drive a
     * correlation pass, proving correlation is on the periodic path rather than being a
     * class that merely compiles.
     */
    @Test
    void testScheduledIngestionCycleAlsoRunsCorrelation() throws Exception {
        Path griefLoggerDb = tempDir.resolve("grieflogger.db");
        createGriefLoggerDatabase(griefLoggerDb);

        CountDownLatch correlated = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        CorrelationEngine recording = new CorrelationEngine(dbManager, WINDOW_SECONDS) {
            @Override
            public CorrelationResult runCorrelation() {
                threadName.set(Thread.currentThread().getName());
                CorrelationResult result = super.runCorrelation();
                correlated.countDown();
                return result;
            }
        };

        IngestionService service = new IngestionService(
                new GriefLoggerAdapter(griefLoggerDb), dbManager,
                new com.itemgraph.graph.NodeManager(), recording);
        try {
            service.start();
            assertTrue(correlated.await(30, TimeUnit.SECONDS),
                    "the scheduled ingestion cycle must trigger a correlation pass");
        } finally {
            service.stop();
        }

        assertNotNull(recording.getLastResult(), "the pass result must be exposed for /ig status");
        assertTrue(recording.getLastResult().success());
        assertTrue(threadName.get().contains("ItemGraph-Ingestion-Worker"),
                "correlation must never run on the server thread, but on " + threadName.get());
    }

    // ------------------------------------------------------------------
    // End-to-end MVP chain, including the Part A container-direction fix
    // ------------------------------------------------------------------

    /**
     * The charter's MVP vertical slice, driven from raw GriefLogger rows:
     *
     * <pre>Chest A -&gt; Player A -&gt; Ground -&gt; Player B -&gt; Chest B</pre>
     *
     * <p>Three of the four hops are single observations once direction is recorded
     * correctly, and this test asserts each of those directions — including the Part A
     * regression, where every containers-table row used to be written container -&gt;
     * player regardless of action, pointing the final deposit hop backwards. The one hop
     * that no single row evidences, Ground, is the inferred edge the correlation engine
     * contributes.
     */
    @Test
    void testMvpChainProducesObservedHopsPlusOneInferredGroundBridge() throws Exception {
        Path griefLoggerDb = tempDir.resolve("grieflogger.db");
        createGriefLoggerDatabase(griefLoggerDb);

        long t0 = now - CLOSED;
        try (Connection gl = DriverManager.getConnection("jdbc:sqlite:" + griefLoggerDb.toAbsolutePath());
             Statement stmt = gl.createStatement()) {
            stmt.execute("INSERT INTO users (id, name, uuid) VALUES (2, 'BetaB', '11111111-2222-3333-4444-555555555555');");

            // Hop 1: Player A withdraws the armour from Chest A (containers action 0 = REMOVE_ITEM).
            stmt.execute("INSERT INTO containers (time, user, level, x, y, z, type, amount, action) VALUES ("
                    + t0 + ", 1, 1, 10, 64, 10, 1, 1, 0);");
            // Hop 2: Player A drops it (items action 2 = DROP_ITEM).
            stmt.execute("INSERT INTO items (time, user, level, x, y, z, type, amount, action) VALUES ("
                    + (t0 + 10_000) + ", 1, 1, 20, 64, 20, 1, 1, 2);");
            // Hop 3: Player B picks it up off the same block (items action 3 = PICKUP_ITEM).
            stmt.execute("INSERT INTO items (time, user, level, x, y, z, type, amount, action) VALUES ("
                    + (t0 + 70_000) + ", 2, 1, 20, 64, 20, 1, 1, 3);");
            // Hop 4: Player B deposits it into Chest B (containers action 1 = ADD_ITEM).
            stmt.execute("INSERT INTO containers (time, user, level, x, y, z, type, amount, action) VALUES ("
                    + (t0 + 80_000) + ", 2, 1, 30, 64, 30, 1, 1, 1);");
        }

        IngestionService service = new IngestionService(new GriefLoggerAdapter(griefLoggerDb), dbManager);
        assertTrue(service.runIngestion().success());
        CorrelationResult correlation = engine.runCorrelation();
        assertTrue(correlation.success(), correlation.errorMessage());

        long playerA = playerNodeIdByLabel("AlphaA");
        long playerB = playerNodeIdByLabel("BetaB");
        long chestA = nodeIdAt("CONTAINER", 10, 64, 10);
        long chestB = nodeIdAt("CONTAINER", 30, 64, 30);
        long ground = nodeIdAt("GROUND", 20, 64, 20);

        // Hop 1 (observed): Chest A -> Player A.
        assertFlow("REMOVE_ITEM", chestA, playerA);
        // Hop 2 (observed): Player A -> Ground.
        assertFlow("DROP_ITEM", playerA, ground);
        // Hop 3 (observed): Ground -> Player B.
        assertFlow("PICKUP_ITEM", ground, playerB);
        // Hop 4 (observed, Part A regression): Player B -> Chest B, NOT Chest B -> Player B.
        assertFlow("ADD_ITEM", playerB, chestB);

        // The only hop that needs inference: the two ground observations are separate
        // events, and nothing in GriefLogger states they concern the same stack.
        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size(), "exactly one hop of the MVP chain is an inference");
        Edge bridge = edges.get(0);
        assertEquals(playerA, bridge.fromNodeId());
        assertEquals(playerB, bridge.toNodeId());
        assertEquals(1, bridge.amount());
        // base 0.95 x proximity(60s/300s) 0.95, unopposed on both sides
        assertEquals(0.9025, bridge.confidence(), 1e-9);
        assertEquals(2, evidenceFor(bridge.id()).size(), "the inference cites both of its observations");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Edge(long id, long fromNodeId, long toNodeId, long fingerprintId, int amount,
                        long timeStart, long timeEnd, double confidence, String explanation) {
    }

    private long insertPlayerNode(String label) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, custom_label) VALUES ('PLAYER', ?, 'minecraft:overworld', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, "uuid-" + label);
            pstmt.setString(2, label);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    private long insertGroundNode(int x, int y, int z) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_nodes (node_type, level_id, x, y, z) VALUES ('GROUND', 'minecraft:overworld', ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setInt(1, x);
            pstmt.setInt(2, y);
            pstmt.setInt(3, z);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    private long insertFingerprint(String itemId, String hash) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_item_fingerprints (item_id, fingerprint_hash) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, itemId);
            pstmt.setString(2, hash);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    private long insertObservation(long timestampMs, long nodeId, Long targetNodeId, long fingerprintId,
                                   String actionType, int amount) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id,
                                             target_node_id, fingerprint_id, action_type, amount)
                VALUES ('TEST', ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, sourceEventSeq++);
            pstmt.setLong(2, timestampMs);
            pstmt.setLong(3, nodeId);
            if (targetNodeId != null) {
                pstmt.setLong(4, targetNodeId);
            } else {
                pstmt.setNull(4, Types.INTEGER);
            }
            pstmt.setLong(5, fingerprintId);
            pstmt.setString(6, actionType);
            pstmt.setInt(7, amount);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    private static long generatedKey(PreparedStatement pstmt) throws SQLException {
        try (ResultSet keys = pstmt.getGeneratedKeys()) {
            assertTrue(keys.next(), "insert must yield a generated key");
            return keys.getLong(1);
        }
    }

    private List<Edge> loadEdges() throws SQLException {
        List<Edge> edges = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM ig_inferred_edges ORDER BY id")) {
            while (rs.next()) {
                edges.add(new Edge(
                        rs.getLong("id"),
                        rs.getLong("from_node_id"),
                        rs.getLong("to_node_id"),
                        rs.getLong("fingerprint_id"),
                        rs.getInt("amount"),
                        rs.getLong("time_start"),
                        rs.getLong("time_end"),
                        rs.getDouble("confidence"),
                        rs.getString("explanation")));
            }
        }
        return edges;
    }

    private int countEdges() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_inferred_edges")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<Long> evidenceFor(long edgeId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT observation_id FROM ig_edge_evidence WHERE edge_id = ? ORDER BY observation_id")) {
            pstmt.setLong(1, edgeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    private List<Long> evidenceObservationIds() throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT observation_id FROM ig_edge_evidence")) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    private Long correlatedAt(long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT correlated_at FROM ig_observations WHERE id = ?")) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "observation " + observationId + " should exist");
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        }
    }

    private void assertFlow(String actionType, long expectedOrigin, long expectedDestination) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT node_id, target_node_id FROM ig_observations WHERE action_type = ?")) {
            pstmt.setString(1, actionType);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), actionType + " observation should exist");
                assertEquals(expectedOrigin, rs.getLong("node_id"), actionType + " origin");
                assertEquals(expectedDestination, rs.getLong("target_node_id"), actionType + " destination");
                assertFalse(rs.next(), "expected exactly one " + actionType + " observation");
            }
        }
    }

    private long playerNodeIdByLabel(String label) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id FROM ig_nodes WHERE node_type = 'PLAYER' AND custom_label = ?")) {
            pstmt.setString(1, label);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "player node " + label + " should exist");
                return rs.getLong("id");
            }
        }
    }

    private long nodeIdAt(String nodeType, int x, int y, int z) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id FROM ig_nodes WHERE node_type = ? AND x = ? AND y = ? AND z = ?")) {
            pstmt.setString(1, nodeType);
            pstmt.setDouble(2, x);
            pstmt.setDouble(3, y);
            pstmt.setDouble(4, z);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), nodeType + " node at " + x + "," + y + "," + z + " should exist");
                return rs.getLong("id");
            }
        }
    }

    /** Minimal read-only stand-in for GriefLogger's schema, matching Phase 0 recon. */
    private static void createGriefLoggerDatabase(Path path) throws SQLException {
        try (Connection gl = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
             Statement stmt = gl.createStatement()) {
            stmt.execute("CREATE TABLE materials (id integer PRIMARY KEY, name text NOT NULL UNIQUE);");
            stmt.execute("CREATE TABLE users (id integer PRIMARY KEY, name text NOT NULL, uuid text DEFAULT NULL UNIQUE);");
            stmt.execute("CREATE TABLE levels (id integer PRIMARY KEY, name text NOT NULL UNIQUE);");
            stmt.execute("""
                CREATE TABLE items (
                    time integer NOT NULL, user integer NOT NULL, level integer NOT NULL,
                    x integer NOT NULL, y integer NOT NULL, z integer NOT NULL,
                    type integer NOT NULL, data blob DEFAULT NULL,
                    amount integer NOT NULL, action integer NOT NULL
                );
            """);
            stmt.execute("""
                CREATE TABLE containers (
                    time integer NOT NULL, user integer NOT NULL, level integer NOT NULL,
                    x integer NOT NULL, y integer NOT NULL, z integer NOT NULL,
                    type integer NOT NULL, data blob DEFAULT NULL,
                    amount integer NOT NULL, action integer NOT NULL
                );
            """);
            stmt.execute("INSERT INTO users (id, name, uuid) VALUES (1, 'AlphaA', 'f0b5d9d8-2cac-3599-aa08-a61237f4e827');");
            stmt.execute("INSERT INTO levels (id, name) VALUES (1, 'minecraft:overworld');");
            stmt.execute("INSERT INTO materials (id, name) VALUES (1, 'netherite_chestplate');");
        }
    }
}
