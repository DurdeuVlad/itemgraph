package com.itemgraph.api;

import java.util.UUID;

public record PlayerEndpoint(
        UUID playerUuid,
        String displayName) implements EndpointRef {

    @Override
    public EndpointKind kind() {
        return EndpointKind.PLAYER;
    }
}
