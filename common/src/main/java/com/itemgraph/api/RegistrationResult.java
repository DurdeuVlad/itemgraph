package com.itemgraph.api;

public record RegistrationResult(
        RegistrationStatus status,
        SourceHandle source,
        String errorCode,
        String message) {
}
