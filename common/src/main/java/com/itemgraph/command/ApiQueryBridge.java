package com.itemgraph.command;

import com.itemgraph.api.ContainerQuery;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemQuery;
import com.itemgraph.api.PlayerQuery;
import com.itemgraph.api.QueryOptions;
import com.itemgraph.query.EdgeExplanation;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.NodeRef;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceHop;
import com.itemgraph.query.TraceQueryService;
import com.itemgraph.query.TraceResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Internal bridge from the public preview API to the shared bounded query worker.
 * Its public surface accepts domain values and returns domain values only; JDBC stays
 * inside {@link QueryDispatcher}'s package-private {@code DataQuery} boundary.
 */
public final class ApiQueryBridge {

    public enum Resolution {
        RESOLVED,
        NOT_FOUND,
        AMBIGUOUS
    }

    public record ApiTraceResponse(
            Resolution resolution,
            List<FingerprintRef> itemCandidates,
            List<NodeRef> endpointCandidates,
            TraceResult result,
            QueryWindow window,
            int requestedLimit,
            Map<Long, EdgeExplanation> explanations) {

        public ApiTraceResponse {
            itemCandidates = List.copyOf(itemCandidates);
            endpointCandidates = List.copyOf(endpointCandidates);
            explanations = Map.copyOf(explanations);
        }
    }

    private ApiQueryBridge() {}

    public static CompletableFuture<ApiTraceResponse> traceItem(ItemQuery query, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            List<FingerprintRef> candidates = resolveFingerprints(conn, query);
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, candidates, List.of(), null, window, options.limit(), Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, candidates, List.of(), null, window, options.limit(), Map.of());
            }
            TraceResult result = new TraceQueryService().trace(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    explanations(conn, result));
        });
    }

    public static CompletableFuture<ApiTraceResponse> tracePlayer(PlayerQuery query, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            List<NodeRef> candidates = resolvePlayerUuidNodes(conn, query.playerUuid().toString());
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, List.of(), candidates, null, window, options.limit(), Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, List.of(), candidates, null, window, options.limit(), Map.of());
            }
            TraceResult result = new TraceQueryService().tracePlayerNode(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    explanations(conn, result));
        });
    }

    public static CompletableFuture<ApiTraceResponse> traceContainer(ContainerQuery query, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            String level = query.level().location().toString();
            List<NodeRef> candidates = new TraceQueryService().resolveContainerNodes(
                    conn, level, query.position().getX(), query.position().getY(), query.position().getZ());
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, List.of(), candidates, null, window, options.limit(), Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, List.of(), candidates, null, window, options.limit(), Map.of());
            }
            TraceResult result = new TraceQueryService().traceContainerNode(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    explanations(conn, result));
        });
    }

    public static CompletableFuture<ApiTraceResponse> traceExternalInventory(
            ExternalInventoryEndpoint inventory, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            String externalKey = inventory.ownerModId() + "/" + inventory.inventoryId();
            List<NodeRef> candidates = resolveExternalInventoryNodes(conn, externalKey);
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, List.of(), candidates, null, window, options.limit(), Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, List.of(), candidates, null, window, options.limit(), Map.of());
            }
            TraceResult result = new TraceQueryService().traceNodeId(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    explanations(conn, result));
        });
    }

    private static CompletableFuture<ApiTraceResponse> submit(
            QueryDispatcher.DataQuery<ApiTraceResponse> query) {
        return QueryDispatcher.submitData(query);
    }

    private static QueryWindow window(QueryOptions options) {
        if (options.sinceMinutes() == null) {
            return QueryWindow.unbounded();
        }
        return QueryWindow.lastMinutes(options.sinceMinutes(), System.currentTimeMillis());
    }

    private static ApiTraceResponse response(Resolution resolution,
                                             List<FingerprintRef> itemCandidates,
                                             List<NodeRef> endpointCandidates,
                                             TraceResult result,
                                             QueryWindow window,
                                             int requestedLimit,
                                             Map<Long, EdgeExplanation> explanations) {
        return new ApiTraceResponse(resolution, itemCandidates, endpointCandidates, result,
                window, requestedLimit, explanations);
    }

    private static Map<Long, EdgeExplanation> explanations(Connection conn, TraceResult result)
            throws SQLException {
        Map<Long, EdgeExplanation> explanations = new HashMap<>();
        if (result == null) {
            return explanations;
        }
        ExplainQueryService explain = new ExplainQueryService();
        for (TraceHop hop : result.hops()) {
            if (hop.kind() == TraceHop.Kind.INFERRED) {
                explain.findEdge(conn, hop.refId()).ifPresent(edge -> explanations.put(edge.id(), edge));
            }
        }
        return explanations;
    }

    private static List<FingerprintRef> resolveFingerprints(Connection conn, ItemQuery query)
            throws SQLException {
        String column;
        String value;
        boolean fuzzyName = false;
        if (query.itemId() != null) {
            column = "item_id = ?";
            value = query.itemId();
        } else if (query.customName() != null) {
            column = "(custom_name = ? OR custom_name LIKE ?)";
            value = query.customName();
            fuzzyName = true;
        } else {
            column = "fingerprint_hash = ?";
            value = query.fingerprintHash();
        }

        String sql = "SELECT id, item_id, custom_name, fingerprint_hash FROM ig_item_fingerprints WHERE "
                + column + " ORDER BY id ASC LIMIT 10";
        List<FingerprintRef> candidates = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, value);
            if (fuzzyName) {
                pstmt.setString(2, "%" + value + "%");
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new FingerprintRef(
                            rs.getLong("id"),
                            rs.getString("item_id"),
                            rs.getString("custom_name"),
                            rs.getString("fingerprint_hash")));
                }
            }
        }
        return candidates;
    }

    private static List<NodeRef> resolvePlayerUuidNodes(Connection conn, String playerUuid)
            throws SQLException {
        List<NodeRef> candidates = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, node_type, custom_label, level_id, x, y, z, owner_uuid, external_key "
                        + "FROM ig_nodes WHERE node_type = 'PLAYER' AND owner_uuid = ? "
                        + "ORDER BY id ASC LIMIT 10")) {
            pstmt.setString(1, playerUuid);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(node(rs));
                }
            }
        }
        return candidates;
    }

    private static List<NodeRef> resolveExternalInventoryNodes(Connection conn, String externalKey)
            throws SQLException {
        List<NodeRef> candidates = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(
                "SELECT id, node_type, custom_label, level_id, x, y, z, owner_uuid, external_key "
                        + "FROM ig_nodes WHERE node_type = 'EXTERNAL_INVENTORY' AND external_key = ? "
                        + "ORDER BY id ASC LIMIT 10")) {
            pstmt.setString(1, externalKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(node(rs));
                }
            }
        }
        return candidates;
    }

    private static NodeRef node(ResultSet rs) throws SQLException {
        return new NodeRef(
                rs.getLong("id"),
                rs.getString("node_type"),
                rs.getString("custom_label"),
                rs.getString("level_id"),
                nullableDouble(rs, "x"),
                nullableDouble(rs, "y"),
                nullableDouble(rs, "z"),
                rs.getString("owner_uuid"),
                rs.getString("external_key"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
