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
        helper.assertTrue(AdminMutationCapture.isItemGraphManagedDropInProgress(player),
                "an over-limit /give target snapshot must retain its managed-drop scope");
        ItemStack overflowStack = new ItemStack(Items.AMETHYST_SHARD, 3);
        ItemEntity overflowEntity = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), overflowStack.copy());
        FabricNativeAuditEventListener.onItemDropped(player, overflowEntity, overflowStack);
        ItemStack unrelatedStack = new ItemStack(Items.STONE, 1);
        ItemEntity unrelatedDrop = new ItemEntity(helper.getLevel(), unrelatedPlayer.getX(),
                unrelatedPlayer.getY(), unrelatedPlayer.getZ(), unrelatedStack.copy());
        FabricNativeAuditEventListener.onItemDropped(unrelatedPlayer, unrelatedDrop, unrelatedStack);
        AdminMutationCapture.finishCurrentSafely("give", true, 1);

        Collection<ServerPlayer> snapshotFailureTargets = new AbstractCollection<>() {
            private int iteratorCalls;

            @Override
            public Iterator<ServerPlayer> iterator() {
                if (iteratorCalls++ == 0) return Collections.singleton(player).iterator();
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
        ItemEntity failedSnapshotDrop = new ItemEntity(helper.getLevel(), player.getX(), player.getY(),
                player.getZ(), overflowStack.copy());
        FabricNativeAuditEventListener.onItemDropped(player, failedSnapshotDrop, overflowStack);
        AdminMutationCapture.finishCurrentSafely("give", true, 1);

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(service.getQueueSize() == 0,
                        "give-overflow evidence queue did not drain"))
                .thenExecute(() -> {
                    helper.assertTrue(service.getTotalDropped() == droppedBefore,
                            "over-limit give overflow must not be dropped from the evidence queue");
                    helper.assertTrue(service.getTotalEnqueued() - enqueuedBefore == 3,
                            "both unresolved give scopes and the unrelated player drop must be retained");
                    try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                         var statement = connection.createStatement()) {
                        statement.setQueryTimeout(5);
                        try (var result = statement.executeQuery("SELECT COUNT(*) FROM ig_observations obs "
                                + "JOIN ig_nodes source ON source.id = obs.node_id WHERE obs.id > "
                                + observationWatermark + " AND source.owner_uuid = '"
                                + player.getUUID() + "' AND obs.action_type = 'DROP_ITEM'")) {
                            result.next();
                            helper.assertTrue(result.getInt(1) == 0,
                                    "give-created overflow must not be attributed as player DROP_ITEM; count="
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
                    } catch (SQLException failure) {
                        throw new IllegalStateException("Could not verify isolated give-overflow evidence", failure);
                    }
                })
                .thenSucceed();
    }
}
