package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Fixes the V9 internal-observation dedup key.
 *
 * <p>V9's partial unique index on
 * {@code (source_type, timestamp_ms, node_id, fingerprint_id, amount, action_type)}
 * contained no per-event discriminator, so two <em>distinct</em> real events sharing
 * every column were silently collapsed by {@code INSERT OR IGNORE}: a player dying
 * with two identical stacks (one {@code now} timestamp for the whole
 * {@code LivingDropsEvent} batch), a pile of identical item entities picked up in
 * the same millisecond, or a machine pushing identical stacks twice in one tick
 * all lost rows — a quantity-conservation violation.
 *
 * <p>V10 adds {@code item_entity_uuid} to the key. Ground events always carry it
 * ({@link com.itemgraph.listener.ItemEntityEventListener} records
 * {@code ItemEntity#getUUID()}), so a genuinely re-submitted row still deduplicates
 * while distinct entities no longer collide. Container rows have NULL entity uuids;
 * SQLite treats NULL keys as distinct, so identical same-millisecond container
 * transfers are each preserved as the separate events they are.
 */
public class V10__InternalDedupEntityUuid implements SchemaMigration {

    @Override
    public int getVersion() {
        return 10;
    }

    @Override
    public String getDescription() {
        return "Include item_entity_uuid in ITEMGRAPH_INTERNAL dedup index so distinct events do not collapse";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_obs_internal_dedup;");
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_internal_dedup
                ON ig_observations(source_type, timestamp_ms, node_id, fingerprint_id, amount, action_type, item_entity_uuid)
                WHERE source_event_id IS NULL;
            """);
        }
    }
}
