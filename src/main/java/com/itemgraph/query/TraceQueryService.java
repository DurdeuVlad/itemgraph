package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Backs {@code /ig trace item <fingerprintId> [limit] [sinceMinutes]}: the known path of
 * one item fingerprint through time.
 *
 * <h2>What a trace actually is</h2>
 *
 * <p>Two tables describe movement and both are queried:
 *
 * <ul>
 *   <li>{@code ig_observations} — a hop that is <b>directly observed</b>. After the Phase 4
 *       and Phase 5 direction fixes a container withdrawal, a deposit and a drop are each a
 *       single row that already names both endpoints, so most hops in a real trace come
 *       from here and need no inference at all.</li>
 *   <li>{@code ig_inferred_edges} — a hop that is <b>inferred</b>. These are the ground
 *       bridges: nothing in the raw evidence states that the stack one player dropped is
 *       the stack another player later picked up, so that link is a scored claim.</li>
 * </ul>
 *
 * <p>They are merged into one chronological timeline because the item's path is what the
 * admin asked for, and splitting it into two lists would force them to re-interleave the
 * timestamps by hand. Each hop keeps its own {@link TraceHop.Kind}, so the merge never
 * costs the evidence/inference distinction.
 *
 * <p>Note that an inferred ground bridge and the two observations underneath it will all
 * appear: the drop (player -> GROUND), the bridge (player -> player) and the pickup
 * (GROUND -> player). That is intentional. Hiding the observations would hide the
 * evidence, and hiding the bridge would hide the claim; showing both, distinctly labelled,
 * is the only presentation that is honest about what is known versus reconstructed.
 *
 * <h2>Bounding</h2>
 *
 * <p>Each side is queried with {@code LIMIT applied + 1}. Because both sides are ordered
 * ascending, taking the first {@code applied} hops of the merge yields exactly the
 * earliest {@code applied} hops overall, and the sentinel row makes "there is more" a
 * fact rather than a guess. See {@link QueryLimits} for the cap and {@link QueryWindow}
 * for the time filter UX.
 *
 * <p>Reads only.
 */
public final class TraceQueryService {

    private static final String OBSERVATIONS_BASE = ObservationQueries.SELECT_FROM
            + " WHERE o.fingerprint_id = ?";

    private static final String EDGES_BASE = """
            SELECT e.id AS e_id,
                   e.amount AS e_amount,
                   e.time_start AS e_time_start,
                   e.time_end AS e_time_end,
                   e.confidence AS e_confidence,
                   e.from_node_id AS origin_id,
                   origin.node_type AS origin_type,
                   origin.custom_label AS origin_label,
                   origin.level_id AS origin_level,
                   origin.x AS origin_x, origin.y AS origin_y, origin.z AS origin_z,
                   e.to_node_id AS dest_id,
                   dest.node_type AS dest_type,
                   dest.custom_label AS dest_label,
                   dest.level_id AS dest_level,
                   dest.x AS dest_x, dest.y AS dest_y, dest.z AS dest_z
            FROM ig_inferred_edges e
            LEFT JOIN ig_nodes origin ON origin.id = e.from_node_id
            LEFT JOIN ig_nodes dest ON dest.id = e.to_node_id
            WHERE e.fingerprint_id = ?""";

    /**
     * @param requestedLimit hops the caller asked for; clamped by {@link QueryLimits#clampLimit(int)}
     * @param window         time bound; {@link QueryWindow#unbounded()} for no time filter
     */
    public TraceResult trace(Connection conn, long fingerprintId, int requestedLimit, QueryWindow window)
            throws SQLException {
        int applied = QueryLimits.clampLimit(requestedLimit);
        int fetch = applied + 1;

        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadObservedHops(conn, fingerprintId, fetch, window));
        hops.addAll(loadInferredHops(conn, fingerprintId, fetch, window));
        hops.sort(TraceHop.CHRONOLOGICAL);

        boolean truncated = hops.size() > applied;
        if (truncated) {
            hops = new ArrayList<>(hops.subList(0, applied));
        }

        return new TraceResult(
                loadFingerprint(conn, fingerprintId),
                hops,
                window,
                applied,
                requestedLimit,
                truncated
        );
    }

    private List<TraceHop> loadObservedHops(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder(OBSERVATIONS_BASE);
        if (window.sinceMs() != null) {
            sql.append(" AND o.timestamp_ms >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND o.timestamp_ms <= ?");
        }
        sql.append(" ORDER BY o.timestamp_ms ASC, o.id ASC LIMIT ?");

        List<TraceHop> hops = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            pstmt.setLong(idx++, fingerprintId);
            if (window.sinceMs() != null) {
                pstmt.setLong(idx++, window.sinceMs());
            }
            if (window.untilMs() != null) {
                pstmt.setLong(idx++, window.untilMs());
            }
            pstmt.setInt(idx, fetch);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    hops.add(TraceHop.observed(ObservationQueries.map(rs)));
                }
            }
        }
        return hops;
    }

    /**
     * Inferred edges span an interval, so the window test is an overlap rather than
     * containment: an edge whose drop predates the window but whose pickup falls inside it
     * really did happen during the window, and dropping it would leave a visible gap in
     * the reconstructed path.
     */
    private List<TraceHop> loadInferredHops(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder(EDGES_BASE);
        if (window.sinceMs() != null) {
            sql.append(" AND e.time_end >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND e.time_start <= ?");
        }
        sql.append(" ORDER BY e.time_start ASC, e.id ASC LIMIT ?");

        List<TraceHop> hops = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            pstmt.setLong(idx++, fingerprintId);
            if (window.sinceMs() != null) {
                pstmt.setLong(idx++, window.sinceMs());
            }
            if (window.untilMs() != null) {
                pstmt.setLong(idx++, window.untilMs());
            }
            pstmt.setInt(idx, fetch);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    long fromId = rs.getLong("origin_id");
                    NodeRef from = rs.wasNull() ? null : ObservationQueries.node(rs, fromId, "origin");

                    long toId = rs.getLong("dest_id");
                    NodeRef to = rs.wasNull() ? null : ObservationQueries.node(rs, toId, "dest");

                    long timeStart = rs.getLong("e_time_start");
                    long timeEnd = rs.getLong("e_time_end");

                    hops.add(new TraceHop(
                            TraceHop.Kind.INFERRED,
                            rs.getLong("e_id"),
                            from,
                            to,
                            rs.getInt("e_amount"),
                            timeStart,
                            timeEnd,
                            rs.getDouble("e_confidence"),
                            "inferred transfer spanning " + QueryFormatter.formatDuration(timeEnd - timeStart)
                    ));
                }
            }
        }
        return hops;
    }

    private FingerprintRef loadFingerprint(Connection conn, long fingerprintId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT item_id, custom_name, fingerprint_hash FROM ig_item_fingerprints WHERE id = ?")) {
            pstmt.setLong(1, fingerprintId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return FingerprintRef.missing(fingerprintId);
                }
                return new FingerprintRef(
                        fingerprintId,
                        rs.getString("item_id"),
                        rs.getString("custom_name"),
                        rs.getString("fingerprint_hash")
                );
            }
        }
    }
}
