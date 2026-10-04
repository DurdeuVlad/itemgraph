package com.itemgraph.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.audit.AuditService;
import com.itemgraph.audit.ContainerBreakCapture;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceQueryService;
import com.itemgraph.query.UnifiedEvidenceQueryService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Cross-loader durable acceptance for item evidence captured at the real player-break boundary. */
public final class ContainerBreakConformanceFixture {
    private ContainerBreakConformanceFixture() { }

    @FunctionalInterface
    public interface BlockBreakAttempt {
        boolean destroy(ServerPlayer player, BlockPos position);
    }

    public static void run(GameTestHelper helper, ServerPlayer player, String loader,
                           BlockBreakAttempt blockBreakAttempt) {
        InternalObservationService service = InternalObservationService.getInstance();
        long observationWatermark = observationWatermark();
        long auditWatermark = auditWatermark();
        long droppedBefore = service.getTotalDropped();

        BlockPos canceledPos = helper.absolutePos(new BlockPos(1, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(canceledPos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place the canceled-break fixture");
        var canceledChest = helper.getLevel().getBlockEntity(canceledPos,
                net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("canceled-break chest has no block entity"));
        canceledChest.setItem(0, new ItemStack(Items.GOLD_INGOT, 2));
        helper.assertTrue(!blockBreakAttempt.destroy(player, canceledPos)
                        && !helper.getLevel().getBlockState(canceledPos).isAir(),
                "a loader-canceled player break must fail and leave its container intact");

        BlockPos emptyPos = helper.absolutePos(new BlockPos(5, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(emptyPos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place the empty-chest fixture");
        helper.assertTrue(player.gameMode.destroyBlock(emptyPos)
                        && helper.getLevel().getBlockState(emptyPos).isAir(),
                "successful empty-chest break must remove the block");

        BlockPos oneStackPos = helper.absolutePos(new BlockPos(9, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(oneStackPos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place the one-stack single-chest fixture");
        var oneStack = helper.getLevel().getBlockEntity(oneStackPos,
                net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("one-stack chest fixture has no block entity"));
        oneStack.setItem(13, new ItemStack(Items.BLAZE_ROD, 5));
        oneStack.setChanged();
        helper.assertTrue(player.gameMode.destroyBlock(oneStackPos)
                        && helper.getLevel().getBlockState(oneStackPos).isAir(),
                "successful one-stack single-chest break must remove the chest block");

        BlockPos singlePos = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(singlePos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place the full chest break fixture");
        var single = helper.getLevel().getBlockEntity(singlePos, net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("full chest fixture has no block entity"));
        for (int slot = 0; slot < single.getContainerSize(); slot++) {
            ItemStack stack = new ItemStack(Items.PAPER, slot + 1);
            if (slot == 1) stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    Component.literal("container-break-component-variant"));
            single.setItem(slot, stack);
        }
        single.setChanged();
        player.setGameMode(GameType.SURVIVAL);
        helper.assertTrue(player.gameMode.destroyBlock(singlePos)
                        && helper.getLevel().getBlockState(singlePos).isAir(),
                "successful survival-mode chest break must remove the chest block");

        BlockPos doubleLeft = helper.absolutePos(new BlockPos(7, 1, 2));
        var leftState = Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.NORTH)
                .setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos doubleRight = doubleLeft.relative(ChestBlock.getConnectedDirection(leftState));
        var rightState = leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT);
        helper.assertTrue(helper.getLevel().setBlock(doubleLeft, leftState, 3),
                "could not place the left double-chest fixture block");
        helper.assertTrue(helper.getLevel().setBlock(doubleRight, rightState, 3),
                "could not place the right double-chest fixture block");
        var left = helper.getLevel().getBlockEntity(doubleLeft,
                net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("left double-chest half has no block entity"));
        var right = helper.getLevel().getBlockEntity(doubleRight,
                net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("right double-chest half has no block entity"));
        left.setItem(0, new ItemStack(Items.DIAMOND, 3));
        right.setItem(0, new ItemStack(Items.EMERALD, 4));
        left.setChanged();
        right.setChanged();
        helper.assertValueEqual(ChestType.LEFT, helper.getLevel().getBlockState(doubleLeft).getValue(ChestBlock.TYPE),
                "double-chest left half must remain paired before the break");
        helper.assertValueEqual(ChestType.RIGHT, helper.getLevel().getBlockState(doubleRight).getValue(ChestBlock.TYPE),
                "double-chest right half must remain paired before the break");
        helper.assertTrue(player.gameMode.destroyBlock(doubleLeft)
                        && helper.getLevel().getBlockState(doubleLeft).isAir(),
                "successful break of the left double-chest half must remove only that block");

        BlockPos unsupportedPos = helper.absolutePos(new BlockPos(11, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(unsupportedPos, Blocks.OAK_SIGN.defaultBlockState(), 3),
                "could not place the unsupported block-entity fixture");
        helper.assertTrue(player.gameMode.destroyBlock(unsupportedPos)
                        && helper.getLevel().getBlockState(unsupportedPos).isAir(),
                "successful non-container block-entity break must be reported as unsupported");

        BlockPos missingActorPos = helper.absolutePos(new BlockPos(13, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(missingActorPos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place missing-actor context fixture");
        var missingActorChest = helper.getLevel().getBlockEntity(missingActorPos,
                net.minecraft.world.level.block.entity.BlockEntityType.CHEST)
                .orElseThrow(() -> new AssertionError("missing-actor fixture has no block entity"));
        var missingActor = ContainerBreakCapture.begin(null, helper.getLevel(), missingActorPos,
                helper.getLevel().getBlockState(missingActorPos), missingActorChest);
        helper.assertValueEqual("CONTAINER_BREAK_ACTOR_UNAVAILABLE", missingActor.unresolvedReason(),
                "missing authoritative actor must stay unresolved");
        helper.assertTrue(helper.getLevel().setBlock(missingActorPos, Blocks.AIR.defaultBlockState(), 3),
                "could not complete the actor-unavailable container-break fixture");
        ContainerBreakCapture.complete(missingActor, true);

        BlockPos missingEntityPos = helper.absolutePos(new BlockPos(15, 1, 2));
        helper.assertTrue(helper.getLevel().setBlock(missingEntityPos, Blocks.CHEST.defaultBlockState(), 3),
                "could not place missing-block-entity context fixture");
        var missingEntity = ContainerBreakCapture.begin(player, helper.getLevel(), missingEntityPos,
                helper.getLevel().getBlockState(missingEntityPos), null);
        helper.assertValueEqual("CONTAINER_BLOCK_ENTITY_UNAVAILABLE", missingEntity.unresolvedReason(),
                "missing block entity must stay unresolved");
        ContainerBreakCapture.complete(missingEntity, false);

        String playerUuid = player.getUUID().toString();
        helper.startSequence()
                    .thenWaitUntil(() -> helper.assertTrue(hasBreakRows(auditWatermark, observationWatermark,
                                playerUuid, emptyPos, singlePos, doubleLeft, oneStackPos, missingActorPos),
                        "bounded asynchronous worker did not persist both container-break evidence groups"))
                .thenExecute(() -> {
                    helper.assertValueEqual(0, service.getQueueSize(),
                            "all accepted container-break evidence must drain from bounded queues");
                    helper.assertValueEqual(droppedBefore, service.getTotalDropped(),
                            "container-break evidence must not be rejected or dropped");
                    assertNoCompletedBreakAt(helper, auditWatermark, playerUuid, canceledPos);
                    assertActorUnavailable(helper, auditWatermark, missingActorPos);
                    assertUnsupportedBlockEntityUnresolved(helper, auditWatermark, playerUuid, unsupportedPos);
                    assertLinkedBreakEvent(helper, auditWatermark, playerUuid, emptyPos);
                    assertLinkedBreakEvent(helper, auditWatermark, playerUuid, singlePos);
                    assertLinkedBreakEvent(helper, auditWatermark, playerUuid, doubleLeft);
                    assertLinkedBreakEvent(helper, auditWatermark, playerUuid, oneStackPos);
                    assertQueryAndConservationSurfaces(helper, auditWatermark, observationWatermark,
                            playerUuid, oneStackPos);
                    assertEmptyChest(helper, auditWatermark, observationWatermark, playerUuid, emptyPos);
                    assertSingleChest(helper, auditWatermark, observationWatermark, playerUuid, singlePos);
                    assertOneStackChest(helper, auditWatermark, observationWatermark, playerUuid, oneStackPos);
                    assertDoubleChestHalf(helper, auditWatermark, observationWatermark, playerUuid, doubleLeft,
                            doubleRight, helper.getLevel().getBlockState(doubleRight).getValue(ChestBlock.TYPE));
                    writeRedactedReport(loader, helper.absolutePos(BlockPos.ZERO), auditWatermark,
                            observationWatermark, playerUuid, canceledPos, emptyPos, singlePos,
                            doubleLeft, oneStackPos, missingActorPos);
                    helper.succeed();
                });
    }

    private static boolean hasBreakRows(long auditWatermark, long observationWatermark,
                                        String playerUuid, BlockPos empty, BlockPos single,
                                        BlockPos doubleLeft, BlockPos oneStack, BlockPos missingActor) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var audits = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_COMPLETED' AND player_uuid = ?
                       AND ((x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?)
                            OR (x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?))
                     """)) {
            audits.setLong(1, auditWatermark);
            audits.setString(2, playerUuid);
            audits.setDouble(3, empty.getX()); audits.setDouble(4, empty.getY()); audits.setDouble(5, empty.getZ());
            audits.setDouble(6, single.getX()); audits.setDouble(7, single.getY()); audits.setDouble(8, single.getZ());
            audits.setDouble(9, doubleLeft.getX()); audits.setDouble(10, doubleLeft.getY()); audits.setDouble(11, doubleLeft.getZ());
            audits.setDouble(12, oneStack.getX()); audits.setDouble(13, oneStack.getY()); audits.setDouble(14, oneStack.getZ());
            try (var rows = audits.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 4) return false;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not poll container-break audit evidence", failure);
        }
          int relatedObservationCount = 0;
          for (BlockPos position : List.of(empty, single, doubleLeft, oneStack)) {
              String parentId = auditEventId(auditWatermark, playerUuid, position);
              relatedObservationCount += observations(observationWatermark, parentId).size();
          }
          if (relatedObservationCount != 29) return false;
          try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
               var actorless = connection.prepareStatement("""
                       SELECT COUNT(*) FROM ig_audit_events
                       WHERE id > ? AND event_type = 'CONTAINER_BREAK_UNRESOLVED'
                         AND player_uuid IS NULL AND player_name IS NULL
                         AND x = ? AND y = ? AND z = ?
                         AND CAST(raw_data AS TEXT) LIKE '%\"actor_status\":\"UNKNOWN\"%'
                         AND CAST(raw_data AS TEXT) LIKE '%\"reason_code\":\"CONTAINER_BREAK_ACTOR_UNAVAILABLE\"%'
                       """)) {
              actorless.setLong(1, auditWatermark);
              actorless.setDouble(2, missingActor.getX()); actorless.setDouble(3, missingActor.getY());
              actorless.setDouble(4, missingActor.getZ());
              try (var rows = actorless.executeQuery()) {
                  if (!rows.next() || rows.getInt(1) != 1) return false;
              }
          } catch (SQLException failure) {
              throw new IllegalStateException("Could not poll actor-unavailable container-break evidence", failure);
          }
          try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
               var statement = connection.prepareStatement("""
                       SELECT COUNT(*) FROM ig_observations
                       WHERE id > ? AND action_type = 'REMOVE_ITEM'
                       AND CAST(raw_data AS TEXT) LIKE ?
                       """)) {
              statement.setLong(1, observationWatermark);
              String onlyStackParent = auditEventId(auditWatermark, playerUuid, oneStack);
              statement.setString(2, "%\"cause_event_id\":\"" + onlyStackParent + "\"%");
              try (var rows = statement.executeQuery()) { return rows.next() && rows.getInt(1) == 1; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not poll container-break slot observations", failure);
        }
    }

    private static void assertEmptyChest(GameTestHelper helper, long auditWatermark,
                                         long observationWatermark, String playerUuid, BlockPos pos) {
        String parentId = auditEventId(auditWatermark, playerUuid, pos);
        helper.assertValueEqual(0, observations(observationWatermark, parentId).size(),
                "an empty container break must persist no manufactured item quantity");
        String raw = completedAudit(auditWatermark, playerUuid, pos);
        helper.assertTrue(raw.contains("\"stack_slots\":0") && raw.contains("\"total_items\":0"),
                "empty inventory must have an explicit successful zero-content summary");
        helper.assertTrue(!raw.contains("CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED")
                        && !raw.contains("drop_link_status"),
                "an empty inventory has no resulting item drop whose destination could be unresolved");
        helper.assertValueEqual("", unresolvedDropAudit(auditWatermark, playerUuid, pos),
                "an empty chest break must not emit a drop-link unresolved event");
    }

    private static void assertNoCompletedBreakAt(GameTestHelper helper, long auditWatermark,
                                                  String playerUuid, BlockPos position) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ig_audit_events
                     WHERE id > ? AND player_uuid = ? AND x = ? AND y = ? AND z = ?
                       AND event_type IN ('CONTAINER_BREAK_COMPLETED', 'CONTAINER_BREAK_UNRESOLVED')
                     """)) {
            statement.setLong(1, auditWatermark);
            statement.setString(2, playerUuid);
            statement.setDouble(3, position.getX()); statement.setDouble(4, position.getY());
            statement.setDouble(5, position.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next() && rows.getInt(1) == 0,
                        "canceled or failed player break must not persist a completed/unresolved contents result");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not check the canceled container-break fixture", failure);
        }
    }

    private static void assertActorUnavailable(GameTestHelper helper, long auditWatermark, BlockPos position) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, player_name, CAST(raw_data AS TEXT), detail
                     FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_UNRESOLVED'
                       AND x = ? AND y = ? AND z = ?
                       AND detail = 'reason=CONTAINER_BREAK_ACTOR_UNAVAILABLE'
                     ORDER BY id
                     """)) {
            statement.setLong(1, auditWatermark);
            statement.setDouble(2, position.getX()); statement.setDouble(3, position.getY());
            statement.setDouble(4, position.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "successful break without an actor must persist unresolved evidence");
                helper.assertValueEqual(null, rows.getString(1), "actor-unavailable evidence must not invent a player UUID");
                helper.assertValueEqual(null, rows.getString(2), "actor-unavailable evidence must not invent a player name");
                JsonObject details = parse(rows.getString(3));
                helper.assertValueEqual("UNKNOWN", details.get("actor_status").getAsString(),
                        "actor-unavailable evidence must persist UNKNOWN actor status");
                helper.assertValueEqual("CONTAINER_BREAK_ACTOR_UNAVAILABLE", details.get("reason_code").getAsString(),
                        "actor-unavailable evidence must persist its stable reason code");
                helper.assertTrue(!rows.next(), "fixture must persist exactly one actor-unavailable result");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify actor-unavailable container-break evidence", failure);
        }
    }

    private static void assertLinkedBreakEvent(GameTestHelper helper, long auditWatermark,
                                               String playerUuid, BlockPos position) {
        JsonObject details = parse(completedAudit(auditWatermark, playerUuid, position));
        String breakEventId = details.get("break_event_id").getAsString();
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT COUNT(*) FROM ig_audit_events
                     WHERE ingest_event_uuid = ? AND event_type = 'BREAK_BLOCK' AND player_uuid = ?
                       AND x = ? AND y = ? AND z = ?
                     """)) {
            statement.setString(1, breakEventId);
            statement.setString(2, playerUuid);
            statement.setDouble(3, position.getX()); statement.setDouble(4, position.getY());
            statement.setDouble(5, position.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next() && rows.getInt(1) == 1,
                        "container snapshot must link to its matching standard BREAK_BLOCK event UUID");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not check linked BREAK_BLOCK event", failure);
        }
    }

    private static void assertUnsupportedBlockEntityUnresolved(GameTestHelper helper, long auditWatermark,
                                                               String playerUuid, BlockPos position) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT CAST(raw_data AS TEXT) FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_UNRESOLVED' AND player_uuid = ?
                       AND x = ? AND y = ? AND z = ?
                     ORDER BY id
                     """)) {
            statement.setLong(1, auditWatermark);
            statement.setString(2, playerUuid);
            statement.setDouble(3, position.getX()); statement.setDouble(4, position.getY());
            statement.setDouble(5, position.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next() && rows.getString(1).contains("CONTAINER_BLOCK_ENTITY_UNSUPPORTED"),
                        "unsupported block entities must persist the stable unresolved reason");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect unsupported block-entity evidence", failure);
        }
    }

    private static void assertQueryAndConservationSurfaces(GameTestHelper helper, long auditWatermark,
                                                           long observationWatermark, String playerUuid,
                                                           BlockPos oneStackPos) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection()) {
            String parentId = auditEventId(auditWatermark, playerUuid, oneStackPos);
            Observation expectedObservation = observations(observationWatermark, parentId).getFirst();
            JsonObject expectedRaw = JsonParser.parseString(expectedObservation.rawData()).getAsJsonObject();
            String breakEventId = expectedRaw.get("break_event_id").getAsString();
            var lookup = new UnifiedEvidenceQueryService().findFiltered(connection,
                    AuditLookupFilters.parse("action.remove_item include.blaze_rod radius.1", System.currentTimeMillis()),
                    "minecraft:overworld", oneStackPos.getX(), oneStackPos.getY(), oneStackPos.getZ(), 100, 0);
            var expectedLookup = lookup.stream()
                    .filter(row -> row.evidenceId().equals("observation#" + expectedObservation.id()))
                    .toList();
            helper.assertTrue(expectedLookup.size() == 1 && expectedLookup.getFirst().actionType().equals("REMOVE_ITEM")
                            && expectedLookup.getFirst().evidenceClass().equals("OBSERVED")
                            && expectedLookup.getFirst().x().equals((double) oneStackPos.getX())
                            && expectedLookup.getFirst().y().equals((double) oneStackPos.getY())
                            && expectedLookup.getFirst().z().equals((double) oneStackPos.getZ())
                            && expectedLookup.getFirst().detail().contains("evidence_event_id=" + expectedObservation.eventId())
                            && expectedLookup.getFirst().detail().contains("parent_event_id=" + parentId)
                            && expectedLookup.getFirst().detail().contains("break_event_id=" + breakEventId),
                    "unified lookup must return the observed slot, exact endpoint, and stable evidence links");

            var trace = new TraceQueryService().traceContainer(connection, "minecraft:overworld",
                    oneStackPos.getX(), oneStackPos.getY(), oneStackPos.getZ(), 100, QueryWindow.unbounded());
            var expectedHop = trace.hops().stream()
                    .filter(hop -> hop.detail().contains("evidence_event_id=" + expectedObservation.eventId()))
                    .toList();
            helper.assertTrue(expectedHop.size() == 1 && expectedHop.getFirst().amount() == 5
                            && expectedHop.getFirst().origin().nodeType().equals("CONTAINER")
                            && expectedHop.getFirst().origin().x().equals((double) oneStackPos.getX())
                            && expectedHop.getFirst().detail().contains("parent_event_id=" + parentId)
                            && expectedHop.getFirst().detail().contains("break_event_id=" + breakEventId),
                    "container trace must expose quantity, endpoint, and stable evidence links");

            String unresolvedEventId = parse(unresolvedDropAudit(auditWatermark, playerUuid, oneStackPos))
                    .get("event_id").getAsString();
            var unresolved = new UnifiedEvidenceQueryService().findFiltered(connection,
                    AuditLookupFilters.parse("action.container_break_unresolved radius.1", System.currentTimeMillis()),
                    "minecraft:overworld", oneStackPos.getX(), oneStackPos.getY(), oneStackPos.getZ(), 100, 0);
            var expectedUnresolved = unresolved.stream()
                    .filter(row -> row.detail().contains("evidence_event_id=" + unresolvedEventId))
                    .toList();
            helper.assertTrue(expectedUnresolved.size() == 1
                            && expectedUnresolved.getFirst().evidenceClass().equals("UNRESOLVED")
                            && expectedUnresolved.getFirst().detail().contains("reason_code="
                            + "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED")
                            && expectedUnresolved.getFirst().detail().contains("parent_event_id=" + parentId)
                            && expectedUnresolved.getFirst().detail().contains("break_event_id=" + breakEventId),
                    "unified audit lookup must expose the unresolved reason and linked event identities");

            var audit = new AuditService().audit(connection);
            helper.assertTrue(audit.healthy(),
                    "read-only quantity-conservation and database-integrity audit must pass: "
                            + String.join("; ", audit.violationDetails()));
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify container-break query and audit surfaces", failure);
        }
    }

    private static void assertSingleChest(GameTestHelper helper, long auditWatermark,
                                          long observationWatermark, String playerUuid, BlockPos pos) {
        String parentId = auditEventId(auditWatermark, playerUuid, pos);
        List<Observation> rows = observations(observationWatermark, parentId);
        helper.assertValueEqual(27, rows.size(), "all 27 nonempty single-chest slots must be recorded individually");
        int total = 0;
        List<String> fingerprints = new ArrayList<>();
        for (int slot = 0; slot < 27; slot++) {
            Observation row = rows.get(slot);
            helper.assertValueEqual(slot, row.slot(), "slot provenance must be stable for single chest");
            helper.assertValueEqual("minecraft:paper", row.itemId(), "single-chest registry ID changed");
            helper.assertValueEqual(slot + 1, row.amount(), "single-chest quantity changed");
            helper.assertValueEqual("CONTAINER", row.sourceType(), "broken inventory must remain the source");
            helper.assertValueEqual("UNKNOWN", row.targetType(), "break must not attribute contents to player or ground");
            helper.assertValueEqual(pos.getX(), row.sourceX(), "container source X changed");
            helper.assertValueEqual(pos.getY(), row.sourceY(), "container source Y changed");
            helper.assertValueEqual(pos.getZ(), row.sourceZ(), "container source Z changed");
            helper.assertTrue(row.rawData().contains("\"actor_status\":\"PLAYER\""),
                    "loader-supplied player actor must be explicit in raw evidence");
            total += row.amount();
            fingerprints.add(row.fingerprint());
        }
        helper.assertValueEqual(378, total, "snapshot must preserve the exact sum of stack quantities");
        helper.assertTrue(fingerprints.stream().distinct().count() > 1,
                "same item registry ID with a custom-name component must retain a distinct fingerprint");
        helper.assertTrue(completedAudit(auditWatermark, playerUuid, pos)
                        .contains("CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED"),
                "unlinked item entities must remain explicitly unresolved");
        helper.assertTrue(unresolvedDropAudit(auditWatermark, playerUuid, pos)
                        .contains("CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED"),
                "unlinked item entities must have their own unresolved audit event");
    }

    private static void assertOneStackChest(GameTestHelper helper, long auditWatermark,
                                            long observationWatermark, String playerUuid, BlockPos pos) {
        String parentId = auditEventId(auditWatermark, playerUuid, pos);
        List<Observation> rows = observations(observationWatermark, parentId);
        helper.assertValueEqual(1, rows.size(), "a one-stack single chest must produce exactly one slot observation");
        Observation row = rows.getFirst();
        helper.assertValueEqual(13, row.slot(), "one-stack slot provenance must retain the original slot index");
        helper.assertValueEqual("minecraft:blaze_rod", row.itemId(), "one-stack item registry ID changed");
        helper.assertValueEqual(5, row.amount(), "one-stack quantity changed");
        helper.assertValueEqual("CONTAINER", row.sourceType(), "one-stack source must remain the broken chest");
        helper.assertValueEqual("UNKNOWN", row.targetType(), "one-stack break must not invent its destination");
        helper.assertTrue(completedAudit(auditWatermark, playerUuid, pos)
                        .contains("\"stack_slots\":1")
                        && completedAudit(auditWatermark, playerUuid, pos).contains("\"total_items\":5"),
                "one-stack parent must conserve its observed quantity");
    }

    private static void assertDoubleChestHalf(GameTestHelper helper, long auditWatermark,
                                              long observationWatermark, String playerUuid,
                                              BlockPos brokenPos, BlockPos remainingPos, ChestType remainingType) {
        String parentId = auditEventId(auditWatermark, playerUuid, brokenPos);
        List<Observation> rows = observations(observationWatermark, parentId);
        helper.assertValueEqual(1, rows.size(), "breaking one double-chest half must snapshot only its own block entity");
        helper.assertValueEqual("minecraft:diamond", rows.get(0).itemId(), "broken double-chest half contents changed");
        helper.assertValueEqual(3, rows.get(0).amount(), "broken double-chest half quantity changed");
        helper.assertTrue(rows.stream().noneMatch(row -> row.itemId().equals("minecraft:emerald")),
                "contents in the surviving chest half must not be claimed by this break");
        helper.assertTrue(unresolvedDropAudit(auditWatermark, playerUuid, brokenPos)
                        .contains("CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED"),
                "double-chest drop linkage must remain explicitly unresolved");
        helper.assertValueEqual(ChestType.SINGLE, remainingType,
                "surviving half must remain a single chest after its partner is destroyed");
    }

    private static void writeRedactedReport(String loader, BlockPos origin, long auditWatermark,
                                            long observationWatermark, String playerUuid,
                                            BlockPos canceled, BlockPos empty, BlockPos single,
                                            BlockPos doubleLeft, BlockPos oneStack, BlockPos missingActor) {
        String directory = System.getenv("ITEMGRAPH_DIFFERENTIAL_REPORT_DIR");
        if (directory == null || directory.isBlank()) return;
        JsonArray events = new JsonArray();
        appendBreakReport(events, auditWatermark, observationWatermark, playerUuid, empty, origin);
        appendBreakReport(events, auditWatermark, observationWatermark, playerUuid, single, origin);
        appendBreakReport(events, auditWatermark, observationWatermark, playerUuid, doubleLeft, origin);
        appendBreakReport(events, auditWatermark, observationWatermark, playerUuid, oneStack, origin);
        appendActorUnavailableReport(events, auditWatermark, missingActor, origin);

        JsonObject report = new JsonObject();
        report.addProperty("raw_schema_version", 1);
        report.addProperty("loader", loader);
        report.addProperty("scenario_id", "player-container-break-contents");
        report.addProperty("world_scope", "isolated_game_test");
        report.add("events", events);
        JsonObject invariants = new JsonObject();
        invariants.addProperty("canceled_break_rows", 0);
        invariants.addProperty("slot_observations", 29);
        invariants.addProperty("source_quantity", 386);
        invariants.addProperty("destination_type", "UNKNOWN");
        invariants.addProperty("drop_links_fabricated", false);
        invariants.addProperty("queue_rejections", 0);
        invariants.addProperty("actor_unavailable_rows", 1);
        report.add("invariants", invariants);
        try {
            Path outputDirectory = Path.of(directory);
            Files.createDirectories(outputDirectory);
            Files.writeString(outputDirectory.resolve("itemgraph-" + loader
                    + "-container-break.raw.json"), report.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not write redacted container-break report", failure);
        }
    }

    private static void appendBreakReport(JsonArray events, long auditWatermark, long observationWatermark,
                                          String playerUuid, BlockPos position, BlockPos origin) {
        String parentId = auditEventId(auditWatermark, playerUuid, position);
        JsonObject parentDetails = parse(completedAudit(auditWatermark, playerUuid, position));
        JsonObject completed = reportEvent(parentId, null, "CONTAINER_BREAK_COMPLETED", "OBSERVED",
                position, origin, "CONTAINER", "UNKNOWN");
        completed.addProperty("actor_ref", "player-1");
        completed.addProperty("break_event_ref", digest(parentDetails.get("break_event_id").getAsString()));
        completed.addProperty("stack_slots", parentDetails.get("stack_slots").getAsInt());
        completed.addProperty("total_items", parentDetails.get("total_items").getAsInt());
        events.add(completed);

        for (Observation observation : observations(observationWatermark, parentId)) {
            JsonObject item = reportEvent(observation.eventId(), parentId, "REMOVE_ITEM", "OBSERVED",
                    position, origin, "CONTAINER", "UNKNOWN");
            item.addProperty("actor_ref", "player-1");
            item.addProperty("slot", observation.slot());
            item.addProperty("item_id", observation.itemId());
            item.addProperty("fingerprint_ref", digest(observation.fingerprint()));
            item.addProperty("quantity", observation.amount());
            events.add(item);
        }

        String unresolvedRaw = unresolvedDropAudit(auditWatermark, playerUuid, position);
        if (unresolvedRaw.isBlank()) return;
        JsonObject unresolvedDetails = parse(unresolvedRaw);
        String unresolvedEventId = unresolvedDetails.get("event_id").getAsString();
        JsonObject unresolved = reportEvent(unresolvedEventId, parentId, "CONTAINER_BREAK_UNRESOLVED",
                "UNRESOLVED", position, origin, "CONTAINER", "UNKNOWN");
        unresolved.addProperty("actor_ref", "player-1");
        unresolved.addProperty("reason_code", unresolvedDetails.get("reason_code").getAsString());
        events.add(unresolved);
    }

    private static void appendActorUnavailableReport(JsonArray events, long auditWatermark,
                                                      BlockPos position, BlockPos origin) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, player_name, CAST(raw_data AS TEXT), detail
                     FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_UNRESOLVED'
                       AND x = ? AND y = ? AND z = ?
                       AND detail = 'reason=CONTAINER_BREAK_ACTOR_UNAVAILABLE'
                     ORDER BY id
                     """)) {
            statement.setLong(1, auditWatermark);
            statement.setDouble(2, position.getX()); statement.setDouble(3, position.getY());
            statement.setDouble(4, position.getZ());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing actor-unavailable audit row");
                if (rows.getString(1) != null || rows.getString(2) != null) {
                    throw new SQLException("actor-unavailable report source unexpectedly has player identity");
                }
                JsonObject details = parse(rows.getString(3));
                String actorStatus = details.get("actor_status").getAsString();
                String reasonCode = details.get("reason_code").getAsString();
                if (!"UNKNOWN".equals(actorStatus)
                        || !"CONTAINER_BREAK_ACTOR_UNAVAILABLE".equals(reasonCode) || rows.next()) {
                    throw new SQLException("actor-unavailable evidence has unexpected status, reason, or count");
                }
                JsonObject unresolved = reportEvent(details.get("event_id").getAsString(), null,
                        "CONTAINER_BREAK_UNRESOLVED", "UNRESOLVED", position, origin, "CONTAINER", "UNKNOWN");
                unresolved.addProperty("actor_status", actorStatus);
                unresolved.addProperty("reason_code", reasonCode);
                events.add(unresolved);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not write actor-unavailable conformance event", failure);
        }
    }

    private static JsonObject reportEvent(String eventId, String parentId, String action,
                                          String evidenceClass, BlockPos absolute, BlockPos origin,
                                          String sourceType, String targetType) {
        JsonObject event = new JsonObject();
        event.addProperty("evidence_ref", digest(eventId));
        if (parentId != null) event.addProperty("parent_ref", digest(parentId));
        event.addProperty("action", action);
        event.addProperty("evidence_class", evidenceClass);
        JsonObject source = new JsonObject();
        source.addProperty("type", sourceType);
        source.addProperty("dimension", "minecraft:overworld");
        source.add("position", relativePosition(absolute, origin));
        event.add("source", source);
        JsonObject target = new JsonObject();
        target.addProperty("type", targetType);
        event.add("destination", target);
        return event;
    }

    private static JsonObject relativePosition(BlockPos absolute, BlockPos origin) {
        JsonObject position = new JsonObject();
        position.addProperty("x", absolute.getX() - origin.getX());
        position.addProperty("y", absolute.getY() - origin.getY());
        position.addProperty("z", absolute.getZ() - origin.getZ());
        return position;
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String auditEventId(long watermark, String playerUuid, BlockPos pos) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT raw_data FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_COMPLETED' AND player_uuid = ?
                       AND x = ? AND y = ? AND z = ? ORDER BY id
                     """)) {
            statement.setLong(1, watermark); statement.setString(2, playerUuid);
            statement.setDouble(3, pos.getX()); statement.setDouble(4, pos.getY()); statement.setDouble(5, pos.getZ());
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("missing container-break summary event");
                return JsonParser.parseString(new String(rows.getBytes(1), StandardCharsets.UTF_8))
                        .getAsJsonObject().get("event_id").getAsString();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read container-break event identity", failure);
        }
    }

    private static String completedAudit(long watermark, String playerUuid, BlockPos pos) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT CAST(raw_data AS TEXT) FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_COMPLETED' AND player_uuid = ?
                       AND x = ? AND y = ? AND z = ? ORDER BY id
                     """)) {
            statement.setLong(1, watermark); statement.setString(2, playerUuid);
            statement.setDouble(3, pos.getX()); statement.setDouble(4, pos.getY()); statement.setDouble(5, pos.getZ());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : ""; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read container-break outcome details", failure);
        }
    }

