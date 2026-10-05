package com.itemgraph.gametest;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.db.DatabaseManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Writes a redacted, bounded performance report for isolated loader GameTests. */
public final class PerformanceReportFixture {
    private PerformanceReportFixture() { }

    public static void writeIfRequested(String loader, String scenario, Map<String, Long> workload,
                                        String griefLoggerRuntimeState) {
        String configuredDirectory = System.getenv("ITEMGRAPH_PERFORMANCE_REPORT_DIR");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        if (!Set.of("fabric", "neoforge").contains(loader)) {
            throw new IllegalArgumentException("unsupported performance-report loader: " + loader);
        }
        if (scenario == null || !scenario.matches("[a-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("performance-report scenario must be a stable identifier");
        }

        OperationalMetrics.Snapshot metrics = OperationalMetrics.getInstance().snapshot();
        InternalObservationService service = InternalObservationService.getInstance();
        var settings = DatabaseManager.getInstance().getSettings();
        String backend = settings == null ? "unknown" : settings.backend().name().toLowerCase(java.util.Locale.ROOT);
        StringBuilder json = new StringBuilder(1_800);
        json.append("{\n  \"schema_version\": 3,\n  \"loader\": ").append(quote(loader))
                .append(",\n  \"scenario\": ").append(quote(scenario))
                .append(",\n  \"minecraft_version\": \"1.21.1\",")
                .append("\n  \"backend\": ").append(quote(backend)).append(',')
                .append("\n  \"grieflogger_runtime_state\": ").append(quote(griefLoggerRuntimeState)).append(',')
                .append("\n  \"workload\": {");
        TreeMap<String, Long> orderedWorkload = new TreeMap<>(workload == null ? Map.of() : workload);
        int index = 0;
        for (var entry : orderedWorkload.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z][a-z0-9_]{0,63}")
                    || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("performance workload fields must be stable non-negative numbers");
            }
            if (index++ > 0) {
                json.append(',');
            }
            json.append("\n    ").append(quote(entry.getKey())).append(": ").append(entry.getValue());
        }
        if (!orderedWorkload.isEmpty()) {
            json.append('\n');
        }
        json.append("  },\n  \"queue\": {\n    \"depth\": ").append(service.getQueueSize())
                .append(",\n    \"peak_depth\": ").append(metrics.peakQueueDepth())
                .append(",\n    \"capacity_per_type\": 10000")
                .append(",\n    \"flush_every_ticks\": ").append(service.getQueueFrequencyTicks())
                .append(",\n    \"max_batch_size\": ").append(service.getMaxBatchSize())
                .append(",\n    \"rejected_items\": ").append(metrics.queueRejectedItems())
                .append("\n  },\n  \"latency\": {\n    \"enqueue\": ")
                .append(latencyJson(metrics.enqueue()))
                .append(",\n    \"persistence_commit\": ").append(latencyJson(metrics.persistenceCommit()))
                .append(",\n    \"query\": ").append(latencyJson(metrics.query()))
                .append(",\n    \"correlation\": ").append(latencyJson(metrics.correlation()))
                .append("\n  },\n  \"persistence\": {\n    \"persisted_items\": ")
                .append(metrics.persistedItems())
                .append(",\n    \"failed_batches\": ").append(metrics.persistenceFailures())
                .append(",\n    \"largest_batch\": ").append(metrics.largestBatchSize())
                .append("\n  },\n  \"components\": {\n    \"unresolved_payload_decode_failures\": ")
                .append(metrics.unresolvedPayloadDecodeFailures())
                .append(",\n    \"decode_failure_cache_insertions\": ")
                .append(metrics.decodeFailureCacheInsertions())
                .append(",\n    \"negative_cache_hits\": ").append(metrics.decodeCacheHits())
                .append("\n  },\n  \"memory\": {\n    \"heap_used_bytes\": ").append(metrics.heapUsedBytes())
                .append(",\n    \"heap_max_bytes\": ").append(metrics.heapMaxBytes())
                .append("\n  }\n}\n");

        writeAtomically(Path.of(configuredDirectory).resolve("itemgraph-" + loader + "-" + scenario + ".json"),
                json.toString());
    }

    private static String latencyJson(OperationalMetrics.LatencySnapshot latency) {
        boolean overflow = latency.p95UpperBoundNanos() == Long.MAX_VALUE;
        return "{\"count\":" + latency.count()
                + ",\"failed\":" + latency.failed()
                + ",\"average_us\":" + latency.averageMicros()
                + ",\"max_ns\":" + latency.maxNanos()
                + ",\"p95_upper_bound_ns\":" + (overflow ? "null" : latency.p95UpperBoundNanos())
                + ",\"p95_over_10s\":" + overflow + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void writeAtomically(Path destination, String contents) {
        try {
            Files.createDirectories(destination.getParent());
            Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temporary, contents, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException unsupportedAtomicMove) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the redacted ItemGraph performance report", failure);
        }
    }
}
