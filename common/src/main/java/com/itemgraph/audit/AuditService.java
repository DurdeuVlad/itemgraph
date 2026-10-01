package com.itemgraph.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Database integrity and conservation invariant audit engine (Phase 10).
 *
 * <p>Verifies all core ItemGraph invariants:
 * <ol>
 *   <li>Quantity conservation: allocations never exceed observation capacity and
 *       each active edge has valid, supported source/destination evidence whose
 *       allocation totals equal the edge quantity.</li>
 *   <li>Positivity: quantities on observations, edges, and allocations are strictly positive.</li>
 *   <li>Relational integrity: no orphaned allocations or missing endpoint nodes.</li>
 *   <li>Status consistency: correlation_status accurately reflects allocation state.</li>
 * </ol>
 */
public class AuditService {

    public AuditReport audit(Connection conn) throws SQLException {
        List<String> details = new ArrayList<>();

        long totalObs = count(conn, "SELECT COUNT(*) FROM ig_observations");
        long totalEdges = count(conn, "SELECT COUNT(*) FROM ig_inferred_edges WHERE edge_state = 'ACTIVE'");
        long totalAllocations = count(conn, "SELECT COUNT(*) FROM ig_edge_allocations a JOIN ig_inferred_edges e ON e.id = a.edge_id WHERE e.edge_state = 'ACTIVE'");
        long totalTransformations = count(conn, "SELECT COUNT(*) FROM ig_item_transformations");

        // 1. Conservation violation: sum of allocations exceeds observation capacity
        int overAllocated = 0;
        String overAllocSql = """
            SELECT o.id, o.amount, SUM(a.amount) AS allocated
            FROM ig_observations o
            JOIN ig_edge_allocations a ON a.observation_id = o.id
            JOIN ig_inferred_edges e ON e.id = a.edge_id AND e.edge_state = 'ACTIVE'
            GROUP BY o.id, o.amount
            HAVING SUM(a.amount) > o.amount
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(overAllocSql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                overAllocated++;
                details.add("Conservation violation on obs#" + rs.getLong("id") +
                        ": capacity=" + rs.getInt("amount") + ", total_allocated=" + rs.getInt("allocated"));
            }
        }

        // 2. Every active edge must account for its full quantity on both sides.
        // A graph may satisfy every per-observation capacity while an edge's own
        // amount disagrees with the SOURCE or DESTINATION allocation ledger.
        int invalidEdgeAllocations = 0;
        String edgeAllocationSql = """
            SELECT e.id, e.amount,
                   COALESCE(SUM(CASE WHEN a.allocation_role = 'SOURCE' THEN a.amount ELSE 0 END), 0) AS source_allocated,
                   COALESCE(SUM(CASE WHEN a.allocation_role = 'DESTINATION' THEN a.amount ELSE 0 END), 0) AS destination_allocated,
                   COALESCE(SUM(CASE WHEN a.edge_id IS NOT NULL AND (
                       a.allocation_role IS NULL OR a.allocation_role NOT IN ('SOURCE', 'DESTINATION')
                       OR o.id IS NULL OR ev.edge_id IS NULL
                       OR o.fingerprint_id IS NULL OR o.fingerprint_id != e.fingerprint_id
                       OR (a.allocation_role = 'SOURCE' AND (
                           o.action_type IS NULL OR o.action_type NOT IN ('DROP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM', 'DEATH_DROP')
                           OR o.node_id IS NULL OR o.node_id != e.from_node_id))
                       OR (a.allocation_role = 'DESTINATION' AND (
                           o.action_type IS NULL OR o.action_type != 'PICKUP_ITEM'
                           OR o.target_node_id IS NULL OR o.target_node_id != e.to_node_id))
                   ) THEN 1 ELSE 0 END), 0) AS invalid_allocations
            FROM ig_inferred_edges e
            LEFT JOIN ig_edge_allocations a ON a.edge_id = e.id
            LEFT JOIN ig_observations o ON o.id = a.observation_id
            LEFT JOIN ig_edge_evidence ev ON ev.edge_id = e.id AND ev.observation_id = a.observation_id
            WHERE e.edge_state = 'ACTIVE'
            GROUP BY e.id, e.amount
            HAVING source_allocated != e.amount OR destination_allocated != e.amount OR invalid_allocations != 0
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(edgeAllocationSql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                invalidEdgeAllocations++;
                details.add("Invalid edge allocations on edge#" + rs.getLong("id")
                        + ": amount=" + rs.getInt("amount")
                        + ", source_allocated=" + rs.getLong("source_allocated")
                        + ", destination_allocated=" + rs.getLong("destination_allocated")
                        + ", invalid_allocation_rows=" + rs.getLong("invalid_allocations"));
            }
        }

