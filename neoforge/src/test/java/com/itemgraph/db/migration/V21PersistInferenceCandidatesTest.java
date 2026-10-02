package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V21PersistInferenceCandidatesTest {
    @Test
    void addsCandidateColumnsAndCanBeAppliedAgain() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE ig_inferred_edges (id INTEGER PRIMARY KEY, explanation TEXT)");
            }

            V21__PersistInferenceCandidates migration = new V21__PersistInferenceCandidates();
            migration.apply(connection, DatabaseDialect.SQLITE);
            migration.apply(connection, DatabaseDialect.SQLITE);

            try (Statement statement = connection.createStatement();
                 ResultSet columns = statement.executeQuery("PRAGMA table_info(ig_inferred_edges)")) {
                boolean idsFound = false;
                boolean truncatedFound = false;
                while (columns.next()) {
                    idsFound |= "competing_observation_ids".equals(columns.getString("name"));
                    truncatedFound |= "competing_candidates_truncated".equals(columns.getString("name"));
                }
                assertTrue(idsFound, "candidate IDs should be persisted on inferred edges");
                assertTrue(truncatedFound, "candidate truncation should be persisted on inferred edges");
            }
            assertEquals(21, migration.getVersion());
        }
    }
}
