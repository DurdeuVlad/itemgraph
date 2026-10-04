package com.itemgraph.fabric.gametest;

import com.itemgraph.command.InspectionService;
import com.itemgraph.gametest.CommandPacketConformanceFixture;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;

/** Fabric server packet coverage for the published command roots. */
public final class CommandPacketGameTests implements FabricGameTest {
    @GameTest(template = "fabric-gametest-api-v1:empty",
            batch = "zz_itemgraph_command_packets", timeoutTicks = 4000)
    @SuppressWarnings("removal")
    public void inspectCommandsExecuteFromClientPacketsAndPersistAttempts(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        CommandPacketConformanceFixture.run(helper, player, complete -> {
            var ops = player.getServer().getPlayerList().getOps();
            ops.add(new ServerOpListEntry(player.getGameProfile(), 4, false));
            Runnable cleanup = () -> {
                ops.remove(player.getGameProfile());
                InspectionService.getInstance().clear(player.getUUID());
            };
            runWithCleanup(cleanup, () -> {
                helper.assertTrue(player.createCommandSourceStack().hasPermission(2),
                        "mock client must have permission level 2 for the published inspector commands");
                helper.runAfterDelay(1, () -> runWithCleanup(cleanup, () -> {
                    player.connection.handleChatCommand(new ServerboundChatCommandPacket("itemgraph inspect on"));
                    helper.runAfterDelay(1, () -> runWithCleanup(cleanup, () -> {
                        helper.assertTrue(InspectionService.getInstance().isEnabled(player.getUUID()),
                                "full command root did not enable inspection through the server packet handler");
                        player.connection.handleChatCommand(new ServerboundChatCommandPacket("ig inspect status"));
                        helper.runAfterDelay(1, () -> runWithCleanup(cleanup, () -> {
                            helper.assertTrue(InspectionService.getInstance().isEnabled(player.getUUID()),
                                    "status command packet must report state without toggling inspection off");
                            player.connection.handleChatCommand(new ServerboundChatCommandPacket("ig inspect off"));
                            helper.runAfterDelay(1, () -> runWithCleanup(cleanup, () -> {
                                helper.assertFalse(InspectionService.getInstance().isEnabled(player.getUUID()),
                                        "short command root did not disable inspection through the server packet handler");
                                cleanup.run();
                                complete.run();
                            }));
                        }));
                    }));
                }));
            });
        });
    }

    private static void runWithCleanup(Runnable cleanup, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            cleanup.run();
            throw failure;
        }
    }
}
