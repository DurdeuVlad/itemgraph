package com.itemgraph.api;

public enum QueryStatus {
    OK,
    NOT_FOUND,
    AMBIGUOUS,
    INVALID_INPUT,
    QUEUE_FULL,
    DATABASE_UNAVAILABLE,
    SHUTDOWN,
    FAILED
}
