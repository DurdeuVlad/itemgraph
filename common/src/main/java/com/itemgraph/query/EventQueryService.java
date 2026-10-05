package com.itemgraph.query;

import com.itemgraph.audit.EventTaxonomy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Backs {@code /ig event <observationId>}: one raw observation, fully resolved.
 *
 * <p>Reads only. This service exists so the Brigadier handler in
 * {@code ItemGraphCommands} is a thin wrapper with no SQL in it — the query logic is
 * then testable against a real SQLite database without a running Minecraft server, and
 * the command layer is left with nothing to get wrong except argument parsing.
 *
 * <p>A missing id is an {@link Optional#empty()} result, not an exception: "there is no
 * observation 42" is a legitimate answer to a forensic question, and the caller renders
 * it as a clear message rather than a stack trace.
 */
public final class EventQueryService {

    private static final String BY_ID = ObservationQueries.SELECT_FROM + " WHERE o.id = ?";
    private static final String BY_EVENT_UUID = ObservationQueries.SELECT_FROM + " WHERE o.ingest_event_uuid = ?";
    private static final String TRANSFORMATION_BY_EVENT_UUID = """
            SELECT t.id, t.transformation_type, t.quantity, t.timestamp_ms, t.details,
                   t.ingest_event_uuid, p.level_id, p.x, p.y, p.z,
                   p.custom_label AS player_name, p.owner_uuid AS player_uuid,
                   source_fp.item_id AS source_item, result_fp.item_id AS result_item
            FROM ig_item_transformations t
            LEFT JOIN ig_nodes p ON p.id = t.player_node_id
            LEFT JOIN ig_item_fingerprints source_fp ON source_fp.id = t.source_fingerprint_id
            LEFT JOIN ig_item_fingerprints result_fp ON result_fp.id = t.result_fingerprint_id
            WHERE t.ingest_event_uuid = ?
            """;
    private static final String AUDIT_BY_EVENT_UUID = """
            SELECT a.id, a.event_type, a.timestamp_ms, a.player_uuid, a.player_name,
                   a.level_id, a.x, a.y, a.z, a.subject_id, a.detail, a.raw_data
            FROM ig_audit_events a
            WHERE a.ingest_event_uuid = ? AND a.event_type IN (
                'ADMIN_ITEM_COMMAND_ATTEMPT', 'ADMIN_ITEM_COMMAND_EFFECT',
                'ADMIN_ITEM_COMMAND_FAILURE', 'ADMIN_ITEM_COMMAND_UNRESOLVED',
                'CREATIVE_SLOT_ATTEMPT', 'CREATIVE_SLOT_EFFECT',
                'CREATIVE_BLOCK_ATTEMPT', 'CREATIVE_BLOCK_RESULT', 'CREATIVE_BLOCK_UNRESOLVED'
            )
            """;

    public Optional<ObservationDetail> findObservation(Connection conn, long observationId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(BY_ID)) {
            pstmt.setLong(1, observationId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(ObservationQueries.map(rs));
            }
        }
    }

    public Optional<ObservationDetail> findObservationByEventUuid(Connection conn, String eventUuid) throws SQLException {
        if (!AdminMutationEvidenceLinks.isCanonicalUuid(eventUuid)) return Optional.empty();
        try (PreparedStatement pstmt = conn.prepareStatement(BY_EVENT_UUID)) {
            pstmt.setString(1, eventUuid.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() ? Optional.of(ObservationQueries.map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<UnifiedEvidenceDetail> findTransformationByEventUuid(Connection conn, String eventUuid)
            throws SQLException {
        if (!AdminMutationEvidenceLinks.isCanonicalUuid(eventUuid)) return Optional.empty();
        try (PreparedStatement pstmt = conn.prepareStatement(TRANSFORMATION_BY_EVENT_UUID)) {
            pstmt.setString(1, eventUuid.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String action = rs.getString("transformation_type");
                var definition = EventTaxonomy.find(action, EventTaxonomy.Surface.TRANSFORMATION).orElse(null);
                boolean unresolved = !EventTaxonomy.isTraceableTransformation(action);
                String detail = "evidence_event_id=" + eventUuid + " "
                        + (rs.getString("details") == null ? "" : rs.getString("details") + " ")
                        + "source=" + rs.getString("source_item") + " result=" + rs.getString("result_item");
                if (unresolved) detail += " evidence=UNRESOLVED quantity=UNKNOWN";
                return Optional.of(new UnifiedEvidenceDetail(
                        "TRANSFORMATION", "transformation#" + rs.getLong("id"),
                        rs.getLong("timestamp_ms"), rs.getString("level_id"),
                        nullableDouble(rs, "x"), nullableDouble(rs, "y"), nullableDouble(rs, "z"),
                        rs.getString("player_name") == null ? rs.getString("player_uuid") : rs.getString("player_name"),
                        action, unresolved ? null : rs.getInt("quantity"),
                        rs.getString("source_item") + " -> " + rs.getString("result_item"),
                        detail, unresolved || definition == null ? EventTaxonomy.EvidenceClass.UNRESOLVED.name()
                                : definition.evidenceClass().name()));
            }
        }
    }

    public Optional<UnifiedEvidenceDetail> findAuditEventByEventUuid(Connection conn, String eventUuid)
            throws SQLException {
        if (!AdminMutationEvidenceLinks.isCanonicalUuid(eventUuid)) return Optional.empty();
        try (PreparedStatement pstmt = conn.prepareStatement(AUDIT_BY_EVENT_UUID)) {
            pstmt.setString(1, eventUuid.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String action = rs.getString("event_type");
                String detail = AdminMutationEvidenceLinks.appendAuditDetail(action,
                        rs.getString("detail"), rs.getBytes("raw_data"));
                var definition = EventTaxonomy.find(action, EventTaxonomy.Surface.AUDIT_EVENT).orElse(null);
                return Optional.of(new UnifiedEvidenceDetail(
                        "AUDIT", "audit#" + rs.getLong("id"), rs.getLong("timestamp_ms"),
                        rs.getString("level_id"), nullableDouble(rs, "x"), nullableDouble(rs, "y"),
                        nullableDouble(rs, "z"), rs.getString("player_name") == null
                                ? rs.getString("player_uuid") : rs.getString("player_name"),
                        action, 0, rs.getString("subject_id"), detail,
                        definition == null ? EventTaxonomy.EvidenceClass.UNRESOLVED.name()
                                : definition.evidenceClass().name()));
            }
        }
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
