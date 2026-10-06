package com.itemgraph.listener;

import com.itemgraph.audit.TransformationOutputEvidence;
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
