package com.itemgraph.db;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, audited SQL compatibility surface for the two supported dialects.
 *
 * <p>Application SQL remains ordinary JDBC SQL.  Only SQLite schema/idempotency
 * constructs that have no MySQL/MariaDB spelling are normalized here; query
 * semantics such as ordering, limits, joins, and predicates stay identical.
 * This keeps dialect decisions in one place instead of scattering loader checks
 * through evidence and correlation code.</p>
 */
final class DialectSql {
    private static final Pattern TABLE_INFO = Pattern.compile(
            "(?is)^\\s*PRAGMA\\s+table_info\\s*\\(\\s*[`\\\"']?([A-Za-z0-9_]+)[`\\\"']?\\s*\\)\\s*;?\\s*$");
    private static final Pattern DROP_INDEX = Pattern.compile(
            "(?is)^\\s*DROP\\s+INDEX\\s+IF\\s+EXISTS\\s+([A-Za-z0-9_]+)\\s*;?\\s*$");

    private DialectSql() {
    }

    static String translate(String sql, DatabaseDialect dialect) {
        if (dialect == DatabaseDialect.SQLITE || sql == null) {
            return sql;
        }
        Matcher tableInfo = TABLE_INFO.matcher(sql);
        if (tableInfo.matches()) {
            String table = tableInfo.group(1);
            return "SELECT COLUMN_NAME AS name FROM INFORMATION_SCHEMA.COLUMNS "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' ORDER BY ORDINAL_POSITION";
        }
        String trimmed = sql.trim();
        if (trimmed.regionMatches(true, 0, "PRAGMA", 0, "PRAGMA".length())) {
            // SQLite-only connection/session pragmas have already been applied
            // by DatabaseManager for the network backend.  A harmless query
            // keeps migration and connection setup code deterministic.
            return "SELECT 1";
        }

        String translated = sql;
        translated = translated.replaceAll("(?i)\\bINSERT\\s+OR\\s+IGNORE\\b", "INSERT IGNORE");
        // Rewrite the complete SQLite identity-column phrase before the generic
        // token replacement, otherwise the second expression can no longer match.
        translated = translated.replaceAll("(?i)\\bINTEGER\\s+PRIMARY\\s+KEY\\s+AUTOINCREMENT\\b",
                "BIGINT PRIMARY KEY AUTO_INCREMENT");
        translated = translated.replaceAll("(?i)\\bAUTOINCREMENT\\b", "AUTO_INCREMENT");
        // SQLite INTEGER is a signed 64-bit value. Use BIGINT for IDs, epoch
        // milliseconds, rowids, and quantities so strict MySQL mode cannot
        // overflow during migration recording or evidence ingestion.
        translated = translated.replaceAll("(?i)\\bINTEGER\\b", "BIGINT");
        translated = translated.replaceAll("(?i)\\bREAL\\b", "DOUBLE");
        translated = translated.replaceAll("(?i)\\bBLOB\\b", "LONGBLOB");
        translated = translated.replaceAll("(?i)\\bCAST\\(([^()]*)\\s+AS\\s+TEXT\\)", "CAST($1 AS CHAR)");
        // MySQL/MariaDB require VARCHAR-like key columns; SQLite permits TEXT
        // keys.  Keep large forensic fields as LONGTEXT after the key rewrite.
        translated = translated.replaceAll("(?i)\\bTEXT\\b", "VARCHAR(191)");
        translated = translated.replaceAll("(?i)\\b(source_path|detail|details|explanation|component_summary|fingerprint_component_summary|payload_json|unresolved_reason|message|report_json|competing_observation_ids)\\s+VARCHAR\\(191\\)",
                "$1 LONGTEXT");
        translated = translated.replaceAll("(?i)\\b(BLOB)\\b", "LONGBLOB");

        Matcher dropIndex = DROP_INDEX.matcher(translated);
        if (dropIndex.matches()) {
            String index = dropIndex.group(1);
            String table = index.startsWith("idx_obs_") ? "ig_observations" :
                    index.startsWith("idx_nodes_") ? "ig_nodes" :
                    index.startsWith("idx_edge_") ? "ig_edge_evidence" :
                    index.startsWith("idx_edges_") ? "ig_inferred_edges" :
                    index.startsWith("idx_alloc_") ? "ig_edge_allocations" :
                    index.startsWith("idx_trans_") ? "ig_item_transformations" :
                    index.startsWith("idx_audit_") ? "ig_audit_events" :
                    index.startsWith("idx_gl_lookup_") ? "ig_grieflogger_lookup" :
                    index.startsWith("idx_gl_import_") ? "ig_grieflogger_rows" : "ig_observations";
            return "DROP INDEX " + index + " ON " + table;
        }

        // MySQL/MariaDB lack CREATE INDEX IF NOT EXISTS, while CREATE TABLE IF
        // NOT EXISTS is supported and must remain idempotent for migrations.
        translated = translated.replaceAll("(?i)\\b(CREATE\\s+(?:UNIQUE\\s+)?INDEX)\\s+IF\\s+NOT\\s+EXISTS\\b", "$1");
        translated = translated.replaceAll("(?is)\\s+WHERE\\s+source_event_id\\s+IS\\s+NULL\\s*;?\\s*$", ";");
        translated = translated.replaceAll("(?is)\\s+WHERE\\s+external_key\\s+IS\\s+NOT\\s+NULL\\s*;?\\s*$", ";");
        translated = translated.replaceAll("(?is)\\s+WHERE\\s+source_event_id\\s+IS\\s+NULL\\s*\\)", ")");

        translated = translateUpsert(translated);
        return translated;
    }

    private static String translateUpsert(String sql) {
        Matcher conflict = Pattern.compile("(?is)\\bON\\s+CONFLICT\\s*\\([^)]*\\)\\s+DO\\s+UPDATE\\s+SET\\s+").matcher(sql);
        if (!conflict.find()) {
            return sql;
        }
        String result = conflict.replaceFirst("ON DUPLICATE KEY UPDATE ");
        Matcher excluded = Pattern.compile("(?i)\\bexcluded\\.([A-Za-z0-9_]+)").matcher(result);
        StringBuffer buffer = new StringBuffer();
        while (excluded.find()) {
            excluded.appendReplacement(buffer, "VALUES(" + excluded.group(1) + ")");
        }
        excluded.appendTail(buffer);
        return buffer.toString();
    }
}
