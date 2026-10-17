package com.itemgraph.fabric.mixin;

import com.google.gson.JsonObject;
import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.canon.ItemCanonicalizer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Fabric has no grindstone event; the anonymous result slot's
 * {@code onTake} is the authoritative take boundary — the same point
 * NeoForge's {@code GrindstoneEvent.OnTakeItem} wraps. At HEAD the repair
 * slots still hold the consumed inputs.
 */
@Mixin(targets = "net.minecraft.world.inventory.GrindstoneMenu$4")
public abstract class GrindstoneResultSlotMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void itemgraph$recordGrindstone(Player player, ItemStack resultStack, CallbackInfo ci) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        // The result slot is only clickable while its menu is open, so the
        // player's containerMenu is the enclosing GrindstoneMenu. This avoids
        // shadowing the synthetic outer-this field, whose name differs across
        // loader mappings (this$0 vs intermediary field_*).
        if (!(player.containerMenu instanceof GrindstoneMenu grindstoneMenu)) {
            return;
        }
        GrindstoneMenuAccessor menu = (GrindstoneMenuAccessor) grindstoneMenu;
        var inputs = new ArrayList<TransformationEvidence.ItemAmount>();
        for (int slot = 0; slot < 2; slot++) {
            ItemStack input = menu.itemgraph$getRepairSlots().getItem(slot);
            if (input != null && !input.isEmpty()) {
                inputs.add(new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(input), input.getCount()));
            }
        }
        if (inputs.isEmpty()) {
            return;
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
        // The result slot's onTake clears the inputs; the output stack the
        // player receives is passed as the argument and is fully observable.
        TransformationCapture.submit("GRINDSTONE",
                player.getUUID().toString(), player.getGameProfile().getName(),
                level[0], position[0], position[1], position[2],
                inputs, false,
                resultStack != null && !resultStack.isEmpty()
                        ? new TransformationEvidence.ItemAmount(
                                ItemCanonicalizer.canonicalizeStack(resultStack), resultStack.getCount())
                        : null,
                resultStack == null || resultStack.isEmpty(),
                TransformationEvidence.Outcome.COMPLETED, "grindstone",
                "Fabric mixin GrindstoneMenu$4.onTake", new JsonObject());
    }
}
