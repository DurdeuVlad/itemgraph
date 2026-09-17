package com.itemgraph.correlation;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 quantity-flow and stack reconstruction test suite.
 *
 * <p>Validates strict quantity conservation without per-item UUIDs:
 * <ul>
 *     <li>Exact 1-to-1 and full stack flows</li>
 *     <li>One-to-many stack splits (drop 64 -> pickup 20 + pickup 44)</li>
 *     <li>Many-to-one stack merges (drop 20 + drop 30 -> pickup 50)</li>
 *     <li>Partial transfers with open window vs closed window accounting</li>
 *     <li>Many-to-many multi-fragment flows</li>
 *     <li>Over-capacity protections for source and destination</li>
 *     <li>Ambiguity handling with competing candidates</li>
 *     <li>Temporal and spatial boundary validation</li>
 *     <li>Engine idempotency and restart continuity</li>
 *     <li>Single-transaction atomicity</li>
 * </ul>
 */
class QuantityFlowTest {

    private static final long WINDOW_SECONDS = 300L;
    private static final long CLOSED = 10L * 60L * 1000L; // 10 minutes ago, comfortably closed

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
    // 1. Exact 1-to-1 Regression
    // ------------------------------------------------------------------

    @Test
    void testExactOneToOneRegression() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(10, 64, 10);
        long fp = insertFingerprint("minecraft:netherite_chestplate", "hash-armor-1");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 1);
        long pickupObs = insertObservation(dropTime + 30_000, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        assertEquals(2, result.observationsFinalised());

        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size());
        Edge edge = edges.get(0);
        assertEquals(playerA, edge.fromNodeId());
        assertEquals(playerB, edge.toNodeId());
        assertEquals(1, edge.amount());
        assertTrue(edge.explanation().contains("exact transfer"));

        assertEquals("FULLY_ALLOCATED", observationStatus(dropObs));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickupObs));
        assertNotNull(correlatedAt(dropObs));
        assertNotNull(correlatedAt(pickupObs));

        List<Allocation> allocations = loadAllocations();
        assertEquals(2, allocations.size());
        assertEquals(1, allocations.get(0).amount());
        assertEquals(1, allocations.get(1).amount());
    }

    // ------------------------------------------------------------------
    // 2. Exact Full Stack Regression (64 -> 64)
    // ------------------------------------------------------------------

    @Test
    void testExactFullStackRegression() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(10, 64, 10);
        long fp = insertFingerprint("minecraft:torch", "hash-torch-64");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        long pickupObs = insertObservation(dropTime + 45_000, ground, playerB, fp, "PICKUP_ITEM", 64);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        assertEquals(2, result.observationsFinalised());

        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size());
        Edge edge = edges.get(0);
        assertEquals(64, edge.amount());
        assertTrue(edge.explanation().contains("exact transfer"));
        assertTrue(edge.explanation().contains("Allocated 64 units"));

        assertEquals("FULLY_ALLOCATED", observationStatus(dropObs));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickupObs));

        List<Allocation> allocations = loadAllocations();
        assertEquals(2, allocations.size());
        for (Allocation alloc : allocations) {
            assertEquals(64, alloc.amount());
        }
    }

    // ------------------------------------------------------------------
    // 3. One-to-Many Stack Split (64 -> 20 + 44)
    // ------------------------------------------------------------------

    @Test
    void testOneToManyStackSplit() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(15, 64, 15);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        long pickup1 = insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 20);
        long pickup2 = insertObservation(dropTime + 20_000, ground, playerC, fp, "PICKUP_ITEM", 44);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, result.edgesCreated());
        assertEquals(3, result.observationsFinalised());

        List<Edge> edges = loadEdges();
        assertEquals(2, edges.size());

        Edge edge1 = edges.get(0);
        assertEquals(playerA, edge1.fromNodeId());
        assertEquals(playerB, edge1.toNodeId());
        assertEquals(20, edge1.amount());
        assertTrue(edge1.explanation().contains("stack split"));

        Edge edge2 = edges.get(1);
        assertEquals(playerA, edge2.fromNodeId());
        assertEquals(playerC, edge2.toNodeId());
        assertEquals(44, edge2.amount());
        assertTrue(edge2.explanation().contains("stack split"));

        assertEquals("FULLY_ALLOCATED", observationStatus(dropObs));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup1));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup2));

        assertEquals(64, totalAllocatedForObservation(dropObs));
        assertEquals(20, totalAllocatedForObservation(pickup1));
        assertEquals(44, totalAllocatedForObservation(pickup2));
    }

    // ------------------------------------------------------------------
    // 4. Partial Split: Open Window vs Closed Window
    // ------------------------------------------------------------------

    @Test
    void testPartialSplitOpenWindowPreservesResidualForFuturePickups() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:iron_ingot", "hash-iron");

        // Drop occurred 30s ago (within 300s window)
        long dropTime = now - 30_000L;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        long pickupObs = insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 20);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        // Drop is NOT finalised because window is open and residual 44 remains
        assertEquals(1, result.observationsFinalised());
        assertEquals(1, result.deferred());

        assertEquals("PARTIALLY_ALLOCATED", observationStatus(dropObs));
        assertNull(correlatedAt(dropObs), "open window drop with residual must not be marked correlated_at");
        assertEquals("FULLY_ALLOCATED", observationStatus(pickupObs));
        assertNotNull(correlatedAt(pickupObs));

        assertEquals(20, totalAllocatedForObservation(dropObs));
    }

    @Test
    void testPartialSplitClosedWindowMarksUnresolved() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:iron_ingot", "hash-iron");

        // Drop occurred outside the correlation window
        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        long pickupObs = insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 20);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        assertEquals(2, result.observationsFinalised(), "both drop and pickup finalised once window closed");

        assertEquals("CLOSED_UNRESOLVED", observationStatus(dropObs));
        assertNotNull(correlatedAt(dropObs), "closed window drop must have correlated_at set");
        assertEquals("FULLY_ALLOCATED", observationStatus(pickupObs));

        assertEquals(20, totalAllocatedForObservation(dropObs));
    }

    // ------------------------------------------------------------------
    // 5. Many-to-One Stack Merge (20 + 30 -> 50)
    // ------------------------------------------------------------------

    @Test
    void testManyToOneStackMerge() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(25, 64, 25);
        long fp = insertFingerprint("minecraft:gold_ingot", "hash-gold");

        long dropTime = now - CLOSED;
        long drop1 = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 20);
        long drop2 = insertObservation(dropTime + 5_000, playerB, ground, fp, "DROP_ITEM", 30);
        long pickup = insertObservation(dropTime + 20_000, ground, playerC, fp, "PICKUP_ITEM", 50);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, result.edgesCreated());
        assertEquals(3, result.observationsFinalised());

        List<Edge> edges = loadEdges();
        assertEquals(2, edges.size());

        // First drop (20) allocated
        Edge e1 = edges.get(0);
        assertEquals(playerA, e1.fromNodeId());
        assertEquals(playerC, e1.toNodeId());
        assertEquals(20, e1.amount());
        assertTrue(e1.explanation().contains("stack merge"));

        // Second drop (30) allocated
        Edge e2 = edges.get(1);
        assertEquals(playerB, e2.fromNodeId());
        assertEquals(playerC, e2.toNodeId());
        assertEquals(30, e2.amount());
        assertTrue(e2.explanation().contains("stack merge"));

        assertEquals("FULLY_ALLOCATED", observationStatus(drop1));
        assertEquals("FULLY_ALLOCATED", observationStatus(drop2));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup));

        assertEquals(20, totalAllocatedForObservation(drop1));
        assertEquals(30, totalAllocatedForObservation(drop2));
        assertEquals(50, totalAllocatedForObservation(pickup));
    }

    // ------------------------------------------------------------------
    // 6. Partial Merge (20 + 30 -> 40)
    // ------------------------------------------------------------------

    @Test
    void testPartialMergePreservesDropResidual() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(30, 64, 30);
        long fp = insertFingerprint("minecraft:copper_ingot", "hash-copper");

        long dropTime = now - CLOSED;
        long drop1 = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 20);
        long drop2 = insertObservation(dropTime + 5_000, playerB, ground, fp, "DROP_ITEM", 30);
        long pickup = insertObservation(dropTime + 20_000, ground, playerC, fp, "PICKUP_ITEM", 40);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, result.edgesCreated());

        List<Edge> edges = loadEdges();
        assertEquals(2, edges.size());

        // Drop1 satisfies 20 of 40
        assertEquals(20, edges.get(0).amount());
        assertEquals(playerA, edges.get(0).fromNodeId());

        // Drop2 satisfies remaining 20 of 40 (leaving 10 residual in Drop2)
        assertEquals(20, edges.get(1).amount());
        assertEquals(playerB, edges.get(1).fromNodeId());

        assertEquals("FULLY_ALLOCATED", observationStatus(drop1));
        assertEquals("CLOSED_UNRESOLVED", observationStatus(drop2), "Drop2 had 10 unallocated residual and window closed");
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup));

        assertEquals(20, totalAllocatedForObservation(drop1));
        assertEquals(20, totalAllocatedForObservation(drop2));
        assertEquals(40, totalAllocatedForObservation(pickup));
    }

    // ------------------------------------------------------------------
    // 7. Many-to-Many Flow (32 + 32 -> 20 + 44)
    // ------------------------------------------------------------------

    @Test
    void testManyToManyFlowConservesTotalQuantity() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long playerD = insertPlayerNode("DeltaD");
        long ground = insertGroundNode(35, 64, 35);
        long fp = insertFingerprint("minecraft:emerald", "hash-emerald");

        long dropTime = now - CLOSED;
        long drop1 = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 32);
        long drop2 = insertObservation(dropTime + 5_000, playerB, ground, fp, "DROP_ITEM", 32);
        long pickup1 = insertObservation(dropTime + 20_000, ground, playerC, fp, "PICKUP_ITEM", 20);
        long pickup2 = insertObservation(dropTime + 30_000, ground, playerD, fp, "PICKUP_ITEM", 44);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        // drop1(32) -> pickup1(20) [leaves 12 in drop1]
        // drop1(12 remaining) -> pickup2(12) [drop1 exhausted; leaves 32 needed in pickup2]
        // drop2(32) -> pickup2(32) [both drop2 and pickup2 exhausted]
        assertEquals(3, result.edgesCreated());
        assertEquals(4, result.observationsFinalised());

        int totalAllocated = 0;
        for (Edge edge : loadEdges()) {
            totalAllocated += edge.amount();
        }
        assertEquals(64, totalAllocated, "total allocated across all edges must equal total dropped (64)");

        assertEquals("FULLY_ALLOCATED", observationStatus(drop1));
        assertEquals("FULLY_ALLOCATED", observationStatus(drop2));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup1));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup2));

        assertEquals(32, totalAllocatedForObservation(drop1));
        assertEquals(32, totalAllocatedForObservation(drop2));
        assertEquals(20, totalAllocatedForObservation(pickup1));
        assertEquals(44, totalAllocatedForObservation(pickup2));
    }

    // ------------------------------------------------------------------
    // 8. Destination Over-Capacity Protection
    // ------------------------------------------------------------------

    @Test
    void testDestinationOverCapacityProtection() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(40, 64, 40);
        long fp = insertFingerprint("minecraft:lapis_lazuli", "hash-lapis");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        long pickupObs = insertObservation(dropTime + 15_000, ground, playerB, fp, "PICKUP_ITEM", 20);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());

        Edge edge = loadEdges().get(0);
        assertEquals(20, edge.amount(), "destination only picked up 20, cannot allocate 64");
        assertEquals(20, totalAllocatedForObservation(pickupObs));
    }

    // ------------------------------------------------------------------
    // 9. Source Over-Capacity Protection
    // ------------------------------------------------------------------

    @Test
    void testSourceOverCapacityProtection() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(45, 64, 45);
        long fp = insertFingerprint("minecraft:redstone", "hash-redstone");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 20);
        long pickup1 = insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 15);
        long pickup2 = insertObservation(dropTime + 20_000, ground, playerC, fp, "PICKUP_ITEM", 15);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(2, result.edgesCreated());

        List<Edge> edges = loadEdges();
        assertEquals(15, edges.get(0).amount(), "pickup1 gets 15 from drop");
        assertEquals(5, edges.get(1).amount(), "pickup2 can only get 5 remaining from drop (source capacity = 20)");

        assertEquals("FULLY_ALLOCATED", observationStatus(dropObs));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup1));
        assertEquals("CLOSED_UNRESOLVED", observationStatus(pickup2), "pickup2 only got 5 of 15; residual 10 unresolved");

        assertEquals(20, totalAllocatedForObservation(dropObs));
        assertEquals(15, totalAllocatedForObservation(pickup1));
        assertEquals(5, totalAllocatedForObservation(pickup2));
    }

    // ------------------------------------------------------------------
    // 10. Competing Identical Sources
    // ------------------------------------------------------------------

    @Test
    void testCompetingIdenticalSourcesEvaluatesEarlierDropAndAppliesPenalty() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(50, 64, 50);
        long fp = insertFingerprint("minecraft:coal", "hash-coal");

        long t0 = now - CLOSED;
        long dropFar = insertObservation(t0, playerA, ground, fp, "DROP_ITEM", 32);
        long dropNear = insertObservation(t0 + 20_000, playerB, ground, fp, "DROP_ITEM", 32);
        long pickup = insertObservation(t0 + 30_000, ground, playerC, fp, "PICKUP_ITEM", 32);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());

        Edge edge = loadEdges().get(0);
        assertEquals(playerA, edge.fromNodeId(), "chronological scan evaluates earlier drop first (AlphaA)");
        assertEquals(playerC, edge.toNodeId());
        assertEquals(32, edge.amount());

        // Competing drop candidate lowers drop-ambiguity factor
        assertTrue(edge.confidence() < 0.90, "competing candidate must decrease confidence: " + edge.confidence());
        assertTrue(edge.explanation().contains("competing drop"), edge.explanation());

        assertEquals("FULLY_ALLOCATED", observationStatus(dropFar));
        assertEquals("CLOSED_UNRESOLVED", observationStatus(dropNear));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup));
    }

    // ------------------------------------------------------------------
    // 11. Competing Identical Destinations
    // ------------------------------------------------------------------

    @Test
    void testCompetingIdenticalDestinationsChoosesClosestAndAppliesPenalty() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(55, 64, 55);
        long fp = insertFingerprint("minecraft:quartz", "hash-quartz");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 32);
        long pickupNear = insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 32);
        long pickupFar = insertObservation(dropTime + 30_000, ground, playerC, fp, "PICKUP_ITEM", 32);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());

        Edge edge = loadEdges().get(0);
        assertEquals(playerA, edge.fromNodeId());
        assertEquals(playerB, edge.toNodeId(), "engine must pick the temporally closer pickup (BetaB)");
        assertEquals(32, edge.amount());

        assertTrue(edge.confidence() < 0.90, "competing pickup must lower confidence: " + edge.confidence());
        assertTrue(edge.explanation().contains("next-best was"), edge.explanation());

        assertEquals("FULLY_ALLOCATED", observationStatus(dropObs));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickupNear));
        assertEquals("CLOSED_UNRESOLVED", observationStatus(pickupFar));
    }

    // ------------------------------------------------------------------
    // 12. Temporal Ordering & Window Boundaries
    // ------------------------------------------------------------------

    @Test
    void testPickupBeforeDropIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(60, 64, 60);
        long fp = insertFingerprint("minecraft:arrow", "hash-arrow");

        long t0 = now - CLOSED;
        // Pickup occurs 10s BEFORE drop
        insertObservation(t0 - 10_000, ground, playerB, fp, "PICKUP_ITEM", 16);
        insertObservation(t0, playerA, ground, fp, "DROP_ITEM", 16);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated(), "pickup before drop must never produce an edge");
    }

    @Test
    void testPickupOutsideWindowIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(65, 64, 65);
        long fp = insertFingerprint("minecraft:bow", "hash-bow");

        long t0 = now - CLOSED;
        // Pickup occurs 400s after drop (window is 300s)
        insertObservation(t0, playerA, ground, fp, "DROP_ITEM", 1);
        insertObservation(t0 + 400_000L, ground, playerB, fp, "PICKUP_ITEM", 1);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated(), "pickup outside 300s window must not match");
    }

    // ------------------------------------------------------------------
    // 13. Mismatch Checks
    // ------------------------------------------------------------------

    @Test
    void testFingerprintMismatchIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(70, 64, 70);
        long fp1 = insertFingerprint("minecraft:diamond", "hash-1");
        long fp2 = insertFingerprint("minecraft:emerald", "hash-2");

        long t0 = now - CLOSED;
        insertObservation(t0, playerA, ground, fp1, "DROP_ITEM", 10);
        insertObservation(t0 + 10_000, ground, playerB, fp2, "PICKUP_ITEM", 10);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
    }

    @Test
    void testGroundLocationMismatchIsRejected() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long groundA = insertGroundNode(75, 64, 75);
        long groundB = insertGroundNode(80, 64, 80);
        long fp = insertFingerprint("minecraft:apple", "hash-apple");

        long t0 = now - CLOSED;
        insertObservation(t0, playerA, groundA, fp, "DROP_ITEM", 5);
        insertObservation(t0 + 10_000, groundB, playerB, fp, "PICKUP_ITEM", 5);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated(), "different ground nodes must not bridge");
    }

    // ------------------------------------------------------------------
    // 14. Idempotency Over Unchanged Data
    // ------------------------------------------------------------------

    @Test
    void testDuplicateCorrelationRunIsIdempotent() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(85, 64, 85);
        long fp = insertFingerprint("minecraft:bread", "hash-bread");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 64);

        CorrelationResult firstRun = engine.runCorrelation();
        assertTrue(firstRun.success());
        assertEquals(1, firstRun.edgesCreated());
        assertEquals(2, firstRun.observationsFinalised());

        CorrelationResult secondRun = engine.runCorrelation();
        assertTrue(secondRun.success());
        assertEquals(0, secondRun.edgesCreated(), "second run over unchanged data must create 0 edges");
        assertEquals(0, secondRun.observationsFinalised());

        assertEquals(1, countEdges());
        assertEquals(2, loadAllocations().size());
    }

    // ------------------------------------------------------------------
    // 15. Restart Simulation and Continuity
    // ------------------------------------------------------------------

    @Test
    void testRestartSimulationPreservesLedgerAndCompletesSplit() throws Exception {
        Path dbPath = tempDir.resolve("itemgraph.db");

        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long playerC = insertPlayerNode("GammaC");
        long ground = insertGroundNode(90, 64, 90);
        long fp = insertFingerprint("minecraft:netherite_ingot", "hash-ingot");

        long dropTime = now - 30_000L; // open window
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 64);
        long pickup1 = insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 20);

        // First pass: partial split 20 allocated, 44 residual remains in open window
        CorrelationResult res1 = engine.runCorrelation();
        assertTrue(res1.success(), res1.errorMessage());
        assertEquals(1, res1.edgesCreated());
        assertEquals(1, res1.deferred());

        // Simulate server restart: close dbManager and reopen it
        dbManager.close();
        assertFalse(dbManager.isInitialized());

        dbManager = DatabaseManager.getInstance();
        dbManager.initialize(dbPath);
        assertTrue(dbManager.isInitialized());
        assertEquals(8, dbManager.getCurrentSchemaVersion());
        conn = dbManager.getConnection();
        CorrelationEngine eng2 = new CorrelationEngine(dbManager, WINDOW_SECONDS);

        // Add second pickup of remaining 44 units
        long pickup2 = insertObservation(dropTime + 20_000, ground, playerC, fp, "PICKUP_ITEM", 44);

        // Run correlation again on new engine instance
        CorrelationResult res2 = eng2.runCorrelation();
        assertTrue(res2.success(), res2.errorMessage());
        assertEquals(1, res2.edgesCreated());

        assertEquals(2, countEdges());
        assertEquals("FULLY_ALLOCATED", observationStatus(dropObs));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup1));
        assertEquals("FULLY_ALLOCATED", observationStatus(pickup2));

        assertEquals(64, totalAllocatedForObservation(dropObs));
        assertEquals(20, totalAllocatedForObservation(pickup1));
        assertEquals(44, totalAllocatedForObservation(pickup2));
    }

    // ------------------------------------------------------------------
    // 16. Transaction Rollback Integrity
    // ------------------------------------------------------------------

    @Test
    void testTransactionRollbackOnFailureLeavesZeroResidualState() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(95, 64, 95);
        long fp = insertFingerprint("minecraft:sponge", "hash-sponge");

        long dropTime = now - CLOSED;
        insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 10);
        insertObservation(dropTime + 10_000, ground, playerB, fp, "PICKUP_ITEM", 10);

        // Create a trigger that deliberately breaks edge insertion to simulate failure
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TRIGGER fail_edge_insert BEFORE INSERT ON ig_inferred_edges " +
                    "BEGIN SELECT RAISE(ABORT, 'Simulated persistence failure'); END;");
        }

        CorrelationResult result = engine.runCorrelation();
        assertFalse(result.success(), "engine must report failure on SQL error");
        assertTrue(result.errorMessage().contains("Simulated persistence failure"));

        // Verify transaction atomicity: 0 edges, 0 allocations, observation unaffected
        assertEquals(0, countEdges());
        assertEquals(0, loadAllocations().size());

        // Remove trigger and verify normal correlation succeeds cleanly
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TRIGGER fail_edge_insert;");
        }

        CorrelationResult retryResult = engine.runCorrelation();
        assertTrue(retryResult.success());
        assertEquals(1, retryResult.edgesCreated());
        assertEquals(1, countEdges());
        assertEquals(2, loadAllocations().size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record Edge(long id, long fromNodeId, long toNodeId, long fingerprintId, int amount,
                        long timeStart, long timeEnd, double confidence, String explanation) {}

    private record Allocation(long edgeId, long observationId, String allocationRole, int amount) {}

    private long insertPlayerNode(String label) throws SQLException {
        return insertPlayerNode(conn, label);
    }

    private long insertPlayerNode(Connection c, String label) throws SQLException {
        try (PreparedStatement pstmt = c.prepareStatement(
                "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, custom_label) VALUES ('PLAYER', ?, 'minecraft:overworld', ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, "uuid-" + label);
            pstmt.setString(2, label);
            pstmt.executeUpdate();
            return generatedKey(pstmt);
        }
    }

    private long insertGroundNode(int x, int y, int z) throws SQLException {
        return insertGroundNode(conn, x, y, z);
    }

    private long insertGroundNode(Connection c, int x, int y, int z) throws SQLException {
        try (PreparedStatement pstmt = c.prepareStatement(
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
        return insertFingerprint(conn, itemId, hash);
    }

    private long insertFingerprint(Connection c, String itemId, String hash) throws SQLException {
        try (PreparedStatement pstmt = c.prepareStatement(
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
        return insertObservation(conn, timestampMs, nodeId, targetNodeId, fingerprintId, actionType, amount);
    }

    private long insertObservation(Connection c, long timestampMs, long nodeId, Long targetNodeId, long fingerprintId,
                                   String actionType, int amount) throws SQLException {
        try (PreparedStatement pstmt = c.prepareStatement("""
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
        return loadEdges(conn);
    }

    private List<Edge> loadEdges(Connection c) throws SQLException {
        List<Edge> edges = new ArrayList<>();
        try (Statement stmt = c.createStatement();
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
        return countEdges(conn);
    }

    private int countEdges(Connection c) throws SQLException {
        try (Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_inferred_edges")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<Allocation> loadAllocations() throws SQLException {
        List<Allocation> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT edge_id, observation_id, allocation_role, amount FROM ig_edge_allocations ORDER BY edge_id, observation_id")) {
            while (rs.next()) {
                list.add(new Allocation(
                        rs.getLong("edge_id"),
                        rs.getLong("observation_id"),
                        rs.getString("allocation_role"),
                        rs.getInt("amount")));
            }
        }
        return list;
    }

    private String observationStatus(long observationId) throws SQLException {
        return observationStatus(conn, observationId);
    }

    private String observationStatus(Connection c, long observationId) throws SQLException {
        try (PreparedStatement pstmt = c.prepareStatement(
                "SELECT correlation_status FROM ig_observations WHERE id = ?")) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "observation " + observationId + " should exist");
                return rs.getString(1);
            }
        }
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

    private int totalAllocatedForObservation(long observationId) throws SQLException {
        return totalAllocatedForObservation(conn, observationId);
    }

    private int totalAllocatedForObservation(Connection c, long observationId) throws SQLException {
        try (PreparedStatement pstmt = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM ig_edge_allocations WHERE observation_id = ?")) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
