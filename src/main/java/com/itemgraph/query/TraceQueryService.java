package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Backs /ig trace commands: item timelines, player timelines, container timelines,
 * and item fingerprint resolution (Phases 6, 8C, 9).
 */
public final class TraceQueryService {

    private static final String OBSERVATIONS_BASE = ObservationQueries.SELECT_FROM
            + " WHERE o.fingerprint_id = ?";

    private static final String EDGES_BASE = """
            SELECT e.id AS e_id,
                   e.edge_state AS e_edge_state,
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
                   dest.x AS dest_x, dest.y AS dest_y, dest.z AS dest_z,
                   e.fingerprint_id AS fp_id,
                   fp.item_id AS fp_item_id,
                   fp.custom_name AS fp_custom_name,
                   fp.fingerprint_hash AS fp_hash
            FROM ig_inferred_edges e
            LEFT JOIN ig_nodes origin ON origin.id = e.from_node_id
            LEFT JOIN ig_nodes dest ON dest.id = e.to_node_id
            LEFT JOIN ig_item_fingerprints fp ON fp.id = e.fingerprint_id
            """;

    /**
     * Reconstruct the timeline of an item fingerprint through time.
     */
    public TraceResult trace(Connection conn, long fingerprintId, int requestedLimit, QueryWindow window)
            throws SQLException {
        int applied = QueryLimits.clampLimit(requestedLimit);
        int fetch = applied + 1;

        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadObservedHops(conn, fingerprintId, fetch, window));
        hops.addAll(loadInferredHops(conn, fingerprintId, fetch, window));
        hops.addAll(loadTransformations(conn, fingerprintId, fetch, window));
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

