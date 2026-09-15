package com.itemgraph.ingest;

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
    private final NodeManager nodeManager;

    private final Map<String, Long> fingerprintCache = new ConcurrentHashMap<>();

    private ScheduledExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile IngestionResult lastResult = null;
    private volatile long lastRunTimestamp = 0;

    public IngestionService() {
        this(new GriefLoggerAdapter(), DatabaseManager.getInstance());
    }

    public IngestionService(GriefLoggerAdapter adapter, DatabaseManager dbManager) {
        this(adapter, dbManager, new NodeManager());
    }

    public IngestionService(GriefLoggerAdapter adapter, DatabaseManager dbManager, NodeManager nodeManager) {
        this.adapter = adapter;
        this.dbManager = dbManager;
        this.nodeManager = nodeManager;
    }

    public static IngestionService getInstance() {
        return INSTANCE;
    }

    public NodeManager getNodeManager() {
        return nodeManager;
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
                            long fingerprintId = getOrCreateFingerprint(igConn, event.materialName(), event.rawData());

                            String actionType = ItemActionMapping.getActionName(event.actionId());

                            long nodeId;
                            Long targetNodeId = null;

                            if (isContainerTable) {
                                // For containers, node_id is the container block location
                                nodeId = nodeManager.getOrCreateContainerNode(igConn, event.levelName(), event.x(), event.y(), event.z());
                                // target_node_id is the player interacting with the container if available
                                if (event.userUuid() != null || event.userName() != null) {
                                    targetNodeId = nodeManager.getOrCreatePlayerNode(igConn, event.userUuid(), event.userName(), event.levelName(), event.x(), event.y(), event.z());
                                }
                            } else {
                                ItemEndpoints endpoints = resolveItemEndpoints(igConn, event, actionType);
                                nodeId = endpoints.nodeId();
                                targetNodeId = endpoints.targetNodeId();
                            }

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

    /** Resolved (origin, destination) node pair for a single observation. */
    private record ItemEndpoints(long nodeId, Long targetNodeId) {
    }

    /**
     * Direction convention for ig_observations (Phase 4).
     *
     * <p><b>node_id = ORIGIN (where the item came FROM), target_node_id = DESTINATION
     * (where the item went TO).</b> Every observation is read as a directed
     * {@code node_id -> target_node_id} flow of one fingerprint at one timestamp.
     *
     * <p>This is a modeling decision, not something GriefLogger states. GriefLogger's
     * items table is player-centric: every row records an action a player performed,
     * with the player as the only named party. Phases 2-3 mirrored that literally
     * (node_id = the acting player, target_node_id = NULL for every action), which
     * loses direction entirely — a DROP_ITEM and the PICKUP_ITEM that later recovers
     * the same stack shared no node, so correlation had nothing to join on. Naming the
     * implied second endpoint is what makes the MVP chain
     * (chest -> player -> ground -> player -> chest) traversable.
     *
     * <p>Per-action mapping, derived from ItemActionMapping's ADD/REMOVE semantics:
     * <ul>
     *   <li>DROP_ITEM, THROW_ITEM, SHOOT_ITEM: player -> GROUND. The item leaves the
     *       player into the world at the logged coordinates, where a later PICKUP_ITEM
     *       at the same block can pick it back up.</li>
     *   <li>PICKUP_ITEM: GROUND -> player. The mirror image of a drop, and the join
     *       that closes the drop/pickup pair.</li>
     *   <li>ADD_ITEM, ADD_ITEM_ENDER: UNKNOWN -> player. The item entered the player's
     *       (or their ender chest's) inventory, but the items table alone names no
     *       physical source, so the origin is explicitly unresolved rather than guessed.
     *       The containers table supplies the real source for container-sourced adds;
     *       Phase 5 correlation is what links the two.</li>
     *   <li>CRAFT_ITEM: UNKNOWN -> player. Only the crafted output is this event's
     *       concern; the consumed materials are a transformation (Phase 9), not a
     *       transfer from a known node.</li>
     *   <li>REMOVE_ITEM, REMOVE_ITEM_ENDER, BREAK_ITEM, CONSUME_ITEM: player -> UNKNOWN.
     *       The item left the player's inventory or ceased to exist, with no further
     *       evidence in this table about where it went.</li>
     *   <li>Any unrecognized action id: player -> NULL. No direction is claimed at all,
     *       because we cannot honestly infer one.</li>
     * </ul>
     *
     * <p>UNKNOWN is a real, queryable sentinel node per level, not a NULL. That keeps
     * "we know the item left the player but not to where" distinct from "this row makes
     * no topological claim", which the forensic-integrity rules require.
     */
    private ItemEndpoints resolveItemEndpoints(Connection conn, GriefLoggerRawEvent event, String actionType) throws SQLException {
        long player = nodeManager.getOrCreatePlayerNode(
                conn, event.userUuid(), event.userName(), event.levelName(), event.x(), event.y(), event.z());

        return switch (actionType) {
            // Item leaves the player into the world at the logged position.
            case "DROP_ITEM", "THROW_ITEM", "SHOOT_ITEM" -> new ItemEndpoints(
                    player,
                    nodeManager.getOrCreateGroundNode(conn, event.levelName(), event.x(), event.y(), event.z()));

            // Item enters the player from the world at the logged position.
            case "PICKUP_ITEM" -> new ItemEndpoints(
                    nodeManager.getOrCreateGroundNode(conn, event.levelName(), event.x(), event.y(), event.z()),
                    player);

            // Item enters the player from a source this table does not name.
            case "ADD_ITEM", "ADD_ITEM_ENDER", "CRAFT_ITEM" -> new ItemEndpoints(
                    nodeManager.getOrCreateUnknownNode(conn, event.levelName()),
                    player);

            // Item leaves the player with no further evidence of a destination.
            case "REMOVE_ITEM", "REMOVE_ITEM_ENDER", "BREAK_ITEM", "CONSUME_ITEM" -> new ItemEndpoints(
                    player,
                    nodeManager.getOrCreateUnknownNode(conn, event.levelName()));

            // Unrecognized action id (e.g. a GriefLogger version added a new action):
            // record the observation anchored to the player but claim no direction.
            default -> {
                LOGGER.debug("No direction mapping for item action '{}' (GriefLogger rowid {}); recording without a target node.",
                        actionType, event.rowid());
                yield new ItemEndpoints(player, null);
            }
        };
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

    public long getOrCreateFingerprint(Connection conn, String materialName, byte[] rawData) throws SQLException {
        com.itemgraph.canon.CanonicalItem canonical = com.itemgraph.canon.ItemCanonicalizer.canonicalize(materialName, rawData);
        String itemId = canonical.itemId();
        String hash = canonical.fingerprintHash();

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

        String insertSql = "INSERT OR IGNORE INTO ig_item_fingerprints (item_id, fingerprint_hash, custom_name, rarity, component_summary) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, itemId);
            pstmt.setString(2, hash);
            pstmt.setString(3, canonical.customName());
            pstmt.setString(4, canonical.rarity());
            pstmt.setString(5, canonical.componentSummary());
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

    /**
     * Delegates to {@link NodeManager}. Kept on IngestionService so existing callers
     * and tests keep working; all node identity logic now lives in the graph package.
     */
    public long getOrCreatePlayerNode(Connection conn, String uuid, String name, String level, double x, double y, double z) throws SQLException {
        return nodeManager.getOrCreatePlayerNode(conn, uuid, name, level, x, y, z);
    }

    /** Delegates to {@link NodeManager}. */
    public long getOrCreateContainerNode(Connection conn, String level, double x, double y, double z) throws SQLException {
        return nodeManager.getOrCreateContainerNode(conn, level, x, y, z);
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

}
