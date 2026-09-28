package com.itemgraph.api;

public record ItemDescriptor(
        String itemId,
        String customName,
        String fingerprintHash) {
}
