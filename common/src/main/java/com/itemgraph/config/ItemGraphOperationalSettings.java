package com.itemgraph.config;

import com.itemgraph.query.QueryLimits;
import com.itemgraph.ingest.InternalObservationService;

/** Validated operator controls shared by the NeoForge and Fabric adapters. */
public record ItemGraphOperationalSettings(
        int maxPageSize,
        boolean serverSideOnly,
        int queuePollIntervalMs,
        int maxBatchSize,
        int databaseHeartbeatIntervalMs,
        boolean captureEnabled,
        String rawEvidenceRetention) {

    public static final int DEFAULT_MAX_PAGE_SIZE = 10;
    public static final int DEFAULT_QUEUE_POLL_INTERVAL_MS = 250;
    public static final int DEFAULT_MAX_BATCH_SIZE = 100;
    public static final int DEFAULT_DATABASE_HEARTBEAT_INTERVAL_MS = 30_000;

    public ItemGraphOperationalSettings {
        if (maxPageSize < 1 || maxPageSize > 100) {
            throw new IllegalArgumentException("query.max_page_size must be in [1,100]");
        }
        if (!serverSideOnly) {
            throw new IllegalArgumentException("operations.server_side_only=false is unsupported; ItemGraph is server-side only");
        }
        if (queuePollIntervalMs < 10 || queuePollIntervalMs > 5_000) {
            throw new IllegalArgumentException("ingestion.poll_interval_ms must be in [10,5000]");
        }
        if (maxBatchSize < 1 || maxBatchSize > 1_000) {
            throw new IllegalArgumentException("ingestion.max_batch_size must be in [1,1000]");
        }
        if (databaseHeartbeatIntervalMs < 1_000 || databaseHeartbeatIntervalMs > 3_600_000) {
            throw new IllegalArgumentException("operations.database_heartbeat_interval_ms must be in [1000,3600000]");
        }
        if (rawEvidenceRetention == null || !rawEvidenceRetention.trim().equalsIgnoreCase("indefinite")) {
            throw new IllegalArgumentException("retention.raw_evidence must be 'indefinite'; ItemGraph does not purge or archive raw evidence");
        }
        rawEvidenceRetention = "indefinite";
    }

    /** Applies load-time query bounds before command registration. */
    public void apply() {
        InternalObservationService.getInstance().configureOperations(
                queuePollIntervalMs, maxBatchSize, databaseHeartbeatIntervalMs, captureEnabled);
        QueryLimits.configureMaxPageSize(maxPageSize);
    }
}
