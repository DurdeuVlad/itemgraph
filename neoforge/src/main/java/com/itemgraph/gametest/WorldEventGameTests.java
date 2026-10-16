package com.itemgraph.gametest;

import com.itemgraph.gametest.ExplosionWorldEventConformanceFixture;
import com.itemgraph.gametest.WorldEventReplayReportFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.itemgraph.ingest.InternalObservationService;

@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class WorldEventGameTests {
    private WorldEventGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "aa_itemgraph_world_explosion", timeoutTicks = 500)
    public static void explosionPersistsOnlyConfirmedBlockChanges(GameTestHelper helper) {
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
                        helper, "neoforge", "explosion", watermark, cutoffWatermark)) {
                    helper.succeed();
                }
            });
        });
    }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "ab_itemgraph_world_piston", timeoutTicks = 300)
    public static void pistonEventsPersistConfirmedAndBlockedResults(GameTestHelper helper) {
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
                        helper, "neoforge", "piston", watermark, cutoffWatermark)) {
                    helper.succeed();
                }
            });
        });
    }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "ac_itemgraph_world_environment", timeoutTicks = 600)
    public static void environmentalEventsPersistBoundedEvidence(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            long watermark = WorldEventReplayReportFixture.watermark();
            var scenario = EnvironmentalWorldEventConformanceFixture.trigger(helper);
            helper.runAfterDelay(400, () -> {
                try {
                    EnvironmentalWorldEventConformanceFixture.assertPersisted(helper, scenario);
                    long cutoffWatermark = WorldEventReplayReportFixture.watermark();
                    if (!WorldEventReplayReportFixture.writeIfRequested(
                            helper, "neoforge", "environment", watermark, cutoffWatermark)) {
                        helper.succeed();
                    }
                } finally {
                    EnvironmentalWorldEventConformanceFixture.cleanup(helper, scenario);
                }
            });
        });
    }
}
