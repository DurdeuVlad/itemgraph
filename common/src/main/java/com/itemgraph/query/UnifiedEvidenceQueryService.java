package com.itemgraph.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

/**
 * Bounded, read-only lookup across native audit events, item observations, and
 * transformations.
 *
 * <p>Each source is fetched only far enough to satisfy the requested global page. The rows
 * are then merged by timestamp on the query worker. This avoids an unbounded server-thread
 * scan while still making page offsets global across the independently keyed tables.
 * Historical GriefLogger rows are read from ItemGraph's normalized projection; the source
 * database is never opened by a lookup.
 */
public final class UnifiedEvidenceQueryService {

    private static final Comparator<UnifiedEvidenceDetail> EVIDENCE_ORDER =
            Comparator.comparingLong(UnifiedEvidenceDetail::timestampMs).reversed()
                    .thenComparing(UnifiedEvidenceDetail::source)
                    .thenComparing(UnifiedEvidenceQueryService::compareEvidenceIds);

    /** Canonical values accepted by the GriefLogger-style filtered lookup. */
    public static final List<String> ACTION_TYPES = List.of(
            "all", "PLAYER_JOIN", "PLAYER_QUIT", "CHAT_MESSAGE", "COMMAND_ATTEMPT",
            "COMMAND_EXECUTED", "PLACE_BLOCK", "BREAK_BLOCK", "INTERACT_BLOCK_ATTEMPT",
            "INTERACT_ENTITY", "INTERACT_ENTITY_COMPLETED", "INTERACT_ENTITY_DENIED",
            "INTERACT_ENTITY_UNRESOLVED", "KILL_ENTITY", "THROW_ITEM", "SHOOT_ITEM", "ADD_ITEM", "REMOVE_ITEM",
            "DROP_ITEM", "PICKUP_ITEM", "CRAFT", "SMELT", "ANVIL_RENAME", "ANVIL_REPAIR", "BREAK_ITEM",
            "CONSUME_ITEM", "HOPPER_INSERT", "HOPPER_EXTRACT", "DEATH_DROP",
            "PROJECTILE_SPAWN_ACCEPTED",
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
        all.addAll(findAudit(conn, filters, levelId, centerX, centerY, centerZ, sourceLimit, null, false));
        all.addAll(findObservations(conn, filters, levelId, centerX, centerY, centerZ, sourceLimit, null));
        all.addAll(findTransformations(conn, filters, levelId, centerX, centerY, centerZ, sourceLimit, null));
        all.addAll(findHistoricalGriefLogger(conn, filters, levelId, centerX, centerY, centerZ,
                sourceLimit, null, false));

        all.sort(EVIDENCE_ORDER);
        if (offset >= all.size()) {
            return List.of();
        }
        int end = Math.min(all.size(), offset + limit);
        return List.copyOf(all.subList(offset, end));
    }

