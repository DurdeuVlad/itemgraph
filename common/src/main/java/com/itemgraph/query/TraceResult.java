package com.itemgraph.query;

import com.itemgraph.audit.EventTaxonomy;
import java.util.List;

/**
 * The reconstructed timeline for one item fingerprint.
 *
 * @param fingerprint  the item being traced; unresolved if the id does not exist
 * @param hops         merged OBSERVED + INFERRED hops, chronological, already limit-capped
 * @param window       the time bound applied
 * @param appliedLimit the limit actually used after clamping
 * @param requestedLimit what the caller asked for, so the output can say it was capped
 * @param truncated    true if more hops matched than were returned
 */
public record TraceResult(
        String targetDescription,
        FingerprintRef fingerprint,
        List<TraceHop> hops,
        QueryWindow window,
        int appliedLimit,
        int requestedLimit,
        boolean truncated
) {

    public TraceResult {
        hops = List.copyOf(hops);
    }

    public TraceResult(
            FingerprintRef fingerprint,
            List<TraceHop> hops,
            QueryWindow window,
            int appliedLimit,
            int requestedLimit,
            boolean truncated
    ) {
        this(
                fingerprint != null ? fingerprint.describeFull() : "item",
                fingerprint,
                hops,
                window,
                appliedLimit,
                requestedLimit,
                truncated
        );
    }

    public boolean limitWasCapped() {
        return requestedLimit > appliedLimit;
    }

    public long observedCount() {
        return hops.stream().filter(h -> h.evidenceClass() == EventTaxonomy.EvidenceClass.OBSERVED).count();
    }

    public long inferredCount() {
        return hops.stream().filter(h -> h.evidenceClass() == EventTaxonomy.EvidenceClass.INFERRED).count();
    }

    public long ambiguousCount() {
        return hops.stream().filter(h -> h.evidenceClass() == EventTaxonomy.EvidenceClass.AMBIGUOUS).count();
    }

    public long unresolvedCount() {
        return hops.stream().filter(h -> h.evidenceClass() == EventTaxonomy.EvidenceClass.UNRESOLVED).count();
    }
}
