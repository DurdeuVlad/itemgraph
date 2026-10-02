package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Persists the bounded competing observation set used when an inferred edge is scored. */
public final class V21__PersistInferenceCandidates implements SchemaMigration {
    @Override
    public int getVersion() {
        return 21;
    }

    @Override
    public String getDescription() {
        return "Persist bounded competing evidence IDs for inferred edges";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        apply(connection, DatabaseDialect.fromConnection(connection));
    }

    @Override
    public void apply(Connection connection, DatabaseDialect dialect) throws SQLException {
        if (!MigrationSchema.hasColumn(connection, dialect, "ig_inferred_edges", "competing_observation_ids")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ig_inferred_edges ADD COLUMN competing_observation_ids TEXT");
            }
        }
        if (!MigrationSchema.hasColumn(connection, dialect, "ig_inferred_edges", "competing_candidates_truncated")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ig_inferred_edges ADD COLUMN "
                        + "competing_candidates_truncated INTEGER NOT NULL DEFAULT 0");
            }
        }
    }
}
