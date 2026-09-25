package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Backs /ig trace commands: item timelines, player timelines, container timelines,
 * and item fingerprint resolution (Phases 6, 8C, 9).
 */
public final class TraceQueryService {

    private static final String OBSERVATIONS_BASE = ObservationQueries.SELECT_FROM
            + " WHERE o.fingerprint_id = ?";

    private record CursorSql(String clause, List<Long> parameters) {}

    private static CursorSql cursorSql(String timeColumn, String idColumn, TraceHop.Kind kind,
                                       TraceHop.Source source, TraceCursor cursor, TracePage.Direction direction) {
        if (cursor == null) {
            return new CursorSql("", List.of());
        }
        int kindComparison = Integer.compare(kind.ordinal(), cursor.kind().ordinal());
        if (direction == TracePage.Direction.FORWARD) {
            if (kindComparison > 0) {
                return new CursorSql(" AND " + timeColumn + " >= ?", List.of(cursor.timestampMs()));
            }
            if (kindComparison < 0) {
                return new CursorSql(" AND " + timeColumn + " > ?", List.of(cursor.timestampMs()));
            }
            String idComparison = source.ordinal() > cursor.source().ordinal() ? ">=" : ">";
            return new CursorSql(" AND (" + timeColumn + " > ? OR (" + timeColumn + " = ? AND "
                    + idColumn + " " + idComparison + " ?))",
                    List.of(cursor.timestampMs(), cursor.timestampMs(), cursor.refId()));
        }
        if (kindComparison < 0) {
            return new CursorSql(" AND " + timeColumn + " <= ?", List.of(cursor.timestampMs()));
        }
        if (kindComparison > 0) {
            return new CursorSql(" AND " + timeColumn + " < ?", List.of(cursor.timestampMs()));
        }
        String idComparison = source.ordinal() < cursor.source().ordinal() ? "<=" : "<";
        return new CursorSql(" AND (" + timeColumn + " < ? OR (" + timeColumn + " = ? AND "
                + idColumn + " " + idComparison + " ?))",
                List.of(cursor.timestampMs(), cursor.timestampMs(), cursor.refId()));
    }

    private static int bindCursor(PreparedStatement pstmt, int index, CursorSql cursor) throws SQLException {
        for (long value : cursor.parameters()) {
            pstmt.setLong(index++, value);
        }
        return index;
    }

    private static int clampPageSize(int requestedPageSize) {
        return Math.max(1, Math.min(QueryLimits.MAX_GUI_PAGE_SIZE, requestedPageSize));
    }

    private static TracePage page(String title, FingerprintRef fingerprint, NodeRef targetNode,
                                  List<FingerprintRef> candidates, List<TraceHop> hops, int pageSize,
                                  QueryWindow window, TracePage.Direction direction, TraceCursor cursor) {
        hops.sort(direction == TracePage.Direction.FORWARD
                ? TraceHop.CHRONOLOGICAL : TraceHop.CHRONOLOGICAL.reversed());
        boolean moreInDirection = hops.size() > pageSize;
        if (moreInDirection) {
            hops = new ArrayList<>(hops.subList(0, pageSize));
        }
        if (direction == TracePage.Direction.BACKWARD) {
            hops.sort(TraceHop.CHRONOLOGICAL);
        }
        if (hops.isEmpty()) {
            return new TracePage(title, TracePage.Resolution.RESOLVED, fingerprint, targetNode,
                    candidates, List.of(), hops, window, pageSize, null, false, null, false);
        }
        boolean hasPrevious = direction == TracePage.Direction.BACKWARD
                ? moreInDirection : cursor != null;
        boolean hasNext = direction == TracePage.Direction.FORWARD
                ? moreInDirection : cursor != null;
        TraceCursor previousCursor = hasPrevious ? TraceCursor.after(hops.get(0)) : null;
        TraceCursor nextCursor = hasNext ? TraceCursor.after(hops.get(hops.size() - 1)) : null;
        return new TracePage(title, TracePage.Resolution.RESOLVED, fingerprint, targetNode,
                candidates, List.of(), hops, window, pageSize, previousCursor, hasPrevious, nextCursor, hasNext);
    }

