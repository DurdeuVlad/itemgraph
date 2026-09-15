package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class V1__InitialSchema implements SchemaMigration {
    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public String getDescription() {
        return "Initial ItemGraph schema";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Ingestion checkpoints for external sources
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_source_checkpoints (
                    source_name TEXT PRIMARY KEY,
                    last_source_rowid INTEGER NOT NULL,
                    last_timestamp INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """);

            // Canonical Inventory Nodes
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_nodes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    node_type TEXT NOT NULL,
                    owner_uuid TEXT,
                    level_id TEXT NOT NULL,
                    x REAL, y REAL, z REAL,
                    block_id TEXT,
                    custom_label TEXT
                )
            """);

            // Canonical Item Fingerprints
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_item_fingerprints (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    item_id TEXT NOT NULL,
                    fingerprint_hash TEXT NOT NULL UNIQUE,
                    custom_name TEXT,
                    rarity TEXT,
                    component_summary TEXT
                )
            """);

            // Authoritative Raw Observations
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_observations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_type TEXT NOT NULL,
                    source_event_id INTEGER,
                    timestamp_ms INTEGER NOT NULL,
                    node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
                    target_node_id INTEGER REFERENCES ig_nodes(id),
                    fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
                    action_type TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    raw_data BLOB,
                    FOREIGN KEY(node_id) REFERENCES ig_nodes(id),
                    FOREIGN KEY(fingerprint_id) REFERENCES ig_item_fingerprints(id)
                )
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_obs_time_fp ON ig_observations(timestamp_ms, fingerprint_id)
            """);

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_obs_source ON ig_observations(source_type, source_event_id)
            """);

            // Derived Inferred Edges
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_inferred_edges (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    from_node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
                    to_node_id INTEGER NOT NULL REFERENCES ig_nodes(id),
                    fingerprint_id INTEGER NOT NULL REFERENCES ig_item_fingerprints(id),
                    amount INTEGER NOT NULL,
                    time_start INTEGER NOT NULL,
                    time_end INTEGER NOT NULL,
                    confidence REAL NOT NULL,
                    explanation TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
            """);

            // Supporting Evidence linking Inferred Edges to Observations
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_edge_evidence (
                    edge_id INTEGER NOT NULL REFERENCES ig_inferred_edges(id),
                    observation_id INTEGER NOT NULL REFERENCES ig_observations(id),
                    PRIMARY KEY(edge_id, observation_id)
                )
            """);
        }
    }
}
