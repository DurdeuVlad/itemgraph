package com.itemgraph.db.migration;

import com.itemgraph.db.DatabaseDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/** Keeps pre-use armor-stand rows as raw evidence while removing their movement claims. */
public final class V20__UnverifiedArmorStandInteractionEvidence implements SchemaMigration {
    public static final String REASON_CODE = "PRE_USE_ARMOR_STAND_TRANSFER_UNVERIFIED";

    @Override
    public int getVersion() {
        return 20;
    }

    @Override
    public String getDescription() {
        return "Quarantine pre-use armor-stand click rows and supersede dependent quantity edges";
    }

    @Override
    public void apply(Connection connection) throws SQLException {
        apply(connection, DatabaseDialect.fromConnection(connection));
    }

    @Override
    public void apply(Connection connection, DatabaseDialect dialect) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ig_observation_dispositions (
                        observation_id BIGINT PRIMARY KEY REFERENCES ig_observations(id),
                        reason_code VARCHAR(64) NOT NULL,
                        detail TEXT NOT NULL,
                        created_at_ms BIGINT NOT NULL
                    )
                    """);
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ig_observation_dispositions (observation_id, reason_code, detail, created_at_ms)
                SELECT o.id, ?, ?, ?
                FROM ig_observations o
                WHERE o.source_type = 'ITEMGRAPH_INTERNAL'
                  AND UPPER(o.action_type) IN ('EQUIP_ARMOR_STAND', 'UNEQUIP_ARMOR_STAND')
                  AND NOT EXISTS (
                      SELECT 1 FROM ig_observation_dispositions d WHERE d.observation_id = o.id
                  )
                """)) {
            statement.setString(1, REASON_CODE);
            statement.setString(2,
                    "Legacy pre-use interaction callback did not prove that an item moved; "
                            + "raw event retained, excluded from current item-flow evidence.");
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE ig_observations
                    SET correlation_status = 'CLOSED_UNRESOLVED'
                    WHERE id IN (SELECT observation_id FROM ig_observation_dispositions
                                 WHERE reason_code = 'PRE_USE_ARMOR_STAND_TRANSFER_UNVERIFIED')
                    """);
            statement.executeUpdate("""
                    UPDATE ig_inferred_edges
                    SET edge_state = 'SUPERSEDED_UNVERIFIED_EVIDENCE'
                    WHERE edge_state = 'ACTIVE'
                      AND id IN (
                          SELECT allocation.edge_id
                          FROM ig_edge_allocations allocation
                          JOIN ig_observation_dispositions disposition
                            ON disposition.observation_id = allocation.observation_id
                          WHERE disposition.reason_code = 'PRE_USE_ARMOR_STAND_TRANSFER_UNVERIFIED'
                      )
                    """);
        }
    }
}
