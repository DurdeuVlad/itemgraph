package com.itemgraph.correlation;

import com.itemgraph.config.ItemGraphConfig;
import com.itemgraph.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 7 correlation: reconstructs item type, metadata, and quantity flow across the
 * ephemeral GROUND node using stack-aware quantity conservation.
 *
 * <h2>Why only ground bridging</h2>
 *
 * <p>After the Phase 4 item-flow topology and the Phase 5 container direction fix,
 * three of the four core transitions are already a single {@code ig_observations} row:
 * a container withdrawal is {@code CONTAINER -> PLAYER}, a container deposit is
 * {@code PLAYER -> CONTAINER}, and a drop is {@code PLAYER -> GROUND}. Those are directly
 * observed facts. Re-deriving them as "inferred edges" would blur the evidence/inference
 * boundary this project is built to preserve.
 *
 * <p>Only the ground transition genuinely needs inference. GROUND is an ephemeral node:
 * a drop names the player who put an item there, a pickup names the player who took one
 * away, and nothing in GriefLogger states they are the same item — the {@code ItemEntity}
 * UUID is not logged (Phase 0 recon). Connecting them is a claim about two separate
 * observations, so it is an inference, needs a deterministic confidence, and needs a
 * complete, explainable narrative.
 *
 * <h2>Phase 7 Quantity Conservation & Allocation Ledger</h2>
 *
 * <p>Phase 5 enforced strict 1-to-1 quantity equality (drop amount == pickup amount).
 * Phase 7 replaces this with a deterministic capacity ledger ({@code ig_edge_allocations}):
 * <ul>
 *   <li><b>Conservation invariant:</b> for every source observation {@code D},
 *       {@code sum(source allocations) <= D.amount}. For every destination observation {@code P},
 *       {@code sum(destination allocations) <= P.amount}. Quantity is never manufactured.</li>
 *   <li><b>Stack splitting:</b> a drop of 64 iron can be split across multiple pickups
 *       (e.g. 20 to Bob, 44 to Chris) with each transfer recorded as an inferred edge
 *       backed by the corresponding quantity allocations.</li>
 *   <li><b>Stack merging:</b> multiple drops (e.g. 20 from Alice, 30 from Bob) can merge into
 *       a single pickup (50 to Chris), with destination capacity respected.</li>
 *   <li><b>Partial & unresolved transfers:</b> unallocated residual quantity remains eligible
 *       for future matching until the correlation window closes, after which it is marked
 *       {@code CLOSED_UNRESOLVED}. No phantom edges are ever invented.</li>
 * </ul>
 *
 * <h2>Confidence & Ambiguity</h2>
 *
 * <p>Deterministic and fully reproducible from the stored observations:
 * <pre>
 *   confidence = BASE
 *              x proximity(dt)
 *              x ambiguity(competing pickups for this drop)
 *              x ambiguity(competing drops for that pickup)
 * </pre>
 * Competing candidates on either side reduce confidence rather than being discarded silently.
 *
 * <h2>Threading</h2>
 *
 * <p>Runs on the ItemGraph ingestion worker thread after each ingestion cycle.
 * GriefLogger's database is treated as strictly read-only and is never written to.
 */
