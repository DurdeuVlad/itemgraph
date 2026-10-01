package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only durable contract for paired item/container/ground movement replay. */
public final class ItemMovementConformanceFixture {
    private ItemMovementConformanceFixture() { }

    public static long observationWatermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_observations")) {
            if (!rows.next()) {
                throw new SQLException("SQLite did not return the ItemGraph observation watermark");
            }
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph observation watermark", failure);
        }
    }

    public static List<List<String>> snapshotRows() {
        return EntityInteractionConformanceFixture.snapshotQuantityObservations();
    }

    public static void assertPersisted(GameTestHelper helper, long priorObservationId,
                                       List<List<String>> priorRows, String playerUuid,
                                       BlockPos containerPos, String expectedItemEntityUuid) {
        List<List<String>> currentRows = snapshotRows();
        helper.assertTrue(currentRows.containsAll(priorRows),
                "item movement replay must not mutate or remove earlier quantity evidence");

        assertOnlyMovementObservations(helper, priorObservationId, playerUuid);
        Map<String, MovementRow> rows = readMovementRows(priorObservationId, playerUuid);
        helper.assertValueEqual(List.of("ADD_ITEM", "DROP_ITEM", "PICKUP_ITEM", "REMOVE_ITEM"),
                rows.keySet().stream().sorted().toList(),
                "container deposits/withdrawals and one dropped-then-picked-up stack require four distinct rows");
        assertRow(helper, rows, "ADD_ITEM", "minecraft:dirt", 2, "PLAYER", "CONTAINER",
                playerUuid, containerPos);
        assertRow(helper, rows, "REMOVE_ITEM", "minecraft:cobblestone", 3, "CONTAINER", "PLAYER",
                playerUuid, containerPos);
        MovementRow drop = rows.get("DROP_ITEM");
        MovementRow pickup = rows.get("PICKUP_ITEM");
        helper.assertTrue(drop != null, "accepted player drop must persist DROP_ITEM");
        helper.assertTrue(pickup != null, "successful ground pickup must persist PICKUP_ITEM");
        assertItemAndCount(helper, drop, "minecraft:diamond", 4, "DROP_ITEM");
        assertItemAndCount(helper, pickup, "minecraft:diamond", 4, "PICKUP_ITEM");
        assertEndpointTypes(helper, drop, "PLAYER", "GROUND", playerUuid, "DROP_ITEM");
        assertEndpointTypes(helper, pickup, "GROUND", "PLAYER", playerUuid, "PICKUP_ITEM");
        helper.assertTrue(drop.itemEntityUuid() != null && !drop.itemEntityUuid().isBlank(),
                "DROP_ITEM must retain the ground entity UUID");
        helper.assertValueEqual(expectedItemEntityUuid, drop.itemEntityUuid(),
                "DROP_ITEM must reference the exact ItemEntity created by the replay");
        helper.assertValueEqual(drop.itemEntityUuid(), pickup.itemEntityUuid(),
                "PICKUP_ITEM must reference the same ground entity as its preceding drop");
        helper.assertValueEqual(expectedItemEntityUuid, pickup.itemEntityUuid(),
                "PICKUP_ITEM must reference the exact ItemEntity created by the replay");
        helper.assertTrue(drop.ingestEventUuid() != null && !drop.ingestEventUuid().isBlank()
                        && pickup.ingestEventUuid() != null && !pickup.ingestEventUuid().isBlank(),
                "drop and pickup must each persist a durable observation identity");
        helper.assertTrue(!drop.ingestEventUuid().equals(pickup.ingestEventUuid()),
                "drop and pickup are distinct events even when they share a ground entity");
        assertGroundContinuity(helper, drop, pickup);
        helper.assertValueEqual(drop.fingerprintHash(), pickup.fingerprintHash(),
                "drop and pickup must preserve the same canonical item fingerprint");
        for (String action : List.of("ADD_ITEM", "REMOVE_ITEM")) {
            MovementRow row = rows.get(action);
            helper.assertTrue(row.rawData().contains("container_session_net_delta"),
                    action + " must identify its interval-bounded session evidence");
            helper.assertTrue(row.timestampEndMs() != null && row.timestampEndMs() >= row.timestampMs(),
                    action + " must retain a non-negative session interval");
        }
    }

    private static void assertRow(GameTestHelper helper, Map<String, MovementRow> rows,
                                  String action, String itemId, int amount,
                                  String sourceType, String targetType, String playerUuid,
                                  BlockPos containerPos) {
        MovementRow row = rows.get(action);
        helper.assertTrue(row != null, "missing persisted " + action + " row");
        assertItemAndCount(helper, row, itemId, amount, action);
        assertEndpointTypes(helper, row, sourceType, targetType, playerUuid, action);
        helper.assertValueEqual((double) containerPos.getX(),
                "CONTAINER".equals(sourceType) ? row.sourceX() : row.targetX(),
                action + " container X coordinate changed");
        helper.assertValueEqual((double) containerPos.getY(),
                "CONTAINER".equals(sourceType) ? row.sourceY() : row.targetY(),
                action + " container Y coordinate changed");
        helper.assertValueEqual((double) containerPos.getZ(),
                "CONTAINER".equals(sourceType) ? row.sourceZ() : row.targetZ(),
                action + " container Z coordinate changed");
        helper.assertValueEqual("minecraft:overworld",
                "CONTAINER".equals(sourceType) ? row.sourceLevel() : row.targetLevel(),
                action + " container dimension changed");
    }

    private static void assertItemAndCount(GameTestHelper helper, MovementRow row,
                                          String itemId, int amount, String action) {
        helper.assertValueEqual(itemId, row.itemId(), action + " item registry ID changed");
        helper.assertValueEqual(amount, row.amount(), action + " moved quantity changed");
        helper.assertTrue(row.fingerprintHash() != null && !row.fingerprintHash().isBlank(),
                action + " must retain canonical metadata fingerprint");
    }

    private static void assertEndpointTypes(GameTestHelper helper, MovementRow row,
                                            String sourceType, String targetType,
                                            String playerUuid, String action) {
        helper.assertValueEqual(sourceType, row.sourceType(), action + " source endpoint changed");
        helper.assertValueEqual(targetType, row.targetType(), action + " target endpoint changed");
        if ("PLAYER".equals(sourceType)) {
            helper.assertValueEqual(playerUuid, row.sourceOwnerUuid(), action + " source player changed");
        }
        if ("PLAYER".equals(targetType)) {
            helper.assertValueEqual(playerUuid, row.targetOwnerUuid(), action + " target player changed");
        }
    }

    private static void assertGroundContinuity(GameTestHelper helper, MovementRow drop, MovementRow pickup) {
        helper.assertValueEqual(drop.targetLevel(), pickup.sourceLevel(),
                "drop and pickup must remain in the same dimension");
        double blockDistance = Math.abs(drop.targetX() - pickup.sourceX())
                + Math.abs(drop.targetY() - pickup.sourceY())
                + Math.abs(drop.targetZ() - pickup.sourceZ());
        helper.assertTrue(blockDistance <= 1.0,
                "drop and pickup ground endpoints must remain within one block for the same ItemEntity; distance="
                        + blockDistance);
    }

    private static void assertOnlyMovementObservations(GameTestHelper helper, long watermark, String playerUuid) {
        String sql = """
                SELECT o.action_type, o.source_type
                FROM ig_observations o
                LEFT JOIN ig_nodes source ON source.id = o.node_id
                LEFT JOIN ig_nodes target ON target.id = o.target_node_id
                WHERE o.id > ? AND (source.owner_uuid = ? OR target.owner_uuid = ?)
                ORDER BY o.id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            statement.setString(3, playerUuid);
            try (var result = statement.executeQuery()) {
                List<String> actions = new ArrayList<>();
                while (result.next()) {
                    actions.add(result.getString("action_type"));
                    helper.assertValueEqual("ITEMGRAPH_INTERNAL", result.getString("source_type"),
                            "movement replay must not create a non-native observation for its player");
                }
                List<String> expected = List.of("ADD_ITEM", "DROP_ITEM", "PICKUP_ITEM", "REMOVE_ITEM");
                helper.assertValueEqual(expected, actions.stream().sorted().toList(),
                        "the replay player must have exactly the four expected new quantity observations");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify the movement player's complete observation scope", failure);
        }
    }

    private static Map<String, MovementRow> readMovementRows(long watermark, String playerUuid) {
        String sql = """
                SELECT o.action_type, o.amount, o.item_entity_uuid,
                       o.timestamp_ms, o.timestamp_end_ms, o.raw_data, o.ingest_event_uuid,
                       fingerprint.item_id, fingerprint.fingerprint_hash,
                       source.node_type AS source_type,
                       source.owner_uuid AS source_owner_uuid, source.level_id AS source_level,
                       source.x AS source_x, source.y AS source_y, source.z AS source_z,
                       target.node_type AS target_type,
                       target.owner_uuid AS target_owner_uuid, target.level_id AS target_level,
                       target.x AS target_x, target.y AS target_y, target.z AS target_z
                FROM ig_observations o
                JOIN ig_nodes source ON source.id = o.node_id
                JOIN ig_nodes target ON target.id = o.target_node_id
                JOIN ig_item_fingerprints fingerprint ON fingerprint.id = o.fingerprint_id
                WHERE o.id > ? AND o.source_type = 'ITEMGRAPH_INTERNAL'
                  AND o.action_type IN ('ADD_ITEM', 'REMOVE_ITEM', 'DROP_ITEM', 'PICKUP_ITEM')
                  AND (source.owner_uuid = ? OR target.owner_uuid = ?)
                ORDER BY o.id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            statement.setString(3, playerUuid);
            try (var result = statement.executeQuery()) {
                Map<String, MovementRow> rows = new LinkedHashMap<>();
                while (result.next()) {
                    long end = result.getLong("timestamp_end_ms");
                    Long endValue = result.wasNull() ? null : end;
                    byte[] raw = result.getBytes("raw_data");
                    MovementRow row = new MovementRow(
                            result.getString("action_type"), result.getInt("amount"),
                            result.getString("item_entity_uuid"), result.getLong("timestamp_ms"), endValue,
                            raw == null ? "" : new String(raw, StandardCharsets.UTF_8),
                            result.getString("ingest_event_uuid"),
                            result.getString("item_id"), result.getString("fingerprint_hash"),
                            result.getString("source_type"),
                            result.getString("source_owner_uuid"), result.getString("source_level"),
                            result.getObject("source_x", Double.class),
                            result.getObject("source_y", Double.class),
                            result.getObject("source_z", Double.class),
                            result.getString("target_type"),
                            result.getString("target_owner_uuid"), result.getString("target_level"),
                            result.getObject("target_x", Double.class),
                            result.getObject("target_y", Double.class),
                            result.getObject("target_z", Double.class));
                    if (rows.putIfAbsent(row.actionType(), row) != null) {
                        throw new IllegalStateException("Duplicate item movement row: " + row.actionType());
                    }
                }
                return rows;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph item movement observations", failure);
        }
    }

    private record MovementRow(String actionType, int amount, String itemEntityUuid,
                               long timestampMs, Long timestampEndMs,
                               String rawData, String ingestEventUuid, String itemId, String fingerprintHash,
                               String sourceType, String sourceOwnerUuid,
                               String sourceLevel, Double sourceX, Double sourceY, Double sourceZ,
                               String targetType, String targetOwnerUuid,
                               String targetLevel, Double targetX, Double targetY, Double targetZ) { }
}
