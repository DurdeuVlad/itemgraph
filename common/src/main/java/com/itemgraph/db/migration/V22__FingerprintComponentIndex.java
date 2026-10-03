package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds ItemGraph-owned canonical component values for exact metadata queries. */
public final class V22__FingerprintComponentIndex implements SchemaMigration {
    @Override
    public int getVersion() {
        return 22;
    }

    @Override
    public String getDescription() {
        return "Index canonical item component values for bounded metadata queries";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        apply(connection, DatabaseDialect.fromConnection(connection));
    }

    @Override
    public void apply(Connection connection, DatabaseDialect dialect) throws SQLException {
        if (!MigrationSchema.hasColumn(connection, dialect,
                "ig_item_fingerprints", "component_index_state")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ig_item_fingerprints ADD COLUMN "
                        + "component_index_state VARCHAR(24) NOT NULL DEFAULT 'LEGACY_UNKNOWN'");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ig_fingerprint_components (
                        fingerprint_id BIGINT NOT NULL,
                        component_id VARCHAR(191) NOT NULL,
                        value_hash CHAR(64) NOT NULL,
                        canonical_value TEXT NOT NULL,
                        PRIMARY KEY (fingerprint_id, component_id, value_hash),
                        FOREIGN KEY (fingerprint_id) REFERENCES ig_item_fingerprints(id)
                    )
                    """);
        }
    }
}
