package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class V2__DeduplicationConstraint implements SchemaMigration {
    @Override
    public int getVersion() {
        return 2;
    }

    @Override
    public String getDescription() {
        return "Add unique constraint on ig_observations(source_type, source_event_id)";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS idx_obs_source;");
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_obs_source_unique ON ig_observations(source_type, source_event_id);");
        }
    }
}