    private static String orderBy(String timeColumn, String idColumn, TracePage.Direction direction) {
        String order = direction == TracePage.Direction.FORWARD ? " ASC" : " DESC";
        return " ORDER BY " + timeColumn + order + ", " + idColumn + order + " LIMIT ?";
    }

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

    public TracePage traceItemPage(Connection conn, String itemQuery, int requestedPageSize,
                                   QueryWindow window, TraceCursor cursor) throws SQLException {
        return traceItemPage(conn, itemQuery, requestedPageSize, window, cursor, TracePage.Direction.FORWARD);
    }

    public TracePage traceItemPage(Connection conn, String itemQuery, int requestedPageSize,
                                   QueryWindow window, TraceCursor cursor, TracePage.Direction direction)
            throws SQLException {
        int pageSize = clampPageSize(requestedPageSize);
        List<FingerprintRef> candidates = resolveFingerprints(conn, itemQuery);
        if (candidates.isEmpty()) {
            return new TracePage("item '" + itemQuery + "'", TracePage.Resolution.NOT_FOUND,
                    null, null, List.of(), List.of(), List.of(), window, pageSize, null, false, null, false);
        }
        if (candidates.size() > 1) {
            return new TracePage("items matching '" + itemQuery + "'", TracePage.Resolution.AMBIGUOUS,
                    null, null, candidates, List.of(), List.of(), window, pageSize, null, false, null, false);
        }

        return fingerprintPage(conn, candidates.get(0), pageSize, window, cursor, direction);
    }

    public TracePage traceFingerprintPage(Connection conn, long fingerprintId, int requestedPageSize,
                                          QueryWindow window, TraceCursor cursor, TracePage.Direction direction)
            throws SQLException {
        int pageSize = clampPageSize(requestedPageSize);
        FingerprintRef fingerprint = loadFingerprint(conn, fingerprintId);
        if (!fingerprint.resolved()) {
            return new TracePage("item fingerprint#" + fingerprintId, TracePage.Resolution.NOT_FOUND,
                    fingerprint, null, List.of(), List.of(), List.of(), window, pageSize, null, false, null, false);
        }
        return fingerprintPage(conn, fingerprint, pageSize, window, cursor, direction);
    }

