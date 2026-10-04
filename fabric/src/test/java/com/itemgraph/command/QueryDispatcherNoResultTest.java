package com.itemgraph.command;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryDispatcherNoResultTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Registries may already be initialized by another suite.
        }
    }

    @AfterEach
    void tearDown() {
        QueryDispatcher.shutdown();
        DatabaseManager.getInstance().close();
    }

    @Test
    void noResultIsReturnedAsACommandFailureOnFabric(@TempDir Path tempDir) throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("no-result.db"));
        CommandSourceStack source = mock(CommandSourceStack.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CountDownLatch callbackQueued = new CountDownLatch(1);
        AtomicReference<Runnable> callback = new AtomicReference<>();
        when(source.getServer()).thenReturn(server);
        when(source.getEntity()).thenReturn(player);
        when(source.hasPermission(2)).thenReturn(true);
        when(player.hasDisconnected()).thenReturn(false);
        when(server.getRunningThread()).thenReturn(Thread.currentThread());
        when(server.isStopped()).thenReturn(false);
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            callbackQueued.countDown();
            return null;
        }).when(server).execute(any(Runnable.class));

        int result = QueryDispatcher.dispatch(source, ItemGraphPermissions.COMMAND, "lookup", connection ->
                QueryDispatcher.QueryOutput.notFound("[ItemGraph] No recorded history found."));

        assertEquals(1, result, "the command reports asynchronous query acceptance");
        assertTrue(callbackQueued.await(5, TimeUnit.SECONDS), "query result should return to the server thread");
        callback.get().run();

        org.mockito.ArgumentCaptor<Component> failure = org.mockito.ArgumentCaptor.forClass(Component.class);
        verify(source).sendFailure(failure.capture());
        assertEquals("[ItemGraph] No recorded history found.", failure.getValue().getString());
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }
}
