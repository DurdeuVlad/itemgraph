package com.itemgraph.canon;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;

/** Writes a bounded canonical component projection into ItemGraph-owned storage. */
public final class FingerprintComponentIndex {
    private FingerprintComponentIndex() {}

    public static void persist(Connection connection, long fingerprintId, CanonicalItem item)
            throws SQLException {
        if (connection == null || item == null) {
            throw new IllegalArgumentException("connection and canonical item are required");
        }
        if (item.searchableComponents().size() > 256) {
            throw new SQLException("canonical component projection exceeded the 256-row storage limit");
        }

        DatabaseDialect dialect = DatabaseDialect.fromConnection(connection);
        String insertPrefix = dialect == DatabaseDialect.SQLITE ? "INSERT OR IGNORE" : "INSERT IGNORE";
        try (PreparedStatement insert = connection.prepareStatement(insertPrefix + " INTO ig_fingerprint_components "
                + "(fingerprint_id, component_id, value_hash, canonical_value) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, String> component : item.searchableComponents().entrySet()) {
                String value = component.getValue();
                if (value.length() > 16_384) {
                    throw new SQLException("canonical component value exceeded the 16384-character storage limit");
                }
                insert.setLong(1, fingerprintId);
                insert.setString(2, component.getKey());
                insert.setString(3, ItemCanonicalizer.sha256Hex(value));
                insert.setString(4, value);
                insert.addBatch();
            }
            insert.executeBatch();
        }

        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE ig_item_fingerprints SET component_index_state = ?
                WHERE id = ? AND component_index_state = 'LEGACY_UNKNOWN'
                """)) {
            update.setString(1, item.componentIndexState());
            update.setLong(2, fingerprintId);
            update.executeUpdate();
        }
    }
}