public class CorrelationEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationEngine.class);

    /** Actions that put an item on the ground (player -> GROUND). */
    private static final Set<String> DROP_ACTIONS = Set.of("DROP_ITEM", "THROW_ITEM", "SHOOT_ITEM", "DEATH_DROP");

    /** The action that takes an item off the ground (GROUND -> player). */
    private static final String PICKUP_ACTION = "PICKUP_ITEM";

    private static final String GROUND_NODE_TYPE = "GROUND";

    /** Ceiling confidence for a perfect, unopposed ground bridge. */
    public static final double BASE_CONFIDENCE = 0.95;

    /** Share of confidence that elapsed-time distance can erode across the full window. */
    public static final double PROXIMITY_WEIGHT = 0.25;

    /** Maximum share of the 1/n ambiguity penalty that temporal separation can recover. */
    public static final double SEPARATION_CAP = 0.9;

    /** Hard bound on work per pass, so a backlog is drained over several passes. */
    public static final int MAX_OBSERVATIONS_PER_PASS = 500;

    private final DatabaseManager dbManager;
    private final long windowSeconds;

    private volatile CorrelationResult lastResult = null;

    public CorrelationEngine() {
        this(DatabaseManager.getInstance());
    }

    public CorrelationEngine(DatabaseManager dbManager) {
        this(dbManager, resolveWindowSecondsFromConfig());
    }

    public CorrelationEngine(DatabaseManager dbManager, long windowSeconds) {
        this.dbManager = dbManager;
        this.windowSeconds = windowSeconds;
    }

    private static long resolveWindowSecondsFromConfig() {
        try {
            if (ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS != null) {
                return ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS.get();
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not read ground bridge window from config, using default {}s",
                    ItemGraphConfig.DEFAULT_GROUND_BRIDGE_MAX_SECONDS);
        }
        return ItemGraphConfig.DEFAULT_GROUND_BRIDGE_MAX_SECONDS;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public CorrelationResult getLastResult() {
        return lastResult;
    }

    private long windowMs() {
        return windowSeconds * 1000L;
    }

    /**
     * Runs one bounded correlation pass over the not-yet-evaluated GROUND observations.
     * Safe to call repeatedly; already-evaluated observations are never revisited.
     */
    public CorrelationResult runCorrelation() {
        long startTime = System.currentTimeMillis();

        if (!dbManager.isInitialized()) {
            String error = "ItemGraph database is not initialized";
            lastResult = new CorrelationResult(false, 0, 0, 0, 0, error);
            return lastResult;
        }

        try {
            Connection conn = dbManager.getConnection();
            synchronized (conn) {
                lastResult = runCorrelationLocked(conn, startTime);
                return lastResult;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String msg = "Correlation pass failed: " + e.getMessage();
            LOGGER.error("Error during ItemGraph correlation pass", e);
            lastResult = new CorrelationResult(false, 0, 0, 0, duration, msg);
            return lastResult;
        }
    }

    private CorrelationResult runCorrelationLocked(Connection conn, long startTime) {
        int finalised = 0;
        int edges = 0;
        int deferred = 0;

        try {
            new ObservationEquivalenceService().reconcile(conn, startTime);
            List<PendingObservation> pending = loadPendingGroundObservations(conn);

            for (PendingObservation obs : pending) {
                if (PICKUP_ACTION.equals(obs.actionType())) {
                    // Pickups are evidence for the drop-side search. Stamping records that
                    // correlation has evaluated the pickup. Candidate lookup evaluates residual
                    // capacity against ig_edge_allocations, so a drop evaluated later can still
                    // cite an already-stamped pickup if it has residual capacity.
                    int pickupAllocated = getDestinationAllocated(conn, obs.id());
                    String status = pickupAllocated >= obs.amount()
                            ? "FULLY_ALLOCATED"
                            : (pickupAllocated > 0 ? "PARTIALLY_ALLOCATED" : "PENDING");
                    if (startTime - obs.timestampMs() > windowMs() && pickupAllocated < obs.amount()) {
                        status = "CLOSED_UNRESOLVED";
                    }
                    markObservationStatus(conn, obs.id(), status, startTime);
                    finalised++;
                    continue;
                }

                if (!DROP_ACTIONS.contains(obs.actionType()) || obs.targetNodeId() == null) {
                    // Non-drop ground observation makes no bridging claim.
                    markObservationStatus(conn, obs.id(), "CLOSED_UNRESOLVED", startTime);
                    finalised++;
                    continue;
                }

                int dropAllocated = getSourceAllocated(conn, obs.id());
                if (dropAllocated > obs.amount()) {
                    throw new IllegalStateException("Data integrity violation: observation " + obs.id()
                            + " has allocated " + dropAllocated + " which exceeds original amount " + obs.amount());
                }

                int dropRemaining = obs.amount() - dropAllocated;
                if (dropRemaining <= 0) {
                    markObservationStatus(conn, obs.id(), "FULLY_ALLOCATED", startTime);
                    finalised++;
                    continue;
                }

                boolean createdEdgeForDrop = false;
                while (dropRemaining > 0) {
                    Bridge bridge = findNextBridge(conn, obs, dropRemaining);
                    if (bridge == null) {
                        break;
                    }

                    persistBridge(conn, obs, bridge, startTime);
                    edges++;
                    createdEdgeForDrop = true;
                    dropRemaining -= bridge.allocatedAmount();
                }

                if (dropRemaining == 0) {
                    // Fully allocated
                    markObservationStatus(conn, obs.id(), "FULLY_ALLOCATED", startTime);
                    finalised++;
                } else if (startTime - obs.timestampMs() > windowMs()) {
                    // Correlation window closed with unallocated residual quantity
                    markObservationStatus(conn, obs.id(), "CLOSED_UNRESOLVED", startTime);
                    finalised++;
                } else {
                    // Window still open: unallocated remainder is eligible for future pickups
                    if (createdEdgeForDrop || dropAllocated > 0) {
                        updateObservationStatusOnly(conn, obs.id(), "PARTIALLY_ALLOCATED");
                    }
                    deferred++;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            CorrelationResult result = new CorrelationResult(true, finalised, edges, deferred, duration, null);
            if (edges > 0 || finalised > 0) {
                LOGGER.info("ItemGraph correlation pass completed in {}ms: {} observations evaluated, {} ground bridges inferred, {} deferred.",
                        duration, finalised, edges, deferred);
            }
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String msg = "Correlation pass failed: " + e.getMessage();
            LOGGER.error("Error during ItemGraph correlation pass", e);
            return new CorrelationResult(false, finalised, edges, deferred, duration, msg);
        }
    }

    // ---------------------------------------------------------------------
    // Candidate search and scoring
    // ---------------------------------------------------------------------

    /** A ground-touching observation awaiting correlation. */
    private record PendingObservation(
            long id,
            long timestampMs,
            long nodeId,
            Long targetNodeId,
            long fingerprintId,
            int amount,
            String actionType,
            String itemEntityUuid
    ) {}

    /** A candidate observation with original and allocated quantity accounting. */
    public record Candidate(long id, long timestampMs, Long playerNodeId, int originalAmount, int allocatedAmount, String itemEntityUuid) {
        public Candidate(long id, long timestampMs, Long playerNodeId, int originalAmount, int allocatedAmount) {
            this(id, timestampMs, playerNodeId, originalAmount, allocatedAmount, null);
        }

        public int remainingCapacity() {
            return originalAmount - allocatedAmount;
        }
    }

    /** An accepted drop -> pickup bridge with full residual and ambiguity breakdown. */
    private record Bridge(
            Candidate pickup,
            int allocatedAmount,
            int dropResidualBefore,
            int dropResidualAfter,
            int pickupResidualBefore,
            int pickupResidualAfter,
            double confidence,
            double proximityFactor,
            double pickupAmbiguityFactor,
            double dropAmbiguityFactor,
            int pickupCandidates,
            int dropCandidates,
            long forwardGapMs,
            long reverseGapMs,
            boolean entityUuidMatch
    ) {}

    private List<PendingObservation> loadPendingGroundObservations(Connection conn) throws SQLException {
        String sql = """
            SELECT o.id, o.timestamp_ms, o.node_id, o.target_node_id, o.fingerprint_id,
                   o.amount, o.action_type, o.item_entity_uuid
            FROM ig_observations o
            JOIN ig_nodes origin ON origin.id = o.node_id
            LEFT JOIN ig_nodes dest ON dest.id = o.target_node_id
            WHERE o.correlated_at IS NULL
              AND (origin.node_type = ? OR dest.node_type = ?)
              AND EXISTS (SELECT 1 FROM ig_observation_match_checks c WHERE c.observation_id = o.id)
            ORDER BY o.timestamp_ms ASC, o.id ASC
            LIMIT ?
        """;

        List<PendingObservation> pending = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, GROUND_NODE_TYPE);
            pstmt.setString(2, GROUND_NODE_TYPE);
            pstmt.setInt(3, MAX_OBSERVATIONS_PER_PASS);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    long rawTarget = rs.getLong("target_node_id");
                    Long targetNodeId = rs.wasNull() ? null : rawTarget;
                    pending.add(new PendingObservation(
                            rs.getLong("id"),
                            rs.getLong("timestamp_ms"),
                            rs.getLong("node_id"),
                            targetNodeId,
                            rs.getLong("fingerprint_id"),
                            rs.getInt("amount"),
                            rs.getString("action_type"),
                            rs.getString("item_entity_uuid")
                    ));
                }
            }
        }
        return pending;
    }

    /**
     * Finds the next best admissible pickup bridge for the remaining quantity of {@code drop}.
     * Returns null if no eligible candidate exists.
     */
    private Bridge findNextBridge(Connection conn, PendingObservation drop, int dropRemaining) throws SQLException {
        List<Candidate> pickups = findCandidatePickups(conn, drop);
        if (pickups.isEmpty()) {
            return null;
        }

        Candidate chosen = pickups.get(0);
        if (chosen.playerNodeId() == null) {
            return null;
        }

        int pickupRemaining = chosen.remainingCapacity();
        if (pickupRemaining <= 0) {
            return null;
        }

        int allocAmount = Math.min(dropRemaining, pickupRemaining);
        long dt = chosen.timestampMs() - drop.timestampMs();

        long forwardGapMs = pickups.size() > 1
                ? pickups.get(1).timestampMs() - chosen.timestampMs()
                : 0L;

        List<Candidate> competingDrops = findCompetingDrops(conn, drop, chosen);
        long reverseGapMs = 0L;
        if (competingDrops.size() > 1) {
            long nearest = Long.MAX_VALUE;
            for (Candidate other : competingDrops) {
                if (other.id() == drop.id()) {
                    continue;
                }
                nearest = Math.min(nearest, Math.abs(other.timestampMs() - drop.timestampMs()));
            }
            reverseGapMs = nearest;
        }

        boolean entityUuidMatch = drop.itemEntityUuid() != null
                && chosen.itemEntityUuid() != null
                && drop.itemEntityUuid().equals(chosen.itemEntityUuid());

        double proximity = proximityFactor(dt);
        double pickupAmbiguity = ambiguityFactor(pickups.size(), forwardGapMs);
        double dropAmbiguity = ambiguityFactor(competingDrops.size(), reverseGapMs);

        double confidence;
        if (entityUuidMatch) {
            // Authoritative ItemEntity UUID match guarantees exact ground continuity
            confidence = 0.9990;
            com.itemgraph.tracker.ItemEntityTracker.getInstance().recordContinuityMatch();
        } else {
            confidence = round4(BASE_CONFIDENCE * proximity * pickupAmbiguity * dropAmbiguity);
        }

        int dropResidualBefore = dropRemaining;
        int dropResidualAfter = dropRemaining - allocAmount;
        int pickupResidualBefore = pickupRemaining;
        int pickupResidualAfter = pickupRemaining - allocAmount;

        return new Bridge(
                chosen, allocAmount,
                dropResidualBefore, dropResidualAfter,
                pickupResidualBefore, pickupResidualAfter,
                confidence, proximity, pickupAmbiguity, dropAmbiguity,
                pickups.size(), competingDrops.size(), forwardGapMs, reverseGapMs,
                entityUuidMatch
        );
    }

    private List<Candidate> findCandidatePickups(Connection conn, PendingObservation drop) throws SQLException {
        String sql = """
            SELECT o.id, o.timestamp_ms, o.target_node_id, o.amount, o.item_entity_uuid,
                   COALESCE(alloc.allocated, 0) AS allocated_amount
            FROM ig_observations o
            LEFT JOIN (
                SELECT a.observation_id, SUM(a.amount) AS allocated
                FROM ig_edge_allocations a
                JOIN ig_inferred_edges e ON e.id = a.edge_id
                WHERE a.allocation_role = 'DESTINATION' AND e.edge_state = 'ACTIVE'
                GROUP BY a.observation_id
            ) alloc ON alloc.observation_id = o.id
            WHERE o.action_type = ?
              AND o.node_id = ?
              AND o.fingerprint_id = ?
              AND o.timestamp_ms > ?
              AND o.timestamp_ms <= ?
              AND o.target_node_id IS NOT NULL
              AND o.correlation_status NOT IN ('CORROBORATING', 'SOURCE_AMBIGUOUS')
              AND EXISTS (SELECT 1 FROM ig_observation_match_checks c WHERE c.observation_id = o.id)
              AND (o.amount - COALESCE(alloc.allocated, 0)) > 0
            ORDER BY (CASE WHEN ? IS NOT NULL AND o.item_entity_uuid = ? THEN 0 ELSE 1 END) ASC,
                     o.timestamp_ms ASC, o.id ASC
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, PICKUP_ACTION);
            pstmt.setLong(2, drop.targetNodeId());
            pstmt.setLong(3, drop.fingerprintId());
            pstmt.setLong(4, drop.timestampMs());
            pstmt.setLong(5, drop.timestampMs() + windowMs());
            if (drop.itemEntityUuid() != null) {
                pstmt.setString(6, drop.itemEntityUuid());
                pstmt.setString(7, drop.itemEntityUuid());
            } else {
                pstmt.setNull(6, Types.VARCHAR);
                pstmt.setNull(7, Types.VARCHAR);
            }
            return readCandidates(pstmt, "target_node_id");
        }
    }

    private List<Candidate> findCompetingDrops(Connection conn, PendingObservation drop, Candidate pickup) throws SQLException {
        String sql = """
            SELECT o.id, o.timestamp_ms, o.node_id, o.amount, o.item_entity_uuid,
                   COALESCE(alloc.allocated, 0) AS allocated_amount
            FROM ig_observations o
            LEFT JOIN (
                SELECT a.observation_id, SUM(a.amount) AS allocated
                FROM ig_edge_allocations a
                JOIN ig_inferred_edges e ON e.id = a.edge_id
                WHERE a.allocation_role = 'SOURCE' AND e.edge_state = 'ACTIVE'
                GROUP BY a.observation_id
            ) alloc ON alloc.observation_id = o.id
            WHERE o.action_type IN ('DROP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM', 'DEATH_DROP')
              AND o.target_node_id = ?
              AND o.fingerprint_id = ?
              AND o.timestamp_ms < ?
              AND o.timestamp_ms >= ?
              AND o.correlation_status NOT IN ('CORROBORATING', 'SOURCE_AMBIGUOUS')
              AND EXISTS (SELECT 1 FROM ig_observation_match_checks c WHERE c.observation_id = o.id)
              AND (o.amount - COALESCE(alloc.allocated, 0)) > 0
            ORDER BY o.timestamp_ms ASC, o.id ASC
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, drop.targetNodeId());
            pstmt.setLong(2, drop.fingerprintId());
            pstmt.setLong(3, pickup.timestampMs());
            pstmt.setLong(4, pickup.timestampMs() - windowMs());
            return readCandidates(pstmt, "node_id");
        }
    }

    private List<Candidate> readCandidates(PreparedStatement pstmt, String playerColumn) throws SQLException {
        List<Candidate> candidates = new ArrayList<>();
        try (ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                long rawPlayer = rs.getLong(playerColumn);
                Long playerNodeId = rs.wasNull() ? null : rawPlayer;
                int originalAmount = rs.getInt("amount");
                int allocatedAmount = rs.getInt("allocated_amount");
                String itemEntityUuid = rs.getString("item_entity_uuid");
                candidates.add(new Candidate(
                        rs.getLong("id"),
                        rs.getLong("timestamp_ms"),
                        playerNodeId,
                        originalAmount,
                        allocatedAmount,
                        itemEntityUuid
                ));
            }
        }
        return candidates;
    }

    /**
     * Timestamp proximity: 1.0 for an instant pickup, falling linearly to
     * {@code 1 - PROXIMITY_WEIGHT} at the far edge of the window.
     */
    double proximityFactor(long dtMs) {
        double fraction = Math.min(1.0, Math.max(0.0, dtMs / (double) windowMs()));
        return 1.0 - PROXIMITY_WEIGHT * fraction;
    }

    /**
     * Competing-candidate penalty with temporal separation discriminator.
     */
    double ambiguityFactor(int candidateCount, long gapMs) {
        if (candidateCount <= 1) {
            return 1.0;
        }
        double prior = 1.0 / candidateCount;
        double separation = Math.min(SEPARATION_CAP, Math.max(0.0, gapMs / (double) windowMs()));
        return prior + (1.0 - prior) * separation;
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    // ---------------------------------------------------------------------
    // Capacity Ledger Helpers
    // ---------------------------------------------------------------------

    public int getSourceAllocated(Connection conn, long observationId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(a.amount), 0)
                FROM ig_edge_allocations a
                JOIN ig_inferred_edges e ON e.id = a.edge_id
                WHERE a.observation_id = ? AND a.allocation_role = 'SOURCE' AND e.edge_state = 'ACTIVE'
                """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int getDestinationAllocated(Connection conn, long observationId) throws SQLException {
        String sql = """
                SELECT COALESCE(SUM(a.amount), 0)
                FROM ig_edge_allocations a
                JOIN ig_inferred_edges e ON e.id = a.edge_id
                WHERE a.observation_id = ? AND a.allocation_role = 'DESTINATION' AND e.edge_state = 'ACTIVE'
                """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    /**
     * Writes the inferred edge, its evidence rows, the source and destination quantity
     * allocations, and updates observation lifecycle state atomically in a single transaction.
     */
    private void persistBridge(Connection conn, PendingObservation drop, Bridge bridge, long nowMs) throws SQLException {
        boolean originalAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            String explanation = buildExplanation(conn, drop, bridge);

            long edgeId;
            String insertEdgeSql = """
                INSERT INTO ig_inferred_edges (
                    from_node_id, to_node_id, fingerprint_id, amount,
                    time_start, time_end, confidence, explanation, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement pstmt = conn.prepareStatement(insertEdgeSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setLong(1, drop.nodeId());
                pstmt.setLong(2, bridge.pickup().playerNodeId());
                pstmt.setLong(3, drop.fingerprintId());
                pstmt.setInt(4, bridge.allocatedAmount());
                pstmt.setLong(5, drop.timestampMs());
                pstmt.setLong(6, bridge.pickup().timestampMs());
                pstmt.setDouble(7, bridge.confidence());
                pstmt.setString(8, explanation);
                pstmt.setLong(9, nowMs);
                pstmt.executeUpdate();
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Failed to obtain generated id for inferred edge");
                    }
                    edgeId = keys.getLong(1);
                }
            }

            // Link evidence
            String insertEvidenceSql = "INSERT OR IGNORE INTO ig_edge_evidence (edge_id, observation_id) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertEvidenceSql)) {
                pstmt.setLong(1, edgeId);
                pstmt.setLong(2, drop.id());
                pstmt.addBatch();
                pstmt.setLong(1, edgeId);
                pstmt.setLong(2, bridge.pickup().id());
                pstmt.addBatch();
                pstmt.executeBatch();
            }
            addGroupEvidence(conn, edgeId, drop.id());
            addGroupEvidence(conn, edgeId, bridge.pickup().id());

            // Record exact quantity allocations in the ledger
            String insertAllocSql = "INSERT INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount) VALUES (?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertAllocSql)) {
                pstmt.setLong(1, edgeId);
                pstmt.setLong(2, drop.id());
                pstmt.setString(3, "SOURCE");
                pstmt.setInt(4, bridge.allocatedAmount());
                pstmt.addBatch();

                pstmt.setLong(1, edgeId);
                pstmt.setLong(2, bridge.pickup().id());
                pstmt.setString(3, "DESTINATION");
                pstmt.setInt(4, bridge.allocatedAmount());
                pstmt.addBatch();

                pstmt.executeBatch();
            }

            // Update destination observation lifecycle status
            String pickupStatus = bridge.pickupResidualAfter() == 0 ? "FULLY_ALLOCATED" : "PARTIALLY_ALLOCATED";
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE ig_observations SET correlation_status = ?, correlated_at = ? WHERE id = ?")) {
                pstmt.setString(1, pickupStatus);
                pstmt.setLong(2, nowMs);
                pstmt.setLong(3, bridge.pickup().id());
                pstmt.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            LOGGER.error("Failed to persist ground bridge for observation {}. Rolled back.", drop.id(), e);
            throw e;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    private void addGroupEvidence(Connection conn, long edgeId, long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("""
                INSERT OR IGNORE INTO ig_edge_evidence (edge_id, observation_id)
                SELECT ?, gm.observation_id
                FROM ig_observation_group_members gm
                WHERE gm.group_id = (
                    SELECT group_id FROM ig_observation_group_members WHERE observation_id = ?
                )
                """)) {
            pstmt.setLong(1, edgeId);
            pstmt.setLong(2, observationId);
            pstmt.executeUpdate();
        }
    }

    private void markObservationStatus(Connection conn, long observationId, String status, long nowMs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlation_status = ?, correlated_at = ? WHERE id = ?")) {
            pstmt.setString(1, status);
            pstmt.setLong(2, nowMs);
            pstmt.setLong(3, observationId);
            pstmt.executeUpdate();
        }
    }

    private void updateObservationStatusOnly(Connection conn, long observationId, String status) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlation_status = ? WHERE id = ?")) {
            pstmt.setString(1, status);
            pstmt.setLong(2, observationId);
            pstmt.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------
    // Explanation
    // ---------------------------------------------------------------------

    private String buildExplanation(Connection conn, PendingObservation drop, Bridge bridge) throws SQLException {
        String dropper = describeNode(conn, drop.nodeId());
        String picker = describeNode(conn, bridge.pickup().playerNodeId());
        String place = describeNode(conn, drop.targetNodeId());
        String item = describeFingerprint(conn, drop.fingerprintId());
        long dtMs = bridge.pickup().timestampMs() - drop.timestampMs();

        boolean isSplit = bridge.dropResidualBefore() > bridge.allocatedAmount()
                || bridge.dropResidualAfter() > 0
                || drop.amount() > bridge.allocatedAmount();
        boolean isMerge = bridge.pickupResidualBefore() > bridge.allocatedAmount()
                || bridge.pickup().originalAmount() > bridge.allocatedAmount();

        String flowType;
        if (isSplit && isMerge) {
            flowType = "many-to-many flow";
        } else if (isSplit) {
            flowType = "stack split";
        } else if (isMerge) {
            flowType = "stack merge";
        } else {
            flowType = "exact transfer";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Ground bridge (%s): %s dropped %dx %s at %s on %s (observation %d); %s picked up %dx there %s later, on %s (observation %d). ",
                flowType,
                dropper, drop.amount(), item, place, formatTime(drop.timestampMs()), drop.id(),
                picker, bridge.allocatedAmount(), formatDuration(dtMs), formatTime(bridge.pickup().timestampMs()), bridge.pickup().id()));

        sb.append(String.format(Locale.ROOT,
                "Allocated %d unit%s (drop: %d/%d allocated, residual %d; pickup: %d/%d allocated, residual %d). ",
                bridge.allocatedAmount(), bridge.allocatedAmount() == 1 ? "" : "s",
                (drop.amount() - bridge.dropResidualAfter()), drop.amount(), bridge.dropResidualAfter(),
                (bridge.pickup().originalAmount() - bridge.pickupResidualAfter()), bridge.pickup().originalAmount(), bridge.pickupResidualAfter()));

        sb.append("Matched on exact item fingerprint and available quantity capacity, same ground block, pickup strictly after the drop, within the ")
                .append(windowSeconds).append("s correlation window. ");

        sb.append(String.format(Locale.ROOT,
                "Candidates: %d admissible pickup%s for this drop%s; %d admissible drop%s for that pickup%s. ",
                bridge.pickupCandidates(), bridge.pickupCandidates() == 1 ? "" : "s",
                bridge.pickupCandidates() > 1
                        ? " (chose the temporally closest; next-best was " + formatDuration(bridge.forwardGapMs()) + " further away)"
                        : "",
                bridge.dropCandidates(), bridge.dropCandidates() == 1 ? "" : "s",
                bridge.dropCandidates() > 1
                        ? " (nearest competing drop was " + formatDuration(bridge.reverseGapMs()) + " away)"
                        : ""));

        if (bridge.entityUuidMatch()) {
            sb.append(String.format(Locale.ROOT,
                    "Authoritative Minecraft ItemEntity UUID match (%s) establishes direct entity continuity on the ground. Confidence %.4f.",
                    drop.itemEntityUuid(), bridge.confidence()));
        } else {
            sb.append(String.format(Locale.ROOT,
                    "Confidence %.4f = base %.2f x proximity %.4f x pickup-ambiguity %.4f x drop-ambiguity %.4f.",
                    bridge.confidence(), BASE_CONFIDENCE, bridge.proximityFactor(),
                    bridge.pickupAmbiguityFactor(), bridge.dropAmbiguityFactor()));

            if (bridge.pickupCandidates() > 1 || bridge.dropCandidates() > 1) {
                sb.append(" Confidence is reduced below the unambiguous ceiling because competing candidates exist:")
                        .append(" this is one plausible reconstruction, not the only one.");
            }
        }

        return sb.toString();
    }

    private String describeNode(Connection conn, long nodeId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT node_type, custom_label, level_id, x, y, z FROM ig_nodes WHERE id = ?")) {
            pstmt.setLong(1, nodeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return "node " + nodeId;
                }
                String type = rs.getString("node_type");
                String label = rs.getString("custom_label");
                if ("PLAYER".equals(type)) {
                    return (label != null && !label.isBlank()) ? label : ("player node " + nodeId);
                }
                double x = rs.getDouble("x");
                if (rs.wasNull()) {
                    return type + " (" + rs.getString("level_id") + ")";
                }
                return String.format(Locale.ROOT, "%s %s %d,%d,%d",
                        type, rs.getString("level_id"),
                        (long) x, (long) rs.getDouble("y"), (long) rs.getDouble("z"));
            }
        }
    }

    private String describeFingerprint(Connection conn, long fingerprintId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT item_id, custom_name FROM ig_item_fingerprints WHERE id = ?")) {
            pstmt.setLong(1, fingerprintId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return "fingerprint " + fingerprintId;
                }
                String itemId = rs.getString("item_id");
                String customName = rs.getString("custom_name");
                return (customName != null && !customName.isBlank())
                        ? ("'" + customName + "' (" + itemId + ")")
                        : itemId;
            }
        }
    }

    private static String formatTime(long epochMs) {
        return Instant.ofEpochMilli(epochMs).toString();
    }

    private static String formatDuration(long ms) {
        if (ms < 1000L) {
            return ms + "ms";
        }
        long totalSeconds = ms / 1000L;
        if (totalSeconds < 60L) {
            return totalSeconds + "s";
        }
        return (totalSeconds / 60L) + "m" + (totalSeconds % 60L) + "s";
    }
}
