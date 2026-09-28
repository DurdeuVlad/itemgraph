package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 5 corrected the direction of container-table observations.
 *
 * <p>Every containers-table row ingested under Phases 2-4 was written with
 * {@code node_id} = the container and {@code target_node_id} = the interacting player,
 * regardless of the action. That is only correct for a withdrawal. A deposit
 * (ADD_ITEM / ADD_ITEM_ENDER) flows the other way — the item leaves the player and
 * enters the container — so every deposit already in the database points backwards,
 * and a deposit is indistinguishable from a withdrawal by topology alone. In the MVP
 * chain (Chest A -> Player A -> Ground -> Player B -> Chest B) this reversed the final
 * hop, which is precisely the hop an admin asking "where did my item end up" cares
 * about.
 *
 * <p>{@code IngestionService.resolveContainerEndpoints} now resolves a real
 * (origin, destination) pair per action. Existing rows cannot be repaired in place —
 * the correct endpoints depend on the source action, which is exactly what
 * re-ingestion recomputes — so this migration clears ItemGraph's derived observation
 * layer and the per-source checkpoints, forcing the next ingestion cycle to re-read
 * every GriefLogger row through the corrected direction logic. This is the same reset
 * V4 performed for the items table, for the same reason.
 *
 * <p>Deliberately left alone, same rationale as V3 and V4:
 * <ul>
 *   <li>{@code ig_item_fingerprints} — canonicalization is unchanged since Phase 3, so
 *       fingerprints are still correct and re-ingestion will reuse them.</li>
 *   <li>{@code ig_nodes} — existing node identities remain valid; re-ingestion resolves
 *       the same rows through the same get-or-create-by-identity logic.</li>
 * </ul>
 *
 * <p>This ONLY touches ItemGraph's own SQLite database. GriefLogger's database is a
 * separate, strictly read-only data source and is never written to by ItemGraph, so
 * re-ingestion re-reads the same immutable raw evidence and loses nothing.
 */
public class V5__ContainerFlowTopology implements SchemaMigration {
    @Override
    public int getVersion() {
        return 5;
    }

    @Override
    public String getDescription() {
        return "Reset observations/checkpoints for corrected container ADD/REMOVE flow direction";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM ig_edge_evidence;");
            stmt.execute("DELETE FROM ig_inferred_edges;");
            stmt.execute("DELETE FROM ig_observations;");
            stmt.execute("DELETE FROM ig_source_checkpoints;");
        }
    }
}
