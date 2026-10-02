package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    private static final int MAX_CANDIDATE_IDS_PER_GROUP = 50;

    private ObservationQueries() {}

    /** Columns + joins. Callers append their own WHERE/ORDER BY/LIMIT. */
    static final String SELECT_FROM = """
            SELECT o.id AS o_id,
                   o.source_type AS o_source_type,
                   o.source_event_id AS o_source_event_id,
                   o.timestamp_ms AS o_timestamp_ms,
                   o.timestamp_end_ms AS o_timestamp_end_ms,
                   CASE
                       WHEN o.source_type = 'ITEMGRAPH_INTERNAL'
                         AND substr(CAST(o.raw_data AS TEXT), 1, 128) LIKE '%\"capture\":\"container_session_net_delta\"%'
                           THEN 'container_session_net_delta'
                       WHEN o.source_type = 'ITEMGRAPH_INTERNAL'
                         AND substr(CAST(o.raw_data AS TEXT), 1, 128) LIKE '%\"capture\":\"queue_overflow_recovery\"%'
                           THEN 'queue_overflow_recovery'
                       ELSE NULL
                   END AS o_capture_type,
                   o.action_type AS o_action_type,
                   o.amount AS o_amount,
                   o.correlated_at AS o_correlated_at,
                   o.correlation_status AS o_correlation_status,
                   o.item_entity_uuid AS o_item_entity_uuid,
                   ogm.group_id AS o_source_group_id,
                   og.state AS o_source_group_state,
                   ogm.member_role AS o_source_group_role,
                   canonical_member.observation_id AS o_canonical_observation_id,
                   og.match_basis AS o_source_match_basis,
                   og.explanation AS o_source_group_explanation,
                   disposition.reason_code AS o_disposition_reason,
                   o.node_id AS origin_id,
                   origin.node_type AS origin_type,
                   origin.custom_label AS origin_label,
                   origin.level_id AS origin_level,
                   origin.x AS origin_x, origin.y AS origin_y, origin.z AS origin_z,
                   origin.owner_uuid AS origin_owner_uuid,
                   origin.external_key AS origin_external_key,
                   o.target_node_id AS dest_id,
                   dest.node_type AS dest_type,
                   dest.custom_label AS dest_label,
                   dest.level_id AS dest_level,
                   dest.x AS dest_x, dest.y AS dest_y, dest.z AS dest_z,
                   dest.owner_uuid AS dest_owner_uuid,
                   dest.external_key AS dest_external_key,
                   o.fingerprint_id AS o_fingerprint_id,
                   f.id AS fp_row_id,
                   f.item_id AS fp_item_id,
                   f.custom_name AS fp_custom_name,
                   f.fingerprint_hash AS fp_hash,
                   f.component_summary AS fp_component_summary
            FROM ig_observations o
            LEFT JOIN ig_nodes origin ON origin.id = o.node_id
            LEFT JOIN ig_nodes dest ON dest.id = o.target_node_id
            LEFT JOIN ig_item_fingerprints f ON f.id = o.fingerprint_id
            LEFT JOIN ig_observation_group_members ogm ON ogm.observation_id = o.id
            LEFT JOIN ig_observation_groups og ON og.id = ogm.group_id
            LEFT JOIN ig_observation_group_members canonical_member
                ON canonical_member.group_id = ogm.group_id AND canonical_member.member_role = 'CANONICAL'
            LEFT JOIN ig_observation_dispositions disposition ON disposition.observation_id = o.id
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
                : new FingerprintRef(fingerprintId, itemId, rs.getString("fp_custom_name"), rs.getString("fp_hash"),
                    rs.getString("fp_component_summary") != null
                            && rs.getString("fp_component_summary").contains("component_decode=UNRESOLVED"));

        long sourceEventId = rs.getLong("o_source_event_id");
        Long sourceEvent = rs.wasNull() ? null : sourceEventId;

        long correlatedAt = rs.getLong("o_correlated_at");
        Long correlated = rs.wasNull() ? null : correlatedAt;

        String correlationStatus = rs.getString("o_correlation_status");
        String itemEntityUuid = rs.getString("o_item_entity_uuid");
        long rawEndMs = rs.getLong("o_timestamp_end_ms");
        Long timestampEndMs = rs.wasNull() ? null : rawEndMs;
        String captureType = rs.getString("o_capture_type");
        long rawGroupId = rs.getLong("o_source_group_id");
        Long groupId = rs.wasNull() ? null : rawGroupId;
        ObservationDetail.SourceGroup sourceGroup = null;
        if (groupId != null) {
            long rawCanonicalId = rs.getLong("o_canonical_observation_id");
            Long canonicalId = rs.wasNull() ? null : rawCanonicalId;
            sourceGroup = new ObservationDetail.SourceGroup(
                    groupId,
                    rs.getString("o_source_group_state"),
                    rs.getString("o_source_group_role"),
                    canonicalId,
                    rs.getString("o_source_match_basis"),
                    rs.getString("o_source_group_explanation"));
        }

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
                itemEntityUuid,
                timestampEndMs,
                captureType,
                sourceGroup,
                rs.getString("o_disposition_reason")
        );
    }

    /**
     * Loads a bounded, deterministic candidate prefix for each ambiguous group in a result
     * page. The extra row marks truncation; candidates remain evidence references and are
     * never quantity allocations.
     */
    static List<ObservationDetail> withAmbiguousCandidates(Connection conn,
                                                            List<ObservationDetail> observations)
            throws SQLException {
        LinkedHashSet<Long> groupIds = new LinkedHashSet<>();
        for (ObservationDetail observation : observations) {
            ObservationDetail.SourceGroup group = observation.sourceGroup();
            if (group != null && "AMBIGUOUS".equals(group.state())) {
                groupIds.add(group.id());
            }
        }
        Map<Long, CandidateSet> candidatesByGroup = candidateEvidenceForGroups(conn, groupIds);
        return observations.stream().map(observation -> {
            ObservationDetail.SourceGroup group = observation.sourceGroup();
            if (group == null || !"AMBIGUOUS".equals(group.state())) {
                return observation;
            }
            CandidateSet candidates = candidatesByGroup.get(group.id());
            return candidates == null ? observation
                    : observation.withCandidateEvidenceIds(candidates.ids(), candidates.truncated());
        }).toList();
    }

    static Map<Long, CandidateSet> candidateEvidenceForGroups(Connection conn, Collection<Long> groupIds)
            throws SQLException {
        Map<Long, CandidateSet> candidatesByGroup = new LinkedHashMap<>();
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT observation_id FROM ig_observation_group_members
                WHERE group_id = ? AND member_role = 'CANDIDATE'
                ORDER BY observation_id ASC LIMIT ?
                """)) {
            for (long groupId : groupIds) {
                statement.setLong(1, groupId);
                statement.setInt(2, MAX_CANDIDATE_IDS_PER_GROUP + 1);
                List<String> candidates = new ArrayList<>();
                boolean truncated = false;
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        if (candidates.size() == MAX_CANDIDATE_IDS_PER_GROUP) {
                            truncated = true;
                            break;
                        }
                        long observationId = result.getLong(1);
                        candidates.add("observation#" + observationId);
                    }
                }
                candidatesByGroup.put(groupId, new CandidateSet(candidates, truncated));
            }
        }
        return candidatesByGroup;
    }

    record CandidateSet(List<String> ids, boolean truncated) {
        CandidateSet {
            ids = List.copyOf(ids);
        }
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
                nullableDouble(rs, pfx + "z"),
                rs.getString(pfx + "owner_uuid"),
                rs.getString(pfx + "external_key")
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
