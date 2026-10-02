package com.itemgraph.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** NeoForge runtime fixture for admin command and creative slot evidence. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class AdminMutationGameTests {
    private AdminMutationGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty",
            batch = "zz_itemgraph_admin_mutations", timeoutTicks = 300_000)
    @SuppressWarnings("removal")
    public static void commandsAndCreativeSlotsPersistCanonicalDeltas(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        AdminMutationConformanceFixture.run(helper, player);
    }
}
