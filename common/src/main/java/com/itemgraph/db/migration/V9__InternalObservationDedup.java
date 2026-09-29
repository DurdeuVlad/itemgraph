package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 11 — GriefLogger-independent event coverage.
 *
 * <p>Adds a composite unique index on {@code ig_observations} for
 * {@code ITEMGRAPH_INTERNAL} rows whose {@code source_event_id} is NULL.
 * SQLite treats NULL != NULL, so the existing
 * {@code idx_obs_source_unique(source_type, source_event_id)} does not prevent
 * duplicate internal rows. This migration adds a second partial unique index
 * covering the logical identity of an internal observation:
 * {@code (source_type, timestamp_ms, node_id, fingerprint_id, amount, action_type)}
 * scoped to {@code source_type = 'ITEMGRAPH_INTERNAL'} rows only.
 *
 * <p>This guarantees idempotent writes from {@link com.itemgraph.ingest.InternalObservationService}
 * even if an event is submitted more than once (e.g. server restart, re-registration).
 */
public class V9__InternalObservationDedup implements SchemaMigration {

    @Override
    public int getVersion() {
        return 9;
    }

    @Override
    public String getDescription() {
        return "Add partial unique index for ITEMGRAPH_INTERNAL observation deduplication";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        apply(conn, DatabaseDialect.fromConnection(conn));
    }

    @Override
    public void apply(Connection conn, DatabaseDialect dialect) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            if (dialect == DatabaseDialect.MYSQL_MARIADB) {
                // MySQL/MariaDB do not have SQLite's partial-index predicate.  A
                // generated sentinel keeps NULL internal events in one unique scope,
                // while non-NULL source events remain distinct by their source id.
                if (!MigrationSchema.hasColumn(conn, "ig_observations", "ig_internal_dedup_source")) {
                    stmt.execute("ALTER TABLE ig_observations ADD COLUMN "
                            + "ig_internal_dedup_source BIGINT AS (COALESCE(source_event_id, -1)) STORED;");
                }
                stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_internal_dedup
                    ON ig_observations(source_type, timestamp_ms, node_id, fingerprint_id, amount, action_type, ig_internal_dedup_source);
                """);
            } else {
                // Partial unique index: only covers internal observations (source_event_id IS NULL).
                // Prevents double-writes when the same real-world event is submitted twice.
                stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_internal_dedup
                    ON ig_observations(source_type, timestamp_ms, node_id, fingerprint_id, amount, action_type)
                    WHERE source_event_id IS NULL;
                """);
            }

            // Performance index for the new DEATH_DROP action type alongside existing drop actions.
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_obs_action_type
                ON ig_observations(action_type);
            """);
        }
    }
}
