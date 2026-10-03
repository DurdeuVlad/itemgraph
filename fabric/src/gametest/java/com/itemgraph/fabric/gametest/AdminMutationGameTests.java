package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.AdminMutationConformanceFixture;
import com.itemgraph.audit.AdminMutationCapture;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.itemgraph.ingest.InternalObservationService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.sql.SQLException;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Fabric runtime fixture for admin command and creative slot evidence. */
public final class AdminMutationGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty",
            batch = "zz_itemgraph_admin_mutations", timeoutTicks = 300_000)
    @SuppressWarnings("removal")
    public void commandsAndCreativeSlotsPersistCanonicalDeltas(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AdminMutationConformanceFixture.run(helper, player);
    }

    @GameTest(template = "fabric-gametest-api-v1:empty",
            batch = "zz_itemgraph_admin_give_overflow", timeoutTicks = 300_000)
    @SuppressWarnings("removal")
    public void unresolvedGiveOverflowIsNotRecordedAsPlayerDrop(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerPlayer unrelatedPlayer = helper.makeMockServerPlayerInLevel();
        InternalObservationService service = InternalObservationService.getInstance();
        long enqueuedBefore = service.getTotalEnqueued();
        long droppedBefore = service.getTotalDropped();
        long observationWatermark;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_observations")) {
                result.next();
                observationWatermark = result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read isolated give-overflow watermarks", failure);
        }

        AdminMutationCapture.beginGive(player.createCommandSourceStack().withPermission(2),
                Collections.nCopies(129, player));
        ItemStack overflowStack = new ItemStack(Items.AMETHYST_SHARD, 3);
        boolean giveDropBegan = AdminMutationCapture.beginGiveDropSafely(player, overflowStack);
        helper.assertTrue(giveDropBegan, "GiveCommand's exact drop call must open its capture boundary");
        ItemEntity overflowEntity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), overflowStack.copy());
        helper.assertTrue(helper.getLevel().addFreshEntity(overflowEntity),
                "over-limit give overflow entity must be accepted by the world before capture");
        FabricNativeAuditEventListener.onItemDropped(player, overflowEntity, overflowStack);
        ItemEntity reentrantDrop = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), overflowStack.copy());
        helper.assertTrue(helper.getLevel().addFreshEntity(reentrantDrop),
                "same-player equal-stack reentrant drop must be accepted by the world before capture");
        FabricNativeAuditEventListener.onItemDropped(player, reentrantDrop, overflowStack);
        ItemStack unrelatedStack = new ItemStack(Items.STONE, 1);
        ItemEntity unrelatedDrop = new ItemEntity(helper.getLevel(), unrelatedPlayer.getX(),
                unrelatedPlayer.getY(), unrelatedPlayer.getZ(), unrelatedStack.copy());
        helper.assertTrue(helper.getLevel().addFreshEntity(unrelatedDrop),
                "unrelated drop entity must be accepted by the world before capture");
        helper.assertTrue(!AdminMutationCapture.deferGiveDropSafely(unrelatedPlayer, unrelatedDrop, unrelatedStack),
                "a separate player's drop must not be suppressed by GiveCommand's exact drop boundary");
        helper.assertTrue(!unrelatedDrop.isRemoved(),
                "accepted unrelated drop must remain present for post-world capture");
        long unrelatedEnqueuedBefore = service.getTotalEnqueued();
        FabricNativeAuditEventListener.onItemDropped(unrelatedPlayer, unrelatedDrop, unrelatedStack);
        helper.assertTrue(service.getTotalEnqueued() == unrelatedEnqueuedBefore + 1,
                "unrelated drop callback must enqueue one observation for player " + unrelatedPlayer.getUUID());
        var deferredDrops = AdminMutationCapture.finishGiveDropSafely(
                giveDropBegan, player, overflowStack, overflowEntity, true);
        helper.assertTrue(deferredDrops.size() == 1 && deferredDrops.get(0).entity() == reentrantDrop,
                "only the exact GiveCommand return entity may be suppressed; equal-stack reentrant drop must be replayed");
        FabricNativeAuditEventListener.replayDeferredGiveDrops(deferredDrops);
        AdminMutationCapture.finishCurrentSafely("give", true, 1);

        Collection<ServerPlayer> snapshotFailureTargets = new AbstractCollection<>() {
            private int iteratorCalls;

            @Override
            public Iterator<ServerPlayer> iterator() {
                iteratorCalls++;
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new IllegalStateException("synthetic inventory snapshot iteration failure");
                    }

                    @Override
                    public ServerPlayer next() {
                        throw new NoSuchElementException();
                    }
                };
            }

            @Override
            public int size() {
                return 1;
            }
        };
        AdminMutationCapture.beginGive(player.createCommandSourceStack().withPermission(2),
                snapshotFailureTargets);
        boolean snapshotFailureDropBegan = AdminMutationCapture.beginGiveDropSafely(player, overflowStack);
        helper.assertTrue(snapshotFailureDropBegan,
                "snapshot-failed GiveCommand drop must open its exact capture boundary");
        ItemEntity failedSnapshotDrop = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), overflowStack.copy());
        helper.assertTrue(helper.getLevel().addFreshEntity(failedSnapshotDrop),
                "snapshot-failed give overflow entity must be accepted by the world before capture");
        FabricNativeAuditEventListener.onItemDropped(player, failedSnapshotDrop, overflowStack);
        AdminMutationCapture.finishGiveDropSafely(snapshotFailureDropBegan, player, overflowStack,
                failedSnapshotDrop, true);
        AdminMutationCapture.finishCurrentSafely("give", true, 1);

        ItemStack rejectedStack = new ItemStack(Items.AMETHYST_SHARD, 4);
        ItemStack rejectedStack2 = new ItemStack(Items.IRON_INGOT, 2);
        AdminMutationCapture.beginGive(player.createCommandSourceStack().withPermission(2),
                java.util.List.of(player, unrelatedPlayer));
        boolean rejectedDropBegan = AdminMutationCapture.beginGiveDropSafely(player, rejectedStack);
        helper.assertTrue(rejectedDropBegan, "rejected Give overflow must open its exact capture boundary");
        ItemEntity rejectedEntity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), rejectedStack.copy());
        ItemStack canceledReentrantStack = new ItemStack(Items.APPLE, 1);
        ItemEntity canceledReentrantEntity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), canceledReentrantStack.copy());
        helper.assertTrue(AdminMutationCapture.deferGiveDropSafely(
                        player, canceledReentrantEntity, canceledReentrantStack, true),
                "same-player canceled reentrant drop must defer until Give output identity resolves");
        var rejectedReplay = AdminMutationCapture.finishGiveDropSafely(rejectedDropBegan, player, rejectedStack,
                rejectedEntity, false);
        helper.assertTrue(rejectedReplay.size() == 1 && rejectedReplay.get(0).canceled()
                        && rejectedReplay.get(0).entity() == canceledReentrantEntity,
                "canceled reentrant entity must replay separately from rejected Give output");
        boolean rejectedDropBegan2 = AdminMutationCapture.beginGiveDropSafely(unrelatedPlayer, rejectedStack2);
        helper.assertTrue(rejectedDropBegan2,
                "second recipient's rejected Give overflow must open its exact capture boundary");
        ItemEntity rejectedEntity2 = new ItemEntity(helper.getLevel(), unrelatedPlayer.getX(),
                unrelatedPlayer.getY(), unrelatedPlayer.getZ(), rejectedStack2.copy());
        helper.assertTrue(AdminMutationCapture.deferGiveDropSafely(
                        unrelatedPlayer, rejectedEntity2, rejectedStack2, true),
                "canceled second-recipient overflow must defer with its cancellation outcome");
        AdminMutationCapture.finishGiveDropSafely(rejectedDropBegan2, unrelatedPlayer, rejectedStack2,
                rejectedEntity2, false);
        AdminMutationCapture.finishCurrentSafely("give", true, 1);

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(service.getQueueSize() == 0,
                            "give-overflow evidence queue did not drain");
                    try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                         var statement = connection.prepareStatement("SELECT COUNT(*) FROM ig_observations obs "
                                 + "JOIN ig_nodes source ON source.id = obs.node_id WHERE obs.id > ? "
                                 + "AND source.owner_uuid = ? AND obs.action_type = 'DROP_ITEM'")) {
                        statement.setQueryTimeout(5);
                        statement.setLong(1, observationWatermark);
                        statement.setString(2, unrelatedPlayer.getUUID().toString());
                        try (var result = statement.executeQuery()) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "unrelated player drop must reach durable storage before verification");
                        }
                        try (var rejectionStatement = connection.createStatement();
                             var result = rejectionStatement.executeQuery("SELECT COUNT(*) FROM ig_audit_events "
                                     + "WHERE event_type = 'ADMIN_ITEM_COMMAND_UNRESOLVED' "
                                     + "AND detail LIKE '%give_overflow_output_rejected%' "
                                     + "AND raw_data LIKE '%\"item_entity_uuid\":\""
                                     + rejectedEntity.getUUID() + "\"%'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "rejected overflow outcome must reach durable storage before verification");
                        }
                    } catch (SQLException failure) {
                        throw new IllegalStateException("Could not await unrelated drop persistence", failure);
                    }
                })
                .thenExecute(() -> {
                    helper.assertTrue(service.getTotalDropped() == droppedBefore,
                            "over-limit give overflow must not be dropped from the evidence queue");
                    helper.assertTrue(service.getTotalEnqueued() - enqueuedBefore == 7,
                            "three unresolved give scopes, accepted overflow ground outputs, reentrant and unrelated player drops must be retained");
                    try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                         var statement = connection.createStatement()) {
                        statement.setQueryTimeout(5);
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_observations obs "
                                + "JOIN ig_nodes source ON source.id = obs.node_id WHERE obs.id > "
                                + observationWatermark + " AND source.owner_uuid = '"
                                + player.getUUID() + "' AND obs.action_type = 'DROP_ITEM'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "only the reentrant equal-stack drop must be attributed as player DROP_ITEM; count="
                                            + result.getInt(1));
                        }
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_observations obs "
                                + "JOIN ig_nodes source ON source.id = obs.node_id WHERE obs.id > "
                                + observationWatermark + " AND source.owner_uuid = '"
                                + unrelatedPlayer.getUUID() + "' AND obs.action_type = 'DROP_ITEM'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "unrelated player drops must still be captured during a give scope; count="
                                            + result.getInt(1));
                        }
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_observations obs "
                                + "WHERE obs.id > " + observationWatermark
                                + " AND obs.action_type = 'ADMIN_ITEM_CREATE' "
                                + "AND obs.amount = 3 AND CAST(obs.raw_data AS TEXT) LIKE "
                                + "'%\"item_id\":\"minecraft:amethyst_shard\"%' AND CAST(obs.raw_data AS TEXT) LIKE "
                                + "'%\"fingerprint\":%' AND CAST(obs.raw_data AS TEXT) LIKE "
                                + "'%\"item_entity_uuid\":%' AND CAST(obs.raw_data AS TEXT) LIKE "
                                + "'%\"target_player_uuid\":\"" + player.getUUID() + "\"%'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 2,
                                    "over-limit and snapshot-failed give must preserve exact accepted ground output evidence; count="
                                            + result.getInt(1));
                        }
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events "
                                + "WHERE event_type = 'ADMIN_ITEM_COMMAND_UNRESOLVED' AND detail LIKE "
                                + "'%target_limit:129%' AND raw_data LIKE '%\"actor_uuid\":\""
                                + player.getUUID() + "\"%'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "over-limit give must retain one target-limit outcome for its issuer; count="
                                            + result.getInt(1));
                        }
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events "
                                + "WHERE event_type = 'ADMIN_ITEM_COMMAND_UNRESOLVED' AND detail LIKE "
                                + "'%snapshot_failed:IllegalStateException%' AND raw_data LIKE '%\"actor_uuid\":\""
                                + player.getUUID() + "\"%'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "failed give inventory snapshots must retain one issuer outcome; count="
                                            + result.getInt(1));
                        }
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events "
                                + "WHERE event_type = 'ADMIN_ITEM_COMMAND_UNRESOLVED' "
                                + "AND detail LIKE '%give_overflow_output_rejected%' "
                                + "AND raw_data LIKE '%\"give_output_rejections\":%' "
                                + "AND raw_data LIKE '%\"item_id\":\"minecraft:amethyst_shard\"%' "
                                + "AND raw_data LIKE '%\"quantity\":4%' "
                                + "AND raw_data NOT LIKE '%" + canceledReentrantEntity.getUUID() + "%' "
                                + "AND raw_data LIKE '%\"item_entity_uuid\":\""
                                + rejectedEntity.getUUID() + "\"%' "
                                + "AND raw_data LIKE '%\"item_id\":\"minecraft:iron_ingot\"%' "
                                + "AND raw_data LIKE '%\"quantity\":2%' "
                                + "AND raw_data LIKE '%item_toss_event_canceled%' "
                                + "AND raw_data LIKE '%\"item_entity_uuid\":\""
                                + rejectedEntity2.getUUID() + "\"%' "
                                + "AND raw_data LIKE '%\"target_player_uuid\":\""
                                + unrelatedPlayer.getUUID() + "\"%'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 1,
                                    "multi-recipient rejected overflow must preserve every item, quantity, entity, and target as unresolved evidence; count="
                                            + result.getInt(1));
                        }
                    } catch (SQLException failure) {
                        throw new IllegalStateException("Could not verify isolated give-overflow evidence", failure);
                    }
                })
                .thenSucceed();
    }
}
