package com.itemgraph.ingest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.graph.NodeManager;
import com.itemgraph.query.AuditEventQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asynchronous, bounded worker for recording internal/supplemental observations (Phase 8/9/10).
 *
 * <p>Ensures that game-thread events (e.g. Armor Stand interactions, entity tracking, anvil events)
 * are never delayed by SQLite disk I/O. Events are enqueued as immutable data records and drained
 * by a dedicated daemon thread.
 */
public class InternalObservationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalObservationService.class);
    private static final InternalObservationService INSTANCE = new InternalObservationService();
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int RECOVERY_QUEUE_CAPACITY = QUEUE_CAPACITY * 2 + 1_000;
    private static final int DEFAULT_MAX_BATCH_SIZE = 100;
    private static final long AUDIT_RETRY_INITIAL_BACKOFF_MS = 25L;
    private static final long AUDIT_RETRY_MAX_BACKOFF_MS = 5_000L;
    private static final long AUDIT_RETRY_LOG_INTERVAL_MS = 10_000L;
    private static final Pattern SOURCE_EVENT_UUID = Pattern.compile(
            "\\\"event_id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\\"");

    public static InternalObservationService getInstance() {
        return INSTANCE;
    }

    public record InternalObservation(
            long timestampMs,
            String actionType,
            String playerUuid,
            String playerName,
            String levelName,
            double x, double y, double z,
            String targetLevelName,
            Double targetX, Double targetY, Double targetZ,
            String targetType, // e.g. "ARMOR_STAND", "PLAYER", "GROUND", "CONTAINER", "ENDER_CHEST", "UNKNOWN"
            String itemId,
            byte[] rawData,
            CanonicalItem item,
            int amount,
            String itemEntityUuid,
            Long timestampEndMs,
            Long sourceEventId,
            String ingestEventUuid
    ) {
        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, String itemId, byte[] rawData, CanonicalItem item, int amount,
                String itemEntityUuid, Long timestampEndMs, Long sourceEventId
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                    targetLevelName, targetX, targetY, targetZ, targetType, itemId, rawData,
                    item, amount, itemEntityUuid, timestampEndMs, sourceEventId, UUID.randomUUID().toString());
        }

        public InternalObservation {
            ingestEventUuid = normalizeIngestEventUuid(ingestEventUuid);
        }

        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, String itemId, byte[] rawData, int amount, String itemEntityUuid
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                 targetLevelName, targetX, targetY, targetZ, targetType, itemId, rawData, null, amount, itemEntityUuid, null, null);
        }

        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, String itemId, byte[] rawData, CanonicalItem item, int amount, String itemEntityUuid
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                    targetLevelName, targetX, targetY, targetZ, targetType, itemId, rawData, item, amount, itemEntityUuid, null, null);
        }

        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, CanonicalItem item, int amount, String itemEntityUuid
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                 targetLevelName, targetX, targetY, targetZ, targetType,
                 item != null ? item.itemId() : "minecraft:air", null, item, amount, itemEntityUuid, null, null);
        }

        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, CanonicalItem item, int amount, String itemEntityUuid, Long timestampEndMs
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                 targetLevelName, targetX, targetY, targetZ, targetType,
                 item != null ? item.itemId() : "minecraft:air", null, item, amount, itemEntityUuid, timestampEndMs, null);
        }

        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, String itemId, byte[] rawData, CanonicalItem item, int amount,
                String itemEntityUuid, Long timestampEndMs
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                    targetLevelName, targetX, targetY, targetZ, targetType, itemId, rawData,
                    item, amount, itemEntityUuid, timestampEndMs, null);
        }

    }

    /**
     * Maps a UUID event identity to the INTEGER source_event_id column used by
     * both SQLite and MySQL/MariaDB. The full UUID remains in raw_data; this
     * stable positive projection makes queued retries idempotent through the
     * existing (source_type, source_event_id) unique index. Persistence compares
     * raw payloads and probes deterministic salted projections if a distinct
     * payload ever collides with the preferred value.
     */
    public static long sourceEventIdForUuid(String eventId) {
        return sourceEventIdForUuid(eventId, 0);
    }

    private static long sourceEventIdForUuid(String eventId, int salt) {
        UUID uuid = UUID.fromString(eventId);
        long value = uuid.getMostSignificantBits() ^ Long.rotateLeft(uuid.getLeastSignificantBits(), 29);
        value ^= 0x9E3779B97F4A7C15L * salt;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        if (value == Long.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        value = Math.abs(value);
        return value == 0 ? 1 : value;
    }

    private record ExistingSourceEvent(boolean present, boolean sameEvent) {}

    /**
     * Resolves the numeric compatibility key without allowing a hash collision
     * to discard a distinct event. The producer UUID is the shared identity;
     * payloads may differ between the audit and quantity ledgers. A colliding
     * producer receives a deterministic salted key in both ledgers.
     */
    private static long collisionSafeSourceEventId(Connection conn, String table,
                                                    long preferredId, byte[] rawData) throws SQLException {
        if (rawData == null) {
            throw new SQLException("source_event_id requires raw_data for collision-safe persistence");
        }
        String eventUuid = sourceEventUuid(rawData);
        if (eventUuid == null) {
            throw new SQLException("source_event_id raw_data must contain a UUID event_id");
        }
        for (int salt = 0; salt < 64; salt++) {
            long candidate = salt == 0
                    ? preferredId
                    : sourceEventIdForUuid(eventUuid, salt);
            ExistingSourceEvent existing = findSourceEvent(conn, candidate, eventUuid);
            if (!existing.present() || existing.sameEvent()) {
                return candidate;
            }
        }
        throw new SQLException("Unable to allocate a collision-free source event ID for " + table);
    }

    private static String sourceEventUuid(byte[] rawData) {
        Matcher matcher = SOURCE_EVENT_UUID.matcher(new String(rawData, java.nio.charset.StandardCharsets.UTF_8));
        if (!matcher.find()) {
            return null;
        }
        try {
            return UUID.fromString(matcher.group(1)).toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ExistingSourceEvent findSourceEvent(Connection conn, long sourceEventId, String eventUuid)
            throws SQLException {
        // Probe both ledgers: a shared producer event must resolve to the same
        // salted ID regardless of which table is persisted first. Inspect all
        // matches before accepting so a conflicting row in either ledger cannot
        // be hidden by an earlier match in the other ledger.
        boolean found = false;
        for (String candidateTable : List.of("ig_observations", "ig_audit_events")) {
            String sql = "SELECT raw_data FROM " + candidateTable
                    + " WHERE source_type = 'ITEMGRAPH_INTERNAL' AND source_event_id = ?";
            try (PreparedStatement statement = conn.prepareStatement(sql)) {
                statement.setLong(1, sourceEventId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        found = true;
                        if (!eventUuid.equals(sourceEventUuid(result.getBytes("raw_data")))) {
                            return new ExistingSourceEvent(true, false);
                        }
                    }
                }
            }
        }
        return new ExistingSourceEvent(found, found);
    }

    public record InternalTransformation(
            long timestampMs,
            String transformationType,
            String playerUuid,
            String playerName,
            String levelName,
            double x, double y, double z,
            CanonicalItem sourceItem,
            CanonicalItem resultItem,
            int quantity,
            String details,
            String ingestEventUuid
    ) {
        public InternalTransformation(
                long timestampMs, String transformationType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                CanonicalItem sourceItem, CanonicalItem resultItem, int quantity, String details
        ) {
            this(timestampMs, transformationType, playerUuid, playerName, levelName, x, y, z,
                    sourceItem, resultItem, quantity, details, UUID.randomUUID().toString());
        }

        public InternalTransformation {
            ingestEventUuid = normalizeIngestEventUuid(ingestEventUuid);
        }
    }

    /**
     * Native audit evidence outside the quantity-flow graph. The record is
     * immutable before it enters the bounded queue so loader event handlers do
     * not expose mutable Minecraft state to the persistence worker.
     */
    public record InternalAuditEvent(
            long timestampMs,
            String eventType,
            String playerUuid,
            String playerName,
            String levelName,
            double x,
            double y,
            double z,
            String subjectId,
            String detail,
            byte[] rawData,
            Long sourceEventId,
            String ingestEventUuid,
            List<AuditEventQueryService.ExactPosition> supersessionPositions
    ) {
        public InternalAuditEvent(
                long timestampMs,
                String eventType,
                String playerUuid,
                String playerName,
                String levelName,
                double x,
                double y,
                double z,
                String subjectId,
                String detail,
                byte[] rawData
        ) {
            this(timestampMs, eventType, playerUuid, playerName, levelName, x, y, z,
                    subjectId, detail, rawData, null, UUID.randomUUID().toString(), List.of());
        }

        public InternalAuditEvent(
                long timestampMs,
                String eventType,
                String playerUuid,
                String playerName,
                String levelName,
                double x,
                double y,
                double z,
                String subjectId,
                String detail,
                byte[] rawData,
                Long sourceEventId
        ) {
            this(timestampMs, eventType, playerUuid, playerName, levelName, x, y, z,
                    subjectId, detail, rawData, sourceEventId, UUID.randomUUID().toString(), List.of());
        }

        public InternalAuditEvent(
                long timestampMs,
                String eventType,
                String playerUuid,
                String playerName,
                String levelName,
                double x,
                double y,
                double z,
                String subjectId,
                String detail,
                byte[] rawData,
                List<AuditEventQueryService.ExactPosition> supersessionPositions
        ) {
            this(timestampMs, eventType, playerUuid, playerName, levelName, x, y, z,
                    subjectId, detail, rawData, null, UUID.randomUUID().toString(), supersessionPositions);
        }

        public InternalAuditEvent {
            rawData = rawData == null ? null : rawData.clone();
            ingestEventUuid = normalizeIngestEventUuid(ingestEventUuid);
            supersessionPositions = List.copyOf(supersessionPositions == null ? List.of() : supersessionPositions);
        }

        @Override
        public byte[] rawData() {
            return rawData == null ? null : rawData.clone();
        }
    }

    private static String normalizeIngestEventUuid(String ingestEventUuid) {
        return ingestEventUuid == null ? UUID.randomUUID().toString() : UUID.fromString(ingestEventUuid).toString();
    }

    private final BlockingQueue<InternalObservation> queue = new LinkedBlockingQueue<>(RECOVERY_QUEUE_CAPACITY);
    private final Object observationQueueLock = new Object();
    private final BlockingQueue<InternalTransformation> transformationQueue = new LinkedBlockingQueue<>(RECOVERY_QUEUE_CAPACITY);
    private final BlockingQueue<InternalAuditEvent> auditEventQueue = new LinkedBlockingQueue<>(RECOVERY_QUEUE_CAPACITY);
    // Serializes queue mutations with depth sampling so a fast drain cannot hide a
    // just-accepted backlog from the queue peak metric.
    private final Object queueMutationLock = new Object();
    private final Semaphore queuedWorkSignals = new Semaphore(0);
    private final AtomicBoolean queuedWorkSignalPending = new AtomicBoolean(false);
    private final AtomicBoolean queueFlushDue = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger serverTicksSinceFlush =
            new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdownSpoolComplete = new AtomicBoolean(false);
    private final Object submissionLifecycleLock = new Object();
    private final Object evidenceSpoolLock = new Object();
    private final Map<String, Object> recoveryEvents = new LinkedHashMap<>();
    private volatile PendingEvidenceSpool.Snapshot inFlightSnapshot =
            new PendingEvidenceSpool.Snapshot(List.of(), List.of(), List.of());
    private volatile java.nio.file.Path evidenceSpoolPath;
    private volatile java.nio.file.Path evidenceSpoolPathOverride;
    private volatile WorkerShutdown.SpoolWriteHandle pendingSpoolWriter;
    private volatile boolean recoveryLoadFailed;
    private final java.util.concurrent.atomic.AtomicBoolean restartRetryRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean restartRetryScheduled = new java.util.concurrent.atomic.AtomicBoolean();
    // Pre-start submissions are retained for lifecycle callbacks; stop closes admission.
    private volatile boolean acceptingSubmissions;
    private final java.util.concurrent.atomic.AtomicLong totalEnqueued = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalPersisted = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalDropped = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalPersistenceOutcomeUnknown = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalTransformations = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalAuditEvents = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalDatabaseHeartbeats = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalDatabaseHeartbeatFailures = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger pendingTransformations = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile long auditRetryBackoffMs = AUDIT_RETRY_INITIAL_BACKOFF_MS;
    private volatile long lastAuditRetryLogMs;
    private volatile long transformationRetryBackoffMs = AUDIT_RETRY_INITIAL_BACKOFF_MS;
    private volatile long lastTransformationRetryLogMs;
    private volatile long observationRetryBackoffMs = AUDIT_RETRY_INITIAL_BACKOFF_MS;
    private volatile long lastObservationRetryLogMs;
    private volatile int queuePollIntervalMs = 250;
    private volatile int queueFrequencyTicks = 20;
    private volatile int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private volatile int databaseHeartbeatIntervalMs = 30_000;
    private volatile boolean captureEnabled = true;
    private volatile Thread workerThread;

    private InternalObservationService() {}

    /** Applies validated startup settings before the worker begins. */
    public synchronized void configureOperations(int pollIntervalMs, int configuredQueueFrequencyTicks,
                                                 int configuredMaxBatchSize,
                                                 int configuredDatabaseHeartbeatIntervalMs,
                                                 boolean configuredCaptureEnabled) {
        if (running.get()) {
            throw new IllegalStateException("Cannot change ItemGraph operations settings while the worker is running; restart the server");
        }
        if (pollIntervalMs < 10 || pollIntervalMs > 5_000) {
            throw new IllegalArgumentException("ingestion.poll_interval_ms must be in [10,5000]");
        }
        if (configuredQueueFrequencyTicks < 1 || configuredQueueFrequencyTicks > 100) {
            throw new IllegalArgumentException("ingestion.queue_frequency_ticks must be in [1,100]");
        }
        if (configuredMaxBatchSize < 1 || configuredMaxBatchSize > 1_000) {
            throw new IllegalArgumentException("ingestion.max_batch_size must be in [1,1000]");
        }
        if (configuredDatabaseHeartbeatIntervalMs < 1_000 || configuredDatabaseHeartbeatIntervalMs > 3_600_000) {
            throw new IllegalArgumentException("operations.database_heartbeat_interval_ms must be in [1000,3600000]");
        }
        queuePollIntervalMs = pollIntervalMs;
        queueFrequencyTicks = configuredQueueFrequencyTicks;
        maxBatchSize = configuredMaxBatchSize;
        databaseHeartbeatIntervalMs = configuredDatabaseHeartbeatIntervalMs;
        captureEnabled = configuredCaptureEnabled;
    }

    /** Retains source compatibility while using the production default flush cadence. */
    public synchronized void configureOperations(int pollIntervalMs, int configuredMaxBatchSize,
                                                 int configuredDatabaseHeartbeatIntervalMs,
                                                 boolean configuredCaptureEnabled) {
        configureOperations(pollIntervalMs, 20, configuredMaxBatchSize,
                configuredDatabaseHeartbeatIntervalMs, configuredCaptureEnabled);
    }

    public int getQueuePollIntervalMs() {
        return queuePollIntervalMs;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public int getQueueFrequencyTicks() {
        return queueFrequencyTicks;
    }

    public int getDatabaseHeartbeatIntervalMs() {
        return databaseHeartbeatIntervalMs;
    }

    public long getTotalDatabaseHeartbeats() {
        return totalDatabaseHeartbeats.get();
    }

    public long getTotalDatabaseHeartbeatFailures() {
        return totalDatabaseHeartbeatFailures.get();
    }

    public boolean isCaptureEnabled() {
        return captureEnabled;
    }

    public void start() {
        synchronized (submissionLifecycleLock) {
            if (running.get()) {
                return;
            }
            if (hasActiveShutdownWorker()) {
                acceptingSubmissions = false;
                restartRetryRequested.set(true);
                scheduleStartRetry();
                LOGGER.error("Cannot start ItemGraph evidence capture until the previous database/recovery workers finish; a daemon retry will restore evidence and start capture automatically when they exit");
                return;
            }
            restartRetryRequested.set(false);
            startAfterShutdownWorkersLocked();
        }
    }

    private boolean hasActiveShutdownWorker() {
        return (workerThread != null && workerThread.isAlive())
                || (pendingSpoolWriter != null && !pendingSpoolWriter.isComplete());
    }

    private void startAfterShutdownWorkersLocked() {
        acceptingSubmissions = true;
        pendingSpoolWriter = null;
        recoveryLoadFailed = true;
        java.nio.file.Path recoveryPath = currentEvidenceSpoolPath();
        // Keep the exact recovery target available to stop() even if startup
        // recovery is still blocked in filesystem I/O when shutdown begins.
        evidenceSpoolPath = recoveryPath;
        shutdownSpoolComplete.set(false);
        OperationalMetrics.getInstance().reset();
        if (java.nio.file.Files.exists(recoveryPath)
                || java.nio.file.Files.exists(PendingEvidenceSpool.overflowPath(recoveryPath))) {
            startWorker(recoveryPath);
        } else {
            // Normal starts have no recovery I/O to perform. Avoid creating a
            // startup race between the loader lifecycle and its worker.
            recoveryLoadFailed = false;
            startWorker();
        }
    }

    private void scheduleStartRetry() {
        if (!restartRetryScheduled.compareAndSet(false, true)) {
            return;
        }
        Thread retry = new Thread(() -> {
            try {
                while (restartRetryRequested.get() && hasActiveShutdownWorker()) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        restartRetryRequested.set(false);
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                synchronized (submissionLifecycleLock) {
                    if (restartRetryRequested.get() && !running.get() && !hasActiveShutdownWorker()) {
                        restartRetryRequested.set(false);
                        startAfterShutdownWorkersLocked();
                    }
                }
            } finally {
                restartRetryScheduled.set(false);
                if (restartRetryRequested.get() && !hasActiveShutdownWorker()) {
                    scheduleStartRetry();
                }
            }
        }, "ItemGraph-Recovery-Restart");
        retry.setDaemon(true);
        retry.start();
    }

    private void startWorker() {
        startWorker(null);
    }

    private void startWorker(java.nio.file.Path recoveryPath) {
        running.set(true);
        workerThread = new Thread(() -> {
            if (recoveryPath != null) {
                try {
                    restorePendingEvidence(recoveryPath);
                    recoveryLoadFailed = false;
                } catch (java.io.IOException recoveryFailure) {
                    recoveryLoadFailed = true;
                    running.set(false);
                    LOGGER.error("Cannot flush ItemGraph evidence because pending recovery could not be loaded; original recovery file is preserved", recoveryFailure);
                    try {
                        PendingEvidenceSpool.Snapshot queued = closeAdmissionAndSnapshotPendingEvidence();
                        if (queued.size() > 0) {
                            int preserved = writeOverflowEvidenceSpool(recoveryPath, queued);
                            shutdownSpoolComplete.set(true);
                            clearQueuesAfterSpoolHandoff();
                            LOGGER.error("ItemGraph preserved {} accepted queued records in the separate overflow recovery file {} because primary recovery failed", preserved, PendingEvidenceSpool.overflowPath(recoveryPath));
                        }
                    } catch (java.io.IOException overflowFailure) {
                        int atRisk = snapshotPendingEvidence().size();
                        totalDropped.addAndGet(atRisk);
                        LOGGER.error("CRITICAL: ItemGraph could not preserve {} accepted queued records because primary recovery failed and overflow recovery writing failed; the original primary file remains untouched", atRisk, overflowFailure);
                    }
                    return;
                }
            }
            drainQueueSafely();
        }, "ItemGraph-Internal-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
        LOGGER.info("ItemGraph internal observation service started.");
    }

    public void stop() {
        synchronized (submissionLifecycleLock) {
            restartRetryRequested.set(false);
            // Close admission atomically with every producer enqueue before deciding
            // whether a worker is needed for the final drain. JDBC remains worker-owned.
            acceptingSubmissions = false;
            if ((workerThread == null || !workerThread.isAlive()) && hasQueuedWork()) {
                java.nio.file.Path currentSpoolPath = currentEvidenceSpoolPath();
                if (!currentSpoolPath.equals(evidenceSpoolPath) || recoveryLoadFailed) {
                    workerThread = null;
                    startWorker(currentSpoolPath);
                }
                queueFlushDue.set(true);
                signalQueuedWork();
            }
            running.set(false);
            if (workerThread != null) {
                queueFlushDue.set(true);
                signalQueuedWork();
                Thread stopping = workerThread;
                boolean terminated = WorkerShutdown.stop(stopping,
                        WorkerShutdown.GRACEFUL_WAIT_MS, WorkerShutdown.INTERRUPTED_WAIT_MS);
                if (!terminated) {
                    PendingEvidenceSpool.Snapshot shutdownSnapshot = snapshotPendingEvidence();
                    java.nio.file.Path shutdownSpoolPath = evidenceSpoolPath;
                    java.util.concurrent.atomic.AtomicBoolean lossCounted = new java.util.concurrent.atomic.AtomicBoolean();
                    WorkerShutdown.SpoolWriteHandle spoolWriter = WorkerShutdown.startSpoolWriter(() -> {
                        try {
                            int spooled = writePendingEvidenceSpool(shutdownSnapshot, shutdownSpoolPath);
                            if (shutdownSpoolComplete.get()) clearQueuesAfterSpoolHandoff();
                            return spooled;
                        } catch (Exception spoolFailure) {
                            if (lossCounted.compareAndSet(false, true)) totalDropped.addAndGet(shutdownSnapshot.size());
                            LOGGER.error("CRITICAL: ItemGraph recovery-file writer failed at its captured target {}", shutdownSpoolPath, spoolFailure);
                            throw spoolFailure;
                        }
                    });
                    pendingSpoolWriter = spoolWriter;
                    WorkerShutdown.SpoolWriteResult spoolResult = spoolWriter.await(WorkerShutdown.EVIDENCE_SPOOL_WAIT_MS);
                    if (!spoolResult.completed() || spoolResult.failure() != null) {
                        int outstanding = shutdownSnapshot.size();
                        if (lossCounted.compareAndSet(false, true)) totalDropped.addAndGet(outstanding);
                        shutdownSpoolComplete.set(true);
                        clearQueuesAfterSpoolHandoff();
                        LOGGER.error("CRITICAL: ItemGraph evidence worker did not terminate within {} ms and the recovery-file write did not finish within {} ms; {} accepted records remain at risk. Recovery file target: {}. A late daemon write may still complete if the filesystem returns.",
                                WorkerShutdown.GRACEFUL_WAIT_MS + WorkerShutdown.INTERRUPTED_WAIT_MS,
                                WorkerShutdown.EVIDENCE_SPOOL_WAIT_MS, outstanding, evidenceSpoolPath,
                                spoolResult.failure());
                    } else {
                        shutdownSpoolComplete.set(true);
                        clearQueuesAfterSpoolHandoff();
                        LOGGER.error("ItemGraph evidence worker did not terminate within {} ms; {} outstanding records were saved for idempotent recovery at {}. The worker remains tracked and capture will stay disabled until it exits.",
                                WorkerShutdown.GRACEFUL_WAIT_MS + WorkerShutdown.INTERRUPTED_WAIT_MS,
                                spoolResult.written(), evidenceSpoolPath);
                    }
                    return;
                }
                workerThread = null;
                inFlightSnapshot = new PendingEvidenceSpool.Snapshot(List.of(), List.of(), List.of());
                queuedWorkSignals.drainPermits();
                queuedWorkSignalPending.set(false);
                queueFlushDue.set(false);
                serverTicksSinceFlush.set(0);
                return;
            }
            queuedWorkSignals.drainPermits();
            queuedWorkSignalPending.set(false);
            queueFlushDue.set(false);
            serverTicksSinceFlush.set(0);
        }
    }

    private void restorePendingEvidence() throws java.io.IOException {
        restorePendingEvidence(currentEvidenceSpoolPath());
    }

    private void restorePendingEvidence(java.nio.file.Path path) throws java.io.IOException {
        synchronized (evidenceSpoolLock) {
            PendingEvidenceSpool.Snapshot snapshot = PendingEvidenceSpool.merge(
                    PendingEvidenceSpool.read(path),
                    PendingEvidenceSpool.read(PendingEvidenceSpool.overflowPath(path)));
            if (snapshot.size() == 0) {
                if (!path.equals(evidenceSpoolPath)) recoveryEvents.clear();
                evidenceSpoolPath = path;
                return;
            }
            synchronized (queueMutationLock) {
                if (snapshot.observations().size() + queue.size() > RECOVERY_QUEUE_CAPACITY
                        || snapshot.transformations().size() + transformationQueue.size() > RECOVERY_QUEUE_CAPACITY
                        || snapshot.auditEvents().size() + auditEventQueue.size() > RECOVERY_QUEUE_CAPACITY) {
                    throw new java.io.IOException("pending evidence exceeds the configured recovery queue capacity; original file was preserved");
                }
                if (!path.equals(evidenceSpoolPath)) recoveryEvents.clear();
                evidenceSpoolPath = path;
                // Events accepted while recovery I/O ran are newer than every
                // record in the saved snapshot. Put recovered records first so
                // worker persistence preserves temporal order within each ledger.
                List<InternalObservation> newObservations = new ArrayList<>(queue);
                List<InternalTransformation> newTransformations = new ArrayList<>(transformationQueue);
                List<InternalAuditEvent> newAuditEvents = new ArrayList<>(auditEventQueue);
                queue.clear();
                transformationQueue.clear();
                auditEventQueue.clear();
                snapshot.observations().forEach(event -> {
                    queue.add(event);
                    recoveryEvents.put("observation:" + event.ingestEventUuid(), event);
                });
                snapshot.transformations().forEach(event -> {
                    transformationQueue.add(event);
                    recoveryEvents.put("transformation:" + event.ingestEventUuid(), event);
                });
                snapshot.auditEvents().forEach(event -> {
                    auditEventQueue.add(event);
                    recoveryEvents.put("audit:" + event.ingestEventUuid(), event);
                });
                queue.addAll(newObservations);
                transformationQueue.addAll(newTransformations);
                auditEventQueue.addAll(newAuditEvents);
            }
            LOGGER.warn("Restored {} pending ItemGraph evidence records from {}", snapshot.size(), path);
            queueFlushDue.set(true);
            signalQueuedWork();
        }
    }

    private int writeOverflowEvidenceSpool(java.nio.file.Path primaryPath,
                                           PendingEvidenceSpool.Snapshot snapshot) throws java.io.IOException {
        synchronized (evidenceSpoolLock) {
            java.nio.file.Path overflowPath = PendingEvidenceSpool.overflowPath(primaryPath);
            PendingEvidenceSpool.Snapshot complete = PendingEvidenceSpool.merge(
                    PendingEvidenceSpool.read(overflowPath), snapshot);
            PendingEvidenceSpool.write(overflowPath, complete);
            return complete.size();
        }
    }

    void setEvidenceSpoolPathForTests(java.nio.file.Path path) {
        evidenceSpoolPathOverride = path;
    }

    private java.nio.file.Path currentEvidenceSpoolPath() {
        java.nio.file.Path override = evidenceSpoolPathOverride;
        return override != null ? override : PendingEvidenceSpool.pathFor(DatabaseManager.getInstance().getSettings());
    }

    private PendingEvidenceSpool.Snapshot snapshotPendingEvidence() {
        synchronized (queueMutationLock) {
            return snapshotPendingEvidenceLocked();
        }
    }

    private PendingEvidenceSpool.Snapshot closeAdmissionAndSnapshotPendingEvidence() {
        synchronized (queueMutationLock) {
            acceptingSubmissions = false;
            return snapshotPendingEvidenceLocked();
        }
    }

    private PendingEvidenceSpool.Snapshot snapshotPendingEvidenceLocked() {
        List<InternalObservation> observations = new ArrayList<>(queue);
        List<InternalTransformation> transformations = new ArrayList<>(transformationQueue);
        List<InternalAuditEvent> auditEvents = new ArrayList<>(auditEventQueue);
        observations.addAll(inFlightSnapshot.observations());
        transformations.addAll(inFlightSnapshot.transformations());
        auditEvents.addAll(inFlightSnapshot.auditEvents());
        return new PendingEvidenceSpool.Snapshot(observations, transformations, auditEvents);
    }

    private int writePendingEvidenceSpool() throws java.io.IOException {
        return writePendingEvidenceSpool(snapshotPendingEvidence(), evidenceSpoolPath);
    }

    private int writePendingEvidenceSpool(PendingEvidenceSpool.Snapshot snapshot,
                                          java.nio.file.Path targetPath) throws java.io.IOException {
        synchronized (evidenceSpoolLock) {
            for (InternalObservation event : snapshot.observations()) recoveryEvents.put("observation:" + event.ingestEventUuid(), event);
            for (InternalTransformation event : snapshot.transformations()) recoveryEvents.put("transformation:" + event.ingestEventUuid(), event);
            for (InternalAuditEvent event : snapshot.auditEvents()) recoveryEvents.put("audit:" + event.ingestEventUuid(), event);
            PendingEvidenceSpool.Snapshot complete = recoverySnapshot();
            PendingEvidenceSpool.write(targetPath, complete);
            return complete.size();
        }
    }

    private void clearQueuesAfterSpoolHandoff() {
        synchronized (queueMutationLock) {
            queue.clear();
            transformationQueue.clear();
            auditEventQueue.clear();
            pendingTransformations.set(0);
            inFlightSnapshot = new PendingEvidenceSpool.Snapshot(List.of(), List.of(), List.of());
            OperationalMetrics.getInstance().recordQueueDepth(0);
        }
        queueFlushDue.set(false);
        queuedWorkSignals.drainPermits();
        queuedWorkSignalPending.set(false);
    }

    private void acknowledgeRecovered(List<String> eventIds) {
        synchronized (evidenceSpoolLock) {
            eventIds.forEach(recoveryEvents::remove);
            if (recoveryEvents.isEmpty() && evidenceSpoolPath != null) {
                try {
                    PendingEvidenceSpool.delete(evidenceSpoolPath);
                    PendingEvidenceSpool.delete(PendingEvidenceSpool.overflowPath(evidenceSpoolPath));
                } catch (java.io.IOException cleanupFailure) {
                    LOGGER.warn("All recovered ItemGraph evidence is durable, but a recovery file could not be removed; it will be replayed idempotently on next startup", cleanupFailure);
                }
            }
        }
    }

    private PendingEvidenceSpool.Snapshot recoverySnapshot() {
        List<InternalObservation> observations = new ArrayList<>();
        List<InternalTransformation> transformations = new ArrayList<>();
        List<InternalAuditEvent> auditEvents = new ArrayList<>();
        for (Object event : recoveryEvents.values()) {
            if (event instanceof InternalObservation observation) observations.add(observation);
            else if (event instanceof InternalTransformation transformation) transformations.add(transformation);
            else if (event instanceof InternalAuditEvent auditEvent) auditEvents.add(auditEvent);
        }
        return new PendingEvidenceSpool.Snapshot(observations, transformations, auditEvents);
    }

    private boolean hasQueuedWork() {
        return !queue.isEmpty() || !transformationQueue.isEmpty() || !auditEventQueue.isEmpty();
    }

    public boolean submit(InternalObservation obs) {
        if (obs == null) {
            return false;
        }
        return submitAll(List.of(obs));
    }

    /**
     * Enqueues a related set of observations all-or-none. Producers share one
     * lock, and the worker only removes queue entries, so a capacity check under
     * this lock guarantees each following offer succeeds without a partial batch.
     */
    public boolean submitAll(List<InternalObservation> observations) {
        long enqueueStarted = System.nanoTime();
        boolean accepted = false;
        try {
            accepted = submitAllNow(observations);
            return accepted;
        } finally {
            OperationalMetrics.getInstance().recordEnqueue(System.nanoTime() - enqueueStarted, accepted);
        }
    }

    private boolean submitAllNow(List<InternalObservation> observations) {
        synchronized (submissionLifecycleLock) {
            if (captureEnabled && !acceptingSubmissions) {
                int rejected = observations == null ? 1 : observations.size();
                recordPostStopRejection(rejected);
                return false;
            }
            if (observations == null || observations.stream().anyMatch(Objects::isNull)) {
                return false;
            }
            if (!captureEnabled) {
                return true;
            }
            if (observations.isEmpty()) {
                return true;
            }
            synchronized (observationQueueLock) {
                synchronized (queueMutationLock) {
                    if (!acceptingSubmissions) {
                        recordPostStopRejection(observations.size());
                        return false;
                    }
                    if (queue.size() + observations.size() > QUEUE_CAPACITY) {
                        long dropped = totalDropped.addAndGet(observations.size());
                        OperationalMetrics.getInstance().recordQueueRejection(observations.size());
                        if (dropped == observations.size() || dropped % 1000 < observations.size()) {
                            LOGGER.warn("Internal observation queue is full — rejected {} observation batch ({} dropped total; evidence loss)",
                                    observations.size(), dropped);
                        }
                        return false;
                    }
                    for (InternalObservation observation : observations) {
                        // Capacity cannot shrink while producers are locked out; the worker
                        // only frees slots, so this batch cannot be partially accepted.
                        if (!queue.offer(observation)) {
                            throw new IllegalStateException("Observation queue capacity changed during atomic batch enqueue");
                        }
                    }
                    totalEnqueued.addAndGet(observations.size());
                    OperationalMetrics.getInstance().recordQueueDepth(getQueueSize());
                }
                signalQueuedWork();
                return true;
            }
        }
    }

    public boolean submitTransformation(InternalTransformation trans) {
        long enqueueStarted = System.nanoTime();
        boolean accepted = false;
        try {
            accepted = submitTransformationNow(trans);
            return accepted;
        } finally {
            OperationalMetrics.getInstance().recordEnqueue(System.nanoTime() - enqueueStarted, accepted);
        }
    }

    private boolean submitTransformationNow(InternalTransformation trans) {
        synchronized (submissionLifecycleLock) {
            if (captureEnabled && !acceptingSubmissions) {
                int rejected = 1;
                recordPostStopRejection(rejected);
                return false;
            }
            if (trans == null) {
                return false;
            }
            if (!captureEnabled) {
                return true;
            }
            boolean ok;
            synchronized (queueMutationLock) {
                if (!acceptingSubmissions) {
                    recordPostStopRejection(1);
                    return false;
                }
                pendingTransformations.incrementAndGet();
                ok = transformationQueue.size() < QUEUE_CAPACITY && transformationQueue.offer(trans);
                if (ok) {
                    totalEnqueued.incrementAndGet();
                    OperationalMetrics.getInstance().recordQueueDepth(getQueueSize());
                } else {
                    pendingTransformations.decrementAndGet();
                    long dropped = totalDropped.incrementAndGet();
                    OperationalMetrics.getInstance().recordQueueRejection(1);
                    if (dropped == 1 || dropped % 1000 == 0) {
                        LOGGER.warn("Transformation queue is full — dropped {} ({} dropped total; evidence loss)",
                                trans.transformationType(), dropped);
                    }
                }
            }
            if (ok) {
                signalQueuedWork();
            }
            return ok;
        }
    }

    public boolean submitAuditEvent(InternalAuditEvent event) {
        long enqueueStarted = System.nanoTime();
        boolean accepted = false;
        try {
            accepted = submitAuditEventNow(event);
            return accepted;
        } finally {
            OperationalMetrics.getInstance().recordEnqueue(System.nanoTime() - enqueueStarted, accepted);
        }
    }

    private boolean submitAuditEventNow(InternalAuditEvent event) {
        synchronized (submissionLifecycleLock) {
            if (captureEnabled && !acceptingSubmissions) {
                int rejected = 1;
                recordPostStopRejection(rejected);
                return false;
            }
            if (event == null) {
                return false;
            }
            if (!captureEnabled) {
                return true;
            }
            boolean ok;
            synchronized (queueMutationLock) {
                if (!acceptingSubmissions) {
                    recordPostStopRejection(1);
                    return false;
                }
                ok = auditEventQueue.size() < QUEUE_CAPACITY && auditEventQueue.offer(event);
                if (ok) {
                    totalEnqueued.incrementAndGet();
                    OperationalMetrics.getInstance().recordQueueDepth(getQueueSize());
                } else {
                    long dropped = totalDropped.incrementAndGet();
                    OperationalMetrics.getInstance().recordQueueRejection(1);
                    if (dropped == 1 || dropped % 1000 == 0) {
                        LOGGER.warn("Native audit event queue is full — dropped {} ({} dropped total; evidence loss)",
                                event.eventType(), dropped);
                    }
                }
            }
            if (ok) {
                signalQueuedWork();
            }
            return ok;
        }
    }

    public int getQueueSize() {
        return queue.size() + transformationQueue.size() + auditEventQueue.size();
    }

    public int getObservationQueueSize() {
        return queue.size();
    }

    public int getTransformationQueueSize() {
        return transformationQueue.size();
    }

    public int getAuditEventQueueSize() {
        return auditEventQueue.size();
    }

    public int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }

    public long getTotalEnqueued() {
        return totalEnqueued.get();
    }

    public long getTotalPersisted() {
        return totalPersisted.get();
    }

    public long getTotalDropped() {
        return totalDropped.get();
    }

    /** Number of records whose shutdown write failed with a commit outcome that cannot be determined. */
    public long getTotalPersistenceOutcomeUnknown() {
        return totalPersistenceOutcomeUnknown.get();
    }

    public long getTotalTransformations() {
        return totalTransformations.get();
    }

    public long getTotalAuditEvents() {
        return totalAuditEvents.get();
    }

    /** Resets queue state for a new lifecycle or test fixture and reopens admission. */
    public synchronized void clear() {
        synchronized (submissionLifecycleLock) {
            acceptingSubmissions = true;
            recoveryLoadFailed = false;
            shutdownSpoolComplete.set(false);
            synchronized (queueMutationLock) {
                queue.clear();
                transformationQueue.clear();
                auditEventQueue.clear();
                inFlightSnapshot = new PendingEvidenceSpool.Snapshot(List.of(), List.of(), List.of());
            }
            synchronized (evidenceSpoolLock) {
                recoveryEvents.clear();
            }
            pendingTransformations.set(0);
            totalEnqueued.set(0);
            totalPersisted.set(0);
            totalDropped.set(0);
            totalPersistenceOutcomeUnknown.set(0);
            totalTransformations.set(0);
            totalAuditEvents.set(0);
            totalDatabaseHeartbeats.set(0);
            totalDatabaseHeartbeatFailures.set(0);
            OperationalMetrics.getInstance().reset();
            queuedWorkSignals.drainPermits();
            queuedWorkSignalPending.set(false);
            queueFlushDue.set(false);
            serverTicksSinceFlush.set(0);
            resetAuditRetryState();
            resetObservationRetryState();
        }
    }

    private void drainQueueSafely() {
        List<InternalObservation> obsBatch = new ArrayList<>(maxBatchSize);
        long nextDatabaseHeartbeatNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(databaseHeartbeatIntervalMs);

        while ((running.get() || hasQueuedWork()) && !shutdownSpoolComplete.get()) {
            try {
                long nanosUntilHeartbeat = nextDatabaseHeartbeatNanos - System.nanoTime();
                long pollWaitMs = running.get()
                        ? Math.min(queuePollIntervalMs,
                                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanosUntilHeartbeat)))
                        : 0L;
                boolean signaled = queuedWorkSignals.tryAcquire(pollWaitMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if (signaled || !running.get()) {
                    queuedWorkSignalPending.set(false);

                    // Drain shutdown backlog on the worker in configured-size batches.
                    if (!running.get()) {
                        queueFlushDue.set(true);
                    }
                    if (queueFlushDue.getAndSet(false)) {
                        List<InternalTransformation> transBatch = new ArrayList<>(maxBatchSize);
                        List<InternalAuditEvent> auditBatch = new ArrayList<>(maxBatchSize);
                        synchronized (queueMutationLock) {
                            queue.drainTo(obsBatch, maxBatchSize);
                            transformationQueue.drainTo(transBatch, maxBatchSize);
                            auditEventQueue.drainTo(auditBatch, maxBatchSize);
                            inFlightSnapshot = new PendingEvidenceSpool.Snapshot(obsBatch, transBatch, auditBatch);
                        }

                        if (!obsBatch.isEmpty()) {
                            try {
                                persistMeasured(obsBatch.size(), () -> persistBatch(obsBatch));
                                resetObservationRetryState();
                                totalPersisted.addAndGet(obsBatch.size());
                                acknowledgeRecovered(observationIds(obsBatch));
                            } catch (Exception failure) {
                                if (running.get()) {
                                    requeueObservationBatch(obsBatch, failure);
                                } else {
                                    boolean spooled = preserveFailedShutdownBatch("observations", failure);
                                    reportShutdownPersistenceFailure("observations", obsBatch.size(), failure, spooled);
                                    handoffShutdownRecords(spooled, failure);
                                }
                            } finally {
                                completeInFlightObservations();
                            }
                            obsBatch.clear();
                            if (shutdownSpoolComplete.get()) return;
                        }

                        if (!transBatch.isEmpty()) {
                            try {
                                persistMeasured(transBatch.size(), () -> persistTransformations(transBatch));
                                resetTransformationRetryState();
                                totalTransformations.addAndGet(transBatch.size());
                                totalPersisted.addAndGet(transBatch.size());
                                pendingTransformations.addAndGet(-transBatch.size());
                                acknowledgeRecovered(transformationIds(transBatch));
                            } catch (Exception failure) {
                                if (running.get()) {
                                    requeueTransformationBatch(transBatch, failure);
                                } else {
                                    pendingTransformations.addAndGet(-transBatch.size());
                                    boolean spooled = preserveFailedShutdownBatch("transformations", failure);
                                    reportShutdownPersistenceFailure("transformations", transBatch.size(), failure, spooled);
                                    handoffShutdownRecords(spooled, failure);
                                }
                            } finally {
                                completeInFlightTransformations();
                                transBatch.clear();
                            }
                            if (shutdownSpoolComplete.get()) return;
                        }

                        if (!auditBatch.isEmpty()) {
                            try {
                                persistMeasured(auditBatch.size(), () -> persistAuditEvents(auditBatch));
                                resetAuditRetryState();
                                totalAuditEvents.addAndGet(auditBatch.size());
                                totalPersisted.addAndGet(auditBatch.size());
                                acknowledgeRecovered(auditEventIds(auditBatch));
                            } catch (Exception e) {
                                if (running.get()) {
                                    requeueAuditBatch(auditBatch, e);
                                } else {
                                    boolean spooled = preserveFailedShutdownBatch("audit events", e);
                                    reportShutdownPersistenceFailure("audit events", auditBatch.size(), e, spooled);
                                    handoffShutdownRecords(spooled, e);
                                }
                            }
                            completeInFlightAuditEvents();
                            if (shutdownSpoolComplete.get()) return;
                        }

                        synchronized (queueMutationLock) {
                            inFlightSnapshot = new PendingEvidenceSpool.Snapshot(List.of(), List.of(), List.of());
                        }

                        // A pass is intentionally bounded per queue. Keep the worker running
                        // immediately while any bounded queue still has a backlog.
                        if (!queue.isEmpty() || !transformationQueue.isEmpty() || !auditEventQueue.isEmpty()) {
                            queueFlushDue.set(true);
                            signalQueuedWork();
                        }
                    }
                }

                long nowNanos = System.nanoTime();
                if (running.get() && nowNanos >= nextDatabaseHeartbeatNanos) {
                    runDatabaseHeartbeat();
                    nextDatabaseHeartbeatNanos = System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(databaseHeartbeatIntervalMs);
                }
            } catch (InterruptedException e) {
                if (running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
                queueFlushDue.set(true);
            } catch (Exception e) {
                LOGGER.error("Error persisting internal observations/transformations batch", e);
            }
        }
    }

    private static List<String> observationIds(List<InternalObservation> events) {
        return events.stream().map(event -> "observation:" + event.ingestEventUuid()).toList();
    }

    private static List<String> transformationIds(List<InternalTransformation> events) {
        return events.stream().map(event -> "transformation:" + event.ingestEventUuid()).toList();
    }

    private static List<String> auditEventIds(List<InternalAuditEvent> events) {
        return events.stream().map(event -> "audit:" + event.ingestEventUuid()).toList();
    }

    private boolean preserveFailedShutdownBatch(String batchType, Exception failure) {
        try {
            int preserved = writePendingEvidenceSpool();
            if (preserved == 0) {
                LOGGER.error("ItemGraph could not confirm shutdown persistence for {} but no pending records were available to spool", batchType, failure);
            }
            return preserved > 0;
        } catch (java.io.IOException spoolFailure) {
            LOGGER.error("CRITICAL: ItemGraph failed to write its shutdown recovery file for {} after a database error", batchType, spoolFailure);
            return false;
        }
    }

    private void reportShutdownPersistenceFailure(String batchType, int count, Exception failure, boolean spooled) {
        if (hasUnknownCommitOutcome(failure)) {
            long unknown = totalPersistenceOutcomeUnknown.addAndGet(count);
            LOGGER.error("Shutdown could not confirm persistence for {} {} ({} records with unknown commit outcome); recovery file saved={}",
                    count, batchType, unknown, spooled, failure);
        } else {
            LOGGER.error("Could not persist {} {} during shutdown; recovery file saved={}",
                    count, batchType, spooled, failure);
        }
    }

    private void handoffShutdownRecords(boolean spooled, Exception failure) {
        if (!spooled) {
            int outstanding = snapshotPendingEvidence().size();
            totalDropped.addAndGet(outstanding);
            LOGGER.error("{} accepted evidence records could not be persisted or spooled during shutdown",
                    outstanding, failure);
        }
        shutdownSpoolComplete.set(true);
        clearQueuesAfterSpoolHandoff();
    }

    private void completeInFlightObservations() {
        synchronized (queueMutationLock) {
            inFlightSnapshot = new PendingEvidenceSpool.Snapshot(List.of(),
                    inFlightSnapshot.transformations(), inFlightSnapshot.auditEvents());
        }
    }

    private void completeInFlightTransformations() {
        synchronized (queueMutationLock) {
            inFlightSnapshot = new PendingEvidenceSpool.Snapshot(inFlightSnapshot.observations(),
                    List.of(), inFlightSnapshot.auditEvents());
        }
    }

    private void completeInFlightAuditEvents() {
        synchronized (queueMutationLock) {
            inFlightSnapshot = new PendingEvidenceSpool.Snapshot(inFlightSnapshot.observations(),
                    inFlightSnapshot.transformations(), List.of());
        }
    }

    /**
     * Called once from each loader's server-end-tick callback. Queue producers only
     * wake this worker; database batches are released on the configured server-tick
     * cadence, matching GriefLogger's queueFrequency control without doing JDBC work
     * on the server thread.
     */
    public void onServerTick() {
        if (!running.get()) {
            return;
        }
        if (serverTicksSinceFlush.incrementAndGet() >= queueFrequencyTicks) {
            serverTicksSinceFlush.set(0);
            queueFlushDue.set(true);
            signalQueuedWork();
        }
    }

    /** Coalesce producer wakeups while preserving a signal for enqueues racing a drain. */
    private void signalQueuedWork() {
        if (queuedWorkSignalPending.compareAndSet(false, true)) {
            queuedWorkSignals.release();
        }
    }

    private void runDatabaseHeartbeat() {
        DatabaseManager database = DatabaseManager.getInstance();
        com.itemgraph.db.DatabaseSettings settings = database.getSettings();
        if (settings == null || !settings.isNetworkBackend()) {
            return;
        }
        try {
            if (database.validateNetworkConnection(5)) {
                totalDatabaseHeartbeats.incrementAndGet();
            } else {
                totalDatabaseHeartbeatFailures.incrementAndGet();
                LOGGER.warn("ItemGraph network database keepalive reported an invalid connection; the next heartbeat will retry");
            }
        } catch (SQLException heartbeatFailure) {
            totalDatabaseHeartbeatFailures.incrementAndGet();
            LOGGER.warn("ItemGraph network database keepalive failed; the next heartbeat will retry",
                    heartbeatFailure);
        }
    }

    @FunctionalInterface
    private interface PersistenceOperation {
        void run() throws Exception;
    }

    private enum PersistenceOutcome {
        NOT_COMMITTED,
        COMMIT_UNKNOWN,
        COMMITTED
    }

    private static final class PersistenceOutcomeException extends SQLException {
        private final PersistenceOutcome outcome;

        private PersistenceOutcomeException(PersistenceOutcome outcome, Exception cause) {
            super(cause.getMessage(), cause);
            this.outcome = outcome;
        }
    }

    @FunctionalInterface
    private interface SqlTransactionBody {
        void run() throws SQLException;
    }

    /** Executes one transaction and preserves whether a failed JDBC call could have committed. */
    private static void runTransaction(Connection connection, SqlTransactionBody body) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        boolean commitSucceeded = false;
        boolean rollbackFailed = false;
        Exception failure = null;
        try {
            connection.setAutoCommit(false);
            transactionStarted = true;
            body.run();
            commitAttempted = true;
            connection.commit();
            commitSucceeded = true;
        } catch (Exception transactionFailure) {
            failure = transactionFailure;
            if (transactionStarted) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    rollbackFailed = true;
                    transactionFailure.addSuppressed(rollbackFailure);
                }
            }
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException restoreFailure) {
                if (failure == null) {
                    failure = restoreFailure;
                } else {
                    failure.addSuppressed(restoreFailure);
                }
            }
        }

        if (failure != null) {
            PersistenceOutcome outcome = commitSucceeded
                    ? PersistenceOutcome.COMMITTED
                    : commitAttempted || rollbackFailed
                            ? PersistenceOutcome.COMMIT_UNKNOWN
                            : PersistenceOutcome.NOT_COMMITTED;
            throw new PersistenceOutcomeException(outcome, failure);
        }
    }

    private static boolean hasUnknownCommitOutcome(Exception failure) {
        return failure instanceof PersistenceOutcomeException outcomeFailure
                && outcomeFailure.outcome == PersistenceOutcome.COMMIT_UNKNOWN;
    }

    private static void persistMeasured(int batchSize, PersistenceOperation operation) throws Exception {
        long started = System.nanoTime();
        boolean succeeded = false;
        try {
            operation.run();
            succeeded = true;
        } catch (PersistenceOutcomeException failure) {
            if (failure.outcome != PersistenceOutcome.COMMITTED) {
                throw failure;
            }
            succeeded = true;
            LOGGER.error("ItemGraph transaction committed, but JDBC connection state restoration failed", failure);
        } finally {
            OperationalMetrics.getInstance().recordPersistenceBatch(
                    batchSize, System.nanoTime() - started, succeeded);
        }
    }

    /**
     * Puts an audit batch back behind any events that arrived while the write was in
     * progress. The queue is bounded, so an event that cannot be retained is counted
     * as dropped and never reported as persisted.
     */
    private void requeueAuditBatch(List<InternalAuditEvent> batch, Exception failure) {
        long now = System.currentTimeMillis();
        long lastLog = lastAuditRetryLogMs;
        if (lastLog == 0L || now - lastLog >= AUDIT_RETRY_LOG_INTERVAL_MS) {
            lastAuditRetryLogMs = now;
            LOGGER.error("Could not persist {} native audit events; retaining them for retry (backoff={} ms)",
                    batch.size(), auditRetryBackoffMs, failure);
        }
        synchronized (queueMutationLock) {
            for (InternalAuditEvent event : batch) {
                if (!auditEventQueue.offer(event)) {
                    long dropped = totalDropped.incrementAndGet();
                    OperationalMetrics.getInstance().recordQueueRejection(1);
                    if (dropped == 1 || dropped % 1000 == 0) {
                        LOGGER.warn("Native audit queue remained full while retrying; dropped {} ({} dropped total; evidence loss)",
                                event.eventType(), dropped);
                    }
                } else {
                    signalQueuedWork();
                }
            }
            OperationalMetrics.getInstance().recordQueueDepth(getQueueSize());
        }
        if (running.get()) {
            long backoff = auditRetryBackoffMs;
            auditRetryBackoffMs = Math.min(AUDIT_RETRY_MAX_BACKOFF_MS, backoff * 2L);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void resetAuditRetryState() {
        auditRetryBackoffMs = AUDIT_RETRY_INITIAL_BACKOFF_MS;
        lastAuditRetryLogMs = 0L;
    }

    /**
     * Returns a failed transformation batch to its bounded queue. Events that cannot
     * fit are counted as evidence loss; the in-flight batch is never retained only in
     * worker-local memory across another pass or shutdown.
     */
    private void requeueTransformationBatch(List<InternalTransformation> batch, Exception failure) {
        if (batch.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastLog = lastTransformationRetryLogMs;
        if (lastLog == 0L || now - lastLog >= AUDIT_RETRY_LOG_INTERVAL_MS) {
            lastTransformationRetryLogMs = now;
            LOGGER.error("Could not persist {} transformations; retaining them for retry (backoff={} ms)",
                    batch.size(), transformationRetryBackoffMs, failure);
        }
        synchronized (queueMutationLock) {
            for (InternalTransformation transformation : batch) {
                if (!transformationQueue.offer(transformation)) {
                    pendingTransformations.decrementAndGet();
                    long dropped = totalDropped.incrementAndGet();
                    OperationalMetrics.getInstance().recordQueueRejection(1);
                    if (dropped == 1 || dropped % 1000 == 0) {
                        LOGGER.warn("Transformation queue remained full while retrying; dropped {} ({} dropped total; evidence loss)",
                                transformation.transformationType(), dropped);
                    }
                } else {
                    signalQueuedWork();
                }
            }
            OperationalMetrics.getInstance().recordQueueDepth(getQueueSize());
        }
        if (running.get()) {
            long backoff = transformationRetryBackoffMs;
            transformationRetryBackoffMs = Math.min(AUDIT_RETRY_MAX_BACKOFF_MS, backoff * 2L);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void resetTransformationRetryState() {
        transformationRetryBackoffMs = AUDIT_RETRY_INITIAL_BACKOFF_MS;
        lastTransformationRetryLogMs = 0L;
    }

    private void reportTransformationBatchLoss(List<InternalTransformation> batch, Exception failure) {
        long dropped = totalDropped.addAndGet(batch.size());
        LOGGER.error("Could not persist {} transformations during shutdown; {} events were definitely not committed",
                batch.size(), dropped, failure);
    }

    private void reportObservationBatchLoss(List<InternalObservation> batch, Exception failure) {
        long dropped = totalDropped.addAndGet(batch.size());
        LOGGER.error("Could not persist {} internal observations during shutdown; {} events were definitely not committed",
                batch.size(), dropped, failure);
    }

    private void reportAuditBatchLoss(List<InternalAuditEvent> batch, Exception failure) {
        long dropped = totalDropped.addAndGet(batch.size());
        LOGGER.error("Could not persist {} native audit events during shutdown; {} events were definitely not committed",
                batch.size(), dropped, failure);
    }

    private void reportTransformationBatchOutcomeUnknown(List<InternalTransformation> batch, Exception failure) {
        long unknown = totalPersistenceOutcomeUnknown.addAndGet(batch.size());
        LOGGER.error("Shutdown could not confirm persistence for {} transformations ({} records with unknown commit outcome)",
                batch.size(), unknown, failure);
    }

    private void reportObservationBatchOutcomeUnknown(List<InternalObservation> batch, Exception failure) {
        long unknown = totalPersistenceOutcomeUnknown.addAndGet(batch.size());
        LOGGER.error("Shutdown could not confirm persistence for {} internal observations ({} records with unknown commit outcome)",
                batch.size(), unknown, failure);
    }

    private void reportAuditBatchOutcomeUnknown(List<InternalAuditEvent> batch, Exception failure) {
        long unknown = totalPersistenceOutcomeUnknown.addAndGet(batch.size());
        LOGGER.error("Shutdown could not confirm persistence for {} native audit events ({} records with unknown commit outcome)",
                batch.size(), unknown, failure);
    }

    private void recordPostStopRejection(int count) {
        if (count <= 0) {
            return;
        }
        long dropped = totalDropped.addAndGet(count);
        OperationalMetrics.getInstance().recordQueueRejection(count);
        if (dropped == count || dropped % 1000 < count) {
            LOGGER.warn("Rejected {} internal evidence records after shutdown admission closed ({} dropped total)",
                    count, dropped);
        }
    }

    /**
     * Retains an observation batch after a transactional write failure so a
     * paired legacy audit projection cannot outlive the quantity evidence.
     */
    private void requeueObservationBatch(List<InternalObservation> batch, Exception failure) {
        if (batch.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long lastLog = lastObservationRetryLogMs;
        if (lastLog == 0L || now - lastLog >= AUDIT_RETRY_LOG_INTERVAL_MS) {
            lastObservationRetryLogMs = now;
            LOGGER.error("Could not persist {} internal observations; retaining them for retry (backoff={} ms)",
                    batch.size(), observationRetryBackoffMs, failure);
        }
        synchronized (observationQueueLock) {
            synchronized (queueMutationLock) {
                for (InternalObservation observation : batch) {
                    if (!queue.offer(observation)) {
                        long dropped = totalDropped.incrementAndGet();
                        OperationalMetrics.getInstance().recordQueueRejection(1);
                        if (dropped == 1 || dropped % 1000 == 0) {
                            LOGGER.warn("Internal observation queue remained full while retrying; dropped {} ({} dropped total)",
                                    observation.actionType(), dropped);
                        }
                    } else {
                        signalQueuedWork();
                    }
                }
                OperationalMetrics.getInstance().recordQueueDepth(getQueueSize());
            }
        }
        if (running.get()) {
            long backoff = observationRetryBackoffMs;
            observationRetryBackoffMs = Math.min(AUDIT_RETRY_MAX_BACKOFF_MS, backoff * 2L);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void resetObservationRetryState() {
        observationRetryBackoffMs = AUDIT_RETRY_INITIAL_BACKOFF_MS;
        lastObservationRetryLogMs = 0L;
    }

    private void persistAuditEvents(List<InternalAuditEvent> batch) throws SQLException {
        if (batch.isEmpty()) {
            return;
        }
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            throw new SQLException("ItemGraph database is not initialized");
        }

        Connection conn = db.getConnection();
        synchronized (conn) {
            runTransaction(conn, () -> {
                String insertSql = """
                    INSERT OR IGNORE INTO ig_audit_events (
                        event_type, timestamp_ms, player_uuid, player_name,
                        level_id, x, y, z, subject_id, detail, source_type, source_event_id,
                        raw_data, ingest_event_uuid
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ITEMGRAPH_INTERNAL', ?, ?, ?)
                """;
                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    for (InternalAuditEvent event : batch) {
                        pstmt.setString(1, event.eventType());
                        pstmt.setLong(2, event.timestampMs());
                        setNullableString(pstmt, 3, event.playerUuid());
                        setNullableString(pstmt, 4, event.playerName());
                        setNullableString(pstmt, 5, event.levelName());
                        pstmt.setDouble(6, event.x());
                        pstmt.setDouble(7, event.y());
                        pstmt.setDouble(8, event.z());
                        setNullableString(pstmt, 9, event.subjectId());
                        setNullableString(pstmt, 10, event.detail());
                        if (event.sourceEventId() == null) {
                            pstmt.setNull(11, Types.BIGINT);
                        } else {
                            pstmt.setLong(11, collisionSafeSourceEventId(conn, "ig_audit_events",
                                    event.sourceEventId(), event.rawData()));
                        }
                        if (event.rawData() == null) {
                            pstmt.setNull(12, Types.BLOB);
                        } else {
                            pstmt.setBytes(12, event.rawData());
                        }
                        pstmt.setString(13, event.ingestEventUuid());
                        pstmt.executeUpdate();
                        if ("BREAK_BLOCK".equalsIgnoreCase(event.eventType())) {
                            long removalId = findPersistedAuditEventId(conn, event.ingestEventUuid());
                            supersedeEarlierBlockInteractions(conn, event, removalId);
                            supersedeImportedBlockInteractions(conn, event, removalId);
                        }
                    }
                }
            });
        }
    }

    /**
     * Adds durable visibility tombstones after the break event is inserted. Raw
     * interaction rows remain untouched and ordinary lookup can still explain
     * which break superseded each one.
     */
    private static long findPersistedAuditEventId(Connection connection, String ingestEventUuid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM ig_audit_events WHERE ingest_event_uuid = ?")) {
            statement.setString(1, ingestEventUuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("persisted BREAK_BLOCK event was not found by ingest UUID");
                }
                return result.getLong(1);
            }
        }
    }

    private static void supersedeEarlierBlockInteractions(Connection connection,
                                                           InternalAuditEvent event,
                                                           long removalId) throws SQLException {
        List<AuditEventQueryService.ExactPosition> targets = event.supersessionPositions().isEmpty()
                ? List.of(new AuditEventQueryService.ExactPosition(event.x(), event.y(), event.z()))
                : event.supersessionPositions();
        String selectSql = """
                SELECT interaction.id
                FROM ig_audit_events interaction
                WHERE interaction.event_type IN ('INTERACT_BLOCK', 'INTERACT_BLOCK_ATTEMPT')
                  AND interaction.level_id = ?
                  AND interaction.x = ? AND interaction.y = ? AND interaction.z = ?
                  AND interaction.timestamp_ms <= ? AND interaction.id < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM ig_audit_event_supersessions prior
                      WHERE prior.superseded_event_id = interaction.id
                  )
                ORDER BY interaction.id
                """;
        String insertSql = """
                INSERT OR IGNORE INTO ig_audit_event_supersessions
                    (superseded_event_id, superseding_event_id, reason_code, created_at_ms)
                VALUES (?, ?, 'BLOCK_REMOVED_AT_TARGET', ?)
                """;
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (AuditEventQueryService.ExactPosition position : targets.stream().distinct().toList()) {
                select.setString(1, event.levelName());
                select.setDouble(2, position.x());
                select.setDouble(3, position.y());
                select.setDouble(4, position.z());
                select.setLong(5, event.timestampMs());
                select.setLong(6, removalId);
                try (ResultSet interactions = select.executeQuery()) {
                    while (interactions.next()) {
                        insert.setLong(1, interactions.getLong(1));
                        insert.setLong(2, removalId);
                        insert.setLong(3, event.timestampMs());
                        insert.executeUpdate();
                    }
                }
            }
        }
    }

    private static void supersedeImportedBlockInteractions(Connection connection,
                                                            InternalAuditEvent event,
                                                            long removalId) throws SQLException {
        String selectSql = """
                SELECT source_sha256, table_name, source_key
                FROM ig_grieflogger_lookup historical
                WHERE historical.table_name = 'blocks'
                  AND UPPER(historical.action_type) IN ('INTERACT_BLOCK', 'INTERACT_BLOCK_ATTEMPT')
                  AND historical.level_name = ?
                  AND historical.x = ? AND historical.y = ? AND historical.z = ?
                  AND historical.timestamp_ms <= ?
                  AND NOT EXISTS (
                      SELECT 1 FROM ig_grieflogger_row_supersessions prior
                      WHERE prior.source_sha256 = historical.source_sha256
                        AND prior.table_name = historical.table_name
                        AND prior.source_key_prefix = SUBSTR(historical.source_key, 1, 191)
                        AND HEX(prior.source_key) = HEX(historical.source_key)
                  )
                ORDER BY historical.source_sha256, historical.source_key
                """;
        String insertSql = """
                INSERT OR IGNORE INTO ig_grieflogger_row_supersessions
                    (source_sha256, table_name, source_key_hash, source_key, source_key_prefix,
                     superseding_event_id, reason_code, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'BLOCK_REMOVED_AT_TARGET', ?)
                """;
        List<AuditEventQueryService.ExactPosition> targets = event.supersessionPositions().isEmpty()
                ? List.of(new AuditEventQueryService.ExactPosition(event.x(), event.y(), event.z()))
                : event.supersessionPositions();
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (AuditEventQueryService.ExactPosition position : targets.stream().distinct().toList()) {
                select.setString(1, event.levelName());
                select.setDouble(2, position.x());
                select.setDouble(3, position.y());
                select.setDouble(4, position.z());
                select.setLong(5, event.timestampMs());
                try (ResultSet historicalRows = select.executeQuery()) {
                    while (historicalRows.next()) {
                        insert.setString(1, historicalRows.getString("source_sha256"));
                        insert.setString(2, historicalRows.getString("table_name"));
                        String sourceKey = historicalRows.getString("source_key");
                        String sourceKeyHash = sha256(sourceKey);
                        insert.setString(3, sourceKeyHash);
                        insert.setString(4, sourceKey);
                        insert.setString(5, sourceKeyPrefix(sourceKey));
                        insert.setLong(6, removalId);
                        insert.setLong(7, event.timestampMs());
                        boolean inserted = insert.executeUpdate() > 0;
                        if (!inserted && !supersessionKeyMatches(connection,
                                historicalRows.getString("source_sha256"),
                                historicalRows.getString("table_name"),
                                sourceKeyHash, sourceKey)) {
                            throw new SQLException("source-key digest collision while recording GriefLogger supersession");
                        }
                    }
                }
            }
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String sourceKeyPrefix(String sourceKey) {
        int prefixCodePoints = Math.min(sourceKey.codePointCount(0, sourceKey.length()), 191);
        return sourceKey.substring(0, sourceKey.offsetByCodePoints(0, prefixCodePoints));
    }

    private static boolean supersessionKeyMatches(Connection connection, String sourceHash,
                                                    String tableName, String sourceKeyHash,
                                                    String sourceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT source_key FROM ig_grieflogger_row_supersessions
                WHERE source_sha256 = ? AND table_name = ? AND source_key_hash = ?
                """)) {
            statement.setString(1, sourceHash);
            statement.setString(2, tableName);
            statement.setString(3, sourceKeyHash);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && sourceKey.equals(result.getString(1));
            }
        }
    }

    private static void setNullableString(PreparedStatement pstmt, int index, String value) throws SQLException {
        if (value == null) {
            pstmt.setNull(index, Types.VARCHAR);
        } else {
            pstmt.setString(index, value);
        }
    }

    private void persistTransformations(List<InternalTransformation> batch) throws SQLException {
        if (batch.isEmpty()) return;
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            throw new SQLException("ItemGraph database is not initialized");
        }

        Connection conn = db.getConnection();
        synchronized (conn) {
            persistTransformationsLocked(conn, batch);
        }
    }

    private void persistTransformationsLocked(Connection conn, List<InternalTransformation> batch) throws SQLException {
        NodeManager nodeManager = IngestionService.getInstance().getNodeManager();

        runTransaction(conn, () -> {
            String insertSql = """
                INSERT OR IGNORE INTO ig_item_transformations (
                    transformation_type, player_node_id, source_fingerprint_id,
                    result_fingerprint_id, quantity, timestamp_ms, details, ingest_event_uuid
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (InternalTransformation trans : batch) {
                    long sourceFpId = getOrCreateFingerprint(conn, trans.sourceItem());
                    long resultFpId = getOrCreateFingerprint(conn, trans.resultItem());
                    long playerNodeId = nodeManager.getOrCreatePlayerNode(
                            conn, trans.playerUuid(), trans.playerName(), trans.levelName(),
                            trans.x(), trans.y(), trans.z());

                    pstmt.setString(1, trans.transformationType());
                    pstmt.setLong(2, playerNodeId);
                    pstmt.setLong(3, sourceFpId);
                    pstmt.setLong(4, resultFpId);
                    pstmt.setInt(5, trans.quantity());
                    pstmt.setLong(6, trans.timestampMs());
                    pstmt.setString(7, trans.details());
                    pstmt.setString(8, trans.ingestEventUuid());
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
        });
    }

    private void persistBatch(List<InternalObservation> batch) throws SQLException {
        if (batch.isEmpty()) return;
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) {
            throw new SQLException("ItemGraph database is not initialized");
        }

        Connection conn = db.getConnection();
        synchronized (conn) {
            persistBatchLocked(conn, batch);
        }
    }

    private void persistBatchLocked(Connection conn, List<InternalObservation> batch) throws SQLException {
        NodeManager nodeManager = IngestionService.getInstance().getNodeManager();

        runTransaction(conn, () -> {
            // The source ID deduplicates producer identities. The separate queued
            // identity also makes retries safe when a DB commit succeeds but the
            // worker loses the acknowledgement and replays the same batch.
            String insertSql = """
                INSERT OR IGNORE INTO ig_observations (
                    source_type, source_event_id, timestamp_ms, node_id, target_node_id,
                    fingerprint_id, action_type, amount, raw_data, correlation_status,
                    item_entity_uuid, timestamp_end_ms, ingest_event_uuid
                ) VALUES ('ITEMGRAPH_INTERNAL', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
            """;

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (InternalObservation obs : batch) {
                    long fingerprintId = obs.item() != null
                            ? getOrCreateFingerprint(conn, obs.item())
                            : getOrCreateFingerprint(conn, obs.itemId(), obs.rawData());

                    Long targetNodeId = null;
                    long originNodeId;

                    switch (obs.targetType() != null ? obs.targetType() : "") {
                        case "ARMOR_STAND" -> {
                            long armorStandNodeId = nodeManager.getOrCreateArmorStandNode(
                                    conn, obs.targetLevelName(), obs.targetX(), obs.targetY(), obs.targetZ());
                            if ("EQUIP_ARMOR_STAND".equals(obs.actionType())) {
                                originNodeId = playerNode(conn, nodeManager, obs);
                                targetNodeId = armorStandNodeId;
                            } else {
                                // UNEQUIP_ARMOR_STAND
                                originNodeId = armorStandNodeId;
                                targetNodeId = playerNode(conn, nodeManager, obs);
                            }
                        }
                        case "GROUND" -> {
                            // DROP_ITEM / DEATH_DROP: player -> ground
                            // PICKUP_ITEM: ground -> player
                            long groundNodeId = nodeManager.getOrCreateGroundNode(
                                    conn, obs.targetLevelName(),
                                    obs.targetX(), obs.targetY(), obs.targetZ());
                            if ("PICKUP_ITEM".equals(obs.actionType())) {
                                originNodeId = groundNodeId;
                                targetNodeId = playerNode(conn, nodeManager, obs);
                            } else {
                                // DROP_ITEM, DEATH_DROP, THROW_ITEM, SHOOT_ITEM
                                originNodeId = playerNode(conn, nodeManager, obs);
                                targetNodeId = groundNodeId;
                            }
                        }
                        case "ADMIN_CREATE_PLAYER", "CREATIVE_CREATE_PLAYER" -> {
                            // Administrative or creative creation is an explicit
                            // source event: unknown source -> confirmed player inventory.
                            originNodeId = unknownNode(conn, nodeManager, obs);
                            targetNodeId = playerNode(conn, nodeManager, obs);
                        }
                        case "ADMIN_REMOVE_PLAYER", "CREATIVE_REMOVE_PLAYER" -> {
                            // Administrative or creative removal is explicit destruction;
                            // the sink stays unknown instead of inventing a destination.
                            originNodeId = playerNode(conn, nodeManager, obs);
                            targetNodeId = unknownNode(conn, nodeManager, obs);
                        }
                        case "ADMIN_CREATE_CONTAINER" -> {
                            originNodeId = unknownNode(conn, nodeManager, obs);
                            targetNodeId = nodeManager.getOrCreateContainerNode(
                                    conn, obs.targetLevelName(),
                                    obs.targetX(), obs.targetY(), obs.targetZ());
                        }
                        case "ADMIN_REMOVE_CONTAINER" -> {
                            originNodeId = nodeManager.getOrCreateContainerNode(
                                    conn, obs.targetLevelName(),
                                    obs.targetX(), obs.targetY(), obs.targetZ());
                            targetNodeId = unknownNode(conn, nodeManager, obs);
                        }
                        case "ADMIN_CREATE_GROUND", "CREATIVE_CREATE_GROUND" -> {
                            originNodeId = unknownNode(conn, nodeManager, obs);
                            targetNodeId = nodeManager.getOrCreateGroundNode(
                                    conn, obs.targetLevelName(),
                                    obs.targetX(), obs.targetY(), obs.targetZ());
                        }
                        case "ADMIN_REMOVE_GROUND" -> {
                            originNodeId = nodeManager.getOrCreateGroundNode(
                                    conn, obs.targetLevelName(),
                                    obs.targetX(), obs.targetY(), obs.targetZ());
                            targetNodeId = unknownNode(conn, nodeManager, obs);
                        }
                        case "CONTAINER" -> {
                            // ADD_ITEM / REMOVE_ITEM: player <-> container; CAPABILITY_* keeps the remote endpoint UNKNOWN.
                            long containerNodeId = nodeManager.getOrCreateContainerNode(
                                    conn, obs.targetLevelName(),
                                    obs.targetX(), obs.targetY(), obs.targetZ());
                            switch (obs.actionType()) {
                                case "ADD_ITEM" -> {
                                    // Player deposits into container: player -> container.
                                    // Ambiguous sessions resolve the actor endpoint to UNKNOWN; candidates stay in raw_data.
                                    originNodeId = actorNode(conn, nodeManager, obs);
                                    targetNodeId = containerNodeId;
                                }
                                case "REMOVE_ITEM" -> {
                                    // Player withdraws from container: container -> player.
                                    originNodeId = containerNodeId;
                                    targetNodeId = actorNode(conn, nodeManager, obs);
                                }
                                case "CAPABILITY_INSERT", "HOPPER_INSERT" -> {
                                    // Capability-mediated insertion; the handler API does not identify the caller.
                                    originNodeId = unknownNode(conn, nodeManager, obs);
                                    targetNodeId = containerNodeId;
                                }
                                case "CAPABILITY_EXTRACT", "HOPPER_EXTRACT" -> {
                                    // Capability-mediated extraction; the handler API does not identify the caller.
                                    originNodeId = containerNodeId;
                                    targetNodeId = unknownNode(conn, nodeManager, obs);
                                }
                                default -> {
                                    originNodeId = containerNodeId;
                                    targetNodeId = null;
                                }
                            }
                        }
                        case "ENDER_CHEST" -> {
                            long enderNodeId = nodeManager.getOrCreateExternalInventoryNode(
                                    conn,
                                    "minecraft:ender_chest/" + obs.playerUuid(),
                                    "Ender Chest of " + obs.playerName(),
                                    obs.targetLevelName(), null, null, null);
                            if ("ADD_ITEM_ENDER".equals(obs.actionType())) {
                                originNodeId = playerNode(conn, nodeManager, obs);
                                targetNodeId = enderNodeId;
                            } else if ("REMOVE_ITEM_ENDER".equals(obs.actionType())) {
                                originNodeId = enderNodeId;
                                targetNodeId = playerNode(conn, nodeManager, obs);
                            } else {
                                originNodeId = enderNodeId;
                            }
                        }
                        case "UNKNOWN" -> {
                            originNodeId = playerNode(conn, nodeManager, obs);
                            targetNodeId = unknownNode(conn, nodeManager, obs);
                        }
                        case "PLAYER" -> {
                            // Direct player-to-player observation (future use)
                            originNodeId = playerNode(conn, nodeManager, obs);
                            targetNodeId = nodeManager.getOrCreatePlayerNode(
                                    conn, obs.playerUuid(), obs.playerName(),
                                    obs.targetLevelName(), obs.targetX(), obs.targetY(), obs.targetZ());
                        }
                        default -> {
                            // No target type set: anchor to player with no direction (e.g. pure transformation hook)
                            originNodeId = playerNode(conn, nodeManager, obs);
                        }
                    }

                    if (obs.sourceEventId() != null) {
                        pstmt.setLong(1, collisionSafeSourceEventId(conn, "ig_observations",
                                obs.sourceEventId(), obs.rawData()));
                    } else {
                        pstmt.setNull(1, Types.BIGINT);
                    }
                    pstmt.setLong(2, obs.timestampMs());
                    pstmt.setLong(3, originNodeId);
                    if (targetNodeId != null) {
                        pstmt.setLong(4, targetNodeId);
                    } else {
                        pstmt.setNull(4, Types.INTEGER);
                    }
                    pstmt.setLong(5, fingerprintId);
                    pstmt.setString(6, obs.actionType());
                    pstmt.setInt(7, obs.amount());
                    if (obs.rawData() != null) {
                        pstmt.setBytes(8, obs.rawData());
                    } else {
                        pstmt.setNull(8, Types.BLOB);
                    }
                    if (obs.itemEntityUuid() != null) {
                        pstmt.setString(9, obs.itemEntityUuid());
                    } else {
                        pstmt.setNull(9, Types.VARCHAR);
                    }
                    if (obs.timestampEndMs() != null) {
                        pstmt.setLong(10, obs.timestampEndMs());
                    } else {
                        pstmt.setNull(10, Types.BIGINT);
                    }
                    pstmt.setString(11, obs.ingestEventUuid());
                    pstmt.executeUpdate();
                }
            }
        });
    }

    /**
     * Resolves the real player node for an observation. Only called for action
     * types that have a genuine player endpoint — sentinel identities
     * ({@code [capability caller unknown]}, {@code [ambiguous]}) must not materialize fake
     * PLAYER rows in {@code ig_nodes}.
     */
    private static long playerNode(Connection conn, NodeManager nodeManager, InternalObservation obs)
            throws SQLException {
        return nodeManager.getOrCreatePlayerNode(
                conn, obs.playerUuid(), obs.playerName(), obs.levelName(), obs.x(), obs.y(), obs.z());
    }

    /**
     * Resolves the player-side endpoint of a player-attributable container action:
     * the actor's PLAYER node when the actor is unambiguous, or the per-level
     * UNKNOWN sentinel when multiple players held the container open during the
     * observation window (the candidates are preserved in {@code raw_data}).
     */
    private static long actorNode(Connection conn, NodeManager nodeManager, InternalObservation obs)
            throws SQLException {
        if (com.itemgraph.listener.ContainerInteractionTracker.AMBIGUOUS_UUID.equals(obs.playerUuid())
                || com.itemgraph.listener.ContainerInteractionTracker.UNKNOWN_CALLER_UUID.equals(obs.playerUuid())) {
            return unknownNode(conn, nodeManager, obs);
        }
        return playerNode(conn, nodeManager, obs);
    }

    private static long unknownNode(Connection conn, NodeManager nodeManager, InternalObservation obs)
            throws SQLException {
        return nodeManager.getOrCreateUnknownNode(conn, obs.targetLevelName());
    }

    public long getOrCreateFingerprint(Connection conn, CanonicalItem canonical) throws SQLException {
        String selectSql = "SELECT id FROM ig_item_fingerprints WHERE fingerprint_hash = ? LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, canonical.fingerprintHash());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        String insertSql = """
            INSERT INTO ig_item_fingerprints (
                item_id, fingerprint_hash, custom_name, rarity, component_summary
            ) VALUES (?, ?, ?, ?, ?)
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, canonical.itemId());
            pstmt.setString(2, canonical.fingerprintHash());
            pstmt.setString(3, canonical.customName());
            pstmt.setString(4, canonical.rarity());
            pstmt.setString(5, canonical.componentSummary());
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Failed to create fingerprint for " + canonical.itemId());
    }

    public long getOrCreateFingerprint(Connection conn, String rawItemId, byte[] rawData) throws SQLException {
        CanonicalItem canonical = ItemCanonicalizer.canonicalize(rawItemId, rawData);
        return getOrCreateFingerprint(conn, canonical);
    }
}
