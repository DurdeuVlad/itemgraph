package com.itemgraph.ingest;

public record SourceCheckpoint(
        String sourceName,
        long lastSourceRowid,
        long lastTimestamp,
        long updatedAt
) {}
