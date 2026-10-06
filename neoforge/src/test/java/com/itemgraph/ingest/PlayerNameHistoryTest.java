package com.itemgraph.ingest;

import com.itemgraph.db.migration.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNameHistoryTest {
    @Test
    void retainsDistinctNamesByUuidAndKeepsFirstAndLastSeenTimes() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MigrationRunner.runMigrations(connection);

            PlayerNameHistory.recordJoin(connection, "uuid-a", "OldName", 100L);
            PlayerNameHistory.recordJoin(connection, "uuid-a", "NewName", 200L);
            PlayerNameHistory.recordJoin(connection, "uuid-a", "oldname", 300L);
            PlayerNameHistory.recordJoin(connection, "uuid-a", "OldName", 150L);
            PlayerNameHistory.recordJoin(connection, "uuid-b", "OldName", 400L);

            assertRow(connection, "uuid-a", "oldname", "oldname", 100L, 300L);
            assertRow(connection, "uuid-a", "newname", "NewName", 200L, 200L);
            assertRow(connection, "uuid-b", "oldname", "OldName", 400L, 400L);
            assertEquals(3, scalar(connection, "SELECT COUNT(*) FROM ig_player_name_history"));
        }
    }

    @Test
    void ignoresMissingAndOverlongIdentityValues() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MigrationRunner.runMigrations(connection);
            PlayerNameHistory.recordJoin(connection, null, "Name", 100L);
            PlayerNameHistory.recordJoin(connection, "uuid-a", " ", 100L);
            PlayerNameHistory.recordJoin(connection, "uuid-a", "a".repeat(17), 100L);

            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM ig_player_name_history"));
        }
    }

    @Test
    void migrationBackfillsNativeNamesFromImmutableJoinEvidence() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            MigrationRunner.runMigrations(connection);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM ig_player_name_history");
                statement.executeUpdate("DELETE FROM ig_schema_migrations WHERE version = 21");
                statement.executeUpdate("""
                        INSERT INTO ig_audit_events (
                            event_type, timestamp_ms, player_uuid, player_name, level_id,
                            source_type, ingest_event_uuid
                        ) VALUES
                            ('PLAYER_JOIN', 100, 'uuid-a', 'OldName', 'minecraft:overworld',
                             'ITEMGRAPH_INTERNAL', 'join-old'),
                            ('PLAYER_JOIN', 200, 'uuid-a', 'NewName', 'minecraft:overworld',
                             'ITEMGRAPH_INTERNAL', 'join-new'),
                            ('PLAYER_JOIN', 300, 'uuid-a', 'oldname', 'minecraft:overworld',
                             'ITEMGRAPH_INTERNAL', 'join-old-case')
                        """);
            }

            assertEquals(MigrationRunner.LATEST_VERSION, MigrationRunner.runMigrations(connection));
            assertRow(connection, "uuid-a", "oldname", "oldname", 100L, 300L);
            assertRow(connection, "uuid-a", "newname", "NewName", 200L, 200L);
        }
    }

    private static void assertRow(Connection connection, String uuid, String normalizedName,
                                  String displayName, long firstSeen, long lastSeen) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("""
                SELECT player_name, first_seen_ms, last_seen_ms
                FROM ig_player_name_history
                WHERE player_uuid = ? AND normalized_name = ?
                """)) {
            query.setString(1, uuid);
            query.setString(2, normalizedName);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(displayName, row.getString(1));
                assertEquals(firstSeen, row.getLong(2));
                assertEquals(lastSeen, row.getLong(3));
                assertFalse(row.next());
            }
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getInt(1);
        }
    }
}
