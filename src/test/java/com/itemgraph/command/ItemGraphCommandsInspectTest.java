package com.itemgraph.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemGraphCommandsInspectTest {

    private final InspectionService service = InspectionService.getInstance();

    @BeforeAll
    static void initMinecraftRegistries() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        service.clear();
    }

    @Test
    void inspectCommandTogglesAndExplicitFormsAreDeterministic() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        UUID playerUuid = UUID.randomUUID();
        CommandSourceStack source = commandSource(playerUuid);

        assertEquals(1, dispatcher.execute("itemgraph inspect", source));
        assertTrue(service.isEnabled(playerUuid));

        assertEquals(1, dispatcher.execute("itemgraph inspect status", source));
        assertTrue(service.isEnabled(playerUuid), "status must not toggle the mode");

        assertEquals(1, dispatcher.execute("itemgraph inspect off", source));
        assertFalse(service.isEnabled(playerUuid));

        assertEquals(1, dispatcher.execute("itemgraph inspect off", source));
        assertFalse(service.isEnabled(playerUuid), "off must remain disabled");

        assertEquals(1, dispatcher.execute("ig inspect on", source));
        assertTrue(service.isEnabled(playerUuid));

        assertEquals(1, dispatcher.execute("ig inspect on", source));
        assertTrue(service.isEnabled(playerUuid), "on must remain enabled");
    }

    @Test
    void inspectCommandIsUnavailableWithoutPermission() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        UUID playerUuid = UUID.randomUUID();
        CommandSourceStack source = commandSource(playerUuid);
        when(source.hasPermission(2)).thenReturn(false);

        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("itemgraph inspect", source));
        assertFalse(service.isEnabled(playerUuid));
    }

    private CommandSourceStack commandSource(UUID playerUuid) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(source.getEntity()).thenReturn(player);
        when(source.hasPermission(2)).thenReturn(true);
        when(player.getUUID()).thenReturn(playerUuid);
        return source;
    }
}
