package com.itemgraph.query;

/**
 * A resolved {@code ig_item_fingerprints} row, flattened for display.
 *
 * <p>The charter is explicit that a named item is distinctive evidence, not a guaranteed
 * identity, so the fingerprint id and hash are always available for display: two stacks
 * sharing a fingerprint are indistinguishable <em>by metadata</em>, which is a different
 * claim from being the same physical stack.
 *
 * @param id              {@code ig_item_fingerprints.id}, the value {@code /ig trace item} takes
 * @param itemId          registry id, e.g. {@code minecraft:netherite_chestplate}
 * @param customName      custom name if the item carried one, else null
 * @param fingerprintHash deterministic canonical-metadata hash
 */
public record FingerprintRef(long id, String itemId, String customName, String fingerprintHash) {

    /** A fingerprint id with no corresponding row (dangling reference). */
    public static FingerprintRef missing(long id) {
        return new FingerprintRef(id, null, null, null);
    }

    public boolean resolved() {
        return itemId != null;
    }

    /** Short form used inline: {@code 'Old Reliable' (minecraft:netherite_chestplate)}. */
    public String describe() {
        if (!resolved()) {
            return "fingerprint#" + id + " (no such row in ig_item_fingerprints)";
        }
        return (customName != null && !customName.isBlank())
                ? ("'" + customName + "' (" + itemId + ")")
                : itemId;
    }

    /** Detail form, adding the fingerprint id and hash for cross-referencing. */
    public String describeFull() {
        if (!resolved()) {
            return describe();
        }
        return describe() + " [fingerprint#" + id + " hash=" + fingerprintHash + "]";
    }
}
