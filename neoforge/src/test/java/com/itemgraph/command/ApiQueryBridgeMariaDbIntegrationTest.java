package com.itemgraph.command;

import com.itemgraph.api.ItemQuery;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.ItemMetadataPredicate;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises API component-filter SQL on the network database engines used by CI. */
class ApiQueryBridgeMariaDbIntegrationTest {
    @Test
    void mariaDbApiComponentFilterResolvesOuterFingerprint() throws Exception {
        assertApiComponentFilterResolvesOuterFingerprint(
                "ITEMGRAPH_TEST_MARIADB_URL", "ITEMGRAPH_TEST_MARIADB_USER", "ITEMGRAPH_TEST_MARIADB_PASSWORD",
                "disable");
    }

    @Test
    void mysqlApiComponentFilterResolvesOuterFingerprint() throws Exception {
        assertApiComponentFilterResolvesOuterFingerprint(
                "ITEMGRAPH_TEST_MYSQL_URL", "ITEMGRAPH_TEST_MYSQL_USER", "ITEMGRAPH_TEST_MYSQL_PASSWORD",
                "trust");
    }

    private static void assertApiComponentFilterResolvesOuterFingerprint(
            String urlVariable, String userVariable, String passwordVariable, String sslMode) throws Exception {
        String url = System.getenv(urlVariable);
        assumeTrue(url != null && !url.isBlank(), "No endpoint configured for " + urlVariable);
        URI endpoint = URI.create(url.substring("jdbc:".length()));
        String databaseName = endpoint.getPath().replaceFirst("^/", "");
        DatabaseManager database = DatabaseManager.getInstance();
        List<Long> insertedFingerprints = new ArrayList<>();
        try {
            database.initialize(DatabaseSettings.mysqlMariaDb(endpoint.getHost(), endpoint.getPort(), databaseName,
                    System.getenv().getOrDefault(userVariable, "itemgraph"),
                    System.getenv().getOrDefault(passwordVariable, "itemgraph"), 5_000, true, sslMode));
            assertTrue(database.isInitialized(), "the CI endpoint should initialize ItemGraph storage: " + urlVariable);

            String fixture = UUID.randomUUID().toString().replace("-", "");
            String itemId = "itemgraph:api_filter_" + fixture;
            Connection connection = database.getConnection();
            long match = insertFingerprint(connection, itemId, fixture + "-match", "COMPLETE");
            insertedFingerprints.add(match);
            insertedFingerprints.add(insertFingerprint(connection, itemId, fixture + "-other", "COMPLETE"));
            String canonicalName = "\"Excalibur\"";
            try (PreparedStatement component = connection.prepareStatement("""
                    INSERT INTO ig_fingerprint_components
                        (fingerprint_id, component_id, value_hash, canonical_value)
                    VALUES (?, 'minecraft:custom_name#plain_text', ?, ?)
                    """)) {
                component.setLong(1, match);
                component.setString(2, ItemCanonicalizer.sha256Hex(canonicalName));
                component.setString(3, canonicalName);
                component.executeUpdate();
            }

            ItemQuery query = ItemQuery.itemId(itemId).withMetadata(List.of(new ItemMetadataPredicate(
                    ItemMetadataPredicate.Kind.CUSTOM_NAME,
                    "minecraft:custom_name#plain_text", "Excalibur")));
            List<FingerprintRef> candidates = ApiQueryBridge.resolveFingerprints(connection, query);
            assertEquals(1, candidates.size(), "the API query should return only the matching component fingerprint");
            assertEquals(match, candidates.getFirst().id());
            assertFalse(candidates.getFirst().componentIndexUnresolved());
        } finally {
            try {
                if (database.isInitialized() && !insertedFingerprints.isEmpty()) {
                    deleteFixture(database.getConnection(), insertedFingerprints);
                }
            } finally {
                database.close();
            }
        }
    }

    private static void deleteFixture(Connection connection, List<Long> fingerprintIds) throws Exception {
        try (PreparedStatement components = connection.prepareStatement(
                "DELETE FROM ig_fingerprint_components WHERE fingerprint_id = ?");
             PreparedStatement fingerprints = connection.prepareStatement(
                     "DELETE FROM ig_item_fingerprints WHERE id = ?")) {
            for (long id : fingerprintIds) {
                components.setLong(1, id);
                components.addBatch();
                fingerprints.setLong(1, id);
                fingerprints.addBatch();
            }
            components.executeBatch();
            fingerprints.executeBatch();
        }
    }

    private static long insertFingerprint(Connection connection, String itemId, String hashSuffix,
                                          String componentIndexState) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO ig_item_fingerprints
                    (item_id, fingerprint_hash, component_index_state)
                VALUES (?, ?, ?)
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, itemId);
            insert.setString(2, "api-component-" + hashSuffix);
            insert.setString(3, componentIndexState);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next(), "fingerprint insert should return its generated ID");
                return keys.getLong(1);
            }
        }
    }
}
