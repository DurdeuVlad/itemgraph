package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Adds an append-only link from retired block-interaction evidence to its break evidence. */
public final class V19__AuditEventSupersession implements SchemaMigration {
    @Override
    public int getVersion() {
        return 19;
    }

    @Override
    public String getDescription() {
        return "Retain block interaction evidence with explicit break supersession links";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        apply(connection, DatabaseDialect.fromConnection(connection));
    }

    @Override
    public void apply(Connection connection, DatabaseDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ig_audit_event_supersessions (
                        superseded_event_id BIGINT PRIMARY KEY,
                        superseding_event_id BIGINT NOT NULL,
                        reason_code TEXT NOT NULL,
                        created_at_ms BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ig_grieflogger_row_supersessions (
                        source_sha256 CHAR(64) NOT NULL,
                        table_name VARCHAR(64) NOT NULL,
                        source_key_hash CHAR(64) NOT NULL,
                        source_key LONGTEXT NOT NULL,
                        source_key_prefix VARCHAR(191) NOT NULL,
                        superseding_event_id BIGINT NOT NULL,
                        reason_code VARCHAR(64) NOT NULL,
                        created_at_ms BIGINT NOT NULL,
                        PRIMARY KEY (source_sha256, table_name, source_key_hash)
                    )
                    """);
        }
        if (!indexExists(connection, dialect, "idx_audit_event_supersessions_replacement",
                "ig_audit_event_supersessions")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX idx_audit_event_supersessions_replacement "
                        + "ON ig_audit_event_supersessions(superseding_event_id)");
            }
        }
        if (!indexExists(connection, dialect, "idx_gl_row_supersessions_lookup",
                "ig_grieflogger_row_supersessions")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX idx_gl_row_supersessions_lookup "
                        + "ON ig_grieflogger_row_supersessions(source_sha256, table_name, source_key_prefix)");
            }
        }
    }

    private static boolean indexExists(Connection connection, DatabaseDialect dialect,
                                       String indexName, String tableName) throws SQLException {
        String sql = dialect == DatabaseDialect.SQLITE
                ? "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?"
                : "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() "
                    + "AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (dialect == DatabaseDialect.SQLITE) {
                statement.setString(1, indexName);
            } else {
                statement.setString(1, tableName);
                statement.setString(2, indexName);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }
}
