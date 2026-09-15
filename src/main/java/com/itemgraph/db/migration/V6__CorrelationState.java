package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 5 correlation bookkeeping.
 *
 * <p>Adds {@code ig_observations.correlated_at}, a nullable epoch-millis stamp set by
 * {@code com.itemgraph.correlation.CorrelationEngine} once an observation has been
 * evaluated for ground-node bridging, whether or not that evaluation produced an
 * inferred edge. NULL means "not yet evaluated", which is what makes the engine
 * incremental: every correlation pass is driven by {@code correlated_at IS NULL} and
 * therefore never re-scans the whole observation table.
 *
 * <p>Kept separate from V5 on purpose. V5 is a data reset caused by the container
 * direction fix; this is a schema addition for a new subsystem. Splitting them keeps
 * each migration's reason for existing legible in the schema history — an operator
 * reading the applied-migration log can tell "we threw observations away" apart from
 * "we added a column".
 *
 * <p>The column is added, never backfilled. Every observation predating this migration
 * was already removed by V5's reset, and re-ingestion writes fresh rows with a NULL
 * correlated_at, so the first correlation pass after deployment evaluates the whole
 * re-ingested set exactly once.
 *
 * <p>Supporting indexes:
 * <ul>
 *   <li>{@code idx_obs_correlation_pending} — the driving scan
 *       ({@code correlated_at IS NULL} ordered by time).</li>
 *   <li>{@code idx_obs_bridge_lookup} — candidate lookup by (ground node, fingerprint,
 *       time), which is the hot query when bridging a drop to its pickups.</li>
 *   <li>{@code idx_edge_evidence_obs} — reverse lookup of "has this observation already
 *       been consumed as evidence", used to stop one pickup from being attributed to
 *       two different drops (quantity conservation).</li>
 * </ul>
 */
public class V6__CorrelationState implements SchemaMigration {
    @Override
    public int getVersion() {
        return 6;
    }

    @Override
    public String getDescription() {
        return "Add ig_observations.correlated_at and correlation lookup indexes";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE ig_observations ADD COLUMN correlated_at INTEGER;");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_correlation_pending ON ig_observations(correlated_at, timestamp_ms);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_bridge_lookup ON ig_observations(node_id, fingerprint_id, timestamp_ms);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_bridge_target_lookup ON ig_observations(target_node_id, fingerprint_id, timestamp_ms);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_edge_evidence_obs ON ig_edge_evidence(observation_id);");
        }
    }
}
