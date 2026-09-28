package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 4 (graph nodes) changed what an ig_observations row means.
 *
 * <p>Every item-table observation ingested under Phases 2-3 was written with
 * {@code node_id} = the acting player and {@code target_node_id} = NULL, regardless
 * of the action. That is topologically wrong: a DROP_ITEM and the PICKUP_ITEM that
 * later recovers the same stack were both anchored to the player alone, so they
 * shared no node and correlation had nothing to join them on. Direction was not
 * merely missing, it was unrecorded — a drop and a pickup were indistinguishable in
 * the graph.
 *
 * <p>Phase 4 resolves a real (origin, destination) pair per action
 * (see {@code IngestionService.resolveItemEndpoints}), introducing GROUND and UNKNOWN
 * nodes. Existing rows cannot be repaired in place — the correct endpoints depend on
 * the source action and coordinates, which is exactly what re-ingestion recomputes —
 * so this migration clears ItemGraph's derived observation layer and the per-source
 * checkpoints, forcing the next ingestion cycle to re-read every GriefLogger row
 * through the corrected direction logic.
 *
 * <p>Deliberately left alone:
 * <ul>
 *   <li>{@code ig_item_fingerprints} — canonicalization is unchanged since Phase 3,
 *       so fingerprints are still correct and re-ingestion will reuse them.</li>
 *   <li>{@code ig_nodes} — existing PLAYER and CONTAINER nodes remain valid identities;
 *       new GROUND and UNKNOWN nodes are simply created on demand during re-ingestion.</li>
 * </ul>
 *
 * <p>This ONLY touches ItemGraph's own SQLite database. GriefLogger's database is a
 * separate, strictly read-only data source and is never written to by ItemGraph, so
 * re-ingestion re-reads the same immutable raw evidence and loses nothing.
 */
public class V4__ItemFlowTopology implements SchemaMigration {
    @Override
    public int getVersion() {
        return 4;
    }

    @Override
    public String getDescription() {
        return "Reset observations/checkpoints for Phase-4 item flow direction topology";
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
