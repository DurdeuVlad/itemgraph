package com.itemgraph.api;

public record ExternalInventoryEndpoint(
        String ownerModId,
        String inventoryId,
        String displayName,
        WorldLocation lastKnownLocation) implements EndpointRef {

    @Override
    public EndpointKind kind() {
        return EndpointKind.EXTERNAL_INVENTORY;
    }

    String externalKey() {
        return ownerModId + "/" + inventoryId;
    }
}
