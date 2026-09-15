package com.itemgraph.ingest;

import com.itemgraph.db.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class IngestionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionService.class);
    private static final IngestionService INSTANCE = new IngestionService();

    public static final String SOURCE_ITEMS = "grieflogger_items";
    public static final String SOURCE_CONTAINERS = "grieflogger_containers";
    public static final String SOURCE_TYPE_GRIEFLOGGER = "GRIEFLOGGER";
    private static final long CONTAINER_EVENT_ID_OFFSET = 10_000_000_000L;
    private static final int BATCH_SIZE = 500;

    private final GriefLoggerAdapter adapter;
    private final DatabaseManager dbManager;

    private final Map<String, Long> fingerprintCache = new ConcurrentHashMap<>();
    private final Map<String, Long> playerNodeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> containerNodeCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile IngestionResult lastResult = null;
    private volatile long lastRunTimestamp = 0;

    public IngestionService() {
        this(new GriefLoggerAdapter(), DatabaseManager.getInstance());
    }

    public IngestionService(GriefLoggerAdapter adapter, DatabaseManager dbManager) {
        this.adapter = adapter;
        this.dbManager = dbManager;
    }

    public static IngestionService getInstance() {
        return INSTANCE;
    }

    public GriefLoggerAdapter getAdapter() {
        return adapter;
    }

    public IngestionResult getLastResult() {
        return lastResult;
    }

    public long getLastRunTimestamp() {
        return lastRunTimestamp;
    }

    /**
     * Starts the background ingestion executor. Runs off the server tick thread.
     */
    public synchronized void start() {
        if (running.get()) {
            return;
        }

        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ItemGraph-Ingestion-Worker");
                t.setDaemon(true);
                return t;
            }
        });

        running.set(true);
        // Schedule every 60 seconds, with an initial delay of 5 seconds
        executor.scheduleWithFixedDelay(this::runIngestionSafely, 5, 60, TimeUnit.SECONDS);
        LOGGER.info("ItemGraph ingestion service started (scheduled every 60s).");
    }

    /**
     * Stops the background ingestion executor cleanly.
     */
    public synchronized void stop() {
        running.set(false);
        if (executor != null) {
            LOGGER.info("Stopping ItemGraph ingestion service...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
            LOGGER.info("ItemGraph ingestion service stopped.");
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void runIngestionSafely() {
        try {
            runIngestion();
        } catch (Throwable t) {
            LOGGER.error("Unexpected error in ItemGraph scheduled ingestion cycle", t);
        }
    }

    /**
     * Executes one complete ingestion cycle across both items and containers tables.
     * Synchronized to prevent overlapping runs between the timer and manual /ig ingest now.
     */
    public synchronized IngestionResult runIngestion() {
        long startTime = System.currentTimeMillis();
        lastRunTimestamp = startTime;

        if (!dbManager.isInitialized()) {
            String error = "ItemGraph database is not initialized";
            LOGGER.warn("Skipping ingestion cycle: {}", error);
            lastResult = new IngestionResult(false, 0, 0, 0, error);
            return lastResult;
        }

        if (!adapter.isDatabaseAvailable()) {
            String error = "GriefLogger database not found or not readable at " + adapter.getDatabasePath();
            LOGGER.warn("Skipping ingestion cycle: {}", error);
            lastResult = new IngestionResult(false, 0, 0, 0, error);
            return lastResult;
        }

        int totalItems = 0;
        int totalContainers = 0;

        try {
            Connection igConn = dbManager.getConnection();

            // Ingest items table
            totalItems = ingestTable(igConn, "items", SOURCE_ITEMS, false);

            // Ingest containers table
            totalContainers = ingestTable(igConn, "containers", SOURCE_CONTAINERS, true);

            long duration = System.currentTimeMillis() - startTime;
            lastResult = new IngestionResult(true, totalItems, totalContainers, duration, null);
            if (totalItems > 0 || totalContainers > 0) {
                LOGGER.info("ItemGraph ingestion cycle completed in {}ms: {} items, {} containers ingested.",
                        duration, totalItems, totalContainers);
            }
            return lastResult;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            String msg = "Ingestion cycle failed: " + e.getMessage();
            LOGGER.error("Error during ItemGraph ingestion cycle", e);
            lastResult = new IngestionResult(false, totalItems, totalContainers, duration, msg);
            return lastResult;
        }
    }

    private int ingestTable(Connection igConn, String tableName, String checkpointSource, boolean isContainerTable) throws SQLException {
        SourceCheckpoint checkpoint = getCheckpoint(igConn, checkpointSource);
        long afterRowId = checkpoint.lastSourceRowid();
        int ingestedCount = 0;

        while (true) {
            List<GriefLoggerRawEvent> events;
            try {
                events = adapter.fetchEvents(tableName, afterRowId, BATCH_SIZE);
            } catch (SQLException e) {
                LOGGER.warn("Could not fetch events from GriefLogger table '{}' (after rowid {}): {}",
                        tableName, afterRowId, e.getMessage());
                break;
            }

            if (events == null || events.isEmpty()) {
                break;
            }

            boolean origAutoCommit = igConn.getAutoCommit();
            try {
                igConn.setAutoCommit(false);

                String insertObsSql = """
                    INSERT OR IGNORE INTO ig_observations (
                        source_type, source_event_id, timestamp_ms, node_id, target_node_id,
                        fingerprint_id, action_type, amount, raw_data
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

                long maxRowId = afterRowId;
                long maxTimestamp = checkpoint.lastTimestamp();

                try (PreparedStatement pstmt = igConn.prepareStatement(insertObsSql)) {
                    for (GriefLoggerRawEvent event : events) {
                        try {
                            long sourceEventId = isContainerTable ? (CONTAINER_EVENT_ID_OFFSET + event.rowid()) : event.rowid();
                            long fingerprintId = getOrCreateFingerprint(igConn, event.materialName());

                            long nodeId;
                            Long targetNodeId = null;

                            if (isContainerTable) {
                                // For containers, node_id is the container block location
                                nodeId = getOrCreateContainerNode(igConn, event.levelName(), event.x(), event.y(), event.z());
                                // target_node_id is the player interacting with the container if available
                                if (event.userUuid() != null || event.userName() != null) {
                                    targetNodeId = getOrCreatePlayerNode(igConn, event.userUuid(), event.userName(), event.levelName(), event.x(), event.y(), event.z());
                                }
                            } else {
                                // For items, node_id is the player performing the action
                                nodeId = getOrCreatePlayerNode(igConn, event.userUuid(), event.userName(), event.levelName(), event.x(), event.y(), event.z());
                            }

                            String actionType = ItemActionMapping.getActionName(event.actionId());

                            pstmt.setString(1, SOURCE_TYPE_GRIEFLOGGER);
                            pstmt.setLong(2, sourceEventId);
                            pstmt.setLong(3, event.timestampMs());
                            pstmt.setLong(4, nodeId);
                            if (targetNodeId != null) {
                                pstmt.setLong(5, targetNodeId);
                            } else {
                                pstmt.setNull(5, Types.INTEGER);
                            }
                            pstmt.setLong(6, fingerprintId);
                            pstmt.setString(7, actionType);
                            pstmt.setInt(8, event.amount());
                            if (event.rawData() != null) {
                                pstmt.setBytes(9, event.rawData());
                            } else {
                                pstmt.setNull(9, Types.BLOB);
                            }

                            pstmt.executeUpdate();
                            ingestedCount++;

                            if (event.rowid() > maxRowId) {
                                maxRowId = event.rowid();
                            }
                            if (event.timestampMs() > maxTimestamp) {
                                maxTimestamp = event.timestampMs();
                            }
                        } catch (Exception rowEx) {
                            LOGGER.warn("Skipping malformed GriefLogger row {} in table '{}': {}", event.rowid(), tableName, rowEx.getMessage());
                        }
                    }
                }

                // Update checkpoint after batch is processed
                updateCheckpoint(igConn, checkpointSource, maxRowId, maxTimestamp);

                igConn.commit();
                afterRowId = maxRowId;
            } catch (SQLException e) {
                igConn.rollback();
                LOGGER.error("Failed to persist batch for GriefLogger table '{}' at rowid {}. Rolled back.", tableName, afterRowId, e);
                throw e;
            } finally {
                igConn.setAutoCommit(origAutoCommit);
            }

            if (events.size() < BATCH_SIZE) {
                break;
            }
        }

        return ingestedCount;
    }

    public SourceCheckpoint getCheckpoint(Connection conn, String sourceName) throws SQLException {
        String sql = "SELECT last_source_rowid, last_timestamp, updated_at FROM ig_source_checkpoints WHERE source_name = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sourceName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return new SourceCheckpoint(
                            sourceName,
                            rs.getLong("last_source_rowid"),
                            rs.getLong("last_timestamp"),
                            rs.getLong("updated_at")
                    );
                }
            }
        }
        return new SourceCheckpoint(sourceName, 0, 0, 0);
    }

    private void updateCheckpoint(Connection conn, String sourceName, long lastRowId, long lastTimestamp) throws SQLException {
        String sql = """
            INSERT INTO ig_source_checkpoints (source_name, last_source_rowid, last_timestamp, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(source_name) DO UPDATE SET
                last_source_rowid = excluded.last_source_rowid,
                last_timestamp = excluded.last_timestamp,
                updated_at = excluded.updated_at
        """;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sourceName);
            pstmt.setLong(2, lastRowId);
            pstmt.setLong(3, lastTimestamp);
            pstmt.setLong(4, System.currentTimeMillis());
            pstmt.executeUpdate();
        }
    }

    public long getOrCreateFingerprint(Connection conn, String materialName) throws SQLException {
        String itemId = normalizeItemId(materialName);
        String hash = sha256Hex(itemId);

        Long cached = fingerprintCache.get(hash);
        if (cached != null) {
            return cached;
        }

        String selectSql = "SELECT id FROM ig_item_fingerprints WHERE fingerprint_hash = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, hash);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    fingerprintCache.put(hash, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT OR IGNORE INTO ig_item_fingerprints (item_id, fingerprint_hash) VALUES (?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, itemId);
            pstmt.setString(2, hash);
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    fingerprintCache.put(hash, id);
                    return id;
                }
            }
        }

        // If insert was ignored (race condition or existing), query again
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, hash);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    fingerprintCache.put(hash, id);
                    return id;
                }
            }
        }

        throw new SQLException("Failed to resolve or create fingerprint for " + itemId);
    }

    public long getOrCreatePlayerNode(Connection conn, String uuid, String name, String level, double x, double y, double z) throws SQLException {
        String levelId = (level != null && !level.isBlank()) ? level : "minecraft:overworld";
        String customLabel = (name != null && !name.isBlank()) ? name : "unknown";

        String cacheKey = (uuid != null && !uuid.isBlank()) ? uuid : ("name:" + customLabel);
        Long cached = playerNodeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (uuid != null && !uuid.isBlank()) {
            String selectSql = "SELECT id FROM ig_nodes WHERE node_type = 'PLAYER' AND owner_uuid = ? LIMIT 1";
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, uuid);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }

            String insertSql = "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, x, y, z, custom_label) VALUES ('PLAYER', ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, levelId);
                pstmt.setDouble(3, x);
                pstmt.setDouble(4, y);
                pstmt.setDouble(5, z);
                pstmt.setString(6, customLabel);
                pstmt.executeUpdate();
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }
        } else {
            String selectSql = "SELECT id FROM ig_nodes WHERE node_type = 'PLAYER' AND custom_label = ? LIMIT 1";
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, customLabel);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }

            String insertSql = "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, x, y, z, custom_label) VALUES ('PLAYER', NULL, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, levelId);
                pstmt.setDouble(2, x);
                pstmt.setDouble(3, y);
                pstmt.setDouble(4, z);
                pstmt.setString(5, customLabel);
                pstmt.executeUpdate();
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }
        }

        throw new SQLException("Failed to create player node for " + customLabel);
    }

    public long getOrCreateContainerNode(Connection conn, String level, double x, double y, double z) throws SQLException {
        String levelId = (level != null && !level.isBlank()) ? level : "minecraft:overworld";
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        String cacheKey = levelId + ":" + ix + ":" + iy + ":" + iz;

        Long cached = containerNodeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String selectSql = "SELECT id FROM ig_nodes WHERE node_type = 'CONTAINER' AND level_id = ? AND x = ? AND y = ? AND z = ? LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, levelId);
            pstmt.setDouble(2, ix);
            pstmt.setDouble(3, iy);
            pstmt.setDouble(4, iz);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    containerNodeCache.put(cacheKey, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT INTO ig_nodes (node_type, level_id, x, y, z) VALUES ('CONTAINER', ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, levelId);
            pstmt.setDouble(2, ix);
            pstmt.setDouble(3, iy);
            pstmt.setDouble(4, iz);
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    containerNodeCache.put(cacheKey, id);
                    return id;
                }
            }
        }

        throw new SQLException("Failed to create container node at " + cacheKey);
    }

    public long getTotalObservationsCount() {
        if (!dbManager.isInitialized()) return 0;
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to query total observations count", e);
        }
        return 0;
    }

    public static String normalizeItemId(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return "minecraft:air";
        }
        materialName = materialName.trim();
        if (materialName.contains(":")) {
            return materialName;
        }
        return "minecraft:" + materialName;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }
}
