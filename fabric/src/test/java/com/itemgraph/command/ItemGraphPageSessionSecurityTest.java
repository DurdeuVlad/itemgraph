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
        UUID ownerSessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        CommandSourceStack owner = sourceFor(ownerId);
        CommandSourceStack other = sourceFor(otherId);
        ItemGraphCommands.AuditPageSession ownerSession = new ItemGraphCommands.AuditPageSession(
                ownerSessionId, "BREAK_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=BREAK_BLOCK", System.currentTimeMillis());
        ItemGraphCommands.AuditPageSession otherSession = new ItemGraphCommands.AuditPageSession(
                otherSessionId, "PLACE_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=PLACE_BLOCK", System.currentTimeMillis());
        ItemGraphCommands.rememberPageSession(owner, ownerSession);
        ItemGraphCommands.rememberPageSession(other, otherSession);

        assertSame(ownerSession, ItemGraphCommands.pageSession(owner, ownerSessionId));
        assertSame(otherSession, ItemGraphCommands.pageSession(other, otherSessionId));
        assertNull(ItemGraphCommands.pageSession(other, ownerSessionId),
                "a player cannot resolve another player's lookup page token");
        ItemGraphCommands.clearPageSession(ownerId);
        assertNull(ItemGraphCommands.pageSession(owner, ownerSessionId),
                "clearing the player's page state invalidates its session token");
        assertSame(otherSession, ItemGraphCommands.pageSession(other, otherSessionId),
                "clearing one player's page state preserves another player's session");
    }

    private static CommandSourceStack sourceFor(UUID playerId) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getEntity()).thenReturn(player);
        return source;
    }
}
