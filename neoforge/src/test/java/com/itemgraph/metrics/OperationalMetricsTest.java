package com.itemgraph.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalMetricsTest {
    private final OperationalMetrics metrics = OperationalMetrics.getInstance();

    @BeforeEach
    void resetBefore() {
        metrics.reset();
    }

    @AfterEach
    void resetAfter() {
        metrics.reset();
    }

    @Test
    void recordsBoundedLatencyBucketsAndPercentileWithoutEventData() {
        for (int i = 0; i < 19; i++) {
            metrics.recordQuery(100_000L, true);
        }
        metrics.recordQuery(2_000_000L, false);

        OperationalMetrics.LatencySnapshot query = metrics.snapshot().query();
        assertEquals(20, query.count());
        assertEquals(1, query.failed());
        assertEquals(2_000_000L, query.maxNanos());
        assertEquals(100_000L, query.p95UpperBoundNanos());
    }

    @Test
    void reportsNoPercentileForMetricsWithNoSamples() {
        OperationalMetrics.LatencySnapshot query = metrics.snapshot().query();
        assertEquals(0, query.count());
        assertEquals(0, query.p95UpperBoundNanos());
    }

    @Test
    void tracksPersistenceSuccessFailureQueuePressureAndDecodeOutcomesSeparately() {
        metrics.recordPersistenceBatch(32, 5_000_000L, true);
        metrics.recordPersistenceBatch(4, 12_000_000L, false);
        metrics.recordQueueDepth(71);
        metrics.recordQueueDepth(12);
        metrics.recordQueueRejection(4);
        metrics.recordUnresolvedPayloadDecodeFailure();
        metrics.recordUnresolvedPayloadDecodeFailure();
        metrics.recordDecodeFailureCacheInsertion();
        metrics.recordDecodeCacheHit();

        OperationalMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(32, snapshot.persistedItems());
        assertEquals(1, snapshot.persistenceFailures());
        assertEquals(32, snapshot.largestBatchSize());
        assertEquals(71, snapshot.peakQueueDepth());
        assertEquals(4, snapshot.queueRejectedItems());
        assertEquals(2, snapshot.unresolvedPayloadDecodeFailures());
        assertEquals(1, snapshot.decodeFailureCacheInsertions());
        assertEquals(1, snapshot.decodeCacheHits());
        assertTrue(snapshot.heapUsedBytes() >= 0);
    }
}
