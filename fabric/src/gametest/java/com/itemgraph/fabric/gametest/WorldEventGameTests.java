package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.ExplosionWorldEventConformanceFixture;
import com.itemgraph.gametest.PistonWorldEventConformanceFixture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import com.itemgraph.ingest.InternalObservationService;

public final class WorldEventGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "aa_itemgraph_world_events", timeoutTicks = 500)
    public void explosionPersistsOnlyConfirmedBlockChanges(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            java.util.Set<BlockPos> changed = ExplosionWorldEventConformanceFixture.triggerDestructiveExplosion(helper);
            BlockPos unchanged = ExplosionWorldEventConformanceFixture.triggerNoBlockEffectExplosion(helper);
            BlockPos partialCoverageCenter = ExplosionWorldEventConformanceFixture.triggerPartialCoverage(helper);
            helper.runAfterDelay(100, () -> {
                ExplosionWorldEventConformanceFixture.assertConfirmedChange(helper, changed);
                ExplosionWorldEventConformanceFixture.assertNoConfirmedChange(helper, unchanged);
                ExplosionWorldEventConformanceFixture.assertPartialCoverage(helper, partialCoverageCenter);
                helper.succeed();
            });
        });
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "aa_itemgraph_world_events", timeoutTicks = 300)
    public void pistonEventsPersistConfirmedAndBlockedResults(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
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
                helper.succeed();
            });
        });
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "aa_itemgraph_world_events", timeoutTicks = 600)
    public void environmentalEventsPersistBoundedEvidence(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            var scenario = com.itemgraph.gametest.EnvironmentalWorldEventConformanceFixture.trigger(helper);
            helper.runAfterDelay(400, () -> {
                try {
                    com.itemgraph.gametest.EnvironmentalWorldEventConformanceFixture.assertPersisted(helper, scenario);
                } finally {
                    com.itemgraph.gametest.EnvironmentalWorldEventConformanceFixture.cleanup(helper, scenario);
                }
                helper.succeed();
            });
        });
    }
}
