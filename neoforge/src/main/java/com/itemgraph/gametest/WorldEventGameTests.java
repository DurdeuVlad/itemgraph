package com.itemgraph.gametest;

import com.itemgraph.gametest.ExplosionWorldEventConformanceFixture;
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

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "aa_itemgraph_world_events", timeoutTicks = 500)
    public static void explosionPersistsOnlyConfirmedBlockChanges(GameTestHelper helper) {
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

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "aa_itemgraph_world_events", timeoutTicks = 300)
    public static void pistonEventsPersistConfirmedAndBlockedResults(GameTestHelper helper) {
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

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "aa_itemgraph_world_events", timeoutTicks = 600)
    public static void environmentalEventsPersistBoundedEvidence(GameTestHelper helper) {
        InternalObservationService.getInstance().start();
        helper.runAfterDelay(100, () -> {
            var scenario = EnvironmentalWorldEventConformanceFixture.trigger(helper);
            helper.runAfterDelay(400, () -> {
                try {
                    EnvironmentalWorldEventConformanceFixture.assertPersisted(helper, scenario);
                } finally {
                    EnvironmentalWorldEventConformanceFixture.cleanup(helper, scenario);
                }
                helper.succeed();
            });
        });
    }
}
