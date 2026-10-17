package com.itemgraph.fabric.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.canon.ItemCanonicalizer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric has no enchant-applied event; {@code clickMenuButton} is the
 * authoritative apply boundary — the same point NeoForge's
 * {@code PlayerEnchantItemEvent} wraps. HEAD snapshots the input item and
 * lapis; a successful return records the consumed inputs and enchanted output.
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {

    @Unique private ItemStack itemgraph$preItem = ItemStack.EMPTY;
    @Unique private ItemStack itemgraph$preLapis = ItemStack.EMPTY;
    @Unique private int itemgraph$clicked = -1;

    @Inject(method = "clickMenuButton", at = @At("HEAD"))
    private void itemgraph$enchantHead(Player player, int button, CallbackInfoReturnable<Boolean> cir) {
        var slots = ((EnchantmentMenuAccessor) this).itemgraph$getEnchantSlots();
        itemgraph$preItem = copyOrEmpty(slots.getItem(0));
        itemgraph$preLapis = copyOrEmpty(slots.getItem(1));
        itemgraph$clicked = button;
    }

    @Inject(method = "clickMenuButton", at = @At("RETURN"))
    private void itemgraph$enchantReturn(Player player, int button, CallbackInfoReturnable<Boolean> cir) {
        ItemStack preItem = itemgraph$preItem;
        ItemStack preLapis = itemgraph$preLapis;
        int clicked = itemgraph$clicked;
        itemgraph$preItem = ItemStack.EMPTY;
        itemgraph$preLapis = ItemStack.EMPTY;
        itemgraph$clicked = -1;
        if (!cir.getReturnValue() || player == null || player.level().isClientSide()
                || preItem.isEmpty()) {
            return;
        }
        var slots = ((EnchantmentMenuAccessor) this).itemgraph$getEnchantSlots();
        ItemStack postItem = slots.getItem(0);
        if (postItem == null || postItem.isEmpty()) {
            return;
        }
        var inputs = new java.util.ArrayList<TransformationEvidence.ItemAmount>();
        inputs.add(new TransformationEvidence.ItemAmount(
                ItemCanonicalizer.canonicalizeStack(preItem), preItem.getCount()));
        int lapisConsumed = preLapis.isEmpty() ? 0 : preLapis.getCount() - slots.getItem(1).getCount();
        if (lapisConsumed > 0) {
            var lapisCanonical = ItemCanonicalizer.canonicalizeStack(preLapis);
            inputs.add(new TransformationEvidence.ItemAmount(lapisCanonical, lapisConsumed));
        }
        JsonObject extra = new JsonObject();
        int[] costs = ((EnchantmentMenu) (Object) this).costs;
        if (clicked >= 0 && clicked < costs.length) {
            extra.addProperty("levels_spent", costs[clicked]);
        }
        JsonArray applied = new JsonArray();
        var preEnchants = EnchantmentHelper.getEnchantmentsForCrafting(preItem);
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(postItem).entrySet()) {
            int preLevel = preEnchants.getLevel(entry.getKey());
            if (entry.getIntValue() <= preLevel) {
                continue;
            }
            var ench = new JsonObject();
            ench.addProperty("id", entry.getKey().unwrapKey()
                    .map(key -> key.location().toString()).orElse("unknown"));
            ench.addProperty("level", entry.getIntValue());
            applied.add(ench);
        }
        if (applied.size() > 0) {
            extra.add("applied_enchantments", applied);
        }
        double[] position = {player.getX(), player.getY(), player.getZ()};
        String[] level = {player.level().dimension().location().toString()};
        ((EnchantmentMenuAccessor) this).itemgraph$getAccess().evaluate((lvl, pos) -> {
            position[0] = pos.getX();
            position[1] = pos.getY();
            position[2] = pos.getZ();
            level[0] = lvl.dimension().location().toString();
            return null;
        });
        TransformationCapture.submit("ENCHANTING",
                player.getUUID().toString(), player.getGameProfile().getName(),
                level[0], position[0], position[1], position[2],
                inputs, false,
                new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(postItem), postItem.getCount()),
                TransformationEvidence.Outcome.COMPLETED, "enchanting_table",
                "Fabric mixin EnchantmentMenu.clickMenuButton", extra);
    }

    private static ItemStack copyOrEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }
}
