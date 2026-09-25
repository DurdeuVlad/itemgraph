package com.itemgraph.api;

public record EndpointDescriptor(
        EndpointKind kind,
        String stableKey,
        String displayName,
        WorldLocation location) {
}
