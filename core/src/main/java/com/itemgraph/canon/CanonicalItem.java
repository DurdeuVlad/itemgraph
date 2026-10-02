package com.itemgraph.canon;

import java.util.Map;

/**
 * Represents a canonicalized Minecraft item with its deterministic fingerprint hash
 * and extracted human-readable component metadata.
 *
 * @param itemId           Canonical item registry ID (e.g. "minecraft:diamond_sword")
 * @param fingerprintHash  Deterministic SHA-256 hex string computed from canonical attributes
 * @param customName       Extracted custom display name, or null if unset
 * @param rarity           Extracted item rarity name (e.g. "COMMON", "EPIC"), or null if unset
 * @param componentSummary Human-readable explanation of key extracted components, or null if default
 * @param searchableComponents Persistent canonical component values, never intended for player display
 * @param componentIndexState COMPLETE when every patched component was serializable, otherwise PARTIAL
 */
public record CanonicalItem(
        String itemId,
        String fingerprintHash,
        String customName,
        String rarity,
        String componentSummary,
        Map<String, String> searchableComponents,
        String componentIndexState
) {
    public CanonicalItem {
        searchableComponents = Map.copyOf(searchableComponents == null ? Map.of() : searchableComponents);
        componentIndexState = componentIndexState == null ? "PARTIAL" : componentIndexState;
    }

    /** Retains the existing construction shape for callers which supply no component projection. */
    public CanonicalItem(String itemId, String fingerprintHash, String customName,
                         String rarity, String componentSummary) {
        this(itemId, fingerprintHash, customName, rarity, componentSummary, Map.of(), "LEGACY_UNKNOWN");
    }
}
