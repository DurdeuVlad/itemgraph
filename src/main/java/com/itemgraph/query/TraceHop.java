package com.itemgraph.query;

import java.util.Comparator;

/**
 * One hop in a reconstructed item timeline.
 *
 * <p>A hop is either a directly observed movement (one {@code ig_observations} row) or an
 * inferred one (one {@code ig_inferred_edges} row). Both kinds are shown in the same
 * timeline because that is what an admin actually needs to see — the item's path — but
 * {@link #kind()} is carried on every single hop and is never optional, so the two can
 * never be confused. The charter's evidence/inference boundary is a property of each row,
 * not a caveat printed once at the top of the output.
 *
 * <p>After the Phase 4/5 direction fixes most hops are OBSERVED: a container withdrawal,
 * a deposit and a drop are each a single fully evidenced row. INFERRED hops are
 * specifically the ground bridges, where nothing in the raw evidence states that the
 * stack one player dropped is the stack another player picked up.
 *
 * @param kind         OBSERVED or INFERRED, never null
 * @param refId        {@code ig_observations.id} for OBSERVED, {@code ig_inferred_edges.id} for INFERRED
 * @param origin       where the item came from, may be null for a corrupt observation row
 * @param destination  where the item went, may be null when the source recorded no endpoint
 * @param amount       stack size moved
 * @param timestampMs  when the hop happened (OBSERVED) or started (INFERRED)
 * @param endMs        event interval end for a session net delta, {@code timestampMs} for point OBSERVED evidence, or {@code time_end} for INFERRED
 * @param confidence   null for OBSERVED (evidence is not scored), the stored score for INFERRED
 * @param detail       action type for OBSERVED, short inference description for INFERRED
 */
public record TraceHop(
        Kind kind,
        long refId,
        NodeRef origin,
        NodeRef destination,
        int amount,
        long timestampMs,
        long endMs,
        Double confidence,
        String detail,
        FingerprintRef item
) {

    public enum Kind {
        /** Directly evidenced by a single raw observation row. */
        OBSERVED,
        /** Reconstructed by the correlation engine from several observations. */
        INFERRED
    }

    public TraceHop(
            Kind kind,
            long refId,
            NodeRef origin,
            NodeRef destination,
            int amount,
            long timestampMs,
            long endMs,
            Double confidence,
            String detail
    ) {
        this(kind, refId, origin, destination, amount, timestampMs, endMs, confidence, detail, null);
    }

    /**
     * Chronological ordering for the merged timeline.
     *
     * <p>Ties break OBSERVED-before-INFERRED and then by id, purely so the same database
     * always produces byte-identical output: a forensic tool whose row order drifts
     * between invocations is not one an admin can quote in an incident write-up.
     */
    public static final Comparator<TraceHop> CHRONOLOGICAL =
            Comparator.comparingLong(TraceHop::timestampMs)
                    .thenComparing(hop -> hop.kind().ordinal())
                    .thenComparingLong(TraceHop::refId);

    public static TraceHop observed(ObservationDetail obs) {
        String detail = obs.actionType();
        if (obs.sourceGroup() != null) {
            ObservationDetail.SourceGroup group = obs.sourceGroup();
            detail += " [source group #" + group.id() + " " + group.state() + "]";
            if (group.canonicalObservationId() != null) {
                detail += " corroborates observation#" + group.canonicalObservationId();
            }
        }
        if ("container_session_net_delta".equals(obs.captureType())) {
            detail += " [session net delta]";
        } else if ("queue_overflow_recovery".equals(obs.captureType())) {
            detail += " [queue overflow recovery]";
        }
        return new TraceHop(
                Kind.OBSERVED,
                obs.id(),
                obs.origin(),
                obs.destination(),
                obs.amount(),
                obs.timestampMs(),
                obs.timestampEndMs() == null ? obs.timestampMs() : obs.timestampEndMs(),
                null,
                detail,
                obs.fingerprint()
        );
    }

    public static TraceHop inferred(EdgeExplanation edge) {
        return new TraceHop(
                Kind.INFERRED,
                edge.id(),
                edge.from(),
                edge.to(),
                edge.amount(),
                edge.timeStart(),
                edge.timeEnd(),
                edge.confidence(),
                "inferred transfer spanning " + QueryFormatter.formatDuration(edge.spanMs()),
                edge.fingerprint()
        );
    }
}
