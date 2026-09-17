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
            String targetType, // e.g. "ARMOR_STAND", "PLAYER", "GROUND"
            String itemId,
            byte[] rawData,
            CanonicalItem item,
            int amount,
            String itemEntityUuid
    ) {
        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, String itemId, byte[] rawData, int amount, String itemEntityUuid
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                 targetLevelName, targetX, targetY, targetZ, targetType, itemId, rawData, null, amount, itemEntityUuid);
        }

        public InternalObservation(
                long timestampMs, String actionType, String playerUuid, String playerName,
                String levelName, double x, double y, double z,
                String targetLevelName, Double targetX, Double targetY, Double targetZ,
                String targetType, CanonicalItem item, int amount, String itemEntityUuid
        ) {
            this(timestampMs, actionType, playerUuid, playerName, levelName, x, y, z,
                 targetLevelName, targetX, targetY, targetZ, targetType,
                 item != null ? item.itemId() : "minecraft:air", null, item, amount, itemEntityUuid);
        }
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

    private final BlockingQueue<InternalObservation> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<InternalTransformation> transformationQueue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong totalEnqueued = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalPersisted = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalTransformations = new java.util.concurrent.atomic.AtomicLong(0);
    private Thread workerThread;

    private InternalObservationService() {}

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
            try {
                workerThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            workerThread = null;
        }
        // Flush remaining queues synchronously on shutdown
        flushQueues();
    }

    public boolean submit(InternalObservation obs) {
        boolean ok = queue.offer(obs);
        if (ok) {
            totalEnqueued.incrementAndGet();
        }
        return ok;
    }

    public boolean submitTransformation(InternalTransformation trans) {
        boolean ok = transformationQueue.offer(trans);
        if (ok) {
            totalEnqueued.incrementAndGet();
        }
        return ok;
    }

    public int getQueueSize() {
        return queue.size() + transformationQueue.size();
    }

    public long getTotalEnqueued() {
        return totalEnqueued.get();
    }

    public long getTotalPersisted() {
        return totalPersisted.get();
    }

    public long getTotalTransformations() {
        return totalTransformations.get();
    }

    public synchronized void clear() {
        queue.clear();
        transformationQueue.clear();
        totalEnqueued.set(0);
        totalPersisted.set(0);
        totalTransformations.set(0);
    }

    private void drainQueueSafely() {
        List<InternalObservation> obsBatch = new ArrayList<>(100);
        List<InternalTransformation> transBatch = new ArrayList<>(100);

        while (running.get()) {
            try {
                InternalObservation firstObs = queue.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (firstObs != null) {
                    obsBatch.add(firstObs);
                    queue.drainTo(obsBatch, 99);
                    persistBatch(obsBatch);
                    totalPersisted.addAndGet(obsBatch.size());
                    obsBatch.clear();
                }

                InternalTransformation firstTrans = transformationQueue.poll();
                if (firstTrans != null) {
                    transBatch.add(firstTrans);
                    transformationQueue.drainTo(transBatch, 99);
                    persistTransformations(transBatch);
                    totalTransformations.addAndGet(transBatch.size());
                    totalPersisted.addAndGet(transBatch.size());
                    transBatch.clear();
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                LOGGER.error("Error persisting internal observations/transformations batch", e);
            }
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
                LOGGER.error("Error flushing internal observations on shutdown", e);
            }
        }

        List<InternalTransformation> remainingTrans = new ArrayList<>();
        transformationQueue.drainTo(remainingTrans);
        if (!remainingTrans.isEmpty()) {
            try {
                persistTransformations(remainingTrans);
                totalTransformations.addAndGet(remainingTrans.size());
                totalPersisted.addAndGet(remainingTrans.size());
            } catch (Exception e) {
                LOGGER.error("Error flushing transformations on shutdown", e);
            }
        }
    }

    private void persistTransformations(List<InternalTransformation> batch) throws SQLException {
        if (batch.isEmpty()) return;
        DatabaseManager db = DatabaseManager.getInstance();
        if (!db.isInitialized()) return;

        Connection conn = db.getConnection();
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
        if (!db.isInitialized()) return;

        Connection conn = db.getConnection();
        NodeManager nodeManager = IngestionService.getInstance().getNodeManager();

        boolean origAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);

            String insertSql = """
                INSERT INTO ig_observations (
                    source_type, source_event_id, timestamp_ms, node_id, target_node_id,
                    fingerprint_id, action_type, amount, raw_data, correlation_status, item_entity_uuid
                ) VALUES ('ITEMGRAPH_INTERNAL', NULL, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """;

            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                for (InternalObservation obs : batch) {
                    long fingerprintId = obs.item() != null
                            ? getOrCreateFingerprint(conn, obs.item())
                            : getOrCreateFingerprint(conn, obs.itemId(), obs.rawData());

                    long playerNodeId = nodeManager.getOrCreatePlayerNode(
                            conn, obs.playerUuid(), obs.playerName(), obs.levelName(), obs.x(), obs.y(), obs.z());

                    Long targetNodeId = null;
                    long originNodeId = playerNodeId;

                    if ("ARMOR_STAND".equals(obs.targetType())) {
                        long armorStandNodeId = nodeManager.getOrCreateArmorStandNode(
                                conn, obs.targetLevelName(), obs.targetX(), obs.targetY(), obs.targetZ());
                        if ("EQUIP_ARMOR_STAND".equals(obs.actionType())) {
                            originNodeId = playerNodeId;
                            targetNodeId = armorStandNodeId;
                        } else {
                            // UNEQUIP_ARMOR_STAND
                            originNodeId = armorStandNodeId;
                            targetNodeId = playerNodeId;
                        }
                    }

                    pstmt.setLong(1, obs.timestampMs());
                    pstmt.setLong(2, originNodeId);
                    if (targetNodeId != null) {
                        pstmt.setLong(3, targetNodeId);
                    } else {
                        pstmt.setNull(3, Types.INTEGER);
                    }
                    pstmt.setLong(4, fingerprintId);
                    pstmt.setString(5, obs.actionType());
                    pstmt.setInt(6, obs.amount());
                    if (obs.rawData() != null) {
                        pstmt.setBytes(7, obs.rawData());
                    } else {
                        pstmt.setNull(7, Types.BLOB);
                    }
                    if (obs.itemEntityUuid() != null) {
                        pstmt.setString(8, obs.itemEntityUuid());
                    } else {
                        pstmt.setNull(8, Types.VARCHAR);
                    }
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
