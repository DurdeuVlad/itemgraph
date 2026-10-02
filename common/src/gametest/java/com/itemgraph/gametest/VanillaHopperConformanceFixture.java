package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Durable cross-loader replay for one vanilla chest-to-hopper transfer. */
public final class VanillaHopperConformanceFixture {
    private VanillaHopperConformanceFixture() { }

    public static void run(GameTestHelper helper) {
        BlockPos sourcePos = helper.absolutePos(new BlockPos(2, 3, 2));
        BlockPos hopperPos = sourcePos.below();
        long priorId = observationWatermark();
        helper.assertTrue(helper.getLevel().setBlock(sourcePos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place hopper source chest");
        helper.assertTrue(helper.getLevel().setBlock(hopperPos, Blocks.HOPPER.defaultBlockState(), 3),
                "could not place vanilla hopper");
        ChestBlockEntity chest = helper.getLevel().getBlockEntity(sourcePos, BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("placed hopper source chest is missing"));
        HopperBlockEntity hopper = helper.getLevel().getBlockEntity(hopperPos, BlockEntityType.HOPPER)
                .orElseThrow(() -> new AssertionError("placed vanilla hopper block entity is missing"));
        chest.setItem(0, new ItemStack(Items.DIRT, 1));
        chest.setChanged();

        helper.runAtTickTime(32, () -> {
            helper.assertTrue(chest.getItem(0).isEmpty(), "vanilla hopper must extract the source dirt");
            helper.assertTrue(hopper.getItem(0).is(Items.DIRT) && hopper.getItem(0).getCount() == 1,
                    "vanilla hopper must retain exactly the one extracted dirt");
            awaitRows(helper, priorId, sourcePos, hopperPos, 40, new AtomicReference<>());
        });
    }

    private record Row(String action, int amount, String itemId,
                       int x, int y, int z, String knownEndpointType, String unknownEndpointType) { }

    private static void awaitRows(GameTestHelper helper, long priorId, BlockPos sourcePos,
                                  BlockPos hopperPos, int tick,
                                  AtomicReference<CompletableFuture<List<Row>>> pending) {
        helper.runAtTickTime(tick, () -> {
            if (InternalObservationService.getInstance().getQueueSize() != 0) {
                retry(helper, priorId, sourcePos, hopperPos, tick, pending);
                return;
            }
            if (pending.get() == null) {
                pending.set(CompletableFuture.supplyAsync(() -> readRows(priorId, sourcePos, hopperPos)));
            }
            CompletableFuture<List<Row>> read = pending.get();
            if (!read.isDone()) {
                retry(helper, priorId, sourcePos, hopperPos, tick, pending);
                return;
            }
            try {
                List<Row> rows = read.join();
                if (rows.size() < 2 && tick < 100) {
                    pending.set(null);
                    retry(helper, priorId, sourcePos, hopperPos, tick, pending);
                    return;
                }
                helper.assertValueEqual(List.of("HOPPER_EXTRACT", "HOPPER_INSERT"),
                        rows.stream().map(Row::action).sorted().toList(),
                        "one chest-to-hopper transfer must retain both observed endpoint deltas");
                helper.assertValueEqual(0, rows.stream().mapToInt(row ->
                                "HOPPER_INSERT".equals(row.action()) ? row.amount() : -row.amount()).sum(),
                        "paired hopper endpoint deltas must conserve quantity");
                for (Row row : rows) {
                    helper.assertValueEqual(1, row.amount(), "hopper transfer quantity differs");
                    helper.assertValueEqual("minecraft:dirt", row.itemId(), "hopper item fingerprint differs");
                    helper.assertValueEqual("CONTAINER", row.knownEndpointType(),
                            "native hopper delta must anchor its exact changed container");
                    helper.assertValueEqual("UNKNOWN", row.unknownEndpointType(),
                            "native hopper delta must leave its unobserved remote endpoint unknown");
                }
                helper.assertTrue(rows.stream().anyMatch(row -> row.x() == sourcePos.getX()
                                && row.y() == sourcePos.getY() && row.z() == sourcePos.getZ()),
                        "hopper extraction must identify the source chest position");
                helper.assertTrue(rows.stream().anyMatch(row -> row.x() == hopperPos.getX()
                                && row.y() == hopperPos.getY() && row.z() == hopperPos.getZ()),
                        "hopper insertion must identify the hopper position");
                helper.succeed();
            } catch (RuntimeException failure) {
                helper.fail("Could not verify durable vanilla hopper evidence: " + failure);
            }
        });
    }

    private static void retry(GameTestHelper helper, long priorId, BlockPos sourcePos,
                              BlockPos hopperPos, int tick,
                              AtomicReference<CompletableFuture<List<Row>>> pending) {
        if (tick >= 100) {
            helper.fail("vanilla hopper evidence did not become durable within 100 GameTest ticks");
            return;
        }
        awaitRows(helper, priorId, sourcePos, hopperPos, tick + 1, pending);
    }

    private static long observationWatermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_observations")) {
            if (!rows.next()) throw new SQLException("SQLite did not return the observation watermark");
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph observation watermark", failure);
        }
    }

    private static List<Row> readRows(long priorId, BlockPos source, BlockPos hopper) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT observation.action_type, observation.amount, fingerprint.item_id,
                            CASE WHEN observation.action_type = 'HOPPER_INSERT' THEN target.x ELSE origin.x END,
                            CASE WHEN observation.action_type = 'HOPPER_INSERT' THEN target.y ELSE origin.y END,
                            CASE WHEN observation.action_type = 'HOPPER_INSERT' THEN target.z ELSE origin.z END,
                            CASE WHEN observation.action_type = 'HOPPER_INSERT' THEN target.node_type ELSE origin.node_type END,
                            CASE WHEN observation.action_type = 'HOPPER_INSERT' THEN origin.node_type ELSE target.node_type END
                     FROM ig_observations observation
                     JOIN ig_nodes origin ON origin.id = observation.node_id
                     JOIN ig_nodes target ON target.id = observation.target_node_id
                     JOIN ig_item_fingerprints fingerprint ON fingerprint.id = observation.fingerprint_id
                     WHERE observation.id > ?
                       AND observation.action_type IN ('HOPPER_EXTRACT', 'HOPPER_INSERT')
                       AND fingerprint.item_id = 'minecraft:dirt'
                       AND ((observation.action_type = 'HOPPER_INSERT'
                               AND target.x = ? AND target.y = ? AND target.z = ?)
                         OR (observation.action_type = 'HOPPER_EXTRACT'
                               AND origin.x = ? AND origin.y = ? AND origin.z = ?))
                     ORDER BY observation.id
                     """)) {
            statement.setLong(1, priorId);
            statement.setDouble(2, hopper.getX());
            statement.setDouble(3, hopper.getY());
            statement.setDouble(4, hopper.getZ());
            statement.setDouble(5, source.getX());
            statement.setDouble(6, source.getY());
            statement.setDouble(7, source.getZ());
            try (var rows = statement.executeQuery()) {
                List<Row> result = new ArrayList<>();
                while (rows.next()) {
                    result.add(new Row(rows.getString(1), rows.getInt(2), rows.getString(3),
                            (int) rows.getDouble(4), (int) rows.getDouble(5), (int) rows.getDouble(6),
                            rows.getString(7), rows.getString(8)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read persisted vanilla hopper evidence", failure);
        }
    }
}
