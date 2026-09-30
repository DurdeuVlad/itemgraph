package com.itemgraph.ingest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.graph.NodeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
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
            Long sourceEventId
    ) {
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
            String details
    ) {}

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
            Long sourceEventId
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
                    subjectId, detail, rawData, null);
        }

        public InternalAuditEvent {
            rawData = rawData == null ? null : rawData.clone();
        }

        @Override
        public byte[] rawData() {
            return rawData == null ? null : rawData.clone();
        }
    }

    private final BlockingQueue<InternalObservation> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<InternalTransformation> transformationQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<InternalAuditEvent> auditEventQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong totalEnqueued = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalPersisted = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalDropped = new java.util.concurrent.atomic.AtomicLong(0);
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
    private volatile int maxBatchSize = DEFAULT_MAX_BATCH_SIZE;
    private volatile int databaseHeartbeatIntervalMs = 30_000;
    private volatile boolean captureEnabled = true;
    private Thread workerThread;

    private InternalObservationService() {}

    /** Applies validated startup settings before the worker begins. */
    public synchronized void configureOperations(int pollIntervalMs, int configuredMaxBatchSize,
                                                 int configuredDatabaseHeartbeatIntervalMs,
                                                 boolean configuredCaptureEnabled) {
        if (running.get()) {
            throw new IllegalStateException("Cannot change ItemGraph operations settings while the worker is running; restart the server");
        }
        if (pollIntervalMs < 10 || pollIntervalMs > 5_000) {
            throw new IllegalArgumentException("ingestion.poll_interval_ms must be in [10,5000]");
        }
        if (configuredMaxBatchSize < 1 || configuredMaxBatchSize > 1_000) {
            throw new IllegalArgumentException("ingestion.max_batch_size must be in [1,1000]");
        }
        if (configuredDatabaseHeartbeatIntervalMs < 1_000 || configuredDatabaseHeartbeatIntervalMs > 3_600_000) {
            throw new IllegalArgumentException("operations.database_heartbeat_interval_ms must be in [1000,3600000]");
        }
        queuePollIntervalMs = pollIntervalMs;
        maxBatchSize = configuredMaxBatchSize;
        databaseHeartbeatIntervalMs = configuredDatabaseHeartbeatIntervalMs;
        captureEnabled = configuredCaptureEnabled;
    }

    public int getQueuePollIntervalMs() {
        return queuePollIntervalMs;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
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

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);
        workerThread = new Thread(this::drainQueueSafely, "ItemGraph-Internal-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
        LOGGER.info("ItemGraph internal observation service started.");
    }

    public synchronized void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            boolean interrupted = false;
            while (workerThread.isAlive()) {
                try {
                    workerThread.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    workerThread.interrupt();
                }
            }
            workerThread = null;
            // Do not flush or let the database close while a worker still owns a batch.
            flushQueues();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            return;
        }
        // Flush remaining queues synchronously on shutdown
        flushQueues();
    }

    public boolean submit(InternalObservation obs) {
        if (!captureEnabled) {
            return true;
        }
        boolean ok = queue.offer(obs);
        if (ok) {
            totalEnqueued.incrementAndGet();
        } else {
            long dropped = totalDropped.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                LOGGER.warn("Internal observation queue is full — dropped {} observation ({} dropped total; evidence loss)",
                        obs.actionType(), dropped);
            }
        }
        return ok;
    }

    public boolean submitTransformation(InternalTransformation trans) {
        if (!captureEnabled) {
            return true;
        }
        pendingTransformations.incrementAndGet();
        boolean ok = transformationQueue.offer(trans);
        if (ok) {
            totalEnqueued.incrementAndGet();
        } else {
            pendingTransformations.decrementAndGet();
            long dropped = totalDropped.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                LOGGER.warn("Transformation queue is full — dropped {} ({} dropped total; evidence loss)",
                        trans.transformationType(), dropped);
            }
        }
        return ok;
    }

    public boolean submitAuditEvent(InternalAuditEvent event) {
        if (event == null) {
            return false;
        }
        if (!captureEnabled) {
            return true;
        }
        boolean ok = auditEventQueue.offer(event);
        if (ok) {
            totalEnqueued.incrementAndGet();
        } else {
            long dropped = totalDropped.incrementAndGet();
            if (dropped == 1 || dropped % 1000 == 0) {
                LOGGER.warn("Native audit event queue is full — dropped {} ({} dropped total; evidence loss)",
                        event.eventType(), dropped);
            }
        }
        return ok;
    }

    public int getQueueSize() {
        return queue.size() + pendingTransformations.get() + auditEventQueue.size();
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

    public long getTotalTransformations() {
        return totalTransformations.get();
    }

    public long getTotalAuditEvents() {
        return totalAuditEvents.get();
    }

    public synchronized void clear() {
        queue.clear();
        transformationQueue.clear();
        auditEventQueue.clear();
        pendingTransformations.set(0);
        totalEnqueued.set(0);
        totalPersisted.set(0);
        totalDropped.set(0);
        totalTransformations.set(0);
        totalAuditEvents.set(0);
        totalDatabaseHeartbeats.set(0);
        totalDatabaseHeartbeatFailures.set(0);
        resetAuditRetryState();
        resetObservationRetryState();
    }

    private void drainQueueSafely() {
        List<InternalObservation> obsBatch = new ArrayList<>(maxBatchSize);
        List<InternalTransformation> transBatch = new ArrayList<>(maxBatchSize);
        long nextDatabaseHeartbeatNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(databaseHeartbeatIntervalMs);

        while (running.get()) {
            try {
                long nanosUntilHeartbeat = nextDatabaseHeartbeatNanos - System.nanoTime();
                long pollWaitMs = Math.min(queuePollIntervalMs,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanosUntilHeartbeat)));
                InternalObservation firstObs = queue.poll(pollWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (firstObs != null) {
                    obsBatch.add(firstObs);
                    queue.drainTo(obsBatch, maxBatchSize - 1);
                    try {
                        persistBatch(obsBatch);
                        resetObservationRetryState();
                        totalPersisted.addAndGet(obsBatch.size());
                    } catch (Exception failure) {
                        requeueObservationBatch(obsBatch, failure);
                    }
                    obsBatch.clear();
                }

                InternalTransformation firstTrans = transformationQueue.poll();
                if (firstTrans != null) {
                    transBatch.add(firstTrans);
                    transformationQueue.drainTo(transBatch, maxBatchSize - 1);
                    try {
                        persistTransformations(transBatch);
                        resetTransformationRetryState();
                        totalTransformations.addAndGet(transBatch.size());
                        totalPersisted.addAndGet(transBatch.size());
                        pendingTransformations.addAndGet(-transBatch.size());
                    } catch (Exception failure) {
                        requeueTransformationBatch(transBatch, failure);
                    } finally {
                        transBatch.clear();
                    }
                }

                InternalAuditEvent firstAudit = auditEventQueue.poll();
                if (firstAudit != null) {
                    List<InternalAuditEvent> auditBatch = new ArrayList<>(maxBatchSize);
                    auditBatch.add(firstAudit);
                    auditEventQueue.drainTo(auditBatch, maxBatchSize - 1);
                    try {
                        persistAuditEvents(auditBatch);
                        resetAuditRetryState();
                        totalAuditEvents.addAndGet(auditBatch.size());
                        totalPersisted.addAndGet(auditBatch.size());
                    } catch (Exception e) {
                        requeueAuditBatch(auditBatch, e);
                    }
                }

                long nowNanos = System.nanoTime();
                if (nowNanos >= nextDatabaseHeartbeatNanos) {
                    runDatabaseHeartbeat();
                    nextDatabaseHeartbeatNanos = System.nanoTime()
                            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(databaseHeartbeatIntervalMs);
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                LOGGER.error("Error persisting internal observations/transformations batch", e);
            }
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

    private void flushQueues() {
        List<InternalObservation> remainingObs = new ArrayList<>();
        queue.drainTo(remainingObs);
        if (!remainingObs.isEmpty()) {
            try {
                persistBatch(remainingObs);
                totalPersisted.addAndGet(remainingObs.size());
            } catch (Exception e) {
                long dropped = totalDropped.addAndGet(remainingObs.size());
                LOGGER.error("Could not persist {} internal observations during shutdown; {} events were dropped",
                        remainingObs.size(), dropped, e);
            }
        }

        List<InternalTransformation> remainingTrans = new ArrayList<>(maxBatchSize);
        while (transformationQueue.drainTo(remainingTrans, maxBatchSize) > 0) {
            try {
                persistTransformations(remainingTrans);
                resetTransformationRetryState();
                totalTransformations.addAndGet(remainingTrans.size());
                totalPersisted.addAndGet(remainingTrans.size());
            } catch (Exception e) {
                reportTransformationBatchLoss(remainingTrans, e);
            } finally {
                pendingTransformations.addAndGet(-remainingTrans.size());
                remainingTrans.clear();
            }
        }

        List<InternalAuditEvent> remainingAudit = new ArrayList<>();
        auditEventQueue.drainTo(remainingAudit);
        if (!remainingAudit.isEmpty()) {
            try {
                persistAuditEvents(remainingAudit);
                resetAuditRetryState();
                totalAuditEvents.addAndGet(remainingAudit.size());
                totalPersisted.addAndGet(remainingAudit.size());
            } catch (Exception e) {
                // The worker is stopped at this point, so retaining rows in memory
                // would not provide a future retry after the database closes.
                reportAuditBatchLoss(remainingAudit, e);
            }
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
        for (InternalAuditEvent event : batch) {
            if (!auditEventQueue.offer(event)) {
                long dropped = totalDropped.incrementAndGet();
                if (dropped == 1 || dropped % 1000 == 0) {
                    LOGGER.warn("Native audit queue remained full while retrying; dropped {} ({} dropped total; evidence loss)",
                            event.eventType(), dropped);
                }
            }
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
        for (InternalTransformation transformation : batch) {
            if (!transformationQueue.offer(transformation)) {
                pendingTransformations.decrementAndGet();
                long dropped = totalDropped.incrementAndGet();
                if (dropped == 1 || dropped % 1000 == 0) {
                    LOGGER.warn("Transformation queue remained full while retrying; dropped {} ({} dropped total; evidence loss)",
                            transformation.transformationType(), dropped);
                }
            }
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
        LOGGER.error("Could not persist {} transformations during shutdown; {} events were dropped",
                batch.size(), dropped, failure);
    }

    private void reportAuditBatchLoss(List<InternalAuditEvent> batch, Exception failure) {
        long dropped = totalDropped.addAndGet(batch.size());
        LOGGER.error("Could not persist {} native audit events during shutdown; {} events were dropped",
                batch.size(), dropped, failure);
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
        for (InternalObservation observation : batch) {
            if (!queue.offer(observation)) {
                long dropped = totalDropped.incrementAndGet();
                if (dropped == 1 || dropped % 1000 == 0) {
                    LOGGER.warn("Internal observation queue remained full while retrying; dropped {} ({} dropped total)",
                            observation.actionType(), dropped);
                }
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
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                String insertSql = """
                    INSERT OR IGNORE INTO ig_audit_events (
                        event_type, timestamp_ms, player_uuid, player_name,
                        level_id, x, y, z, subject_id, detail, source_type, source_event_id, raw_data
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ITEMGRAPH_INTERNAL', ?, ?)
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
                        pstmt.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
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

        boolean origAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            String insertSql = """
                INSERT INTO ig_item_transformations (
                    transformation_type, player_node_id, source_fingerprint_id,
                    result_fingerprint_id, quantity, timestamp_ms, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
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
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
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

        boolean origAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            // INSERT OR IGNORE: source_event_id deduplicates durable producer
            // identities; collisionSafeSourceEventId prevents the lossy numeric
            // UUID projection from silently discarding a distinct payload.
            String insertSql = """
                INSERT OR IGNORE INTO ig_observations (
                    source_type, source_event_id, timestamp_ms, node_id, target_node_id,
                    fingerprint_id, action_type, amount, raw_data, correlation_status, item_entity_uuid, timestamp_end_ms
                ) VALUES ('ITEMGRAPH_INTERNAL', ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
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
                    pstmt.executeUpdate();
                }
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(origAutoCommit);
        }
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
