package com.itemgraph.query;

/**
 * One fully resolved {@code ig_observations} row.
 *
 * <p><b>This is OBSERVED evidence.</b> Every field here came from a single raw row that
 * ItemGraph derived directly from a GriefLogger event; nothing in this record is
 * reconstructed, scored or guessed. That is why {@link #kindLabel()} is a constant:
 * an observation can never be anything other than observed, and the display layer must
 * never be able to accidentally relabel one as an inference.
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
        Long correlatedAtMs
) {

    /** Always {@code OBSERVED}. See the class javadoc. */
    public String kindLabel() {
        return "OBSERVED";
    }
}
