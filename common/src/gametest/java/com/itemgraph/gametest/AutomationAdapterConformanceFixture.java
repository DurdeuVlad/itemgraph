package com.itemgraph.gametest;

import com.itemgraph.api.AutomationEndpoint;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemGraphApi;
import com.itemgraph.api.ItemGraphService;
import com.itemgraph.api.RegistrationResult;
import com.itemgraph.api.RegistrationStatus;
import com.itemgraph.api.SourceHandle;
import com.itemgraph.api.SourceRegistration;
import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Shared durable assertions for native loader-adapter inventory replays. */
public final class AutomationAdapterConformanceFixture {
    public static final String TEST_MOD_ID = "itemgraph_gametest";
    public static final int MOVED_AMOUNT = 3;
    private static final long REGISTRATION_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    private AutomationAdapterConformanceFixture() { }

    public static void run(GameTestHelper helper, AdapterReplay replay) {
        ItemGraphService service = ItemGraphApi.get(helper.getLevel().getServer())
                .orElseThrow(() -> new AssertionError("ItemGraph API service is unavailable"));
        CompletableFuture<RegistrationResult> registering = service.registerSource(
                SourceRegistration.of(TEST_MOD_ID, "ItemGraph automation conformance fixture"));
        pollRegistration(helper, service, replay, registering,
                System.nanoTime() + REGISTRATION_TIMEOUT_NANOS, 1);
    }

    private static void pollRegistration(GameTestHelper helper, ItemGraphService service,
                                         AdapterReplay replay,
                                         CompletableFuture<RegistrationResult> registering,
                                         long deadlineNanos, int tick) {
        helper.runAtTickTime(tick, () -> {
            if (!registering.isDone()) {
                if (System.nanoTime() >= deadlineNanos) {
                    helper.fail("test integration source did not register within 30 seconds");
                    return;
                }
                pollRegistration(helper, service, replay, registering, deadlineNanos, tick + 1);
                return;
            }
            try {
                RegistrationResult result = registering.join();
                helper.assertTrue(result.status() == RegistrationStatus.REGISTERED
                                || result.status() == RegistrationStatus.UNCHANGED
                                || result.status() == RegistrationStatus.UPDATED,
                        "test consumer source registration failed: " + result.errorCode());
                BlockPos shulkerPos = helper.absolutePos(new BlockPos(3, 2, 2));
                helper.assertTrue(helper.getLevel().setBlock(shulkerPos, Blocks.SHULKER_BOX.defaultBlockState(), 3),
                        "could not place shulker-like destination fixture");
                if (!(helper.getLevel().getBlockEntity(shulkerPos) instanceof ShulkerBoxBlockEntity)) {
                    helper.fail("shulker-like destination block entity is missing");
                    return;
                }
                String inventoryId = "portable-backpack:conformance:" + UUID.randomUUID();
                AutomationEndpoint portable = AutomationEndpoint.externalInventory(TEST_MOD_ID,
                        inventoryId, "Fixture Backpack", "slot:0", "item");
                AutomationEndpoint shulker = AutomationEndpoint.blockInventory(TEST_MOD_ID,
                        "Fixture Shulker-like Storage", helper.getLevel().dimension(), shulkerPos,
                        "slot:0", Direction.WEST);
                long replayStartedAt = System.currentTimeMillis();
                replay.run(helper, service, result.source(), portable, shulker, shulkerPos);
                pollPersistence(helper, List.of(externalKey(portable.reference()),
                        externalKey(shulker.reference())), replayStartedAt, 2);
            } catch (RuntimeException failure) {
                helper.fail("could not run native automation adapter replay: " + failure);
            }
        });
    }

    private static void pollPersistence(GameTestHelper helper, List<String> endpointKeys,
                                        long replayStartedAt, int tick) {
        CompletableFuture<List<Row>> readback = CompletableFuture.supplyAsync(
                () -> readRows(endpointKeys, replayStartedAt));
        pollReadback(helper, endpointKeys, replayStartedAt, readback, tick);
    }

