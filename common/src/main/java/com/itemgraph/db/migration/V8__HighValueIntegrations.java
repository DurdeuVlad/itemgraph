package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 8/9 high-value integrations and transformation tracking.
 *
 * <ul>
 *   <li>Adds {@code item_entity_uuid} column to {@code ig_observations} for ground transfer continuity.</li>
 *   <li>Creates {@code ig_item_transformations} table for linking item transitions across anvil, crafting, and smithing.</li>
 * </ul>
 */
public class V8__HighValueIntegrations implements SchemaMigration {

    @Override
    public int getVersion() {
        return 8;
    }

    @Override
    public String getDescription() {
        return "Add item_entity_uuid to ig_observations and create ig_item_transformations table";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 1. Add item_entity_uuid to ig_observations
            stmt.execute("ALTER TABLE ig_observations ADD COLUMN item_entity_uuid TEXT DEFAULT NULL;");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_obs_entity_uuid ON ig_observations(item_entity_uuid);");

            // 2. Transformations table (Phase 9)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_item_transformations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    transformation_type TEXT NOT NULL,
                    player_node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
                    source_fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
                    result_fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
                    quantity INTEGER NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    details TEXT
                );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trans_source_fp ON ig_item_transformations(source_fingerprint_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trans_result_fp ON ig_item_transformations(result_fingerprint_id);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_trans_player ON ig_item_transformations(player_node_id, timestamp_ms);");
        }
    }
}
