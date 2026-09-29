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
        String detail
) {}
