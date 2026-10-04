package com.itemgraph.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** NeoForge live-server acceptance for player-broken inventory blocks. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class ContainerBreakGameTests {
    private static final ThreadLocal<BlockPos> CANCELED_BREAK_POSITION = new ThreadLocal<>();

    static {
        NeoForge.EVENT_BUS.addListener(ContainerBreakGameTests::cancelSelectedBreak);
    }

    private ContainerBreakGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty", batch = "itemgraph_container_break",
            timeoutTicks = 1_000)
    public static void successfulPlayerBreakPersistsExactContainerContents(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ContainerBreakConformanceFixture.run(helper, player, "neoforge",
                ContainerBreakGameTests::attemptCanceledBreak);
    }

    private static void cancelSelectedBreak(BlockEvent.BreakEvent event) {
        BlockPos selected = CANCELED_BREAK_POSITION.get();
        if (selected != null && selected.equals(event.getPos())) event.setCanceled(true);
    }

    private static boolean attemptCanceledBreak(ServerPlayer player, BlockPos position) {
        CANCELED_BREAK_POSITION.set(position.immutable());
        try {
            return player.gameMode.destroyBlock(position);
        } finally {
            CANCELED_BREAK_POSITION.remove();
        }
    }
}
