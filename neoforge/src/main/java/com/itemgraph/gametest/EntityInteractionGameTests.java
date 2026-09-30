package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.SQLException;

/** Development-only GameTests. Both NeoForge release jar tasks exclude this package. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class EntityInteractionGameTests {
    private EntityInteractionGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty", timeoutTicks = 100)
    public static void serverInteractPacketPersistsEntityAttempt(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        BlockPos targetPos = helper.absolutePos(new BlockPos(2, 1, 2));
        Cow target = new Cow(EntityType.COW, helper.getLevel());
        target.moveTo(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5,
                0.0F, 0.0F);
        helper.getLevel().addFreshEntity(target);

        player.teleportTo(targetPos.getX() + 1.0, targetPos.getY(), targetPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        InternalObservationService observations = InternalObservationService.getInstance();
        long persistedBefore = observations.getTotalPersisted();
        long droppedBefore = observations.getTotalDropped();
        ServerboundInteractPacket packet = ServerboundInteractPacket.createInteractionPacket(
                target, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 1.0, 0.5));
        helper.assertTrue(packet.getTarget(helper.getLevel()) == target,
                "interaction packet did not resolve its target in the GameTest level");
        helper.assertTrue(player.canInteractWithEntity(target.getBoundingBox(), 1.0),
                "mock player is outside the server interaction distance");
        player.connection.handleInteract(packet);
        observations.stop();
        helper.assertTrue(observations.getTotalPersisted() > persistedBefore,
                "server interaction packet did not persist an audit event");
        helper.assertTrue(observations.getQueueSize() == 0,
                "ItemGraph queues must be empty after the worker stops and flushes");
        helper.assertValueEqual(droppedBefore, observations.getTotalDropped(),
                "the interaction must not lose evidence to a full or failed queue");

        String playerUuid = player.getUUID().toString();
        String targetUuid = target.getUUID().toString();
        helper.succeedWhen(() -> {
            try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                 var statement = connection.prepareStatement("""
                         SELECT event_type, detail
                         FROM ig_audit_events
                         WHERE player_uuid = ?
                           AND subject_id = 'minecraft:cow'
                           AND detail LIKE ?
                         """)) {
                statement.setString(1, playerUuid);
                statement.setString(2, "%target_uuid=" + targetUuid + "%");
                try (var rows = statement.executeQuery()) {
                    helper.assertTrue(rows.next(),
                            "server interaction packet did not persist evidence for target " + targetUuid);
                    helper.assertValueEqual("INTERACT_ENTITY", rows.getString("event_type"),
                            "non-armor-stand interaction must remain an attempt without a claimed result");
                    String detail = rows.getString("detail");
                    helper.assertTrue(detail.contains("hand=main_hand"),
                            "persisted interaction must retain the hand used");
                    helper.assertTrue(detail.contains("held_item=minecraft:stick held_count=1"),
                            "persisted interaction must retain the held item snapshot");
                    helper.assertFalse(rows.next(),
                            "one server interaction must not produce duplicate or terminal entity rows");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read ItemGraph's audit ledger", e);
            }
        });
    }
}
