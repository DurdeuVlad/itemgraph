package com.itemgraph.ingest;

import com.itemgraph.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Imports every documented GriefLogger 26.2 table into an immutable, generic
 * provenance ledger. The source connection is opened read-only and is never used
 * for writes, migrations, indexes, or pragmas that change source state.
 *
 * <p>Rows are intentionally retained as deterministic JSON rather than being
 * guessed into the item-flow ledger. This keeps unknown actions, schema variants,
 * and binary component fields queryable until a versioned decoder exists.</p>
 */
public final class GriefLoggerHistoricalImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(GriefLoggerHistoricalImporter.class);
    public static final List<String> SOURCE_TABLES = List.of(
            "items", "containers", "blocks", "sessions", "chats", "commands",
            "users", "usernames", "levels", "materials", "entities");
    private static final int BATCH_SIZE = 500;
    private static final int KNOWN_ACTION_MIN = 0;
    private static final int KNOWN_ACTION_MAX = 17;
    /** Portable source-key width for MySQL/MariaDB composite primary keys. */
    static final int MAX_SOURCE_KEY_CODE_POINTS = 191;

    private final GriefLoggerAdapter source;
    private final DatabaseManager target;

    public GriefLoggerHistoricalImporter(GriefLoggerAdapter source, DatabaseManager target) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
    }

    public record TableReport(String tableName, boolean present, long rowsSeen,
                              long rowsImported, long rowsOpaque, String status,
                              String detail) {}

    public record ImportReport(String sourcePath, String sourceSha256,
                               String schemaFingerprint, long runId,
                               List<TableReport> tables, long rowsImported,
                               long rowsOpaque, String status) {
        public ImportReport {
            tables = List.copyOf(tables);
        }
    }

    private record Column(String name, String declaredType, boolean primaryKey) {}

    /** Performs one bounded, resumable import pass. */
    public ImportReport importAll() throws IOException, SQLException {
        if (!source.isDatabaseAvailable()) {
            throw new IOException("GriefLogger database is not readable: " + source.getDatabasePath());
        }
        if (!source.isSupportedSchemaAvailable()) {
            throw new IOException("GriefLogger database does not contain the supported core schema: "
                    + source.getDatabasePath());
        }
        if (!target.isInitialized()) {
            throw new SQLException("ItemGraph database is not initialized");
        }

        String sourceHash = sha256(source.getDatabasePath());
        long startedAt = System.currentTimeMillis();
        try (Connection sourceConn = source.openReadOnlyConnection();
             Connection targetConn = target.openWriteConnection()) {
            Map<String, List<Column>> schema = discoverSchema(sourceConn);
            String schemaFingerprint = schemaFingerprint(schema);
            GriefLoggerHistoricalProjection.SourceReferences references =
                    GriefLoggerHistoricalProjection.loadReferences(sourceConn);
            boolean originalAutoCommit = targetConn.getAutoCommit();
            long runId = insertRun(targetConn, source.getDatabasePath().toString(), sourceHash,
                    schemaFingerprint, startedAt);
            List<TableReport> reports = new ArrayList<>();
            long imported = 0;
            long opaque = 0;
            try {
                targetConn.setAutoCommit(false);
                for (String table : SOURCE_TABLES) {
                    List<Column> columns = schema.get(table);
                    TableReport report;
                    if (columns == null) {
                        report = new TableReport(table, false, 0, 0, 0,
                                "MISSING", "table is absent from source schema");
                    } else {
                        report = importTable(sourceConn, targetConn, sourceHash,
                                schemaFingerprint, table, columns, references);
                    }
                    // A table boundary is a durable checkpoint. This prevents a later
                    // table failure from contradicting counts already persisted.
                    targetConn.commit();
                    reports.add(report);
                    imported += report.rowsImported();
                    opaque += report.rowsOpaque();
                }
                updateRun(targetConn, runId, "COMPLETE", reports.size(), imported, opaque,
                        reportJson(reports));
                targetConn.commit();
                return new ImportReport(source.getDatabasePath().toString(), sourceHash,
                        schemaFingerprint, runId, reports, imported, opaque, "COMPLETE");
            } catch (SQLException | RuntimeException e) {
                if (e instanceof TableImportFailure tableFailure) {
                    TableReport partial = tableFailure.report();
                    reports.add(partial);
                    imported += partial.rowsImported();
                    opaque += partial.rowsOpaque();
                }
                try {
                    targetConn.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                try {
                    updateRun(targetConn, runId, "FAILED", reports.size(), imported, opaque,
                            reportJson(reports, e));
                    targetConn.commit();
                } catch (SQLException statusFailure) {
                    e.addSuppressed(statusFailure);
                }
                throw e;
            } finally {
                targetConn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private static final class TableImportFailure extends SQLException {
        private final TableReport report;

        private TableImportFailure(TableReport report, Throwable cause) {
            super("Historical import failed for table " + report.tableName(), cause);
            this.report = report;
        }

        private TableReport report() {
            return report;
        }
    }

    private TableReport importTable(Connection sourceConn, Connection targetConn,
                                    String sourceHash, String schemaFingerprint,
                                    String table, List<Column> columns,
                                    GriefLoggerHistoricalProjection.SourceReferences references) throws SQLException {
        String checkpoint = null;
        boolean checkpointFound = false;
        long seen = 0;
        long imported = 0;
        long opaque = 0;
        long committedSeen = 0;
        long committedImported = 0;
        long committedOpaque = 0;
        long ordinal = 0;
        boolean rowidQuery = true;
        try {
            checkpoint = readCheckpoint(targetConn, sourceHash, table);
            checkpointFound = checkpoint == null;
            try {
                String select = "SELECT rowid AS __itemgraph_rowid__, * FROM " + quote(table) + " ORDER BY rowid ASC";
                try (PreparedStatement stmt = sourceConn.prepareStatement(select)) {
                    try (ResultSet rows = stmt.executeQuery()) {
                        while (rows.next()) {
                            ordinal++;
                            RowData row = rowData(rows, columns, ordinal, true);
                            if (!checkpointFound) {
                                ensureProjection(targetConn, sourceHash, schemaFingerprint, table, row, references);
                                if (matchesCheckpoint(row, checkpoint)) {
                                    checkpointFound = true;
                                }
                                continue;
                            }
                            seen++;
                            boolean inserted = insertRow(targetConn, sourceHash, schemaFingerprint, table, row,
                                    references);
                            if (inserted) {
                                imported++;
                            }
                            if (row.unresolvedReason() != null) {
                                opaque++;
                            }
                            writeCheckpoint(targetConn, sourceHash, table, row.sourceKey(),
                                    seen, imported, opaque);
                            if ((seen % BATCH_SIZE) == 0) {
                                targetConn.commit();
                                committedSeen = seen;
                                committedImported = imported;
                                committedOpaque = opaque;
                            }
                        }
                    }
                }
        } catch (SQLException rowidFailure) {
            String message = rowidFailure.getMessage() == null ? "" : rowidFailure.getMessage().toLowerCase();
            if (message.contains("no such column") || message.contains("without rowid")) {
                rowidQuery = false;
                // WITHOUT ROWID tables are uncommon in GriefLogger, but schema-variant
                // fixtures must remain importable with an ordinal/hash source key.
                String fallback = "SELECT * FROM " + quote(table);
                try (PreparedStatement stmt = sourceConn.prepareStatement(fallback);
                     ResultSet rows = stmt.executeQuery()) {
                    while (rows.next()) {
                        ordinal++;
                        RowData row = rowData(rows, columns, ordinal, false);
                        if (!checkpointFound) {
                            ensureProjection(targetConn, sourceHash, schemaFingerprint, table, row, references);
                            if (matchesCheckpoint(row, checkpoint)) {
                                checkpointFound = true;
                            }
                            continue;
                        }
                        seen++;
                        boolean inserted = insertRow(targetConn, sourceHash, schemaFingerprint, table, row,
                                references);
                        if (inserted) {
                            imported++;
                        }
                        if (row.unresolvedReason() != null) {
                            opaque++;
                        }
                        writeCheckpoint(targetConn, sourceHash, table, row.sourceKey(),
                                seen, imported, opaque);
                        if ((seen % BATCH_SIZE) == 0) {
                            targetConn.commit();
                            committedSeen = seen;
                            committedImported = imported;
                            committedOpaque = opaque;
                        }
                    }
                }
            } else {
                throw rowidFailure;
            }
        }
        } catch (SQLException | RuntimeException failure) {
            String detail = rowidQuery
                    ? "source-rowid retained; hash/ordinal checkpoint; failed after committed rows"
                    : "ordinal/hash checkpoint; source has no rowid; failed after committed rows";
            TableReport partial = new TableReport(table, true, committedSeen, committedImported,
                    committedOpaque, "FAILED", detail);
            throw new TableImportFailure(partial, failure);
        }
        String detail = rowidQuery ? "source-rowid retained; hash/ordinal checkpoint" : "ordinal/hash checkpoint; source has no rowid";
        return new TableReport(table, true, seen, imported, opaque, "COMPLETE", detail);
    }

    private record RowData(String sourceKey, String legacySourceKey, Long sourceRowid, long ordinal,
                           String payloadJson, byte[] payloadBlob, Integer actionId,
                           String unresolvedReason, Map<String, Object> values) {}

    private RowData rowData(ResultSet rows, List<Column> columns, long ordinal,
                            boolean hasRowid) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        byte[] opaqueBlob = null;
        Integer actionId = null;
        List<String> primaryValues = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i);
            int resultIndex = hasRowid ? i + 2 : i + 1;
            Object value = rows.getObject(resultIndex);
            values.put(column.name(), value);
            if (column.primaryKey()) {
                primaryValues.add(value == null ? "null" : canonicalScalar(value));
            }
            if (value instanceof byte[] bytes && opaqueBlob == null) {
                opaqueBlob = bytes.clone();
            }
            if ((column.name().equalsIgnoreCase("action")
                    || column.name().equalsIgnoreCase("action_id")) && value instanceof Number n) {
                actionId = n.intValue();
            }
        }
        String payloadJson = mapJson(values);
        Long sourceRowid = null;
        String sourceKey;
        String legacySourceKey;
        if (!primaryValues.isEmpty()) {
            legacySourceKey = "pk:" + String.join("|", primaryValues);
            sourceKey = sourceKeyForPrimaryValues(primaryValues);
        } else if (hasRowid) {
            sourceRowid = rows.getLong("__itemgraph_rowid__");
            sourceKey = "hash:" + sha256(payloadJson.getBytes(StandardCharsets.UTF_8)) + ":" + ordinal;
            legacySourceKey = sourceKey;
        } else {
            sourceKey = "hash:" + sha256(payloadJson.getBytes(StandardCharsets.UTF_8)) + ":" + ordinal;
            legacySourceKey = sourceKey;
        }
        String unresolved = null;
        if (actionId != null && (actionId < KNOWN_ACTION_MIN || actionId > KNOWN_ACTION_MAX)) {
            unresolved = "unknown_action_id:" + actionId;
        } else if (opaqueBlob != null) {
            unresolved = "opaque_binary_field";
        }
        return new RowData(sourceKey, legacySourceKey, sourceRowid, ordinal, payloadJson, opaqueBlob,
                actionId, unresolved, Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    static String sourceKeyForPrimaryValues(List<String> primaryValues) {
        String sourceKey = "pk:" + String.join("|", primaryValues);
        if (sourceKey.codePointCount(0, sourceKey.length()) <= MAX_SOURCE_KEY_CODE_POINTS) {
            return sourceKey;
        }
        // Keep imported keys within the portable MySQL/MariaDB composite-index
        // limit. The immutable payload_json still contains every original PK
        // column/value, so the full source identity remains auditable.
        StringBuilder framedValues = new StringBuilder().append(primaryValues.size()).append(':');
        for (String value : primaryValues) {
            framedValues.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        return "pksha256:" + sha256(framedValues.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean matchesCheckpoint(RowData row, String checkpoint) {
        return row.sourceKey().equals(checkpoint) || row.legacySourceKey().equals(checkpoint);
    }

    private boolean insertRow(Connection conn, String sourceHash, String schemaFingerprint,
                              String table, RowData row,
                              GriefLoggerHistoricalProjection.SourceReferences references) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO ig_grieflogger_rows
                (source_sha256, schema_fingerprint, table_name, source_key, source_rowid,
                 row_ordinal, payload_json, payload_blob, action_id, imported_at, unresolved_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sourceHash);
            stmt.setString(2, schemaFingerprint);
            stmt.setString(3, table);
            stmt.setString(4, row.sourceKey());
            if (row.sourceRowid() == null) stmt.setNull(5, Types.INTEGER); else stmt.setLong(5, row.sourceRowid());
            stmt.setLong(6, row.ordinal());
            stmt.setString(7, row.payloadJson());
            if (row.payloadBlob() == null) stmt.setNull(8, Types.BLOB); else stmt.setBytes(8, row.payloadBlob());
            if (row.actionId() == null) stmt.setNull(9, Types.INTEGER); else stmt.setInt(9, row.actionId());
            stmt.setLong(10, System.currentTimeMillis());
            if (row.unresolvedReason() == null) stmt.setNull(11, Types.VARCHAR); else stmt.setString(11, row.unresolvedReason());
            boolean inserted = stmt.executeUpdate() > 0;
            if (!inserted && row.sourceKey().startsWith("pksha256:")) {
                verifyHashedPrimaryKeyRetry(conn, sourceHash, table, row);
            }
            if (inserted || GriefLoggerHistoricalProjection.isEventTable(table)) {
                GriefLoggerHistoricalProjection.insert(conn, sourceHash, table,
                        new GriefLoggerHistoricalProjection.Row(row.sourceKey(), row.sourceRowid(),
                                row.values(), row.payloadBlob() == null ? null : sha256(row.payloadBlob()),
                                row.unresolvedReason()), references);
            }
            return inserted;
        }
    }

    private static void verifyHashedPrimaryKeyRetry(Connection conn, String sourceHash,
                                                     String table, RowData row) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT payload_json FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = ? AND source_key = ?
                """)) {
            statement.setString(1, sourceHash);
            statement.setString(2, table);
            statement.setString(3, row.sourceKey());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !row.payloadJson().equals(result.getString(1))) {
                    throw new SQLException("Hashed GriefLogger primary-key collision or conflicting retry for table "
                            + table + " and source key " + row.sourceKey());
                }
            }
        }
    }

    private void ensureProjection(Connection conn, String sourceHash, String schemaFingerprint,
                                  String table, RowData row,
                                  GriefLoggerHistoricalProjection.SourceReferences references) throws SQLException {
        if (!GriefLoggerHistoricalProjection.isEventTable(table)) {
            return;
        }
        String storedSourceKey = existingLedgerSourceKey(conn, sourceHash, table, row);
        GriefLoggerHistoricalProjection.insert(conn, sourceHash, table,
                new GriefLoggerHistoricalProjection.Row(storedSourceKey, row.sourceRowid(),
                        row.values(), row.payloadBlob() == null ? null : sha256(row.payloadBlob()),
                        row.unresolvedReason()), references);
    }

    private static String existingLedgerSourceKey(Connection conn, String sourceHash,
                                                   String table, RowData row) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT source_key FROM ig_grieflogger_rows
                WHERE source_sha256 = ? AND table_name = ?
                  AND (HEX(source_key) = HEX(?) OR HEX(source_key) = HEX(?))
                """)) {
            statement.setString(1, sourceHash);
            statement.setString(2, table);
            statement.setString(3, row.legacySourceKey());
            statement.setString(4, row.sourceKey());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : row.sourceKey();
            }
        }
    }

    private static String readCheckpoint(Connection conn, String hash, String table) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT last_source_key FROM ig_grieflogger_import_checkpoints WHERE source_sha256 = ? AND table_name = ?")) {
            stmt.setString(1, hash);
            stmt.setString(2, table);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void writeCheckpoint(Connection conn, String hash, String table, String key,
                                        long seen, long imported, long opaque) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO ig_grieflogger_import_checkpoints
                (source_sha256, table_name, last_source_key, rows_seen, rows_imported, rows_opaque, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(source_sha256, table_name) DO UPDATE SET
                last_source_key=excluded.last_source_key, rows_seen=excluded.rows_seen,
                rows_imported=excluded.rows_imported, rows_opaque=excluded.rows_opaque,
                updated_at=excluded.updated_at
                """)) {
            stmt.setString(1, hash);
            stmt.setString(2, table);
            stmt.setString(3, key);
            stmt.setLong(4, seen);
            stmt.setLong(5, imported);
            stmt.setLong(6, opaque);
            stmt.setLong(7, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    private static Map<String, List<Column>> discoverSchema(Connection conn) throws SQLException {
        Map<String, List<Column>> schema = new LinkedHashMap<>();
        for (String table : SOURCE_TABLES) {
            List<Column> columns = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement("PRAGMA table_info(" + quote(table) + ")");
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    columns.add(new Column(rs.getString("name"), rs.getString("type"),
                            rs.getInt("pk") > 0));
                }
            }
            if (!columns.isEmpty()) {
                schema.put(table, columns);
            }
        }
        return schema;
    }

    private static String schemaFingerprint(Map<String, List<Column>> schema) {
        StringBuilder canonical = new StringBuilder();
        for (String table : SOURCE_TABLES) {
            canonical.append(table).append(':');
            for (Column column : schema.getOrDefault(table, Collections.emptyList())) {
                canonical.append(column.name()).append('/').append(column.declaredType())
                        .append('/').append(column.primaryKey()).append(';');
            }
            canonical.append('|');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static long insertRun(Connection conn, String sourcePath, String sourceHash,
                                  String schemaFingerprint, long startedAt) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO ig_grieflogger_import_runs
                (source_path, source_sha256, schema_fingerprint, started_at, status)
                VALUES (?, ?, ?, ?, 'RUNNING')
                """, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, sourcePath);
            stmt.setString(2, sourceHash);
            stmt.setString(3, schemaFingerprint);
            stmt.setLong(4, startedAt);
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("missing historical import run id");
                return keys.getLong(1);
            }
        }
    }

    private static void updateRun(Connection conn, long runId, String status, int tableCount,
                                  long imported, long opaque, String reportJson) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("""
                UPDATE ig_grieflogger_import_runs SET completed_at = ?, status = ?,
                table_count = ?, rows_imported = ?, rows_opaque = ?, report_json = ? WHERE id = ?
                """)) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setString(2, status);
            stmt.setInt(3, tableCount);
            stmt.setLong(4, imported);
            stmt.setLong(5, opaque);
            stmt.setString(6, reportJson);
            stmt.setLong(7, runId);
            stmt.executeUpdate();
        }
    }

    private static String reportJson(List<TableReport> reports) {
        return reportJson(reports, null);
    }

    private static String reportJson(List<TableReport> reports, Throwable failure) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < reports.size(); i++) {
            if (i > 0) json.append(',');
            TableReport report = reports.get(i);
            json.append('"').append(escapeJson(report.tableName())).append("\":{")
                    .append("\"present\":").append(report.present())
                    .append(",\"rowsSeen\":").append(report.rowsSeen())
                    .append(",\"rowsImported\":").append(report.rowsImported())
                    .append(",\"rowsOpaque\":").append(report.rowsOpaque())
                    .append(",\"status\":\"").append(escapeJson(report.status())).append("\"")
                    .append(",\"detail\":\"").append(escapeJson(report.detail())).append("\"}");
        }
        if (failure != null) {
            if (!reports.isEmpty()) json.append(',');
            json.append("\"error\":\"").append(escapeJson(failure.toString())).append("\"");
        }
        return json.append('}').toString();
    }

    private static String mapJson(Map<String, Object> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append('"').append(escapeJson(entry.getKey())).append("\":")
                    .append(valueJson(entry.getValue()));
        }
        return json.append('}').toString();
    }

    private static String valueJson(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof byte[] bytes) {
            return "{\"$binaryBase64\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}";
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private static String canonicalScalar(Object value) {
        return value instanceof byte[] bytes
                ? Base64.getEncoder().encodeToString(bytes)
                : value.toString();
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        }
    }

    private static String sha256(byte[] value) {
        MessageDigest digest = digest();
        return hex(digest.digest(value));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format("%02x", b));
        return result.toString();
    }
}
