package com.itemgraph.command;

import com.itemgraph.query.AuditLookupFilters;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the small bounded set of cancellable export and verification jobs. */
final class IncidentExportJobs {
    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentExportJobs.class);
    private static final int MAX_ACTIVE_JOBS = 4;
    static final long JOB_TIMEOUT_MS = 120_000L;
    private static final AtomicInteger ACTIVE_JOBS = new AtomicInteger();
    private static final AtomicLong NEXT_ID = new AtomicLong();
    private static final ConcurrentHashMap<String, Job> JOBS = new ConcurrentHashMap<>();

    private IncidentExportJobs() {}

    static String submitExport(CommandSourceStack source, String filename,
                               AuditLookupFilters filters,
                               IncidentBundleService.RedactionProfile profile,
                               Path exportDirectory) {
        int active = ACTIVE_JOBS.incrementAndGet();
        if (active > MAX_ACTIVE_JOBS) {
            ACTIVE_JOBS.decrementAndGet();
            throw new RejectedExecutionException("the export job limit is full");
        }
        MinecraftServer server = source.getServer();
        Vec3 position = source.getPosition();
        String dimension = source.getLevel().dimension().location().toString();
        String id = Long.toString(NEXT_ID.incrementAndGet(), 36).toUpperCase(Locale.ROOT);
        Job job = new Job(id, source, server);
        JOBS.put(id, job);
        try {
            QueryDispatcher.CancellableDataHandle<IncidentBundleService.ExportResult> handle =
                    QueryDispatcher.submitCancellableData((connection, cancelled, commit) -> {
                        try {
                            return IncidentBundleService.export(
                                    connection, filters, dimension, position.x, position.y, position.z,
                                    IncidentBundleService.MAX_EVIDENCE_RECORDS, profile, exportDirectory, filename,
                                    () -> cancelled.getAsBoolean() || job.cancelled.get(), commit,
                                    completed -> reportProgress(job, completed));
                        } catch (IOException failure) {
                            throw new SQLException("failed to write the incident bundle", failure);
                        }
                    }, JOB_TIMEOUT_MS);
            job.handle = handle;
            handle.future().whenComplete((result, failure) -> completeExport(job, result, failure));
            return id;
        } catch (RuntimeException failure) {
            JOBS.remove(id);
            ACTIVE_JOBS.decrementAndGet();
            throw failure;
        }
    }

    static String submitVerification(CommandSourceStack source, String filename, Path exportDirectory) {
        int active = ACTIVE_JOBS.incrementAndGet();
        if (active > MAX_ACTIVE_JOBS) {
            ACTIVE_JOBS.decrementAndGet();
            throw new RejectedExecutionException("the export job limit is full");
        }
        MinecraftServer server = source.getServer();
        String id = Long.toString(NEXT_ID.incrementAndGet(), 36).toUpperCase(Locale.ROOT);
        Job job = new Job(id, source, server);
        JOBS.put(id, job);
        try {
            QueryDispatcher.CancellableDataHandle<IncidentBundleService.VerificationResult> handle =
                    QueryDispatcher.submitCancellableTask(cancelled ->
                            IncidentBundleService.verify(exportDirectory, filename,
                                    () -> cancelled.getAsBoolean() || job.cancelled.get()
                                            || Thread.currentThread().isInterrupted()), JOB_TIMEOUT_MS);
            job.handle = handle;
            handle.future().whenComplete((result, failure) -> completeVerification(job, result, failure));
            return id;
        } catch (RuntimeException failure) {
            JOBS.remove(id);
            ACTIVE_JOBS.decrementAndGet();
            throw failure;
        }
    }

    static boolean cancel(CommandSourceStack source, String requestedId) {
        Job job = JOBS.get(requestedId.toUpperCase(Locale.ROOT));
        if (job == null || job.handle == null) {
            source.sendFailure(Component.literal("[ItemGraph] No active export job has that ID."));
            return false;
        }
        if (!isAuthorizedToCancel(source, job.ownerId)) {
            source.sendFailure(Component.literal("[ItemGraph] Only the job owner or a level-4 operator can cancel it."));
            return false;
        }
        synchronized (job) {
            if (job.cancelled.get() || !job.handle.cancel()) {
                source.sendFailure(Component.literal("[ItemGraph] That job is already completing."));
                return false;
            }
            job.cancelled.set(true);
        }
        source.sendSuccess(() -> Component.literal("[ItemGraph] Cancellation requested for export job " + job.id + "."), false);
        return true;
    }

    static boolean isAuthorizedToCancel(CommandSourceStack source, UUID ownerId) {
        Entity actor = source.getEntity();
        UUID actorId = actor instanceof ServerPlayer player ? player.getUUID() : null;
        return source.hasPermission(4) || actorId != null && actorId.equals(ownerId);
    }

    private static void reportProgress(Job job, int completedRecords) {
        job.server.execute(() -> {
            if (!job.cancelled.get() && QueryDispatcher.canStillReport(job.source, job.server)
                    && job.source.hasPermission(2)) {
                job.source.sendSuccess(() -> Component.literal("[ItemGraph] Export job " + job.id
                        + " processed " + completedRecords + " bounded bundle records."), false);
            }
        });
    }

    private static void completeExport(Job job, IncidentBundleService.ExportResult result, Throwable failure) {
        finish(job, () -> {
            if (failure != null) {
                reportFailure(job, "Export failed", failure);
            } else {
                job.source.sendSuccess(() -> Component.literal("[ItemGraph] Export job " + job.id
                        + " wrote " + result.evidenceCount() + " observed evidence records and "
                        + result.inferredEdgeCount() + " linked inferred edges to " + result.filename()
                        + "; final SHA-256 " + result.finalHash() + "."), false);
            }
        });
    }

    private static void completeVerification(Job job, IncidentBundleService.VerificationResult result,
                                             Throwable failure) {
        finish(job, () -> {
            if (failure != null) {
                reportFailure(job, "Verification failed", failure);
            } else if (result.valid()) {
                job.source.sendSuccess(() -> Component.literal("[ItemGraph] Bundle verified: "
                        + result.recordCount() + " records; final SHA-256 " + result.finalHash() + "."), false);
            } else {
                job.source.sendFailure(Component.literal("[ItemGraph] Bundle verification failed: "
                        + result.message()));
            }
        });
    }

    private static void reportFailure(Job job, String prefix, Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        if (job.cancelled.get()) {
            job.source.sendSuccess(() -> Component.literal("[ItemGraph] Export job " + job.id + " cancelled."), false);
        } else {
            LOGGER.warn("{} for incident job {}: {}", prefix, job.id, cause.toString());
            job.source.sendFailure(Component.literal("[ItemGraph] " + prefix + ": " + safeMessage(cause)));
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "an internal error occurred; inspect the server log";
        }
        return message.length() <= 256 ? message : message.substring(0, 256);
    }

    private static void finish(Job job, Runnable delivery) {
        JOBS.remove(job.id);
        ACTIVE_JOBS.decrementAndGet();
        job.server.execute(() -> {
            if (Thread.currentThread() == job.server.getRunningThread()
                    && QueryDispatcher.canStillReport(job.source, job.server)
                    && job.source.hasPermission(2)) {
                delivery.run();
            }
        });
    }

    private static final class Job {
        private final String id;
        private final CommandSourceStack source;
        private final MinecraftServer server;
        private final UUID ownerId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile QueryDispatcher.CancellableDataHandle<?> handle;

        private Job(String id, CommandSourceStack source, MinecraftServer server) {
            this.id = id;
            this.source = source;
            this.server = server;
            this.ownerId = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        }
    }
}
