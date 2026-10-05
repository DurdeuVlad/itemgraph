package com.itemgraph.gametest;

import com.google.gson.JsonParser;
import com.itemgraph.audit.WorldEventCapture;
import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Explosion;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable cross-loader fixture for successful and no-effect explosion paths. */
public final class ExplosionWorldEventConformanceFixture {
    private ExplosionWorldEventConformanceFixture() { }

    public static Set<BlockPos> triggerDestructiveExplosion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer source = helper.makeMockServerPlayerInLevel();
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        Set<BlockPos> placed = new HashSet<>();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    BlockPos target = center.offset(x, y, z);
                    level.setBlock(target, Blocks.COBBLESTONE.defaultBlockState(), 3);
                    placed.add(target.immutable());
                }
            }
        }
        level.explode(source, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5,
                6.0F, false, Level.ExplosionInteraction.TNT);
        Set<BlockPos> changed = placed.stream()
                .filter(pos -> !level.getBlockState(pos).is(Blocks.COBBLESTONE))
                .map(BlockPos::immutable)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        helper.assertTrue(!changed.isEmpty(), "fixture explosion did not change any target block");
        return changed;
    }

    public static BlockPos triggerNoBlockEffectExplosion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer source = helper.makeMockServerPlayerInLevel();
        BlockPos center = helper.absolutePos(new BlockPos(7, 1, 2));
        BlockPos target = center.offset(2, 0, 0);
        level.setBlock(target, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.explode(source, center.getX() + 0.5, center.getY() + 0.5, center.getZ() + 0.5,
                4.0F, false, Level.ExplosionInteraction.NONE);
        helper.assertTrue(level.getBlockState(target).is(Blocks.COBBLESTONE),
                "no-block-effect explosion unexpectedly changed its target");
        return target;
    }

    public static BlockPos triggerPartialCoverage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer source = helper.makeMockServerPlayerInLevel();
        BlockPos center = helper.absolutePos(new BlockPos(12, 1, 2));
        BlockPos loadedCandidate = center.offset(1, 0, 0);
        BlockPos unavailableCandidate = center.offset(512, 0, 0);
        level.setBlock(loadedCandidate, Blocks.COBBLESTONE.defaultBlockState(), 3);
        Explosion explosion = level.explode(source, center.getX() + 0.5, center.getY() + 0.5,
                center.getZ() + 0.5, 2.0F, false, Level.ExplosionInteraction.NONE);
        WorldEventCapture.ExplosionSnapshot partial = WorldEventCapture.snapshotExplosion(
                level, java.util.Arrays.asList(loadedCandidate, unavailableCandidate, null));
        helper.assertTrue(!partial.beforeStates().containsKey(unavailableCandidate),
                "partial-coverage fixture unexpectedly loaded the distant candidate chunk");
        level.setBlock(loadedCandidate, Blocks.AIR.defaultBlockState(), 3);
        // Exercise the exceptional-exit result contract directly: partial block
        // changes remain observed and the incomplete outcome remains unresolved.
        WorldEventCapture.recordExplosionResults(level, explosion, partial, WorldEventCapture.ExplosionExit.THREW);

        WorldEventCapture.ExplosionSnapshot capped = WorldEventCapture.snapshotExplosion(
                level, java.util.Collections.nCopies(4_097, loadedCandidate));
        helper.assertValueEqual(4_097, capped.affectedPositionCount(),
                "candidate cap fixture must preserve the full input count");
        helper.assertValueEqual(1, capped.beforeStates().size(),
                "duplicate candidate positions must be deduplicated in the bounded snapshot");
        helper.assertTrue(capped.isPartial(),
                "candidate cap fixture must mark truncated position coverage as partial");
        helper.assertTrue(capped.candidateCoverageTruncated(),
                "candidate cap fixture must identify truncation separately from duplicates");
        WorldEventCapture.ExplosionSnapshot duplicates = WorldEventCapture.snapshotExplosion(
                level, List.of(loadedCandidate, loadedCandidate));
        helper.assertFalse(duplicates.isPartial(),
                "duplicate candidates within the cap must not be mistaken for missing state coverage");
        return center;
    }

    public static void assertConfirmedChange(GameTestHelper helper, Set<BlockPos> targets) {
        int minX = targets.stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = targets.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY = targets.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int maxY = targets.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ = targets.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = targets.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, level_id, x, y, z, subject_id, detail, raw_data, ingest_event_uuid
                     FROM ig_audit_events
                     WHERE event_type = 'EXPLOSION_BLOCK_CHANGE'
                       AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
                     ORDER BY id
                     """)) {
            statement.setInt(1, minX);
            statement.setInt(2, maxX);
            statement.setInt(3, minY);
            statement.setInt(4, maxY);
            statement.setInt(5, minZ);
            statement.setInt(6, maxZ);
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "confirmed explosion block change was not persisted");
                Set<String> positions = new HashSet<>();
                Set<String> evidenceIds = new HashSet<>();
                String causeEventId = null;
                boolean foundChangedTarget = false;
                do {
                    helper.assertTrue(rows.getString("player_uuid") == null,
                            "explosion evidence must not fabricate a player actor");
                    helper.assertValueEqual("minecraft:overworld", rows.getString("level_id"),
                            "explosion evidence must preserve the dimension");
                    String position = rows.getDouble("x") + ":" + rows.getDouble("y") + ":" + rows.getDouble("z");
                    helper.assertTrue(positions.add(position), "explosion emitted duplicate evidence for " + position);
                    BlockPos blockPos = new BlockPos((int) rows.getDouble("x"),
                            (int) rows.getDouble("y"), (int) rows.getDouble("z"));
                    foundChangedTarget |= targets.contains(blockPos);
                    if (targets.contains(blockPos)) {
                        helper.assertValueEqual("minecraft:cobblestone", rows.getString("subject_id"),
                                "explosion evidence must identify the affected block");
                    }

                    String ingestId = rows.getString("ingest_event_uuid");
                    helper.assertValueEqual(UUID.fromString(ingestId).toString(), ingestId,
                            "explosion evidence must have a stable ItemGraph evidence ID");
                    helper.assertTrue(evidenceIds.add(ingestId), "explosion rows reused an evidence ID");
                    String detail = rows.getString("detail");
                    helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                            "raw and formatted evidence payloads must preserve identical source details");
                    var payload = JsonParser.parseString(detail).getAsJsonObject();
                    helper.assertValueEqual("CONFIRMED_CHANGE", payload.get("outcome").getAsString(),
                            "explosion row must be emitted only after a confirmed state delta");
                    helper.assertValueEqual("OBSERVED", payload.get("evidence_class").getAsString(),
                            "confirmed explosion delta must remain observed evidence");
                    helper.assertValueEqual("DIRECT_STATE_DELTA", payload.get("source_reliability").getAsString(),
                            "explosion result must identify its post-effect state comparison");
                    helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                            "block changes must never add item quantity");
                    helper.assertTrue(!payload.get("before_state").getAsString()
                                    .equals(payload.get("after_state").getAsString()),
                            "confirmed explosion row must retain distinct before and after states");
                    helper.assertValueEqual(ingestId, payload.get("event_id").getAsString(),
                            "raw payload ID must match the stored ItemGraph evidence ID");
                    String rowCauseId = payload.getAsJsonObject("metadata").get("cause_event_id").getAsString();
                    if (causeEventId == null) {
                        causeEventId = rowCauseId;
                    } else {
                        helper.assertValueEqual(causeEventId, rowCauseId,
                                "one explosion must share one cause event identity across changed blocks");
                    }
                } while (rows.next());
                helper.assertTrue(foundChangedTarget,
                        "at least one actual block change from the fixture explosion must be represented");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect durable explosion evidence", failure);
        }
    }

    public static void assertNoConfirmedChange(GameTestHelper helper, BlockPos unchangedTarget) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT 1 FROM ig_audit_events
                     WHERE event_type = 'EXPLOSION_BLOCK_CHANGE'
                       AND x = ? AND y = ? AND z = ? LIMIT 1
                     """)) {
            statement.setDouble(1, unchangedTarget.getX());
            statement.setDouble(2, unchangedTarget.getY());
            statement.setDouble(3, unchangedTarget.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertFalse(rows.next(), "an unchanged block must not be reported as an explosion change");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not verify unchanged explosion target", failure);
        }
    }

    public static void assertPartialCoverage(GameTestHelper helper, BlockPos center) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT player_uuid, x, y, z, detail, raw_data, ingest_event_uuid
                     FROM ig_audit_events
                     WHERE event_type = 'WORLD_EFFECT_UNRESOLVED'
                       AND x = ? AND y = ? AND z = ?
                     ORDER BY id DESC LIMIT 1
                     """)) {
            statement.setDouble(1, center.getX());
            statement.setDouble(2, center.getY());
            statement.setDouble(3, center.getZ());
            try (var rows = statement.executeQuery()) {
                helper.assertTrue(rows.next(), "incomplete explosion coverage did not persist an unresolved row");
                helper.assertTrue(rows.getString("player_uuid") == null,
                        "partial explosion evidence must not fabricate a player actor");
                String detail = rows.getString("detail");
                helper.assertValueEqual(detail, new String(rows.getBytes("raw_data"), StandardCharsets.UTF_8),
                        "partial raw and formatted evidence must preserve identical details");
                var payload = JsonParser.parseString(detail).getAsJsonObject();
                helper.assertValueEqual("UNRESOLVED", payload.get("evidence_class").getAsString(),
                        "partial explosion coverage must remain unresolved");
                helper.assertValueEqual("UNRESOLVED", payload.get("outcome").getAsString(),
                        "partial explosion coverage must not be presented as a confirmed change");
                helper.assertValueEqual("WORLD_EFFECT_PARTIAL", payload.get("reason_code").getAsString(),
                        "partial explosion coverage must use the stable reason code");
                var metadata = payload.getAsJsonObject("metadata");
                helper.assertValueEqual("THREW", metadata.get("finalize_exit").getAsString(),
                        "exceptional finalization must be explicit in partial evidence");
                helper.assertValueEqual(1, metadata.get("finalize_exception_count").getAsInt(),
                        "exceptional finalization must retain its failure count");
                helper.assertValueEqual("NONE", payload.get("quantity_semantics").getAsString(),
                        "partial block evidence must not create item quantity");
                helper.assertValueEqual(3, metadata.get("affected_position_count").getAsInt(),
                        "partial evidence must retain the complete candidate count");
                helper.assertValueEqual(1, metadata.get("captured_position_count").getAsInt(),
                        "partial evidence must retain the captured candidate count");
                helper.assertValueEqual(1, metadata.get("unavailable_position_count").getAsInt(),
                        "partial evidence must count candidates in unloaded chunks");
                helper.assertValueEqual(1, metadata.get("snapshot_failure_count").getAsInt(),
                        "partial evidence must count invalid candidates without stopping later processing");
                String evidenceId = rows.getString("ingest_event_uuid");
                helper.assertValueEqual(evidenceId, payload.get("event_id").getAsString(),
                        "partial raw payload ID must match the durable evidence ID");
                String unresolvedCauseId = metadata.get("cause_event_id").getAsString();
                try (var changedStatement = connection.prepareStatement("""
                        SELECT detail, raw_data FROM ig_audit_events
                        WHERE event_type = 'EXPLOSION_BLOCK_CHANGE' AND x = ? AND y = ? AND z = ?
                        ORDER BY id DESC LIMIT 1
                        """)) {
                    changedStatement.setDouble(1, center.getX() + 1);
                    changedStatement.setDouble(2, center.getY());
                    changedStatement.setDouble(3, center.getZ());
                    try (var changedRows = changedStatement.executeQuery()) {
                        helper.assertTrue(changedRows.next(),
                                "exceptional explosion exit lost a confirmed partial block change");
                        String changedDetail = changedRows.getString("detail");
                        helper.assertValueEqual(changedDetail,
                                new String(changedRows.getBytes("raw_data"), StandardCharsets.UTF_8),
                                "partial confirmed raw and formatted evidence differ");
                        var changedPayload = JsonParser.parseString(changedDetail).getAsJsonObject();
                        helper.assertValueEqual("OBSERVED", changedPayload.get("evidence_class").getAsString(),
                                "confirmed partial block change must remain observed");
                        helper.assertValueEqual("CONFIRMED_CHANGE", changedPayload.get("outcome").getAsString(),
                                "confirmed partial block change must not be downgraded to unresolved");
                        helper.assertValueEqual(unresolvedCauseId,
                                changedPayload.getAsJsonObject("metadata").get("cause_event_id").getAsString(),
                                "partial confirmed and unresolved rows must share one cause ID");
                        helper.assertValueEqual("THREW",
                                changedPayload.getAsJsonObject("metadata").get("finalize_exit").getAsString(),
                                "partial confirmed delta must identify its exceptional exit");
                    }
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not inspect partial explosion evidence", failure);
        }
    }
}
