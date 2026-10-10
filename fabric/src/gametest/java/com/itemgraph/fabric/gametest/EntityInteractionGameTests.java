package com.itemgraph.fabric.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.itemgraph.gametest.EntityInteractionConformanceFixture;
import com.itemgraph.gametest.BucketPickupConformanceFixture;
import com.itemgraph.gametest.ItemMovementConformanceFixture;
import com.itemgraph.gametest.ItemGraphReplayReportFixture;
import com.itemgraph.gametest.ProjectileConformanceFixture;
import com.itemgraph.ingest.InternalObservationService;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;

import java.sql.SQLException;
import java.util.Map;

/** Fabric server GameTest exercises the same server packet and ledger boundary as NeoForge. */
public final class EntityInteractionGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 100)
    public void serverInteractPacketPersistsEntityAttemptAndArmorStandOutcome(GameTestHelper helper) {
        long replayAuditWatermark = BucketPickupConformanceFixture.auditWatermark();
        long replayTransformationWatermark = ItemGraphReplayReportFixture.transformationWatermark();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerPlayer fluidPlayer = helper.makeMockServerPlayerInLevel();
        ServerPlayer movementPlayer = helper.makeMockServerPlayerInLevel();
        FabricNativeAuditEventListener.recordChatMessage(player, "itemgraph_replay_chat");
        InternalObservationService observations = InternalObservationService.getInstance();
        long droppedBefore = observations.getTotalDropped();
        var quantityObservationsBefore = EntityInteractionConformanceFixture.snapshotQuantityObservations();
        long movementWatermark = ItemMovementConformanceFixture.observationWatermark();
        var movementRowsBefore = ItemMovementConformanceFixture.snapshotRows();
        ProjectileConformanceFixture.Watermark projectileWatermark = ProjectileConformanceFixture.watermark();
        long bucketAuditWatermark = BucketPickupConformanceFixture.auditWatermark();

        BlockPos interactionChest = helper.absolutePos(new BlockPos(12, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(interactionChest, Blocks.CHEST.defaultBlockState(), 3),
                "could not place the chest for the native block interaction replay");
        player.teleportTo(interactionChest.getX() + 0.5, interactionChest.getY(), interactionChest.getZ() + 2.0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        BlockHitResult chestHit = new BlockHitResult(Vec3.atCenterOf(interactionChest), Direction.NORTH,
                interactionChest, false);
        UseBlockCallback.EVENT.invoker().interact(player, helper.getLevel(), InteractionHand.MAIN_HAND, chestHit);

        BlockPos placementSupport = helper.absolutePos(new BlockPos(14, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(placementSupport, Blocks.STONE.defaultBlockState(), 3),
                "could not place the support block for native block placement replay");
        player.teleportTo(placementSupport.getX() + 0.5, placementSupport.getY(), placementSupport.getZ() + 2.0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BLOCK));
        BlockHitResult placementHit = new BlockHitResult(Vec3.atCenterOf(placementSupport), Direction.UP,
                placementSupport, false);
        BlockPos placedBlock = placementSupport.above();
        helper.assertTrue(helper.getLevel().setBlock(placedBlock, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3),
                "could not establish the diamond block state for the native placement listener replay");
        helper.assertTrue(helper.getLevel().getBlockState(placedBlock).is(Blocks.DIAMOND_BLOCK),
                "diamond block state was not present before dispatching the native placement hook");
        FabricNativeAuditEventListener.onBlockItemPlaced(
                new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND, placementHit)),
                (BlockItem) Items.DIAMOND_BLOCK, InteractionResult.SUCCESS,
                Map.of(placedBlock, Blocks.AIR.defaultBlockState()), null);

        BlockPos killedCowPos = helper.absolutePos(new BlockPos(16, 1, 2));
        Cow killedCow = new Cow(EntityType.COW, helper.getLevel());
        killedCow.moveTo(killedCowPos.getX() + 0.5, killedCowPos.getY(), killedCowPos.getZ() + 0.5, 0.0F, 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(killedCow),
                "could not spawn the cow for native kill replay");
        player.teleportTo(killedCowPos.getX() + 1.0, killedCowPos.getY(), killedCowPos.getZ() + 0.5);
        helper.assertTrue(killedCow.hurt(player.damageSources().playerAttack(player), 100.0F)
                        && !killedCow.isAlive(),
                "player damage did not kill the cow used by the native kill replay");

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

        // An inspector's entity attack packet must not damage or pop the
        // inspected entity; the denial persists as ATTACK_ENTITY_DENIED.
        ServerPlayer inspectorPlayer = helper.makeMockServerPlayerInLevel();
        inspectorPlayer.teleportTo(armorStandPos.getX() + 1.0, armorStandPos.getY(), armorStandPos.getZ() + 0.5);
        com.itemgraph.command.InspectionService.getInstance().setEnabled(inspectorPlayer.getUUID(), true);
        inspectorPlayer.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(
                armorStand, inspectorPlayer.isShiftKeyDown()));
        com.itemgraph.command.InspectionService.getInstance().clear(inspectorPlayer.getUUID());
        helper.assertTrue(armorStand.isAlive(),
                "inspection-mode attack packet damaged or removed the inspected armor stand");

        BlockPos waterPos = helper.absolutePos(new BlockPos(6, 1, 2));
        BlockPos emptyResultPos = helper.absolutePos(new BlockPos(7, 1, 2));
        BlockPos alternateFluidPos = helper.absolutePos(new BlockPos(8, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(waterPos, Blocks.WATER.defaultBlockState(), 3),
                "could not place the source water block for the bucket replay");
        helper.assertTrue(helper.getLevel().setBlock(emptyResultPos, Blocks.WATER.defaultBlockState(), 3),
                "could not place the source water block for the empty-result guard replay");
        helper.assertTrue(helper.getLevel().setBlock(alternateFluidPos, Blocks.WATER.defaultBlockState(), 3),
                "could not place the source water block for the alternate bucket-content replay");
        FabricNativeAuditEventListener.recordBucketPickup(fluidPlayer, helper.getLevel(), emptyResultPos,
                helper.getLevel().getBlockState(emptyResultPos), ItemStack.EMPTY);
        FabricNativeAuditEventListener.recordBucketPickup(fluidPlayer, helper.getLevel(), alternateFluidPos,
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
        ItemEntity droppedDiamond = movementPlayer.drop(diamondStack, false, false);
        helper.assertTrue(droppedDiamond != null && droppedDiamond.isAlive(),
                "server did not accept the four-diamond player drop");
        droppedDiamond.setNoPickUpDelay();
        droppedDiamond.playerTouch(movementPlayer);
        helper.assertTrue(droppedDiamond.isRemoved(),
                "mock server player did not pick up the accepted diamond stack");
        helper.assertTrue(inventoryCount(movementPlayer, Items.DIAMOND) == 4,
                "ground pickup must restore all four diamonds to player inventory");
        FabricNativeAuditEventListener.onItemUseFinished(player,
                new ItemStack(Items.APPLE), ItemStack.EMPTY);
        FabricNativeAuditEventListener.onItemDestroyed(player, new ItemStack(Items.WOODEN_SWORD));
        SimpleContainer craftMatrix = new SimpleContainer(1);
        craftMatrix.setItem(0, new ItemStack(Items.PAPER));
        FabricNativeAuditEventListener.onCrafted(player, craftMatrix, new ItemStack(Items.BOOK));
        FabricNativeAuditEventListener.onDisconnect(fluidPlayer);
        player.connection.handleChatCommand(new ServerboundChatCommandPacket("say itemgraph_replay_command"));

        observations.stop();
        helper.assertTrue(observations.getQueueSize() == 0,
                "ItemGraph queues must be empty after the worker stops and flushes");
        helper.assertValueEqual(droppedBefore, observations.getTotalDropped(),
                "the interactions must not lose evidence to a full or failed queue");
        // Fabric GameTests share one server lifecycle across batches. Restore the
        // process-wide service after proving stop-and-flush so later fixtures can
        // exercise their own evidence paths on the same test server.
        observations.start();

        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();
        String cowUuid = cow.getUUID().toString();
        String armorStandUuid = armorStand.getUUID().toString();
        helper.succeedWhen(() -> {
            ItemMovementConformanceFixture.assertPersisted(helper, movementWatermark,
                    movementRowsBefore, movementPlayer.getUUID().toString(), containerPos,
                    droppedDiamond.getUUID().toString());
            ProjectileConformanceFixture.assertPersisted(
                    helper, projectileWatermark, playerUuid, quantityObservationsBefore);
            BucketPickupConformanceFixture.assertPersisted(
                    helper, bucketAuditWatermark, projectileWatermark.observationId(),
                    fluidPlayer.getUUID().toString(), waterPos);
            BucketPickupConformanceFixture.assertNoAuditAt(helper, bucketAuditWatermark,
                    fluidPlayer.getUUID().toString(), emptyResultPos);
            BucketPickupConformanceFixture.assertSubjectAt(helper, bucketAuditWatermark,
                    fluidPlayer.getUUID().toString(), alternateFluidPos, "minecraft:lava");
            assertAttempt(helper, playerUuid, cowUuid);
            assertArmorStandOutcomes(helper, playerUuid, armorStandUuid);
            assertAttackDenied(helper, inspectorPlayer.getUUID().toString(), armorStandUuid);
            EntityInteractionConformanceFixture.assertCow(helper, playerUuid, playerName, cowPos, cowUuid);
            EntityInteractionConformanceFixture.assertArmorStand(
                    helper, playerUuid, playerName, armorStandPos, armorStandUuid);
            ItemGraphReplayReportFixture.writeIfRequested(helper, "fabric",
                    Math.min(movementWatermark, projectileWatermark.observationId()),
                    replayAuditWatermark, replayTransformationWatermark,
                    Map.of(movementPlayer.getUUID().toString(), "actor:replay-mover",
                            playerUuid, "actor:replay-interactor",
                            fluidPlayer.getUUID().toString(), "actor:replay-fluid"), waterPos,
                    Map.of("PLACE_BLOCK", placedBlock,
                            "INTERACT_BLOCK_ATTEMPT", interactionChest,
                            "KILL_ENTITY", killedCowPos));
            helper.assertValueEqual(droppedBefore, observations.getTotalDropped(),
                    "the interactions must not lose evidence to a full or failed queue");
        });
    }

    private static void assertAttackDenied(GameTestHelper helper, String inspectorUuid, String targetUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, detail
                     FROM ig_audit_events
                     WHERE player_uuid = ? AND event_type = 'ATTACK_ENTITY_DENIED' AND detail LIKE ?
                     """)) {
            statement.setString(1, inspectorUuid);
            statement.setString(2, "%target_uuid=" + targetUuid + "%");
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(),
                        "inspection-mode attack packet did not persist its denied evidence");
                helper.assertTrue(rows.getString("detail").contains("reason=INSPECTION_MODE"),
                        "inspection-mode attack denial must attribute the inspection reason");
                helper.assertFalse(rows.next(),
                        "one denied attack packet must not produce duplicate denial rows");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read ItemGraph's attack denial evidence", e);
        }
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
