package com.itemgraph.gametest;

import com.google.gson.JsonParser;
import com.itemgraph.audit.WorldEventCapture;
import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import net.minecraft.util.RandomSource;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/** Cross-loader fixture that invokes the actual fluid, fire, Enderman, and falling-block paths. */
public final class EnvironmentalWorldEventConformanceFixture {
    public record Scenario(BlockPos fluidSource, BlockPos fluidTarget, BlockPos fireCenter,
                           BlockPos endermanTakeTarget, BlockPos endermanPlaceTarget,
                           String endermanEntityId, BlockPos fallingSource, BlockPos fallingLanding,
                           long fallingEvidenceWatermark, BlockPos exceptionalFluidTarget,
                           BlockPos exceptionalFallingSource, BlockPos earlyFallingSource) { }

    private EnvironmentalWorldEventConformanceFixture() { }

    public static Scenario trigger(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos fluidSource = helper.absolutePos(new BlockPos(2, 8, 2));
        BlockPos fluidTarget = fluidSource.east();
        BlockPos firePos = helper.absolutePos(new BlockPos(7, 3, 2));
        // Keep falling-block evidence in the fixture chunk and drive its vanilla tick
        // explicitly so the landing boundary does not depend on random block scheduling.
        BlockPos fallingSource = helper.absolutePos(new BlockPos(1, 30, 1));
        BlockPos floor = fallingSource.offset(0, -3, 0);
        BlockPos fallingLanding = floor.above();
        BlockPos exceptionalFluidTarget = helper.absolutePos(new BlockPos(4, 8, 4));
        BlockPos exceptionalFallingSource = helper.absolutePos(new BlockPos(6, 8, 6));
        BlockPos earlyFallingSource = helper.absolutePos(new BlockPos(9, 8, 6));
        boolean setupComplete = false;
        FixtureEnderman enderman = null;
        try {
            level.setBlock(fluidSource.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidSource.north(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidSource.south(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidSource.west(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidTarget.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidTarget.north(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidTarget.south(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidTarget.east(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fluidSource, Blocks.WATER.defaultBlockState(), 3);

            boolean fireTickWasEnabled = level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK);
            level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(true, level.getServer());
            try {
                level.setBlock(firePos.below(), Blocks.NETHERRACK.defaultBlockState(), 3);
                level.setBlock(firePos, Blocks.FIRE.defaultBlockState(), 3);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx != 0 || dy != 0 || dz != 0) {
                                level.setBlock(firePos.offset(dx, dy, dz), Blocks.OAK_LEAVES.defaultBlockState(), 3);
                            }
                        }
                    }
                }
                // Drive vanilla FireBlock.tick directly with controlled random inputs. This keeps
                // the fixture on the real wrapped write path without waiting for random-tick luck.
                for (long seed = 0; seed < 256; seed++) {
                    level.getBlockState(firePos).tick(level, firePos, RandomSource.create(seed));
                }
            } finally {
                level.getGameRules().getRule(GameRules.RULE_DOFIRETICK)
                        .set(fireTickWasEnabled, level.getServer());
            }

            enderman = new FixtureEnderman(level);
            BlockPos endermanCenter = helper.absolutePos(new BlockPos(14, 5, 2));
            enderman.moveTo(endermanCenter.getX() + 0.5, endermanCenter.getY(), endermanCenter.getZ() + 0.5,
                    0.0F, 0.0F);
            level.addFreshEntity(enderman);
            BlockPos takeTarget = null;
            BlockPos placeTarget = null;
            Goal takeGoal = enderman.findGoal("EndermanTakeBlockGoal");
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = 1; dy <= 1; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (dx != 0 || dy != 0 || dz != 0) {
                            level.setBlock(endermanCenter.offset(dx, dy, dz), Blocks.DIRT.defaultBlockState(), 3);
                        }
                    }
                }
            }
            for (int attempt = 0; attempt < 500 && enderman.getCarriedBlock() == null; attempt++) {
                takeGoal.tick();
            }
            helper.assertTrue(enderman.getCarriedBlock() != null,
                    "Enderman take goal did not remove a holdable block from its search region");
            for (int dx = -2; dx <= 2 && takeTarget == null; dx++) {
                for (int dz = -2; dz <= 2 && takeTarget == null; dz++) {
                    BlockPos candidate = endermanCenter.offset(dx, 1, dz);
                    if (level.getBlockState(candidate).isAir()) {
                        takeTarget = candidate.immutable();
                    }
                }
            }
            helper.assertTrue(takeTarget != null, "Enderman take goal changed no expected dirt target");
            Goal placeGoal = enderman.findGoal("EndermanLeaveBlockGoal");
            enderman.setCarriedBlock(Blocks.DIRT.defaultBlockState());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(endermanCenter.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(endermanCenter.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            for (int attempt = 0; attempt < 500 && enderman.getCarriedBlock() != null; attempt++) {
                placeGoal.tick();
            }
            helper.assertTrue(enderman.getCarriedBlock() == null,
                    "Enderman place goal did not place its carried block");
            for (int dx = -1; dx <= 1 && placeTarget == null; dx++) {
                for (int dz = -1; dz <= 1 && placeTarget == null; dz++) {
                    BlockPos candidate = endermanCenter.offset(dx, 0, dz);
                    if (level.getBlockState(candidate).is(Blocks.DIRT)) {
                        placeTarget = candidate.immutable();
                    }
                }
            }
            helper.assertTrue(placeTarget != null, "Enderman place goal changed no expected air target to dirt");

            long fallingEvidenceWatermark = latestPersistedAuditEventId();
            level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(fallingSource, Blocks.SAND.defaultBlockState(), 3);
            FallingBlockEntity fallingEntity = FallingBlockEntity.fall(
                    level, fallingSource, Blocks.SAND.defaultBlockState());
            helper.assertTrue(level.getEntity(fallingEntity.getUUID()) == fallingEntity,
                    "falling block entity was not accepted by the test level");
            // Drive the actual server tick at a known landing boundary. Natural fall
            // scheduling is not deterministic in a GameTest's isolated tick region.
            fallingEntity.setPos(fallingLanding.getX() + 0.5,
                    fallingLanding.getY() + 1.25,
                    fallingLanding.getZ() + 0.5);
            fallingEntity.setDeltaMovement(0.0, -1.0, 0.0);
            fallingEntity.tick();
            level.setBlock(exceptionalFluidTarget.below(), Blocks.STONE.defaultBlockState(), 3);
            for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                level.setBlock(exceptionalFluidTarget.relative(side), Blocks.STONE.defaultBlockState(), 3);
            }
            var exceptionalFluidBefore = WorldEventCapture.safeSnapshotBlockState(level, exceptionalFluidTarget);
            level.setBlock(exceptionalFluidTarget, Blocks.WATER.defaultBlockState(), 3);
            WorldEventCapture.recordFluidSpread(level, exceptionalFluidTarget, exceptionalFluidBefore,
                    Direction.EAST, Blocks.WATER.defaultBlockState().getFluidState(), true);

            level.setBlock(exceptionalFallingSource, Blocks.SAND.defaultBlockState(), 3);
            var exceptionalFallingBefore = WorldEventCapture.safeSnapshotBlockState(level, exceptionalFallingSource);
            level.setBlock(exceptionalFallingSource, Blocks.AIR.defaultBlockState(), 3);
            WorldEventCapture.recordFallingBlockSourceFailure(level, exceptionalFallingSource,
                    exceptionalFallingBefore, Blocks.SAND.defaultBlockState());
            level.setBlock(earlyFallingSource.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(earlyFallingSource, Blocks.SAND.defaultBlockState(), 3);
            var earlyFallingBefore = WorldEventCapture.safeSnapshotBlockState(level, earlyFallingSource);
            WorldEventCapture.recordFallingBlockSourceFailure(level, earlyFallingSource,
                    earlyFallingBefore, Blocks.SAND.defaultBlockState());
            setupComplete = true;
            return new Scenario(fluidSource, fluidTarget, firePos, takeTarget, placeTarget,
                    enderman.getUUID().toString(), fallingSource, fallingLanding, fallingEvidenceWatermark,
                    exceptionalFluidTarget, exceptionalFallingSource, earlyFallingSource);
        } finally {
            if (enderman != null) {
                enderman.discard();
            }
            if (!setupComplete) {
                clearEnvironmentalBlocks(level, fluidSource, firePos, fallingSource, floor.above(),
                        exceptionalFluidTarget, exceptionalFallingSource, earlyFallingSource);
            }
        }
    }

