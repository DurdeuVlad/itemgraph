package com.itemgraph.gametest;

import com.itemgraph.gametest.VanillaDispenserConformanceFixture;
import com.itemgraph.gametest.VanillaHopperConformanceFixture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** NeoForge live vanilla dispenser/dropper evidence replay. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class VanillaAutomationGameTests {
    private VanillaAutomationGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty",
            batch = "zz_itemgraph_automation", timeoutTicks = 100)
    public static void dispenserPersistsOnlyAcceptedItemEntityEvidence(GameTestHelper helper) {
        VanillaDispenserConformanceFixture.run(helper, Blocks.DISPENSER, "DISPENSER_DROP");
    }

    @GameTest(templateNamespace = "itemgraph", template = "empty",
            batch = "zz_itemgraph_automation", timeoutTicks = 100)
    public static void dropperPersistsOnlyAcceptedItemEntityEvidence(GameTestHelper helper) {
        VanillaDispenserConformanceFixture.run(helper, Blocks.DROPPER, "DROPPER_DROP");
    }

    @GameTest(templateNamespace = "itemgraph", template = "empty",
            batch = "zz_itemgraph_automation", timeoutTicks = 150)
    public static void vanillaHopperPersistsConservingEndpointDeltas(GameTestHelper helper) {
        VanillaHopperConformanceFixture.run(helper);
    }
}
