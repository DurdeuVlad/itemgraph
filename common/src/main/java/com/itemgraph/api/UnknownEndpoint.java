package com.itemgraph.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record UnknownEndpoint(
        ResourceKey<Level> level,
        String reason) implements EndpointRef {

    @Override
    public EndpointKind kind() {
        return EndpointKind.UNKNOWN;
    }
}
