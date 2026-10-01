package com.itemgraph.gametest;

import com.itemgraph.command.InspectionService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** NeoForge server packet coverage for the published command roots. */
@GameTestHolder("itemgraph")
@PrefixGameTestTemplate(false)
public final class CommandPacketGameTests {
    private CommandPacketGameTests() { }

    @GameTest(templateNamespace = "itemgraph", template = "empty",
            batch = "zz_itemgraph_command_packets", timeoutTicks = 400)
    @SuppressWarnings("removal")
    public static void inspectCommandsExecuteFromClientPacketsAndPersistAttempts(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        long watermark = CommandPacketConformanceFixture.auditWatermark();
        List<List<String>> quantityRowsBefore = CommandPacketConformanceFixture.snapshotQuantityObservations();

        var ops = player.getServer().getPlayerList().getOps();
        ops.add(new ServerOpListEntry(player.getGameProfile(), 4, false));
        try {
            helper.assertTrue(player.createCommandSourceStack().hasPermission(2),
                    "mock client must have permission level 2 for the published inspector commands");
            player.connection.handleChatCommand(new ServerboundChatCommandPacket("itemgraph inspect on"));
            helper.assertTrue(InspectionService.getInstance().isEnabled(player.getUUID()),
                    "full command root did not enable inspection through the server packet handler");

            player.connection.handleChatCommand(new ServerboundChatCommandPacket("ig inspect status"));
            helper.assertTrue(InspectionService.getInstance().isEnabled(player.getUUID()),
                    "status command packet must report state without toggling inspection off");

            player.connection.handleChatCommand(new ServerboundChatCommandPacket("ig inspect off"));
            helper.assertFalse(InspectionService.getInstance().isEnabled(player.getUUID()),
                    "short command root did not disable inspection through the server packet handler");
        } finally {
            ops.remove(player.getGameProfile());
            InspectionService.getInstance().clear(player.getUUID());
        }

        helper.succeedWhen(() -> CommandPacketConformanceFixture.assertPersisted(
                helper, watermark, player.getUUID().toString(), player.getGameProfile().getName(),
                quantityRowsBefore));
    }
}
