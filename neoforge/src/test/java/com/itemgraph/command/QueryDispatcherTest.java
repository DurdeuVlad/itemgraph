package com.itemgraph.command;

import com.itemgraph.command.QueryDispatcher.QueryOutput;
import com.itemgraph.command.QueryDispatcher.QueryAction;
import com.itemgraph.db.DatabaseManager;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryDispatcherTest {

    @Test
    void structuredChatHoverAddsBoundedSafeFieldsWithoutChangingVisibleFormatterText() {
        String visible = "[OBSERVED] CHEST minecraft:overworld 1,64,2 -> PLAYER Admin : 1x at 2026-10-05T12:00:00Z";
        QueryDispatcher.ChatHoverDetail detail = new QueryDispatcher.ChatHoverDetail(
                "OBSERVED", "minecraft:diamond named stack", "sha256:abc123", "DROP_ITEM",
                "2026-10-05T12:00:00Z", "CHEST minecraft:overworld 1,64,2", "PLAYER Admin");

        Component component = QueryDispatcher.chatLine(visible, detail);
        HoverEvent hover = component.getStyle().getHoverEvent();
        String hoverText = hover.getValue(HoverEvent.Action.SHOW_TEXT).getString();

        assertEquals(visible, component.getString(), "QueryFormatter's visible text remains unchanged");
        assertTrue(hoverText.contains("Canonical metadata fingerprint: sha256:abc123"));
        assertTrue(hoverText.contains("UTC time: 2026-10-05T12:00:00Z"));
        assertTrue(hoverText.contains("Origin: CHEST minecraft:overworld 1,64,2"));
        assertFalse(hoverText.toLowerCase().contains("nbt"));
        assertFalse(hoverText.contains("raw component payload"));

        QueryDispatcher.ChatHoverDetail oversized = new QueryDispatcher.ChatHoverDetail(
                "OBSERVED", "x".repeat(400), "hash", "DROP_ITEM", "2026-10-05T12:00:00Z", "x", "y");
        assertTrue(QueryDispatcher.chatLine(visible, oversized).getStyle().getHoverEvent()
                .getValue(HoverEvent.Action.SHOW_TEXT).getString().contains("..."));
    }

    @Test
    void resultLocationTokenIsPlayerScopedOneUseAndRechecksOriginPermission() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        List<String> originPermissions = List.of(ItemGraphPermissions.TRACE);
        QueryDispatcher.LocationAction target = new QueryDispatcher.LocationAction(
                "minecraft:overworld", 12.0, 65.0, -8.0);
        UUID crossPlayerToken = QueryDispatcher.issueLocationGrant(ownerId, originPermissions, target,
                System.currentTimeMillis());
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer otherPlayer = mock(ServerPlayer.class);
        CommandSourceStack otherSource = mock(CommandSourceStack.class);
        when(otherPlayer.getUUID()).thenReturn(otherId);
        when(otherSource.getEntity()).thenReturn(otherPlayer);
        when(otherSource.getServer()).thenReturn(server);
        assertFalse(QueryDispatcher.consumeLocationGrant(otherSource, crossPlayerToken),
                "a different player cannot use the token");

        ServerPlayer owner = mock(ServerPlayer.class);
        CommandSourceStack ownerSource = mock(CommandSourceStack.class);
        PlayerList players = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(owner.getUUID()).thenReturn(ownerId);
        when(ownerSource.getEntity()).thenReturn(owner);
        when(ownerSource.getServer()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayer(ownerId)).thenReturn(owner);
        when(server.getLevel(any(ResourceKey.class))).thenReturn(overworld);

        UUID revokedToken = QueryDispatcher.issueLocationGrant(ownerId, originPermissions, target,
                System.currentTimeMillis());
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.TRACE));
        assertFalse(QueryDispatcher.consumeLocationGrant(ownerSource, revokedToken),
                "the origin permission is checked again at click time");
        verify(server, never()).getLevel(any(ResourceKey.class));

        ItemGraphPermissions.setChecker((source, node) -> true);
        UUID singleUseToken = QueryDispatcher.issueLocationGrant(ownerId, originPermissions, target,
                System.currentTimeMillis());
        assertTrue(QueryDispatcher.consumeLocationGrant(ownerSource, singleUseToken));
        assertFalse(QueryDispatcher.consumeLocationGrant(ownerSource, singleUseToken),
                "successful links are one-use");
        verify(owner).teleportTo(eq(overworld), eq(12.0), eq(65.0), eq(-8.0), eq(Set.of()), anyFloat(), anyFloat());
    }

    @Test
    void pageLocationTokenRetainsPageAndOriginatingAuditPermissions() {
        UUID ownerId = UUID.randomUUID();
        ServerPlayer owner = mock(ServerPlayer.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(owner.getUUID()).thenReturn(ownerId);
        when(source.getEntity()).thenReturn(owner);
        UUID token = QueryDispatcher.issueLocationGrant(ownerId,
                List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.PAGE, ItemGraphPermissions.AUDIT),
                new QueryDispatcher.LocationAction("minecraft:overworld", 1, 64, 1), System.currentTimeMillis());

        ItemGraphPermissions.setChecker((checkedSource, node) -> !node.equals(ItemGraphPermissions.PAGE));
        assertFalse(QueryDispatcher.consumeLocationGrant(source, token),
                "a continuation location token must be rejected after PAGE is revoked");
    }

    @Test
    void dataQueriesRejectAnyMissingPermissionInTheRequiredSurfaceSet() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        ItemGraphPermissions.setChecker((checkedSource, node) -> !node.equals(ItemGraphPermissions.AUDIT));
        AtomicBoolean queryRan = new AtomicBoolean();
        AtomicBoolean callbackRan = new AtomicBoolean();

        int accepted = QueryDispatcher.dispatchData(source,
                List.of(ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT), "protected GUI",
                conn -> {
                    queryRan.set(true);
                    return "protected";
                }, (returnedSource, result) -> callbackRan.set(true), () -> {});

        assertEquals(0, accepted);
        assertFalse(queryRan.get(), "authorization precedes candidate/query reads");
        assertFalse(callbackRan.get());
    }

    @Test
    void singlePermissionDataQueryRejectsBeforeSubmittingWhenPermissionIsMissing() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        ItemGraphPermissions.setChecker((checkedSource, node) -> !node.equals(ItemGraphPermissions.GUI));
        AtomicBoolean queryRan = new AtomicBoolean();
        AtomicBoolean callbackRan = new AtomicBoolean();
        AtomicBoolean failureRan = new AtomicBoolean();

        int accepted = QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "protected GUI",
                conn -> {
                    queryRan.set(true);
                    return "protected";
                }, (returnedSource, result) -> callbackRan.set(true), () -> failureRan.set(true));

        assertEquals(0, accepted);
        assertFalse(queryRan.get(), "permission is checked before the JDBC task is submitted");
        assertFalse(callbackRan.get());
        assertTrue(failureRan.get(), "denial clears the caller's loading state");
    }

    @Test
    void locationActionRejectsMissingDimensionAndNonFiniteCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueryDispatcher.LocationAction(" ", 1.0, 2.0, 3.0));
        assertThrows(IllegalArgumentException.class,
                () -> new QueryDispatcher.LocationAction("minecraft:overworld", Double.NaN, 2.0, 3.0));
        assertThrows(IllegalArgumentException.class,
                () -> new QueryDispatcher.LocationAction("minecraft:overworld", 1.0, Double.POSITIVE_INFINITY, 3.0));
    }

    @BeforeEach
    void allowNamedNodesUnlessTheTestOverridesTheProvider() {
        ItemGraphPermissions.setChecker((source, node) -> true);
    }

    @Test
    void statusQueryFailuresHideRawJdbcMessagesFromCommandCallers() {
        SQLException databaseFailure = new SQLException("jdbc:mariadb://private-host/db user=admin password=secret");

        assertEquals("status query failed; inspect the server log for connection details",
                QueryDispatcher.callerFailureMessage("status", databaseFailure));
        assertEquals(databaseFailure.getMessage(), QueryDispatcher.callerFailureMessage("trace", databaseFailure));
    }

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
        ItemGraphPermissions.setChecker(null);
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

        int result = QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "trace", conn -> QueryOutput.found(List.of("test line")));

        assertEquals(0, result, "dispatch must return 0 when database is not connected");

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(captor.capture());
        String failureText = captor.getValue().getString();
        assertEquals("[ItemGraph] Query failed: the ItemGraph database is not connected. See /ig status.",
                failureText);

        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void interactiveQueryActionCarriesAPlayerScopedRunCommand() {
        CommandSourceStack source = mock(CommandSourceStack.class);

        QueryDispatcher.sendActions(source,
                List.of(new QueryAction("Next", "/ig lookup page 2 CHAT_MESSAGE 20")));

        ArgumentCaptor<java.util.function.Supplier<Component>> captor =
                ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(source).sendSuccess(captor.capture(), eq(false));
        Component controls = captor.getValue().get();
        assertEquals("[ItemGraph] [Next]", controls.getString());
        assertNotNull(controls.getSiblings().get(0).getStyle().getClickEvent());
        assertEquals("/ig lookup page 2 CHAT_MESSAGE 20",
                controls.getSiblings().get(0).getStyle().getClickEvent().getValue());
    }

    @Test
    void lookupOnlyGrantCannotRunOrDiscoverSensitiveAuditLookupIncludingBroadCounts() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        AtomicBoolean queryRan = new AtomicBoolean();
        ItemGraphPermissions.setChecker((checkedSource, node) ->
                node.equals(ItemGraphPermissions.COMMAND) || node.equals(ItemGraphPermissions.LOOKUP));

        int result = QueryDispatcher.dispatch(source, ItemGraphCommands.lookupPermissionsForType("all"),
                "all lookup", conn -> {
                    queryRan.set(true); // Includes both result rows and any has-next/count probe.
                    return QueryOutput.found(List.of("CHAT_MESSAGE private metadata"));
                });

        assertEquals(0, result);
        assertFalse(queryRan.get(), "audit denial must happen before result rows or next-page metadata are queried");
        verify(source).sendFailure(any(Component.class));
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void entitylessOffThreadDispatchDropsResultWhenAuditPermissionIsRevokedDuringWait(@TempDir Path tempDir)
            throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("permission-revoked-rcon.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        Thread serverThread = new Thread("mock-server-thread");
        AtomicBoolean auditAllowed = new AtomicBoolean(true);
        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(null);
        when(server.getRunningThread()).thenReturn(serverThread);
        ItemGraphPermissions.setChecker((checkedSource, node) ->
                node.equals(ItemGraphPermissions.COMMAND)
                        || node.equals(ItemGraphPermissions.LOOKUP)
                        || (node.equals(ItemGraphPermissions.AUDIT) && auditAllowed.get()));

        int result = QueryDispatcher.dispatch(source,
                List.of(ItemGraphPermissions.LOOKUP, ItemGraphPermissions.AUDIT), "revoked-rcon", conn -> {
                    auditAllowed.set(false);
                    return QueryOutput.found(List.of("private audit metadata"),
                            List.of(new QueryAction("Next", "/ig page 2 token")));
                });

        assertEquals(0, result);
        verify(source).sendFailure(any(Component.class));
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void testDispatchReportsFailureWhenDatabaseClosesAfterPrecheck(@TempDir Path tempDir) throws Exception {
        DatabaseManager db = DatabaseManager.getInstance();
        db.initialize(tempDir.resolve("shutdown-race.db"));

        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch firstQueryStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstQuery = new CountDownLatch(1);
        CountDownLatch callbacksQueued = new CountDownLatch(2);
        List<Runnable> queuedCallbacks = new CopyOnWriteArrayList<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(false);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedCallbacks.add(invocation.getArgument(0));
            callbacksQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "blocking-query", conn -> {
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
        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "shutdown-race", conn ->
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
    void testEntitylessServerThreadGetsAcknowledgementBeforeAsyncResult(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("server-thread-rcon.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedCallback = new AtomicReference<>();
        List<String> successMessages = new CopyOnWriteArrayList<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(null);
        when(source.hasPermission(2)).thenReturn(true);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            java.util.function.Supplier<Component> message = invocation.getArgument(0);
            successMessages.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        doAnswer(invocation -> {
            queuedCallback.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "server-thread-rcon", conn -> {
            queryStarted.countDown();
            try {
                assertTrue(releaseQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return QueryOutput.found(List.of("completed trace"));
        }));
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(successMessages.get(0).contains("accepted"));
        releaseQuery.countDown();
        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        queuedCallback.get().run();

        verify(source, times(1)).sendSuccess(any(), anyBoolean());
        verify(source, never()).sendFailure(any());
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
            resultCode.set(QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "off-thread-player", conn -> {
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
        verify(source, times(1)).sendSuccess(any(), anyBoolean());
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
            QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "interrupted-rcon", conn -> {
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
    void testRconSqlFailureDoesNotExposeCompletableFutureWrapper(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("rcon-sql-failure.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        AtomicReference<Integer> resultCode = new AtomicReference<>();
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(Thread.currentThread());
        when(server.isStopped()).thenReturn(false);
        Thread caller = new Thread(() -> resultCode.set(QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "rcon-sql-failure",
                conn -> { throw new SQLException("clean SQL failure"); })));
        caller.start();
        caller.join(5_000);

        assertFalse(caller.isAlive());
        assertEquals(0, resultCode.get());
        verify(source).sendFailure(failure.capture());
        assertEquals("[ItemGraph] Query failed: clean SQL failure", failure.getValue().getString());
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

        Thread slowCaller = new Thread(() -> slowResult.set(QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "slow-rcon", conn -> {
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

        Thread quickCaller = new Thread(() -> quickResult.set(QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "quick-rcon", conn -> {
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
    void testRconTimeoutCancelsBeforeNextStatementStarts(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("rcon-timeout-before-statement.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        CountDownLatch queryEntered = new CountDownLatch(1);
        CountDownLatch releaseStatement = new CountDownLatch(1);
        CountDownLatch queryFinished = new CountDownLatch(1);
        AtomicReference<Statement> activeStatement = new AtomicReference<>();
        AtomicReference<Integer> resultCode = new AtomicReference<>();

        when(source.getServer()).thenReturn(server);
        when(server.getRunningThread()).thenReturn(Thread.currentThread());
        when(server.isStopped()).thenReturn(false);

        Thread caller = new Thread(() -> resultCode.set(QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "cancel-before-statement", conn -> {
            queryEntered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = releaseStatement.await(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
            }
            try (Statement statement = conn.createStatement()) {
                activeStatement.set(statement);
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
            return QueryOutput.found(List.of("query completed"));
        })));
        caller.start();
        assertTrue(queryEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        verify(source, timeout(7_000)).sendFailure(any());
        caller.join(5_000);
        releaseStatement.countDown();
        boolean cancelledBeforeStatementCompleted = queryFinished.await(5, java.util.concurrent.TimeUnit.SECONDS);
        if (!cancelledBeforeStatementCompleted && activeStatement.get() != null) {
            activeStatement.get().cancel();
        }

        assertFalse(caller.isAlive());
        assertEquals(0, resultCode.get());
        assertTrue(cancelledBeforeStatementCompleted, "a cancelled query must not start long SQL after timeout");

        Thread quickCaller = new Thread(() -> resultCode.set(QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "after-cancel", conn ->
                QueryOutput.found(List.of("worker available")))));
        quickCaller.start();
        quickCaller.join(5_000);

        assertFalse(quickCaller.isAlive());
        assertEquals(1, resultCode.get());
        verify(source).sendSuccess(any(), anyBoolean());
    }

    @Test
    void testTextQueryRechecksPermissionBeforeDelivery(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("permission-revoked-query.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(false);
        AtomicBoolean traceAllowed = new AtomicBoolean(true);
        ItemGraphPermissions.setChecker((checkedSource, node) ->
                node.equals(ItemGraphPermissions.COMMAND)
                        || (node.equals(ItemGraphPermissions.TRACE) && traceAllowed.get()));
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.TRACE, "permission-revoked", conn ->
                QueryOutput.found(List.of("sensitive trace"))));
        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        traceAllowed.set(false);
        queuedTask.get().run();

        ArgumentCaptor<Component> failures = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failures.capture());
        assertTrue(failures.getValue().getString().contains("no longer have permission"),
                "a revoked result must be reported instead of silently dropped");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.function.Supplier<Component>> successes =
                ArgumentCaptor.forClass(java.util.function.Supplier.class);
        verify(source, times(1)).sendSuccess(successes.capture(), anyBoolean());
        assertFalse(successes.getAllValues().stream().anyMatch(supplier ->
                        supplier.get().getString().contains("sensitive")),
                "revoked query output must never reach the player");
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
        int result = QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "test-query", conn -> QueryOutput.found(List.of("Success line 1")));

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

        int result = QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "test-query-nf", conn -> QueryOutput.notFound("Not found line"));

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

        assertEquals(1, QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "flow-page", conn -> {
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
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
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

        assertEquals(1, QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "inline-callback", conn -> {
            queryStarted.countDown();
            try {
                assertTrue(releaseQuery.await(5, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return "page";
        },
                (returnedSource, result) -> consumerCalled.set(true), () -> {
                    failureDelivered.set(true);
                    failureThread.set(Thread.currentThread());
                }));
        try {
            assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            releaseQuery.countDown();
        }
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
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch inlineCallbackRan = new CountDownLatch(1);
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenAnswer(invocation -> {
            assertSame(serverThread, Thread.currentThread());
            return player;
        });
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            inlineCallbackRan.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "inline-text-query", conn -> {
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
        verify(source, atLeastOnce()).getEntity();
        verify(source, never()).hasPermission(2);
        verify(source, times(1)).sendSuccess(any(), anyBoolean());
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

        assertEquals(1, QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "failing-flow-page",
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

        assertEquals(1, QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "blocking-query", conn -> {
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
            assertEquals(1, QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "queued-query", conn -> "queued",
                    (returnedSource, result) -> {}));
        }
        assertEquals(0, QueryDispatcher.dispatchData(source, ItemGraphPermissions.GUI, "overflow-query", conn -> "rejected",
                (returnedSource, result) -> {}));
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("queue is full"));
        releaseWorker.countDown();
    }

    @Test
    void playerQueryGetsAcceptedMessageBeforeAsyncResult(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("player-accepted.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        List<String> successMessages = new CopyOnWriteArrayList<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(false);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));
        doAnswer(invocation -> {
            java.util.function.Supplier<Component> message = invocation.getArgument(0);
            successMessages.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());

        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "player-query", conn ->
                QueryOutput.found(List.of("result line"))));
        assertFalse(successMessages.isEmpty(), "the player must see that the query was accepted");
        assertTrue(successMessages.get(0).contains("accepted"));
        assertTrue(callbackQueued.await(5, java.util.concurrent.TimeUnit.SECONDS));
        queuedTask.get().run();
        assertEquals("result line", successMessages.get(1));
    }

    @Test
    void playerExecutionTimeoutReportsTimeoutInsteadOfJdbcCancellation(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("player-exec-timeout.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(false);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "player-timeout", conn -> {
            queryStarted.countDown();
            try {
                releaseQuery.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return QueryOutput.found(List.of("late result"));
        }));
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // Hold the query past the five-second dispatcher deadline so the timeout fires mid-execution.
        Thread.sleep(6_000);
        releaseQuery.countDown();
        assertTrue(callbackQueued.await(10, java.util.concurrent.TimeUnit.SECONDS));
        queuedTask.get().run();

        ArgumentCaptor<Component> failures = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failures.capture());
        String message = failures.getValue().getString();
        assertTrue(message.contains("timed out"), message);
        assertTrue(message.contains("retry"), message);
        assertFalse(message.contains("cancelled"), message);
    }

    @Test
    void dataQueryTimeoutReportsTimeoutAndRunsFailureConsumer(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("data-timeout.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch releaseQuery = new CountDownLatch(1);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> queuedTask = new AtomicReference<>();
        AtomicBoolean failureRan = new AtomicBoolean(false);
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(source.hasPermission(2)).thenReturn(true);
        when(player.hasDisconnected()).thenReturn(false);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTask.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        assertEquals(1, QueryDispatcher.dispatchData(source, List.of(ItemGraphPermissions.GUI), "gui-timeout",
                conn -> {
                    queryStarted.countDown();
                    try {
                        releaseQuery.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new SQLException(e);
                    }
                    return "late";
                },
                (s, result) -> { },
                () -> failureRan.set(true)));
        assertTrue(queryStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(6_000);
        releaseQuery.countDown();
        assertTrue(callbackQueued.await(10, java.util.concurrent.TimeUnit.SECONDS));
        queuedTask.get().run();

        assertTrue(failureRan.get(), "a timed-out data query must run the failure consumer so menus can restore");
        ArgumentCaptor<Component> failures = ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failures.capture());
        String message = failures.getValue().getString();
        assertTrue(message.contains("timed out"), message);
        assertFalse(message.contains("cancelled"), "the JDBC cancellation sentinel must not leak: " + message);
    }

    @Test
    void queuedPlayerQueryTimeoutReportsQueueWait(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("player-queue-timeout.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch occupierStarted = new CountDownLatch(1);
        CountDownLatch releaseOccupier = new CountDownLatch(1);
        CountDownLatch callbacksQueued = new CountDownLatch(2);
        List<Runnable> queuedTasks = new CopyOnWriteArrayList<>();
        Thread serverThread = Thread.currentThread();

        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(player.hasDisconnected()).thenReturn(false);
        when(server.getRunningThread()).thenReturn(serverThread);
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            queuedTasks.add(invocation.getArgument(0));
            callbacksQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        // The first query occupies the single worker past the dispatcher deadline.
        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "occupier", conn -> {
            occupierStarted.countDown();
            try {
                releaseOccupier.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException(e);
            }
            return QueryOutput.found(List.of("occupier done"));
        }));
        assertTrue(occupierStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        // The second query waits in the worker queue while the first blocks.
        assertEquals(1, QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "queued", conn ->
                QueryOutput.found(List.of("queued done"))));
        Thread.sleep(6_000);
        releaseOccupier.countDown();
        assertTrue(callbacksQueued.await(10, java.util.concurrent.TimeUnit.SECONDS));
        queuedTasks.forEach(Runnable::run);

        ArgumentCaptor<Component> failures = ArgumentCaptor.forClass(Component.class);
        verify(source, times(2)).sendFailure(failures.capture());
        List<String> messages = failures.getAllValues().stream().map(Component::getString).toList();
        assertTrue(messages.get(0).contains("timed out"), messages.get(0));
        assertTrue(messages.get(1).contains("waited too long in the worker queue"),
                "a deadline reached while still queued must say so: " + messages.get(1));
    }

    @Test
    void callerFailureMessageMapsDispatcherTimeouts() {
        QueryDispatcher.QueryCancellation executionTimeout = new QueryDispatcher.QueryCancellation();
        executionTimeout.markStarted();
        executionTimeout.cancelTimedOut();
        assertEquals("the query timed out; narrow the time window, filters, or limit and retry",
                QueryDispatcher.callerFailureMessage("trace",
                        new SQLException("query cancelled before completion"), executionTimeout));

        QueryDispatcher.QueryCancellation queueTimeout = new QueryDispatcher.QueryCancellation();
        queueTimeout.cancelTimedOut();
        assertEquals("the query waited too long in the worker queue; retry shortly",
                QueryDispatcher.callerFailureMessage("trace",
                        new SQLException("query cancelled before execution began"), queueTimeout));

        QueryDispatcher.QueryCancellation plainCancel = new QueryDispatcher.QueryCancellation();
        plainCancel.cancel();
        SQLException realFailure = new SQLException("syntax error near WHERE");
        assertEquals("syntax error near WHERE",
                QueryDispatcher.callerFailureMessage("trace", realFailure, plainCancel));
    }

    @Test
    void testShutdownIdempotent() {
        assertDoesNotThrow(QueryDispatcher::shutdown);
        assertDoesNotThrow(QueryDispatcher::shutdown);
    }
}
