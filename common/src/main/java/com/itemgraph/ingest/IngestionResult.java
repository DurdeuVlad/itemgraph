package com.itemgraph.ingest;

public record IngestionResult(
        boolean success,
        int itemsIngested,
        int containersIngested,
        long durationMs,
        String errorMessage
) {
    public int totalIngested() {
        return itemsIngested + containersIngested;
    }
}