    private static String unresolvedDropAudit(long watermark, String playerUuid, BlockPos pos) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT CAST(raw_data AS TEXT) FROM ig_audit_events
                     WHERE id > ? AND event_type = 'CONTAINER_BREAK_UNRESOLVED' AND player_uuid = ?
                       AND x = ? AND y = ? AND z = ?
                       AND detail LIKE 'drop_link_status=UNRESOLVED%'
                     ORDER BY id
                     """)) {
            statement.setLong(1, watermark); statement.setString(2, playerUuid);
            statement.setDouble(3, pos.getX()); statement.setDouble(4, pos.getY()); statement.setDouble(5, pos.getZ());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : ""; }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read unresolved container-break outcome", failure);
        }
    }

    private static List<Observation> observations(long watermark, String parentId) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT o.id, o.amount, CAST(o.raw_data AS TEXT), f.item_id, f.fingerprint_hash,
                            source.node_type AS source_type, source.x AS source_x,
                            source.y AS source_y, source.z AS source_z, target.node_type AS target_type
                     FROM ig_observations o
                     JOIN ig_item_fingerprints f ON f.id = o.fingerprint_id
                     JOIN ig_nodes source ON source.id = o.node_id
                     JOIN ig_nodes target ON target.id = o.target_node_id
                     WHERE o.id > ? AND o.action_type = 'REMOVE_ITEM'
                       AND CAST(o.raw_data AS TEXT) LIKE ? ORDER BY o.id
                     """)) {
            statement.setLong(1, watermark);
            statement.setString(2, "%\"cause_event_id\":\"" + parentId + "\"%");
            try (var rows = statement.executeQuery()) {
                List<Observation> result = new ArrayList<>();
                while (rows.next()) {
                    JsonObject raw = JsonParser.parseString(rows.getString(3)).getAsJsonObject();
                    result.add(new Observation(rows.getLong(1), rows.getInt(2), raw.get("slot").getAsInt(),
                            rows.getString(4), rows.getString(5), rows.getString(6),
                            rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getString(10), rows.getString(3),
                            raw.get("event_id").getAsString()));
                }
                result.sort(java.util.Comparator.comparingInt(Observation::slot));
                return List.copyOf(result);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not query container-break item observations", failure);
        }
    }

    private static long observationWatermark() {
        return maxId("ig_observations");
    }

    private static long auditWatermark() {
        return maxId("ig_audit_events");
    }

    private static long maxId(String table) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM " + table)) {
            if (!rows.next()) throw new SQLException("missing watermark result");
            return rows.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read " + table + " watermark", failure);
        }
    }

    private record Observation(long id, int amount, int slot, String itemId, String fingerprint,
                               String sourceType, int sourceX, int sourceY, int sourceZ,
                               String targetType, String rawData, String eventId) { }
}
