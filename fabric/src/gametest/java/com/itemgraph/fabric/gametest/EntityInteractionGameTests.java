package com.itemgraph.fabric.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.gametest.EntityInteractionConformanceFixture;
import com.itemgraph.gametest.ProjectileConformanceFixture;
import com.itemgraph.ingest.InternalObservationService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.sql.SQLException;

/** Fabric server GameTest exercises the same server packet and ledger boundary as NeoForge. */
public final class EntityInteractionGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 100)
    public void serverInteractPacketPersistsEntityAttemptAndArmorStandOutcome(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        InternalObservationService observations = InternalObservationService.getInstance();
        long droppedBefore = observations.getTotalDropped();
        var quantityObservationsBefore = EntityInteractionConformanceFixture.snapshotQuantityObservations();
        ProjectileConformanceFixture.Watermark projectileWatermark = ProjectileConformanceFixture.watermark();

        Snowball snowball = new Snowball(helper.getLevel(), player);
        snowball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(snowball),
                "server rejected the player-owned snowball used by the projectile replay");
        Arrow arrow = new Arrow(helper.getLevel(), player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 2.5F, 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(arrow),
                "server rejected the player-owned arrow used by the projectile replay");

        BlockPos cowPos = helper.absolutePos(new BlockPos(2, 1, 2));
        Cow cow = new Cow(EntityType.COW, helper.getLevel());
        cow.moveTo(cowPos.getX() + 0.5, cowPos.getY(), cowPos.getZ() + 0.5, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(cow);
        player.teleportTo(cowPos.getX() + 1.0, cowPos.getY(), cowPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        ServerboundInteractPacket cowPacket = ServerboundInteractPacket.createInteractionPacket(
                cow, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 1.0, 0.5));
        player.connection.handleInteract(cowPacket);

        ArmorStand armorStand = EntityType.ARMOR_STAND.create(helper.getLevel());
        helper.assertTrue(armorStand != null, "could not create armor stand in the GameTest level");
        BlockPos armorStandPos = helper.absolutePos(new BlockPos(4, 1, 2));
        armorStand.moveTo(armorStandPos.getX() + 0.5, armorStandPos.getY(), armorStandPos.getZ() + 0.5,
                0.0F, 0.0F);
        helper.getLevel().addFreshEntity(armorStand);
        player.teleportTo(armorStandPos.getX() + 1.0, armorStandPos.getY(), armorStandPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BOOTS));
        player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(
                armorStand, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 0.1, 0.0)));
        helper.assertTrue(armorStand.getItemBySlot(EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS),
                "armor stand interaction packet did not equip the held boots");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(
                armorStand, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 0.1, 0.0)));
        helper.assertTrue(armorStand.getItemBySlot(EquipmentSlot.FEET).isEmpty(),
                "armor stand interaction packet did not unequip the boots with an empty hand");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertValueEqual(net.minecraft.world.InteractionResult.PASS,
                armorStand.interact(player, InteractionHand.MAIN_HAND),
                "direct inherited Entity.interact call must return PASS for an ordinary armor stand");

        observations.stop();
        helper.assertTrue(observations.getQueueSize() == 0,
                "ItemGraph queues must be empty after the worker stops and flushes");
        helper.assertValueEqual(droppedBefore, observations.getTotalDropped(),
                "the interactions must not lose evidence to a full or failed queue");

        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();
        String cowUuid = cow.getUUID().toString();
        String armorStandUuid = armorStand.getUUID().toString();
        helper.succeedWhen(() -> {
            ProjectileConformanceFixture.assertPersisted(
                    helper, projectileWatermark, playerUuid, quantityObservationsBefore);
            assertAttempt(helper, playerUuid, cowUuid);
            assertArmorStandOutcomes(helper, playerUuid, armorStandUuid);
            EntityInteractionConformanceFixture.assertCow(helper, playerUuid, playerName, cowPos, cowUuid);
            EntityInteractionConformanceFixture.assertArmorStand(
                    helper, playerUuid, playerName, armorStandPos, armorStandUuid);
        });
    }

    private static void assertAttempt(GameTestHelper helper, String playerUuid, String targetUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, detail
                     FROM ig_audit_events
                     WHERE player_uuid = ? AND subject_id = 'minecraft:cow' AND detail LIKE ?
                     """)) {
            statement.setString(1, playerUuid);
            statement.setString(2, "%target_uuid=" + targetUuid + "%");
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "cow packet did not persist an interaction attempt");
                helper.assertValueEqual("INTERACT_ENTITY", rows.getString("event_type"),
                        "unsupported entity interaction must remain an attempt");
                String detail = rows.getString("detail");
                helper.assertTrue(detail.contains("hand=main_hand"), "attempt must retain the hand used");
                helper.assertTrue(detail.contains("held_item=minecraft:stick held_count=1"),
                        "attempt must retain the held item snapshot");
                helper.assertTrue(detail.contains("target_support=callback_only"),
                        "unsupported cow interactions must not claim a method result");
                helper.assertTrue(detail.contains(
                                "target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT"),
                        "unsupported cow result capture must use a stable reason code");
                helper.assertFalse(rows.next(), "one cow packet must not create duplicate evidence rows");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read ItemGraph's cow interaction evidence", e);
        }
    }

    private static void assertArmorStandOutcomes(GameTestHelper helper, String playerUuid, String targetUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, detail
                     FROM ig_audit_events
                     WHERE player_uuid = ? AND subject_id = 'minecraft:armor_stand' AND detail LIKE ?
                     ORDER BY event_type
                     """)) {
            statement.setString(1, playerUuid);
            statement.setString(2, "%target_uuid=" + targetUuid + "%");
            int attempts = 0;
            int bootsAttempts = 0;
            int emptyHandAttempts = 0;
            int completed = 0;
            int unresolvedFallbacks = 0;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String eventType = rows.getString("event_type");
                    String detail = rows.getString("detail");
                    helper.assertTrue(detail.contains("target_support=armor_stand_method_result"),
                            "armor stand rows must identify the supported result class");
                    if ("INTERACT_ENTITY".equals(eventType)) {
                        attempts++;
                        helper.assertTrue(detail.contains("hand=main_hand"),
                                "armor stand attempt must retain the interaction hand");
                        if (detail.contains("held_item=minecraft:diamond_boots held_count=1")) {
                            bootsAttempts++;
                        } else if (detail.contains("held_item=minecraft:air held_count=0")) {
                            emptyHandAttempts++;
                        } else {
                            helper.fail("armor stand attempt has unexpected held-stack evidence: " + detail);
                        }
                    } else if ("INTERACT_ENTITY_COMPLETED".equals(eventType)) {
                        completed++;
                        helper.assertTrue(detail.contains("method=interact_at"),
                                "armor stand completion must identify the handled method");
                    } else if ("INTERACT_ENTITY_UNRESOLVED".equals(eventType)) {
                        unresolvedFallbacks++;
                        helper.assertTrue(detail.contains("method=interact"),
                                "fallback Entity.interact result must identify its method boundary");
                        helper.assertTrue(detail.contains("reason=ENTITY_INTERACTION_METHOD_PASSED"),
                                "fallback PASS must remain explicitly unresolved");
                    } else {
                        helper.fail("armor stand interaction produced unexpected event type " + eventType);
                    }
                }
            }
            helper.assertValueEqual(2, attempts, "equip and unequip packets must each produce one attempt");
            helper.assertValueEqual(1, bootsAttempts,
                    "equip attempt must retain the one diamond-boot stack held before interaction");
            helper.assertValueEqual(1, emptyHandAttempts,
                    "unequip attempt must retain the empty hand state");
            helper.assertValueEqual(2, completed, "equip and unequip must each produce one completion");
            helper.assertValueEqual(1, unresolvedFallbacks,
                    "direct inherited Entity.interact call must produce one PASS result");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read ItemGraph's armor stand evidence", e);
        }
    }
}
