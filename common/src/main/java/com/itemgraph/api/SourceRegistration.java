package com.itemgraph.api;

public record SourceRegistration(
        String modId,
        String displayName) {

    public static SourceRegistration of(String modId, String displayName) {
        return new SourceRegistration(modId, displayName);
    }
}
