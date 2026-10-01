package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.sql.SQLException;
import java.util.List;

/** Shared durable contract for the cross-loader successful source-fluid pickup replay. */
public final class BucketPickupConformanceFixture {
    private BucketPickupConformanceFixture() { }

    public static long auditWatermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
            if (!rows.next()) {
                throw new SQLException("SQLite did not return the ItemGraph audit watermark");
            }
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph audit watermark", failure);
        }
    }

    public static void assertPersisted(GameTestHelper helper, long priorAuditId, long priorObservationId,
                                       String playerUuid, BlockPos fluidPos) {
        assertNoFluidQuantity(helper, priorObservationId, playerUuid);
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, level_id, x, y, z, subject_id, detail
                     FROM ig_audit_events
                     WHERE id > ? AND player_uuid = ?
                       AND x = ? AND y = ? AND z = ?
                     ORDER BY id
                     """)) {
            statement.setLong(1, priorAuditId);
            statement.setString(2, playerUuid);
            statement.setDouble(3, fluidPos.getX());
            statement.setDouble(4, fluidPos.getY());
            statement.setDouble(5, fluidPos.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "successful water-bucket pickup must persist fluid evidence");
                helper.assertValueEqual("BREAK_BLOCK", rows.getString("event_type"),
                        "fluid pickup must preserve GriefLogger's source-fluid removal action");
                helper.assertValueEqual("minecraft:overworld", rows.getString("level_id"),
                        "fluid evidence dimension changed");
                helper.assertValueEqual((double) fluidPos.getX(), rows.getDouble("x"),
                        "fluid evidence X changed");
                helper.assertValueEqual((double) fluidPos.getY(), rows.getDouble("y"),
                        "fluid evidence Y changed");
                helper.assertValueEqual((double) fluidPos.getZ(), rows.getDouble("z"),
                        "fluid evidence Z changed");
                helper.assertValueEqual("minecraft:water", rows.getString("subject_id"),
                        "fluid evidence must name the captured source-fluid block");
                helper.assertTrue(rows.getString("detail") == null,
                        "fluid block evidence must not attach invented item or outcome data");
                helper.assertFalse(rows.next(),
                        "one successful bucket pickup must not duplicate fluid evidence");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph's fluid pickup evidence", failure);
        }
    }

    public static void assertNoAuditAt(GameTestHelper helper, long priorAuditId,
                                       String playerUuid, BlockPos fluidPos) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM ig_audit_events
                     WHERE id > ? AND player_uuid = ? AND x = ? AND y = ? AND z = ?
                     LIMIT 1
                     """)) {
            statement.setLong(1, priorAuditId);
            statement.setString(2, playerUuid);
            statement.setDouble(3, fluidPos.getX());
            statement.setDouble(4, fluidPos.getY());
            statement.setDouble(5, fluidPos.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertFalse(rows.next(), "empty bucket pickup result must not persist fluid evidence");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify empty bucket pickup was ignored", failure);
        }
    }

    public static void assertSubjectAt(GameTestHelper helper, long priorAuditId,
                                       String playerUuid, BlockPos fluidPos, String subjectId) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, subject_id FROM ig_audit_events
                     WHERE id > ? AND player_uuid = ? AND x = ? AND y = ? AND z = ?
                     ORDER BY id
                     """)) {
            statement.setLong(1, priorAuditId);
            statement.setString(2, playerUuid);
            statement.setDouble(3, fluidPos.getX());
            statement.setDouble(4, fluidPos.getY());
            statement.setDouble(5, fluidPos.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "bucket pickup result must persist fluid evidence at its source position");
                helper.assertValueEqual("BREAK_BLOCK", rows.getString("event_type"),
                        "bucket pickup result must remain block audit evidence");
                helper.assertValueEqual(subjectId, rows.getString("subject_id"),
                        "fluid evidence must use the returned bucket's fluid content");
                helper.assertFalse(rows.next(), "one bucket pickup result must not duplicate fluid evidence");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify returned bucket fluid content", failure);
        }
    }

    private static void assertNoFluidQuantity(GameTestHelper helper, long priorObservationId,
                                              String playerUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT observation.action_type
                     FROM ig_observations observation
                     LEFT JOIN ig_nodes source ON source.id = observation.node_id
                     LEFT JOIN ig_nodes target ON target.id = observation.target_node_id
                     WHERE observation.id > ?
                       AND (source.owner_uuid = ? OR target.owner_uuid = ?)
                       AND UPPER(observation.action_type) NOT IN ('THROW_ITEM', 'SHOOT_ITEM')
                     ORDER BY observation.id
                     """)) {
            statement.setLong(1, priorObservationId);
            statement.setString(2, playerUuid);
            statement.setString(3, playerUuid);
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(!rows.next(),
                        "bucket fluid pickup must not create a non-projectile quantity observation");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify no quantity was attributed to fluid pickup", failure);
        }
    }
}
