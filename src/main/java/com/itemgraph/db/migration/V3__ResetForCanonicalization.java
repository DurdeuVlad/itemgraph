package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 3 (item canonicalization) replaced Phase 2's placeholder fingerprinting
 * (bare item_id hash only, ignoring the raw_data metadata blob - meaning e.g.
 * every diamond sword shared one fingerprint regardless of enchantments or
 * custom names) with real DataComponentPatch decoding via ItemCanonicalizer.
 *
 * Existing ig_item_fingerprints rows were computed with the old, coarser
 * formula and are now stale. This migration clears ItemGraph's OWN derived
 * data (fingerprints, observations, and the per-source ingestion checkpoints)
 * so the next ingestion cycle re-processes every GriefLogger row through the
 * new canonicalizer from scratch.
 *
 * This ONLY touches ItemGraph's own SQLite database. GriefLogger's database
 * is a separate, read-only data source and is never written to by ItemGraph -
 * nothing here affects it in any way. ig_nodes is intentionally left alone:
 * node identity (players/containers) does not depend on item fingerprinting
 * and re-ingestion will simply reuse the existing nodes via the same
 * get-or-create-by-identity logic.
 */
public class V3__ResetForCanonicalization implements SchemaMigration {
    @Override
    public int getVersion() {
        return 3;
    }

    @Override
    public String getDescription() {
        return "Reset stale Phase-2 fingerprints/observations/checkpoints for Phase-3 canonicalization";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM ig_edge_evidence;");
            stmt.execute("DELETE FROM ig_inferred_edges;");
            stmt.execute("DELETE FROM ig_observations;");
            stmt.execute("DELETE FROM ig_item_fingerprints;");
            stmt.execute("DELETE FROM ig_source_checkpoints;");
        }
    }
}
