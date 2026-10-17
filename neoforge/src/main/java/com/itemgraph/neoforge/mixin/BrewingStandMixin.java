package com.itemgraph.neoforge.mixin;

import com.google.gson.JsonObject;
import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.canon.ItemCanonicalizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Records completed brewing cycles at the same authoritative boundary on both
 * loaders. NeoForge's {@code PotionBrewEvent.Post} carries only the item list —
 * no level or position — and {@code PlayerBrewedPotionEvent} fires when a
 * player takes a potion out, not when the transformation happens. The shared
 * {@code doBrew} boundary supplies level, position, pre/post slot states, and
 * the consumed ingredient.
 */
@Mixin(net.minecraft.world.level.block.entity.BrewingStandBlockEntity.class)
public abstract class BrewingStandMixin {

    @Inject(method = "doBrew", at = @At("HEAD"))
    private static void itemgraph$brewHead(Level level, BlockPos pos,
                                           NonNullList<ItemStack> items, CallbackInfo ci) {
        BrewingCaptureState.PRE.set(snapshot(items));
    }

    @Inject(method = "doBrew", at = @At("RETURN"))
    private static void itemgraph$brewReturn(Level level, BlockPos pos,
                                             NonNullList<ItemStack> items, CallbackInfo ci) {
        ItemStack[] pre = BrewingCaptureState.PRE.get();
        BrewingCaptureState.PRE.remove();
        if (level == null || level.isClientSide() || pre == null) {
            return;
        }
        var inputs = new ArrayList<TransformationEvidence.ItemAmount>();
        ItemStack preIngredient = pre.length > 3 && pre[3] != null ? pre[3] : ItemStack.EMPTY;
        if (!preIngredient.isEmpty()) {
            inputs.add(new TransformationEvidence.ItemAmount(
                    ItemCanonicalizer.canonicalizeStack(preIngredient), 1));
        }
        var outputs = new ArrayList<TransformationEvidence.ItemAmount>();
        for (int slot = 0; slot < 3 && slot < items.size(); slot++) {
            ItemStack post = items.get(slot);
            ItemStack before = pre[slot] != null ? pre[slot] : ItemStack.EMPTY;
            if (post != null && !post.isEmpty()
                    && (before.isEmpty() || !ItemStack.isSameItemSameComponents(before, post))) {
                outputs.add(new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(post), post.getCount()));
            }
        }
        if (preIngredient.isEmpty()) {
            return;
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("potion_slots", outputs.size());
        String dim = level.dimension().location().toString();
        if (outputs.isEmpty()) {
            // Brew ran but produced no observable potion output — record it as
            // an unresolved station effect rather than inventing a result.
            TransformationCapture.submitUnresolved(null, null, dim,
                    pos.getX(), pos.getY(), pos.getZ(), "brewing_stand", "empty_brew_result");
            return;
        }
        TransformationCapture.submit("BREWING", null, null, dim,
                pos.getX(), pos.getY(), pos.getZ(),
                inputs, inputs.isEmpty(), outputs.get(0), false,
                TransformationEvidence.Outcome.COMPLETED, "brewing_stand",
                "NeoForge mixin BrewingStandBlockEntity.doBrew", withOutputs(extra, outputs));
    }

    private static ItemStack[] snapshot(NonNullList<ItemStack> items) {
        ItemStack[] copy = new ItemStack[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            copy[i] = stack == null ? ItemStack.EMPTY : stack.copy();
        }
        return copy;
    }

    private static JsonObject withOutputs(JsonObject base, java.util.List<TransformationEvidence.ItemAmount> outputs) {
        if (outputs.size() > 1) {
            var array = new com.google.gson.JsonArray();
            for (int i = 1; i < outputs.size(); i++) {
                JsonObject out = new JsonObject();
                out.addProperty("item", outputs.get(i).item().itemId());
                out.addProperty("fingerprint", outputs.get(i).item().fingerprintHash());
                out.addProperty("qty", outputs.get(i).quantity());
                array.add(out);
            }
            base.add("additional_outputs", array);
        }
        return base;
    }

    /** Per-thread pre-brew snapshot — doBrew is a static method with no per-call state. */
    private static final class BrewingCaptureState {
        private static final ThreadLocal<ItemStack[]> PRE = new ThreadLocal<>();
    }
}
