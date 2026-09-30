package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Adds durable per-record keys so retrying an uncertain commit cannot duplicate evidence. */
public final class V18__QueueEventIdempotency implements SchemaMigration {
    private record EventTable(String table, String index) {}

    private static final List<EventTable> EVENT_TABLES = List.of(
            new EventTable("ig_observations", "idx_obs_ingest_event_uuid"),
            new EventTable("ig_item_transformations", "idx_trans_ingest_event_uuid"),
            new EventTable("ig_audit_events", "idx_audit_ingest_event_uuid")
    );

    @Override
    public int getVersion() {
        return 18;
    }

    @Override
    public String getDescription() {
        return "Add idempotent queue event identities to native evidence ledgers";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        apply(connection, DatabaseDialect.fromConnection(connection));
    }

    @Override
    public void apply(Connection connection, DatabaseDialect dialect) throws SQLException {
        for (EventTable eventTable : EVENT_TABLES) {
            if (!MigrationSchema.hasColumn(connection, eventTable.table(), "ingest_event_uuid")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE " + eventTable.table()
                            + " ADD COLUMN ingest_event_uuid VARCHAR(36)");
                }
            }
            if (!indexExists(connection, dialect, eventTable.table(), eventTable.index())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE UNIQUE INDEX " + eventTable.index()
                            + " ON " + eventTable.table() + "(ingest_event_uuid)");
                }
            }
        }

        // V10/V11 used a shape-based observation key. Keep its target/action
        // coverage, but include the new event UUID so a genuinely distinct event
        // with the same entity and same-tick values is not ignored. Rebuild by
        // name on migration retry as MySQL/MariaDB DDL may have committed partly.
        if (dialect == DatabaseDialect.MYSQL_MARIADB
                && !MigrationSchema.hasColumn(connection, "ig_observations", "ig_ingest_event_uuid_dedup")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ig_observations ADD COLUMN "
                        + "ig_ingest_event_uuid_dedup VARCHAR(36) "
                        + "AS (COALESCE(ingest_event_uuid, '')) STORED");
            }
        }
        if (indexExists(connection, dialect, "ig_observations", "idx_obs_internal_dedup")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(dialect == DatabaseDialect.SQLITE
                        ? "DROP INDEX idx_obs_internal_dedup"
                        : "DROP INDEX idx_obs_internal_dedup ON ig_observations");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(dialect == DatabaseDialect.MYSQL_MARIADB
                    ? """
                        CREATE UNIQUE INDEX idx_obs_internal_dedup
                        ON ig_observations(source_type, timestamp_ms, node_id, ig_target_node_dedup,
                            fingerprint_id, amount, action_type, item_entity_uuid,
                            ig_internal_dedup_source, ig_ingest_event_uuid_dedup)
                        """
                    : """
                        CREATE UNIQUE INDEX idx_obs_internal_dedup
                        ON ig_observations(source_type, timestamp_ms, node_id,
                            COALESCE(target_node_id, -1), fingerprint_id, amount,
                            action_type, item_entity_uuid, COALESCE(ingest_event_uuid, ''))
                        WHERE source_event_id IS NULL
                        """);
        }
    }

    private static boolean indexExists(Connection connection, DatabaseDialect dialect,
                                       String table, String index) throws SQLException {
        String sql = dialect == DatabaseDialect.SQLITE
                ? "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?"
                : "SELECT 1 FROM information_schema.statistics "
                    + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (dialect == DatabaseDialect.SQLITE) {
                statement.setString(1, index);
            } else {
                statement.setString(1, table);
                statement.setString(2, index);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
