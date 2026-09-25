package com.itemgraph.api;

public sealed interface EndpointRef permits
        PlayerEndpoint,
        WorldEndpoint,
        ExternalInventoryEndpoint,
        UnknownEndpoint {

    EndpointKind kind();
}
