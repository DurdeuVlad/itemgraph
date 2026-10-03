package com.itemgraph.gametest;

import com.google.gson.JsonParser;
import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.itemgraph.audit.WorldEventCapture;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Durable cross-loader fixture for successful and no-movement piston results. */
public final class PistonWorldEventConformanceFixture {
    public record Scenario(BlockPos piston, BlockPos movedFrom, BlockPos movedTo) { }

    private PistonWorldEventConformanceFixture() { }

    public static Scenario triggerSuccessfulPiston(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos piston = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos movedFrom = piston.relative(Direction.EAST);
        BlockPos movedTo = movedFrom.relative(Direction.EAST);
        level.setBlock(movedFrom, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(piston, Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
        level.setBlock(piston.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        helper.assertTrue(level.hasNeighborSignal(piston),
                "redstone block above the piston did not provide a neighbor signal");
        return new Scenario(piston, movedFrom, movedTo);
    }

    public static Scenario triggerBlockedPiston(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos piston = helper.absolutePos(new BlockPos(9, 1, 2));
        BlockPos obstruction = piston.relative(Direction.EAST);
        level.setBlock(obstruction, Blocks.OBSIDIAN.defaultBlockState(), 3);
        level.setBlock(piston, Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST), 3);
        level.setBlock(piston.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        // Vanilla's pre-check skips queueing when this structure cannot resolve.
        // Dispatch the block event directly to verify the trigger's false result.
        level.blockEvent(piston, Blocks.PISTON, PistonBaseBlock.TRIGGER_EXTEND,
                Direction.EAST.get3DDataValue());
        helper.assertFalse(level.getBlockState(piston).getValue(PistonBaseBlock.EXTENDED),
                "piston with an unmovable obstruction unexpectedly extended");
        return new Scenario(piston, obstruction, obstruction);
    }

    /** Simulates a throw after a piston trigger has partially changed a sampled block. */
    public static Scenario triggerExceptionalPiston(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos piston = helper.absolutePos(new BlockPos(2, 1, 9));
        BlockPos movedFrom = piston.relative(Direction.EAST);
        BlockPos movedTo = movedFrom.relative(Direction.EAST);
        BlockState pistonState = Blocks.PISTON.defaultBlockState()
                .setValue(PistonBaseBlock.FACING, Direction.EAST);
        level.setBlock(movedFrom, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(piston, pistonState, 3);
        WorldEventCapture.beginPiston(level, pistonState, piston, PistonBaseBlock.TRIGGER_EXTEND);
        level.setBlock(movedFrom, Blocks.AIR.defaultBlockState(), 3);
        WorldEventCapture.abortPiston(level, piston);
        return new Scenario(piston, movedFrom, movedTo);
    }

    public static void assertSuccessfulMove(GameTestHelper helper, Scenario scenario) {
        helper.assertTrue(helper.getLevel().getBlockState(scenario.piston())
                        .getValue(PistonBaseBlock.EXTENDED),
                "powered piston did not report a successful extension");
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, x, y, z, detail, raw_data, ingest_event_uuid
                     FROM ig_audit_events
                     WHERE event_type = 'PISTON_BLOCK_MOVE'
                       AND ((x = ? AND y = ? AND z = ?) OR (x = ? AND y = ? AND z = ?))
                     ORDER BY id
                     """)) {
            statement.setDouble(1, scenario.movedFrom().getX());
            statement.setDouble(2, scenario.movedFrom().getY());
            statement.setDouble(3, scenario.movedFrom().getZ());
            statement.setDouble(4, scenario.movedTo().getX());
            statement.setDouble(5, scenario.movedTo().getY());
            statement.setDouble(6, scenario.movedTo().getZ());
            try (var rows = statement.executeQuery()) {
                Set<String> evidenceIds = new HashSet<>();
                String causeEventId = null;
                boolean foundMovedFrom = false;
                boolean foundMovedTo = false;
                while (rows.next()) {
                    helper.assertTrue(rows.getString("player_uuid") == null,
                            "piston evidence must not fabricate a player actor");
                    String evidenceId = rows.getString("ingest_event_uuid");
                    helper.assertValueEqual(UUID.fromString(evidenceId).toString(), evidenceId,
                            "piston evidence must have a UUID evidence identity");
                    helper.assertTrue(evidenceIds.add(evidenceId), "piston rows reused an evidence ID");
                    String detail = rows.getString("detail");
                    helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                            "piston raw and formatted evidence must match");
                    var payload = JsonParser.parseString(detail).getAsJsonObject();
                    helper.assertValueEqual(evidenceId, payload.get("event_id").getAsString(),
                            "piston payload and stored evidence IDs must match");
                    helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                            "confirmed piston changes must remain observed evidence");
                    helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                            "piston block changes must not create item quantity");
                    helper.assertValueEqual("DIRECT_STATE_DELTA", payload.get("source_reliability").getAsString(),
                            "confirmed piston changes must be classified as direct state deltas");
                    helper.assertTrue(!payload.get("before_state").getAsString()
                                    .equals(payload.get("after_state").getAsString()),
                            "confirmed piston evidence must preserve a real before/after delta");
                    var metadata = payload.getAsJsonObject("metadata");
                    helper.assertValueEqual("PistonBaseBlock.triggerEvent.return",
                            metadata.get("capture_boundary").getAsString(),
                            "piston evidence must identify its immediate return-boundary capture");
                    helper.assertTrue(payload.get("after_state").getAsString().contains("moving_piston"),
                            "piston fixture must document that return-boundary state can be transient");
                    String rowCauseId = metadata.get("cause_event_id").getAsString();
                    if (causeEventId == null) {
                        causeEventId = rowCauseId;
                    } else {
                        helper.assertValueEqual(causeEventId, rowCauseId,
                                "one piston movement must share one cause identity");
                    }
                    int x = (int) rows.getDouble("x");
                    int y = (int) rows.getDouble("y");
                    int z = (int) rows.getDouble("z");
                    foundMovedFrom |= x == scenario.movedFrom().getX()
                            && y == scenario.movedFrom().getY() && z == scenario.movedFrom().getZ();
                    foundMovedTo |= x == scenario.movedTo().getX()
                            && y == scenario.movedTo().getY() && z == scenario.movedTo().getZ();
                }
                helper.assertTrue(!evidenceIds.isEmpty(), "piston movement evidence was not persisted");
                helper.assertTrue(foundMovedFrom, "piston evidence omitted the source block position");
                helper.assertTrue(foundMovedTo, "piston evidence omitted the destination block position");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect durable piston evidence", failure);
        }
    }

    public static void assertBlockedAttempt(GameTestHelper helper, Scenario scenario) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, detail, raw_data, ingest_event_uuid
                     FROM ig_audit_events
                     WHERE event_type = 'PISTON_BLOCK_ATTEMPT'
                       AND x = ? AND y = ? AND z = ?
                     ORDER BY id DESC LIMIT 1
                     """)) {
            statement.setDouble(1, scenario.piston().getX());
            statement.setDouble(2, scenario.piston().getY());
            statement.setDouble(3, scenario.piston().getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "blocked piston result was not persisted");
                helper.assertTrue(rows.getString("player_uuid") == null,
                        "blocked piston evidence must not fabricate a player actor");
                String evidenceId = rows.getString("ingest_event_uuid");
                String detail = rows.getString("detail");
                helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                        "blocked piston raw and formatted evidence must match");
                var payload = JsonParser.parseString(detail).getAsJsonObject();
                helper.assertValueEqual(evidenceId, payload.get("event_id").getAsString(),
                        "blocked piston payload and stored evidence IDs must match");
                helper.assertValueEqual("UNCHANGED", payload.get("outcome").getAsString(),
                        "a blocked piston must not be presented as a successful movement");
                helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                        "the observed no-movement result must remain observed evidence");
                helper.assertValueEqual("GAME_CALLBACK_ATTEMPT", payload.get("source_reliability").getAsString(),
                        "a no-movement piston result must be classified as a callback attempt, not a state delta");
                helper.assertFalse(payload.has("before_state") || payload.has("after_state"),
                        "a piston attempt without a state change must not claim a state delta");
                helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                        "a blocked piston must not create item quantity");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect blocked piston evidence", failure);
        }
    }

    public static void assertExceptionalAttempt(GameTestHelper helper, Scenario scenario) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, x, y, z, detail, raw_data, ingest_event_uuid
                     FROM ig_audit_events
                     WHERE ((event_type = 'PISTON_BLOCK_MOVE' AND x = ? AND y = ? AND z = ?)
                         OR (event_type = 'WORLD_EFFECT_UNRESOLVED' AND x = ? AND y = ? AND z = ?))
                     ORDER BY id
                     """)) {
            statement.setDouble(1, scenario.movedFrom().getX());
            statement.setDouble(2, scenario.movedFrom().getY());
            statement.setDouble(3, scenario.movedFrom().getZ());
            statement.setDouble(4, scenario.piston().getX());
            statement.setDouble(5, scenario.piston().getY());
            statement.setDouble(6, scenario.piston().getZ());
            try (var rows = statement.executeQuery()) {
                boolean foundDelta = false;
                boolean foundUnresolved = false;
                String causeEventId = null;
                while (rows.next()) {
                    String eventType = rows.getString("event_type");
                    String detail = rows.getString("detail");
                    helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                            "exceptional piston raw and formatted evidence must match");
                    var payload = JsonParser.parseString(detail).getAsJsonObject();
                    helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                            "exceptional piston evidence must not invent item quantity");
                    var metadata = payload.getAsJsonObject("metadata");
                    helper.assertValueEqual("THREW", metadata.get("callback_result").getAsString(),
                            "exceptional piston result must retain the throw outcome");
                    String rowCauseId = metadata.get("cause_event_id").getAsString();
                    if (causeEventId == null) {
                        causeEventId = rowCauseId;
                    } else {
                        helper.assertValueEqual(causeEventId, rowCauseId,
                                "exceptional piston delta and unresolved row must share one cause ID");
                    }
                    if ("PISTON_BLOCK_MOVE".equals(eventType)) {
                        foundDelta = true;
                        helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                                "confirmed exceptional piston deltas must remain observed evidence");
                        helper.assertValueEqual("CONFIRMED_CHANGE", payload.get("outcome").getAsString(),
                                "partial piston block changes must remain confirmed observations");
                    } else {
                        foundUnresolved = true;
                        helper.assertValueEqual("UNRESOLVED", payload.get("evidence_class").getAsString(),
                                "exceptional piston coverage must remain unresolved evidence");
                        helper.assertValueEqual("UNRESOLVED", payload.get("outcome").getAsString(),
                                "a thrown piston callback must not appear complete");
                        helper.assertValueEqual("WORLD_EFFECT_PARTIAL", payload.get("reason_code").getAsString(),
                                "thrown piston callback must use the partial-coverage reason");
                        helper.assertValueEqual("1", metadata.get("callback_exception_count").getAsString(),
                                "thrown piston callback must record its exception count");
                    }
                }
                helper.assertTrue(foundDelta, "exceptional piston fixture omitted its confirmed partial delta");
                helper.assertTrue(foundUnresolved, "exceptional piston fixture omitted unresolved coverage evidence");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect exceptional piston evidence", failure);
        }
    }
}
