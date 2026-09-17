package com.itemgraph.audit;

import java.util.List;

/**
 * Results of a forensic invariant and database integrity audit (Phase 10).
 */
public record AuditReport(
        boolean healthy,
        long totalObservations,
        long totalEdges,
        long totalAllocations,
        long totalTransformations,
        int overAllocatedObservations,
        int nonPositiveQuantities,
        int orphanedAllocations,
        int invalidEdgeNodes,
        int statusMismatches,
        List<String> violationDetails
) {}
