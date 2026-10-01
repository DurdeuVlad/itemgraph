package com.itemgraph.command;

import com.itemgraph.query.QueryWindow;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FabricItemGraphPageDispatchTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Registries may already be bootstrapped by another test class.
        }
    }

    @AfterEach
    void clearPageSessions() {
        ItemGraphCommands.clearPageSessions();
    }

    @Test
    void executingStandalonePageWithoutAnActiveSessionReturnsTheSameFailureOnBothRoots() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack shortRoot = sourceFor(UUID.randomUUID());

        assertEquals(0, dispatcher.execute("ig page 2", shortRoot));
        assertFailure(shortRoot, "[ItemGraph] No active lookup page session. Run /ig lookup first.");

        CommandSourceStack fullRoot = sourceFor(UUID.randomUUID());
        assertEquals(0, dispatcher.execute("itemgraph page 2", fullRoot));
        assertFailure(fullRoot, "[ItemGraph] No active lookup page session. Run /ig lookup first.");
    }

    @Test
    void executingPageWithAnotherPlayersSessionTokenDoesNotExposeTheirResults() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CommandSourceStack owner = sourceFor(ownerId);
        CommandSourceStack other = sourceFor(otherId);
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                sessionId, "BREAK_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=BREAK_BLOCK", System.currentTimeMillis());
        ItemGraphCommands.rememberPageSession(owner, session);

        assertEquals(0, dispatcher.execute("itemgraph page 2 " + sessionId, other));
        assertFailure(other, "[ItemGraph] No active lookup page session. Run /ig lookup first.");
        assertSame(session, ItemGraphCommands.pageSession(owner, sessionId),
                "a rejected foreign token must leave the owner's session usable");
    }

    @Test
    void malformedAndExpiredSessionTokensReturnDeterministicFailures() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack malformed = sourceFor(UUID.randomUUID());

        assertEquals(0, dispatcher.execute("ig page 2 not-a-uuid", malformed));
        assertFailure(malformed, "[ItemGraph] Invalid lookup page session.");

        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        CommandSourceStack expired = sourceFor(playerId);
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                sessionId, "BREAK_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=BREAK_BLOCK",
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31));
        ItemGraphCommands.rememberPageSession(expired, session);
        assertTrue(ItemGraphCommands.hasPageSessionOwner(playerId));

        assertEquals(0, dispatcher.execute("itemgraph page 2 " + sessionId, expired));
        assertFailure(expired, "[ItemGraph] No active lookup page session. Run /ig lookup first.");
        assertFalse(ItemGraphCommands.hasPageSessionOwner(playerId),
                "rejecting an expired explicit token must remove its empty player-state entry");
        assertNull(ItemGraphCommands.pageSession(expired, sessionId),
                "an expired session must be removed when page execution rejects it");
    }

    @Test
    void executingProtectedPageAsUnauthorizedSourceFailsPermissionCheck() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack denied = sourceFor(UUID.randomUUID());
        when(denied.hasPermission(2)).thenReturn(false);

        for (String command : new String[] {"ig page 2", "itemgraph page 2"}) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, denied));
        }
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        return dispatcher;
    }

    private static CommandSourceStack sourceFor(UUID playerId) {
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getEntity()).thenReturn(player);
        return source;
    }

    private static void assertFailure(CommandSourceStack source, String expected) {
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failure.capture());
        assertEquals(expected, failure.getValue().getString());
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }
}
