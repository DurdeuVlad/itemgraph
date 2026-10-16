package com.itemgraph.listener;

import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.audit.TransformationOutputEvidence;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import com.google.gson.JsonObject;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.GrindstoneEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;

/**
 * NeoForge event listener for item transformations (Phase 9).
 *
 * <p>Captures anvil renaming/repairs as source/result transformations. Crafting and
 * smelting record only the observed output as unresolved audit evidence because their
 * complete input sets are unavailable at the event boundary.
 */
public class TransformationEventListener {

    private final InternalObservationService observationService;

    public TransformationEventListener() {
        this(InternalObservationService.getInstance());
    }

    public TransformationEventListener(InternalObservationService observationService) {
        this.observationService = observationService;
    }

    @SubscribeEvent
    public void onAnvilRepair(AnvilRepairEvent event) {
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }

        ItemStack left = event.getLeft();
        ItemStack output = event.getOutput();
        if (left == null || left.isEmpty() || output == null || output.isEmpty()) {
            return;
        }

        CanonicalItem sourceItem = ItemCanonicalizer.canonicalizeStack(left);
        CanonicalItem resultItem = ItemCanonicalizer.canonicalizeStack(output);

        String type;
        String details;

        String sourceName = sourceItem.customName();
        String resultName = resultItem.customName();
        if ((sourceName != null && !sourceName.equals(resultName)) || (sourceName == null && resultName != null)) {
            type = "ANVIL_RENAME";
            details = "Renamed '" + (sourceName != null ? sourceName : sourceItem.itemId())
                    + "' -> '" + (resultName != null ? resultName : resultItem.itemId()) + "'";
        } else {
            type = "ANVIL_REPAIR";
            details = "Anvil repair/combine on " + output.getItem();
        }

        long now = System.currentTimeMillis();
        String level = player.level().dimension().location().toString();

