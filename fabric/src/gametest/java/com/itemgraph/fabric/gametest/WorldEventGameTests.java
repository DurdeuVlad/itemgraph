package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.ExplosionWorldEventConformanceFixture;
import com.itemgraph.gametest.PistonWorldEventConformanceFixture;
import com.itemgraph.gametest.WorldEventReplayReportFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import com.itemgraph.ingest.InternalObservationService;

public final class WorldEventGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "aa_itemgraph_world_explosion", timeoutTicks = 500)
    public void explosionPersistsOnlyConfirmedBlockChanges(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            long watermark = WorldEventReplayReportFixture.watermark();
            java.util.Set<BlockPos> changed = ExplosionWorldEventConformanceFixture.triggerDestructiveExplosion(helper);
            BlockPos unchanged = ExplosionWorldEventConformanceFixture.triggerNoBlockEffectExplosion(helper);
            BlockPos partialCoverageCenter = ExplosionWorldEventConformanceFixture.triggerPartialCoverage(helper);
            helper.runAfterDelay(100, () -> {
                ExplosionWorldEventConformanceFixture.assertConfirmedChange(helper, changed);
                ExplosionWorldEventConformanceFixture.assertNoConfirmedChange(helper, unchanged);
                ExplosionWorldEventConformanceFixture.assertPartialCoverage(helper, partialCoverageCenter);
                long cutoffWatermark = WorldEventReplayReportFixture.watermark();
                if (!WorldEventReplayReportFixture.writeIfRequested(
                        helper, "fabric", "explosion", watermark, cutoffWatermark)) {
                    helper.succeed();
                }
            });
        });
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "ab_itemgraph_world_piston", timeoutTicks = 300)
    public void pistonEventsPersistConfirmedAndBlockedResults(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            long watermark = WorldEventReplayReportFixture.watermark();
            PistonWorldEventConformanceFixture.Scenario moved =
                    PistonWorldEventConformanceFixture.triggerSuccessfulPiston(helper);
            PistonWorldEventConformanceFixture.Scenario blocked =
                    PistonWorldEventConformanceFixture.triggerBlockedPiston(helper);
            PistonWorldEventConformanceFixture.Scenario exceptional =
                    PistonWorldEventConformanceFixture.triggerExceptionalPiston(helper);
            helper.runAfterDelay(100, () -> {
                PistonWorldEventConformanceFixture.assertSuccessfulMove(helper, moved);
                PistonWorldEventConformanceFixture.assertBlockedAttempt(helper, blocked);
                PistonWorldEventConformanceFixture.assertExceptionalAttempt(helper, exceptional);
                long cutoffWatermark = WorldEventReplayReportFixture.watermark();
                if (!WorldEventReplayReportFixture.writeIfRequested(
                        helper, "fabric", "piston", watermark, cutoffWatermark)) {
                    helper.succeed();
                }
            });
        });
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "ac_itemgraph_world_environment", timeoutTicks = 600)
    public void environmentalEventsPersistBoundedEvidence(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            long watermark = WorldEventReplayReportFixture.watermark();
            var scenario = com.itemgraph.gametest.EnvironmentalWorldEventConformanceFixture.trigger(helper);
            helper.runAfterDelay(400, () -> {
                try {
                    com.itemgraph.gametest.EnvironmentalWorldEventConformanceFixture.assertPersisted(helper, scenario);
                    long cutoffWatermark = WorldEventReplayReportFixture.watermark();
                    if (!WorldEventReplayReportFixture.writeIfRequested(
                            helper, "fabric", "environment", watermark, cutoffWatermark)) {
                        helper.succeed();
                    }
                } finally {
                    com.itemgraph.gametest.EnvironmentalWorldEventConformanceFixture.cleanup(helper, scenario);
                }
            });
        });
    }
}
