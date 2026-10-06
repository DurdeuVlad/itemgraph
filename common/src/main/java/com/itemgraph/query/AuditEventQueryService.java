package com.itemgraph.query;

import com.itemgraph.audit.EventTaxonomy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only queries for native GriefLogger-parity audit events. */
public final class AuditEventQueryService {
    public static final double MAX_RADIUS_BLOCKS = 1_024.0;
    public record ExactPosition(double x, double y, double z) {}
    public static final List<String> EVENT_TYPES = java.util.stream.Stream
            .concat(java.util.stream.Stream.of("all"), EventTaxonomy.auditLookupTypes().stream())
            .toList();

    public List<AuditEventDetail> find(Connection conn, String eventType, String playerName,
                                       QueryWindow window, int requestedLimit) throws SQLException {
        return find(conn, eventType, playerName, window, null,
                null, null, null, null, requestedLimit);
    }

    public List<AuditEventDetail> find(Connection conn, String eventType, String playerName,
                                       QueryWindow window, String levelId,
                                       Double centerX, Double centerY, Double centerZ,
                                       Double requestedRadius, int requestedLimit) throws SQLException {
        return find(conn, eventType, playerName, window, levelId,
                centerX, centerY, centerZ, requestedRadius, requestedLimit, 0);
    }

