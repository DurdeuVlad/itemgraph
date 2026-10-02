package com.itemgraph.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Adapter boundary for inventory and automation integrations.
 *
 * <p>Adapters call this only after their native API confirms that the outermost
 * operation committed. Simulated, rejected, and rolled-back operations produce
 * no evidence. A partial commit records exactly the accepted quantity.
 */
public final class AutomationTransferAdapter {
    private static final int MAX_QUEUE_FULL_RETRIES = 3;
    static final int MAX_PENDING_RETRIES = 128;
    private static final long RETRY_BACKOFF_MILLIS = 50;
    private static final ThreadPoolExecutor RETRY_EXECUTOR = createRetryExecutor();
    private static volatile Executor retryExecutor = RETRY_EXECUTOR;

    private AutomationTransferAdapter() {
    }

    /** Package-scoped test seam; integrations cannot replace the bounded production executor. */
    static Executor setRetryExecutorForTesting(Executor replacement) {
        if (replacement == null) {
            throw new IllegalArgumentException("retry executor is required");
        }
        Executor previous = retryExecutor;
        retryExecutor = replacement;
        return previous;
    }

    private static ThreadPoolExecutor createRetryExecutor() {
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "ItemGraph-Automation-Retry");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_RETRIES), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submit a completed transfer between two known endpoints. An empty result
     * means the API call was a simulation, rolled back, rejected, or moved zero.
     *
     * @param requestedAmount amount the caller attempted to move
     * @param movedAmount amount the native API reports as committed
     * @param committed true only after the outer transaction has committed
     * @param simulated true for preview/simulation calls
     * @param automationModId mod whose automation performed the transfer
     * @param originSlot stable slot number, or -1 when the endpoint has no slot model
     * @param destinationSlot stable slot number, or -1 when the endpoint has no slot model
     */
    public static Optional<CompletableFuture<SubmissionResult>> reportCommittedTransfer(
            ItemGraphService service,
            SourceHandle source,
            long sourceEventId,
            long timestampMs,
            String transferId,
            AutomationEndpoint origin,
            AutomationEndpoint destination,
            ItemSnapshot item,
            long requestedAmount,
            long movedAmount,
            boolean committed,
            boolean simulated,
            String automationModId,
            int originSlot,
            int destinationSlot) {
        if (service == null || source == null || origin == null || destination == null
                || item == null || requestedAmount < 0 || movedAmount < 0
                || movedAmount > requestedAmount || movedAmount > Integer.MAX_VALUE
                || originSlot < -1 || destinationSlot < -1
                || transferId == null || transferId.isBlank() || transferId.length() > 128
                || transferId.codePoints().anyMatch(Character::isISOControl)
                || !slotMatchesEndpoint(origin, originSlot)
                || !slotMatchesEndpoint(destination, destinationSlot)
                || !ApiValidation.validModId(automationModId)) {
            throw new IllegalArgumentException("invalid automation transfer evidence");
        }
        if (!source.modId().equals(automationModId)) {
            throw new IllegalArgumentException("automationModId must match the registered source mod");
        }
        if (simulated || !committed || movedAmount == 0) {
            return Optional.empty();
        }

        ItemSnapshot movedItem = ItemSnapshot.of(item.itemId(), (int) movedAmount,
                item.customName(), item.components());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("evidence_class", "observed");
        attributes.put("transfer_state", "committed");
        attributes.put("transfer_id", transferId);
        attributes.put("automation_mod_id", automationModId);
        attributes.put("requested_amount", Long.toString(requestedAmount));
        attributes.put("moved_amount", Long.toString(movedAmount));
        attributes.put("origin_slot", Integer.toString(originSlot));
        attributes.put("origin_slot_policy", origin.slotPolicy());
        attributes.put("origin_side", origin.side());
        attributes.put("destination_slot", Integer.toString(destinationSlot));
        attributes.put("destination_slot_policy", destination.slotPolicy());
        attributes.put("destination_side", destination.side());
        return Optional.of(submitWithBoundedRetry(service, source, new DirectObservation(
                sourceEventId,
                timestampMs,
                ObservationAction.TRANSFER_ITEM,
                origin.reference(),
                destination.reference(),
                movedItem,
                null,
                null,
                attributes)));
    }

    private static boolean slotMatchesEndpoint(AutomationEndpoint endpoint, int slot) {
        return slot < 0 ? endpoint.slotPolicy().equals("aggregate")
                : endpoint.slotPolicy().equals("slot:" + slot);
    }

    /**
     * Submit the observed delta for one known endpoint when the native API does
     * not expose the other endpoint. The missing end remains UNKNOWN.
     */
    public static Optional<CompletableFuture<SubmissionResult>> reportCommittedEndpointDelta(
            ItemGraphService service,
            SourceHandle source,
            long sourceEventId,
            long timestampMs,
            String transferId,
            AutomationEndpoint endpoint,
            ResourceKey<Level> dimension,
            ItemSnapshot item,
            long requestedAmount,
            long movedAmount,
            boolean inserted,
            boolean committed,
            boolean simulated,
            String automationModId,
            int slot) {
        if (service == null || source == null || endpoint == null || dimension == null || item == null
                || requestedAmount < 0
                || movedAmount < 0 || movedAmount > requestedAmount || movedAmount > Integer.MAX_VALUE
                || slot < -1 || transferId == null || transferId.isBlank() || transferId.length() > 128
                || transferId.codePoints().anyMatch(Character::isISOControl)
                || !slotMatchesEndpoint(endpoint, slot)
                || !ApiValidation.validModId(automationModId)) {
            throw new IllegalArgumentException("invalid automation endpoint delta evidence");
        }
        if (!source.modId().equals(automationModId)) {
            throw new IllegalArgumentException("automationModId must match the registered source mod");
        }
        if (simulated || !committed || movedAmount == 0) {
            return Optional.empty();
        }

        ItemSnapshot movedItem = ItemSnapshot.of(item.itemId(), (int) movedAmount,
                item.customName(), item.components());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("evidence_class", "observed");
        attributes.put("transfer_state", "committed");
        attributes.put("transfer_id", transferId);
        attributes.put("automation_mod_id", automationModId);
        attributes.put("requested_amount", Long.toString(requestedAmount));
        attributes.put("moved_amount", Long.toString(movedAmount));
        attributes.put("endpoint_slot", Integer.toString(slot));
        attributes.put("endpoint_slot_policy", endpoint.slotPolicy());
        attributes.put("endpoint_side", endpoint.side());
        UnknownEndpoint unknown = new UnknownEndpoint(dimension, "other automation endpoint not exposed by native API");
        EndpointRef origin = inserted ? unknown : endpoint.reference();
        EndpointRef destination = inserted ? endpoint.reference() : unknown;
        return Optional.of(submitWithBoundedRetry(service, source, new DirectObservation(
                sourceEventId, timestampMs, ObservationAction.TRANSFER_ITEM, origin, destination,
                movedItem, null, null, attributes)));
    }

    private static CompletableFuture<SubmissionResult> submitWithBoundedRetry(
            ItemGraphService service, SourceHandle source, DirectObservation observation) {
        CompletableFuture<SubmissionResult> result = new CompletableFuture<>();
        submitOnce(service, source, observation).whenComplete((submission, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (submission == null) {
                result.completeExceptionally(new IllegalStateException(
                        "ItemGraph service returned a null submission result"));
            } else if (submission.status() != SubmissionStatus.QUEUE_FULL) {
                result.complete(submission);
            } else {
                enqueueRetry(service, source, observation, submission, 1, result);
            }
        });
        return result;
    }

    private static CompletableFuture<SubmissionResult> submitOnce(
            ItemGraphService service, SourceHandle source, DirectObservation observation) {
        try {
            CompletableFuture<SubmissionResult> submission = service.submitObservation(source, observation);
            if (submission == null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("ItemGraph service returned a null submission future"));
            }
            return submission;
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static void enqueueRetry(ItemGraphService service, SourceHandle source,
                                     DirectObservation observation, SubmissionResult lastResult,
                                     int retryNumber, CompletableFuture<SubmissionResult> result) {
        if (retryNumber > MAX_QUEUE_FULL_RETRIES) {
            result.complete(lastResult);
            return;
        }
        try {
            retryExecutor.execute(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_BACKOFF_MILLIS * retryNumber);
                    submitOnce(service, source, observation).whenComplete((submission, failure) -> {
                        if (failure != null) {
                            result.completeExceptionally(failure);
                        } else if (submission == null) {
                            result.completeExceptionally(new IllegalStateException(
                                    "ItemGraph service returned a null submission result"));
                        } else if (submission.status() != SubmissionStatus.QUEUE_FULL) {
                            result.complete(submission);
                        } else {
                            enqueueRetry(service, source, observation, submission,
                                    retryNumber + 1, result);
                        }
                    });
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    result.complete(lastResult);
                }
            });
        } catch (RejectedExecutionException saturated) {
            result.complete(lastResult);
        }
    }
}