        this.observationService.submitTransformation(
                new InternalObservationService.InternalTransformation(
                        now,
                        type,
                        player.getUUID().toString(),
                        player.getGameProfile().getName(),
                        level,
                        player.getX(), player.getY(), player.getZ(),
                        sourceItem,
                        resultItem,
                        output.getCount(),
                        details
                )
        );
    }

    @SubscribeEvent
    public void onTradeWithVillager(TradeWithVillagerEvent event) {
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }
        var offer = event.getMerchantOffer();
        var villager = event.getAbstractVillager();
        if (offer == null) {
            return;
        }
        var inputs = new java.util.ArrayList<TransformationEvidence.ItemAmount>();
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
        if (villager != null) {
            x = villager.getX();
            y = villager.getY();
            z = villager.getZ();
            extra.addProperty("merchant_entity", typedActor(villager));
            extra.addProperty("merchant_entity_uuid", villager.getUUID().toString());
        }
        TransformationCapture.submit("TRADE",
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), x, y, z,
                inputs, inputs.isEmpty(),
                new TransformationEvidence.ItemAmount(
                        ItemCanonicalizer.canonicalizeStack(result), result.getCount()),
                TransformationEvidence.Outcome.COMPLETED, "merchant",
                "NeoForge TradeWithVillagerEvent", extra);
    }

    @SubscribeEvent
    public void onPlayerEnchantItem(PlayerEnchantItemEvent event) {
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }
        ItemStack enchanted = event.getEnchantedItem();
        if (enchanted == null || enchanted.isEmpty()) {
            return;
        }
        JsonObject extra = new JsonObject();
        if (event.getEnchantments() != null && !event.getEnchantments().isEmpty()) {
            var applied = new com.google.gson.JsonArray();
            for (var instance : event.getEnchantments()) {
                var ench = new JsonObject();
                ench.addProperty("id", instance.enchantment.getKey() != null
                        ? instance.enchantment.getKey().location().toString() : "unknown");
                ench.addProperty("level", instance.level);
                applied.add(ench);
            }
            extra.add("applied_enchantments", applied);
        }
        CanonicalItem resultCanonical = ItemCanonicalizer.canonicalizeStack(enchanted);
        // The pre-enchant fingerprint and the lapis/level costs are not
        // observable at this event boundary — mark inputs unobserved rather
        // than inventing a pre-enchant state.
        TransformationCapture.submit("ENCHANTING",
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                java.util.List.of(new TransformationEvidence.ItemAmount(resultCanonical, 1)),
                true,
                new TransformationEvidence.ItemAmount(resultCanonical, enchanted.getCount()),
                TransformationEvidence.Outcome.COMPLETED, "enchanting_table",
                "NeoForge PlayerEnchantItemEvent", extra);
    }

    @SubscribeEvent
    public void onGrindstoneTake(GrindstoneEvent.OnTakeItem event) {
        Player player = event.getPlayer();
        if (player == null || player.level().isClientSide()) {
            return;
        }
        var inputs = new java.util.ArrayList<TransformationEvidence.ItemAmount>();
        ItemStack top = event.getTopItem();
        ItemStack bottom = event.getBottomItem();
        if (top != null && !top.isEmpty()) {
            inputs.add(new TransformationEvidence.ItemAmount(
                    ItemCanonicalizer.canonicalizeStack(top), top.getCount()));
        }
        if (bottom != null && !bottom.isEmpty()) {
            inputs.add(new TransformationEvidence.ItemAmount(
                    ItemCanonicalizer.canonicalizeStack(bottom), bottom.getCount()));
        }
        if (inputs.isEmpty()) {
            return;
        }
        double[] position = {player.getX(), player.getY(), player.getZ()};
        String[] level = {player.level().dimension().location().toString()};
        var access = event.getContainerAccess();
        if (access != null) {
            access.evaluate((lvl, pos) -> {
                position[0] = pos.getX();
                position[1] = pos.getY();
                position[2] = pos.getZ();
                level[0] = lvl.dimension().location().toString();
                return null;
            });
        }
        JsonObject extra = new JsonObject();
        extra.addProperty("grindstone_xp", event.getXp());
        ItemStack newTop = event.getNewTopItem();
        ItemStack newBottom = event.getNewBottomItem();
        if ((newTop != null && !newTop.isEmpty()) || (newBottom != null && !newBottom.isEmpty())) {
            extra.addProperty("kept_input", true);
        }
        // The grindstone output item is the disenchanted/repaired result;
        // the event carries the post-operation inputs, so the output stack
        // itself is not exposed — record it as an unobserved output via the
        // kept inputs rather than fabricating the result stack.
        TransformationCapture.submit("GRINDSTONE",
                player.getUUID().toString(), player.getGameProfile().getName(),
                level[0], position[0], position[1], position[2],
                inputs, false, null, true,
                TransformationEvidence.Outcome.COMPLETED, "grindstone",
                "NeoForge GrindstoneEvent.OnTakeItem", extra);
    }

    private static String typedActor(net.minecraft.world.entity.Entity entity) {
        String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType()).toString();
        return "entity:" + type + " id=" + entity.getUUID();
    }

    @SubscribeEvent
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player player = event.getEntity();
        recordCrafted(player, event.getInventory(), event.getCrafting());
    }

    public void recordCrafted(Player player, Container matrix, ItemStack output) {
        if (player == null || player.level().isClientSide()) {
            return;
        }
        if (output == null || output.isEmpty()) {
            return;
        }
        CanonicalItem resultItem = ItemCanonicalizer.canonicalizeStack(output);
        // ItemCraftedEvent may run after the matrix is consumed. A remaining
        // stack is still only a partial recipe, so preserve result-only evidence.
        this.observationService.submitAuditEvent(TransformationOutputEvidence.create(
                "CRAFT_OUTPUT_UNRESOLVED", System.currentTimeMillis(),
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(),
                resultItem, output.getCount(), "NeoForge PlayerEvent.ItemCraftedEvent"));
    }

    @SubscribeEvent
    public void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }

        ItemStack output = event.getSmelting();
        if (output == null || output.isEmpty()) {
            return;
        }

        CanonicalItem resultItem = ItemCanonicalizer.canonicalizeStack(output);
        this.observationService.submitAuditEvent(TransformationOutputEvidence.create(
                "SMELT_OUTPUT_UNRESOLVED", System.currentTimeMillis(),
                player.getUUID().toString(), player.getGameProfile().getName(),
                player.level().dimension().location().toString(), player.getX(), player.getY(), player.getZ(),
                resultItem, output.getCount(), "NeoForge PlayerEvent.ItemSmeltedEvent"));
    }
}
