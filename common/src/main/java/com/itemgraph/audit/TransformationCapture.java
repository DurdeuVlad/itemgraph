package com.itemgraph.audit;

import com.google.gson.JsonObject;
import com.itemgraph.ingest.InternalObservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared submission path for item-processing transformation evidence on both
 * loaders (issue #57). Loader hooks pass already-canonicalized endpoints; this
 * class owns bounded queue submission and the {@code TRANSFORMATION_UNRESOLVED}
 * fallback emitted when a callback cannot produce complete evidence or the
 * queue rejects it.
 */
public final class TransformationCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransformationCapture.class);
    private static final AtomicBoolean WARNED_QUEUE_REJECTION = new AtomicBoolean();

    private TransformationCapture() {}

    /**
     * Builds and submits one transformation record. Builder or submission
     * failure emits {@code TRANSFORMATION_UNRESOLVED} audit evidence instead of
     * dropping the station effect silently.
     */
    public static void submit(String eventType, String playerUuid, String playerName,
                              String dimension, double x, double y, double z,
                              List<TransformationEvidence.ItemAmount> inputs,
                              boolean unobservedInputs,
                              TransformationEvidence.ItemAmount output,
                              boolean unobservedOutput,
                              TransformationEvidence.Outcome outcome,
                              String station, String captureBoundary,
                              JsonObject extraDetails) {
        try {
            boolean accepted = InternalObservationService.getInstance().submitTransformation(
                    TransformationEvidence.create(eventType, System.currentTimeMillis(),
                            playerUuid, playerName, dimension, x, y, z,
                            inputs, unobservedInputs, output, unobservedOutput, outcome,
                            station, captureBoundary, extraDetails));
            if (!accepted) {
                submitUnresolved(playerUuid, playerName, dimension, x, y, z, station, "queue_rejected");
            }
        } catch (RuntimeException captureFailure) {
            LOGGER.error("Could not record {} transformation at {},{},{} in {}",
                    eventType, x, y, z, dimension, captureFailure);
            submitUnresolved(playerUuid, playerName, dimension, x, y, z, station,
                    captureFailure.getClass().getSimpleName());
        }
    }

    /** Shorthand for completed operations whose output is fully observed. */
    public static void submit(String eventType, String playerUuid, String playerName,
                              String dimension, double x, double y, double z,
                              List<TransformationEvidence.ItemAmount> inputs,
                              boolean unobservedInputs,
                              TransformationEvidence.ItemAmount output,
                              TransformationEvidence.Outcome outcome,
                              String station, String captureBoundary,
                              JsonObject extraDetails) {
        submit(eventType, playerUuid, playerName, dimension, x, y, z,
                inputs, unobservedInputs, output, false, outcome,
                station, captureBoundary, extraDetails);
    }

    /** Emits the unresolved station-effect row; only queue loss can still drop it. */
    public static void submitUnresolved(String playerUuid, String playerName,
                                        String dimension, double x, double y, double z,
                                        String station, String reason) {
        try {
            boolean accepted = InternalObservationService.getInstance().submitAuditEvent(
                    TransformationEvidence.createUnresolved(System.currentTimeMillis(),
                            playerUuid, playerName, dimension, x, y, z, station, reason));
            if (!accepted) {
                warnQueueRejectionOnce(dimension, x, y, z);
            }
        } catch (RuntimeException unresolvedFailure) {
            LOGGER.error("Could not record unresolved transformation evidence at {},{},{} in {}",
                    x, y, z, dimension, unresolvedFailure);
        }
    }

    private static void warnQueueRejectionOnce(String dimension, double x, double y, double z) {
        if (WARNED_QUEUE_REJECTION.compareAndSet(false, true)) {
            LOGGER.error("ItemGraph rejected transformation evidence at {},{},{} in {}",
                    x, y, z, dimension);
        }
    }
}
