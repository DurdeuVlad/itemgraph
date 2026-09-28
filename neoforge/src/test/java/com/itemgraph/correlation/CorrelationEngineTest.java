package com.itemgraph.correlation;

import com.itemgraph.audit.AuditService;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.GriefLoggerAdapter;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.query.EdgeExplanation;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.ObservationDetail;
import com.itemgraph.query.QueryFormatter;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void initMinecraftRegistries() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

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

    @Test
    void crossSourceCopiesDoNotProvideIndependentGroundCapacity() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long dropTime = now - CLOSED;
        String itemEntityUuid = "entity-cross-source-1";
        long internalDropId;
        long griefLoggerDropId;
        long internalPickupId;
        long griefLoggerPickupId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + dropTime + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 101, "
                    + (dropTime + 1) + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + (dropTime + 60_000) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalPickupId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 102, "
                    + (dropTime + 60_001) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerPickupId = generatedKey(stmt);
        }

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated(), "four source rows describe one physical five-item transfer");
        assertEquals(1, countEdges());
        assertEquals(5, loadEdges().get(0).amount(), "corroborating source rows must not double quantity");
        long edgeId = loadEdges().get(0).id();
        assertEquals(List.of(internalDropId, griefLoggerDropId, internalPickupId, griefLoggerPickupId),
                evidenceFor(edgeId), "the inferred edge must cite both sources for each physical event");

        EdgeExplanation explanation = new ExplainQueryService().findEdge(conn, edgeId).orElseThrow();
        assertEquals(4, explanation.evidence().size());
        ObservationDetail corroboratingPickup = explanation.evidence().stream()
                .filter(observation -> observation.id() == griefLoggerPickupId)
                .findFirst().orElseThrow();
        assertEquals("CONFIRMED", corroboratingPickup.sourceGroup().state());
        assertEquals("CORROBORATING", corroboratingPickup.sourceGroup().memberRole());
        assertTrue(String.join("\n", QueryFormatter.formatExplain(explanation))
                .contains("source group: confirmed"));
    }

    @Test
    void lateGriefLoggerCopiesAttachToExistingEdgeWithoutAddingCapacity() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long dropTime = now - 120_000;
        long pickupTime = dropTime + 60_000;
        String itemEntityUuid = "entity-late-source-copy";
        long internalDropId;
        long internalPickupId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + dropTime + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + pickupTime + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalPickupId = generatedKey(stmt);
        }

        assertEquals(1, engine.runCorrelation().edgesCreated());
        long edgeId = loadEdges().get(0).id();
        long griefLoggerDropId;
        long griefLoggerPickupId;
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 501, "
                    + (dropTime + 1) + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 502, "
                    + (pickupTime + 1) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerPickupId = generatedKey(stmt);
        }

        CorrelationResult secondPass = engine.runCorrelation();

        assertTrue(secondPass.success(), secondPass.errorMessage());
        assertEquals(0, secondPass.edgesCreated());
        assertEquals(1, countActiveEdges());
        assertEquals(List.of(internalDropId, internalPickupId, griefLoggerDropId, griefLoggerPickupId),
                evidenceFor(edgeId));
        assertEquals("CONFIRMED", matchCheckResult(internalDropId));
        assertEquals("CONFIRMED", matchCheckResult(griefLoggerDropId));
        assertEquals("CONFIRMED", matchCheckResult(internalPickupId));
        assertEquals("CONFIRMED", matchCheckResult(griefLoggerPickupId));
    }

    @Test
    void legacyInternalSourceTypeStillGroupsWithGriefLoggerCopies() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long dropTime = now - CLOSED;
        String itemEntityUuid = "entity-legacy-internal";
        long legacyDropId;
        long griefLoggerDropId;
        long legacyPickupId;
        long griefLoggerPickupId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('INTERNAL', "
                    + dropTime + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            legacyDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 151, "
                    + (dropTime + 1) + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('INTERNAL', "
                    + (dropTime + 60_000) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            legacyPickupId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 152, "
                    + (dropTime + 60_001) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerPickupId = generatedKey(stmt);
        }

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        assertEquals(List.of(legacyDropId, griefLoggerDropId, legacyPickupId, griefLoggerPickupId),
                evidenceFor(loadEdges().get(0).id()));
    }

    @Test
    void crossSourceRowsWithoutSharedEntityIdentityRemainAmbiguous() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long dropTime = now - CLOSED;
        long internalDropId;
        long griefLoggerDropId;
        long internalPickupId;
        long griefLoggerPickupId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount) VALUES ('ITEMGRAPH_INTERNAL', "
                    + dropTime + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5)", Statement.RETURN_GENERATED_KEYS);
            internalDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount) VALUES ('GRIEFLOGGER', 201, "
                    + (dropTime + 1) + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5)", Statement.RETURN_GENERATED_KEYS);
            griefLoggerDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount) VALUES ('ITEMGRAPH_INTERNAL', "
                    + (dropTime + 60_000) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5)", Statement.RETURN_GENERATED_KEYS);
            internalPickupId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount) VALUES ('GRIEFLOGGER', 202, "
                    + (dropTime + 60_001) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5)", Statement.RETURN_GENERATED_KEYS);
            griefLoggerPickupId = generatedKey(stmt);
        }

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated(), "a possible source duplicate without shared entity identity cannot support quantity allocation");
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(internalDropId));
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(griefLoggerDropId));
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(internalPickupId));
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(griefLoggerPickupId));
    }

    @Test
    void sharedEntityUuidWithConflictingPickupActorsIsAmbiguousNotCollapsed() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long pickupTime = now - CLOSED;
        String itemEntityUuid = "entity-conflicting-pickup-actor";
        long internalPickupId;
        long griefLoggerPickupId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + pickupTime + ", " + ground + ", " + playerA + ", " + fp + ", 'PICKUP_ITEM', 3, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalPickupId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 601, "
                    + (pickupTime + 1) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 3, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerPickupId = generatedKey(stmt);
        }

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(internalPickupId));
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(griefLoggerPickupId));
        assertEquals(2, countObservations(), "conflicting actor rows remain separately auditable");
    }

    @Test
    void canceledDropAttemptMakesConflictingGriefLoggerGroundRowAmbiguous() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long dropTime = now - CLOSED;
        long cancelledId;
        long griefLoggerDropId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, fingerprint_id, action_type, amount) VALUES ('ITEMGRAPH_INTERNAL', "
                    + dropTime + ", " + playerA + ", " + fp + ", 'DROP_CANCELLED', 5)", Statement.RETURN_GENERATED_KEYS);
            cancelledId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount) VALUES ('GRIEFLOGGER', 701, "
                    + (dropTime + 1) + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5)", Statement.RETURN_GENERATED_KEYS);
            griefLoggerDropId = generatedKey(stmt);
        }
        insertObservation(dropTime + 60_000, ground, playerB, fp, "PICKUP_ITEM", 5);

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(cancelledId));
        assertEquals("SOURCE_AMBIGUOUS", correlationStatus(griefLoggerDropId));
        assertEquals(0, countEdges());
    }

    @Test
    void legacyDuplicateEdgesAreSupersededAndQuantityIsRebuiltFromOneSourceGroup() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");
        long dropTime = now - CLOSED;
        long pickupTime = dropTime + 60_000;
        String itemEntityUuid = "entity-legacy-duplicate";
        long internalDropId;
        long griefLoggerDropId;
        long internalPickupId;
        long griefLoggerPickupId;

        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + dropTime + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 301, "
                    + (dropTime + 1) + ", " + playerA + ", " + ground + ", " + fp + ", 'DROP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerDropId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('ITEMGRAPH_INTERNAL', "
                    + pickupTime + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            internalPickupId = generatedKey(stmt);
            stmt.executeUpdate("INSERT INTO ig_observations (source_type, source_event_id, timestamp_ms, node_id, target_node_id, fingerprint_id, action_type, amount, item_entity_uuid) VALUES ('GRIEFLOGGER', 302, "
                    + (pickupTime + 1) + ", " + ground + ", " + playerB + ", " + fp + ", 'PICKUP_ITEM', 5, '" + itemEntityUuid + "')", Statement.RETURN_GENERATED_KEYS);
            griefLoggerPickupId = generatedKey(stmt);
        }

        long retainedEdge = insertLegacyEdge(playerA, playerB, fp, 5, dropTime, pickupTime,
                internalDropId, internalPickupId);
        long supersededEdge = insertLegacyEdge(playerA, playerB, fp, 5, dropTime + 1, pickupTime + 1,
                griefLoggerDropId, griefLoggerPickupId);
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlation_status = 'FULLY_ALLOCATED', correlated_at = ?")) {
            pstmt.setLong(1, now);
            pstmt.executeUpdate();
        }

        CorrelationResult result = engine.runCorrelation();

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, result.edgesCreated());
        assertEquals(2, countEdges(), "superseded evidence rows remain stored for auditability");
        assertEquals(1, countActiveEdges());
        assertEquals("SUPERSEDED_SOURCE_DUPLICATE", edgeState(supersededEdge));
        assertEquals(List.of(internalDropId, griefLoggerDropId, internalPickupId, griefLoggerPickupId),
                evidenceFor(retainedEdge));
        assertEquals("SUPERSEDED_SOURCE_DUPLICATE",
                new ExplainQueryService().findEdge(conn, supersededEdge).orElseThrow().edgeState());
        assertTrue(new AuditService().audit(conn).healthy());
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
     * Partial pickup. Phase 7 implements quantity-flow conservation, so when AlphaA drops 5
     * and BetaB picks up 3 at the same ground location, the engine allocates 3 units to an
     * inferred bridge edge A -> B and records the 2 unrecovered units as residual capacity.
     */
    @Test
    void testPartialQuantityAllocatesUpToPickupCapacityAndPreservesResidual() throws Exception {
        long playerA = insertPlayerNode("AlphaA");
        long playerB = insertPlayerNode("BetaB");
        long ground = insertGroundNode(20, 64, 20);
        long fp = insertFingerprint("minecraft:diamond", "hash-diamond");

        long dropTime = now - CLOSED;
        long dropObs = insertObservation(dropTime, playerA, ground, fp, "DROP_ITEM", 5);
        long pickupObs = insertObservation(dropTime + 30_000, ground, playerB, fp, "PICKUP_ITEM", 3);

        CorrelationResult result = engine.runCorrelation();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, result.edgesCreated());
        assertEquals(1, countEdges());

        List<Edge> edges = loadEdges();
        assertEquals(1, edges.size());
        Edge edge = edges.get(0);
        assertEquals(playerA, edge.fromNodeId());
        assertEquals(playerB, edge.toNodeId());
        assertEquals(3, edge.amount(), "edge amount must match the allocated quantity (3 of 5)");
        assertTrue(edge.explanation().contains("stack split"));
        assertTrue(edge.explanation().contains("Allocated 3 units (drop: 3/5 allocated, residual 2; pickup: 3/3 allocated, residual 0)"));

        assertNotNull(correlatedAt(dropObs));
        assertNotNull(correlatedAt(pickupObs));
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

    private static long generatedKey(Statement stmt) throws SQLException {
        try (ResultSet keys = stmt.getGeneratedKeys()) {
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

    private int countObservations() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countActiveEdges() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_inferred_edges WHERE edge_state = 'ACTIVE'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String edgeState(long edgeId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT edge_state FROM ig_inferred_edges WHERE id = ?")) {
            pstmt.setLong(1, edgeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private long insertLegacyEdge(long fromNode, long toNode, long fingerprintId, int amount,
                                  long timeStart, long timeEnd, long sourceObservation,
                                  long destinationObservation) throws SQLException {
        long edgeId;
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT INTO ig_inferred_edges (from_node_id, to_node_id, fingerprint_id, amount,
                    time_start, time_end, confidence, explanation, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 0.9, 'legacy duplicated source inference', ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setLong(1, fromNode);
            pstmt.setLong(2, toNode);
            pstmt.setLong(3, fingerprintId);
            pstmt.setInt(4, amount);
            pstmt.setLong(5, timeStart);
            pstmt.setLong(6, timeEnd);
            pstmt.setLong(7, now);
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                assertTrue(keys.next());
                edgeId = keys.getLong(1);
            }
        }
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_edge_evidence (edge_id, observation_id) VALUES (?, ?)")) {
            pstmt.setLong(1, edgeId);
            pstmt.setLong(2, sourceObservation);
            pstmt.addBatch();
            pstmt.setLong(1, edgeId);
            pstmt.setLong(2, destinationObservation);
            pstmt.addBatch();
            pstmt.executeBatch();
        }
        try (PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount) VALUES (?, ?, ?, ?)")) {
            pstmt.setLong(1, edgeId);
            pstmt.setLong(2, sourceObservation);
            pstmt.setString(3, "SOURCE");
            pstmt.setInt(4, amount);
            pstmt.addBatch();
            pstmt.setLong(1, edgeId);
            pstmt.setLong(2, destinationObservation);
            pstmt.setString(3, "DESTINATION");
            pstmt.setInt(4, amount);
            pstmt.addBatch();
            pstmt.executeBatch();
        }
        return edgeId;
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

    private String matchCheckResult(long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT result FROM ig_observation_match_checks WHERE observation_id = ?")) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "match check for observation " + observationId + " should exist");
                return rs.getString(1);
            }
        }
    }

    private String correlationStatus(long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT correlation_status FROM ig_observations WHERE id = ?")) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "observation " + observationId + " should exist");
                return rs.getString(1);
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
