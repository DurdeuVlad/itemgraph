package com.itemgraph.db.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;

public class MigrationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationRunner.class);

    private static final List<SchemaMigration> MIGRATIONS = List.of(
            new V1__InitialSchema(),
            new V2__DeduplicationConstraint(),
            new V3__ResetForCanonicalization(),
            new V4__ItemFlowTopology()
    );

    public static int runMigrations(Connection conn) throws SQLException {
        ensureMigrationTable(conn);
        int currentVersion = getCurrentVersion(conn);
        LOGGER.info("Current ItemGraph schema version: {}", currentVersion);

        final int startingVersion = currentVersion;
        List<SchemaMigration> pending = MIGRATIONS.stream()
                .filter(m -> m.getVersion() > startingVersion)
                .sorted(Comparator.comparingInt(SchemaMigration::getVersion))
                .toList();

        if (pending.isEmpty()) {
            LOGGER.info("ItemGraph schema is up to date (version {}).", currentVersion);
            return currentVersion;
        }

        boolean originalAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            for (SchemaMigration migration : pending) {
                LOGGER.info("Applying ItemGraph migration v{}: {}", migration.getVersion(), migration.getDescription());
                migration.apply(conn);
                recordMigration(conn, migration);
                conn.commit();
                currentVersion = migration.getVersion();
                LOGGER.info("Successfully applied ItemGraph migration v{}.", currentVersion);
            }
        } catch (SQLException e) {
            conn.rollback();
            LOGGER.error("Failed to apply ItemGraph migration at version {}. Rolling back.", currentVersion, e);
            throw e;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
        }

        return currentVersion;
    }

    private static void ensureMigrationTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ig_schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at INTEGER NOT NULL,
                    description TEXT NOT NULL
                )
            """);
        }
    }

    public static int getCurrentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM ig_schema_migrations")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        }
    }

    private static void recordMigration(Connection conn, SchemaMigration migration) throws SQLException {
        String sql = "INSERT INTO ig_schema_migrations (version, applied_at, description) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, migration.getVersion());
            pstmt.setLong(2, System.currentTimeMillis());
            pstmt.setString(3, migration.getDescription());
            pstmt.executeUpdate();
        }
    }
}
