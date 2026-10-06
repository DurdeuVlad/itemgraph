package com.itemgraph.query;

/**
 * One row in the bounded cross-table lookup timeline.
 *
 * <p>The source and evidence id are kept separate because the three ItemGraph tables use
 * independent integer sequences. The evidence id is prefixed (for example,
 * {@code observation#42}) so an operator can open the correct detail command without
 * confusing an audit row with an observation or transformation row. Every row returned by
 * this service is raw evidence and carries the class assigned by its taxonomy definition,
 * including {@code UNRESOLVED} for unsupported legacy event types.
 */
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
        Integer quantity,
        String subjectId,
        String detail,
        String evidenceClass,
        String orderingTableName,
        String orderingSourceKey) {

    public UnifiedEvidenceDetail(String source, String evidenceId, long timestampMs,
                                 String levelName, Double x, Double y, Double z,
                                 String playerName, String actionType, Integer quantity,
                                 String subjectId, String detail, String evidenceClass) {
        this(source, evidenceId, timestampMs, levelName, x, y, z, playerName,
                actionType, quantity, subjectId, detail, evidenceClass, null, null);
    }

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
