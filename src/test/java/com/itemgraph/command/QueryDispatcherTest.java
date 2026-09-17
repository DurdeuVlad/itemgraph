package com.itemgraph.command;

import com.itemgraph.command.QueryDispatcher.QueryOutput;
import com.itemgraph.db.DatabaseManager;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryDispatcherTest {

    @BeforeAll
    static void initMinecraft() {
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
        QueryDispatcher.shutdown();
        DatabaseManager.getInstance().close();
    }

    @Test
    void testDispatchWhenDatabaseNotInitializedReturnsFailure(@TempDir Path tempDir) {
        DatabaseManager db = DatabaseManager.getInstance();
        // Reset lastError so this asserts the exact no-error production response, not a
        // substring that could keep passing after the command text changes.
        db.initialize(tempDir.resolve("disconnected.db"));
        db.close();
        assertFalse(db.isInitialized(), "Database must be closed for this test");

        CommandSourceStack source = mock(CommandSourceStack.class);

        int result = QueryDispatcher.dispatch(source, "trace", conn -> QueryOutput.found(List.of("test line")));

        assertEquals(0, result, "dispatch must return 0 when database is not connected");

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(captor.capture());
        String failureText = captor.getValue().getString();
        assertEquals("[ItemGraph] Query failed: the ItemGraph database is not connected. See /ig status.",
                failureText);

        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void testDispatchReportsFailureWhenDatabaseClosesAfterPrecheck(@TempDir Path tempDir) throws Exception {
        DatabaseManager db = DatabaseManager.getInstance();
        db.initialize(tempDir.resolve("shutdown-race.db"));

        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstQuery = new CountDownLatch(1);

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(Thread.currentThread());
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, "blocking-query", conn -> {
            firstQueryStarted.countDown();
            try {
                assertTrue(releaseFirstQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return QueryOutput.found(List.of("first query complete"));
        }));
        assertTrue(firstQueryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

        // The second dispatch passes isInitialized(), then waits behind the first query.
        // Closing the database before releasing the worker reproduces the shutdown race.
        assertEquals(1, QueryDispatcher.dispatch(source, "shutdown-race", conn ->
                QueryOutput.found(List.of("must not be returned"))));
        db.close();
        releaseFirstQuery.countDown();

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, timeout(5000)).sendFailure(captor.capture());
        assertEquals("[ItemGraph] Query failed: ItemGraph database is not initialized",
                captor.getValue().getString());
    }

    @Test
    void testCanStillReportServerStopped() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isStopped()).thenReturn(true);

        boolean canReport = QueryDispatcher.canStillReport(source, server);
        assertFalse(canReport, "canStillReport must return false if server is stopped");
    }

    @Test
    void testCanStillReportDisconnectedPlayer() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);

        when(server.isStopped()).thenReturn(false);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(true);

        boolean canReport = QueryDispatcher.canStillReport(source, server);
        assertFalse(canReport, "canStillReport must return false if player has disconnected");
    }

    @Test
    void testCanStillReportActivePlayer() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);

        when(server.isStopped()).thenReturn(false);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(false);

        boolean canReport = QueryDispatcher.canStillReport(source, server);
        assertTrue(canReport, "canStillReport must return true for active, connected player");
    }

    @Test
    void testCanStillReportConsoleSource() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);

        when(server.isStopped()).thenReturn(false);
        when(source.getEntity()).thenReturn(null); // Console has no entity

        boolean canReport = QueryDispatcher.canStillReport(source, server);
        assertTrue(canReport, "canStillReport must return true for console/null entity source");
    }

    @Test
    void testCanStillReportNonPlayerEntity() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Entity nonPlayerEntity = mock(Entity.class);

        when(server.isStopped()).thenReturn(false);
        when(source.getEntity()).thenReturn(nonPlayerEntity);

        boolean canReport = QueryDispatcher.canStillReport(source, server);
        assertTrue(canReport, "canStillReport must return true for non-player entities like command blocks");
    }

    @Test
    void testQueryOutputConstructors() {
        QueryOutput found = QueryOutput.found(List.of("Line A", "Line B"));
        assertTrue(found.found());
        assertEquals(2, found.lines().size());
        assertEquals("Line A", found.lines().get(0));

        QueryOutput notFound = QueryOutput.notFound("Missing row #42");
        assertFalse(notFound.found());
        assertEquals(1, notFound.lines().size());
        assertEquals("Missing row #42", notFound.lines().get(0));
    }

    @Test
    void testDispatchOffThreadWithInitializedDatabaseSuccess(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("query_dispatch.db");
        DatabaseManager.getInstance().initialize(dbPath);

        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Thread fakeServerThread = new Thread();
        when(server.getRunningThread()).thenReturn(fakeServerThread);
        when(source.getServer()).thenReturn(server);

        // Dispatched from current thread (not fakeServerThread), so it executes synchronously
        int result = QueryDispatcher.dispatch(source, "test-query", conn -> QueryOutput.found(List.of("Success line 1")));

        assertEquals(1, result);
        verify(source, times(1)).sendSuccess(any(), eq(false));
        verify(source, never()).sendFailure(any());
    }

    @Test
    void testDispatchOffThreadWithInitializedDatabaseNotFound(@TempDir Path tempDir) throws Exception {
        Path dbPath = tempDir.resolve("query_dispatch_nf.db");
        DatabaseManager.getInstance().initialize(dbPath);

        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Thread fakeServerThread = new Thread();
        when(server.getRunningThread()).thenReturn(fakeServerThread);
        when(source.getServer()).thenReturn(server);

        int result = QueryDispatcher.dispatch(source, "test-query-nf", conn -> QueryOutput.notFound("Not found line"));

        assertEquals(0, result);
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(captor.capture());
        assertEquals("Not found line", captor.getValue().getString());
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void testShutdownIdempotent() {
        assertDoesNotThrow(QueryDispatcher::shutdown);
        assertDoesNotThrow(QueryDispatcher::shutdown);
    }
}
