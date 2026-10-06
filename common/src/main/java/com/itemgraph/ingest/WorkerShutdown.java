package com.itemgraph.ingest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded shutdown waits for ItemGraph's database workers. */
final class WorkerShutdown {
    static final long GRACEFUL_WAIT_MS = 5_000;
    static final long INTERRUPTED_WAIT_MS = 5_000;
    static final long EVIDENCE_SPOOL_WAIT_MS = 5_000;

    private WorkerShutdown() {}

    static boolean stop(ExecutorService worker, long gracefulWaitMs, long interruptedWaitMs) {
        worker.shutdown();
        boolean interrupted = false;
        boolean terminated = false;
        try {
            try {
                terminated = worker.awaitTermination(gracefulWaitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException stopInterrupted) {
                interrupted = true;
            }
            if (!terminated) {
                worker.shutdownNow();
                try {
                    terminated = worker.awaitTermination(interruptedWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException stopInterrupted) {
                    interrupted = true;
                    worker.shutdownNow();
                    try {
                        terminated = worker.awaitTermination(interruptedWaitMs, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException repeatedInterrupt) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return terminated || worker.isTerminated();
    }

    static boolean stop(Thread worker, long gracefulWaitMs, long interruptedWaitMs) {
        boolean interrupted = false;
        try {
            try {
                worker.join(gracefulWaitMs);
            } catch (InterruptedException stopInterrupted) {
                interrupted = true;
            }
            if (worker.isAlive()) {
                worker.interrupt();
                try {
                    worker.join(interruptedWaitMs);
                } catch (InterruptedException stopInterrupted) {
                    interrupted = true;
                    worker.interrupt();
                    try {
                        worker.join(interruptedWaitMs);
                    } catch (InterruptedException repeatedInterrupt) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return !worker.isAlive();
    }

    static SpoolWriteResult writeSpoolBounded(java.util.concurrent.Callable<Integer> writer, long waitMs) {
        return startSpoolWriter(writer).await(waitMs);
    }

    static SpoolWriteHandle startSpoolWriter(java.util.concurrent.Callable<Integer> writer) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicInteger written = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread spoolWorker = new Thread(() -> {
            try {
                written.set(writer.call());
            } catch (Throwable writeFailure) {
                failure.set(writeFailure);
            } finally {
                completed.countDown();
            }
        }, "ItemGraph-Evidence-Spool");
        spoolWorker.setDaemon(true);
        spoolWorker.start();
        return new SpoolWriteHandle(spoolWorker, completed, written, failure);
    }

    static final class SpoolWriteHandle {
        private final Thread worker;
        private final CountDownLatch completed;
        private final AtomicInteger written;
        private final AtomicReference<Throwable> failure;

        private SpoolWriteHandle(Thread worker, CountDownLatch completed,
                                 AtomicInteger written, AtomicReference<Throwable> failure) {
            this.worker = worker;
            this.completed = completed;
            this.written = written;
            this.failure = failure;
        }

        boolean isComplete() {
            return completed.getCount() == 0;
        }

        Thread worker() {
            return worker;
        }

        SpoolWriteResult await(long waitMs) {
            try {
                if (completed.await(waitMs, TimeUnit.MILLISECONDS)) {
                    return new SpoolWriteResult(true, written.get(), failure.get());
                }
                worker.interrupt();
                return new SpoolWriteResult(false, written.get(), null);
            } catch (InterruptedException interrupted) {
                worker.interrupt();
                Thread.currentThread().interrupt();
                return new SpoolWriteResult(false, written.get(), interrupted);
            }
        }
    }

    record SpoolWriteResult(boolean completed, int written, Throwable failure) {}
}
