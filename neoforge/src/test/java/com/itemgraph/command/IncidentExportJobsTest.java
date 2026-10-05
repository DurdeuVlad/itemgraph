package com.itemgraph.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentExportJobsTest {

    @Test
    void permissionCheckReadsCurrentAuthorizationOnServerExecutorAndFailsClosed() throws Exception {
        ThreadPoolExecutor serverExecutor = new ThreadPoolExecutor(1, 1, 0,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r ->
                new Thread(r, "ItemGraph-Test-Server"));
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        AtomicBoolean levelFourPermission = new AtomicBoolean(true);
        AtomicReference<Thread> permissionCheckThread = new AtomicReference<>();
        AtomicReference<Thread> serverThread = new AtomicReference<>();
        try {
            serverExecutor.execute(() -> {
                serverThread.set(Thread.currentThread());
                blockerStarted.countDown();
                try {
                    releaseBlocker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            FutureTask<Boolean> authorization = new FutureTask<>(() ->
                    IncidentExportJobs.checkOnServerThread(serverExecutor,
                            () -> Thread.currentThread() == serverThread.get(), () -> {
                        permissionCheckThread.set(Thread.currentThread());
                        return levelFourPermission.get();
                    }, 2_000));
            new Thread(authorization, "ItemGraph-Permission-Test-Caller").start();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (serverExecutor.getQueue().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(serverExecutor.getQueue().isEmpty(), "the permission read should wait for the server thread");
            levelFourPermission.set(false);
            releaseBlocker.countDown();

            assertFalse(authorization.get(2, TimeUnit.SECONDS),
                    "the gate must read permission after revocation, not a captured command snapshot");
            assertTrue(permissionCheckThread.get() != null
                            && permissionCheckThread.get().getName().equals("ItemGraph-Test-Server"),
                    "the permission supplier must execute on the server executor");

            AtomicBoolean offThreadPermissionWasRead = new AtomicBoolean();
            assertFalse(IncidentExportJobs.checkOnServerThread(serverExecutor, () -> false, () -> {
                offThreadPermissionWasRead.set(true);
                return true;
            }, 1_000), "the gate must reject an executor that does not prove server-thread execution");
            assertFalse(offThreadPermissionWasRead.get(), "off-thread Minecraft state must never be read");

            assertFalse(IncidentExportJobs.checkOnServerThread(ignored -> {}, () -> true, () -> true, 10),
                    "a stopped/unresponsive server executor must fail closed on timeout");
        } finally {
            releaseBlocker.countDown();
            serverExecutor.shutdownNow();
            assertTrue(serverExecutor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }
}
