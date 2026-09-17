package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * NeoForge event listener for item transformations (Phase 9).
 *
 * <p>Captures anvil renaming/repairs, crafting operations, and smelting, linking source
 * items and result items in {@code ig_item_transformations}.
 */
public class TransformationEventListener {

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

        InternalObservationService.getInstance().submitTransformation(
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
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        Player player = event.getEntity();
        if (player == null || player.level().isClientSide()) {
            return;
        }

        ItemStack output = event.getCrafting();
        if (output == null || output.isEmpty()) {
            return;
        }

        Container matrix = event.getInventory();
        CanonicalItem primaryIngredient = null;
        if (matrix != null) {
            for (int i = 0; i < matrix.getContainerSize(); i++) {
                ItemStack slotStack = matrix.getItem(i);
                if (slotStack != null && !slotStack.isEmpty()) {
                    primaryIngredient = ItemCanonicalizer.canonicalizeStack(slotStack);
                    break;
                }
            }
        }

        CanonicalItem resultItem = ItemCanonicalizer.canonicalizeStack(output);
        if (primaryIngredient == null) {
            primaryIngredient = new CanonicalItem("minecraft:ingredient",
                    ItemCanonicalizer.sha256Hex("id=minecraft:ingredient"), null, null, null);
        }

        long now = System.currentTimeMillis();
        String level = player.level().dimension().location().toString();
        String details = "Crafted " + output.getCount() + "x " + resultItem.itemId() + " from " + primaryIngredient.itemId();

        InternalObservationService.getInstance().submitTransformation(
                new InternalObservationService.InternalTransformation(
                        now,
                        "CRAFT",
                        player.getUUID().toString(),
                        player.getGameProfile().getName(),
                        level,
                        player.getX(), player.getY(), player.getZ(),
                        primaryIngredient,
                        resultItem,
                        output.getCount(),
                        details
                )
        );
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
        CanonicalItem sourceItem = new CanonicalItem("minecraft:smelt_ingredient",
                ItemCanonicalizer.sha256Hex("id=minecraft:smelt_ingredient"), null, null, null);

        long now = System.currentTimeMillis();
        String level = player.level().dimension().location().toString();
        String details = "Smelted " + output.getCount() + "x " + resultItem.itemId();

        InternalObservationService.getInstance().submitTransformation(
                new InternalObservationService.InternalTransformation(
                        now,
                        "SMELT",
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
}
