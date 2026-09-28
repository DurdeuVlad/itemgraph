package com.itemgraph.query;

/**
 * One fully resolved {@code ig_observations} row.
 *
 * <p><b>This is OBSERVED evidence.</b> The event fields come from one raw source row;
 * {@link #sourceGroup()} is derived metadata that relates cross-source copies without
 * merging or discarding either raw observation. Nothing in this record is reconstructed,
 * scored or guessed. That is why {@link #kindLabel()} is a constant: an observation can
 * never be anything other than observed, and the display layer must never relabel one as
 * an inference.
 *
 * @param id              {@code ig_observations.id}, the value {@code /ig event} takes
 * @param sourceType      originating evidence source, e.g. {@code GRIEFLOGGER}
 * @param sourceEventId   the source's own row id, or null if the source had none
 * @param timestampMs     event time, epoch millis
 * @param origin          where the item came from; null only if the row is corrupt
 * @param destination     where the item went; null when the source recorded no endpoint
 * @param fingerprint     canonical item identity
 * @param actionType      normalised action, e.g. {@code DROP_ITEM}
 * @param amount          stack size moved
 * @param correlatedAtMs  when the correlation engine evaluated this row, or null if not yet
 * @param captureType     known internal capture mode when the row represents an interval
 */
public record ObservationDetail(
        long id,
        String sourceType,
        Long sourceEventId,
        long timestampMs,
        NodeRef origin,
        NodeRef destination,
        FingerprintRef fingerprint,
        String actionType,
        int amount,
        Long correlatedAtMs,
        String correlationStatus,
        String itemEntityUuid,
        Long timestampEndMs,
        String captureType,
        SourceGroup sourceGroup
) {

    public record SourceGroup(
            long id,
            String state,
            String memberRole,
            Long canonicalObservationId,
            String matchBasis,
            String explanation
    ) {}

    public ObservationDetail(
            long id,
            String sourceType,
            Long sourceEventId,
            long timestampMs,
            NodeRef origin,
            NodeRef destination,
            FingerprintRef fingerprint,
            String actionType,
            int amount,
            Long correlatedAtMs,
            String correlationStatus,
            String itemEntityUuid
    ) {
        this(id, sourceType, sourceEventId, timestampMs, origin, destination, fingerprint, actionType,
                amount, correlatedAtMs, correlationStatus, itemEntityUuid, null, null, null);
    }

    public ObservationDetail(
            long id,
            String sourceType,
            Long sourceEventId,
            long timestampMs,
            NodeRef origin,
            NodeRef destination,
            FingerprintRef fingerprint,
            String actionType,
            int amount,
            Long correlatedAtMs,
            String correlationStatus
    ) {
        this(id, sourceType, sourceEventId, timestampMs, origin, destination, fingerprint,
                actionType, amount, correlatedAtMs, correlationStatus, null, null, null, null);
    }

    public ObservationDetail(
            long id,
            String sourceType,
            Long sourceEventId,
            long timestampMs,
            NodeRef origin,
            NodeRef destination,
            FingerprintRef fingerprint,
            String actionType,
            int amount,
            Long correlatedAtMs
    ) {
        this(id, sourceType, sourceEventId, timestampMs, origin, destination, fingerprint,
                actionType, amount, correlatedAtMs, null, null, null, null, null);
    }

    /** Always {@code OBSERVED}. See the class javadoc. */
    public String kindLabel() {
        return "OBSERVED";
    }
}
