package com.itemgraph.fabric.mixin;

import com.google.gson.JsonObject;
import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.canon.ItemCanonicalizer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Fabric has no trade event; {@link MerchantResultSlot#onTake} is the
 * authoritative take boundary — the same point NeoForge's
 * {@code TradeWithVillagerEvent} wraps. The active offer supplies the
 * costA/costB/result contract.
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {

    @Shadow @Final private Merchant merchant;
    @Shadow @Final private MerchantContainer slots;

    @Inject(method = "onTake", at = @At("HEAD"))
    private void itemgraph$recordTrade(Player player, ItemStack taken, CallbackInfo ci) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        MerchantOffer offer = slots.getActiveOffer();
        if (offer == null) {
            return;
        }
        var inputs = new ArrayList<TransformationEvidence.ItemAmount>();
        ItemStack costA = offer.getCostA();
        ItemStack costB = offer.getCostB();
        if (costA != null && !costA.isEmpty()) {
            inputs.add(new TransformationEvidence.ItemAmount(
                    ItemCanonicalizer.canonicalizeStack(costA), costA.getCount()));
        }
        if (costB != null && !costB.isEmpty()) {
            inputs.add(new TransformationEvidence.ItemAmount(
                    ItemCanonicalizer.canonicalizeStack(costB), costB.getCount()));
        }
        ItemStack result = offer.getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        JsonObject extra = new JsonObject();
        extra.addProperty("offer_uses", offer.getUses());
        extra.addProperty("offer_max_uses", offer.getMaxUses());
        if (merchant instanceof Entity entity) {
            x = entity.getX();
            y = entity.getY();
            z = entity.getZ();
            extra.addProperty("merchant_entity", "entity:"
                    + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(entity.getType())
                    + " id=" + entity.getUUID());
            extra.addProperty("merchant_entity_uuid", entity.getUUID().toString());
        } else if (merchant != null) {
            extra.addProperty("merchant_entity", "non_entity_merchant");
        }
        TransformationCapture.submit("TRADE",
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), x, y, z,
                inputs, inputs.isEmpty(),
                new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(result), result.getCount()),
                TransformationEvidence.Outcome.COMPLETED, "merchant",
                "Fabric mixin MerchantResultSlot.onTake", extra);
    }
}
