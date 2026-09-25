package com.itemgraph.api;

import java.util.List;

public record FlowHop(
        Provenance provenance,
        EvidenceRef evidence,
        EndpointDescriptor origin,
        EndpointDescriptor destination,
        ItemDescriptor item,
        int amount,
        long timestampStartMs,
        long timestampEndMs,
        Double confidence,
        String detail,
        String explanation,
        List<EvidenceRef> supportingEvidence,
        boolean supportingEvidenceTruncated) {

    public FlowHop {
        supportingEvidence = supportingEvidence == null
                ? List.of()
                : List.copyOf(supportingEvidence);
    }
}
