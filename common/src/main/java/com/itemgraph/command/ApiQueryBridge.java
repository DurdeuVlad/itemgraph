package com.itemgraph.command;

import com.itemgraph.api.ContainerQuery;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemQuery;
import com.itemgraph.api.PlayerQuery;
import com.itemgraph.api.QueryOptions;
import com.itemgraph.query.EdgeExplanation;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.ItemMetadataSql;
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
            String normalizedPredicate,
            boolean metadataMatchUnconfirmed,
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
            String normalizedPredicate = query.normalizedPredicate() + " " + options.normalizedTimePredicate();
            boolean metadataMatchUnconfirmed = candidates.stream().anyMatch(FingerprintRef::componentIndexUnresolved);
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, candidates, List.of(), null, window, options.limit(),
                        normalizedPredicate, false, Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, candidates, List.of(), null, window, options.limit(),
                        normalizedPredicate, metadataMatchUnconfirmed, Map.of());
            }
            TraceResult result = new TraceQueryService().trace(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    normalizedPredicate, metadataMatchUnconfirmed, explanations(conn, result));
        });
    }

    public static CompletableFuture<ApiTraceResponse> tracePlayer(PlayerQuery query, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            String normalizedPredicate = "player." + query.playerUuid() + " " + options.normalizedTimePredicate();
            List<NodeRef> candidates = resolvePlayerUuidNodes(conn, query.playerUuid().toString());
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, List.of(), candidates, null, window, options.limit(),
                        normalizedPredicate, Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, List.of(), candidates, null, window, options.limit(),
                        normalizedPredicate, Map.of());
            }
            TraceResult result = new TraceQueryService().tracePlayerNode(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    normalizedPredicate, explanations(conn, result));
        });
    }

    public static CompletableFuture<ApiTraceResponse> traceContainer(ContainerQuery query, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            String level = query.level().location().toString();
            String normalizedPredicate = "container." + level + ":" + query.position().getX() + ","
                    + query.position().getY() + "," + query.position().getZ() + " "
                    + options.normalizedTimePredicate();
            List<NodeRef> candidates = new TraceQueryService().resolveContainerNodes(
                    conn, level, query.position().getX(), query.position().getY(), query.position().getZ());
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, List.of(), candidates, null, window, options.limit(),
                        normalizedPredicate, Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, List.of(), candidates, null, window, options.limit(),
                        normalizedPredicate, Map.of());
            }
            TraceResult result = new TraceQueryService().traceContainerNode(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    normalizedPredicate, explanations(conn, result));
        });
    }

    public static CompletableFuture<ApiTraceResponse> traceExternalInventory(
            ExternalInventoryEndpoint inventory, QueryOptions options) {
        return submit(conn -> {
            QueryWindow window = window(options);
            String externalKey = inventory.ownerModId() + "/" + inventory.inventoryId();
            String normalizedPredicate = "external." + externalKey + " " + options.normalizedTimePredicate();
            List<NodeRef> candidates = resolveExternalInventoryNodes(conn, externalKey);
            if (candidates.isEmpty()) {
                return response(Resolution.NOT_FOUND, List.of(), candidates, null, window, options.limit(),
                        normalizedPredicate, Map.of());
            }
            if (candidates.size() > 1) {
                return response(Resolution.AMBIGUOUS, List.of(), candidates, null, window, options.limit(),
                        normalizedPredicate, Map.of());
            }
            TraceResult result = new TraceQueryService().traceNodeId(
                    conn, candidates.get(0).id(), options.limit(), window);
            return response(Resolution.RESOLVED, List.of(), List.of(), result, window, options.limit(),
                    normalizedPredicate, explanations(conn, result));
        });
    }

    private static CompletableFuture<ApiTraceResponse> submit(
            QueryDispatcher.DataQuery<ApiTraceResponse> query) {
        return QueryDispatcher.submitData(query);
    }

    private static QueryWindow window(QueryOptions options) {
        if (options.absoluteWindow() != null) {
            return new QueryWindow(options.absoluteWindow().sinceMs(), options.absoluteWindow().untilMs());
        }
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
                window, requestedLimit, window.normalizedPredicate(), false, explanations);
    }

    private static ApiTraceResponse response(Resolution resolution,
                                             List<FingerprintRef> itemCandidates,
                                             List<NodeRef> endpointCandidates,
                                             TraceResult result,
                                             QueryWindow window,
                                             int requestedLimit,
                                             String normalizedPredicate,
                                             Map<Long, EdgeExplanation> explanations) {
        return response(resolution, itemCandidates, endpointCandidates, result, window,
                requestedLimit, normalizedPredicate, false, explanations);
    }

    private static ApiTraceResponse response(Resolution resolution,
                                             List<FingerprintRef> itemCandidates,
                                             List<NodeRef> endpointCandidates,
                                             TraceResult result,
                                             QueryWindow window,
                                             int requestedLimit,
                                             String normalizedPredicate,
                                             boolean metadataMatchUnconfirmed,
                                             Map<Long, EdgeExplanation> explanations) {
        return new ApiTraceResponse(resolution, itemCandidates, endpointCandidates, result,
                window, requestedLimit, normalizedPredicate, metadataMatchUnconfirmed, explanations);
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

    static List<FingerprintRef> resolveFingerprints(Connection conn, ItemQuery query)
            throws SQLException {
        String column;
        String value;
        boolean fuzzyName = false;
        if (query.itemId() != null) {
            column = "f.item_id = ?";
            value = query.itemId();
        } else if (query.customName() != null) {
            column = "(f.custom_name = ? OR f.custom_name LIKE ?)";
            value = query.customName();
            fuzzyName = true;
        } else {
            column = "f.fingerprint_hash = ?";
            value = query.fingerprintHash();
        }

        boolean componentPredicate = query.metadataPredicates().stream().anyMatch(predicate ->
                predicate.kind() != com.itemgraph.query.ItemMetadataPredicate.Kind.ITEM_ID
                        && predicate.kind() != com.itemgraph.query.ItemMetadataPredicate.Kind.FINGERPRINT);
        StringBuilder sql = new StringBuilder("SELECT f.id, f.item_id, f.custom_name, f.fingerprint_hash, "
                + "f.component_index_state FROM ig_item_fingerprints f WHERE " + column);
        List<Object> args = new ArrayList<>();
        args.add(value);
        if (fuzzyName) {
            args.add("%" + value + "%");
        }
        ItemMetadataSql.append(sql, args, query.metadataPredicates(), "f.id", "f.item_id", "f.fingerprint_hash");
        sql.append(componentPredicate
                ? " ORDER BY CASE WHEN f.component_index_state = 'COMPLETE' THEN 0 ELSE 1 END, f.id ASC LIMIT 10"
                : " ORDER BY f.id ASC LIMIT 10");
        List<FingerprintRef> candidates = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int index = 0; index < args.size(); index++) {
                pstmt.setString(index + 1, (String) args.get(index));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new FingerprintRef(
                            rs.getLong("id"),
                            rs.getString("item_id"),
                            rs.getString("custom_name"),
                            rs.getString("fingerprint_hash"), false,
                            componentPredicate && !"COMPLETE".equals(rs.getString("component_index_state"))));
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
