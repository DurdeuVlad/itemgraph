package com.itemgraph.api;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.ItemStack;

public record ItemSnapshot(
        String itemId,
        int amount,
        String customName,
        Map<String, String> components) {

    public ItemSnapshot {
        if (components != null) {
            components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
        }
    }

    /** Copies the supplied stack immediately; no Minecraft object is retained. */
    public static ItemSnapshot of(ItemStack stack) {
        if (stack == null) {
            return new ItemSnapshot(null, 0, null, Map.of());
        }
        CanonicalItem canonical = ItemCanonicalizer.canonicalizeStack(stack);
        return new ItemSnapshot(
                canonical.itemId(),
                stack.getCount(),
                canonical.customName(),
                ItemCanonicalizer.canonicalComponents(stack));
    }

    public static ItemSnapshot of(
            String itemId,
            int amount,
            String customName,
            Map<String, String> components) {
        return new ItemSnapshot(itemId, amount, customName, components);
    }
}
