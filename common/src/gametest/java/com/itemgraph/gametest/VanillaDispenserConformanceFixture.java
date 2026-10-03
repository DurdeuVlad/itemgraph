package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.phys.AABB;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Durable read-only replay shared by the Fabric and NeoForge server GameTests. */
public final class VanillaDispenserConformanceFixture {
    private static final String REPLAY_ITEM_NAME = "ItemGraph vanilla automation replay";
    private static final int FIRST_DISPENSE_CHECK_TICK = 12;
    private static final int MAX_DISPENSE_WAIT_TICKS = 40;

    private VanillaDispenserConformanceFixture() { }

    public static void run(GameTestHelper helper, Block dispenserBlock, String expectedAction) {
        BlockPos sourcePos = helper.absolutePos(new BlockPos(2, 1, 2));
        long priorObservationId = observationWatermark();
        helper.assertTrue(helper.getLevel().setBlock(sourcePos,
                        dispenserBlock.defaultBlockState().setValue(DispenserBlock.FACING, Direction.EAST), 3),
                "could not place vanilla dispenser fixture");
        var blockEntity = helper.getLevel().getBlockEntity(sourcePos);
        helper.assertTrue(blockEntity instanceof DispenserBlockEntity,
                "placed dispenser/dropper block entity is missing");
        DispenserBlockEntity dispenser = (DispenserBlockEntity) blockEntity;
        ItemStack replayStack = new ItemStack(Items.NETHER_STAR, 1);
        replayStack.set(DataComponents.CUSTOM_NAME, Component.literal(REPLAY_ITEM_NAME));
        dispenser.setItem(0, replayStack);
        Set<java.util.UUID> preexistingItemEntities = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(sourcePos).inflate(3))
                .stream().map(ItemEntity::getUUID).collect(Collectors.toUnmodifiableSet());
        helper.assertTrue(helper.getLevel().setBlock(sourcePos.north(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3),
                "could not power vanilla dispenser fixture");

        helper.runAtTickTime(FIRST_DISPENSE_CHECK_TICK, () -> awaitDispense(
                helper, dispenser, sourcePos, preexistingItemEntities, priorObservationId, expectedAction,
                FIRST_DISPENSE_CHECK_TICK));
    }