        // 3. Every active edge's time interval must exactly cite its allocated
        // source and destination evidence in forward temporal order.
        int invalidEdgeTemporal = 0;
        String edgeTemporalSql = """
            SELECT e.id, e.time_start, e.time_end,
                   COALESCE(SUM(CASE WHEN a.allocation_role = 'SOURCE' AND (
                       o.timestamp_ms IS NULL OR o.timestamp_ms != e.time_start OR o.timestamp_ms >= e.time_end
                   ) THEN 1 WHEN a.allocation_role = 'DESTINATION' AND (
                       o.timestamp_ms IS NULL OR o.timestamp_ms != e.time_end OR o.timestamp_ms <= e.time_start
                   ) THEN 1 ELSE 0 END), 0) AS invalid_temporal_rows
            FROM ig_inferred_edges e
            LEFT JOIN ig_edge_allocations a ON a.edge_id = e.id
            LEFT JOIN ig_observations o ON o.id = a.observation_id
            WHERE e.edge_state = 'ACTIVE'
            GROUP BY e.id, e.time_start, e.time_end
            HAVING e.time_start >= e.time_end OR invalid_temporal_rows != 0
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(edgeTemporalSql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                invalidEdgeTemporal++;
                details.add("Invalid temporal edge evidence on edge#" + rs.getLong("id")
                        + ": edge_start=" + rs.getLong("time_start")
                        + ", edge_end=" + rs.getLong("time_end")
                        + ", invalid_temporal_rows=" + rs.getLong("invalid_temporal_rows"));
            }
        }

        // 4. Non-positive quantities in observations, edges, or allocations
        int nonPositive = 0;
        nonPositive += (int) count(conn, "SELECT COUNT(*) FROM ig_observations WHERE amount <= 0");
        nonPositive += (int) count(conn, "SELECT COUNT(*) FROM ig_inferred_edges WHERE amount <= 0");
        nonPositive += (int) count(conn, "SELECT COUNT(*) FROM ig_edge_allocations WHERE amount <= 0");
        if (nonPositive > 0) {
            details.add("Found " + nonPositive + " records with amount <= 0");
        }

        // 5. Orphaned allocations
        int orphanedAllocations = 0;
        String orphanAllocSql = """
            SELECT COUNT(*) FROM ig_edge_allocations a
            WHERE NOT EXISTS (SELECT 1 FROM ig_observations o WHERE o.id = a.observation_id)
               OR NOT EXISTS (SELECT 1 FROM ig_inferred_edges e WHERE e.id = a.edge_id)
        """;
        orphanedAllocations = (int) count(conn, orphanAllocSql);
        if (orphanedAllocations > 0) {
            details.add("Found " + orphanedAllocations + " orphaned edge allocations (missing edge or observation)");
        }

        // 6. Invalid edge topology references
        int invalidEdgeNodes = 0;
        String invalidNodesSql = """
            SELECT COUNT(*) FROM ig_inferred_edges e
            WHERE e.edge_state = 'ACTIVE'
              AND (NOT EXISTS (SELECT 1 FROM ig_nodes n WHERE n.id = e.from_node_id)
                   OR NOT EXISTS (SELECT 1 FROM ig_nodes n WHERE n.id = e.to_node_id))
        """;
        invalidEdgeNodes = (int) count(conn, invalidNodesSql);
        if (invalidEdgeNodes > 0) {
            details.add("Found " + invalidEdgeNodes + " inferred edges with invalid endpoint nodes");
        }

        // 7. Correlation status consistency check
        int statusMismatches = 0;
        String statusMismatchSql = """
            SELECT COUNT(*) FROM ig_observations o
            WHERE (o.correlation_status = 'PENDING'
                   AND EXISTS (
                       SELECT 1 FROM ig_edge_allocations a
                       JOIN ig_inferred_edges e ON e.id = a.edge_id AND e.edge_state = 'ACTIVE'
                       WHERE a.observation_id = o.id
                   ))
               OR (o.correlation_status = 'CORROBORATING'
                   AND NOT EXISTS (
                       SELECT 1 FROM ig_observation_group_members m
                       JOIN ig_observation_groups g ON g.id = m.group_id
                       WHERE m.observation_id = o.id AND m.member_role = 'CORROBORATING' AND g.state = 'CONFIRMED'
                   ))
               OR (o.correlation_status = 'SOURCE_AMBIGUOUS'
                   AND NOT EXISTS (
                       SELECT 1 FROM ig_observation_group_members m
                       JOIN ig_observation_groups g ON g.id = m.group_id
                       WHERE m.observation_id = o.id AND m.member_role = 'CANDIDATE' AND g.state = 'AMBIGUOUS'
                   ))
               OR (o.correlation_status IN ('CORROBORATING', 'SOURCE_AMBIGUOUS')
                   AND EXISTS (
                       SELECT 1 FROM ig_edge_allocations a
                       JOIN ig_inferred_edges e ON e.id = a.edge_id AND e.edge_state = 'ACTIVE'
                       WHERE a.observation_id = o.id
                   ))
        """;
        statusMismatches = (int) count(conn, statusMismatchSql);
        int malformedGroups = (int) count(conn, """
            SELECT COUNT(*) FROM ig_observation_groups g
            LEFT JOIN (
                SELECT group_id,
                       SUM(CASE WHEN member_role = 'CANONICAL' THEN 1 ELSE 0 END) AS canonical_count,
                       SUM(CASE WHEN member_role = 'CANDIDATE' THEN 1 ELSE 0 END) AS candidate_count
                FROM ig_observation_group_members
                GROUP BY group_id
            ) members ON members.group_id = g.id
            WHERE (g.state = 'CONFIRMED' AND COALESCE(members.canonical_count, 0) != 1)
               OR (g.state = 'AMBIGUOUS' AND (COALESCE(members.canonical_count, 0) != 0 OR COALESCE(members.candidate_count, 0) < 2))
        """);
        statusMismatches += malformedGroups;
        int invalidEdgeStates = (int) count(conn, """
            SELECT COUNT(*) FROM ig_inferred_edges
            WHERE edge_state IS NULL
               OR edge_state NOT IN ('ACTIVE', 'SUPERSEDED_SOURCE_DUPLICATE', 'SUPERSEDED_SOURCE_AMBIGUITY',
                                     'SUPERSEDED_UNVERIFIED_EVIDENCE')
        """);
        statusMismatches += invalidEdgeStates;
        if (invalidEdgeStates > 0) {
            details.add("Found " + invalidEdgeStates + " inferred edges with an unknown edge_state");
        }
        if (statusMismatches > 0) {
            details.add("Found " + statusMismatches + " observations or source groups with inconsistent correlation state");
        }

        boolean healthy = (overAllocated == 0 && invalidEdgeAllocations == 0 && invalidEdgeTemporal == 0
                && nonPositive == 0 && orphanedAllocations == 0 &&
                           invalidEdgeNodes == 0 && statusMismatches == 0);

        return new AuditReport(
                healthy,
                totalObs,
                totalEdges,
                totalAllocations,
                totalTransformations,
                overAllocated,
                invalidEdgeAllocations,
                invalidEdgeTemporal,
                nonPositive,
                orphanedAllocations,
                invalidEdgeNodes,
                statusMismatches,
                details
        );
    }

    private long count(Connection conn, String sql) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0;
        }
    }
}
