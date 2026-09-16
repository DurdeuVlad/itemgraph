package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Backs {@code /ig explain <edgeId>}: one inferred edge plus the raw observations it is
 * built on.
 *
 * <p>This is the literal implementation of the charter's forensic-integrity requirement.
 * An admin asking "why does ItemGraph think this transfer happened?" must get back the
 * exact supporting observations and scoring factors, so this service returns both the
 * stored {@code explanation} (the scoring narrative, written at inference time) and every
 * {@code ig_edge_evidence} row resolved to its underlying observation via the same
 * projection {@code /ig event} uses.
 *
 * <p>The explanation is read, never recomputed. Regenerating it here would mean the
 * displayed reasoning could drift from the confidence actually stored on the row — the
 * output would then describe an inference ItemGraph never made.
 *
 * <p>Reads only.
 */
public final class ExplainQueryService {

    private static final String EDGE_BY_ID = """
            SELECT e.id AS e_id,
                   e.amount AS e_amount,
                   e.time_start AS e_time_start,
                   e.time_end AS e_time_end,
                   e.confidence AS e_confidence,
                   e.explanation AS e_explanation,
                   e.created_at AS e_created_at,
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
                   e.fingerprint_id AS e_fingerprint_id,
                   f.item_id AS fp_item_id,
                   f.custom_name AS fp_custom_name,
                   f.fingerprint_hash AS fp_hash
            FROM ig_inferred_edges e
            LEFT JOIN ig_nodes origin ON origin.id = e.from_node_id
            LEFT JOIN ig_nodes dest ON dest.id = e.to_node_id
            LEFT JOIN ig_item_fingerprints f ON f.id = e.fingerprint_id
            WHERE e.id = ?
            """;

    /**
     * Evidence rows for one edge, chronological so the listing reads as the sequence of
     * events the inference was drawn from. One row over the cap is fetched so the caller
     * can honestly report truncation instead of silently omitting evidence.
     */
    private static final String EVIDENCE_FOR_EDGE = ObservationQueries.SELECT_FROM + """
             JOIN ig_edge_evidence ev ON ev.observation_id = o.id
            WHERE ev.edge_id = ?
            ORDER BY o.timestamp_ms ASC, o.id ASC
            LIMIT ?
            """;

    public Optional<EdgeExplanation> findEdge(Connection conn, long edgeId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(EDGE_BY_ID)) {
            pstmt.setLong(1, edgeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                long fromId = rs.getLong("origin_id");
                NodeRef from = rs.wasNull() ? null : ObservationQueries.node(rs, fromId, "origin");

                long toId = rs.getLong("dest_id");
                NodeRef to = rs.wasNull() ? null : ObservationQueries.node(rs, toId, "dest");

                long fingerprintId = rs.getLong("e_fingerprint_id");
                String itemId = rs.getString("fp_item_id");
                FingerprintRef fingerprint = itemId == null
                        ? FingerprintRef.missing(fingerprintId)
                        : new FingerprintRef(fingerprintId, itemId, rs.getString("fp_custom_name"), rs.getString("fp_hash"));

                List<ObservationDetail> evidence = loadEvidence(conn, edgeId);
                boolean truncated = evidence.size() > QueryLimits.MAX_EVIDENCE_ROWS;
                if (truncated) {
                    evidence = evidence.subList(0, QueryLimits.MAX_EVIDENCE_ROWS);
                }

                return Optional.of(new EdgeExplanation(
                        rs.getLong("e_id"),
                        from,
                        to,
                        fingerprint,
                        rs.getInt("e_amount"),
                        rs.getLong("e_time_start"),
                        rs.getLong("e_time_end"),
                        rs.getDouble("e_confidence"),
                        rs.getString("e_explanation"),
                        rs.getLong("e_created_at"),
                        evidence,
                        truncated
                ));
            }
        }
    }

    private List<ObservationDetail> loadEvidence(Connection conn, long edgeId) throws SQLException {
        List<ObservationDetail> evidence = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(EVIDENCE_FOR_EDGE)) {
            pstmt.setLong(1, edgeId);
            pstmt.setInt(2, QueryLimits.MAX_EVIDENCE_ROWS + 1);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    evidence.add(ObservationQueries.map(rs));
                }
            }
        }
        return evidence;
    }
}
