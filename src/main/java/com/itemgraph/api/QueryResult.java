package com.itemgraph.api;

public record QueryResult(
        QueryStatus status,
        FlowResult result,
        String errorCode,
        String message) {
}
