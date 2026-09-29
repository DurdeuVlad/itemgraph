package com.itemgraph.query;

/**
 * One row in the bounded cross-table lookup timeline.
 *
 * <p>The source and evidence id are kept separate because the three ItemGraph tables use
 * independent integer sequences. The evidence id is prefixed (for example,
 * {@code observation#42}) so an operator can open the correct detail command without
 * confusing an audit row with an observation or transformation row. Every row returned by
 * this service is raw evidence and therefore carries the {@code OBSERVED} class.</n+ */
public record UnifiedEvidenceDetail(
        String source,
        String evidenceId,
        long timestampMs,
        String levelName,
        Double x,
        Double y,
        Double z,
        String playerName,
        String actionType,
        int quantity,
        String subjectId,
        String detail,
        String evidenceClass) {

    public UnifiedEvidenceDetail {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        if (evidenceId == null || evidenceId.isBlank()) {
            throw new IllegalArgumentException("evidence id is required");
        }
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("action type is required");
        }
        if (evidenceClass == null || evidenceClass.isBlank()) {
            throw new IllegalArgumentException("evidence class is required");
        }
    }
}
