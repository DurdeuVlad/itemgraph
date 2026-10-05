package com.itemgraph.command;

import com.itemgraph.query.QueryWindow;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.query.AuditLookupFilters;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.Connection;
import java.sql.PreparedStatement;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FabricItemGraphPageDispatchTest {

    @Test
    void fabricChatBoundaryPreservesVisibleQueryLineAndUsesStructuredSafeHoverFields() {
        String visible = "[OBSERVED] CHEST minecraft:overworld 3,64,4 -> PLAYER Admin : 1x at 2026-10-05T12:00:00Z";
        QueryDispatcher.ChatHoverDetail hover = new QueryDispatcher.ChatHoverDetail(
                "OBSERVED", "minecraft:diamond", "sha256:canonical", "DROP_ITEM",
                "2026-10-05T12:00:00Z", "CHEST minecraft:overworld 3,64,4", "PLAYER Admin");

        Component result = QueryDispatcher.chatLine(visible, hover);
        String hoverText = result.getStyle().getHoverEvent()
                .getValue(HoverEvent.Action.SHOW_TEXT).getString();

        assertEquals(visible, result.getString());
        assertTrue(hoverText.contains("Canonical metadata fingerprint: sha256:canonical"));
        assertTrue(hoverText.contains("UTC time: 2026-10-05T12:00:00Z"));
        assertFalse(hoverText.toLowerCase().contains("nbt"));
    }
    @Test
    void lookupContinuationRetainsOriginAndAuditGatesOnFabric() {
        assertEquals(List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT),
                ItemGraphCommands.lookupPermissionsForType("COMMAND_EXECUTED"));
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                UUID.randomUUID(), "CHAT_MESSAGE", null, QueryWindow.unbounded(), 20, null,
                null, null, null, null, null, null, "chat lookup",
                ItemGraphPermissions.LOOKUP, true, System.currentTimeMillis());

        assertEquals(List.of(ItemGraphPermissions.PAGE, ItemGraphPermissions.LOOKUP,
                        ItemGraphPermissions.AUDIT), ItemGraphCommands.pagePermissionsFor(session));
    }

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
        QueryDispatcher.shutdown();
        DatabaseManager.getInstance().close();
        ItemGraphPermissions.setChecker(null);
    }

    @Test
    void lookupOnlyGrantCannotReadSensitiveAliasesOrContinueProtectedSessionOnFabric() throws Exception {
        ItemGraphPermissions.setChecker((source, node) -> List.of(ItemGraphPermissions.COMMAND,
                ItemGraphPermissions.LOOKUP, ItemGraphPermissions.PAGE).contains(node));
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        for (String command : List.of(
                "ig lookup CHAT_MESSAGE",
                "ig lookup chat_message",
                "itemgraph lookup player Alex COMMAND_ATTEMPT",
                "ig lookup near minecraft:overworld 0 64 0 5 COMMAND_EXECUTED",
                "ig lookup filters action.chat_message radius.5")) {
            CommandSourceStack source = sourceFor(UUID.randomUUID());
            assertEquals(0, dispatcher.execute(command, source), command);
            assertFailure(source, command.contains("filters") || command.contains("action.")
                    ? "[ItemGraph] This lookup can include protected audit events and requires itemgraph.audit."
                    : "[ItemGraph] You do not have permission to use this ItemGraph command.");
        }

        UUID ownerId = UUID.randomUUID();
        CommandSourceStack pageSource = sourceFor(ownerId);
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                UUID.randomUUID(), "COMMAND_ATTEMPT", null, QueryWindow.unbounded(), 20, null,
                null, null, null, null, null, null, "command lookup",
                ItemGraphPermissions.LOOKUP, true, System.currentTimeMillis());
        ItemGraphCommands.rememberPageSession(pageSource, session);

        assertEquals(0, dispatcher.execute("ig page 2 " + session.sessionId(), pageSource));
        assertFailure(pageSource, "[ItemGraph] You do not have permission to use this ItemGraph command.");
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

    @Test
    void generatedPageControlsCarryTheOwningLookupSessionAndRespectBoundaries() {
        UUID sessionId = UUID.randomUUID();
        ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                sessionId, "BREAK_BLOCK", null, QueryWindow.unbounded(), 10,
                null, null, null, null, null, null, null, "type=BREAK_BLOCK", System.currentTimeMillis());

        var firstPage = ItemGraphCommands.auditPageActions(session, 1, true, true);
        assertEquals(1, firstPage.size());
        assertEquals("Next", firstPage.get(0).label());
        assertEquals("/ig page 2 " + sessionId, firstPage.get(0).command());

        var middlePage = ItemGraphCommands.auditPageActions(session, 2, true, true);
        assertEquals(java.util.List.of("Previous", "Next"),
                middlePage.stream().map(QueryDispatcher.QueryAction::label).toList());
        assertEquals("/ig page 1 " + sessionId, middlePage.get(0).command());
        assertEquals("/ig page 3 " + sessionId, middlePage.get(1).command());

        var lastPage = ItemGraphCommands.auditPageActions(session, 3, false, true);
        assertEquals(1, lastPage.size());
        assertEquals("/ig page 2 " + sessionId, lastPage.get(0).command());
        assertTrue(ItemGraphCommands.auditPageActions(session, 1, true, false).isEmpty(),
                "non-player command sources receive no clickable page controls");
    }

    @Test
    void exactMultipleOfPageSizeDoesNotOfferAnEmptyNextPage(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("exact-page-count.db"));
        try (Connection connection = DatabaseManager.getInstance().getConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO ig_audit_events
                         (event_type, timestamp_ms, level_id, x, y, z, source_type)
                     VALUES ('BREAK_BLOCK', ?, 'minecraft:overworld', 4, 64, -3, 'ITEMGRAPH_INTERNAL')
                     """)) {
            for (int i = 0; i < 20; i++) {
                insert.setLong(1, i + 1L);
                insert.executeUpdate();
            }

            AuditLookupFilters filters = AuditLookupFilters.parse(
                    "action.break_block radius.10", System.currentTimeMillis());
            ItemGraphCommands.AuditPageSession session = new ItemGraphCommands.AuditPageSession(
                    UUID.randomUUID(), null, null, filters.window(), 10,
                    "minecraft:overworld", 4.0, 64.0, -3.0, filters.radiusBlocks(),
                    null, filters, "action=break_block", System.currentTimeMillis());

            assertTrue(ItemGraphCommands.canCheckNextAuditPage(1, 10, 0, 10));
            assertTrue(ItemGraphCommands.hasNextAuditPage(connection, session, 1, 10, 0),
                    "page 1 has ten more matching rows at offset 10");
            assertTrue(ItemGraphCommands.auditPageActions(session, 1,
                    ItemGraphCommands.hasNextAuditPage(connection, session, 1, 10, 0), true)
                    .stream().anyMatch(action -> action.label().equals("Next")));

            assertTrue(ItemGraphCommands.canCheckNextAuditPage(2, 10, 10, 10));
            assertFalse(ItemGraphCommands.hasNextAuditPage(connection, session, 2, 10, 10),
                    "page 2 is full, but there is no row at offset 20");
            assertEquals(List.of("Previous"), ItemGraphCommands.auditPageActions(
                    session, 2, ItemGraphCommands.hasNextAuditPage(connection, session, 2, 10, 10), true)
                    .stream().map(QueryDispatcher.QueryAction::label).toList());
        }
    }

    @Test
    void filteredLookupWithNoEvidenceReturnsItsDocumentedEmptyMessageOnFabric(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("filtered-empty.db"));
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(playerId);
        ServerLevel level = mock(ServerLevel.class);
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.getX()).thenReturn(4.0);
        when(player.getY()).thenReturn(64.0);
        when(player.getZ()).thenReturn(-3.0);
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> callback = new AtomicReference<>();
        when(source.getEntity()).thenReturn(player);
        when(source.getServer()).thenReturn(server);
        when(source.hasPermission(2)).thenReturn(true);
        when(player.hasDisconnected()).thenReturn(false);
        when(server.getRunningThread()).thenReturn(Thread.currentThread());
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, dispatcher.execute("ig lookup action.break_block radius.10", source));
        assertTrue(callbackQueued.await(5, TimeUnit.SECONDS),
                "the real filtered lookup query should schedule its result on the server thread");
        callback.get().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Supplier<Component>> success = (ArgumentCaptor)
                ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(source, org.mockito.Mockito.atLeastOnce()).sendSuccess(success.capture(), anyBoolean());
        verify(source, never()).sendFailure(any());
        List<String> messages = success.getAllValues().stream()
                .map(supplier -> supplier.get().getString())
                .toList();
        assertTrue(messages.stream().anyMatch(line -> line.contains("UNIFIED EVIDENCE")));
        assertTrue(messages.stream().anyMatch(line -> line.equals(
                "[ItemGraph] No audit, item-flow, transformation, or imported evidence matched the requested filters.")));
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
