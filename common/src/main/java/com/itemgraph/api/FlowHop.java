package com.itemgraph.api;

import com.itemgraph.audit.EventTaxonomy;

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
        boolean supportingEvidenceTruncated,
        EventTaxonomy.EvidenceClass evidenceClass,
        String reasonCode,
        List<String> candidateEvidenceIds,
        boolean candidateEvidenceTruncated,
        int quantityImpact) {

    public FlowHop(Provenance provenance, EvidenceRef evidence,
                   EndpointDescriptor origin, EndpointDescriptor destination,
                   ItemDescriptor item, int amount, long timestampStartMs, long timestampEndMs,
                   Double confidence, String detail, String explanation,
                   List<EvidenceRef> supportingEvidence, boolean supportingEvidenceTruncated) {
        this(provenance, evidence, origin, destination, item, amount, timestampStartMs,
                timestampEndMs, confidence, detail, explanation, supportingEvidence,
                supportingEvidenceTruncated,
                provenance == Provenance.INFERRED ? EventTaxonomy.EvidenceClass.INFERRED
                        : EventTaxonomy.EvidenceClass.OBSERVED,
                null, List.of(), false, amount);
    }

    public FlowHop {
        supportingEvidence = supportingEvidence == null
                ? List.of()
                : List.copyOf(supportingEvidence);
        candidateEvidenceIds = candidateEvidenceIds == null ? List.of() : List.copyOf(candidateEvidenceIds);
        if (evidenceClass == null) {
            throw new IllegalArgumentException("evidence class is required");
        }
        if ((evidenceClass == EventTaxonomy.EvidenceClass.AMBIGUOUS
                || evidenceClass == EventTaxonomy.EvidenceClass.UNRESOLVED) && quantityImpact != 0) {
            throw new IllegalArgumentException("ambiguous and unresolved flow results cannot allocate quantity");
        }
        if ((evidenceClass == EventTaxonomy.EvidenceClass.AMBIGUOUS
                || evidenceClass == EventTaxonomy.EvidenceClass.UNRESOLVED)
                && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("ambiguous and unresolved flow results require a reason code");
        }
    }
}
