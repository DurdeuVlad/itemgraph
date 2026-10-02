package com.itemgraph.command;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentExportAuthorizationTest {
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

    @Test
    void onlyOwnerOrLevelFourOperatorCanCancelAnExport() {
        UUID ownerId = UUID.randomUUID();
        ServerPlayer owner = mock(ServerPlayer.class);
        when(owner.getUUID()).thenReturn(ownerId);
        CommandSourceStack ownerSource = mock(CommandSourceStack.class);
        when(ownerSource.getEntity()).thenReturn(owner);
        when(ownerSource.hasPermission(4)).thenReturn(false);
        assertTrue(IncidentExportJobs.isAuthorizedToCancel(ownerSource, ownerId));

        ServerPlayer otherPlayer = mock(ServerPlayer.class);
        when(otherPlayer.getUUID()).thenReturn(UUID.randomUUID());
        CommandSourceStack otherSource = mock(CommandSourceStack.class);
        when(otherSource.getEntity()).thenReturn(otherPlayer);
        when(otherSource.hasPermission(4)).thenReturn(false);
        assertFalse(IncidentExportJobs.isAuthorizedToCancel(otherSource, ownerId));

        CommandSourceStack consoleSource = mock(CommandSourceStack.class);
        when(consoleSource.getEntity()).thenReturn(null);
        when(consoleSource.hasPermission(4)).thenReturn(false);
        assertFalse(IncidentExportJobs.isAuthorizedToCancel(consoleSource, ownerId));

        CommandSourceStack operatorSource = mock(CommandSourceStack.class);
        when(operatorSource.getEntity()).thenReturn(null);
        when(operatorSource.hasPermission(4)).thenReturn(true);
        assertTrue(IncidentExportJobs.isAuthorizedToCancel(operatorSource, ownerId));
    }
}
