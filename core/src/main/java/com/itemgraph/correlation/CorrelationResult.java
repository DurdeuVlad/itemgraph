package com.itemgraph.correlation;

/**
 * Outcome of one correlation pass, for logging and {@code /ig status} diagnostics.
 *
 * @param success            false only if the pass aborted with an error
 * @param observationsFinalised ground-touching observations stamped {@code correlated_at}
 *                           during this pass (evaluated and never revisited)
 * @param edgesCreated       inferred ground-bridge edges written during this pass
 * @param deferred           drops whose correlation window has not closed yet; left
 *                           pending on purpose so a pickup ingested by a later cycle
 *                           can still be matched
 * @param durationMs         wall-clock duration of the pass
 * @param errorMessage       failure detail, or null on success
 */
public record CorrelationResult(
        boolean success,
        int observationsFinalised,
        int edgesCreated,
        int deferred,
        long durationMs,
        String errorMessage
) {
}
