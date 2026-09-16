package com.itemgraph.query;

import java.util.List;

/**
 * One fully resolved {@code ig_inferred_edges} row together with every observation cited
 * as evidence for it.
 *
 * <p><b>This is an INFERENCE, not evidence.</b> The edge itself is a claim ItemGraph made
 * about two separate observations; only the rows in {@link #evidence()} are observed
 * facts. The charter requires that an admin asking "why does ItemGraph think this
 * transfer happened?" receives the exact supporting observations and the scoring factors,
 * so this record deliberately carries both: {@link #explanation()} is the scoring
 * narrative written by the correlation engine at inference time (never regenerated here,
 * because a regenerated explanation could silently disagree with the stored confidence),
 * and {@link #evidence()} is the raw material underneath it.
 *
 * @param id           {@code ig_inferred_edges.id}, the value {@code /ig explain} takes
 * @param from         claimed origin of the transfer
 * @param to           claimed destination of the transfer
 * @param fingerprint  canonical item identity
 * @param amount       claimed quantity moved
 * @param timeStart    earliest supporting observation, epoch millis
 * @param timeEnd      latest supporting observation, epoch millis
 * @param confidence   deterministic score in [0, 1]; never 1.0 for a ground bridge
 * @param explanation  the scoring narrative stored on the row at inference time
 * @param createdAtMs  when the edge was written
 * @param evidence     the observations cited by {@code ig_edge_evidence}, chronological
 * @param evidenceTruncated true if more evidence rows exist than were loaded
 */
public record EdgeExplanation(
        long id,
        NodeRef from,
        NodeRef to,
        FingerprintRef fingerprint,
        int amount,
        long timeStart,
        long timeEnd,
        double confidence,
        String explanation,
        long createdAtMs,
        List<ObservationDetail> evidence,
        boolean evidenceTruncated
) {

    public EdgeExplanation {
        evidence = List.copyOf(evidence);
    }

    /** Always {@code INFERRED}. See the class javadoc. */
    public String kindLabel() {
        return "INFERRED";
    }

    /** Elapsed time the claimed transfer spans. */
    public long spanMs() {
        return timeEnd - timeStart;
    }
}