    public List<AuditEventDetail> find(Connection conn, String eventType, String playerName,
                                       QueryWindow window, String levelId,
                                       Double centerX, Double centerY, Double centerZ,
                                       Double requestedRadius, int requestedLimit,
                                       int requestedOffset) throws SQLException {
        int limit = QueryLimits.clampLimit(requestedLimit);
        int offset = QueryLimits.clampOffset(requestedOffset);
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.event_type, a.timestamp_ms, a.player_uuid, a.player_name,
                       a.level_id, a.x, a.y, a.z, a.subject_id, a.detail,
                       s.superseding_event_id, s.reason_code
                FROM ig_audit_events a
                LEFT JOIN ig_audit_event_supersessions s ON s.superseded_event_id = a.id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (eventType != null && !eventType.isBlank() && !"all".equalsIgnoreCase(eventType)) {
            sql.append(" AND a.event_type = ?");
            args.add(eventType.toUpperCase(java.util.Locale.ROOT));
        }
        if (playerName != null && !playerName.isBlank() && !"*".equals(playerName)) {
            String normalizedUser = playerName.toLowerCase(Locale.ROOT);
            sql.append(" AND (LOWER(a.player_name) = ? OR LOWER(a.player_uuid) = ? ")
                    .append("OR LOWER(a.player_uuid) IN (")
                    .append("SELECT LOWER(player_uuid) FROM ig_player_name_history WHERE normalized_name = ?))");
            args.add(normalizedUser);
            args.add(normalizedUser);
            args.add(normalizedUser);
        }
        if (window != null && window.sinceMs() != null) {
            sql.append(" AND a.timestamp_ms >= ?");
            args.add(window.sinceMs());
        }
        if (window != null && window.untilMs() != null) {
            sql.append(" AND a.timestamp_ms <= ?");
            args.add(window.untilMs());
        }
        if (levelId != null && !levelId.isBlank()) {
            sql.append(" AND a.level_id = ?");
            args.add(levelId);
        }
        if (centerX != null && centerY != null && centerZ != null && requestedRadius != null) {
            if (requestedRadius == 0.0) {
                // The inspector needs the clicked block's history, not a nearby-radius
                // approximation that can include adjacent blocks. This sentinel is only
                // used by the server-side inspector; public near lookups still clamp to
                // the documented minimum radius.
                sql.append(" AND a.x = ? AND a.y = ? AND a.z = ?");
                args.add(centerX);
                args.add(centerY);
                args.add(centerZ);
            } else {
                double radius = Math.max(1.0, Math.min(MAX_RADIUS_BLOCKS, requestedRadius));
                sql.append(" AND ((a.x - ?) * (a.x - ?) + (a.y - ?) * (a.y - ?) + (a.z - ?) * (a.z - ?)) <= ?");
                args.add(centerX);
                args.add(centerX);
                args.add(centerY);
                args.add(centerY);
                args.add(centerZ);
                args.add(centerZ);
                args.add(radius * radius);
            }
        }
        sql.append(" ORDER BY a.timestamp_ms DESC, a.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                Object arg = args.get(i);
                if (arg instanceof Long value) {
                    pstmt.setLong(i + 1, value);
                } else if (arg instanceof Integer value) {
                    pstmt.setInt(i + 1, value);
                } else if (arg instanceof Double value) {
                    pstmt.setDouble(i + 1, value);
                } else {
                    pstmt.setString(i + 1, (String) arg);
                }
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                List<AuditEventDetail> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new AuditEventDetail(
                            rs.getLong("id"),
                            rs.getString("event_type"),
                            rs.getLong("timestamp_ms"),
                            rs.getString("player_uuid"),
                            rs.getString("player_name"),
                            rs.getString("level_id"),
                            rs.getDouble("x"),
                            rs.getDouble("y"),
                            rs.getDouble("z"),
                            rs.getString("subject_id"),
                            rs.getString("detail"), nullableLong(rs, "superseding_event_id"),
                            rs.getString("reason_code")));
                }
                return List.copyOf(result);
            }
        }
    }

    /**
     * Executes an exact logical-target lookup for a bounded set of block positions.
     * The positions are ORed inside one ordered query so pagination remains global
     * and no evidence row is duplicated in memory.
     */
    public List<AuditEventDetail> findExact(Connection conn, String eventType, String playerName,
                                            QueryWindow window, String levelId,
                                            List<ExactPosition> positions, int requestedLimit,
                                            int requestedOffset) throws SQLException {
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("at least one exact position is required");
        }
        if (levelId == null || levelId.isBlank()) {
            throw new IllegalArgumentException("dimension is required for an exact lookup");
        }
        List<ExactPosition> uniquePositions = positions.stream().distinct().toList();
        int limit = QueryLimits.clampLimit(requestedLimit);
        int offset = QueryLimits.clampOffset(requestedOffset);
        if (uniquePositions.size() > 8) {
            throw new IllegalArgumentException("at most eight exact positions are supported");
        }
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, timestamp_ms, player_uuid, player_name,
                       level_id, x, y, z, subject_id, detail
                FROM ig_audit_events
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (eventType != null && !eventType.isBlank() && !"all".equalsIgnoreCase(eventType)) {
            sql.append(" AND event_type = ?");
            args.add(eventType.toUpperCase(Locale.ROOT));
        }
        if (playerName != null && !playerName.isBlank() && !"*".equals(playerName)) {
            String normalizedUser = playerName.toLowerCase(Locale.ROOT);
            sql.append(" AND (LOWER(player_name) = ? OR LOWER(player_uuid) = ? ")
                    .append("OR LOWER(player_uuid) IN (")
                    .append("SELECT LOWER(player_uuid) FROM ig_player_name_history WHERE normalized_name = ?))");
            args.add(normalizedUser);
            args.add(normalizedUser);
            args.add(normalizedUser);
        }
        if (window != null && window.sinceMs() != null) {
            sql.append(" AND timestamp_ms >= ?");
            args.add(window.sinceMs());
        }
        if (window != null && window.untilMs() != null) {
            sql.append(" AND timestamp_ms <= ?");
            args.add(window.untilMs());
        }
        sql.append(" AND level_id = ? AND (");
        args.add(levelId);
        for (int i = 0; i < uniquePositions.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(x = ? AND y = ? AND z = ?)");
            ExactPosition position = uniquePositions.get(i);
            args.add(position.x());
            args.add(position.y());
            args.add(position.z());
        }
        sql.append(") ORDER BY timestamp_ms DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            bind(pstmt, args);
            try (ResultSet rs = pstmt.executeQuery()) {
                List<AuditEventDetail> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new AuditEventDetail(
                            rs.getLong("id"), rs.getString("event_type"), rs.getLong("timestamp_ms"),
                            rs.getString("player_uuid"), rs.getString("player_name"), rs.getString("level_id"),
                            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                            rs.getString("subject_id"), rs.getString("detail")));
                }
                return List.copyOf(result);
            }
        }
    }

    /**
     * Executes the bounded GriefLogger-style lookup against native audit rows.
     * The radius is a cube, matching GriefLogger's documented six-direction
     * radius semantics; the legacy {@link #find} overload retains its spherical
     * near-query behavior for compatibility.
     */
    public List<AuditEventDetail> findFiltered(Connection conn, AuditLookupFilters filters,
                                               String levelId, double centerX, double centerY,
                                               double centerZ, int requestedLimit, int requestedOffset)
            throws SQLException {
        if (filters == null) {
            throw new IllegalArgumentException("filters are required");
        }
        int limit = QueryLimits.clampLimit(requestedLimit);
        int offset = QueryLimits.clampOffset(requestedOffset);
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, timestamp_ms, player_uuid, player_name,
                       level_id, x, y, z, subject_id, detail
                FROM ig_audit_events
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (!filters.eventTypes().isEmpty()) {
            appendPlaceholders(sql, " AND event_type IN (", filters.eventTypes().size(), ")");
            args.addAll(filters.eventTypes());
        }
        if (!filters.playerNames().isEmpty()) {
            List<String> names = filters.playerNames().stream()
                    .map(value -> value.toLowerCase(Locale.ROOT)).toList();
            sql.append(" AND (LOWER(player_name) IN (");
            appendPlaceholders(sql, "", names.size(), "");
            sql.append(") OR LOWER(player_uuid) IN (");
            appendPlaceholders(sql, "", names.size(), "");
            sql.append(") OR LOWER(player_uuid) IN (")
                    .append("SELECT LOWER(player_uuid) FROM ig_player_name_history WHERE normalized_name IN (");
            appendPlaceholders(sql, "", names.size(), "");
            sql.append(")))");
            args.addAll(names);
            args.addAll(names);
            args.addAll(names);
        }
        if (filters.window() != null && filters.window().sinceMs() != null) {
            sql.append(" AND timestamp_ms >= ?");
            args.add(filters.window().sinceMs());
        }
        if (filters.window() != null && filters.window().untilMs() != null) {
            sql.append(" AND timestamp_ms <= ?");
            args.add(filters.window().untilMs());
        }
        if (levelId == null || levelId.isBlank()) {
            throw new IllegalArgumentException("dimension is required for a radius lookup");
        }
        sql.append(" AND level_id = ?");
        args.add(levelId);
        double radius = filters.radiusBlocks();
        sql.append(" AND ABS(x - ?) <= ? AND ABS(y - ?) <= ? AND ABS(z - ?) <= ?");
        args.add(centerX);
        args.add(radius);
        args.add(centerY);
        args.add(radius);
        args.add(centerZ);
        args.add(radius);
        appendSubjectFilter(sql, args, "subject_id", filters.includeSubjects(), false);
        appendSubjectFilter(sql, args, "subject_id", filters.excludeSubjects(), true);
        sql.append(" ORDER BY timestamp_ms DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);

        try (PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {
            bind(pstmt, args);
            try (ResultSet rs = pstmt.executeQuery()) {
                List<AuditEventDetail> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new AuditEventDetail(
                            rs.getLong("id"), rs.getString("event_type"), rs.getLong("timestamp_ms"),
                            rs.getString("player_uuid"), rs.getString("player_name"), rs.getString("level_id"),
                            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                            rs.getString("subject_id"), rs.getString("detail")));
                }
                return List.copyOf(result);
            }
        }
    }

    private static void appendPlaceholders(StringBuilder sql, String prefix, int count, String suffix) {
        sql.append(prefix);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(suffix);
    }

    private static void appendSubjectFilter(StringBuilder sql, List<Object> args, String column,
                                            List<String> values, boolean exclude) {
        if (values.isEmpty()) {
            return;
        }
        sql.append(exclude ? " AND (" + column + " IS NULL OR LOWER(" + column + ") NOT IN ("
                : " AND LOWER(" + column + ") IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
            args.add(values.get(i));
        }
        sql.append(exclude ? "))" : ")");
    }

    private static void bind(PreparedStatement pstmt, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object arg = args.get(i);
            if (arg instanceof Long value) {
                pstmt.setLong(i + 1, value);
            } else if (arg instanceof Integer value) {
                pstmt.setInt(i + 1, value);
            } else if (arg instanceof Double value) {
                pstmt.setDouble(i + 1, value);
            } else {
                pstmt.setString(i + 1, (String) arg);
            }
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
