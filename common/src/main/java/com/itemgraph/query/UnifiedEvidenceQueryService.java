package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Bounded, read-only lookup across native audit events, item observations, and
 * transformations.
 *
 * <p>Each source is fetched only far enough to satisfy the requested global page. The rows
 * are then merged by timestamp on the query worker. This avoids an unbounded server-thread
 * scan while still making page offsets global across the three independently keyed tables.
 * Imported GriefLogger records are already immutable ItemGraph observations and therefore
 * enter the same path without opening or mutating the GriefLogger database.
 */
public final class UnifiedEvidenceQueryService {

    /** Canonical values accepted by the GriefLogger-style filtered lookup. */
    public static final List<String> ACTION_TYPES = List.of(
            "all", "PLAYER_JOIN", "PLAYER_QUIT", "CHAT_MESSAGE", "COMMAND_ATTEMPT",
            "COMMAND_EXECUTED", "PLACE_BLOCK", "BREAK_BLOCK", "INTERACT_BLOCK_ATTEMPT",
            "KILL_ENTITY", "THROW_ITEM", "SHOOT_ITEM", "ADD_ITEM", "REMOVE_ITEM",
            "DROP_ITEM", "PICKUP_ITEM", "CRAFT", "SMELT", "ANVIL_RENAME", "ANVIL_REPAIR", "BREAK_ITEM",
            "CONSUME_ITEM", "HOPPER_INSERT", "HOPPER_EXTRACT", "DEATH_DROP",
            "ADD_ITEM_ENDER", "REMOVE_ITEM_ENDER");

    private static final String OBSERVATION_ACTION = "CASE WHEN UPPER(o.action_type) = 'CRAFT_ITEM' THEN 'CRAFT' ELSE UPPER(o.action_type) END";
    private static final String TRANSFORMATION_ACTION = "UPPER(t.transformation_type)";

    public List<UnifiedEvidenceDetail> findFiltered(Connection conn, AuditLookupFilters filters,
                                                     String levelId, double centerX, double centerY,
                                                     double centerZ, int requestedLimit,
                                                     int requestedOffset) throws SQLException {
        if (filters == null) {
            throw new IllegalArgumentException("filters are required");
        }
        if (levelId == null || levelId.isBlank()) {
            throw new IllegalArgumentException("dimension is required for a radius lookup");
        }
        int limit = QueryLimits.clampLimit(requestedLimit);
        int offset = QueryLimits.clampOffset(requestedOffset);
        int sourceLimit = Math.min(QueryLimits.MAX_OFFSET + QueryLimits.MAX_LIMIT, offset + limit);

        List<UnifiedEvidenceDetail> all = new ArrayList<>();
        all.addAll(findAudit(conn, filters, levelId, centerX, centerY, centerZ, sourceLimit));
        all.addAll(findObservations(conn, filters, levelId, centerX, centerY, centerZ, sourceLimit));
        all.addAll(findTransformations(conn, filters, levelId, centerX, centerY, centerZ, sourceLimit));

        all.sort(Comparator.comparingLong(UnifiedEvidenceDetail::timestampMs).reversed()
                .thenComparing(UnifiedEvidenceDetail::source)
                .thenComparing(UnifiedEvidenceDetail::evidenceId));
        if (offset >= all.size()) {
            return List.of();
        }
        int end = Math.min(all.size(), offset + limit);
        return List.copyOf(all.subList(offset, end));
    }

