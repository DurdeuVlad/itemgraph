package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 7 quantity flow and stack conservation.
 *
 * <p>Phase 5 correlation enforced a strict 1-to-1 matching constraint (drop.amount == pickup.amount)
 * and binary evidence consumption (one pickup could only support one edge). That deliberately
 * deferred stack splitting and stack merging to Phase 7.
 *
 * <p>This migration introduces the quantity allocation ledger:
 * <ul>
 *   <li>{@code ig_edge_allocations}: tracks exact item quantities attributed from a source observation
 *       or to a destination observation for each inferred edge. This enables 1-to-many splits,
 *       many-to-1 merges, and partial transfers without per-item UUIDs.</li>
 *   <li>{@code ig_observations.correlation_status}: replaces the overloaded binary interpretation
 *       of {@code correlated_at} with an explicit forensic lifecycle state:
 *       <ul>
 *         <li>{@code PENDING}: unallocated, candidate window still open.</li>
 *         <li>{@code PARTIALLY_ALLOCATED}: partially consumed, window still open for subsequent matches.</li>
 *         <li>{@code FULLY_ALLOCATED}: all evidenced quantity accounted for.</li>
 *         <li>{@code CLOSED_UNRESOLVED}: window closed with unallocated residual quantity.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Existing Phase 5/6 edges and evaluated observations are backfilled into the allocation ledger
 * and status column so migration is seamless, idempotent, and preserves historical data without
 * double-allocation.
 */
public class V7__QuantityFlowLedger implements SchemaMigration {

    @Override
    public int getVersion() {
        return 7;
    }

    @Override
    public String getDescription() {
        return "Add ig_edge_allocations ledger and ig_observations.correlation_status for quantity flow";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 1. Quantity allocation ledger
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_edge_allocations (
                    edge_id INTEGER NOT NULL REFERENCES ig_inferred_edges(id) ON DELETE CASCADE,
                    observation_id INTEGER NOT NULL REFERENCES ig_observations(id),
                    allocation_role TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    PRIMARY KEY(edge_id, observation_id, allocation_role)
                );
            """);

            stmt.execute("CREATE INDEX IF NOT EXISTS idx_alloc_obs_role ON ig_edge_allocations(observation_id, allocation_role);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_alloc_edge ON ig_edge_allocations(edge_id);");

            // 2. Explicit lifecycle status on observations
            stmt.execute("ALTER TABLE ig_observations ADD COLUMN correlation_status TEXT NOT NULL DEFAULT 'PENDING';");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_corr_status_time ON ig_observations(correlation_status, timestamp_ms);");

            // 3. Backfill existing Phase 5/6 edges into ig_edge_allocations
            stmt.execute("""
                INSERT OR IGNORE INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount)
                SELECT e.id, o.id, 'SOURCE', e.amount
                FROM ig_inferred_edges e
                JOIN ig_edge_evidence ev ON ev.edge_id = e.id
                JOIN ig_observations o ON o.id = ev.observation_id
                WHERE o.action_type IN ('DROP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM');
            """);

            stmt.execute("""
                INSERT OR IGNORE INTO ig_edge_allocations (edge_id, observation_id, allocation_role, amount)
                SELECT e.id, o.id, 'DESTINATION', e.amount
                FROM ig_inferred_edges e
                JOIN ig_edge_evidence ev ON ev.edge_id = e.id
                JOIN ig_observations o ON o.id = ev.observation_id
                WHERE o.action_type = 'PICKUP_ITEM';
            """);

            // 4. Backfill correlation_status for existing observations
            stmt.execute("""
                UPDATE ig_observations
                SET correlation_status = 'FULLY_ALLOCATED'
                WHERE id IN (
                    SELECT o.id FROM ig_observations o
                    JOIN (SELECT observation_id, SUM(amount) AS total FROM ig_edge_allocations GROUP BY observation_id) a
                      ON a.observation_id = o.id
                    WHERE a.total >= o.amount
                );
            """);

            stmt.execute("""
                UPDATE ig_observations
                SET correlation_status = 'PARTIALLY_ALLOCATED'
                WHERE id IN (
                    SELECT o.id FROM ig_observations o
                    JOIN (SELECT observation_id, SUM(amount) AS total FROM ig_edge_allocations GROUP BY observation_id) a
                      ON a.observation_id = o.id
                    WHERE a.total < o.amount AND a.total > 0
                );
            """);

            stmt.execute("""
                UPDATE ig_observations
                SET correlation_status = 'CLOSED_UNRESOLVED'
                WHERE correlated_at IS NOT NULL
                  AND correlation_status = 'PENDING'
                  AND id NOT IN (SELECT observation_id FROM ig_edge_allocations);
            """);
        }
    }
}