    /**
     * Returns one globally ordered page of all evidence at a bounded set of exact
     * physical positions. Used by the block inspector so container deltas and
     * block interactions share one page without radius bleed or per-table duplicates.
     */
    public List<UnifiedEvidenceDetail> findExact(Connection conn, String levelId,
                                                 List<AuditEventQueryService.ExactPosition> positions,
                                                 int requestedLimit, int requestedOffset) throws SQLException {
        if (levelId == null || levelId.isBlank()) {
            throw new IllegalArgumentException("dimension is required for an exact lookup");
        }
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("at least one exact position is required");
        }
        List<AuditEventQueryService.ExactPosition> uniquePositions = positions.stream().distinct().toList();
        if (uniquePositions.size() > 8) {
            throw new IllegalArgumentException("at most eight exact positions are supported");
        }
        int limit = QueryLimits.clampLimit(requestedLimit);
        int offset = QueryLimits.clampOffset(requestedOffset);
        int sourceLimit = Math.min(QueryLimits.MAX_OFFSET + QueryLimits.MAX_LIMIT, offset + limit);
        AuditLookupFilters allActions = new AuditLookupFilters(
                List.of(), List.of(), List.of(), List.of(), 1.0, QueryWindow.unbounded());
        List<UnifiedEvidenceDetail> all = new ArrayList<>();
        all.addAll(findAudit(conn, allActions, levelId, 0, 0, 0, sourceLimit, uniquePositions, true));
        all.addAll(findObservations(conn, allActions, levelId, 0, 0, 0, sourceLimit, uniquePositions));
        all.addAll(findTransformations(conn, allActions, levelId, 0, 0, 0, sourceLimit, uniquePositions));
        all.addAll(findHistoricalGriefLogger(conn, allActions, levelId, 0, 0, 0,
                sourceLimit, uniquePositions, true));
        all.sort(EVIDENCE_ORDER);
        if (offset >= all.size()) {
            return List.of();
        }
        return List.copyOf(all.subList(offset, Math.min(all.size(), offset + limit)));
    }

    /**
     * Returns raw imported rows for an explicit source/table/key provenance lookup.
     * Reference and identity tables intentionally do not enter the radius timeline:
     * they have no event location and must only be opened by an exact provenance request.
     */
    public List<UnifiedEvidenceDetail> findHistoricalProvenance(Connection conn,
                                                                  String sourceSha256,
                                                                  String tableName,
                                                                  String sourceKey,
                                                                  int requestedLimit,
                                                                  int requestedOffset) throws SQLException {
        if (sourceSha256 == null || sourceSha256.isBlank()
                || tableName == null || tableName.isBlank()
                || sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("source hash, table, and source key are required");
        }
        int limit = QueryLimits.clampLimit(requestedLimit);
        int offset = QueryLimits.clampOffset(requestedOffset);
        String sql = """
                SELECT source_sha256, table_name, source_key, source_rowid, payload_json,
                       payload_blob, action_id, imported_at, unresolved_reason
                FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = ? AND source_key = ?
                ORDER BY row_ordinal ASC
                LIMIT ? OFFSET ?
                """;
        List<UnifiedEvidenceDetail> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql)) {
            statement.setString(1, sourceSha256);
            statement.setString(2, tableName);
            statement.setString(3, sourceKey);
            statement.setInt(4, limit);
            statement.setInt(5, offset);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String unresolved = rs.getString("unresolved_reason");
                    byte[] rawBytes = rs.getBytes("payload_blob");
                    String detail = "source=GRIEFLOGGER table=" + rs.getString("table_name")
                            + " key=" + rs.getString("source_key")
                            + " payload=" + rs.getString("payload_json")
                            + (rawBytes == null ? "" : " rawByteSha256=" + sha256(rawBytes))
                            + (unresolved == null ? "" : " unresolved=" + unresolved);
                    rows.add(new UnifiedEvidenceDetail(
                            "GRIEFLOGGER", "provenance#" + rs.getString("table_name")
                                    + "#" + rs.getString("source_key"),
                            rs.getLong("imported_at"), null, null, null, null, null,
                            "HISTORICAL_PROVENANCE", 0, null, detail,
                            unresolved == null ? "PROVENANCE_ONLY" : "UNRESOLVED"));
                }
            }
        }
        return List.copyOf(rows);
    }

    private List<UnifiedEvidenceDetail> findHistoricalGriefLogger(Connection conn,
                                                                    AuditLookupFilters filters,
                                                                    String levelId, double centerX,
                                                                    double centerY, double centerZ,
                                                                    int limit,
                                                                    List<AuditEventQueryService.ExactPosition> exactPositions,
                                                                    boolean activeInspectorHistory) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT g.source_sha256, g.table_name, g.source_key, g.source_rowid, g.timestamp_ms,
                       g.level_name, g.x, g.y, g.z, g.player_name, g.player_uuid, g.action_type, g.quantity,
                       g.subject_id, g.detail, g.evidence_class,
                       s.superseding_event_id, s.reason_code
                FROM ig_grieflogger_lookup g
                LEFT JOIN ig_grieflogger_row_supersessions s
                  ON s.source_sha256 = g.source_sha256
                 AND s.table_name = g.table_name
                 AND s.source_key_prefix = SUBSTR(g.source_key, 1, 191)
                 AND HEX(s.source_key) = HEX(g.source_key)
                WHERE (NOT EXISTS (SELECT 1 FROM ig_grieflogger_import_runs WHERE status = 'COMPLETE')
                       OR g.source_sha256 = (SELECT source_sha256 FROM ig_grieflogger_import_runs
                                             WHERE status = 'COMPLETE'
                                             ORDER BY completed_at DESC, id DESC LIMIT 1))
                """);
        if (activeInspectorHistory) {
            sql.append(" AND s.source_sha256 IS NULL");
        }
        List<Object> args = new ArrayList<>();
        appendActionFilter(sql, args, "UPPER(g.action_type)", filters.eventTypes(), true);
        appendUserFilter(sql, args, List.of("g.player_name"), List.of("g.player_uuid"), filters.playerNames());
        appendWindow(sql, args, filters.window(), "g.timestamp_ms");
        appendEvidenceLocation(sql, args, "g.level_name", "g.x", "g.y", "g.z", levelId,
                centerX, centerY, centerZ, filters.radiusBlocks(), exactPositions);
        appendSubjectFilter(sql, args, "g.subject_id", filters.includeSubjects(), false);
        appendSubjectFilter(sql, args, "g.subject_id", filters.excludeSubjects(), true);
        // HEX gives SQLite and MySQL/MariaDB the same bytewise ordering even when
        // their default text collations differ for case, accents, or Unicode.
        sql.append(" ORDER BY g.timestamp_ms DESC, HEX(g.table_name), HEX(g.source_key) LIMIT ?");
        args.add(limit);

        List<UnifiedEvidenceDetail> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bind(statement, args);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("table_name");
                    String sourceKey = rs.getString("source_key");
                    String detail = rs.getString("detail");
                    Long supersedingEvent = nullableLong(rs, "superseding_event_id");
                    if (supersedingEvent != null) {
                        String reason = "superseded_by=audit#" + supersedingEvent
                                + " reason=" + rs.getString("reason_code");
                        detail = detail == null || detail.isBlank() ? reason : detail + " " + reason;
                    }
                    rows.add(new UnifiedEvidenceDetail(
                            "GRIEFLOGGER",
                            "historical#" + table + "#" + sourceKey,
                            rs.getLong("timestamp_ms"),
                            rs.getString("level_name"),
                            nullableDouble(rs, "x"), nullableDouble(rs, "y"), nullableDouble(rs, "z"),
                            firstNonBlank(rs.getString("player_name"), rs.getString("player_uuid")),
                            rs.getString("action_type"), rs.getInt("quantity"),
                            rs.getString("subject_id"), detail,
                            valueOr(rs.getString("evidence_class"), "OBSERVED"), table, sourceKey));
                }
            }
        } catch (SQLException failure) {
            // Databases created before migration 16 remain readable while the
            // migration is pending; their native sources still produce results.
            if (failure.getMessage() != null && failure.getMessage().contains("no such table")) {
                return List.of();
            }
            throw failure;
        }
        return rows;
    }

    private List<UnifiedEvidenceDetail> findAudit(Connection conn, AuditLookupFilters filters,
                                                   String levelId, double centerX, double centerY,
                                                   double centerZ, int limit,
                                                   List<AuditEventQueryService.ExactPosition> exactPositions,
                                                   boolean activeInspectorHistory) throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, a.event_type, a.timestamp_ms, a.player_uuid, a.player_name,
                       a.level_id, a.x, a.y, a.z, a.subject_id, a.detail, a.source_type,
                       s.superseding_event_id, s.reason_code
                FROM ig_audit_events a
                LEFT JOIN ig_audit_event_supersessions s ON s.superseded_event_id = a.id
                WHERE 1 = 1
                  -- New projectile rows also have a quantity observation. Keep
                  -- only the new audit compatibility projection out of the
                  -- unified timeline when the durable observation with the
                  -- same raw event identity exists. If persistence lost the
                  -- observation, retain the audit row as the only evidence.
                  AND NOT (UPPER(a.event_type) IN ('THROW_ITEM', 'SHOOT_ITEM')
                           AND a.source_type = 'ITEMGRAPH_INTERNAL'
                           AND COALESCE(a.detail, '') LIKE '%quantity=%'
                           AND EXISTS (
                               SELECT 1
                               FROM ig_observations o
                               WHERE o.source_type = 'ITEMGRAPH_INTERNAL'
                                 AND UPPER(o.action_type) = UPPER(a.event_type)
                                 AND o.raw_data IS NOT NULL
                                 AND o.raw_data = a.raw_data
                           ))
                """);
        if (activeInspectorHistory) {
            sql.append(" AND s.superseded_event_id IS NULL");
        }
        List<Object> args = new ArrayList<>();
        appendActionFilter(sql, args, "UPPER(event_type)", filters.eventTypes(), false);
        appendUserFilter(sql, args, List.of("player_name"), List.of("player_uuid"), filters.playerNames());
        appendWindow(sql, args, filters.window(), "timestamp_ms");
        appendEvidenceLocation(sql, args, "level_id", "x", "y", "z", levelId, centerX, centerY, centerZ,
                filters.radiusBlocks(), exactPositions);
        appendSubjectFilter(sql, args, "subject_id", filters.includeSubjects(), false);
        appendSubjectFilter(sql, args, "subject_id", filters.excludeSubjects(), true);
        sql.append(" ORDER BY timestamp_ms DESC, COALESCE(source_type, 'AUDIT') ASC, id DESC LIMIT ?");
        args.add(limit);

        List<UnifiedEvidenceDetail> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(sql.toString())) {
            bind(statement, args);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String detail = rs.getString("detail");
                    Long supersedingEvent = nullableLong(rs, "superseding_event_id");
                    if (supersedingEvent != null) {
                        String reason = "superseded_by=audit#" + supersedingEvent
                                + " reason=" + rs.getString("reason_code");
                        detail = detail == null || detail.isBlank() ? reason : detail + " " + reason;
                    }
                    rows.add(new UnifiedEvidenceDetail(
                            valueOr(rs.getString("source_type"), "AUDIT"),
                            "audit#" + rs.getLong("id"),
                            rs.getLong("timestamp_ms"),
                            rs.getString("level_id"),
                            nullableDouble(rs, "x"), nullableDouble(rs, "y"), nullableDouble(rs, "z"),
                            firstNonBlank(rs.getString("player_name"), rs.getString("player_uuid")),
                            rs.getString("event_type"), 0, rs.getString("subject_id"), detail, "OBSERVED"));
                }
            }
        }
        return rows;
    }

    private List<UnifiedEvidenceDetail> findObservations(Connection conn, AuditLookupFilters filters,
                                                          String levelId, double centerX,
                                                          double centerY, double centerZ, int limit,
                                                          List<AuditEventQueryService.ExactPosition> exactPositions)
            throws SQLException {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.source_type, o.source_event_id, o.timestamp_ms,
                       o.action_type, o.amount,
                       COALESCE(origin.level_id, dest.level_id) AS level_id,
                       COALESCE(origin.x, dest.x) AS x,
                       COALESCE(origin.y, dest.y) AS y,
                       COALESCE(origin.z, dest.z) AS z,
                       origin.level_id AS origin_level_id, origin.x AS origin_x,
                       origin.y AS origin_y, origin.z AS origin_z,
                       dest.level_id AS dest_level_id, dest.x AS dest_x,
                       dest.y AS dest_y, dest.z AS dest_z,
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
                  AND NOT EXISTS (
                      SELECT 1 FROM ig_observation_dispositions d WHERE d.observation_id = o.id
                  )
                """);
        List<Object> args = new ArrayList<>();
        appendActionFilter(sql, args, OBSERVATION_ACTION, filters.eventTypes(), true);
        appendUserFilter(sql, args,
                List.of("origin.custom_label", "dest.custom_label"),
                List.of("origin.owner_uuid", "dest.owner_uuid"), filters.playerNames());
        appendWindow(sql, args, filters.window(), "o.timestamp_ms");
        if (exactPositions == null) {
            appendEvidenceLocation(sql, args, "COALESCE(origin.level_id, dest.level_id)",
                    "COALESCE(origin.x, dest.x)", "COALESCE(origin.y, dest.y)",
                    "COALESCE(origin.z, dest.z)", levelId, centerX, centerY, centerZ,
                    filters.radiusBlocks(), null);
        } else {
            appendObservationExactLocation(sql, args, levelId, exactPositions);
        }
        appendSubjectFilter(sql, args, "f.item_id", filters.includeSubjects(), false);
        appendSubjectFilter(sql, args, "f.item_id", filters.excludeSubjects(), true);
        sql.append(" ORDER BY o.timestamp_ms DESC, "
                + "CASE WHEN UPPER(o.source_type) = 'GRIEFLOGGER' THEN 'GRIEFLOGGER' ELSE 'OBSERVATION' END ASC, "
                + "o.id DESC LIMIT ?");
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
                    String rowLevel = rs.getString("level_id");
                    Double rowX = nullableDouble(rs, "x");
                    Double rowY = nullableDouble(rs, "y");
                    Double rowZ = nullableDouble(rs, "z");
                    if (exactPositions != null && !matchesAny(exactPositions,
                            levelId, rs.getString("origin_level_id"), nullableDouble(rs, "origin_x"),
                            nullableDouble(rs, "origin_y"), nullableDouble(rs, "origin_z"))) {
                        rowLevel = rs.getString("dest_level_id");
                        rowX = nullableDouble(rs, "dest_x");
                        rowY = nullableDouble(rs, "dest_y");
                        rowZ = nullableDouble(rs, "dest_z");
                    }
                    rows.add(new UnifiedEvidenceDetail(
                            source, "observation#" + rs.getLong("id"), rs.getLong("timestamp_ms"),
                            rowLevel, rowX, rowY, rowZ, firstNonBlank(rs.getString("player_name"),
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
                                                              double centerY, double centerZ, int limit,
                                                              List<AuditEventQueryService.ExactPosition> exactPositions)
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
        appendEvidenceLocation(sql, args, "p.level_id", "p.x", "p.y", "p.z", levelId,
                centerX, centerY, centerZ, filters.radiusBlocks(), exactPositions);
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

    private static void appendEvidenceLocation(StringBuilder sql, List<Object> args, String levelExpression,
                                               String xExpression, String yExpression, String zExpression,
                                               String levelId, double centerX, double centerY, double centerZ,
                                               double radius,
                                               List<AuditEventQueryService.ExactPosition> exactPositions) {
        sql.append(" AND ").append(levelExpression).append(" = ?");
        args.add(levelId);
        if (exactPositions != null) {
            sql.append(" AND (");
            for (int i = 0; i < exactPositions.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("(").append(xExpression).append(" = ? AND ")
                        .append(yExpression).append(" = ? AND ")
                        .append(zExpression).append(" = ?)");
                AuditEventQueryService.ExactPosition position = exactPositions.get(i);
                args.add(position.x());
                args.add(position.y());
                args.add(position.z());
            }
            sql.append(")");
            return;
        }
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

    private static void appendObservationExactLocation(StringBuilder sql, List<Object> args,
                                                        String levelId,
                                                        List<AuditEventQueryService.ExactPosition> positions) {
        sql.append(" AND ((origin.level_id = ? AND (");
        args.add(levelId);
        appendPositionAlternatives(sql, args, "origin", positions);
        sql.append(")) OR (dest.level_id = ? AND (");
        args.add(levelId);
        appendPositionAlternatives(sql, args, "dest", positions);
        sql.append(")))");
    }

    private static void appendPositionAlternatives(StringBuilder sql, List<Object> args, String endpoint,
                                                   List<AuditEventQueryService.ExactPosition> positions) {
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }
            sql.append("(").append(endpoint).append(".x = ? AND ")
                    .append(endpoint).append(".y = ? AND ")
                    .append(endpoint).append(".z = ?)");
            AuditEventQueryService.ExactPosition position = positions.get(i);
            args.add(position.x());
            args.add(position.y());
            args.add(position.z());
        }
    }

    private static boolean matchesAny(List<AuditEventQueryService.ExactPosition> positions,
                                      String targetLevelId, String levelId, Double x, Double y, Double z) {
        if (!java.util.Objects.equals(targetLevelId, levelId) || x == null || y == null || z == null) {
            return false;
        }
        return positions.stream().anyMatch(position -> position.x() == x
                && position.y() == y && position.z() == z);
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

    private static int compareEvidenceIds(UnifiedEvidenceDetail left, UnifiedEvidenceDetail right) {
        boolean leftHistorical = left.orderingTableName() != null && left.orderingSourceKey() != null;
        boolean rightHistorical = right.orderingTableName() != null && right.orderingSourceKey() != null;
        Long leftId = numericEvidenceId(left.evidenceId());
        Long rightId = numericEvidenceId(right.evidenceId());
        if (leftId != null && rightId != null) {
            return Long.compare(rightId, leftId);
        }
        if (leftId != null) {
            return -1;
        }
        if (rightId != null) {
            return 1;
        }
        if (leftHistorical && rightHistorical) {
            int tableComparison = compareUtf8(left.orderingTableName(), right.orderingTableName());
            return tableComparison != 0 ? tableComparison
                    : compareUtf8(left.orderingSourceKey(), right.orderingSourceKey());
        }
        if (leftHistorical != rightHistorical) {
            return leftHistorical ? 1 : -1;
        }
        return compareUtf8(left.evidenceId(), right.evidenceId());
    }

    private static int compareUtf8(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int sharedLength = Math.min(leftBytes.length, rightBytes.length);
        for (int i = 0; i < sharedLength; i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(leftBytes[i]), Byte.toUnsignedInt(rightBytes[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }

    private static Long numericEvidenceId(String evidenceId) {
        if (evidenceId.startsWith("historical#") || evidenceId.startsWith("provenance#")) {
            return null;
        }
        int separator = evidenceId.lastIndexOf('#');
        if (separator < 0 || separator == evidenceId.length() - 1) {
            return null;
        }
        String suffix = evidenceId.substring(separator + 1);
        if (!suffix.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
