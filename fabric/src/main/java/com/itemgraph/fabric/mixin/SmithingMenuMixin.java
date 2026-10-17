package com.itemgraph.fabric.mixin;

import com.google.gson.JsonObject;
import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.canon.ItemCanonicalizer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Fabric has no smithing event; {@link SmithingMenu#onTake} is the
 * authoritative take boundary where the input slots still hold the stacks
 * about to be consumed.
 */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {

    @Shadow
    private RecipeHolder<SmithingRecipe> selectedRecipe;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void itemgraph$recordSmithing(Player player, ItemStack resultStack, CallbackInfo ci) {
        if (player == null || player.level().isClientSide() || resultStack == null || resultStack.isEmpty()) {
            return;
        }
        ItemCombinerMenuAccessor menu = (ItemCombinerMenuAccessor) this;
        var inputs = new ArrayList<TransformationEvidence.ItemAmount>();
        for (int slot = 0; slot < 3; slot++) {
            ItemStack input = menu.itemgraph$getInputSlots().getItem(slot);
            if (input != null && !input.isEmpty()) {
                inputs.add(new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(input), input.getCount()));
            }
        }
        double[] position = {player.getX(), player.getY(), player.getZ()};
        String[] level = {player.level().dimension().location().toString()};
        menu.itemgraph$getAccess().evaluate((lvl, pos) -> {
            position[0] = pos.getX();
            position[1] = pos.getY();
            position[2] = pos.getZ();
            level[0] = lvl.dimension().location().toString();
            return null;
        });
        JsonObject extra = new JsonObject();
        if (selectedRecipe != null) {
            extra.addProperty("smithing_recipe", selectedRecipe.id().toString());
        }
        TransformationCapture.submit("SMITHING",
                player.getUUID().toString(), player.getGameProfile().getName(),
                level[0], position[0], position[1], position[2],
                inputs, false,
                new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(resultStack), resultStack.getCount()),
                TransformationEvidence.Outcome.COMPLETED, "smithing_table",
                "Fabric mixin SmithingMenu.onTake", extra);
    }
}
