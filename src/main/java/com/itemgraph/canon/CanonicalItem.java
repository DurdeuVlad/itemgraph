package com.itemgraph.canon;

/**
 * Represents a canonicalized Minecraft item with its deterministic fingerprint hash
 * and extracted human-readable component metadata.
 *
 * @param itemId           Canonical item registry ID (e.g. "minecraft:diamond_sword")
 * @param fingerprintHash  Deterministic SHA-256 hex string computed from canonical attributes
 * @param customName       Extracted custom display name, or null if unset
 * @param rarity           Extracted item rarity name (e.g. "COMMON", "EPIC"), or null if unset
 * @param componentSummary Human-readable explanation of key extracted components, or null if default
 */
public record CanonicalItem(
        String itemId,
        String fingerprintHash,
        String customName,
        String rarity,
        String componentSummary
) {}
