package com.itemgraph.db;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialectSqlTest {
    @Test
    void preservesTableIdempotencyAndRewritesSQLiteTypes() {
        String sql = "CREATE TABLE IF NOT EXISTS sample (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "applied_at INTEGER NOT NULL, source_path TEXT NOT NULL, name TEXT NOT NULL, payload BLOB, ratio REAL, detail TEXT, fingerprint_component_summary TEXT)";

        String translated = DialectSql.translate(sql, DatabaseDialect.MYSQL_MARIADB);

        assertTrue(translated.contains("CREATE TABLE IF NOT EXISTS"));
        assertTrue(translated.contains("BIGINT PRIMARY KEY AUTO_INCREMENT"));
        assertTrue(translated.contains("applied_at BIGINT"));
        assertTrue(translated.contains("source_path LONGTEXT"));
        assertTrue(translated.contains("name VARCHAR(191)"));
        assertTrue(translated.contains("payload LONGBLOB"));
        assertTrue(translated.contains("ratio DOUBLE"));
        assertTrue(translated.contains("detail LONGTEXT"));
        assertTrue(translated.contains("fingerprint_component_summary LONGTEXT"));
    }

    @Test
    void rewritesIdempotentIndexesWithoutRemovingTableGuard() {
        String sql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_nodes_external_key "
                + "ON ig_nodes(node_type, external_key) WHERE external_key IS NOT NULL";

        String translated = DialectSql.translate(sql, DatabaseDialect.MYSQL_MARIADB);

        assertTrue(translated.startsWith("CREATE UNIQUE INDEX idx_nodes_external_key"));
        assertFalse(translated.contains("IF NOT EXISTS"));
        assertFalse(translated.toLowerCase().contains(" where "));
    }

    @Test
    void rewritesSQLiteUpsertAndTableInfo() {
        String upsert = "INSERT INTO ig_source_checkpoints (source_name, last_source_rowid) VALUES (?, ?) "
                + "ON CONFLICT(source_name) DO UPDATE SET last_source_rowid = excluded.last_source_rowid";
        assertEquals("INSERT INTO ig_source_checkpoints (source_name, last_source_rowid) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE last_source_rowid = VALUES(last_source_rowid)",
                DialectSql.translate(upsert, DatabaseDialect.MYSQL_MARIADB));

        assertEquals("SELECT COLUMN_NAME AS name FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ig_nodes' ORDER BY ORDINAL_POSITION",
                DialectSql.translate("PRAGMA table_info(ig_nodes)", DatabaseDialect.MYSQL_MARIADB));
    }

    @Test
    void rewritesDropIndexWithOwningTable() {
        assertEquals("DROP INDEX idx_obs_internal_dedup ON ig_observations",
                DialectSql.translate("DROP INDEX IF EXISTS idx_obs_internal_dedup;", DatabaseDialect.MYSQL_MARIADB));
    }
}
