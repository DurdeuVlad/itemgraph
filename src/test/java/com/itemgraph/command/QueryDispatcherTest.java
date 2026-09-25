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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        CountDownLatch callbacksQueued = new CountDownLatch(2);
        List<Runnable> queuedCallbacks = new CopyOnWriteArrayList<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedCallbacks.add(invocation.getArgument(0));
            callbacksQueued.countDown();
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
        assertTrue(callbacksQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        queuedCallbacks.forEach(Runnable::run);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source, timeout(5000)).sendFailure(captor.capture());
        assertEquals("[ItemGraph] Query failed: ItemGraph database is not initialized",
                captor.getValue().getString());
    }

    @Test
    void testPlayerSourceFromOffThreadDoesNotUseSynchronousRconBranch(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("off-thread-player-query.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        CountDownLatch dispatchReturned = new CountDownLatch(1);
        AtomicReference<Integer> resultCode = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        Thread caller = new Thread(() -> {
            resultCode.set(QueryDispatcher.dispatch(source, "off-thread-player", conn -> {
                queryStarted.countDown();
                try {
                    assertTrue(releaseQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
                return QueryOutput.found(List.of("private trace"));
            }));
            dispatchReturned.countDown();
        });
        caller.start();
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        boolean returnedWithoutBlocking = dispatchReturned.await(1, java.util.concurrent.TimeUnit.SECONDS);
        releaseQuery.countDown();
        caller.join(5_000);

        assertTrue(returnedWithoutBlocking, "player command dispatch must not block off-thread");
        assertEquals(1, resultCode.get());
        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        verify(source, never()).sendSuccess(any(), anyBoolean());
        verify(source, never()).sendFailure(any());
    }

    @Test
    void testRconInterruptionRestoresInterruptFlag(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("rcon-interrupt.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(Thread.currentThread());
        when(server.isStopped()).thenReturn(false);

        Thread caller = new Thread(() -> {
            QueryDispatcher.dispatch(source, "interrupted-rcon", conn -> {
                queryStarted.countDown();
                try {
                    assertTrue(releaseQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException(e);
                }
                return QueryOutput.found(List.of("result"));
            });
            interruptRestored.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(5_000);
        releaseQuery.countDown();

        assertFalse(caller.isAlive());
        assertTrue(interruptRestored.get());
        verify(source).sendFailure(any());
    }

    @Test
    void testRconTimeoutInterruptsSqliteQueryAndReleasesWorker(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("rcon-timeout-cancel.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch queryFinished = new CountDownLatch(1);
        AtomicReference<Integer> slowResult = new AtomicReference<>();
        AtomicReference<Integer> quickResult = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);

        Thread slowCaller = new Thread(() -> slowResult.set(QueryDispatcher.dispatch(source, "slow-rcon", conn -> {
            try (Statement statement = conn.createStatement()) {
                queryStarted.countDown();
                try (ResultSet rs = statement.executeQuery("""
                        WITH RECURSIVE counter(x) AS (
                            VALUES(0)
                            UNION ALL
                            SELECT x + 1 FROM counter WHERE x < 1000000000
                        )
                        SELECT SUM(x) FROM counter
                        """)) {
                    rs.next();
                }
            } finally {
                queryFinished.countDown();
            }
            return QueryOutput.found(List.of("slow query completed"));
        })));
        slowCaller.start();
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        slowCaller.join(10_000);
        assertFalse(slowCaller.isAlive());
        assertEquals(0, slowResult.get());
        assertTrue(queryFinished.await(5, java.util.concurrent.TimeUnit.SECONDS));

        Thread quickCaller = new Thread(() -> quickResult.set(QueryDispatcher.dispatch(source, "quick-rcon", conn -> {
            try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
            return QueryOutput.found(List.of("quick query completed"));
        })));
        quickCaller.start();
        quickCaller.join(5_000);

        assertFalse(quickCaller.isAlive());
        assertEquals(1, quickResult.get());
        verify(source).sendFailure(any());
        verify(source).sendSuccess(any(), anyBoolean());
    }

    @Test
    void testTextQueryRechecksPermissionBeforeDelivery(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("permission-revoked-query.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, "permission-revoked", conn ->
                QueryOutput.found(List.of("sensitive trace"))));
        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        when(source.hasPermission(2)).thenReturn(false);
        queuedTask.get().run();

        verify(source).hasPermission(2);
        verify(source, never()).sendSuccess(any(), anyBoolean());
        verify(source, never()).sendFailure(any());
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
    void testDispatchDataQueriesReadOnlyOffThreadAndDeliversOnServerExecutor(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("data-query.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        AtomicReference<Thread> queryThread = new AtomicReference<>();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>();
        Thread callerThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.getRunningThread()).thenReturn(callerThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatchData(source, "flow-page", conn -> {
            queryThread.set(Thread.currentThread());
            assertThrows(SQLException.class, () -> {
                try (var stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE should_not_exist (id INTEGER)");
                }
            });
            return "page result";
        }, (returnedSource, page) -> {
            assertSame(source, returnedSource);
            callbackThread.set(Thread.currentThread());
            result.set(page);
        }));

        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNotSame(callerThread, queryThread.get(), "database work must run off-thread");
        queuedTask.get().run();
        assertSame(callerThread, callbackThread.get(), "delivery must run through the server executor");
        assertEquals("page result", result.get());
        verify(source, never()).sendFailure(any());
    }

    @Test
    void testDispatchDataDropsInlineCallbackWhenServerExecutorRunsOffThread(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("inline-server-executor.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch callbackExecuted = new CountDownLatch(1);
        AtomicBoolean consumerCalled = new AtomicBoolean();
        AtomicBoolean failureDelivered = new AtomicBoolean();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            callbackExecuted.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatchData(source, "inline-callback", conn -> "page",
                (returnedSource, result) -> consumerCalled.set(true), () -> {
                    failureDelivered.set(true);
                    failureThread.set(Thread.currentThread());
                }));
        assertTrue(callbackExecuted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(consumerCalled.get(), "an off-thread inline callback must not access Minecraft state");
        assertTrue(failureDelivered.get(), "the failure callback must release browser loading state");
        assertNotSame(serverThread, failureThread.get());
        verify(source, never()).getEntity();
        verify(source, never()).hasPermission(2);
        verify(source, never()).sendFailure(any());
    }

    @Test
    void testTextQueryDropsInlineCompletionOutsideServerThread(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("inline-text-query.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch inlineCallbackRan = new CountDownLatch(1);
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            inlineCallbackRan.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, "inline-text-query", conn -> {
            queryStarted.countDown();
            try {
                assertTrue(releaseQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return QueryOutput.found(List.of("sensitive result"));
        }));
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        releaseQuery.countDown();
        assertTrue(inlineCallbackRan.await(5, java.util.concurrent.TimeUnit.SECONDS));
        verify(source, never()).getEntity();
        verify(source, never()).sendSuccess(any(), anyBoolean());
        verify(source, never()).sendFailure(any());
    }

    @Test
    void testDispatchDataInvokesFailureConsumerOnServerThread(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("data-query-failure.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        AtomicBoolean failureDelivered = new AtomicBoolean();
        AtomicReference<Thread> failureThread = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatchData(source, "failing-flow-page",
                conn -> { throw new SQLException("expected query failure"); },
                (returnedSource, result) -> fail("failed data query must not invoke success"),
                () -> {
                    failureDelivered.set(true);
                    failureThread.set(Thread.currentThread());
                }));
        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        queuedTask.get().run();

        assertTrue(failureDelivered.get());
        assertSame(serverThread, failureThread.get());
        verify(source).sendFailure(any());
    }

    @Test
    void testDispatchDataRejectsQueriesWhenBoundedWorkerQueueIsFull(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("bounded-query-queue.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        when(source.getServer()).thenReturn(server);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatchData(source, "blocking-query", conn -> {
            workerStarted.countDown();
            try {
                assertTrue(releaseWorker.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return "complete";
        }, (returnedSource, result) -> {}));
        assertTrue(workerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));

        for (int i = 0; i < 64; i++) {
            assertEquals(1, QueryDispatcher.dispatchData(source, "queued-query", conn -> "queued",
                    (returnedSource, result) -> {}));
        }
        assertEquals(0, QueryDispatcher.dispatchData(source, "overflow-query", conn -> "rejected",
                (returnedSource, result) -> {}));
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("queue is full"));
        releaseWorker.countDown();
    }

    @Test
    void testShutdownIdempotent() {
        assertDoesNotThrow(QueryDispatcher::shutdown);
        assertDoesNotThrow(QueryDispatcher::shutdown);
    }
}
