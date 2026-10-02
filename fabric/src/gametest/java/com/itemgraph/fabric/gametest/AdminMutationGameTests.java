package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.AdminMutationConformanceFixture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Fabric runtime fixture for admin command and creative slot evidence. */
public final class AdminMutationGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty",
            batch = "zz_itemgraph_admin_mutations", timeoutTicks = 300_000)
    @SuppressWarnings("removal")
    public void commandsAndCreativeSlotsPersistCanonicalDeltas(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AdminMutationConformanceFixture.run(helper, player);
    }
}
