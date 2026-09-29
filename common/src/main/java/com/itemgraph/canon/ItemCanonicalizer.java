package com.itemgraph.canon;

import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Item canonicalization engine for ItemGraph (Phase 3).
 *
 * Replaces Phase 2 bare-id hashing with 1.21.1 DataComponentPatch decoding and
 * canonical fingerprinting.
 *
 * =========================================================================
 * CANONICAL FINGERPRINT SPECIFICATION (per CLAUDE.md "Item identity" section)
 * =========================================================================
 * The canonical payload entering the SHA-256 hash consists of a deterministic,
 * ordered key-value string:
 *
 *   id=<registry_id>[;custom_name=<name>][;enchantments=<ench1:lvl,ench2:lvl>][;damage=<dmg>][;trim=<mat/pat>][;lore=<line1|line2>]
 *
 * Components included:
 * 1. Registry ID: canonical item ResourceLocation (e.g. "minecraft:diamond_sword"),
 *    resolved via BuiltInRegistries.ITEM when available.
 * 2. custom_name: string representation from DataComponents.CUSTOM_NAME (via Component.getString()).
 * 3. enchantments: sorted alphabetically by enchantment ResourceLocation (e.g. "minecraft:sharpness:5,minecraft:unbreaking:3")
 *    from DataComponents.ENCHANTMENTS.
 * 4. damage: integer durability damage value from DataComponents.DAMAGE.
 * 5. trim: material/pattern resource location pair from DataComponents.TRIM.
 * 6. lore: ordered list of strings joined by "|" from DataComponents.LORE.
 *
 * Items with null, empty, or unpatched DataComponentPatch produce "id=<registry_id>",
 * ensuring standard vanilla items share identical canonical fingerprints. A raw patch
 * that cannot be decoded is represented by "id=<registry_id>;opaque_data_sha256=<hash>"
 * so the source evidence remains distinct and explicitly unresolved.
 */
