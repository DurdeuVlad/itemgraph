package com.itemgraph.query;

import java.util.List;

/**
 * One row in the bounded cross-table lookup timeline.
 *
 * <p>The source and evidence id are kept separate because the three ItemGraph tables use
 * independent integer sequences. The evidence id is prefixed (for example,
 * {@code observation#42}) so an operator can open the correct detail command without
 * confusing an audit row with an observation or transformation row. Query classification,
 * uncertainty reason, candidates, and quantity impact remain separate from the raw row.
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
        int quantity,
        String subjectId,
        String detail,
        String evidenceClass,
        String orderingTableName,
        String orderingSourceKey,
        String sourceSha256,
        String sourcePayloadSha256,
        String reasonCode,
        List<String> candidateEvidenceIds,
        int quantityImpact,
        Long sourceGroupId,
        boolean candidateEvidenceTruncated) {

    public UnifiedEvidenceDetail(String source, String evidenceId, long timestampMs,
                                 String levelName, Double x, Double y, Double z,
                                 String playerName, String actionType, int quantity,
                                 String subjectId, String detail, String evidenceClass,
                                 String orderingTableName, String orderingSourceKey,
                                 String sourceSha256, String sourcePayloadSha256) {
        this(source, evidenceId, timestampMs, levelName, x, y, z, playerName,
                actionType, quantity, subjectId, detail, evidenceClass, orderingTableName,
                orderingSourceKey, sourceSha256, sourcePayloadSha256, null, List.of(),
                "AMBIGUOUS".equals(evidenceClass) || "UNRESOLVED".equals(evidenceClass) ? 0 : quantity,
                null, false);
    }

    public UnifiedEvidenceDetail(String source, String evidenceId, long timestampMs,
                                 String levelName, Double x, Double y, Double z,
                                 String playerName, String actionType, int quantity,
                                 String subjectId, String detail, String evidenceClass) {
        this(source, evidenceId, timestampMs, levelName, x, y, z, playerName,
                actionType, quantity, subjectId, detail, evidenceClass, null, null, null, null);
    }

    public UnifiedEvidenceDetail(String source, String evidenceId, long timestampMs,
                                 String levelName, Double x, Double y, Double z,
                                 String playerName, String actionType, int quantity,
                                 String subjectId, String detail, String evidenceClass,
                                 String orderingTableName, String orderingSourceKey) {
        this(source, evidenceId, timestampMs, levelName, x, y, z, playerName,
                actionType, quantity, subjectId, detail, evidenceClass,
                orderingTableName, orderingSourceKey, null, null);
    }

    public UnifiedEvidenceDetail {
        candidateEvidenceIds = candidateEvidenceIds == null ? List.of() : List.copyOf(candidateEvidenceIds);
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

    public UnifiedEvidenceDetail withCandidateEvidence(ObservationQueries.CandidateSet candidates) {
        if (sourceGroupId == null || candidates == null) {
            return this;
        }
        return new UnifiedEvidenceDetail(source, evidenceId, timestampMs, levelName, x, y, z,
                playerName, actionType, quantity, subjectId, detail, evidenceClass,
                orderingTableName, orderingSourceKey, sourceSha256, sourcePayloadSha256,
                reasonCode, candidates.ids(), quantityImpact, sourceGroupId, candidates.truncated());
    }
}
