package com.itemgraph.command;

import com.itemgraph.query.QueryWindow;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemGraphPageSessionSecurityTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Registries may already have been bootstrapped by another test class.
        }
    }

    @AfterEach
    void clearSessions() {
        ItemGraphCommands.clearPageSessions();
    }

    @Test
    void lookupPageSessionCannotBeResolvedByAnotherPlayerAndCanBeCleared() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CommandSourceStack owner = sourceFor(ownerId);
        CommandSourceStack other = sourceFor(otherId);
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                sessionId, "BREAK_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=BREAK_BLOCK", System.currentTimeMillis());
        ItemGraphCommands.rememberPageSession(owner, session);

        assertSame(session, ItemGraphCommands.pageSession(owner, sessionId));
        assertNull(ItemGraphCommands.pageSession(other, sessionId),
                "a player cannot resolve another player's lookup page token");
        ItemGraphCommands.clearPageSession(ownerId);
        assertNull(ItemGraphCommands.pageSession(owner, sessionId),
                "clearing the player's page state invalidates its session token");
    }

    private static CommandSourceStack sourceFor(UUID playerId) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getEntity()).thenReturn(player);
        return source;
    }
}
