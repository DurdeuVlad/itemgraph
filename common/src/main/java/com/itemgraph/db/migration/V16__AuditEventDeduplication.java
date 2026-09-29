package com.itemgraph.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds producer-event identity deduplication to the native audit ledger. */
public final class V16__AuditEventDeduplication implements SchemaMigration {
    @Override
    public int getVersion() {
        return 16;
    }

    @Override
    public String getDescription() {
        return "Deduplicate native audit events by source and source event ID";
    }

    @Override
    public void apply(Connection conn) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_events_source_unique
                    ON ig_audit_events(source_type, source_event_id)
                    """);
        }
    }
}