    private List<UnifiedEvidenceDetail> findAudit(Connection conn, AuditLookupFilters filters,
                                                   String levelId, double centerX, double centerY,
                                                   double centerZ, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT id, event_type, timestamp_ms, player_uuid, player_name,
                       level_id, x, y, z, subject_id, detail, source_type
                FROM ig_audit_events
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendActionFilter(sql, args, "UPPER(event_type)", filters.eventTypes(), false);
        appendUserFilter(sql, args, List.of("player_name"), List.of("player_uuid"), filters.playerNames());
        appendWindow(sql, args, filters.window(), "timestamp_ms");
        appendLocation(sql, args, "level_id", "x", "y", "z", levelId, centerX, centerY, centerZ,
                filters.radiusBlocks());
        appendSubjectFilter(sql, args, "subject_id", filters.includeSubjects(), false);
        appendSubjectFilter(sql, args, "subject_id", filters.excludeSubjects(), true);
        sql.append(" ORDER BY timestamp_ms DESC, id DESC LIMIT ?");
        args.add(limit);

        List<UnifiedEvidenceDetail> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bind(statement, args);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new UnifiedEvidenceDetail(
                            valueOr(rs.getString("source_type"), "AUDIT"),
                            "audit#" + rs.getLong("id"),
                            rs.getLong("timestamp_ms"),
                            rs.getString("level_id"),
                            nullableDouble(rs, "x"), nullableDouble(rs, "y"), nullableDouble(rs, "z"),
                            firstNonBlank(rs.getString("player_name"), rs.getString("player_uuid")),
                            rs.getString("event_type"), 0, rs.getString("subject_id"),
                            rs.getString("detail"), "OBSERVED"));
                }
            }
        }
        return rows;
    }

    private List<UnifiedEvidenceDetail> findObservations(Connection conn, AuditLookupFilters filters,
                                                          String levelId, double centerX,
                                                          double centerY, double centerZ, int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.source_type, o.source_event_id, o.timestamp_ms,
                       o.action_type, o.amount,
                       COALESCE(origin.level_id, dest.level_id) AS level_id,
                       COALESCE(origin.x, dest.x) AS x,
                       COALESCE(origin.y, dest.y) AS y,
                       COALESCE(origin.z, dest.z) AS z,
                       CASE WHEN origin.node_type = 'PLAYER' THEN origin.custom_label
                            WHEN dest.node_type = 'PLAYER' THEN dest.custom_label
                            ELSE COALESCE(origin.custom_label, dest.custom_label) END AS player_name,
                       CASE WHEN origin.node_type = 'PLAYER' THEN origin.owner_uuid
                            WHEN dest.node_type = 'PLAYER' THEN dest.owner_uuid
                            ELSE COALESCE(origin.owner_uuid, dest.owner_uuid) END AS player_uuid,
                       f.item_id, origin.custom_label AS origin_label,
                       dest.custom_label AS dest_label
                FROM ig_observations o
                LEFT JOIN ig_nodes origin ON origin.id = o.node_id
                LEFT JOIN ig_nodes dest ON dest.id = o.target_node_id
                LEFT JOIN ig_item_fingerprints f ON f.id = o.fingerprint_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendActionFilter(sql, args, OBSERVATION_ACTION, filters.eventTypes(), true);
        appendUserFilter(sql, args,
                List.of("origin.custom_label", "dest.custom_label"),
                List.of("origin.owner_uuid", "dest.owner_uuid"), filters.playerNames());
        appendWindow(sql, args, filters.window(), "o.timestamp_ms");
        appendLocation(sql, args, "COALESCE(origin.level_id, dest.level_id)",
                "COALESCE(origin.x, dest.x)", "COALESCE(origin.y, dest.y)",
                "COALESCE(origin.z, dest.z)", levelId, centerX, centerY, centerZ, filters.radiusBlocks());
        appendSubjectFilter(sql, args, "f.item_id", filters.includeSubjects(), false);
        appendSubjectFilter(sql, args, "f.item_id", filters.excludeSubjects(), true);
        sql.append(" ORDER BY o.timestamp_ms DESC, o.id DESC LIMIT ?");
        args.add(limit);

        List<UnifiedEvidenceDetail> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bind(statement, args);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String sourceType = valueOr(rs.getString("source_type"), "UNKNOWN");
                    String source = "GRIEFLOGGER".equalsIgnoreCase(sourceType)
                            ? "GRIEFLOGGER" : "OBSERVATION";
                    Long sourceEventId = nullableLong(rs, "source_event_id");
                    String detail = "source=" + sourceType
                            + (sourceEventId == null ? "" : " sourceEvent#" + sourceEventId)
                            + " origin=" + valueOr(rs.getString("origin_label"), "(unlabeled)")
                            + " destination=" + valueOr(rs.getString("dest_label"), "(unlabeled)");
                    rows.add(new UnifiedEvidenceDetail(
                            source, "observation#" + rs.getLong("id"), rs.getLong("timestamp_ms"),
                            rs.getString("level_id"), nullableDouble(rs, "x"), nullableDouble(rs, "y"),
                            nullableDouble(rs, "z"), firstNonBlank(rs.getString("player_name"),
                                    rs.getString("player_uuid")),
                            rs.getString("action_type"), rs.getInt("amount"), rs.getString("item_id"),
                            detail, "OBSERVED"));
                }
            }
        }
        return rows;
    }

    private List<UnifiedEvidenceDetail> findTransformations(Connection conn, AuditLookupFilters filters,
                                                              String levelId, double centerX,
                                                              double centerY, double centerZ, int limit)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.transformation_type, t.quantity, t.timestamp_ms, t.details,
                       p.level_id, p.x, p.y, p.z, p.custom_label AS player_name,
                       p.owner_uuid AS player_uuid,
                       source_fp.item_id AS source_item, result_fp.item_id AS result_item
                FROM ig_item_transformations t
                LEFT JOIN ig_nodes p ON p.id = t.player_node_id
                LEFT JOIN ig_item_fingerprints source_fp ON source_fp.id = t.source_fingerprint_id
                LEFT JOIN ig_item_fingerprints result_fp ON result_fp.id = t.result_fingerprint_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        appendActionFilter(sql, args, TRANSFORMATION_ACTION, filters.eventTypes(), true);
        appendUserFilter(sql, args, List.of("p.custom_label"), List.of("p.owner_uuid"), filters.playerNames());
        appendWindow(sql, args, filters.window(), "t.timestamp_ms");
        appendLocation(sql, args, "p.level_id", "p.x", "p.y", "p.z", levelId,
                centerX, centerY, centerZ, filters.radiusBlocks());
        appendTransformationSubjectFilter(sql, args, filters.includeSubjects(), false);
        appendTransformationSubjectFilter(sql, args, filters.excludeSubjects(), true);
        sql.append(" ORDER BY t.timestamp_ms DESC, t.id DESC LIMIT ?");
        args.add(limit);

        List<UnifiedEvidenceDetail> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bind(statement, args);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String sourceItem = rs.getString("source_item");
                    String resultItem = rs.getString("result_item");
                    String detail = valueOr(rs.getString("details"), "")
                            + " source=" + valueOr(sourceItem, "(missing)")
                            + " result=" + valueOr(resultItem, "(missing)");
                    rows.add(new UnifiedEvidenceDetail(
                            "TRANSFORMATION", "transformation#" + rs.getLong("id"),
                            rs.getLong("timestamp_ms"), rs.getString("level_id"),
                            nullableDouble(rs, "x"), nullableDouble(rs, "y"), nullableDouble(rs, "z"),
                            firstNonBlank(rs.getString("player_name"), rs.getString("player_uuid")),
                            rs.getString("transformation_type"), rs.getInt("quantity"),
                            sourceItem + " -> " + resultItem, detail, "OBSERVED"));
                }
            }
        }
        return rows;
    }

    private static void appendActionFilter(StringBuilder sql, List<Object> args, String expression,
                                           List<String> actions, boolean craftItemAlias) {
        if (actions.isEmpty()) {
            return;
        }
        sql.append(" AND (");
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            String action = actions.get(i).toUpperCase(Locale.ROOT);
            if (craftItemAlias && "CRAFT".equals(action)) {
                sql.append(expression).append(" IN (?, ?)");
                args.add("CRAFT");
                args.add("CRAFT_ITEM");
            } else {
                sql.append(expression).append(" = ?");
                args.add(action);
            }
        }
        sql.append(")");
    }

    private static void appendUserFilter(StringBuilder sql, List<Object> args,
                                         List<String> nameExpressions,
                                         List<String> uuidExpressions, List<String> users) {
        if (users.isEmpty()) {
            return;
        }
        if (nameExpressions.size() != uuidExpressions.size()) {
            throw new IllegalArgumentException("user name and UUID expressions must be paired");
        }
        sql.append(" AND (");
        for (int i = 0; i < nameExpressions.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(LOWER(").append(nameExpressions.get(i)).append(") IN (");
            appendPlaceholders(sql, users.size());
            sql.append(") OR LOWER(").append(uuidExpressions.get(i)).append(") IN (");
            appendPlaceholders(sql, users.size());
            sql.append("))");
            users.forEach(user -> args.add(user.toLowerCase(Locale.ROOT)));
            users.forEach(user -> args.add(user.toLowerCase(Locale.ROOT)));
        }
        sql.append(")");
    }

    private static void appendWindow(StringBuilder sql, List<Object> args, QueryWindow window,
                                     String timestampExpression) {
        if (window != null && window.sinceMs() != null) {
            sql.append(" AND ").append(timestampExpression).append(" >= ?");
            args.add(window.sinceMs());
        }
        if (window != null && window.untilMs() != null) {
            sql.append(" AND ").append(timestampExpression).append(" <= ?");
            args.add(window.untilMs());
        }
    }

    private static void appendLocation(StringBuilder sql, List<Object> args, String levelExpression,
                                       String xExpression, String yExpression, String zExpression,
                                       String levelId, double centerX, double centerY, double centerZ,
                                       double radius) {
        sql.append(" AND ").append(levelExpression).append(" = ?");
        args.add(levelId);
        sql.append(" AND ").append(xExpression).append(" IS NOT NULL AND ")
                .append(yExpression).append(" IS NOT NULL AND ").append(zExpression)
                .append(" IS NOT NULL AND ABS(").append(xExpression).append(" - ?) <= ?")
                .append(" AND ABS(").append(yExpression).append(" - ?) <= ?")
                .append(" AND ABS(").append(zExpression).append(" - ?) <= ?");
        args.add(centerX);
        args.add(radius);
        args.add(centerY);
        args.add(radius);
        args.add(centerZ);
        args.add(radius);
    }

    private static void appendSubjectFilter(StringBuilder sql, List<Object> args, String expression,
                                             List<String> subjects, boolean exclude) {
        if (subjects.isEmpty()) {
            return;
        }
        if (exclude) {
            sql.append(" AND (").append(expression).append(" IS NULL OR LOWER(")
                    .append(expression).append(") NOT IN (");
        } else {
            sql.append(" AND LOWER(").append(expression).append(") IN (");
        }
        appendPlaceholders(sql, subjects.size());
        sql.append(exclude ? "))" : ")");
        subjects.forEach(subject -> args.add(subject.toLowerCase(Locale.ROOT)));
    }

    private static void appendTransformationSubjectFilter(StringBuilder sql, List<Object> args,
                                                           List<String> subjects, boolean exclude) {
        if (subjects.isEmpty()) {
            return;
        }
        sql.append(" AND (");
        sql.append(exclude ? "(source_fp.item_id IS NULL OR LOWER(source_fp.item_id) NOT IN ("
                : "LOWER(source_fp.item_id) IN (");
        appendPlaceholders(sql, subjects.size());
        sql.append(exclude ? ")) AND " : ") OR ");
        sql.append(exclude ? "(result_fp.item_id IS NULL OR LOWER(result_fp.item_id) NOT IN ("
                : "LOWER(result_fp.item_id) IN (");
        appendPlaceholders(sql, subjects.size());
        sql.append(exclude ? "))" : ")");
        subjects.forEach(subject -> args.add(subject.toLowerCase(Locale.ROOT)));
        subjects.forEach(subject -> args.add(subject.toLowerCase(Locale.ROOT)));
        sql.append(")");
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append('?');
        }
    }

    private static void bind(PreparedStatement statement, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object value = args.get(i);
            if (value instanceof Long number) {
                statement.setLong(i + 1, number);
            } else if (value instanceof Double number) {
                statement.setDouble(i + 1, number);
            } else if (value instanceof Integer number) {
                statement.setInt(i + 1, number);
            } else {
                statement.setString(i + 1, String.valueOf(value));
            }
        }
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String first, String second) {
        return valueOr(first, valueOr(second, null));
    }
}
