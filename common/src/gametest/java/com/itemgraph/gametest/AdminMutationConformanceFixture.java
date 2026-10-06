package com.itemgraph.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.itemgraph.audit.AdminMutationCapture;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cross-loader live contract for issue #33 command and creative-slot capture. */
public final class AdminMutationConformanceFixture {
    private AdminMutationConformanceFixture() { }

    public static void run(GameTestHelper helper, ServerPlayer player) {
        var slab = net.minecraft.world.level.block.Blocks.STONE_SLAB;
        var bottomSlab = slab.defaultBlockState().setValue(
                net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
        var doubleSlab = slab.defaultBlockState().setValue(
                net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
        helper.assertTrue(AdminMutationCapture.isCreativePlacedBlockChange(bottomSlab, doubleSlab, slab, true),
                "a slab merge at the clicked placement position must count as a block placement");
        helper.assertFalse(AdminMutationCapture.isCreativePlacedBlockChange(bottomSlab, doubleSlab, slab),
                "same-type state changes away from the clicked placement position must not count as placement");
        InternalObservationService service = InternalObservationService.getInstance();
        long enqueuedBefore = service.getTotalEnqueued();
        long persistedBefore = service.getTotalPersisted();
        long droppedBefore = service.getTotalDropped();
        Watermark watermark = watermark();

        Commands commands = helper.getLevel().getServer().getCommands();
        var commandSource = player.createCommandSourceStack().withPermission(2);
        commands.performPrefixedCommand(player.createCommandSourceStack().withPermission(0),
                "give @s minecraft:emerald 1");
        helper.assertTrue(inventoryCount(player, Items.EMERALD) == 0,
                "a permission-denied /give must not change the player's inventory");
        commands.performPrefixedCommand(commandSource, "give @s minecraft:diamond 5");
        helper.assertTrue(inventoryCount(player, Items.DIAMOND) == 5,
                "successful /give must add exactly five diamonds to its target");
        commands.performPrefixedCommand(commandSource, "clear @s minecraft:diamond 2");
        helper.assertTrue(inventoryCount(player, Items.DIAMOND) == 3,
                "partial /clear must remove exactly two diamonds");
        player.inventoryMenu.getCraftSlots().setItem(0, new ItemStack(Items.EMERALD));
        commands.performPrefixedCommand(commandSource, "clear @s minecraft:emerald");
        helper.assertTrue(player.inventoryMenu.getCraftSlots().getItem(0).isEmpty(),
                "/clear must include the player's crafting slots in its exact removal snapshot");
        commands.performPrefixedCommand(commandSource, "clear @s minecraft:lapis_lazuli");
        helper.assertTrue(inventoryCount(player, Items.LAPIS_LAZULI) == 0,
                "a zero-match /clear must not create an item");
        commands.performPrefixedCommand(commandSource,
                "execute as @s run give @s minecraft:gold_ingot 2");
        helper.assertTrue(inventoryCount(player, Items.GOLD_INGOT) == 2,
                "nested namespaced /give must create exactly two gold ingots");
        commands.performPrefixedCommand(commandSource, "execute as @s run say give @s minecraft:emerald 1");
        helper.assertTrue(inventoryCount(player, Items.EMERALD) == 0,
                "a non-item command after /execute run must not be classified as an item mutation");
        commands.performPrefixedCommand(commandSource,
                "item replace entity @s armor.head with minecraft:iron_helmet");
        helper.assertTrue(player.getInventory().getItem(39).is(Items.IRON_HELMET),
                "/item replace must set the selected entity equipment slot");
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        commands.performPrefixedCommand(commandSource,
                "item replace entity @s weapon.mainhand with minecraft:iron_ingot 4");
        helper.assertTrue(player.getInventory().getItem(0).is(Items.IRON_INGOT)
                        && player.getInventory().getItem(0).getCount() == 4,
                "/item replace must replace an occupied slot with the exact new stack");
        ArmorStand targetStand = EntityType.ARMOR_STAND.create(helper.getLevel());
        helper.assertTrue(targetStand != null, "could not create the non-player entity fixture");
        String targetStandTag = "itemgraph_issue33_" + targetStand.getUUID().toString().replace("-", "");
        targetStand.addTag(targetStandTag);
        targetStand.moveTo(player.getX() + 1.0, player.getY(), player.getZ(), player.getYRot(), 0.0F);
        helper.assertTrue(helper.getLevel().addFreshEntity(targetStand),
                "could not add the non-player entity fixture");
        commands.performPrefixedCommand(commandSource,
                "item replace entity @e[type=minecraft:armor_stand,tag=" + targetStandTag + ",limit=1] "
                        + "weapon.mainhand with minecraft:apple");
        helper.assertTrue(targetStand.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.APPLE),
                "/item replace must apply to a selected non-player entity slot");
        commands.performPrefixedCommand(commandSource,
                "execute as @e[type=minecraft:armor_stand,tag=" + targetStandTag
                        + ",limit=1] run item replace entity @s weapon.offhand with minecraft:carrot");
        helper.assertTrue(targetStand.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.CARROT),
                "nested /execute as must apply the item mutation to its effective entity");
        commands.performPrefixedCommand(commandSource,
                "item modify entity @s armor.head "
                        + "{function:\"minecraft:set_components\",components:{\"minecraft:damage\":1}}");
        helper.assertValueEqual(1, player.getInventory().getItem(39).get(net.minecraft.core.component.DataComponents.DAMAGE),
                "/item modify must apply a component-changing item function");
        commands.performPrefixedCommand(commandSource,
                "item replace entity @s armor.head with itemgraph:missing_item_for_issue_33");
        helper.assertTrue(player.getInventory().getItem(39).is(Items.IRON_HELMET)
                        && player.getInventory().getItem(39).get(net.minecraft.core.component.DataComponents.DAMAGE) == 1,
                "an invalid item identifier must leave the target slot unchanged");
        commands.performPrefixedCommand(commandSource,
                "item modify entity @s armor.head "
                        + "{function:\"minecraft:set_components\",components:{\"minecraft:damage\":1}}");
        BlockPos containerPos = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(containerPos,
                        net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3),
                "could not place the block-container fixture");
        commands.performPrefixedCommand(commandSource, "item replace block " + containerPos.getX() + " "
                + containerPos.getY() + " " + containerPos.getZ() + " container.0 with minecraft:emerald 3");
        helper.assertTrue(helper.getLevel().getBlockEntity(containerPos,
                        net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                        .map(chest -> chest.getItem(0).is(Items.EMERALD) && chest.getItem(0).getCount() == 3)
                        .orElse(false), "/item replace must set the selected block-container slot");
        BlockPos copyTargetPos = helper.absolutePos(new BlockPos(4, 1, 2));
        helper.getLevel().setBlock(copyTargetPos,
                net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState(), 3);
        var copyTarget = helper.getLevel().getBlockEntity(copyTargetPos,
                net.minecraft.world.level.block.entity.BlockEntityType.CHEST);
        helper.assertTrue(copyTarget.isPresent(), "could not create the /item from target container fixture");
        copyTarget.orElseThrow().setItem(0, ItemStack.EMPTY);
        commands.performPrefixedCommand(commandSource, "item replace block " + copyTargetPos.getX() + " "
                + copyTargetPos.getY() + " " + copyTargetPos.getZ() + " container.0 from block "
                + containerPos.getX() + " " + containerPos.getY() + " " + containerPos.getZ() + " container.0");
        helper.assertTrue(helper.getLevel().getBlockEntity(containerPos,
                        net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                        .map(chest -> chest.getItem(0).is(Items.EMERALD) && chest.getItem(0).getCount() == 3)
                        .orElse(false)
                        && helper.getLevel().getBlockEntity(copyTargetPos,
                        net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                        .map(chest -> chest.getItem(0).is(Items.EMERALD) && chest.getItem(0).getCount() == 3)
                        .orElse(false),
                "/item from block must preserve its source and copy the exact selected stack to the target");
        commands.performPrefixedCommand(commandSource,
                "item replace entity @s weapon.offhand from entity @e[type=minecraft:armor_stand,tag="
                        + targetStandTag + ",limit=1] weapon.mainhand");
        helper.assertTrue(player.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.APPLE)
                        && targetStand.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.APPLE),
                "/item from entity must preserve its source and copy the exact selected stack to the target");

        player.setGameMode(GameType.CREATIVE);
        helper.assertTrue(player.isCreative(), "GameTest player must be in creative mode for slot packet tests");
        BlockPos placementSupport = helper.absolutePos(new BlockPos(20, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(placementSupport,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3),
                "could not place the creative block-placement support fixture");
        BlockHitResult placementHit = new BlockHitResult(Vec3.atCenterOf(placementSupport), Direction.UP,
                placementSupport, false);
        player.teleportTo(placementSupport.getX() - 2.5, placementSupport.getY(),
                placementSupport.getZ() + 0.5);
        ItemStack originalMainHand = player.getItemInHand(InteractionHand.MAIN_HAND).copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
        net.minecraft.world.InteractionResult placementResult = player.gameMode.useItemOn(player,
                helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, placementHit);
        player.setItemInHand(InteractionHand.MAIN_HAND, originalMainHand);
        BlockPos placedBlock = placementSupport.above();
        helper.assertTrue(placementResult.consumesAction()
                        && helper.getLevel().getBlockState(placedBlock)
                        .is(net.minecraft.world.level.block.Blocks.STONE),
                "creative block placement must change the world at the clicked target; result=" + placementResult
                        + " target=" + placedBlock + " player=" + player.position());
        BlockPos slabMergePos = helper.absolutePos(new BlockPos(24, 1, 2));
        var worldBottomSlab = net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState().setValue(
                net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
        helper.assertTrue(helper.getLevel().setBlock(slabMergePos, worldBottomSlab, 3),
                "could not place the bottom-slab merge fixture");
        BlockHitResult slabMergeHit = new BlockHitResult(Vec3.atCenterOf(slabMergePos), Direction.UP,
                slabMergePos, false);
        player.teleportTo(slabMergePos.getX() - 2.5, slabMergePos.getY(), slabMergePos.getZ() + 0.5);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_SLAB));
        net.minecraft.world.InteractionResult slabMergeResult = player.gameMode.useItemOn(player,
                helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND, slabMergeHit);
        player.setItemInHand(InteractionHand.MAIN_HAND, originalMainHand);
        helper.assertTrue(slabMergeResult.consumesAction()
                        && helper.getLevel().getBlockState(slabMergePos).getValue(
                        net.minecraft.world.level.block.SlabBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE,
                "creative placement onto a bottom slab must merge it into a double slab; result=" + slabMergeResult);
        BlockPos creativeBreakPos = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(creativeBreakPos,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3),
                "could not place the creative block-break fixture");
        helper.assertTrue(player.gameMode.destroyBlock(creativeBreakPos)
                        && helper.getLevel().getBlockState(creativeBreakPos).isAir(),
                "creative block-break fixture must remove the block and return success");
        // The player menu's hotbar starts at slot 36; packet slot 1 is the crafting grid.
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        var creativePacket = new ServerboundSetCreativeModeSlotPacket(36, new ItemStack(Items.IRON_INGOT, 4));
        player.connection.handleSetCreativeModeSlot(creativePacket);
        helper.assertTrue(player.getInventory().getItem(0).is(Items.IRON_INGOT)
                        && player.getInventory().getItem(0).getCount() == 4,
                "accepted creative-slot packet must set the addressed inventory slot");
        player.connection.handleSetCreativeModeSlot(creativePacket);
        AdminMutationCapture.beginCreativeSlotSafely(player, 35);
        player.inventoryMenu.getSlot(35).set(new ItemStack(Items.LAPIS_LAZULI, 2));
        AdminMutationCapture.failCurrentSafely("creative_slot", "packet_handler_exception");
        var creativeDrop = new ServerboundSetCreativeModeSlotPacket(-1,
                new ItemStack(Items.GOLD_NUGGET, 3));
        player.connection.handleSetCreativeModeSlot(creativeDrop);

        // Force GiveCommand through its accepted overflow path after recording normal inventory deltas.
        player.setGameMode(GameType.SURVIVAL);
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.DIAMOND_SWORD));
        }
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        player.getInventory().setItem(40, new ItemStack(Items.SHIELD));
        int emptyInventorySlots = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) emptyInventorySlots++;
        }
        helper.assertValueEqual(0, emptyInventorySlots,
                "accepted /give overflow replay must begin with all 41 inventory slots occupied by valid items");
        commands.performPrefixedCommand(commandSource, "give @s minecraft:amethyst_shard 7");

        String playerUuid = player.getUUID().toString();
        helper.startSequence()
                .thenWaitUntil(() -> {
                    long enqueuedDelta = service.getTotalEnqueued() - enqueuedBefore;
                    long completedDelta = service.getTotalPersisted() - persistedBefore
                            + service.getTotalDropped() - droppedBefore;
                    helper.assertTrue(service.getQueueSize() == 0 && completedDelta >= enqueuedDelta,
                            "issue #33 accepted evidence did not drain: queued=" + service.getQueueSize()
                                    + " enqueued=" + enqueuedDelta + " completed=" + completedDelta);
                })
                .thenExecute(() -> {
            helper.assertTrue(service.getTotalDropped() == droppedBefore,
                    "issue #33 evidence must not be rejected during the live replay");
            MutationIds mutationIds = assertCommandEvents(helper, watermark.auditId(), playerUuid, slabMergePos,
                    targetStand.getUUID().toString());
            assertItemDeltas(helper, watermark.observationId(), playerUuid, mutationIds, containerPos,
                    targetStand.getUUID().toString());
            assertTransformations(helper, watermark.transformationId(), mutationIds);
            assertNoCreativeTransformations(helper, watermark.transformationId());
            assertRelatedEvidenceLinks(helper, watermark.auditId(), playerUuid);
            assertRawCommandHistorySuppressesItemCommands(helper, watermark.auditId(), playerUuid);
            assertManagedDropsAreNotDuplicated(helper, watermark.observationId(), playerUuid);
                })
                .thenSucceed();
    }

    private static MutationIds assertCommandEvents(GameTestHelper helper, long watermark, String playerUuid,
                                                   BlockPos expectedSlabMergePos,
                                                   String expectedExecutionEntityUuid) {
        String sql = """
                SELECT event_type, subject_id, detail, raw_data, x, y, z
                FROM ig_audit_events
                WHERE id > ? AND player_uuid = ?
                  AND event_type IN ('ADMIN_ITEM_COMMAND_ATTEMPT', 'ADMIN_ITEM_COMMAND_EFFECT',
                                     'ADMIN_ITEM_COMMAND_FAILURE', 'ADMIN_ITEM_COMMAND_UNRESOLVED',
                                     'CREATIVE_SLOT_ATTEMPT',
                         'CREATIVE_SLOT_EFFECT', 'CREATIVE_BLOCK_ATTEMPT',
                         'CREATIVE_BLOCK_RESULT', 'CREATIVE_BLOCK_UNRESOLVED')
                ORDER BY id
                """;
        List<AuditRow> rows = new ArrayList<>();
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    byte[] raw = result.getBytes("raw_data");
                    rows.add(new AuditRow(result.getString("event_type"), result.getString("subject_id"),
                            result.getString("detail"), raw == null ? "" : new String(raw, StandardCharsets.UTF_8),
                            result.getDouble("x"), result.getDouble("y"), result.getDouble("z")));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read issue #33 audit evidence", failure);
        }

        Map<String, Attempt> commandAttempts = new HashMap<>();
        Map<String, String> creativeAttempts = new HashMap<>();
        Set<String> invalidItemAttemptEvents = new java.util.HashSet<>();
        int giveAttempts = 0;
        int clearAttempts = 0;
        int itemAttempts = 0;
        int effects = 0;
        int failures = 0;
        int unresolvedEntitySlots = 0;
        int unresolvedNoDeltaResults = 0;
        int creativeEffects = 0;
        int unchangedCreativeAttempts = 0;
        int unresolvedCreativeSlots = 0;
        int confirmedCreativeBreaks = 0;
        int observedCreativePlacements = 0;
        int observedSlabMerges = 0;
        Map<String, String> creativeBlockAttempts = new HashMap<>();
        int invalidItemFailures = 0;
        int executionContextEvidence = 0;
        for (AuditRow row : rows) {
            JsonObject payload = row.rawData().isBlank()
                    ? new JsonObject() : JsonParser.parseString(row.rawData()).getAsJsonObject();
            helper.assertTrue(payload.has("staff_private") && payload.get("staff_private").getAsBoolean(),
                    "administrative and creative audit evidence must be explicitly staff-private");
            switch (row.eventType()) {
                case "ADMIN_ITEM_COMMAND_ATTEMPT" -> {
                    String mutationId = payload.get("mutation_event_id").getAsString();
                    commandAttempts.put(payload.get("event_id").getAsString(),
                            new Attempt(mutationId, row.subjectId()));
                    if ("item".equals(row.subjectId())
                            && payload.get("detail").getAsString().contains("outcome=parse_failed")) {
                        invalidItemAttemptEvents.add(payload.get("event_id").getAsString());
                    }
                    helper.assertTrue(!row.rawData().contains("@s")
                                    && !row.rawData().contains("minecraft:")
                                    && !row.rawData().contains("execute"),
                            "admin command attempt payload must omit selectors and item arguments");
                    if ("give".equals(row.subjectId())) giveAttempts++;
                    if ("clear".equals(row.subjectId())) clearAttempts++;
                    if ("item".equals(row.subjectId())) itemAttempts++;
                }
                case "ADMIN_ITEM_COMMAND_EFFECT", "ADMIN_ITEM_COMMAND_FAILURE" -> {
                    Attempt attempt = commandAttempts.get(payload.get("command_attempt_event_id").getAsString());
                    helper.assertTrue(attempt != null,
                            "command completion must reference its durable attempt event");
                    helper.assertTrue(attempt.mutationId().equals(payload.get("mutation_event_id").getAsString())
                                    && attempt.root().equals(row.subjectId()),
                            "command completion must reuse the matching attempt mutation ID and command root");
                    if ("item".equals(row.subjectId())
                            && ("ADMIN_ITEM_COMMAND_EFFECT".equals(row.eventType())
                            || payload.has("mutation_kind"))) {
                        helper.assertTrue(payload.has("mutation_kind")
                                        && Set.of("item_replace", "item_modify")
                                        .contains(payload.get("mutation_kind").getAsString()),
                                "item completion must retain its specific mutation kind under the stable item command root");
                    }
                    String linkedAttempt = payload.get("command_attempt_event_id").getAsString();
                    if (invalidItemAttemptEvents.contains(linkedAttempt)) {
                        helper.assertTrue("ADMIN_ITEM_COMMAND_FAILURE".equals(row.eventType()),
                                "invalid-item command must not emit a successful effect");
                        invalidItemFailures++;
                    }
                    if ("ADMIN_ITEM_COMMAND_EFFECT".equals(row.eventType())) effects++;
                    else failures++;
                }
                case "ADMIN_ITEM_COMMAND_UNRESOLVED" -> {
                    Attempt attempt = commandAttempts.get(payload.get("command_attempt_event_id").getAsString());
                    helper.assertTrue(attempt != null
                                    && attempt.mutationId().equals(payload.get("mutation_event_id").getAsString()),
                            "unresolved command evidence must reuse its command attempt and mutation IDs");
                    if (payload.has("target_entity_uuid")) {
                        JsonObject before = payload.getAsJsonObject("before");
                        JsonObject after = payload.getAsJsonObject("after");
                        helper.assertTrue(row.detail().contains("endpoint=unresolved")
                                        && expectedExecutionEntityUuid.equals(payload.get("target_entity_uuid").getAsString())
                                        && "minecraft:armor_stand".equals(payload.get("target_entity_type").getAsString())
                                        && before != null && after != null
                                        && before.get("empty").getAsBoolean()
                                        && Set.of("minecraft:apple", "minecraft:carrot")
                                        .contains(after.get("item_id").getAsString()),
                                "non-player entity slot mutations must retain exact unresolved before/after evidence");
                        if (payload.has("execution_context_actor_kind")) {
                            helper.assertTrue("player".equals(payload.get("actor_kind").getAsString())
                                            && playerUuid.equals(payload.get("actor_uuid").getAsString())
                                            && "entity".equals(payload.get("execution_context_actor_kind").getAsString())
                                            && expectedExecutionEntityUuid.equals(
                                                    payload.get("execution_context_actor_uuid").getAsString())
                                            && "minecraft:armor_stand".equals(
                                                    payload.get("execution_context_entity_type").getAsString()),
                                    "nested /execute as must retain the original player issuer and separate effective entity context");
                            executionContextEvidence++;
                        }
                        unresolvedEntitySlots++;
                    } else {
                        helper.assertTrue(row.detail().contains("command_reported_effect_without_captured_delta"),
                                "a positive command result without a captured change must remain unresolved");
                        unresolvedNoDeltaResults++;
                    }
                }
                case "CREATIVE_SLOT_EFFECT" -> {
                    creativeEffects++;
                    helper.assertTrue(payload.get("detail").getAsString()
                                    .contains("cause=creative_inventory_packet cause_status=undifferentiated"),
                            "creative slot effects must state that server-visible cause is undifferentiated");
                    helper.assertTrue("attempt".equals(creativeAttempts.get(
                                    payload.get("mutation_event_id").getAsString())),
                            "creative completion must reuse its changed slot attempt mutation ID");
                }
                case "CREATIVE_SLOT_ATTEMPT" -> {
                    helper.assertTrue(payload.get("detail").getAsString()
                                    .contains("cause=creative_inventory_packet cause_status=undifferentiated"),
                            "creative slot attempts must state that server-visible cause is undifferentiated");
                    String outcome = row.detail().contains("outcome=unresolved_packet_handler_exception")
                            ? "unresolved_packet_handler_exception"
                            : row.detail().contains("unchanged_or_rejected")
                                ? "unchanged_or_rejected" : "attempt";
                    creativeAttempts.put(payload.get("mutation_event_id").getAsString(), outcome);
                    if ("unchanged_or_rejected".equals(outcome)) unchangedCreativeAttempts++;
                    if ("unresolved_packet_handler_exception".equals(outcome)) {
                        helper.assertTrue(row.detail().contains("mutation_count=1"),
                                "a partial creative slot mutation followed by a packet exception must remain unresolved");
                        unresolvedCreativeSlots++;
                    }
                }
                case "CREATIVE_BLOCK_ATTEMPT" -> {
                    helper.assertTrue(payload.has("player_inventory_quantity_delta")
                                    && payload.get("player_inventory_quantity_delta").getAsInt() == 0
                                    && payload.has("mutation_event_id")
                                    && payload.get("event_id").getAsString()
                                    .equals(payload.get("mutation_event_id").getAsString())
                                    && "attempt".equals(payload.get("outcome").getAsString()),
                            "creative block attempts must store exact zero inventory delta and their mutation ID");
                    creativeBlockAttempts.put(payload.get("mutation_event_id").getAsString(),
                            payload.get("event_id").getAsString());
                }
                case "CREATIVE_BLOCK_RESULT", "CREATIVE_BLOCK_UNRESOLVED" -> {
                    String mutationId = payload.get("mutation_event_id").getAsString();
                    helper.assertTrue(creativeBlockAttempts.containsKey(mutationId)
                                    && payload.get("attempt_event_id").getAsString()
                                    .equals(creativeBlockAttempts.get(mutationId)),
                            "creative block outcomes must link to the durable attempt event");
                    helper.assertTrue(payload.has("player_inventory_quantity_delta")
                                    && payload.get("player_inventory_quantity_delta").getAsInt() == 0,
                            "creative block outcomes must store exact zero inventory delta");
                    if ("break".equals(payload.get("action").getAsString())) {
                        helper.assertTrue(row.eventType().equals("CREATIVE_BLOCK_RESULT")
                                        && payload.get("outcome").getAsString().equals("confirmed_success")
                                        && (payload.get("cause_status").getAsString().equals("server_player_game_mode_result")
                                        || payload.get("cause_status").getAsString().equals("fabric_player_block_break_after")),
                                "creative block destruction must persist an authoritative success result");
                        confirmedCreativeBreaks++;
                    } else if ("place".equals(payload.get("action").getAsString())) {
                        helper.assertTrue(row.eventType().equals("CREATIVE_BLOCK_RESULT")
                                        && payload.get("outcome").getAsString().equals("confirmed_success")
                                        && (payload.get("cause_status").getAsString().equals("neoforge_block_item_place_return")
                                        || payload.get("cause_status").getAsString().equals("fabric_block_item_after_state_diff")),
                                "creative block placement must retain an authoritative completed-state result");
                        observedCreativePlacements++;
                        if ("minecraft:stone_slab".equals(row.subjectId())) {
                            helper.assertTrue(row.x() == expectedSlabMergePos.getX()
                                            && row.y() == expectedSlabMergePos.getY()
                                            && row.z() == expectedSlabMergePos.getZ(),
                                    "slab merge evidence must identify the clicked slab position");
                            observedSlabMerges++;
                        }
                    } else {
                        helper.fail("unexpected creative block action " + payload.get("action").getAsString());
                    }
                }
                default -> helper.fail("unexpected issue #33 audit event " + row.eventType());
            }
        }
        helper.assertTrue(giveAttempts == 4 && clearAttempts == 3 && itemAttempts == 10,
                "denied, invalid-item, nested, overflow, clear, and item commands must retain typed attempts");
        helper.assertTrue(invalidItemAttemptEvents.size() == 1 && invalidItemFailures == 1,
                "invalid item parse must link exactly one failed outcome to its typed attempt");
        helper.assertTrue(effects == 13 && failures == 3 && unresolvedEntitySlots == 2
                        && unresolvedNoDeltaResults == 1,
                "confirmed, permission-denied, and failed command outcomes must remain distinct; observed effects="
                        + effects + " failures=" + failures + " unresolvedEntitySlots=" + unresolvedEntitySlots
                        + " unresolvedNoDeltaResults=" + unresolvedNoDeltaResults);
        helper.assertTrue(executionContextEvidence == 1,
                "nested /execute as evidence must preserve exactly one original issuer and effective actor pair");
        helper.assertTrue(creativeEffects == 2 && unchangedCreativeAttempts == 1,
                "changed, unchanged, and ground-drop creative packets must have distinct outcomes");
        helper.assertTrue(unresolvedCreativeSlots == 1,
                "a partial creative-slot packet mutation followed by an exception must not be reported as confirmed");
        helper.assertTrue(confirmedCreativeBreaks == 1,
                "creative block break must have one durable confirmed result on both loaders");
        helper.assertTrue(observedCreativePlacements == 2 && observedSlabMerges == 1,
                "ordinary placement and same-block slab merge must each have one durable result on both loaders");
        helper.assertTrue(creativeBlockAttempts.size() == 3,
                "each creative block placement and break must have a durable attempt row");
        helper.assertTrue(creativeAttempts.size() == 4,
                "each creative slot packet must have its own mutation ID");
        return new MutationIds(commandAttempts, creativeAttempts.keySet());
    }

    private static void assertItemDeltas(GameTestHelper helper, long watermark, String playerUuid,
                                         MutationIds mutationIds, BlockPos copySourcePos,
                                         String copySourceEntityUuid) {
        String sql = """
                SELECT obs.action_type, obs.amount, source.node_type AS source_type,
                       target.node_type AS target_type, fingerprint.item_id, obs.raw_data,
                       source.owner_uuid AS source_owner, target.owner_uuid AS target_owner
                FROM ig_observations obs
                JOIN ig_nodes source ON source.id = obs.node_id
                JOIN ig_nodes target ON target.id = obs.target_node_id
                JOIN ig_item_fingerprints fingerprint ON fingerprint.id = obs.fingerprint_id
                WHERE obs.id > ?
                  AND obs.source_type = 'ITEMGRAPH_INTERNAL'
                  AND obs.action_type IN ('ADMIN_ITEM_CREATE', 'ADMIN_ITEM_REMOVE',
                                          'CREATIVE_ITEM_CREATE', 'CREATIVE_ITEM_REMOVE')
                ORDER BY obs.id
                """;
        List<ItemDelta> rows = new ArrayList<>();
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    byte[] raw = result.getBytes("raw_data");
                    rows.add(new ItemDelta(result.getString("action_type"), result.getInt("amount"),
                            result.getString("source_type"), result.getString("target_type"),
                            result.getString("item_id"),
                            raw == null ? "" : new String(raw, StandardCharsets.UTF_8),
                            result.getString("source_owner"), result.getString("target_owner")));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read issue #33 item deltas", failure);
        }

        helper.assertTrue(rows.size() == 15, "issue #33 must persist exactly fifteen quantity deltas");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_CREATE")
                        && row.amount() == 5 && row.itemId().equals("minecraft:diamond")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER")),
                "/give must persist the exact five-diamond player creation");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_REMOVE")
                        && row.amount() == 2 && row.itemId().equals("minecraft:diamond")
                        && row.sourceType().equals("PLAYER") && row.targetType().equals("UNKNOWN")),
                "/clear must persist the exact two-diamond player removal");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_REMOVE")
                        && row.amount() == 1 && row.itemId().equals("minecraft:emerald")
                        && row.sourceType().equals("PLAYER") && row.targetType().equals("UNKNOWN")),
                "/clear must persist the exact crafting-slot emerald removal");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_CREATE")
                        && row.amount() == 2 && row.itemId().equals("minecraft:gold_ingot")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER")),
                "nested /execute give must persist its exact gold-ingot creation");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_CREATE")
                        && row.amount() == 3 && row.itemId().equals("minecraft:emerald")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("CONTAINER")),
                "/item replace block must persist the exact container creation");
        helper.assertTrue(rows.stream().anyMatch(row -> {
                    if (!row.action().equals("ADMIN_ITEM_CREATE") || row.amount() != 3
                            || !row.itemId().equals("minecraft:emerald")
                            || !row.targetType().equals("CONTAINER")) return false;
                    JsonObject payload = JsonParser.parseString(row.rawData()).getAsJsonObject();
                    if (!payload.has("copy_source")) return false;
                    JsonObject copySource = payload.getAsJsonObject("copy_source");
                    JsonObject copiedStack = copySource.getAsJsonObject("slots").getAsJsonObject("slot:0");
                    return "container".equals(copySource.get("kind").getAsString())
                            && copySource.get("x").getAsInt() == copySourcePos.getX()
                            && copySource.get("y").getAsInt() == copySourcePos.getY()
                            && copySource.get("z").getAsInt() == copySourcePos.getZ()
                            && "minecraft:emerald".equals(copiedStack.get("item_id").getAsString())
                            && copiedStack.get("count").getAsInt() == 3;
                }),
                "/item from block must retain the source endpoint, slot, item, and quantity with copy evidence");
        helper.assertTrue(rows.stream().anyMatch(row -> {
                    if (!row.action().equals("ADMIN_ITEM_CREATE") || row.amount() != 1
                            || !row.itemId().equals("minecraft:apple") || !row.targetType().equals("PLAYER")) return false;
                    JsonObject payload = JsonParser.parseString(row.rawData()).getAsJsonObject();
                    if (!payload.has("copy_source")) return false;
                    JsonObject copySource = payload.getAsJsonObject("copy_source");
                    JsonObject copiedStack = copySource.getAsJsonObject("slots").getAsJsonObject("slot:98");
                    return "entity".equals(copySource.get("kind").getAsString())
                            && copySourceEntityUuid.equals(copySource.get("entity_uuid").getAsString())
                            && "minecraft:apple".equals(copiedStack.get("item_id").getAsString())
                            && copiedStack.get("count").getAsInt() == 1;
                }),
                "/item from entity must retain the source entity UUID, slot, item, and quantity with copy evidence");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_CREATE")
                        && row.amount() == 1 && row.itemId().equals("minecraft:iron_helmet")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER")),
                "/item replace must persist the exact equipment-slot creation");
        ItemDelta replacedDiamondStack = rows.stream().filter(row -> row.action().equals("ADMIN_ITEM_REMOVE")
                && row.amount() == 3 && row.itemId().equals("minecraft:diamond")
                && row.sourceType().equals("PLAYER") && row.targetType().equals("UNKNOWN"))
                .findFirst().orElse(null);
        ItemDelta replacementIronStack = rows.stream().filter(row -> row.action().equals("ADMIN_ITEM_CREATE")
                && row.amount() == 4 && row.itemId().equals("minecraft:iron_ingot")
                && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER"))
                .findFirst().orElse(null);
        helper.assertTrue(replacedDiamondStack != null && replacementIronStack != null,
                "/item replace must record the old and new occupied-slot stacks as separate quantity deltas");
        if (replacedDiamondStack != null && replacementIronStack != null) {
            JsonObject removed = JsonParser.parseString(replacedDiamondStack.rawData()).getAsJsonObject();
            JsonObject created = JsonParser.parseString(replacementIronStack.rawData()).getAsJsonObject();
            String attemptId = removed.get("command_attempt_event_id").getAsString();
            Attempt attempt = mutationIds.commands().get(attemptId);
            helper.assertTrue(removed.get("mutation_event_id").getAsString()
                            .equals(created.get("mutation_event_id").getAsString())
                            && "item_replace".equals(removed.get("mutation_kind").getAsString())
                            && "item_replace".equals(created.get("mutation_kind").getAsString())
                            && attempt != null && "item".equals(attempt.root())
                            && attempt.mutationId().equals(removed.get("mutation_event_id").getAsString()),
                    "/item replace deltas must share their mutation ID while retaining the stable item command root");
        }
        ItemDelta removedCreativeStack = rows.stream().filter(row -> row.action().equals("CREATIVE_ITEM_REMOVE")
                && row.amount() == 3 && row.itemId().equals("minecraft:diamond")
                && row.sourceType().equals("PLAYER") && row.targetType().equals("UNKNOWN"))
                .findFirst().orElse(null);
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("CREATIVE_ITEM_CREATE")
                        && row.amount() == 4 && row.itemId().equals("minecraft:iron_ingot")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER")),
                "creative slot replacement must persist the full new ingot stack independently");
        ItemDelta createdCreativeStack = rows.stream().filter(row -> row.action().equals("CREATIVE_ITEM_CREATE")
                && row.amount() == 4 && row.itemId().equals("minecraft:iron_ingot")
                && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER"))
                .findFirst().orElse(null);
        helper.assertTrue(removedCreativeStack != null && createdCreativeStack != null,
                "creative replacement must persist both exact before and after slot quantities");
        if (removedCreativeStack != null && createdCreativeStack != null) {
            JsonObject removed = JsonParser.parseString(removedCreativeStack.rawData()).getAsJsonObject();
            JsonObject created = JsonParser.parseString(createdCreativeStack.rawData()).getAsJsonObject();
            helper.assertTrue(removed.get("mutation_event_id").getAsString()
                            .equals(created.get("mutation_event_id").getAsString())
                            && "undifferentiated".equals(removed.get("cause_status").getAsString())
                            && "undifferentiated".equals(created.get("cause_status").getAsString()),
                    "creative packet replacement must retain one shared event ID and unknown causal subtype");
        }
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("CREATIVE_ITEM_CREATE")
                        && row.amount() == 3 && row.itemId().equals("minecraft:gold_nugget")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("GROUND")),
                "accepted negative-slot creative packet must persist one exact ground creation");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("CREATIVE_ITEM_CREATE")
                        && row.amount() == 2 && row.itemId().equals("minecraft:lapis_lazuli")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("PLAYER")),
                "a partial creative-slot mutation followed by a handler exception must retain its observed quantity delta");
        helper.assertTrue(rows.stream().anyMatch(row -> row.action().equals("ADMIN_ITEM_CREATE")
                        && row.amount() == 7 && row.itemId().equals("minecraft:amethyst_shard")
                        && row.sourceType().equals("UNKNOWN") && row.targetType().equals("GROUND")),
                "accepted /give overflow must persist one exact ground creation");
        for (ItemDelta row : rows) {
            JsonObject payload = JsonParser.parseString(row.rawData()).getAsJsonObject();
            helper.assertTrue(payload.has("mutation_event_id") && payload.has("event_id"),
                    "quantity evidence must carry mutation and evidence IDs");
            String mutationId = payload.get("mutation_event_id").getAsString();
            boolean actorIsEndpoint = playerUuid.equals(row.sourceOwner()) || playerUuid.equals(row.targetOwner())
                    || payload.has("actor_uuid") && playerUuid.equals(payload.get("actor_uuid").getAsString());
            helper.assertTrue(actorIsEndpoint, "quantity evidence must retain the acting player endpoint");
            helper.assertTrue(payload.has("staff_private") && payload.get("staff_private").getAsBoolean(),
                    "administrative quantity evidence must be explicitly staff-private");
            if (payload.has("operation") && "creative_slot".equals(payload.get("operation").getAsString())) {
                helper.assertTrue("creative_inventory_packet".equals(payload.get("cause").getAsString())
                                && "undifferentiated".equals(payload.get("cause_status").getAsString()),
                        "creative quantity evidence must preserve its unknown action subtype");
                helper.assertTrue(mutationIds.creative().contains(mutationId),
                        "creative quantity evidence must reuse its creative attempt mutation ID");
                helper.assertTrue(!payload.has("command_attempt_event_id"),
                        "creative quantity evidence must not link to an unrelated command attempt");
            } else {
                String attemptId = payload.get("command_attempt_event_id").getAsString();
                Attempt attempt = mutationIds.commands().get(attemptId);
                helper.assertTrue(attempt != null && attempt.mutationId().equals(mutationId)
                                && attempt.root().equals(payload.get("operation").getAsString()),
                        "quantity evidence must reuse the matching command root and attempt mutation ID");
            }
        }
    }

    private static void assertManagedDropsAreNotDuplicated(GameTestHelper helper, long watermark,
                                                            String playerUuid) {
        String sql = """
                SELECT COUNT(*)
                FROM ig_observations obs
                JOIN ig_nodes source ON source.id = obs.node_id
                JOIN ig_item_fingerprints fingerprint ON fingerprint.id = obs.fingerprint_id
                WHERE obs.id > ? AND source.owner_uuid = ? AND obs.action_type = 'DROP_ITEM'
                  AND fingerprint.item_id IN ('minecraft:gold_nugget', 'minecraft:amethyst_shard')
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing duplicate-drop count");
                helper.assertTrue(result.getInt(1) == 0,
                        "ItemGraph-managed creative and /give drops must not also persist as ordinary DROP_ITEM");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify issue #33 managed drop de-duplication", failure);
        }
    }

    private static void assertTransformations(GameTestHelper helper, long watermark, MutationIds mutationIds) {
        String sql = """
                SELECT transformation.transformation_type, transformation.quantity, transformation.details,
                       source.item_id AS source_item, source.fingerprint_hash AS source_hash,
                       result.item_id AS result_item, result.fingerprint_hash AS result_hash
                FROM ig_item_transformations transformation
                JOIN ig_item_fingerprints source ON source.id = transformation.source_fingerprint_id
                JOIN ig_item_fingerprints result ON result.id = transformation.result_fingerprint_id
                WHERE transformation.id > ? AND transformation.transformation_type = 'ADMIN_ITEM_TRANSFORM'
                ORDER BY transformation.id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            try (var result = statement.executeQuery()) {
                helper.assertTrue(result.next(),
                        "/item modify must persist its exact component transformation");
                String details = result.getString("details");
                String attemptId = detailsValue(details, "command_attempt_event_id=");
                Attempt attempt = mutationIds.commands().get(attemptId);
                helper.assertTrue(result.getInt("quantity") == 1
                                && result.getString("source_item").equals("minecraft:iron_helmet")
                                && result.getString("result_item").equals("minecraft:iron_helmet")
                                && !result.getString("source_hash").equals(result.getString("result_hash")),
                        "component transformation must preserve item type and quantity while changing fingerprint");
                helper.assertTrue(details.contains("cause=item_modify")
                                && details.contains("mutation_event_id=" + (attempt == null ? "" : attempt.mutationId()))
                                && attempt != null && "item".equals(attempt.root()),
                        "component transformation must link its cause and shared attempt mutation ID");
                helper.assertTrue(!result.next(),
                        "repeating the same /item modify must not persist a duplicate transformation");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify issue #33 item transformations", failure);
        }
    }

    private static void assertNoCreativeTransformations(GameTestHelper helper, long watermark) {
        String sql = """
                SELECT transformation.quantity, transformation.details,
                       source.item_id AS source_item, result.item_id AS result_item
                FROM ig_item_transformations transformation
                JOIN ig_item_fingerprints source ON source.id = transformation.source_fingerprint_id
                JOIN ig_item_fingerprints result ON result.id = transformation.result_fingerprint_id
                WHERE transformation.id > ?
                  AND transformation.transformation_type = 'CREATIVE_ITEM_TRANSFORM'
                ORDER BY transformation.id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            try (var result = statement.executeQuery()) {
                helper.assertTrue(!result.next(),
                        "an undifferentiated creative replacement must not claim an item transformation");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify absence of issue #33 creative transformations", failure);
        }
    }

    private static void assertRelatedEvidenceLinks(GameTestHelper helper, long watermark, String playerUuid) {
        Set<String> observationIds = new HashSet<>();
        Set<String> transformationIds = new HashSet<>();
        Set<String> unresolvedIds = new HashSet<>();
        String sql = "SELECT event_type, raw_data FROM ig_audit_events WHERE id > ? AND player_uuid = ?";
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    String eventType = result.getString("event_type");
                    byte[] raw = result.getBytes("raw_data");
                    if (raw == null) continue;
                    JsonObject payload = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                    addEventIds(payload, "related_observation_event_ids", observationIds);
                    addEventIds(payload, "related_transformation_event_ids", transformationIds);
                    addEventIds(payload, "related_unresolved_event_ids", unresolvedIds);
                    if (isOutcome(eventType)) {
                        helper.assertTrue(payload.has("item_flow_link_status"),
                                "every scoped outcome must state whether its item evidence linked");
                        String status = payload.get("item_flow_link_status").getAsString();
                        if ("LINKED".equals(status)) {
                            helper.assertTrue(payload.has("related_observation_event_ids")
                                            || payload.has("related_transformation_event_ids"),
                                    "LINKED status requires an observation or traceable transformation UUID");
                        } else if ("UNRESOLVED_EVIDENCE_RECORDED".equals(status)) {
                            helper.assertTrue(payload.has("related_unresolved_event_ids")
                                            || payload.has("outcome") && payload.has("event_id"),
                                    "unresolved-only status requires a linked audit UUID or the unresolved record's own event UUID");
                        } else {
                            helper.assertTrue(Set.of("PARTIAL_LINK_LIST", "NO_ITEM_EVIDENCE_RECORDED").contains(status),
                                    "outcome status must use the documented closed set");
                        }
                    }
                }
            }
            assertEventIdsExist(helper, connection, observationIds,
                    "SELECT COUNT(*) FROM ig_observations WHERE ingest_event_uuid = ?",
                    "related observation UUID must resolve to its persisted item-flow row");
            assertEventIdsExist(helper, connection, transformationIds,
                    "SELECT COUNT(*) FROM ig_item_transformations WHERE ingest_event_uuid = ?",
                    "related transformation UUID must resolve to its persisted transformation row");
            assertEventIdsExist(helper, connection, unresolvedIds,
                    "SELECT COUNT(*) FROM ig_audit_events WHERE ingest_event_uuid = ? AND event_type = 'ADMIN_ITEM_COMMAND_UNRESOLVED'",
                    "related unresolved UUID must resolve to its persisted unresolved audit row");
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify issue #33 outcome evidence links", failure);
        }
        helper.assertTrue(!observationIds.isEmpty() && !transformationIds.isEmpty() && !unresolvedIds.isEmpty(),
                "live /give, /item modify, and unsupported entity cases must each produce an outcome-openable evidence UUID");
    }

    private static void addEventIds(JsonObject payload, String key, Set<String> target) {
        if (!payload.has(key) || !payload.get(key).isJsonArray()) return;
        JsonArray ids = payload.getAsJsonArray(key);
        for (var id : ids) if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) target.add(id.getAsString());
    }

    private static void assertEventIdsExist(GameTestHelper helper, java.sql.Connection connection,
                                            Set<String> eventIds, String sql, String message) throws SQLException {
        for (String eventId : eventIds) {
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, eventId);
                try (var result = statement.executeQuery()) {
                    helper.assertTrue(result.next() && result.getInt(1) == 1, message + ": " + eventId);
                }
            }
        }
    }

    private static boolean isOutcome(String eventType) {
        return Set.of("ADMIN_ITEM_COMMAND_EFFECT", "ADMIN_ITEM_COMMAND_FAILURE", "ADMIN_ITEM_COMMAND_UNRESOLVED",
                "CREATIVE_SLOT_EFFECT", "CREATIVE_SLOT_ATTEMPT", "CREATIVE_BLOCK_RESULT", "CREATIVE_BLOCK_UNRESOLVED")
                .contains(eventType);
    }

    private static String detailsValue(String details, String prefix) {
        if (details == null) return "";
        for (String part : details.split(" ")) {
            if (part.startsWith(prefix)) return part.substring(prefix.length());
        }
        return "";
    }

    private static void assertRawCommandHistorySuppressesItemCommands(GameTestHelper helper, long watermark,
                                                                      String playerUuid) {
        String sql = """
                SELECT COUNT(*) FROM ig_audit_events
                WHERE id > ? AND player_uuid = ? AND event_type = 'COMMAND_ATTEMPT'
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("Missing generic-command count");
                helper.assertTrue(result.getInt(1) == 0,
                        "item commands and nested /execute text must be absent from generic command history");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify issue #33 command privacy", failure);
        }
    }

    private static Watermark watermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement()) {
            long observations;
            try (var result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_observations")) {
                if (!result.next()) throw new SQLException("Missing ItemGraph observation watermark");
                observations = result.getLong(1);
            }
            long audit;
            try (var result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
                if (!result.next()) throw new SQLException("Missing ItemGraph audit watermark");
                audit = result.getLong(1);
            }
            long transformations;
            try (var result = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_item_transformations")) {
                if (!result.next()) throw new SQLException("Missing ItemGraph transformation watermark");
                transformations = result.getLong(1);
            }
            return new Watermark(observations, audit, transformations);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read issue #33 evidence watermarks", failure);
        }
    }

    private static int inventoryCount(ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private record Watermark(long observationId, long auditId, long transformationId) { }
    private record MutationIds(Map<String, Attempt> commands, Set<String> creative) { }
    private record Attempt(String mutationId, String root) { }
    private record AuditRow(String eventType, String subjectId, String detail, String rawData,
                            double x, double y, double z) { }
    private record ItemDelta(String action, int amount, String sourceType, String targetType,
                             String itemId, String rawData, String sourceOwner, String targetOwner) { }
}
