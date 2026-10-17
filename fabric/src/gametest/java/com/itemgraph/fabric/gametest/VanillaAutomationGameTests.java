package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.VanillaDispenserConformanceFixture;
import com.itemgraph.gametest.VanillaHopperConformanceFixture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Fabric live vanilla dispenser/dropper evidence replay. */
public final class VanillaAutomationGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "zz_itemgraph_automation", timeoutTicks = 100)
    public void dispenserPersistsOnlyAcceptedItemEntityEvidence(GameTestHelper helper) {
        VanillaDispenserConformanceFixture.run(helper, Blocks.DISPENSER, "DISPENSER_DROP");
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "zz_itemgraph_automation", timeoutTicks = 100)
    public void dropperPersistsOnlyAcceptedItemEntityEvidence(GameTestHelper helper) {
        VanillaDispenserConformanceFixture.run(helper, Blocks.DROPPER, "DROPPER_DROP");
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "zz_itemgraph_automation", timeoutTicks = 150)
    public void vanillaHopperPersistsConservingEndpointDeltas(GameTestHelper helper) {
        VanillaHopperConformanceFixture.run(helper);
    }
}
