package com.itemgraph.query;

import com.itemgraph.audit.EventTaxonomy;
import java.util.List;

/**
 * One fully resolved {@code ig_observations} row.
 *
 * <p>The event fields come from one raw source row;
 * {@link #sourceGroup()} is derived metadata that relates cross-source copies without
 * merging or discarding either raw observation. Nothing in this record is reconstructed,
 * scored or guessed. A disposition can mark retained raw evidence as unresolved and
 * excluded from current flow claims; it does not change or erase the original observation.
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
        SourceGroup sourceGroup,
        String dispositionReason
) {

    public record SourceGroup(
            long id,
            String state,
            String memberRole,
            Long canonicalObservationId,
            String matchBasis,
            String explanation,
            List<String> candidateEvidenceIds,
            boolean candidatesTruncated
    ) {
        public SourceGroup(long id, String state, String memberRole, Long canonicalObservationId,
                           String matchBasis, String explanation) {
            this(id, state, memberRole, canonicalObservationId, matchBasis, explanation, List.of(), false);
        }

        public SourceGroup {
            candidateEvidenceIds = candidateEvidenceIds == null ? List.of() : List.copyOf(candidateEvidenceIds);
        }
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
            String correlationStatus,
            String itemEntityUuid
    ) {
        this(id, sourceType, sourceEventId, timestampMs, origin, destination, fingerprint, actionType,
                amount, correlatedAtMs, correlationStatus, itemEntityUuid, null, null, null, null);
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
            String correlationStatus,
            String itemEntityUuid,
            Long timestampEndMs,
            String captureType,
            SourceGroup sourceGroup
    ) {
        this(id, sourceType, sourceEventId, timestampMs, origin, destination, fingerprint,
                actionType, amount, correlatedAtMs, correlationStatus, itemEntityUuid,
                timestampEndMs, captureType, sourceGroup, null);
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
                actionType, amount, correlatedAtMs, correlationStatus, null, null, null, null, null);
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
                actionType, amount, correlatedAtMs, null, null, null, null, null, null);
    }

    /** Classification for query output; it never changes the persisted raw row. */
    public EventTaxonomy.EvidenceClass evidenceClass() {
        if (dispositionReason != null) {
            return EventTaxonomy.EvidenceClass.UNRESOLVED;
        }
        if (fingerprint != null && fingerprint.componentDecodeFailed()) {
            return EventTaxonomy.EvidenceClass.UNRESOLVED;
        }
        if (fingerprint == null || !fingerprint.resolved()) {
            return EventTaxonomy.EvidenceClass.UNRESOLVED;
        }
        if (unknownEndpoint(origin) || unknownEndpoint(destination)) {
            return EventTaxonomy.EvidenceClass.UNRESOLVED;
        }
        if (sourceGroup != null && "AMBIGUOUS".equals(sourceGroup.state())) {
            return EventTaxonomy.EvidenceClass.AMBIGUOUS;
        }
        return EventTaxonomy.EvidenceClass.OBSERVED;
    }

    /** Stable explanation code for non-conclusive movement rows. */
    public String reasonCode() {
        if (dispositionReason != null) {
            return dispositionReason;
        }
        if (fingerprint != null && fingerprint.componentDecodeFailed()) {
            return "COMPONENT_DECODE_FAILED";
        }
        if (fingerprint == null || !fingerprint.resolved()) {
            return "ITEM_FINGERPRINT_UNRESOLVED";
        }
        if (unknownEndpoint(origin) || unknownEndpoint(destination)) {
            return "UNKNOWN_ENDPOINT";
        }
        if (sourceGroup != null && "AMBIGUOUS".equals(sourceGroup.state())) {
            return "SOURCE_EQUIVALENCE_AMBIGUOUS";
        }
        return null;
    }

    /** A non-conclusive row contributes no allocated movement quantity. */
    public int quantityImpact() {
        return evidenceClass() == EventTaxonomy.EvidenceClass.OBSERVED ? amount : 0;
    }

    private static boolean unknownEndpoint(NodeRef endpoint) {
        return endpoint == null || !endpoint.resolved() || "UNKNOWN".equals(endpoint.nodeType());
    }

    public ObservationDetail withCandidateEvidenceIds(List<String> candidateIds, boolean truncated) {
        if (sourceGroup == null) {
            return this;
        }
        SourceGroup enriched = new SourceGroup(sourceGroup.id(), sourceGroup.state(), sourceGroup.memberRole(),
                sourceGroup.canonicalObservationId(), sourceGroup.matchBasis(), sourceGroup.explanation(),
                candidateIds, truncated);
        return new ObservationDetail(id, sourceType, sourceEventId, timestampMs, origin, destination,
                fingerprint, actionType, amount, correlatedAtMs, correlationStatus, itemEntityUuid,
                timestampEndMs, captureType, enriched, dispositionReason);
    }

    /** Retired pre-use quantity rows remain visible as unresolved evidence. */
    public String kindLabel() {
        return evidenceClass().name();
    }
}
