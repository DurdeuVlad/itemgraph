package com.itemgraph.api;

public enum SubmissionStatus {
    PERSISTED,
    DUPLICATE,
    INVALID_INPUT,
    QUEUE_FULL,
    DATABASE_UNAVAILABLE,
    SHUTDOWN,
    FAILED
}
