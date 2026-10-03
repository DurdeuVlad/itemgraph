package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.gametest.framework.GameTestHelper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Shared durable expectations for command packets executed by both loader servers. */
public final class CommandPacketConformanceFixture {
    private static final List<String> EXPECTED_COMMANDS = List.of(
            "itemgraph inspect on",
            "ig inspect status",
            "ig inspect off");

    private CommandPacketConformanceFixture() { }

    public static long auditWatermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the ItemGraph command audit watermark", e);
        }
    }

    public static long observationWatermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_observations")) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read the ItemGraph quantity observation watermark", failure);
        }
    }

    public static List<List<String>> snapshotQuantityObservations() {
        return EntityInteractionConformanceFixture.snapshotQuantityObservations();
    }

    public static void assertPersisted(GameTestHelper helper, long watermark, long observationWatermark,
                                       String playerUuid, String playerName,
                                       List<List<String>> observationsBefore) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, event_type, player_name, detail, timestamp_ms
                     FROM ig_audit_events
                     WHERE id > ? AND player_uuid = ? AND event_type = 'COMMAND_ATTEMPT'
                     ORDER BY id
                     """)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            try (var rows = statement.executeQuery()) {
                List<String> actualCommands = new ArrayList<>();
                int count = 0;
                while (rows.next()) {
                    count++;
                    helper.assertValueEqual("COMMAND_ATTEMPT", rows.getString("event_type"),
                            "a command packet must not persist another audit event type");
                    helper.assertValueEqual(playerName, rows.getString("player_name"),
                            "command evidence must remain attributed to the sending player");
                    String detail = rows.getString("detail");
                    helper.assertTrue(detail != null, "command attempt must retain the command text");
                    actualCommands.add(detail);
                    helper.assertTrue(rows.getLong("timestamp_ms") > 0,
                            "command attempt must retain its event timestamp");
                    helper.assertTrue(rows.getLong("id") > watermark,
                            "command packet evidence must be newer than the fixture watermark");
                }
                helper.assertValueEqual(EXPECTED_COMMANDS, actualCommands,
                        "packet-dispatched command records differ from the cross-loader fixture");
                helper.assertValueEqual(EXPECTED_COMMANDS.size(), count,
                        "a command packet must persist exactly one attempt row");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not query the ItemGraph command conformance fixture", e);
        }

        EntityInteractionConformanceFixture.assertQuantityObservationsUnchanged(helper, observationsBefore);
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ig_observations obs
                     WHERE obs.id > ? AND (
                         EXISTS (SELECT 1 FROM ig_nodes source WHERE source.id = obs.node_id
                                 AND source.owner_uuid = ?)
                         OR EXISTS (SELECT 1 FROM ig_nodes target WHERE target.id = obs.target_node_id
                                    AND target.owner_uuid = ?))
                     """)) {
            statement.setLong(1, observationWatermark);
            statement.setString(2, playerUuid);
            statement.setString(3, playerUuid);
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "could not count quantity evidence from the command sender");
                helper.assertValueEqual(0, rows.getInt(1),
                        "command packets must not create quantity evidence for the sending player");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify command packet quantity evidence", failure);
        }
    }
}
