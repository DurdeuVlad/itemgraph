package com.itemgraph.fabric.mixin;

import com.google.gson.JsonObject;
import com.itemgraph.audit.TransformationCapture;
import com.itemgraph.audit.TransformationEvidence;
import com.itemgraph.canon.ItemCanonicalizer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * Records loot-table generation at the single funnel every public
 * {@code getRandomItems*}/{@code getRandomItemsRaw} entry point delegates to.
 * Neither loader exposes a documented generation event carrying level, table
 * identity, and rolled items (NeoForge {@code BlockDropsEvent} covers only the
 * block-break subset; Fabric's {@code LootTableEvents} are load-time), so the
 * mixin wraps the shared {@code getRandomItemsRaw(LootContext, Consumer)}
 * boundary with a collecting consumer.
 *
 * <p>Runs on whatever thread generated the loot (server thread for gameplay,
 * chunk workers for world-gen chest loot); the capture only reads immutable
 * post-generation state and submits to the bounded queue.
 */
@Mixin(LootTable.class)
public abstract class LootTableMixin {

    private static final int MAX_COLLECTED = 128;

    @WrapMethod(method = "getRandomItemsRaw(Lnet/minecraft/world/level/storage/loot/LootContext;Ljava/util/function/Consumer;)V")
    private void itemgraph$wrapLootGeneration(LootContext context, Consumer<ItemStack> consumer,
                                              Operation<Void> original) {
        List<ItemStack> collected = new ArrayList<>();
        original.call(context, (Consumer<ItemStack>) stack -> {
            if (collected.size() < MAX_COLLECTED) {
                collected.add(stack.copy());
            }
            consumer.accept(stack);
        });
        if (collected.isEmpty()) {
            return;
        }
        try {
            record(context, collected);
        } catch (RuntimeException captureFailure) {
            // Evidence bookkeeping must never affect gameplay; the failure is
            // logged rather than swallowed so missing evidence stays visible.
            org.slf4j.LoggerFactory.getLogger(LootTableMixin.class)
                    .error("Could not record loot generation evidence", captureFailure);
        }
    }

    private void record(LootContext context, List<ItemStack> collected) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        Entity thisEntity = context.getParamOrNull(LootContextParams.THIS_ENTITY);
        Player lastDamagePlayer = context.getParamOrNull(LootContextParams.LAST_DAMAGE_PLAYER);
        Entity attacker = context.getParamOrNull(LootContextParams.ATTACKING_ENTITY);
        Player player = lastDamagePlayer;
        if (player == null && thisEntity instanceof FishingHook hook && hook.getPlayerOwner() != null) {
            player = hook.getPlayerOwner();
        }
        if (player == null && thisEntity instanceof Player p) {
            player = p;
        }
        String dimension = context.getLevel() != null
                ? context.getLevel().dimension().location().toString() : "unknown";
        double x;
        double y;
        double z;
        boolean originUnobserved = false;
        if (origin != null) {
            x = origin.x;
            y = origin.y;
            z = origin.z;
        } else if (thisEntity != null) {
            x = thisEntity.getX();
            y = thisEntity.getY();
            z = thisEntity.getZ();
        } else {
            x = 0;
            y = 0;
            z = 0;
            originUnobserved = true;
        }

        var outputs = new LinkedHashMap<String, TransformationEvidence.ItemAmount>();
        for (ItemStack stack : collected) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            var canonical = ItemCanonicalizer.canonicalizeStack(stack);
            outputs.merge(canonical.fingerprintHash(),
                    new TransformationEvidence.ItemAmount(canonical, stack.getCount()),
                    (a, b) -> new TransformationEvidence.ItemAmount(a.item(), a.quantity() + b.quantity()));
        }
        if (outputs.isEmpty()) {
            return;
        }
        var outputList = List.copyOf(outputs.values());
        int totalQty = outputList.stream().mapToInt(TransformationEvidence.ItemAmount::quantity).sum();
        // The row-level quantity is the total generated across all outputs; the
        // details JSON carries the per-item breakdown.
        TransformationEvidence.ItemAmount primaryOutput = new TransformationEvidence.ItemAmount(
                outputList.get(0).item(), totalQty);

        JsonObject extra = new JsonObject();
        var tableId = tableId(context.getLevel(), (LootTable) (Object) this);
        if (tableId != null) {
            extra.addProperty("loot_table", tableId.toString());
        }
        if (originUnobserved) {
            extra.addProperty("origin_unobserved", true);
        }
        if (thisEntity != null) {
            extra.addProperty("context_entity", "entity:"
                    + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(thisEntity.getType())
                    + " id=" + thisEntity.getUUID());
        }
        if (attacker != null && attacker != thisEntity) {
            extra.addProperty("attacking_entity", "entity:"
                    + net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                            .getKey(attacker.getType())
                    + " id=" + attacker.getUUID());
        }
        ItemStack tool = context.getParamOrNull(LootContextParams.TOOL);
        if (tool != null && !tool.isEmpty()) {
            extra.addProperty("tool", tool.getItem().toString());
        }
        var blockState = context.getParamOrNull(LootContextParams.BLOCK_STATE);
        if (blockState != null) {
            extra.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(blockState.getBlock()).toString());
        }
        if (collected.size() >= MAX_COLLECTED) {
            extra.addProperty("collection_truncated", true);
        }
        if (outputList.size() > 1) {
            var array = new com.google.gson.JsonArray();
            for (int i = 1; i < outputList.size(); i++) {
                JsonObject out = new JsonObject();
                out.addProperty("item", outputList.get(i).item().itemId());
                out.addProperty("fingerprint", outputList.get(i).item().fingerprintHash());
                out.addProperty("qty", outputList.get(i).quantity());
                array.add(out);
            }
            extra.add("additional_outputs", array);
        }

        TransformationCapture.submit("LOOT_GENERATION",
                player != null ? player.getUUID().toString() : null,
                player != null ? player.getGameProfile().getName() : null,
                dimension, x, y, z,
                List.of(), true, primaryOutput, false,
                TransformationEvidence.Outcome.COMPLETED, "loot_table",
                "Fabric mixin LootTable.getRandomItemsRaw", extra);
    }

    /**
     * Resolves the loot table's registry key through the reloadable loot-table
     * registry. Vanilla {@code LootTable} does not store its own id, and
     * {@code LootContext.getQueriedLootTableId} is not present on every
     * loader's mapped classpath, so the id comes from the registry's own
     * value-to-key reverse lookup.
     */
    private static net.minecraft.resources.ResourceLocation tableId(
            net.minecraft.server.level.ServerLevel level, LootTable self) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        try {
            return level.getServer().reloadableRegistries().get()
                    .registry(net.minecraft.core.registries.Registries.LOOT_TABLE)
                    .map(registry -> registry.getKey(self))
                    .orElse(null);
        } catch (RuntimeException lookupFailure) {
            return null;
        }
    }
}
