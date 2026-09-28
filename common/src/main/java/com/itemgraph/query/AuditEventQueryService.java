package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Read-only queries for native GriefLogger-parity audit events. */
public final class AuditEventQueryService {
    public static final double MAX_RADIUS_BLOCKS = 1_024.0;
    public static final List<String> EVENT_TYPES = List.of(
            "all", "PLAYER_JOIN", "PLAYER_QUIT", "CHAT_MESSAGE", "COMMAND_EXECUTED",
            "PLACE_BLOCK", "BREAK_BLOCK", "INTERACT_BLOCK", "INTERACT_BLOCK_ATTEMPT",
            "KILL_ENTITY", "THROW_ITEM", "SHOOT_ITEM");

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
                SELECT id, event_type, timestamp_ms, player_uuid, player_name,
                       level_id, x, y, z, subject_id, detail
                FROM ig_audit_events
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (eventType != null && !eventType.isBlank() && !"all".equalsIgnoreCase(eventType)) {
            sql.append(" AND event_type = ?");
            args.add(eventType.toUpperCase(java.util.Locale.ROOT));
        }
        if (playerName != null && !playerName.isBlank() && !"*".equals(playerName)) {
            sql.append(" AND player_name = ?");
            args.add(playerName);
        }
        if (window != null && window.sinceMs() != null) {
            sql.append(" AND timestamp_ms >= ?");
            args.add(window.sinceMs());
        }
        if (window != null && window.untilMs() != null) {
            sql.append(" AND timestamp_ms <= ?");
            args.add(window.untilMs());
        }
        if (levelId != null && !levelId.isBlank()) {
            sql.append(" AND level_id = ?");
            args.add(levelId);
        }
        if (centerX != null && centerY != null && centerZ != null && requestedRadius != null) {
            double radius = Math.max(1.0, Math.min(MAX_RADIUS_BLOCKS, requestedRadius));
            sql.append(" AND ((x - ?) * (x - ?) + (y - ?) * (y - ?) + (z - ?) * (z - ?)) <= ?");
            args.add(centerX);
            args.add(centerX);
            args.add(centerY);
            args.add(centerY);
            args.add(centerZ);
            args.add(centerZ);
            args.add(radius * radius);
        }
        sql.append(" ORDER BY timestamp_ms DESC, id DESC LIMIT ? OFFSET ?");
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
                            rs.getString("detail")));
                }
                return List.copyOf(result);
            }
        }
    }
}
