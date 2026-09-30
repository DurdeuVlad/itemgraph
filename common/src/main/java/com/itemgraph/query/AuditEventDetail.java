package com.itemgraph.query;

/** One immutable row from the native non-quantity audit ledger. */
public record AuditEventDetail(
        long id,
        String eventType,
        long timestampMs,
        String playerUuid,
        String playerName,
        String levelName,
        double x,
        double y,
        double z,
        String subjectId,
        String detail,
        Long supersedingEventId,
        String supersessionReason
) {
    public AuditEventDetail(long id, String eventType, long timestampMs, String playerUuid,
                            String playerName, String levelName, double x, double y, double z,
                            String subjectId, String detail) {
        this(id, eventType, timestampMs, playerUuid, playerName, levelName, x, y, z,
                subjectId, detail, null, null);
    }
}
