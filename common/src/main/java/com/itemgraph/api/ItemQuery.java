package com.itemgraph.api;

import com.itemgraph.query.ItemMetadataPredicate;

import java.util.List;

public record ItemQuery(
        String itemId,
        String customName,
        String fingerprintHash,
        List<ItemMetadataPredicate> metadataPredicates) {

    public ItemQuery {
        metadataPredicates = List.copyOf(metadataPredicates == null ? List.of() : metadataPredicates);
    }

    public ItemQuery(String itemId, String customName, String fingerprintHash) {
        this(itemId, customName, fingerprintHash, List.of());
    }

    public static ItemQuery itemId(String itemId) {
        return new ItemQuery(itemId, null, null, List.of());
    }

    public static ItemQuery customName(String customName) {
        return new ItemQuery(null, customName, null, List.of());
    }

    public static ItemQuery fingerprintHash(String sha256Hex) {
        return new ItemQuery(null, null, sha256Hex, List.of());
    }

    public ItemQuery withMetadata(List<ItemMetadataPredicate> predicates) {
        return new ItemQuery(itemId, customName, fingerprintHash, predicates);
    }

    public String normalizedPredicate() {
        String selector = itemId != null ? "item." + itemId
                : customName != null ? "name.\"" + customName.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : "fingerprint." + fingerprintHash;
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(selector),
                metadataPredicates.stream().map(ItemMetadataPredicate::normalizedToken))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
