package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.listener.NativeAuditEventListener;
import com.itemgraph.gametest.EntityInteractionConformanceFixture;
import com.itemgraph.gametest.BucketPickupConformanceFixture;
import com.itemgraph.gametest.ItemMovementConformanceFixture;
import com.itemgraph.gametest.ProjectileConformanceFixture;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.sql.SQLException;

/** Development-only GameTests. Both NeoForge release jar tasks exclude this package. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class EntityInteractionGameTests {
    private EntityInteractionGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty", timeoutTicks = 2_000)
    public static void serverInteractPacketPersistsEntityAttemptAndArmorStandOutcome(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerPlayer fluidPlayer = helper.makeMockServerPlayerInLevel();
        ServerPlayer movementPlayer = helper.makeMockServerPlayerInLevel();
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
        var quantityObservationsBefore = EntityInteractionConformanceFixture.snapshotQuantityObservations();
        long movementWatermark = ItemMovementConformanceFixture.observationWatermark();
        var movementRowsBefore = ItemMovementConformanceFixture.snapshotRows();
        ProjectileConformanceFixture.Watermark projectileWatermark = ProjectileConformanceFixture.watermark();
        long bucketAuditWatermark = BucketPickupConformanceFixture.auditWatermark();

        Snowball snowball = new Snowball(helper.getLevel(), player);
        snowball.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.5F, 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(snowball),
                "server rejected the player-owned snowball used by the projectile replay");
        Arrow arrow = new Arrow(helper.getLevel(), player, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
        arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 2.5F, 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(arrow),
                "server rejected the player-owned arrow used by the projectile replay");

        ServerboundInteractPacket packet = ServerboundInteractPacket.createInteractionPacket(
                target, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 1.0, 0.5));
        helper.assertTrue(packet.getTarget(helper.getLevel()) == target,
                "interaction packet did not resolve its target in the GameTest level");
        helper.assertTrue(player.canInteractWithEntity(target.getBoundingBox(), 1.0),
                "mock player is outside the server interaction distance");
        player.connection.handleInteract(packet);

        ArmorStand armorStand = EntityType.ARMOR_STAND.create(helper.getLevel());
        helper.assertTrue(armorStand != null, "could not create armor stand in the GameTest level");
        BlockPos armorStandPos = helper.absolutePos(new BlockPos(4, 1, 2));
        armorStand.moveTo(armorStandPos.getX() + 0.5, armorStandPos.getY(), armorStandPos.getZ() + 0.5,
                0.0F, 0.0F);
        helper.getLevel().addFreshEntity(armorStand);
        player.teleportTo(armorStandPos.getX() + 1.0, armorStandPos.getY(), armorStandPos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BOOTS));
        ServerboundInteractPacket equipPacket = ServerboundInteractPacket.createInteractionPacket(
                armorStand, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 0.1, 0.0));
        player.connection.handleInteract(equipPacket);
        helper.assertTrue(ItemStack.isSameItem(armorStand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET),
                        new ItemStack(Items.DIAMOND_BOOTS)),
                "armor stand interaction packet did not equip the held boots");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        ServerboundInteractPacket unequipPacket = ServerboundInteractPacket.createInteractionPacket(
                armorStand, player.isShiftKeyDown(), InteractionHand.MAIN_HAND, new Vec3(0.0, 0.1, 0.0));
        player.connection.handleInteract(unequipPacket);
        helper.assertTrue(armorStand.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty(),
                "armor stand interaction packet did not unequip the boots with an empty hand");

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertValueEqual(net.minecraft.world.InteractionResult.PASS,
                armorStand.interact(player, InteractionHand.MAIN_HAND),
                "inherited Entity.interact fallback must return PASS for an ordinary armor stand");

        BlockPos waterPos = helper.absolutePos(new BlockPos(6, 1, 2));
        BlockPos emptyResultPos = helper.absolutePos(new BlockPos(7, 1, 2));
        BlockPos alternateFluidPos = helper.absolutePos(new BlockPos(8, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(waterPos, Blocks.WATER.defaultBlockState(), 3),
                "could not place the source water block for the bucket replay");
        helper.assertTrue(helper.getLevel().setBlock(emptyResultPos, Blocks.WATER.defaultBlockState(), 3),
                "could not place the source water block for the empty-result guard replay");
        helper.assertTrue(helper.getLevel().setBlock(alternateFluidPos, Blocks.WATER.defaultBlockState(), 3),
                "could not place the source water block for the alternate bucket-content replay");
        NativeAuditEventListener.recordBucketPickup(fluidPlayer, helper.getLevel(), emptyResultPos,
                helper.getLevel().getBlockState(emptyResultPos), ItemStack.EMPTY);
        NativeAuditEventListener.recordBucketPickup(fluidPlayer, helper.getLevel(), alternateFluidPos,
                helper.getLevel().getBlockState(alternateFluidPos), new ItemStack(Items.LAVA_BUCKET));
        fluidPlayer.teleportTo(waterPos.getX() + 0.5, waterPos.getY(), waterPos.getZ() + 2.0);
        fluidPlayer.setYRot(180.0F);
        fluidPlayer.setXRot(30.0F);
        fluidPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        var bucketResult = Items.BUCKET.use(helper.getLevel(), fluidPlayer, InteractionHand.MAIN_HAND);
        helper.assertTrue(bucketResult.getResult().consumesAction(),
                "server-side empty bucket did not complete source-water pickup");
        helper.assertTrue(helper.getLevel().getBlockState(waterPos).isAir(),
                "successful source-water pickup must remove the source block");
        fluidPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        var emptyPickupResult = Items.BUCKET.use(helper.getLevel(), fluidPlayer, InteractionHand.MAIN_HAND);
        helper.assertFalse(emptyPickupResult.getResult().consumesAction(),
                "empty bucket use without a source fluid must not report a successful pickup");

        BlockPos containerPos = helper.absolutePos(new BlockPos(10, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(containerPos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place the chest for the item movement replay");
        helper.getLevel().getBlockEntity(containerPos, net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .ifPresentOrElse(chest -> {
                    chest.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
                    chest.setChanged();
                    movementPlayer.teleportTo(containerPos.getX() + 1.0, containerPos.getY(), containerPos.getZ() + 0.5);
                    movementPlayer.getInventory().setItem(0, new ItemStack(Items.DIRT, 2));
                    helper.assertTrue(movementPlayer.openMenu(chest).isPresent(),
                            "mock server player could not open the single-chest menu");
                    movementPlayer.containerMenu.clicked(54, 0, ClickType.QUICK_MOVE, movementPlayer);
                    helper.assertTrue(movementPlayer.getInventory().getItem(0).isEmpty()
                                    && chest.getItem(1).is(Items.DIRT)
                                    && chest.getItem(1).getCount() == 2,
                            "server menu quick-move did not transfer both dirt from player inventory to chest");
                    movementPlayer.containerMenu.clicked(0, 0, ClickType.QUICK_MOVE, movementPlayer);
                    helper.assertTrue(chest.getItem(0).isEmpty()
                                    && inventoryCount(movementPlayer, Items.COBBLESTONE) == 3,
                            "server menu quick-move did not transfer all three cobblestone to player inventory");
                    chest.setChanged();
                    movementPlayer.closeContainer();
                }, () -> helper.fail("placed chest has no ChestBlockEntity"));

        movementPlayer.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 4));
        ItemStack diamondStack = movementPlayer.getInventory().removeItem(1, 4);
        helper.assertTrue(diamondStack.getCount() == 4 && inventoryCount(movementPlayer, Items.DIAMOND) == 0,
                "drop replay must first remove the four diamonds from player inventory");
        ItemEntity droppedDiamond = movementPlayer.drop(diamondStack, false);
        helper.assertTrue(droppedDiamond != null && droppedDiamond.isAddedToLevel(),
                "server did not accept the four-diamond player drop");
        droppedDiamond.setNoPickUpDelay();
        droppedDiamond.playerTouch(movementPlayer);
        helper.assertTrue(droppedDiamond.isRemoved(),
                "mock server player did not pick up the accepted diamond stack");
        helper.assertTrue(inventoryCount(movementPlayer, Items.DIAMOND) == 4,
                "ground pickup must restore all four diamonds to player inventory");

        String playerUuid = player.getUUID().toString();
        String targetUuid = target.getUUID().toString();
        String armorStandUuid = armorStand.getUUID().toString();
        String playerName = player.getGameProfile().getName();
        helper.runAtTickTime(200, () -> {
            helper.assertTrue(observations.getTotalPersisted() > persistedBefore,
                    "server interaction packet did not persist an audit event");
            helper.assertValueEqual(droppedBefore, observations.getTotalDropped(),
                    "the interaction must not lose evidence to a full or failed queue");
            ProjectileConformanceFixture.assertPersisted(
                    helper, projectileWatermark, playerUuid, quantityObservationsBefore);
            BucketPickupConformanceFixture.assertPersisted(
                    helper, bucketAuditWatermark, projectileWatermark.observationId(),
                    fluidPlayer.getUUID().toString(), waterPos);
            BucketPickupConformanceFixture.assertNoAuditAt(helper, bucketAuditWatermark,
                    fluidPlayer.getUUID().toString(), emptyResultPos);
            BucketPickupConformanceFixture.assertSubjectAt(helper, bucketAuditWatermark,
                    fluidPlayer.getUUID().toString(), alternateFluidPos, "minecraft:lava");
            ItemMovementConformanceFixture.assertPersisted(helper, movementWatermark,
                    movementRowsBefore, movementPlayer.getUUID().toString(), containerPos,
                    droppedDiamond.getUUID().toString());
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
                    helper.assertTrue(detail.contains("target_support=callback_only"),
                            "unsupported cow interactions must not claim a method result");
                    helper.assertTrue(detail.contains(
                                    "target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT"),
                            "unsupported cow result capture must use a stable reason code");
                    helper.assertFalse(rows.next(),
                            "one server interaction must not produce duplicate or terminal entity rows");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read ItemGraph's audit ledger", e);
            }

            try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
                 var statement = connection.prepareStatement("""
                         SELECT event_type, detail
                         FROM ig_audit_events
                         WHERE player_uuid = ?
                           AND subject_id = 'minecraft:armor_stand'
                           AND detail LIKE ?
                         ORDER BY event_type
                         """)) {
                statement.setString(1, playerUuid);
                statement.setString(2, "%target_uuid=" + armorStandUuid + "%");
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
                                    "armor stand completion must identify the handled interaction method");
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
                helper.assertValueEqual(2, attempts,
                        "equip and unequip packets must each produce one armor stand attempt");
                helper.assertValueEqual(1, bootsAttempts,
                        "the equip attempt must retain the one diamond-boot stack held before interaction");
                helper.assertValueEqual(1, emptyHandAttempts,
                        "the unequip attempt must retain the empty hand state");
                helper.assertValueEqual(2, completed,
                        "equip and unequip must each produce one armor stand completion");
                helper.assertValueEqual(1, unresolvedFallbacks,
                        "the direct inherited Entity.interact call must produce one PASS result");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not read ItemGraph's armor stand evidence", e);
            }

            EntityInteractionConformanceFixture.assertCow(helper, playerUuid, playerName, targetPos, targetUuid);
            EntityInteractionConformanceFixture.assertArmorStand(
                    helper, playerUuid, playerName, armorStandPos, armorStandUuid);
            ItemGraphReplayReportFixture.writeIfRequested(helper, "neoforge",
                    Math.min(movementWatermark, projectileWatermark.observationId()),
                    movementPlayer.getUUID().toString(), playerUuid);
            helper.succeed();
        });
    }

    private static int inventoryCount(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