    private TracePage fingerprintPage(Connection conn, FingerprintRef fingerprint, int pageSize,
                                       QueryWindow window, TraceCursor cursor, TracePage.Direction direction)
            throws SQLException {
        int fetch = pageSize + 1;
        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadObservedHops(conn, fingerprint.id(), fetch, window, cursor, direction));
        hops.addAll(loadInferredHops(conn, fingerprint.id(), fetch, window, cursor, direction));
        hops.addAll(loadTransformations(conn, fingerprint.id(), fetch, window, cursor, direction));
        return page("item " + fingerprint.describeFull(), fingerprint, null, List.of(), hops,
                pageSize, window, direction, cursor);
    }

    public TracePage tracePlayerPage(Connection conn, String playerQuery, int requestedPageSize,
                                     QueryWindow window, TraceCursor cursor) throws SQLException {
        return tracePlayerPage(conn, playerQuery, requestedPageSize, window, cursor, TracePage.Direction.FORWARD);
    }

    public TracePage tracePlayerPage(Connection conn, String playerQuery, int requestedPageSize,
                                     QueryWindow window, TraceCursor cursor, TracePage.Direction direction)
            throws SQLException {
        int pageSize = clampPageSize(requestedPageSize);
        List<NodeRef> candidates = resolvePlayerNodes(conn, playerQuery);
        if (candidates.isEmpty()) {
            return new TracePage("player '" + playerQuery + "'", TracePage.Resolution.NOT_FOUND,
                    null, null, List.of(), List.of(), List.of(), window, pageSize, null, false, null, false);
        }
        if (candidates.size() > 1) {
            return new TracePage("players matching '" + playerQuery + "'", TracePage.Resolution.AMBIGUOUS,
                    null, null, List.of(), candidates, List.of(), window, pageSize, null, false, null, false);
        }
        NodeRef playerNode = candidates.get(0);
        return nodePage(conn, playerNode, playerTitle(playerNode), pageSize, window, cursor, direction);
    }

    public TracePage tracePlayerNodePage(Connection conn, long playerNodeId, int requestedPageSize,
                                         QueryWindow window, TraceCursor cursor, TracePage.Direction direction)
            throws SQLException {
        int pageSize = clampPageSize(requestedPageSize);
        NodeRef playerNode = findNodeById(conn, playerNodeId, "PLAYER");
        if (playerNode == null) {
            return new TracePage("player node#" + playerNodeId, TracePage.Resolution.NOT_FOUND,
                    null, null, List.of(), List.of(), List.of(), window, pageSize, null, false, null, false);
        }
        return nodePage(conn, playerNode, playerTitle(playerNode), pageSize, window, cursor, direction);
    }

    private static String playerTitle(NodeRef playerNode) {
        return "player " + (playerNode.label() != null && !playerNode.label().isBlank()
                ? playerNode.label() : "node#" + playerNode.id());
    }

    private TracePage nodePage(Connection conn, NodeRef node, String title, int pageSize,
                               QueryWindow window, TraceCursor cursor, TracePage.Direction direction)
            throws SQLException {
        int fetch = pageSize + 1;
        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadNodeObservations(conn, node.id(), fetch, window, cursor, direction));
        hops.addAll(loadNodeEdges(conn, node.id(), fetch, window, cursor, direction));
        return page(title, null, node, List.of(), hops, pageSize, window, direction, cursor);
    }

    public TracePage traceContainerPage(Connection conn, String level, double x, double y, double z,
                                        int requestedPageSize, QueryWindow window, TraceCursor cursor)
            throws SQLException {
        return traceContainerPage(conn, level, x, y, z, requestedPageSize,
                window, cursor, TracePage.Direction.FORWARD);
    }

    public TracePage traceContainerPage(Connection conn, String level, double x, double y, double z,
                                        int requestedPageSize, QueryWindow window, TraceCursor cursor,
                                        TracePage.Direction direction) throws SQLException {
        int pageSize = clampPageSize(requestedPageSize);
        List<NodeRef> candidates = resolveContainerNodes(conn, level, x, y, z);
        String title = "container " + level + " at [" + (int) x + ", " + (int) y + ", " + (int) z + "]";
        if (candidates.isEmpty()) {
            return new TracePage(title, TracePage.Resolution.NOT_FOUND,
                    null, null, List.of(), List.of(), List.of(), window, pageSize, null, false, null, false);
        }
        if (candidates.size() > 1) {
            return new TracePage("multiple " + title + " nodes", TracePage.Resolution.AMBIGUOUS,
                    null, null, List.of(), candidates, List.of(), window, pageSize, null, false, null, false);
        }
        NodeRef containerNode = candidates.get(0);
        return nodePage(conn, containerNode, containerNode.describe(), pageSize, window, cursor, direction);
    }

    public TracePage traceContainerNodePage(Connection conn, long containerNodeId, int requestedPageSize,
                                            QueryWindow window, TraceCursor cursor,
                                            TracePage.Direction direction) throws SQLException {
        int pageSize = clampPageSize(requestedPageSize);
        NodeRef containerNode = findNodeById(conn, containerNodeId, "CONTAINER");
        if (containerNode == null) {
            return new TracePage("container node#" + containerNodeId, TracePage.Resolution.NOT_FOUND,
                    null, null, List.of(), List.of(), List.of(), window, pageSize, null, false, null, false);
        }
        return nodePage(conn, containerNode, containerNode.describe(), pageSize, window, cursor, direction);
    }

    /**
     * Reconstruct the timeline of all movements involving a player.
     */
    public TraceResult tracePlayer(Connection conn, String playerQuery, int requestedLimit, QueryWindow window)
            throws SQLException {
        List<NodeRef> candidates = resolvePlayerNodes(conn, playerQuery);
        if (candidates.size() != 1) {
            return new TraceResult("player '" + playerQuery + "'", null, List.of(), window,
                    QueryLimits.clampLimit(requestedLimit), requestedLimit, false);
        }
        return tracePlayerNode(conn, candidates.get(0).id(), requestedLimit, window);
    }

    public TraceResult tracePlayerNode(Connection conn, long playerNodeId, int requestedLimit, QueryWindow window)
            throws SQLException {
        NodeRef playerNode = findNodeById(conn, playerNodeId, "PLAYER");
        if (playerNode == null) {
            return new TraceResult("player node#" + playerNodeId, null, List.of(), window,
                    QueryLimits.clampLimit(requestedLimit), requestedLimit, false);
        }
        String title = playerTitle(playerNode);
        return traceNode(conn, playerNode, title, requestedLimit, window);
    }

    private TraceResult traceNode(Connection conn, NodeRef node, String title, int requestedLimit,
                                  QueryWindow window) throws SQLException {
        int applied = QueryLimits.clampLimit(requestedLimit);
        int fetch = applied + 1;
        List<TraceHop> hops = new ArrayList<>();
        hops.addAll(loadNodeObservations(conn, node.id(), fetch, window));
        hops.addAll(loadNodeEdges(conn, node.id(), fetch, window));
        hops.sort(TraceHop.CHRONOLOGICAL);
        boolean truncated = hops.size() > applied;
        if (truncated) {
            hops = new ArrayList<>(hops.subList(0, applied));
        }
        return new TraceResult(title, null, hops, window, applied, requestedLimit, truncated);
    }

    private NodeRef findNodeById(Connection conn, long nodeId, String nodeType) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, node_type, custom_label, level_id, x, y, z FROM ig_nodes WHERE id = ? AND node_type = ?")) {
            pstmt.setLong(1, nodeId);
            pstmt.setString(2, nodeType);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? ObservationQueries.node(rs, rs.getLong("id"), "") : null;
            }
        }
    }

    public List<NodeRef> resolvePlayerNodes(Connection conn, String playerQuery) throws SQLException {
        if (playerQuery == null || playerQuery.isBlank()) {
            return List.of();
        }
        List<NodeRef> candidates = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, node_type, custom_label, level_id, x, y, z FROM ig_nodes WHERE node_type = 'PLAYER' AND custom_label = ? ORDER BY id ASC LIMIT 10")) {
            pstmt.setString(1, playerQuery);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(ObservationQueries.node(rs, rs.getLong("id"), ""));
                }
            }
        }
        return candidates;
    }

    /**
     * Reconstruct the timeline of all movements involving a container block.
     */
    public TraceResult traceContainer(Connection conn, String level, double x, double y, double z,
                                      int requestedLimit, QueryWindow window) throws SQLException {
        List<NodeRef> candidates = resolveContainerNodes(conn, level, x, y, z);
        if (candidates.size() != 1) {
            String title = "container " + level + " at [" + (int) x + ", " + (int) y + ", " + (int) z + "]";
            return new TraceResult(title, null, List.of(), window,
                    QueryLimits.clampLimit(requestedLimit), requestedLimit, false);
        }
        return traceContainerNode(conn, candidates.get(0).id(), requestedLimit, window);
    }

    public TraceResult traceContainerNode(Connection conn, long containerNodeId, int requestedLimit,
                                          QueryWindow window) throws SQLException {
        NodeRef containerNode = findNodeById(conn, containerNodeId, "CONTAINER");
        if (containerNode == null) {
            return new TraceResult("container node#" + containerNodeId, null, List.of(), window,
                    QueryLimits.clampLimit(requestedLimit), requestedLimit, false);
        }
        String label = containerNode.label();
        String title = label != null && !label.isBlank() && containerNode.x() != null
                ? label + " at [" + containerNode.x().intValue() + ", " + containerNode.y().intValue() + ", "
                        + containerNode.z().intValue() + "] (node#" + containerNode.id() + ")"
                : containerNode.describe();
        return traceNode(conn, containerNode, title, requestedLimit, window);
    }

    public List<NodeRef> resolveContainerNodes(Connection conn, String level, double x, double y, double z)
            throws SQLException {
        List<NodeRef> candidates = new ArrayList<>();
        String findSql = "SELECT id, node_type, custom_label, level_id, x, y, z FROM ig_nodes WHERE node_type = 'CONTAINER' AND x = ? AND y = ? AND z = ?"
                + (level != null ? " AND level_id = ?" : "") + " ORDER BY id ASC LIMIT 10";
        try (PreparedStatement pstmt = conn.prepareStatement(findSql)) {
            pstmt.setDouble(1, x);
            pstmt.setDouble(2, y);
            pstmt.setDouble(3, z);
            if (level != null) {
                pstmt.setString(4, level);
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(ObservationQueries.node(rs, rs.getLong("id"), ""));
                }
            }
        }
        return candidates;
    }

    /**
     * Resolves a string query (ID, registry ID, or custom name) to candidate fingerprints.
     */
    public List<FingerprintRef> resolveFingerprints(Connection conn, String query) throws SQLException {
        Map<Long, FingerprintRef> results = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (query.startsWith("id:")) {
            String idText = query.substring(3);
            if (!idText.matches("\\d+")) {
                return List.of();
            }
            try {
                FingerprintRef idMatch = loadFingerprint(conn, Long.parseLong(idText));
                return idMatch.resolved() ? List.of(idMatch) : List.of();
            } catch (NumberFormatException ignored) {
                return List.of();
            }
        }

        if (query.matches("\\d+")) {
            try {
                FingerprintRef idMatch = loadFingerprint(conn, Long.parseLong(query));
                if (idMatch.resolved()) {
                    results.put(idMatch.id(), idMatch);
                }
            } catch (NumberFormatException ignored) {
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
                    long id = rs.getLong("id");
                    if (results.size() < 10) {
                        results.putIfAbsent(id, new FingerprintRef(
                                id,
                                rs.getString("item_id"),
                                rs.getString("custom_name"),
                                rs.getString("fingerprint_hash")
                        ));
                    }
                }
            }
        }
        return new ArrayList<>(results.values());
    }

    private List<TraceHop> loadObservedHops(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        return loadObservedHops(conn, fingerprintId, fetch, window, null, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadObservedHops(Connection conn, long fingerprintId, int fetch,
                                            QueryWindow window, TraceCursor cursor) throws SQLException {
        return loadObservedHops(conn, fingerprintId, fetch, window, cursor, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadObservedHops(Connection conn, long fingerprintId, int fetch,
                                            QueryWindow window, TraceCursor cursor,
                                            TracePage.Direction direction) throws SQLException {
        StringBuilder sql = new StringBuilder(OBSERVATIONS_BASE);
        if (window.sinceMs() != null) {
            sql.append(" AND COALESCE(o.timestamp_end_ms, o.timestamp_ms) >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND o.timestamp_ms <= ?");
        }
        CursorSql cursorFilter = cursorSql("o.timestamp_ms", "o.id", TraceHop.Kind.OBSERVED,
                TraceHop.Source.OBSERVATION, cursor, direction);
        sql.append(cursorFilter.clause());
        sql.append(orderBy("o.timestamp_ms", "o.id", direction));

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
            idx = bindCursor(pstmt, idx, cursorFilter);
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
        return loadNodeObservations(conn, nodeId, fetch, window, null, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadNodeObservations(Connection conn, long nodeId, int fetch,
                                                QueryWindow window, TraceCursor cursor) throws SQLException {
        return loadNodeObservations(conn, nodeId, fetch, window, cursor, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadNodeObservations(Connection conn, long nodeId, int fetch,
                                                QueryWindow window, TraceCursor cursor,
                                                TracePage.Direction direction) throws SQLException {
        StringBuilder sql = new StringBuilder(ObservationQueries.SELECT_FROM)
                .append(" WHERE (o.node_id = ? OR o.target_node_id = ?)");
        if (window.sinceMs() != null) {
            sql.append(" AND COALESCE(o.timestamp_end_ms, o.timestamp_ms) >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND o.timestamp_ms <= ?");
        }
        CursorSql cursorFilter = cursorSql("o.timestamp_ms", "o.id", TraceHop.Kind.OBSERVED,
                TraceHop.Source.OBSERVATION, cursor, direction);
        sql.append(cursorFilter.clause());
        sql.append(orderBy("o.timestamp_ms", "o.id", direction));

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
            idx = bindCursor(pstmt, idx, cursorFilter);
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
        return loadInferredHops(conn, fingerprintId, fetch, window, null, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadInferredHops(Connection conn, long fingerprintId, int fetch,
                                            QueryWindow window, TraceCursor cursor) throws SQLException {
        return loadInferredHops(conn, fingerprintId, fetch, window, cursor, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadInferredHops(Connection conn, long fingerprintId, int fetch,
                                            QueryWindow window, TraceCursor cursor,
                                            TracePage.Direction direction) throws SQLException {
        StringBuilder sql = new StringBuilder(EDGES_BASE).append(" WHERE e.edge_state = 'ACTIVE' AND e.fingerprint_id = ?");
        if (window.sinceMs() != null) {
            sql.append(" AND e.time_end >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND e.time_start <= ?");
        }
        CursorSql cursorFilter = cursorSql("e.time_start", "e.id", TraceHop.Kind.INFERRED,
                TraceHop.Source.INFERRED_EDGE, cursor, direction);
        sql.append(cursorFilter.clause());
        sql.append(orderBy("e.time_start", "e.id", direction));

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
            idx = bindCursor(pstmt, idx, cursorFilter);
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
        return loadNodeEdges(conn, nodeId, fetch, window, null, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadNodeEdges(Connection conn, long nodeId, int fetch,
                                         QueryWindow window, TraceCursor cursor) throws SQLException {
        return loadNodeEdges(conn, nodeId, fetch, window, cursor, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadNodeEdges(Connection conn, long nodeId, int fetch,
                                         QueryWindow window, TraceCursor cursor,
                                         TracePage.Direction direction) throws SQLException {
        StringBuilder sql = new StringBuilder(EDGES_BASE).append(" WHERE e.edge_state = 'ACTIVE' AND (e.from_node_id = ? OR e.to_node_id = ?)");
        if (window.sinceMs() != null) {
            sql.append(" AND e.time_end >= ?");
        }
        if (window.untilMs() != null) {
            sql.append(" AND e.time_start <= ?");
        }
        CursorSql cursorFilter = cursorSql("e.time_start", "e.id", TraceHop.Kind.INFERRED,
                TraceHop.Source.INFERRED_EDGE, cursor, direction);
        sql.append(cursorFilter.clause());
        sql.append(orderBy("e.time_start", "e.id", direction));

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
            idx = bindCursor(pstmt, idx, cursorFilter);
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
        long edgeId = rs.getLong("e_id");
        double confidence = rs.getDouble("e_confidence");
        if (rs.wasNull()) {
            throw new SQLException("Inferred edge #" + edgeId + " has no stored confidence.");
        }

        return new TraceHop(
                TraceHop.Kind.INFERRED,
                edgeId,
                from,
                to,
                rs.getInt("e_amount"),
                timeStart,
                timeEnd,
                confidence,
                "inferred transfer spanning " + QueryFormatter.formatDuration(timeEnd - timeStart),
                fp
        );
    }

    private List<TraceHop> loadTransformations(Connection conn, long fingerprintId, int fetch, QueryWindow window)
            throws SQLException {
        return loadTransformations(conn, fingerprintId, fetch, window, null, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadTransformations(Connection conn, long fingerprintId, int fetch,
                                               QueryWindow window, TraceCursor cursor) throws SQLException {
        return loadTransformations(conn, fingerprintId, fetch, window, cursor, TracePage.Direction.FORWARD);
    }

    private List<TraceHop> loadTransformations(Connection conn, long fingerprintId, int fetch,
                                               QueryWindow window, TraceCursor cursor,
                                               TracePage.Direction direction) throws SQLException {
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
        CursorSql cursorFilter = cursorSql("t.timestamp_ms", "t.id", TraceHop.Kind.OBSERVED,
                TraceHop.Source.TRANSFORMATION, cursor, direction);
        sql.append(cursorFilter.clause());
        sql.append(orderBy("t.timestamp_ms", "t.id", direction));

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
            idx = bindCursor(pstmt, idx, cursorFilter);
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
                            otherFp,
                            TraceHop.Source.TRANSFORMATION
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