    private static void awaitDispense(GameTestHelper helper, DispenserBlockEntity dispenser, BlockPos sourcePos,
                                      Set<java.util.UUID> preexistingItemEntities, long priorObservationId,
                                      String expectedAction, int tick) {
        List<ItemEntity> spawned = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                        new AABB(sourcePos).inflate(3))
                .stream().filter(entity -> entity.getItem().is(Items.NETHER_STAR)
                        && entity.getItem().get(DataComponents.CUSTOM_NAME) != null
                        && REPLAY_ITEM_NAME.equals(entity.getItem().get(DataComponents.CUSTOM_NAME).getString())
                        && !preexistingItemEntities.contains(entity.getUUID())).toList();
        if (spawned.isEmpty() && tick < MAX_DISPENSE_WAIT_TICKS) {
            helper.runAtTickTime(tick + 1, () -> awaitDispense(helper, dispenser, sourcePos,
                    preexistingItemEntities, priorObservationId, expectedAction, tick + 1));
            return;
        }
        helper.assertTrue(spawned.size() == 1,
                "one powered dispenser/dropper must create one new uniquely named nether star item entity "
                        + "within " + MAX_DISPENSE_WAIT_TICKS + " ticks; found " + spawned.size()
                        + ", slotCount=" + dispenser.getItem(0).getCount()
                        + ", neighborPowered=" + helper.getLevel().hasNeighborSignal(sourcePos));
        helper.assertTrue(dispenser.getItem(0).isEmpty(),
                "one accepted default dispense must consume the one item in its slot");
        ItemEntity entity = spawned.getFirst();
        helper.assertValueEqual(1, entity.getItem().getCount(),
                "captured entity must retain the exact spawned stack count");
        awaitDurableObservation(helper, priorObservationId, expectedAction, sourcePos,
                entity.getUUID().toString(), tick + 4, new AtomicReference<>());
    }

    private record Observation(String action, int amount, String itemId, String itemEntityUuid,
                               String sourceOwner, String targetType, int sourceX, int sourceY, int sourceZ,
                               String rawData) { }

    private static void awaitDurableObservation(GameTestHelper helper, long priorId, String action,
                                                BlockPos source, String entityUuid, int tick,
                                                AtomicReference<CompletableFuture<List<Observation>>> pending) {
        helper.runAtTickTime(tick, () -> {
            if (pending.get() == null) {
                if (InternalObservationService.getInstance().getQueueSize() != 0) {
                    retry(helper, priorId, action, source, entityUuid, tick, pending);
                    return;
                }
                pending.set(CompletableFuture.supplyAsync(() -> readObservations(
                        priorId, action, source, entityUuid)));
            }
            CompletableFuture<List<Observation>> read = pending.get();
            if (!read.isDone()) {
                retry(helper, priorId, action, source, entityUuid, tick, pending);
                return;
            }
            try {
                List<Observation> rows = read.join();
                if (rows.isEmpty() && tick < 80) {
                    pending.set(null);
                    retry(helper, priorId, action, source, entityUuid, tick, pending);
                    return;
                }
                helper.assertValueEqual(1, rows.size(), "one accepted dispense must persist exactly one observation");
                Observation row = rows.getFirst();
                helper.assertValueEqual(action, row.action(), "dispenser action type differs");
                helper.assertValueEqual(1, row.amount(), "dispenser observation quantity differs");
                helper.assertValueEqual("minecraft:nether_star", row.itemId(), "dispenser item fingerprint differs");
                helper.assertValueEqual(entityUuid, row.itemEntityUuid(), "dispenser row must retain the accepted entity UUID");
                helper.assertTrue(row.sourceOwner() == null,
                        "container source must not be assigned a player owner");
                helper.assertValueEqual("GROUND", row.targetType(), "dispenser destination must remain ground");
                helper.assertValueEqual(source.getX(), row.sourceX(), "dispenser source X differs");
                helper.assertValueEqual(source.getY(), row.sourceY(), "dispenser source Y differs");
                helper.assertValueEqual(source.getZ(), row.sourceZ(), "dispenser source Z differs");
                helper.assertTrue(row.rawData().contains("vanilla_dispenser_item_entity"),
                        "dispenser evidence must retain its capture provenance");
                helper.succeed();
            } catch (RuntimeException failure) {
                helper.fail("Could not verify durable vanilla dispenser evidence: " + failure);
            }
        });
    }

    private static void retry(GameTestHelper helper, long priorId, String action, BlockPos source,
                              String entityUuid, int tick, AtomicReference<CompletableFuture<List<Observation>>> pending) {
        if (tick >= 80) {
            helper.fail("vanilla dispenser evidence did not become durable within 80 GameTest ticks");
            return;
        }
        awaitDurableObservation(helper, priorId, action, source, entityUuid, tick + 1, pending);
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

    private static List<Observation> readObservations(long priorId, String action, BlockPos source,
                                                       String entityUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT observation.action_type, observation.amount, fingerprint.item_id,
                            observation.item_entity_uuid, origin.owner_uuid, target.node_type,
                            origin.x, origin.y, origin.z, CAST(observation.raw_data AS TEXT)
                     FROM ig_observations observation
                     JOIN ig_nodes origin ON origin.id = observation.node_id
                     JOIN ig_nodes target ON target.id = observation.target_node_id
                     JOIN ig_item_fingerprints fingerprint ON fingerprint.id = observation.fingerprint_id
                     WHERE observation.id > ? AND observation.action_type = ?
                       AND observation.item_entity_uuid = ?
                       AND origin.x = ? AND origin.y = ? AND origin.z = ?
                     ORDER BY observation.id
                     """)) {
            statement.setLong(1, priorId);
            statement.setString(2, action);
            statement.setString(3, entityUuid);
            statement.setDouble(4, source.getX());
            statement.setDouble(5, source.getY());
            statement.setDouble(6, source.getZ());
            try (var rows = statement.executeQuery()) {
                java.util.ArrayList<Observation> result = new java.util.ArrayList<>();
                while (rows.next()) {
                    result.add(new Observation(rows.getString(1), rows.getInt(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6),
                            (int) rows.getDouble(7), (int) rows.getDouble(8), (int) rows.getDouble(9),
                            rows.getString(10)));
                }
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read persisted vanilla dispenser evidence", failure);
        }
    }
}
