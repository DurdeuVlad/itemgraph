package com.itemgraph.metrics;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, privacy-safe runtime measurements for ItemGraph's async pipeline. */
public final class OperationalMetrics {
    private static final long[] LATENCY_BUCKET_UPPER_BOUNDS_NANOS = {
            100_000L, 250_000L, 500_000L, 1_000_000L, 2_500_000L,
            5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L,
            100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L,
            2_500_000_000L, 5_000_000_000L, 10_000_000_000L, Long.MAX_VALUE
    };

    private static final OperationalMetrics INSTANCE = new OperationalMetrics();

    private final Latency persistenceCommit = new Latency();
    private final Latency query = new Latency();
    private final Latency correlation = new Latency();
    private final Latency enqueue = new Latency();
    private final LongAdder persistedItems = new LongAdder();
    private final LongAdder persistenceFailures = new LongAdder();
    private final LongAdder queueRejectedItems = new LongAdder();
    private final LongAdder unresolvedPayloadDecodeFailures = new LongAdder();
    private final LongAdder decodeFailureCacheInsertions = new LongAdder();
    private final LongAdder decodeCacheHits = new LongAdder();
    private final AtomicLong peakQueueDepth = new AtomicLong();
    private final AtomicLong largestBatchSize = new AtomicLong();

    private OperationalMetrics() { }

    public static OperationalMetrics getInstance() {
        return INSTANCE;
    }

    public void recordPersistenceBatch(int itemCount, long elapsedNanos, boolean succeeded) {
        persistenceCommit.record(elapsedNanos, succeeded);
        if (succeeded) {
            persistedItems.add(itemCount);
            largestBatchSize.accumulateAndGet(itemCount, Math::max);
        } else {
            persistenceFailures.increment();
        }
    }

    public void recordQuery(long elapsedNanos, boolean succeeded) {
        query.record(elapsedNanos, succeeded);
    }

    public void recordCorrelation(long elapsedNanos, boolean succeeded) {
        correlation.record(elapsedNanos, succeeded);
    }

    public void recordEnqueue(long elapsedNanos, boolean accepted) {
        enqueue.record(elapsedNanos, accepted);
    }

    public void recordQueueDepth(long depth) {
        peakQueueDepth.accumulateAndGet(Math.max(0L, depth), Math::max);
    }

    public void recordQueueRejection(long rejectedItems) {
        queueRejectedItems.add(Math.max(0L, rejectedItems));
    }

    public void recordDecodeFailureCacheInsertion() {
        decodeFailureCacheInsertions.increment();
    }

    /** Counts every malformed component-payload encounter, including negative-cache hits. */
    public void recordUnresolvedPayloadDecodeFailure() {
        unresolvedPayloadDecodeFailures.increment();
    }

    public void recordDecodeCacheHit() {
        decodeCacheHits.increment();
    }

    /** Snapshot contains counts and timings only; it never includes evidence or identity data. */
    public Snapshot snapshot() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new Snapshot(
                enqueue.snapshot(), persistenceCommit.snapshot(), query.snapshot(), correlation.snapshot(),
                persistedItems.sum(), persistenceFailures.sum(), queueRejectedItems.sum(),
                peakQueueDepth.get(), largestBatchSize.get(), unresolvedPayloadDecodeFailures.sum(),
                decodeFailureCacheInsertions.sum(),
                decodeCacheHits.sum(), heap.getUsed(), heap.getMax());
    }

    /** Test and server-lifecycle hook. Production never resets metrics while workers are live. */
    public void reset() {
        enqueue.reset();
        persistenceCommit.reset();
        query.reset();
        correlation.reset();
        persistedItems.reset();
        persistenceFailures.reset();
        queueRejectedItems.reset();
        unresolvedPayloadDecodeFailures.reset();
        decodeFailureCacheInsertions.reset();
        decodeCacheHits.reset();
        peakQueueDepth.set(0L);
        largestBatchSize.set(0L);
    }

    public record Snapshot(
            LatencySnapshot enqueue,
            LatencySnapshot persistenceCommit,
            LatencySnapshot query,
            LatencySnapshot correlation,
            long persistedItems,
            long persistenceFailures,
            long queueRejectedItems,
            long peakQueueDepth,
            long largestBatchSize,
            long unresolvedPayloadDecodeFailures,
            long decodeFailureCacheInsertions,
            long decodeCacheHits,
            long heapUsedBytes,
            long heapMaxBytes) {
    }

    public record LatencySnapshot(long count, long failed, long totalNanos, long maxNanos,
                                  long p95UpperBoundNanos) {
        public long averageMicros() {
            return count == 0 ? 0 : totalNanos / count / 1_000L;
        }

        public long maxMillis() {
            return maxNanos / 1_000_000L;
        }

        public long p95UpperBoundMillis() {
            return p95UpperBoundNanos == Long.MAX_VALUE
                    ? Long.MAX_VALUE : (p95UpperBoundNanos + 999_999L) / 1_000_000L;
        }
    }

    private static final class Latency {
        private final LongAdder count = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLongArray buckets = new AtomicLongArray(LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length);

        void record(long elapsedNanos, boolean succeeded) {
            long duration = Math.max(0L, elapsedNanos);
            count.increment();
            totalNanos.add(duration);
            maxNanos.accumulateAndGet(duration, Math::max);
            if (!succeeded) {
                failed.increment();
            }
            for (int i = 0; i < LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length; i++) {
                if (duration <= LATENCY_BUCKET_UPPER_BOUNDS_NANOS[i]) {
                    buckets.incrementAndGet(i);
                    break;
                }
            }
        }

        LatencySnapshot snapshot() {
            long samples = count.sum();
            if (samples == 0) {
                return new LatencySnapshot(0, failed.sum(), totalNanos.sum(), maxNanos.get(), 0L);
            }
            long target = samples == 0 ? 0 : (samples * 95L + 99L) / 100L;
            long cumulative = 0L;
            long percentile = 0L;
            for (int i = 0; i < LATENCY_BUCKET_UPPER_BOUNDS_NANOS.length; i++) {
                cumulative += buckets.get(i);
                if (cumulative >= target) {
                    percentile = LATENCY_BUCKET_UPPER_BOUNDS_NANOS[i];
                    break;
                }
            }
            return new LatencySnapshot(samples, failed.sum(), totalNanos.sum(), maxNanos.get(), percentile);
        }

        void reset() {
            count.reset();
            failed.reset();
            totalNanos.reset();
            maxNanos.set(0L);
            for (int i = 0; i < buckets.length(); i++) {
                buckets.set(i, 0L);
            }
        }
    }
}
