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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 5 correlation: bridges a player's drop to a later player's pickup across the
 * ephemeral GROUND node.
 *
 * <h2>Why only ground bridging</h2>
 *
 * <p>The Phase 5 plan lists four patterns to support: container-remove -> player-gain,
 * player-drop -> item-entity, item-entity -> player-pickup, and player-remove ->
 * container-add. After the Phase 4 item-flow topology and the Phase 5 container
 * direction fix, <b>three of those four are already a single {@code ig_observations}
 * row</b>: a container withdrawal is one row reading container -> player, a container
 * deposit is one row reading player -> container, and a drop is one row reading
 * player -> ground. Those are directly observed facts, and re-deriving them as
 * "inferred edges" would blur the evidence/inference boundary this project is built
 * to preserve — {@code ig_observations} IS the observed evidence,
 * {@code ig_inferred_edges} is specifically for claims no single row evidences.
 *
 * <p>Only the fourth link genuinely needs inference. GROUND is an ephemeral node: a
 * drop names the player who put an item there, a pickup names the player who took one
 * away, and <b>nothing in GriefLogger states they are the same item</b> — the
 * {@code ItemEntity} UUID is not logged (Phase 0 recon). Connecting them is a claim
 * about two separate observations, so it is an inference, it needs a confidence, and
 * it needs an explanation. That is the whole job of this class.
 *
 * <h2>Matching rules</h2>
 *
 * <p>For a DROP-type observation (DROP_ITEM / THROW_ITEM / SHOOT_ITEM, player -> ground),
 * a candidate pickup must:
 * <ul>
 *   <li>be a PICKUP_ITEM observation whose origin is the <em>same</em> GROUND node,</li>
 *   <li>carry the <em>same</em> fingerprint id (exact canonical metadata equality),</li>
 *   <li>carry the <em>same</em> amount — Phase 5 has no split/merge model, so a partial
 *       pickup is deliberately not matched rather than guessed at. Known limitation,
 *       deferred to Phase 7 (quantity flow); matching partial quantities without a
 *       conservation model would manufacture quantity, which the charter forbids,</li>
 *   <li>be strictly later than the drop (a later observation may explain an earlier
 *       event, never the reverse), and within the configured window,</li>
 *   <li>not already be cited as evidence for another accepted bridge. One dropped stack
 *       can only be picked up once, so allowing a pickup to support two bridges would
 *       attribute the same items to two different flows.</li>
 * </ul>
 *
 * <h2>Confidence</h2>
 *
 * <p>Deterministic and fully reproducible from the stored observations — no model, no
 * tuning, no randomness. The same inputs always produce the same number:
 *
 * <pre>
 *   confidence = BASE
 *              x proximity(dt)
 *              x ambiguity(competing pickups for this drop)
 *              x ambiguity(competing drops for that pickup)
 *
 *   proximity(dt)         = 1 - PROXIMITY_WEIGHT * dt / window
 *   ambiguity(n, gap)     = 1                        if n == 1
 *                         = p + (1 - p) * separation if n &gt;  1
 *       where p          = 1 / n
 *             separation = min(SEPARATION_CAP, gap / window)
 * </pre>
 *
 * <p>Each factor is a documented evidentiary statement:
 * <ul>
 *   <li><b>BASE = {@value #BASE_CONFIDENCE}</b> — the ceiling for a perfect, unopposed
 *       match. Not 1.0, and never 1.0: without the ItemEntity UUID this is always
 *       circumstantial evidence (same place, same item, same amount, right order), so
 *       certainty is not available. The headroom is reserved for Phase 8, where a
 *       supplemental hook can log the entity UUID and support a genuinely stronger
 *       claim.</li>
 *   <li><b>proximity</b> — an instant pickup scores 1.0; a pickup at the very edge of
 *       the window scores {@code 1 - PROXIMITY_WEIGHT}. Linear in elapsed time, so the
 *       penalty scales with the window instead of being a magic constant. A long delay
 *       weakens but does not refute the link, hence a modest weight.</li>
 *   <li><b>ambiguity</b> — with {@code n} equally admissible candidates and no
 *       discriminator, the honest prior in favour of the chosen one is {@code 1/n}.
 *       Temporal proximity IS a discriminator, so the score recovers towards 1.0 in
 *       proportion to how far the runner-up is from the chosen candidate, measured as a
 *       fraction of the window. The recovery is capped at {@value #SEPARATION_CAP}: a
 *       demonstrated alternative explanation never fully disappears, so an ambiguous
 *       match can never score as high as an unambiguous one.</li>
 *   <li>The reverse direction uses the identical function: if several drops could also
 *       explain the chosen pickup, that ambiguity is just as real and reduces confidence
 *       just as much.</li>
 * </ul>
 *
 * <p>The exact factor values are written into the edge's {@code explanation}, so an
 * admin asking "why does ItemGraph think this transfer happened" gets the observations,
 * the candidate counts, and the arithmetic.
 *
 * <h2>Incrementality</h2>
 *
 * <p>Every pass is driven by {@code correlated_at IS NULL} and bounded by
 * {@link #MAX_OBSERVATIONS_PER_PASS}; the table is never fully scanned. A pickup is
 * stamped as soon as it is seen (it is matched from the drop side, not the pickup side).
 * A drop is stamped once it produces an edge, or once its window has closed with no
 * match. A drop whose window is still open is deliberately left pending, because the
 * pickup that explains it may simply not have been ingested yet — ingestion cycles run
 * every 60s while the window is minutes long. That deferral is what stops the engine
 * from permanently writing off drops purely for arriving near a cycle boundary.
 *
 * <h2>Threading</h2>
 *
 * <p>Runs on the existing ItemGraph ingestion worker thread, after an ingestion cycle
 * completes — never on the server thread, and never concurrently with ingestion (see
 * {@code IngestionService.runCorrelation}). GriefLogger's database is not touched here
 * at all: correlation reads and writes only ItemGraph's own tables.
 */
public class CorrelationEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationEngine.class);

    /** Actions that put an item on the ground (Phase 4 topology: player -> GROUND). */
    private static final Set<String> DROP_ACTIONS = Set.of("DROP_ITEM", "THROW_ITEM", "SHOOT_ITEM");

    /** The action that takes an item off the ground (Phase 4 topology: GROUND -> player). */
    private static final String PICKUP_ACTION = "PICKUP_ITEM";

    private static final String GROUND_NODE_TYPE = "GROUND";

    /** Ceiling confidence for a perfect, unopposed ground bridge. See class javadoc. */
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

    /**
     * @param windowSeconds maximum drop-to-pickup gap considered a possible bridge.
     */
    public CorrelationEngine(DatabaseManager dbManager, long windowSeconds) {
        this.dbManager = dbManager;
        this.windowSeconds = windowSeconds;
    }

    /**
     * Reads the configured window, falling back to the documented default when the
     * NeoForge config spec is not loaded (unit tests, or very early startup).
     */
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

        int finalised = 0;
        int edges = 0;
        int deferred = 0;

        try {
            Connection conn = dbManager.getConnection();
            List<PendingObservation> pending = loadPendingGroundObservations(conn);

            for (PendingObservation obs : pending) {
                if (PICKUP_ACTION.equals(obs.actionType())) {
                    // Pickups are evidence for the drop-side search, not a search of their
                    // own: stamping one here only records that correlation has seen it.
                    // Candidate lookup deliberately ignores correlated_at, so a drop
                    // ingested later can still cite an already-stamped pickup.
                    markCorrelated(conn, obs.id(), startTime);
                    finalised++;
                    continue;
                }

                if (!DROP_ACTIONS.contains(obs.actionType()) || obs.targetNodeId() == null) {
                    // A GROUND-touching observation that is neither a drop nor a pickup
                    // makes no bridging claim. Stamp it so it stops being rescanned.
                    markCorrelated(conn, obs.id(), startTime);
                    finalised++;
                    continue;
                }

                Bridge bridge = findBridge(conn, obs);
                if (bridge != null) {
                    persistBridge(conn, obs, bridge, startTime);
                    edges++;
                    finalised++;
                } else if (startTime - obs.timestampMs() > windowMs()) {
                    // Window closed with no candidate: the item was never recovered, or
                    // was recovered outside the window. Record the negative result.
                    markCorrelated(conn, obs.id(), startTime);
                    finalised++;
                } else {
                    // Window still open: the explaining pickup may not be ingested yet.
                    deferred++;
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            lastResult = new CorrelationResult(true, finalised, edges, deferred, duration, null);
            if (edges > 0 || finalised > 0) {
                LOGGER.info("ItemGraph correlation pass completed in {}ms: {} observations evaluated, {} ground bridges inferred, {} deferred.",
                        duration, finalised, edges, deferred);
            }
            return lastResult;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String msg = "Correlation pass failed: " + e.getMessage();
            LOGGER.error("Error during ItemGraph correlation pass", e);
            lastResult = new CorrelationResult(false, finalised, edges, deferred, duration, msg);
            return lastResult;
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
            String actionType
    ) {
    }

    /** A candidate observation on the other side of a ground node. */
    private record Candidate(long id, long timestampMs, Long playerNodeId) {
    }

    /** An accepted drop -> pickup bridge together with its scoring breakdown. */
    private record Bridge(
            Candidate pickup,
            double confidence,
            double proximityFactor,
            double pickupAmbiguityFactor,
            double dropAmbiguityFactor,
            int pickupCandidates,
            int dropCandidates,
            long forwardGapMs,
            long reverseGapMs
    ) {
    }

    private List<PendingObservation> loadPendingGroundObservations(Connection conn) throws SQLException {
        String sql = """
            SELECT o.id, o.timestamp_ms, o.node_id, o.target_node_id, o.fingerprint_id,
                   o.amount, o.action_type
            FROM ig_observations o
            JOIN ig_nodes origin ON origin.id = o.node_id
            LEFT JOIN ig_nodes dest ON dest.id = o.target_node_id
            WHERE o.correlated_at IS NULL
              AND (origin.node_type = ? OR dest.node_type = ?)
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
                    // wasNull() reflects the most recent read, so resolve the nullable
                    // column into a Long before reading anything else.
                    long rawTarget = rs.getLong("target_node_id");
                    Long targetNodeId = rs.wasNull() ? null : rawTarget;
                    pending.add(new PendingObservation(
                            rs.getLong("id"),
                            rs.getLong("timestamp_ms"),
                            rs.getLong("node_id"),
                            targetNodeId,
                            rs.getLong("fingerprint_id"),
                            rs.getInt("amount"),
                            rs.getString("action_type")
                    ));
                }
            }
        }
        return pending;
    }

    /**
     * Finds the best pickup that can explain {@code drop}, or null if none is admissible.
     * The temporally closest candidate wins; competing candidates on either side reduce
     * the resulting confidence rather than being silently discarded.
     */
    private Bridge findBridge(Connection conn, PendingObservation drop) throws SQLException {
        List<Candidate> pickups = findCandidatePickups(conn, drop);
        if (pickups.isEmpty()) {
            return null;
        }

        // Ordered by timestamp ascending, so the first candidate is the temporally closest.
        Candidate chosen = pickups.get(0);
        if (chosen.playerNodeId() == null) {
            // A pickup with no destination node names nobody to attribute the item to.
            return null;
        }

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

        double proximity = proximityFactor(dt);
        double pickupAmbiguity = ambiguityFactor(pickups.size(), forwardGapMs);
        double dropAmbiguity = ambiguityFactor(competingDrops.size(), reverseGapMs);

        // Rounded so the stored value is exactly reproducible from the printed factors.
        double confidence = round4(BASE_CONFIDENCE * proximity * pickupAmbiguity * dropAmbiguity);

        return new Bridge(chosen, confidence, proximity, pickupAmbiguity, dropAmbiguity,
                pickups.size(), competingDrops.size(), forwardGapMs, reverseGapMs);
    }

    private List<Candidate> findCandidatePickups(Connection conn, PendingObservation drop) throws SQLException {
        String sql = """
            SELECT o.id, o.timestamp_ms, o.target_node_id
            FROM ig_observations o
            WHERE o.action_type = ?
              AND o.node_id = ?
              AND o.fingerprint_id = ?
              AND o.amount = ?
              AND o.timestamp_ms > ?
              AND o.timestamp_ms <= ?
              AND o.target_node_id IS NOT NULL
              AND o.id NOT IN (SELECT observation_id FROM ig_edge_evidence)
            ORDER BY o.timestamp_ms ASC, o.id ASC
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, PICKUP_ACTION);
            pstmt.setLong(2, drop.targetNodeId());
            pstmt.setLong(3, drop.fingerprintId());
            pstmt.setInt(4, drop.amount());
            pstmt.setLong(5, drop.timestampMs());
            pstmt.setLong(6, drop.timestampMs() + windowMs());
            return readCandidates(pstmt, "target_node_id");
        }
    }

    /**
     * Drops that could equally explain {@code pickup} (including {@code drop} itself).
     * This is the reverse ambiguity: if the picked-up stack has several plausible
     * origins, attributing it to one of them is correspondingly less certain.
     */
    private List<Candidate> findCompetingDrops(Connection conn, PendingObservation drop, Candidate pickup) throws SQLException {
        String sql = """
            SELECT o.id, o.timestamp_ms, o.node_id
            FROM ig_observations o
            WHERE o.action_type IN ('DROP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM')
              AND o.target_node_id = ?
              AND o.fingerprint_id = ?
              AND o.amount = ?
              AND o.timestamp_ms < ?
              AND o.timestamp_ms >= ?
              AND o.id NOT IN (SELECT observation_id FROM ig_edge_evidence)
            ORDER BY o.timestamp_ms ASC, o.id ASC
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, drop.targetNodeId());
            pstmt.setLong(2, drop.fingerprintId());
            pstmt.setInt(3, drop.amount());
            pstmt.setLong(4, pickup.timestampMs());
            pstmt.setLong(5, pickup.timestampMs() - windowMs());
            return readCandidates(pstmt, "node_id");
        }
    }

    private List<Candidate> readCandidates(PreparedStatement pstmt, String playerColumn) throws SQLException {
        List<Candidate> candidates = new ArrayList<>();
        try (ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                // wasNull() reflects the most recent read, so resolve the nullable
                // column into a Long before reading anything else.
                long rawPlayer = rs.getLong(playerColumn);
                Long playerNodeId = rs.wasNull() ? null : rawPlayer;
                candidates.add(new Candidate(
                        rs.getLong("id"),
                        rs.getLong("timestamp_ms"),
                        playerNodeId
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
     * Competing-candidate penalty.
     *
     * <p>{@code n} admissible candidates with no discriminator would justify no more
     * than a {@code 1/n} prior for the chosen one. Temporal separation from the
     * runner-up is a real discriminator, so the score recovers towards 1.0 in
     * proportion to that separation as a fraction of the window — capped at
     * {@link #SEPARATION_CAP}, because an alternative explanation that demonstrably
     * exists never becomes as good as no alternative at all.
     *
     * @param candidateCount total admissible candidates, including the chosen one
     * @param gapMs          time between the chosen candidate and the runner-up
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
    // Persistence
    // ---------------------------------------------------------------------

    /**
     * Writes the inferred edge, its two evidence rows, and the drop's correlation stamp
     * in a single transaction, so an edge can never exist without its evidence.
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
                // The edge bridges OVER the ground node: the ground node is evidence for
                // the transfer, not one of its endpoints.
                pstmt.setLong(1, drop.nodeId());
                pstmt.setLong(2, bridge.pickup().playerNodeId());
                pstmt.setLong(3, drop.fingerprintId());
                pstmt.setInt(4, drop.amount());
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

            String insertEvidenceSql = "INSERT INTO ig_edge_evidence (edge_id, observation_id) VALUES (?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertEvidenceSql)) {
                pstmt.setLong(1, edgeId);
                pstmt.setLong(2, drop.id());
                pstmt.addBatch();
                pstmt.setLong(1, edgeId);
                pstmt.setLong(2, bridge.pickup().id());
                pstmt.addBatch();
                pstmt.executeBatch();
            }

            markCorrelated(conn, drop.id(), nowMs);

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            LOGGER.error("Failed to persist ground bridge for observation {}. Rolled back.", drop.id(), e);
            throw e;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }
    }

    private void markCorrelated(Connection conn, long observationId, long nowMs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_observations SET correlated_at = ? WHERE id = ?")) {
            pstmt.setLong(1, nowMs);
            pstmt.setLong(2, observationId);
            pstmt.executeUpdate();
        }
    }

    // ---------------------------------------------------------------------
    // Explanation
    // ---------------------------------------------------------------------

    /**
     * Builds the human-readable justification stored on the edge. It names both players,
     * both observation ids, the place, both timestamps, the elapsed time, the candidate
     * counts on both sides, and the arithmetic that produced the confidence — everything
     * an admin needs to answer "why does ItemGraph think this transfer happened?"
     * without re-running the engine.
     */
    private String buildExplanation(Connection conn, PendingObservation drop, Bridge bridge) throws SQLException {
        String dropper = describeNode(conn, drop.nodeId());
        String picker = describeNode(conn, bridge.pickup().playerNodeId());
        String place = describeNode(conn, drop.targetNodeId());
        String item = describeFingerprint(conn, drop.fingerprintId());
        long dtMs = bridge.pickup().timestampMs() - drop.timestampMs();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Ground bridge: %s dropped %dx %s at %s on %s (observation %d); %s picked up an identical stack there %s later, on %s (observation %d). ",
                dropper, drop.amount(), item, place, formatTime(drop.timestampMs()), drop.id(),
                picker, formatDuration(dtMs), formatTime(bridge.pickup().timestampMs()), bridge.pickup().id()));

        sb.append("Matched on exact item fingerprint and exact amount, same ground block, pickup strictly after the drop, within the ")
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

        sb.append(String.format(Locale.ROOT,
                "Confidence %.4f = base %.2f x proximity %.4f x pickup-ambiguity %.4f x drop-ambiguity %.4f.",
                bridge.confidence(), BASE_CONFIDENCE, bridge.proximityFactor(),
                bridge.pickupAmbiguityFactor(), bridge.dropAmbiguityFactor()));

        if (bridge.pickupCandidates() > 1 || bridge.dropCandidates() > 1) {
            sb.append(" Confidence is reduced below the unambiguous ceiling because competing candidates exist:")
                    .append(" this is one plausible reconstruction, not the only one.");
        }

        return sb.toString();
    }

    /** Renders a node as something an admin recognises, without inventing detail. */
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
