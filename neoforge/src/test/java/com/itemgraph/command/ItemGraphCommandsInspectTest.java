package com.itemgraph.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        List<String> messages = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph inspect", source));
        assertTrue(service.isEnabled(playerUuid));

        assertEquals(1, dispatcher.execute("ig inspect status", source));
        assertTrue(service.isEnabled(playerUuid), "status must not toggle the mode");

        assertEquals(1, dispatcher.execute("ig inspect on", source));
        assertTrue(service.isEnabled(playerUuid), "on must remain enabled");

        assertEquals(1, dispatcher.execute("itemgraph inspect off", source));
        assertFalse(service.isEnabled(playerUuid));

        assertEquals(1, dispatcher.execute("ig inspect off", source));
        assertFalse(service.isEnabled(playerUuid), "off must remain disabled");

        assertEquals(1, dispatcher.execute("ig inspect", source));
        assertTrue(service.isEnabled(playerUuid));

        assertEquals(1, dispatcher.execute("itemgraph inspect", source));
        assertFalse(service.isEnabled(playerUuid));

        assertEquals(List.of(
                "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history; use /ig inspect off to disable.",
                "[ItemGraph] Inspection is enabled.",
                "[ItemGraph] Inspection is already enabled.",
                "[ItemGraph] Inspection disabled.",
                "[ItemGraph] Inspection is already disabled.",
                "[ItemGraph] Inspection enabled. Left-click blocks or right-click blocks and containers to view read-only history; use /ig inspect off to disable.",
                "[ItemGraph] Inspection disabled."), messages);
    }

    @Test
    void inspectCommandIsUnavailableWithoutPermission() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        UUID playerUuid = UUID.randomUUID();
        CommandSourceStack source = commandSource(playerUuid);
        List<String> messages = captureSuccesses(source);
        when(source.hasPermission(2)).thenReturn(false);

        assertThrows(com.mojang.brigadier.exceptions.CommandSyntaxException.class,
                () -> dispatcher.execute("itemgraph inspect", source));
        assertFalse(service.isEnabled(playerUuid));
        assertTrue(messages.isEmpty(), "a denied inspect command must not emit a success receipt");
        verify(source, never()).sendFailure(any());
    }

    private List<String> captureSuccesses(CommandSourceStack source) {
        List<String> messages = new ArrayList<>();
        doAnswer(invocation -> {
            Supplier<Component> message = invocation.getArgument(0);
            messages.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        return messages;
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
