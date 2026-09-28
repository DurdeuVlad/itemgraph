package com.itemgraph.api;

public record ItemQuery(
        String itemId,
        String customName,
        String fingerprintHash) {

    public static ItemQuery itemId(String itemId) {
        return new ItemQuery(itemId, null, null);
    }

    public static ItemQuery customName(String customName) {
        return new ItemQuery(null, customName, null);
    }

    public static ItemQuery fingerprintHash(String sha256Hex) {
        return new ItemQuery(null, null, sha256Hex);
    }
}
