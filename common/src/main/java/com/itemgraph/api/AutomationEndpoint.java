package com.itemgraph.api;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Stable, privacy-preserving identity for one modded inventory endpoint.
 *
 * <p>The identifier is a SHA-256 digest of the owning mod, dimension, block
 * position, slot policy, and exposed side. It is stable across server restarts
 * and does not disclose coordinates in API query results.
 */
public record AutomationEndpoint(ExternalInventoryEndpoint reference, String slotPolicy, String side) {
    public AutomationEndpoint {
        if (reference == null || !ApiValidation.validModId(reference.ownerModId())
                || !ApiValidation.validInventoryId(reference.inventoryId())
                || reference.lastKnownLocation() != null
                || (reference.displayName() != null && !ApiValidation.validDisplayName(reference.displayName()))
                || slotPolicy == null || slotPolicy.isBlank() || slotPolicy.length() > 128
                || slotPolicy.codePoints().anyMatch(Character::isISOControl)
                || side == null || side.length() > 16 || side.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid automation endpoint identity");
        }
    }

    public static AutomationEndpoint blockInventory(String ownerModId, String displayName,
                                                     ResourceKey<Level> dimension, BlockPos position,
                                                     String slotPolicy, Direction side) {
        if (!ApiValidation.validModId(ownerModId) || dimension == null || position == null
                || slotPolicy == null || slotPolicy.isBlank() || slotPolicy.length() > 128
                || slotPolicy.codePoints().anyMatch(Character::isISOControl)
                || (displayName != null && !ApiValidation.validDisplayName(displayName))) {
            throw new IllegalArgumentException("owner, dimension, position, and bounded slot policy are required");
        }
        String sideName = side == null ? "unsided" : side.getName();
        String inventoryId = "automation:" + digest(ownerModId, dimension.location().toString(),
                Integer.toString(position.getX()), Integer.toString(position.getY()),
                Integer.toString(position.getZ()), slotPolicy, sideName);
        return new AutomationEndpoint(new ExternalInventoryEndpoint(ownerModId, inventoryId,
                displayName, null), slotPolicy, sideName);
    }

    /**
     * Creates an endpoint for a portable or otherwise non-world-backed inventory.
     * The integration supplies its own stable opaque inventory ID; ItemGraph does
     * not derive identity from an item stack or assign per-item UUIDs.
     */
    public static AutomationEndpoint externalInventory(String ownerModId, String inventoryId,
                                                       String displayName, String slotPolicy,
                                                       String side) {
        return new AutomationEndpoint(new ExternalInventoryEndpoint(ownerModId, inventoryId,
                displayName, null), slotPolicy, side == null ? "unsided" : side);
    }

    private static String digest(String... values) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                sha256.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha256.update(bytes);
            }
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
