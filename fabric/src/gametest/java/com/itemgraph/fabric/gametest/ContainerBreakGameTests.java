package com.itemgraph.fabric.gametest;

import com.itemgraph.gametest.ContainerBreakConformanceFixture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Fabric live-server acceptance for player-broken inventory blocks. */
public final class ContainerBreakGameTests implements FabricGameTest {
    private static final ThreadLocal<BlockPos> CANCELED_BREAK_POSITION = new ThreadLocal<>();

    static {
        PlayerBlockBreakEvents.BEFORE.register((level, player, position, state, blockEntity) -> {
            BlockPos selected = CANCELED_BREAK_POSITION.get();
            return selected == null || !selected.equals(position);
        });
    }

    @GameTest(template = "fabric-gametest-api-v1:empty", batch = "itemgraph_container_break",
            timeoutTicks = 1_000)
    public void successfulPlayerBreakPersistsExactContainerContents(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ContainerBreakConformanceFixture.run(helper, player, "fabric",
                ContainerBreakGameTests::attemptCanceledBreak);
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