    /**
     * Reconstruct the timeline of all movements involving a player.
     */
    public TraceResult tracePlayer(Connection conn, String playerQuery, int requestedLimit, QueryWindow window)
            throws SQLException {
        int applied = QueryLimits.clampLimit(requestedLimit);
        int fetch = applied + 1;

        NodeRef playerNode = null;
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, node_type, custom_label, level_id, x, y, z FROM ig_nodes WHERE node_type = 'PLAYER' AND (custom_label = ? OR custom_label LIKE ?) LIMIT 1")) {
            pstmt.setString(1, playerQuery);
            pstmt.setString(2, "%" + playerQuery + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    playerNode = ObservationQueries.node(rs, rs.getLong("id"), "");
                }
            }
        }

        if (playerNode == null) {
            return new TraceResult("player '" + playerQuery + "'", null, List.of(), window, applied, requestedLimit, false);
        }

        long nodeId = playerNode.id();
        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadNodeObservations(conn, nodeId, fetch, window));
        hops.addAll(loadNodeEdges(conn, nodeId, fetch, window));
        hops.sort(TraceHop.CHRONOLOGICAL);

        boolean truncated = hops.size() > applied;
        if (truncated) {
            hops = new ArrayList<>(hops.subList(0, applied));
        }

        String playerTitle = "player " + (playerNode.label() != null && !playerNode.label().isBlank()
                ? playerNode.label() : ("node#" + playerNode.id()));
        return new TraceResult(
                playerTitle,
                null,
                hops,
                window,
                applied,
                requestedLimit,
                truncated
        );
    }

    /**
     * Reconstruct the timeline of all movements involving a container block.
     */
    public TraceResult traceContainer(Connection conn, String level, double x, double y, double z, int requestedLimit, QueryWindow window)
            throws SQLException {
        int applied = QueryLimits.clampLimit(requestedLimit);
        int fetch = applied + 1;

        NodeRef containerNode = null;
        String findSql = "SELECT id, node_type, custom_label, level_id, x, y, z FROM ig_nodes WHERE node_type = 'CONTAINER' AND round(x) = round(?) AND round(y) = round(?) AND round(z) = round(?)"
                + (level != null ? " AND level_id = ?" : "") + " LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(findSql)) {
            pstmt.setDouble(1, x);
            pstmt.setDouble(2, y);
            pstmt.setDouble(3, z);
            if (level != null) {
                pstmt.setString(4, level);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    containerNode = ObservationQueries.node(rs, rs.getLong("id"), "");
                }
            }
        }

        String containerTitle = "container at [" + (int) x + ", " + (int) y + ", " + (int) z + "]";
        if (containerNode == null) {
            return new TraceResult(containerTitle, null, List.of(), window, applied, requestedLimit, false);
        }

        long nodeId = containerNode.id();
        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadNodeObservations(conn, nodeId, fetch, window));
        hops.addAll(loadNodeEdges(conn, nodeId, fetch, window));
        hops.sort(TraceHop.CHRONOLOGICAL);

        boolean truncated = hops.size() > applied;
        if (truncated) {
            hops = new ArrayList<>(hops.subList(0, applied));
        }

        String label = containerNode.label();
        String desc = (label != null && !label.isBlank())
                ? (label + " at [" + (int) x + ", " + (int) y + ", " + (int) z + "] (node#" + containerNode.id() + ")")
                : containerNode.describe();

        return new TraceResult(
                desc,
                null,
                hops,
                window,
                applied,
                requestedLimit,
                truncated
        );
    }

    /**
     * Resolves a string query (ID, registry ID, or custom name) to candidate fingerprints.
     */
    public List<FingerprintRef> resolveFingerprints(Connection conn, String query) throws SQLException {
        List<FingerprintRef> results = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return results;
        }

        if (query.matches("\\d+")) {
            long id = Long.parseLong(query);
            FingerprintRef ref = loadFingerprint(conn, id);
            if (ref.resolved()) {
                results.add(ref);
                return results;
            }
        }

        String sql = """
            SELECT id, item_id, custom_name, fingerprint_hash
            FROM ig_item_fingerprints
            WHERE item_id = ? OR item_id LIKE ? OR custom_name = ? OR custom_name LIKE ?
            ORDER BY id DESC LIMIT 10
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, query);
            pstmt.setString(2, "%" + query + "%");
            pstmt.setString(3, query);
            pstmt.setString(4, "%" + query + "%");
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new FingerprintRef(
                            rs.getLong("id"),
                            rs.getString("item_id"),
                            rs.getString("custom_name"),
                            rs.getString("fingerprint_hash")
                    ));
                }
            }
        }
        return results;
    }

    private List<TraceHop> loadObservedHops(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder(OBSERVATIONS_BASE);
        if (window.sinceMs() != null) {
            sql.append(" AND COALESCE(o.timestamp_end_ms, o.timestamp_ms) >= ?");
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

    private List<TraceHop> loadNodeObservations(Connection conn, long nodeId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder(ObservationQueries.SELECT_FROM)
                .append(" WHERE (o.node_id = ? OR o.target_node_id = ?)");
        if (window.sinceMs() != null) {
            sql.append(" AND COALESCE(o.timestamp_end_ms, o.timestamp_ms) >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND o.timestamp_ms <= ?");
        }
        sql.append(" ORDER BY o.timestamp_ms ASC, o.id ASC LIMIT ?");

        List<TraceHop> hops = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            pstmt.setLong(idx++, nodeId);
            pstmt.setLong(idx++, nodeId);
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

    private List<TraceHop> loadInferredHops(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder(EDGES_BASE).append(" WHERE e.edge_state = 'ACTIVE' AND e.fingerprint_id = ?");
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
                    hops.add(mapEdgeHop(rs));
                }
            }
        }
        return hops;
    }

    private List<TraceHop> loadNodeEdges(Connection conn, long nodeId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder(EDGES_BASE).append(" WHERE e.edge_state = 'ACTIVE' AND (e.from_node_id = ? OR e.to_node_id = ?)");
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
            pstmt.setLong(idx++, nodeId);
            pstmt.setLong(idx++, nodeId);
            if (window.sinceMs() != null) {
                pstmt.setLong(idx++, window.sinceMs());
            }
            if (window.untilMs() != null) {
                pstmt.setLong(idx++, window.untilMs());
            }
            pstmt.setInt(idx, fetch);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    hops.add(mapEdgeHop(rs));
                }
            }
        }
        return hops;
    }

    private TraceHop mapEdgeHop(ResultSet rs) throws SQLException {
        long fromId = rs.getLong("origin_id");
        NodeRef from = rs.wasNull() ? null : ObservationQueries.node(rs, fromId, "origin");

        long toId = rs.getLong("dest_id");
        NodeRef to = rs.wasNull() ? null : ObservationQueries.node(rs, toId, "dest");

        long timeStart = rs.getLong("e_time_start");
        long timeEnd = rs.getLong("e_time_end");

        long fpId = rs.getLong("fp_id");
        FingerprintRef fp = rs.wasNull() ? null : new FingerprintRef(
                fpId,
                rs.getString("fp_item_id"),
                rs.getString("fp_custom_name"),
                rs.getString("fp_hash")
        );

        return new TraceHop(
                TraceHop.Kind.INFERRED,
                rs.getLong("e_id"),
                from,
                to,
                rs.getInt("e_amount"),
                timeStart,
                timeEnd,
                rs.getDouble("e_confidence"),
                "inferred transfer spanning " + QueryFormatter.formatDuration(timeEnd - timeStart),
                fp
        );
    }

    private List<TraceHop> loadTransformations(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
            SELECT t.id AS t_id,
                   t.transformation_type AS t_type,
                   t.player_node_id AS p_id,
                   p.node_type AS p_type,
                   p.custom_label AS p_label,
                   p.level_id AS p_level,
                   p.x AS p_x, p.y AS p_y, p.z AS p_z,
                   t.source_fingerprint_id AS s_fp_id,
                   s_fp.item_id AS s_item_id,
                   s_fp.custom_name AS s_name,
                   s_fp.fingerprint_hash AS s_hash,
                   t.result_fingerprint_id AS r_fp_id,
                   r_fp.item_id AS r_item_id,
                   r_fp.custom_name AS r_name,
                   r_fp.fingerprint_hash AS r_hash,
                   t.quantity AS t_amount,
                   t.timestamp_ms AS t_timestamp,
                   t.details AS t_details
            FROM ig_item_transformations t
            LEFT JOIN ig_nodes p ON p.id = t.player_node_id
            LEFT JOIN ig_item_fingerprints s_fp ON s_fp.id = t.source_fingerprint_id
            LEFT JOIN ig_item_fingerprints r_fp ON r_fp.id = t.result_fingerprint_id
            WHERE (t.source_fingerprint_id = ? OR t.result_fingerprint_id = ?)
        """);
        if (window.sinceMs() != null) {
            sql.append(" AND t.timestamp_ms >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND t.timestamp_ms <= ?");
        }
        sql.append(" ORDER BY t.timestamp_ms ASC, t.id ASC LIMIT ?");

        List<TraceHop> hops = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            pstmt.setLong(idx++, fingerprintId);
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
                    long pId = rs.getLong("p_id");
                    NodeRef playerNode = rs.wasNull() ? null : ObservationQueries.node(rs, pId, "p");

                    long sourceFpId = rs.getLong("s_fp_id");
                    long resultFpId = rs.getLong("r_fp_id");
                    String type = rs.getString("t_type");
                    String details = rs.getString("t_details");
                    int amount = rs.getInt("t_amount");
                    long timestamp = rs.getLong("t_timestamp");

                    FingerprintRef otherFp = (sourceFpId == fingerprintId)
                            ? new FingerprintRef(resultFpId, rs.getString("r_item_id"), rs.getString("r_name"), rs.getString("r_hash"))
                            : new FingerprintRef(sourceFpId, rs.getString("s_item_id"), rs.getString("s_name"), rs.getString("s_hash"));

                    String arrow = (sourceFpId == fingerprintId) ? "->" : "<-";
                    String detail = "[TRANSFORMATION " + type + " " + arrow + " " + otherFp.describe() + "] (" + details + ")";

                    hops.add(new TraceHop(
                            TraceHop.Kind.OBSERVED,
                            rs.getLong("t_id"),
                            playerNode,
                            playerNode,
                            amount,
                            timestamp,
                            timestamp,
                            null,
                            detail,
                            otherFp
                    ));
                }
            }
        }
        return hops;
    }

    public FingerprintRef loadFingerprint(Connection conn, long fingerprintId) throws SQLException {
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
