package com.itemgraph.query;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The one SQL projection every observation-reading query uses.
 *
 * <p>{@code /ig event}, the evidence list under {@code /ig explain} and the OBSERVED rows
 * of {@code /ig trace item} must all describe an observation identically — an admin
 * comparing a trace line against the detail view has to see the same nodes, the same
 * item and the same numbers. Sharing the projection and the mapper is what guarantees
 * that, instead of three hand-written SELECTs drifting apart.
 *
 * <p>Nodes and the fingerprint are resolved by join rather than by a follow-up query per
 * row: an N+1 lookup inside a 100-row trace would be 300 extra round trips for output
 * that has to feel immediate.
 *
 * <p>The joins are LEFT joins even where the schema declares the foreign key NOT NULL.
 * A dangling reference should surface as "no such row" in the output rather than silently
 * dropping the observation from a forensic result set — a missing row in an evidence
 * listing is far more dangerous than an ugly one.
 */
final class ObservationQueries {

    private ObservationQueries() {}

    /** Columns + joins. Callers append their own WHERE/ORDER BY/LIMIT. */
    static final String SELECT_FROM = """
            SELECT o.id AS o_id,
                   o.source_type AS o_source_type,
                   o.source_event_id AS o_source_event_id,
                   o.timestamp_ms AS o_timestamp_ms,
                   o.action_type AS o_action_type,
                   o.amount AS o_amount,
                   o.correlated_at AS o_correlated_at,
                   o.correlation_status AS o_correlation_status,
                   o.item_entity_uuid AS o_item_entity_uuid,
                   o.node_id AS origin_id,
                   origin.node_type AS origin_type,
                   origin.custom_label AS origin_label,
                   origin.level_id AS origin_level,
                   origin.x AS origin_x, origin.y AS origin_y, origin.z AS origin_z,
                   o.target_node_id AS dest_id,
                   dest.node_type AS dest_type,
                   dest.custom_label AS dest_label,
                   dest.level_id AS dest_level,
                   dest.x AS dest_x, dest.y AS dest_y, dest.z AS dest_z,
                   o.fingerprint_id AS o_fingerprint_id,
                   f.item_id AS fp_item_id,
                   f.custom_name AS fp_custom_name,
                   f.fingerprint_hash AS fp_hash
            FROM ig_observations o
            LEFT JOIN ig_nodes origin ON origin.id = o.node_id
            LEFT JOIN ig_nodes dest ON dest.id = o.target_node_id
            LEFT JOIN ig_item_fingerprints f ON f.id = o.fingerprint_id
            """;

    static ObservationDetail map(ResultSet rs) throws SQLException {
        long originId = rs.getLong("origin_id");
        NodeRef origin = rs.wasNull() ? null : node(rs, originId, "origin");

        long destId = rs.getLong("dest_id");
        NodeRef destination = rs.wasNull() ? null : node(rs, destId, "dest");

        long fingerprintId = rs.getLong("o_fingerprint_id");
        String itemId = rs.getString("fp_item_id");
        FingerprintRef fingerprint = itemId == null
                ? FingerprintRef.missing(fingerprintId)
                : new FingerprintRef(fingerprintId, itemId, rs.getString("fp_custom_name"), rs.getString("fp_hash"));

        long sourceEventId = rs.getLong("o_source_event_id");
        Long sourceEvent = rs.wasNull() ? null : sourceEventId;

        long correlatedAt = rs.getLong("o_correlated_at");
        Long correlated = rs.wasNull() ? null : correlatedAt;

        String correlationStatus = rs.getString("o_correlation_status");
        String itemEntityUuid = rs.getString("o_item_entity_uuid");

        return new ObservationDetail(
                rs.getLong("o_id"),
                rs.getString("o_source_type"),
                sourceEvent,
                rs.getLong("o_timestamp_ms"),
                origin,
                destination,
                fingerprint,
                rs.getString("o_action_type"),
                rs.getInt("o_amount"),
                correlated,
                correlationStatus,
                itemEntityUuid
        );
    }

    /**
     * Builds a {@link NodeRef} from the {@code prefix_*} columns of the shared projection.
     * A non-null id whose joined row is absent yields {@link NodeRef#missing(long)}.
     */
    static NodeRef node(ResultSet rs, long id, String prefix) throws SQLException {
        String pfx = (prefix == null || prefix.isEmpty()) ? "" : prefix + "_";
        String type = rs.getString(pfx + (pfx.isEmpty() ? "node_type" : "type"));
        if (type == null) {
            return NodeRef.missing(id);
        }
        return new NodeRef(
                id,
                type,
                rs.getString(pfx + (pfx.isEmpty() ? "custom_label" : "label")),
                rs.getString(pfx + (pfx.isEmpty() ? "level_id" : "level")),
                nullableDouble(rs, pfx + "x"),
                nullableDouble(rs, pfx + "y"),
                nullableDouble(rs, pfx + "z")
        );
    }

    /**
     * {@code ResultSet.wasNull()} reports on the most recent read, so the value must be
     * consumed and tested before anything else is read from the row.
     */
    static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
