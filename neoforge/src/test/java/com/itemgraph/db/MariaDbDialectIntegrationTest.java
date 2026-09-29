package com.itemgraph.db;

import com.itemgraph.db.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared migration and JDBC translation contract against disposable
 * MySQL/MariaDB services supplied by CI. The test is skipped for ordinary local
 * runs unless an endpoint environment variable is present.
 */
class MariaDbDialectIntegrationTest {
    @Test
    void migrationAndBasicEvidenceContractRunsOnConfiguredServers() throws Exception {
        List<Endpoint> endpoints = new ArrayList<>();
        addEndpoint(endpoints, "ITEMGRAPH_TEST_MARIADB_URL", "ITEMGRAPH_TEST_MARIADB_USER", "ITEMGRAPH_TEST_MARIADB_PASSWORD");
        addEndpoint(endpoints, "ITEMGRAPH_TEST_MYSQL_URL", "ITEMGRAPH_TEST_MYSQL_USER", "ITEMGRAPH_TEST_MYSQL_PASSWORD");
        assumeTrue(!endpoints.isEmpty(), "No disposable MySQL/MariaDB endpoint configured");

        for (Endpoint endpoint : endpoints) {
            runContract(endpoint);
        }
    }

    private static void addEndpoint(List<Endpoint> endpoints, String urlKey, String userKey, String passwordKey) {
        String url = System.getenv(urlKey);
        if (url != null && !url.isBlank()) {
            endpoints.add(new Endpoint(url, System.getenv().getOrDefault(userKey, "itemgraph"),
                    System.getenv().getOrDefault(passwordKey, "itemgraph")));
        }
    }

    private static void runContract(Endpoint endpoint) throws Exception {
        try (Connection raw = DriverManager.getConnection(endpoint.url(), endpoint.user(), endpoint.password());
             Connection conn = DialectConnection.wrap(raw, DatabaseDialect.MYSQL_MARIADB)) {
            assertEquals(DatabaseDialect.MYSQL_MARIADB, DatabaseDialect.fromConnection(conn));
            assertEquals(15, MigrationRunner.runMigrations(conn, DatabaseDialect.MYSQL_MARIADB));
            assertEquals(15, MigrationRunner.runMigrations(conn, DatabaseDialect.MYSQL_MARIADB));

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                         + "WHERE table_schema = DATABASE() AND table_name = 'ig_observations'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("INSERT IGNORE INTO ig_nodes (id, node_type, level_id) "
                        + "VALUES (900001, 'PLAYER', 'minecraft:overworld')");
                stmt.executeUpdate("INSERT IGNORE INTO ig_item_fingerprints (id, item_id, fingerprint_hash) "
                        + "VALUES (900001, 'minecraft:diamond', 'dialect-test-hash')");
                stmt.executeUpdate("INSERT IGNORE INTO ig_observations "
                        + "(source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount) "
                        + "VALUES ('MYSQL_DIALECT_TEST', 900001, 1000, 900001, 900001, 'DROP_ITEM', 1)");
            }

            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO ig_source_checkpoints "
                    + "(source_name, last_source_rowid, last_timestamp, updated_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT(source_name) DO UPDATE SET last_source_rowid = excluded.last_source_rowid")) {
                stmt.setString(1, "dialect-test");
                stmt.setLong(2, 2);
                stmt.setLong(3, 3);
                stmt.setLong(4, 4);
                stmt.executeUpdate();
            }
        }
    }

    private record Endpoint(String url, String user, String password) {
    }
}
