package com.itemgraph.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts imported GriefLogger event rows into a deterministic lookup index.
 * This class never writes to the source connection. The raw ledger remains the
 * authoritative record and the projection can be rebuilt from it in a future
 * migration without changing evidence semantics.
 */
final class GriefLoggerHistoricalProjection {
    private static final Set<String> EVENT_TABLES = Set.of(
            "items", "containers", "blocks", "sessions", "chats", "commands");

    private GriefLoggerHistoricalProjection() {}

    static boolean isEventTable(String table) {
        return EVENT_TABLES.contains(table);
    }

    static SourceReferences loadReferences(Connection source) throws SQLException {
        Map<Long, UserIdentity> users = new HashMap<>();
        Map<Long, String> levels = new HashMap<>();
        Map<Long, String> materials = new HashMap<>();
        Map<Long, String> entities = new HashMap<>();
        List<UsernameHistory> usernames = new ArrayList<>();
        loadUsers(source, users);
        loadNamedIds(source, "levels", levels);
        loadNamedIds(source, "materials", materials);
        loadNamedIds(source, "entities", entities);
        loadUsernames(source, usernames);
        Map<String, List<UsernameHistory>> historyByUuid = new HashMap<>();
        for (UsernameHistory history : usernames) {
            historyByUuid.computeIfAbsent(history.uuid().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(history);
        }
        historyByUuid.values().forEach(history -> history.sort(java.util.Comparator.comparingLong(UsernameHistory::time)));
        return new SourceReferences(users, levels, materials, entities, historyByUuid);
    }

    static void insert(Connection target, String sourceHash, String table, Row row,
                       SourceReferences references) throws SQLException {
        if (!EVENT_TABLES.contains(table)) {
            return;
        }
        Normalized normalized = normalize(table, row, references);
        String sql = """
                INSERT OR IGNORE INTO ig_grieflogger_lookup
                (source_sha256, table_name, source_key, source_rowid, timestamp_ms,
                 level_name, x, y, z, player_name, player_uuid, action_type, quantity,
                 subject_id, detail, evidence_class, unresolved_reason, raw_byte_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            statement.setString(1, sourceHash);
            statement.setString(2, table);
            statement.setString(3, row.sourceKey());
            setNullableLong(statement, 4, row.sourceRowid());
            setNullableLong(statement, 5, normalized.timestampMs());
            setNullableString(statement, 6, normalized.levelName());
            setNullableDouble(statement, 7, normalized.x());
            setNullableDouble(statement, 8, normalized.y());
            setNullableDouble(statement, 9, normalized.z());
            setNullableString(statement, 10, normalized.playerName());
            setNullableString(statement, 11, normalized.playerUuid());
            statement.setString(12, normalized.actionType());
            statement.setInt(13, normalized.quantity());
            setNullableString(statement, 14, normalized.subjectId());
            setNullableString(statement, 15, normalized.detail());
            statement.setString(16, normalized.evidenceClass());
            setNullableString(statement, 17, normalized.unresolvedReason());
            setNullableString(statement, 18, row.rawByteHash());
            statement.executeUpdate();
        }
    }

    private static Normalized normalize(String table, Row row, SourceReferences refs) {
        Map<String, Object> values = row.values();
        Long timestamp = number(values, "time", "timestamp_ms", "timestamp");
        Long userId = number(values, "user", "user_id", "player");
        Long levelId = number(values, "level", "level_id", "world");
        Double x = decimal(values, "x");
        Double y = decimal(values, "y");
        Double z = decimal(values, "z");
        UserIdentity identity = refs.user(userId, timestamp);
        String levelName = refs.level(levelId);
        Integer actionId = integer(values, "action", "action_id");
        String action = action(table, actionId);
        String subject = subject(table, actionId, number(values, "type", "type_id"), refs);
        int quantity = intValue(values, "amount", "quantity", "count");
        String unresolved = row.unresolvedReason();
        if (timestamp == null) unresolved = appendReason(unresolved, "missing_timestamp");
        if (table.equals("items") || table.equals("containers") || table.equals("blocks")) {
            if (levelName == null || x == null || y == null || z == null) {
                unresolved = appendReason(unresolved, "incomplete_location");
            }
        }
        if (actionId == null && (table.equals("items") || table.equals("containers")
                || table.equals("blocks") || table.equals("sessions"))) {
            unresolved = appendReason(unresolved, "missing_action_id");
        }
        if (action.startsWith("UNKNOWN_ACTION_") || "HISTORICAL_UNRESOLVED".equals(action)) {
            unresolved = appendReason(unresolved, "unknown_action_id:" + actionId);
        }
        if (subject == null && (table.equals("items") || table.equals("containers")
                || table.equals("blocks"))) {
            unresolved = appendReason(unresolved, "unresolved_subject_reference");
        }
        String detail = detail(table, row, values, actionId, levelId, userId,
                number(values, "type", "type_id"), unresolved);
        return new Normalized(timestamp, levelName, x, y, z,
                identity == null ? null : identity.name(), identity == null ? null : identity.uuid(),
                action, quantity, subject, detail,
                unresolved == null ? "OBSERVED" : "UNRESOLVED", unresolved);
    }

    private static String action(String table, Integer actionId) {
        if (table.equals("items") || table.equals("containers")) {
            return actionId == null ? "HISTORICAL_UNRESOLVED" : ItemActionMapping.getActionName(actionId);
        }
        if (table.equals("blocks")) {
            return switch (actionId == null ? -1 : actionId) {
                case 0 -> "BREAK_BLOCK";
                case 1 -> "PLACE_BLOCK";
                case 2 -> "INTERACT_BLOCK_ATTEMPT";
                case 3 -> "KILL_ENTITY";
                case 4 -> "INTERACT_ENTITY";
                default -> actionId == null ? "HISTORICAL_UNRESOLVED" : "UNKNOWN_ACTION_" + actionId;
            };
        }
        if (table.equals("sessions")) {
            return switch (actionId == null ? -1 : actionId) {
                case 0 -> "PLAYER_JOIN";
                case 1 -> "PLAYER_QUIT";
                default -> actionId == null ? "HISTORICAL_UNRESOLVED" : "UNKNOWN_ACTION_" + actionId;
            };
        }
        if (table.equals("chats")) return "CHAT_MESSAGE";
        if (table.equals("commands")) return "COMMAND_ATTEMPT";
        return "HISTORICAL_UNRESOLVED";
    }

    private static String subject(String table, Integer actionId, Long typeId, SourceReferences refs) {
        if (typeId == null) return null;
        if (table.equals("blocks") && (actionId != null && (actionId == 3 || actionId == 4))) {
            return refs.entity(typeId);
        }
        if (table.equals("items") || table.equals("containers") || table.equals("blocks")) {
            return refs.material(typeId);
        }
        return null;
    }

    private static String detail(String table, Row row, Map<String, Object> values,
                                 Integer actionId, Long levelId, Long userId, Long typeId,
                                 String unresolved) {
        StringBuilder detail = new StringBuilder("source=GRIEFLOGGER table=")
                .append(table).append(" key=").append(row.sourceKey());
        if (row.sourceRowid() != null) detail.append(" rowid=").append(row.sourceRowid());
        if (actionId != null) detail.append(" actionId=").append(actionId);
        if (userId != null) detail.append(" userId=").append(userId);
        if (levelId != null) detail.append(" levelId=").append(levelId);
        if (typeId != null) detail.append(" typeId=").append(typeId);
        Object message = value(values, "message", "command", "text");
        if (message != null && !String.valueOf(message).isBlank()) {
            detail.append(" text=").append(String.valueOf(message));
        }
        if (unresolved != null) detail.append(" unresolved=").append(unresolved);
        if (row.rawByteHash() != null) detail.append(" rawByteSha256=").append(row.rawByteHash());
        return detail.toString();
    }

    private static void loadUsers(Connection source, Map<Long, UserIdentity> users) throws SQLException {
        for (Map<String, Object> row : readRows(source, "users")) {
            Long id = number(row, "id", "user_id");
            if (id != null) users.put(id, new UserIdentity(string(row, "name"), string(row, "uuid")));
        }
    }

    private static void loadNamedIds(Connection source, String table, Map<Long, String> target) throws SQLException {
        for (Map<String, Object> row : readRows(source, table)) {
            Long id = number(row, "id", table.substring(0, table.length() - 1) + "_id");
            String name = string(row, "name");
            if (id != null && name != null && !name.isBlank()) target.put(id, name);
        }
    }

    private static void loadUsernames(Connection source, List<UsernameHistory> target) throws SQLException {
        for (Map<String, Object> row : readRows(source, "usernames")) {
            Long time = number(row, "time", "timestamp_ms", "timestamp");
            String uuid = string(row, "uuid", "user_uuid");
            String name = string(row, "name", "username");
            if (time != null && uuid != null && name != null) target.add(new UsernameHistory(time, uuid, name));
        }
        target.sort(java.util.Comparator.comparingLong(UsernameHistory::time));
    }

    private static List<Map<String, Object>> readRows(Connection source, String table) throws SQLException {
        List<Map<String, Object>> result = new ArrayList<>();
        String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
        try {
            readRows(source, "SELECT * FROM " + quoted + " ORDER BY rowid ASC", result);
        } catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (message.contains("no such table")) return result;
            if (message.contains("no such column") || message.contains("without rowid")) {
                readRows(source, "SELECT * FROM " + quoted, result);
            } else {
                throw e;
            }
        }
        return result;
    }

    private static void readRows(Connection source, String sql,
                                 List<Map<String, Object>> result) throws SQLException {
        try (Statement statement = source.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = rows.getMetaData();
            while (rows.next()) {
                Map<String, Object> values = new HashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    values.put(metadata.getColumnName(i).toLowerCase(Locale.ROOT), rows.getObject(i));
                }
                result.add(values);
            }
        }
    }

    private static String appendReason(String existing, String reason) {
        if (existing == null || existing.isBlank()) return reason;
        return existing.contains(reason) ? existing : existing + ";" + reason;
    }

    private static Object value(Map<String, Object> values, String... names) {
        for (String name : names) {
            Object value = values.get(name.toLowerCase(Locale.ROOT));
            if (value != null) return value;
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String string(Map<String, Object> values, String... names) {
        Object value = value(values, names);
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Map<String, Object> values, String... names) {
        Object value = value(values, names);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integer(Map<String, Object> values, String... names) {
        Long value = number(values, names);
        return value == null ? null : Math.toIntExact(value);
    }

    private static int intValue(Map<String, Object> values, String... names) {
        Long value = number(values, names);
        return value == null ? 0 : Math.toIntExact(value);
    }

    private static Double decimal(Map<String, Object> values, String... names) {
        Object value = value(values, names);
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return null;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER); else statement.setLong(index, value);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) statement.setNull(index, Types.REAL); else statement.setDouble(index, value);
    }

    private record Normalized(Long timestampMs, String levelName, Double x, Double y, Double z,
                              String playerName, String playerUuid, String actionType,
                              int quantity, String subjectId, String detail,
                              String evidenceClass, String unresolvedReason) {}

    record Row(String sourceKey, Long sourceRowid, Map<String, Object> values,
               String rawByteHash, String unresolvedReason) {}

    record UserIdentity(String name, String uuid) {}

    record UsernameHistory(long time, String uuid, String name) {}

    record SourceReferences(Map<Long, UserIdentity> users, Map<Long, String> levels,
                            Map<Long, String> materials, Map<Long, String> entities,
                            Map<String, List<UsernameHistory>> usernames) {
        UserIdentity user(Long id, Long timestamp) {
            if (id == null) return null;
            UserIdentity identity = users.get(id);
            if (identity == null) return null;
            String name = identity.name();
            if (timestamp != null && identity.uuid() != null) {
                for (UsernameHistory history : usernames.getOrDefault(
                        identity.uuid().toLowerCase(Locale.ROOT), List.of())) {
                    if (history.time() <= timestamp) name = history.name();
                }
            }
            return new UserIdentity(name, identity.uuid());
        }

        String level(Long id) { return id == null ? null : levels.get(id); }
        String material(Long id) { return id == null ? null : materials.get(id); }
        String entity(Long id) { return id == null ? null : entities.get(id); }
    }
}