    private static final class FixtureEnderman extends EnderMan {
        private FixtureEnderman(Level level) {
            super(EntityType.ENDERMAN, level);
        }

        private Goal findGoal(String simpleName) {
            return goalSelector.getAvailableGoals().stream()
                    .map(WrappedGoal::getGoal)
                    .filter(goal -> goal.getClass().getSimpleName().equals(simpleName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Enderman has no " + simpleName));
        }
    }

    public static void assertPersisted(GameTestHelper helper, Scenario scenario) {
        assertOneAt(helper, "FLUID_BLOCK_CHANGE", scenario.fluidTarget(), "fluid");
        assertAnyInRegion(helper, "FIRE_BLOCK_CHANGE", scenario.fireCenter(), 2, "fire");
        assertEndermanBoundary(helper, scenario.endermanTakeTarget(), scenario.endermanEntityId(),
                "TAKE_BLOCK", "EndermanTakeBlockGoal.tick.removeBlock");
        assertEndermanBoundary(helper, scenario.endermanPlaceTarget(), scenario.endermanEntityId(),
                "PLACE_BLOCK", "EndermanLeaveBlockGoal.tick.setBlock");
        assertFallingBlockPath(helper, scenario.fallingSource(), scenario.fallingLanding(),
                scenario.fallingEvidenceWatermark());
        assertExceptionalPair(helper, "FLUID_BLOCK_CHANGE", scenario.exceptionalFluidTarget(), "fluid");
        assertExceptionalPair(helper, "FALLING_BLOCK_CHANGE", scenario.exceptionalFallingSource(), "falling_block");
        assertEarlyFallingThrow(helper, scenario.earlyFallingSource());
    }

    /** Stop continuing fire/fluid ticks before the later queue and throughput fixtures run. */
    public static void cleanup(GameTestHelper helper, Scenario scenario) {
        clearEnvironmentalBlocks(helper.getLevel(), scenario.fluidSource(), scenario.fireCenter(),
                scenario.fallingSource(), scenario.fallingLanding(), scenario.exceptionalFluidTarget(),
                scenario.exceptionalFallingSource(), scenario.earlyFallingSource());
    }

    private static void clearEnvironmentalBlocks(ServerLevel level, BlockPos fluidSource, BlockPos fireCenter,
                                                 BlockPos fallingSource, BlockPos fallingLanding,
                                                 BlockPos exceptionalFluidTarget,
                                                 BlockPos exceptionalFallingSource,
                                                 BlockPos earlyFallingSource) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlock(fluidSource.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(fireCenter.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(fallingSource, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(fallingLanding, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(fallingLanding.below(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(exceptionalFluidTarget, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(exceptionalFluidTarget.below(), Blocks.AIR.defaultBlockState(), 3);
        for (Direction side : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            level.setBlock(exceptionalFluidTarget.relative(side), Blocks.AIR.defaultBlockState(), 3);
        }
        level.setBlock(exceptionalFallingSource, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(earlyFallingSource, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(earlyFallingSource.below(), Blocks.AIR.defaultBlockState(), 3);
    }

    private static void assertExceptionalPair(GameTestHelper helper, String observedEventType,
                                              BlockPos target, String family) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, detail, raw_data FROM ig_audit_events
                     WHERE ((event_type = ? ) OR event_type = 'WORLD_EFFECT_UNRESOLVED')
                       AND x = ? AND y = ? AND z = ? ORDER BY id
                     """)) {
            statement.setString(1, observedEventType);
            statement.setDouble(2, target.getX());
            statement.setDouble(3, target.getY());
            statement.setDouble(4, target.getZ());
            try (var rows = statement.executeQuery()) {
                boolean foundObserved = false;
                boolean foundUnresolved = false;
                String causeId = null;
                while (rows.next()) {
                    String eventType = rows.getString("event_type");
                    String detail = rows.getString("detail");
                    helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                            family + " exceptional raw and formatted evidence differ");
                    var payload = JsonParser.parseString(detail).getAsJsonObject();
                    helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                            family + " exceptional evidence must not invent item quantity");
                    var metadata = payload.getAsJsonObject("metadata");
                    helper.assertValueEqual("THREW", metadata.get("callback_result").getAsString(),
                            family + " exceptional evidence must preserve the throw result");
                    helper.assertValueEqual(family, metadata.get("cause_family").getAsString(),
                            family + " exceptional evidence changed cause family");
                    String rowCauseId = metadata.get("cause_event_id").getAsString();
                    if (causeId == null) {
                        causeId = rowCauseId;
                    } else {
                        helper.assertValueEqual(causeId, rowCauseId,
                                family + " observed delta and unresolved marker must share a cause ID");
                    }
                    if (observedEventType.equals(eventType)) {
                        foundObserved = true;
                        helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                                family + " actual state delta must remain observed evidence");
                        helper.assertValueEqual("CONFIRMED_CHANGE", payload.get("outcome").getAsString(),
                                family + " actual state delta must remain confirmed");
                        if ("falling_block".equals(family)) {
                            helper.assertFalse(metadata.has("entity_uuid"),
                                    "exceptional falling source evidence must not invent an entity UUID");
                        }
                    } else {
                        foundUnresolved = true;
                        helper.assertValueEqual("UNRESOLVED", payload.get("evidence_class").getAsString(),
                                family + " exceptional completion must be unresolved");
                        helper.assertValueEqual("WORLD_EFFECT_PARTIAL", payload.get("reason_code").getAsString(),
                                family + " exceptional completion must use the partial reason");
                        helper.assertValueEqual("1", metadata.get("callback_exception_count").getAsString(),
                                family + " exceptional completion must count its exception");
                    }
                }
                helper.assertTrue(foundObserved, family + " exceptional fixture omitted its confirmed state delta");
                helper.assertTrue(foundUnresolved, family + " exceptional fixture omitted its unresolved marker");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect exceptional " + family + " evidence", failure);
        }
    }

    private static void assertEarlyFallingThrow(GameTestHelper helper, BlockPos target) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT event_type, detail, raw_data FROM ig_audit_events
                     WHERE event_type IN ('FALLING_BLOCK_CHANGE', 'WORLD_EFFECT_UNRESOLVED')
                       AND x = ? AND y = ? AND z = ? ORDER BY id
                     """)) {
            statement.setDouble(1, target.getX());
            statement.setDouble(2, target.getY());
            statement.setDouble(3, target.getZ());
            try (var rows = statement.executeQuery()) {
                int rowCount = 0;
                while (rows.next()) {
                    rowCount++;
                    helper.assertValueEqual("WORLD_EFFECT_UNRESOLVED", rows.getString("event_type"),
                            "falling-block throw before source removal must not claim an observed delta");
                    String detail = rows.getString("detail");
                    helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                            "early falling throw raw and formatted evidence differ");
                    var payload = JsonParser.parseString(detail).getAsJsonObject();
                    helper.assertValueEqual("UNRESOLVED", payload.get("evidence_class").getAsString(),
                            "early falling throw must remain unresolved");
                    helper.assertValueEqual("WORLD_EFFECT_PARTIAL", payload.get("reason_code").getAsString(),
                            "early falling throw must use the partial reason");
                    var metadata = payload.getAsJsonObject("metadata");
                    helper.assertValueEqual("THREW", metadata.get("callback_result").getAsString(),
                            "early falling throw must retain the exceptional result");
                    helper.assertFalse(metadata.has("movement"),
                            "early falling throw must not claim that the source was removed");
                    helper.assertFalse(metadata.has("entity_uuid"),
                            "early falling throw must not invent an entity UUID");
                }
                helper.assertValueEqual(1, rowCount,
                        "early falling throw must persist one unresolved row and no source delta");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect early falling-block exception evidence", failure);
        }
    }

    private static void assertEndermanBoundary(GameTestHelper helper, BlockPos target, String entityId,
                                               String movement, String captureBoundary) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, detail, raw_data, ingest_event_uuid FROM ig_audit_events
                     WHERE event_type = 'ENDERMAN_BLOCK_MOVE' AND x = ? AND y = ? AND z = ?
                     ORDER BY id
                     """)) {
            statement.setDouble(1, target.getX());
            statement.setDouble(2, target.getY());
            statement.setDouble(3, target.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), movement + " evidence was not persisted at " + target);
                helper.assertTrue(rows.getString("player_uuid") == null,
                        movement + " must not fabricate a player actor");
                String detail = rows.getString("detail");
                helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                        movement + " raw and formatted evidence differ");
                var payload = JsonParser.parseString(detail).getAsJsonObject();
                helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                        movement + " must remain observed evidence");
                helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                        movement + " must not claim item quantity");
                helper.assertTrue(!payload.get("before_state").getAsString()
                                .equals(payload.get("after_state").getAsString()),
                        movement + " must contain an exact state delta");
                var metadata = payload.getAsJsonObject("metadata");
                helper.assertValueEqual(entityId, metadata.get("entity_uuid").getAsString(),
                        movement + " must identify the fixture Enderman");
                helper.assertValueEqual(movement, metadata.get("movement").getAsString(),
                        "Enderman direction metadata changed");
                helper.assertValueEqual(captureBoundary, metadata.get("capture_boundary").getAsString(),
                        "Enderman evidence came from the wrong write boundary");
                helper.assertTrue(rows.getString("ingest_event_uuid") != null,
                        movement + " must have a durable ItemGraph evidence ID");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect Enderman " + movement + " evidence", failure);
        }
    }

    private static void assertFallingBlockPath(GameTestHelper helper, BlockPos source, BlockPos landing,
                                               long eventIdWatermark) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var sourceStatement = connection.prepareStatement("""
                     SELECT id, detail, raw_data, ingest_event_uuid FROM ig_audit_events
                     WHERE event_type = 'FALLING_BLOCK_CHANGE' AND x = ? AND y = ? AND z = ? AND id > ?
                     ORDER BY id
                     """)) {
            sourceStatement.setDouble(1, source.getX());
            sourceStatement.setDouble(2, source.getY());
            sourceStatement.setDouble(3, source.getZ());
            sourceStatement.setLong(4, eventIdWatermark);
            try (var sourceRows = sourceStatement.executeQuery()) {
                helper.assertTrue(sourceRows.next(), "falling-block source removal was not persisted at " + source);
                var sourcePayload = parseVerifiedPayload(helper, sourceRows, "falling-block source");
                var sourceMetadata = sourcePayload.getAsJsonObject("metadata");
                helper.assertValueEqual("SOURCE_REMOVED", sourceMetadata.get("movement").getAsString(),
                        "falling-block source movement changed");
                String entityId = sourceMetadata.get("entity_uuid").getAsString();
                helper.assertValueEqual(entityId, sourceMetadata.get("cause_event_id").getAsString(),
                        "falling-block cause identity must be its entity UUID");

                try (var landingStatement = connection.prepareStatement("""
                        SELECT detail, raw_data, ingest_event_uuid FROM ig_audit_events
                        WHERE event_type = 'FALLING_BLOCK_CHANGE' AND x = ? AND y = ? AND z = ?
                          AND id > ?
                          AND json_extract(detail, '$.metadata.entity_uuid') = ?
                        ORDER BY id
                        """)) {
                    landingStatement.setDouble(1, landing.getX());
                    landingStatement.setDouble(2, landing.getY());
                    landingStatement.setDouble(3, landing.getZ());
                    landingStatement.setLong(4, eventIdWatermark);
                    landingStatement.setString(5, entityId);
                    try (var landingRows = landingStatement.executeQuery()) {
                        helper.assertTrue(landingRows.next(), "falling entity " + entityId
                                + " has no persisted landing at " + landing);
                        var landingPayload = parseVerifiedPayload(helper, landingRows, "falling-block landing");
                        var landingMetadata = landingPayload.getAsJsonObject("metadata");
                        helper.assertValueEqual("LANDING", landingMetadata.get("movement").getAsString(),
                                "falling-block landing movement changed");
                        helper.assertValueEqual(entityId, landingMetadata.get("cause_event_id").getAsString(),
                                "falling-block landing must retain its source entity identity");
                        helper.assertTrue(!landingPayload.get("before_state").getAsString()
                                        .equals(landingPayload.get("after_state").getAsString()),
                                "falling-block landing must contain an actual state delta");
                        helper.assertValueEqual("Block{minecraft:sand}",
                                landingPayload.get("after_state").getAsString(),
                                "falling-block landing recorded the wrong resulting block");
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect persisted falling-block path", failure);
        }
    }

    private static long latestPersistedAuditEventId() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph evidence watermark", failure);
        }
    }

    private static com.google.gson.JsonObject parseVerifiedPayload(GameTestHelper helper,
                                                                    java.sql.ResultSet rows,
                                                                    String label) throws SQLException {
        String detail = rows.getString("detail");
        helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                label + " raw and formatted evidence differ");
        var payload = JsonParser.parseString(detail).getAsJsonObject();
        helper.assertValueEqual(rows.getString("ingest_event_uuid"), payload.get("event_id").getAsString(),
                label + " evidence identity changed");
        helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                label + " must remain observed evidence");
        helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                label + " must not invent item quantity");
        helper.assertTrue(payload.get("before_state").getAsString()
                        .equals("Block{minecraft:sand}") || payload.get("after_state").getAsString()
                        .equals("Block{minecraft:sand}"), label + " must refer to the sand block");
        return payload;
    }

    private static void assertOneAt(GameTestHelper helper, String type, BlockPos pos, String family) {
        assertQuery(helper, type, pos, 0, family, true);
    }

    private static void assertAnyInRegion(GameTestHelper helper, String type, BlockPos center,
                                          int radius, String family) {
        assertQuery(helper, type, center, radius, family, false);
    }

    private static void assertQuery(GameTestHelper helper, String type, BlockPos center,
                                    int radius, String family, boolean exact) {
        String sql = exact ? """
                SELECT player_uuid, detail, raw_data, ingest_event_uuid FROM ig_audit_events
                WHERE event_type = ? AND x = ? AND y = ? AND z = ? ORDER BY id
                """ : """
                SELECT player_uuid, detail, raw_data, ingest_event_uuid FROM ig_audit_events
                WHERE event_type = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
                ORDER BY id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            if (exact) {
                statement.setDouble(2, center.getX());
                statement.setDouble(3, center.getY());
                statement.setDouble(4, center.getZ());
            } else {
                statement.setDouble(2, center.getX() - radius);
                statement.setDouble(3, center.getX() + radius);
                statement.setDouble(4, center.getY() - radius);
                statement.setDouble(5, center.getY() + radius + 3);
                statement.setDouble(6, center.getZ() - radius);
                statement.setDouble(7, center.getZ() + radius);
            }
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), type + " was not persisted in the expected region around " + center);
                do {
                    helper.assertTrue(rows.getString("player_uuid") == null,
                            type + " must not invent a player actor");
                    String detail = rows.getString("detail");
                    helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                            type + " raw and formatted evidence differ");
                    var payload = JsonParser.parseString(detail).getAsJsonObject();
                    helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                            type + " must retain observed evidence classification");
                    helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                            type + " must not claim item quantity");
                    helper.assertValueEqual(rows.getString("ingest_event_uuid"),
                            payload.get("event_id").getAsString(), type + " evidence identity changed");
                    helper.assertValueEqual(family,
                            payload.getAsJsonObject("metadata").get("cause_family").getAsString(),
                            type + " cause family changed");
                    helper.assertTrue(!payload.get("before_state").getAsString()
                                    .equals(payload.get("after_state").getAsString()),
                            type + " must contain an actual state delta");
                } while (rows.next() && !exact);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect persisted " + type + " evidence", failure);
        }
    }
}