public class ItemCanonicalizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ItemCanonicalizer.class);
    /**
     * A bad historical blob can be referenced by many GriefLogger rows. Keep a
     * bounded registry-aware negative cache so a known failure is not decoded and
     * diagnosed once per row while its entry remains resident. Identity keys matter
     * here: component IDs are only meaningful in the exact RegistryAccess that
     * produced the bytes.
     */
    private static final int MAX_OPAQUE_DECODE_CACHE_ENTRIES = 4096;
    private static final int OPAQUE_DECODE_LOCK_STRIPES = 64;
    private static final LinkedHashMap<OpaqueDecodeKey, Boolean> OPAQUE_DECODE_FAILURES =
            new LinkedHashMap<>(128, 0.75f, true);
    private static final Object[] OPAQUE_DECODE_LOCKS = createDecodeLocks();
    private static volatile RegistryAccess activeRegistryAccess;

    private static Object[] createDecodeLocks() {
        Object[] locks = new Object[OPAQUE_DECODE_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static final class OpaqueDecodeKey {
        private final RegistryAccess registryAccess;
        private final String rawDataHash;

        private OpaqueDecodeKey(RegistryAccess registryAccess, String rawDataHash) {
            this.registryAccess = registryAccess;
            this.rawDataHash = rawDataHash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof OpaqueDecodeKey that)) {
                return false;
            }
            return registryAccess == that.registryAccess && rawDataHash.equals(that.rawDataHash);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(registryAccess) + rawDataHash.hashCode();
        }
    }

    /** Supplies the server's registry view from a loader adapter without binding this class to that loader. */
    public static void setRegistryAccess(RegistryAccess registryAccess) {
        activeRegistryAccess = registryAccess;
        clearOpaqueDecodeFailureCache();
    }

    public static CanonicalItem canonicalize(String rawItemId, byte[] rawData) {
        return canonicalize(rawItemId, rawData, null);
    }

    public static CanonicalItem canonicalize(String rawItemId, byte[] rawData, RegistryAccess registryAccess) {
        String itemId = resolveRegistryId(rawItemId);

        DataComponentPatch patch = DataComponentPatch.EMPTY;
        String opaqueDataHash = null;
        if (rawData != null && rawData.length > 0) {
            RegistryAccess regAccess = registryAccess;
            if (regAccess == null) {
                regAccess = activeRegistryAccess;
            }
            if (regAccess == null) {
                try {
                    regAccess = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
                } catch (Throwable ignored) {
                    regAccess = RegistryAccess.EMPTY;
                }
            }

            String rawDataHash = sha256Hex(rawData);
            OpaqueDecodeKey decodeKey = new OpaqueDecodeKey(regAccess, rawDataHash);
            if (hasOpaqueDecodeFailure(decodeKey)) {
                return extractAndBuild(itemId, patch, null, rawDataHash);
            }

            synchronized (decodeLock(decodeKey)) {
                // A second worker can arrive while the first worker is decoding.
                // Recheck after acquiring the stripe so failed payloads are truly
                // single-flight rather than merely single-log.
                if (hasOpaqueDecodeFailure(decodeKey)) {
                    return extractAndBuild(itemId, patch, null, rawDataHash);
                }
                RegistryFriendlyByteBuf buf = null;
                try {
                    buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(rawData), regAccess);
                    patch = DataComponentPatch.STREAM_CODEC.decode(buf);
                    if (buf.isReadable()) {
                        throw new IllegalArgumentException("DataComponentPatch has " + buf.readableBytes()
                                + " trailing bytes");
                    }
                } catch (Exception e) {
                    opaqueDataHash = rawDataHash;
                    if (rememberOpaqueDecodeFailure(decodeKey)) {
                        LOGGER.debug("Could not decode GriefLogger DataComponentPatch for item '{}' ({} bytes); "
                                        + "raw data SHA-256={} and component metadata remain unresolved",
                                itemId, rawData.length, opaqueDataHash, e);
                    }
                    patch = DataComponentPatch.EMPTY;
                } finally {
                    if (buf != null) {
                        buf.release();
                    }
                }
            }
        }

        return extractAndBuild(itemId, patch, null, opaqueDataHash);
    }

    private static Object decodeLock(OpaqueDecodeKey key) {
        return OPAQUE_DECODE_LOCKS[Math.floorMod(key.hashCode(), OPAQUE_DECODE_LOCK_STRIPES)];
    }

    private static boolean hasOpaqueDecodeFailure(OpaqueDecodeKey key) {
        synchronized (OPAQUE_DECODE_FAILURES) {
            return OPAQUE_DECODE_FAILURES.get(key) != null;
        }
    }

    /**
     * Records a failed hash and returns true only for the first failure in the
     * current registry context. The caller uses that result to keep diagnostics
     * useful without allowing repeated source rows to flood the log.
     */
    private static boolean rememberOpaqueDecodeFailure(OpaqueDecodeKey key) {
        synchronized (OPAQUE_DECODE_FAILURES) {
            if (OPAQUE_DECODE_FAILURES.containsKey(key)) {
                OPAQUE_DECODE_FAILURES.get(key); // refresh LRU order
                return false;
            }
            if (OPAQUE_DECODE_FAILURES.size() >= MAX_OPAQUE_DECODE_CACHE_ENTRIES) {
                OPAQUE_DECODE_FAILURES.remove(OPAQUE_DECODE_FAILURES.keySet().iterator().next());
            }
            OPAQUE_DECODE_FAILURES.put(key, Boolean.TRUE);
            return true;
        }
    }

    static void clearOpaqueDecodeFailureCache() {
        synchronized (OPAQUE_DECODE_FAILURES) {
            OPAQUE_DECODE_FAILURES.clear();
        }
    }

    static int opaqueDecodeFailureCacheSize() {
        synchronized (OPAQUE_DECODE_FAILURES) {
            return OPAQUE_DECODE_FAILURES.size();
        }
    }

    /**
     * Canonicalizes an already-decoded DataComponentPatch directly, skipping the
     * STREAM_CODEC decode step. Exists mainly so tests can exercise the extraction/
     * hashing logic without needing a "network-synced" RegistryAccess to encode a
     * DataComponentPatch to bytes first (STREAM_CODEC encoding of DataComponentType
     * specifically requires registry ID sync, which only exists on a live server -
     * decoding does not have this requirement, only encoding does).
     */
    public static CanonicalItem canonicalizePatch(String rawItemId, DataComponentPatch patch) {
        return extractAndBuild(resolveRegistryId(rawItemId), patch);
    }

    public static CanonicalItem canonicalizeStack(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new CanonicalItem("minecraft:air", sha256Hex("id=minecraft:air"), null, null, null);
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return extractAndBuild(itemId, stack.getComponentsPatch());
    }

    /** Canonical component strings for the public immutable ItemSnapshot DTO. */
    public static Map<String, String> canonicalComponents(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Map.of();
        }
        Map<String, String> components = new LinkedHashMap<>();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        extractAndBuild(itemId, stack.getComponentsPatch(), components);
        return Map.copyOf(components);
    }

    private static CanonicalItem extractAndBuild(String itemId, DataComponentPatch patch) {
        return extractAndBuild(itemId, patch, null, null);
    }

    private static CanonicalItem extractAndBuild(String itemId, DataComponentPatch patch,
                                                 Map<String, String> components) {
        return extractAndBuild(itemId, patch, components, null);
    }

    private static CanonicalItem extractAndBuild(String itemId, DataComponentPatch patch,
                                                 Map<String, String> components, String opaqueDataHash) {
        String customName = null;
        List<String> sortedEnchantments = new ArrayList<>();
        Integer damage = null;
        String trimSummary = null;
        List<String> loreLines = new ArrayList<>();
        String rarity = null;

        if (patch != null && !patch.isEmpty()) {
            try {
                Optional<? extends Component> nameOpt = patch.get(DataComponents.CUSTOM_NAME);
                if (nameOpt != null && nameOpt.isPresent()) {
                    String str = nameOpt.get().getString();
                    if (!str.isEmpty()) {
                        customName = str;
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not extract CUSTOM_NAME for {}: {}", itemId, t.getMessage());
            }

            try {
                Optional<? extends ItemEnchantments> enchOpt = patch.get(DataComponents.ENCHANTMENTS);
                if (enchOpt != null && enchOpt.isPresent() && !enchOpt.get().isEmpty()) {
                    ItemEnchantments ench = enchOpt.get();
                    for (var entry : ench.entrySet()) {
                        Holder<Enchantment> holder = entry.getKey();
                        int level = entry.getIntValue();
                        String enchKey = holder.unwrapKey()
                                .map(k -> k.location().toString())
                                .orElseGet(holder::getRegisteredName);
                        if (enchKey == null || enchKey.isBlank()) {
                            enchKey = holder.toString();
                        }
                        sortedEnchantments.add(enchKey + ":" + level);
                    }
                    Collections.sort(sortedEnchantments);
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not extract ENCHANTMENTS for {}: {}", itemId, t.getMessage());
            }

            try {
                Optional<? extends Integer> dmgOpt = patch.get(DataComponents.DAMAGE);
                if (dmgOpt != null && dmgOpt.isPresent()) {
                    damage = dmgOpt.get();
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not extract DAMAGE for {}: {}", itemId, t.getMessage());
            }

            try {
                Optional<? extends ArmorTrim> trimOpt = patch.get(DataComponents.TRIM);
                if (trimOpt != null && trimOpt.isPresent()) {
                    ArmorTrim trim = trimOpt.get();
                    String material = trim.material().unwrapKey()
                            .map(k -> k.location().toString())
                            .orElseGet(() -> trim.material().getRegisteredName());
                    String pattern = trim.pattern().unwrapKey()
                            .map(k -> k.location().toString())
                            .orElseGet(() -> trim.pattern().getRegisteredName());
                    trimSummary = material + "/" + pattern;
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not extract TRIM for {}: {}", itemId, t.getMessage());
            }

            try {
                Optional<? extends ItemLore> loreOpt = patch.get(DataComponents.LORE);
                if (loreOpt != null && loreOpt.isPresent()) {
                    ItemLore lore = loreOpt.get();
                    for (Component line : lore.lines()) {
                        loreLines.add(line.getString());
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not extract LORE for {}: {}", itemId, t.getMessage());
            }

            try {
                Optional<? extends Rarity> rarityOpt = patch.get(DataComponents.RARITY);
                if (rarityOpt != null && rarityOpt.isPresent()) {
                    rarity = rarityOpt.get().name();
                }
            } catch (Throwable t) {
                LOGGER.debug("Could not extract RARITY for {}: {}", itemId, t.getMessage());
            }
        }

        if (components != null) {
            if (customName != null) {
                components.put("minecraft:custom_name", customName);
            }
            if (!sortedEnchantments.isEmpty()) {
                components.put("minecraft:enchantments", String.join(",", sortedEnchantments));
            }
            if (damage != null) {
                components.put("minecraft:damage", damage.toString());
            }
            if (trimSummary != null && !trimSummary.isEmpty()) {
                components.put("minecraft:trim", trimSummary);
            }
            if (!loreLines.isEmpty()) {
                components.put("minecraft:lore", String.join("|", loreLines));
            }
            if (rarity != null) {
                components.put("minecraft:rarity", rarity);
            }
        }

        // Deterministic canonical string
        StringBuilder canon = new StringBuilder();
        canon.append("id=").append(itemId);
        if (customName != null) {
            canon.append(";custom_name=").append(customName);
        }
        if (!sortedEnchantments.isEmpty()) {
            canon.append(";enchantments=").append(String.join(",", sortedEnchantments));
        }
        if (damage != null) {
            canon.append(";damage=").append(damage);
        }
        if (trimSummary != null && !trimSummary.isEmpty()) {
            canon.append(";trim=").append(trimSummary);
        }
        if (!loreLines.isEmpty()) {
            canon.append(";lore=").append(String.join("|", loreLines));
        }
        if (opaqueDataHash != null) {
            canon.append(";opaque_data_sha256=").append(opaqueDataHash);
        }

        String fingerprintHash = sha256Hex(canon.toString());

        // Human-readable summary for explainability
        List<String> summaryParts = new ArrayList<>();
        if (customName != null) {
            summaryParts.add("custom_name=" + customName);
        }
        if (!sortedEnchantments.isEmpty()) {
            summaryParts.add("enchantments=[" + String.join(", ", sortedEnchantments) + "]");
        }
        if (damage != null) {
            summaryParts.add("damage=" + damage);
        }
        if (trimSummary != null && !trimSummary.isEmpty()) {
            summaryParts.add("trim=" + trimSummary);
        }
        if (!loreLines.isEmpty()) {
            summaryParts.add("lore=[" + String.join(", ", loreLines) + "]");
        }
        if (opaqueDataHash != null) {
            summaryParts.add("component_decode=UNRESOLVED;raw_data_sha256=" + opaqueDataHash);
        }
        String componentSummary = summaryParts.isEmpty() ? null : String.join("; ", summaryParts);

        return new CanonicalItem(itemId, fingerprintHash, customName, rarity, componentSummary);
    }

    public static String normalizeItemId(String materialName) {
        if (materialName == null || materialName.isBlank()) {
            return "minecraft:air";
        }
        materialName = materialName.trim();
        if (materialName.contains(":")) {
            return materialName;
        }
        return "minecraft:" + materialName;
    }

    private static volatile boolean bootstrapped = false;

    public static void ensureBootstrapped() {
        if (!bootstrapped) {
            synchronized (ItemCanonicalizer.class) {
                if (!bootstrapped) {
                    try {
                        SharedConstants.tryDetectVersion();
                        Bootstrap.bootStrap();
                    } catch (Throwable ignored) {
                        // Expected outside a real game launch in test environments
                    }
                    bootstrapped = true;
                }
            }
        }
    }

    public static String resolveRegistryId(String rawItemId) {
        String normalized = normalizeItemId(rawItemId);
        try {
            ensureBootstrapped();
            ResourceLocation loc = ResourceLocation.tryParse(normalized);
            if (loc != null && BuiltInRegistries.ITEM != null && BuiltInRegistries.ITEM.containsKey(loc)) {
                Item item = BuiltInRegistries.ITEM.get(loc);
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null) {
                    return key.toString();
                }
            }
        } catch (Throwable ignored) {
            // Fallback to normalized if registries are uninitialized or unavailable
        }
        return normalized;
    }

    public static String sha256Hex(String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm unavailable", e);
        }
    }
}