    private static void pollReadback(GameTestHelper helper, List<String> endpointKeys, long replayStartedAt,
                                     CompletableFuture<List<Row>> readback, int tick) {
        helper.runAtTickTime(tick, () -> {
            if (!readback.isDone()) {
                if (tick >= 160) {
                    helper.fail("native adapter evidence readback timed out");
                    return;
                }
                pollReadback(helper, endpointKeys, replayStartedAt, readback, tick + 1);
                return;
            }
            try {
                List<Row> rows = readback.join();
                if (rows.size() < 2 && tick < 160) {
                    helper.runAtTickTime(1,
                            () -> pollPersistence(helper, endpointKeys, replayStartedAt, tick + 1));
                    return;
                }
                helper.assertValueEqual(2, rows.size(), "both native endpoint adapters must persist evidence");
                var observedEndpoints = new java.util.HashSet<String>();
                for (Row row : rows) {
                    helper.assertValueEqual("TRANSFER_ITEM", row.action(), "automation action differs");
                    helper.assertValueEqual(MOVED_AMOUNT, row.amount(), "only the committed quantity may be recorded");
                    helper.assertValueEqual("minecraft:nether_star", row.itemId(), "portable item fingerprint differs");
                    helper.assertTrue(row.rawData().contains("\"moved_amount\":\"3\""),
                            "raw evidence must retain the committed quantity");
                    helper.assertTrue(row.rawData().contains("\"endpoint_slot_policy\":\"slot:0\""),
                            "raw evidence must retain the exact endpoint slot");
                    boolean originIsReplayEndpoint = row.originExternalKey() != null
                            && endpointKeys.contains(row.originExternalKey());
                    boolean destinationIsReplayEndpoint = row.destinationExternalKey() != null
                            && endpointKeys.contains(row.destinationExternalKey());
                    helper.assertTrue(originIsReplayEndpoint || destinationIsReplayEndpoint,
                            "the observed endpoint must be one of the replay stores");
                    if (originIsReplayEndpoint) observedEndpoints.add(row.originExternalKey());
                    if (destinationIsReplayEndpoint) observedEndpoints.add(row.destinationExternalKey());
                    helper.assertTrue(row.originOwner() == null && row.destinationOwner() == null,
                            "automation evidence must not invent a player owner");
                    if (row.originExternalKey() != null || row.destinationExternalKey() != null) {
                        helper.assertTrue(row.originX() == null && row.destinationX() == null,
                                "portable and hashed block endpoints must not persist coordinates");
                    }
                }
                helper.assertTrue(observedEndpoints.containsAll(endpointKeys),
                        "both portable and shulker endpoint IDs must appear in persisted evidence");
                helper.succeed();
            } catch (RuntimeException failure) {
                helper.fail("could not verify durable native adapter evidence: " + failure);
            }
        });
    }

    private static List<Row> readRows(List<String> endpointKeys, long replayStartedAt) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT observation.action_type, observation.amount, fingerprint.item_id,
                            CAST(observation.raw_data AS TEXT), origin.external_key, destination.external_key,
                            origin.owner_uuid, destination.owner_uuid, origin.x, destination.x
                     FROM ig_observations observation
                     JOIN ig_nodes origin ON origin.id = observation.node_id
                     JOIN ig_nodes destination ON destination.id = observation.target_node_id
                     JOIN ig_item_fingerprints fingerprint ON fingerprint.id = observation.fingerprint_id
                     WHERE observation.source_type = ? AND observation.timestamp_ms >= ?
                       AND (origin.external_key = ? OR destination.external_key = ?
                            OR origin.external_key = ? OR destination.external_key = ?)
                     """)) {
            statement.setString(1, "EXTERNAL_API:" + TEST_MOD_ID);
            statement.setLong(2, replayStartedAt);
            statement.setString(3, endpointKeys.getFirst());
            statement.setString(4, endpointKeys.getFirst());
            statement.setString(5, endpointKeys.get(1));
            statement.setString(6, endpointKeys.get(1));
            try (var rows = statement.executeQuery()) {
                var result = new java.util.ArrayList<Row>();
                while (rows.next()) {
                    result.add(new Row(rows.getString(1), rows.getInt(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6),
                            rows.getString(7), rows.getString(8),
                            nullableInteger(rows, 9), nullableInteger(rows, 10)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read persisted native automation evidence", failure);
        }
    }

    private static Integer nullableInteger(java.sql.ResultSet rows, int index) throws SQLException {
        int value = rows.getInt(index);
        return rows.wasNull() ? null : value;
    }

    private static String externalKey(ExternalInventoryEndpoint endpoint) {
        return endpoint.ownerModId() + "/" + endpoint.inventoryId();
    }

    @FunctionalInterface
    public interface AdapterReplay {
        void run(GameTestHelper helper, ItemGraphService service, SourceHandle source,
                 AutomationEndpoint portable, AutomationEndpoint shulker, BlockPos shulkerPos);
    }

    private record Row(String action, int amount, String itemId, String rawData,
                       String originExternalKey, String destinationExternalKey,
                       String originOwner, String destinationOwner,
                       Integer originX, Integer destinationX) { }
}
