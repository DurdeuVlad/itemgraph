package com.itemgraph.api;

public record SubmissionResult(
        SubmissionStatus status,
        String sourceModId,
        long sourceEventId,
        String errorCode,
        String message) {
}
