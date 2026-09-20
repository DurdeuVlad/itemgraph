package com.itemgraph.listener;

import com.itemgraph.graph.NodeManager;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.tracker.ItemEntityTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * NeoForge event listener for tracking item drops and pickups (Phase 8A + 0.2.0).
 *
 * <p>Phase 8A: Records ItemEntity UUIDs in {@link ItemEntityTracker} for UUID-based
 * ground-bridge correlation confidence boosting.
 *
 * <p>0.2.0: Also writes full {@code ig_observations} rows so ItemGraph captures drop
 * and pickup movement even when GriefLogger is not installed. These observations use
 * {@code source_type = ITEMGRAPH_INTERNAL} and are deduplicated by V9 partial unique index.
 */
public class ItemEntityEventListener {

    private final ItemEntityTracker tracker;

    public ItemEntityEventListener() {
        this(ItemEntityTracker.getInstance());
    }

    public ItemEntityEventListener(ItemEntityTracker tracker) {
        this.tracker = tracker;
    }

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getEntity();
        if (player == null || itemEntity == null) {
            return;
        }

        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int amount = stack.getCount();
        String level = player.level().dimension().location().toString();
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        long now = System.currentTimeMillis();

        this.tracker.recordDrop(
                itemEntity.getUUID(),
                player.getUUID(),
                level, x, y, z, itemId, amount, now
        );

        // 0.2.0: Write full DROP_ITEM observation (player -> GROUND)
        com.itemgraph.canon.CanonicalItem canonical =
                com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                now,
                "DROP_ITEM",
                player.getUUID().toString(),
                player.getGameProfile().getName(),
                level,
                player.getX(), player.getY(), player.getZ(),
                level,
                (double) x, (double) y, (double) z,
                "GROUND",
                canonical,
                amount,
                itemEntity.getUUID().toString()
        ));
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Post event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getItemEntity();
        if (player == null || itemEntity == null) {
            return;
        }

        ItemStack stack = event.getOriginalStack();
        if (stack.isEmpty()) {
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        int amount = stack.getCount();
        String level = player.level().dimension().location().toString();
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        long now = System.currentTimeMillis();

        this.tracker.recordPickup(
                itemEntity.getUUID(),
                player.getUUID(),
                level, x, y, z, itemId, amount, now
        );

        // 0.2.0: Write full PICKUP_ITEM observation (GROUND -> player)
        com.itemgraph.canon.CanonicalItem canonical =
                com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                now,
                "PICKUP_ITEM",
                player.getUUID().toString(),
                player.getGameProfile().getName(),
                level,
                player.getX(), player.getY(), player.getZ(),
                level,
                (double) x, (double) y, (double) z,
                "GROUND",
                canonical,
                amount,
                itemEntity.getUUID().toString()
        ));
    }

    /**
     * Captures items dropped on player death. Each drop stack becomes a DEATH_DROP
     * observation (player -> GROUND) correlatable with subsequent PICKUP_ITEM events.
     *
     * <p>Uses {@code DEATH_DROP} action type to distinguish from voluntary drops,
     * though the correlation engine treats it identically to {@code DROP_ITEM}.
     */
    @SubscribeEvent
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        // Only observe player deaths — mob loot is not tracked as inventory flow
        if (!(entity instanceof Player player)) {
            return;
        }
        if (entity.level().isClientSide()) {
            return;
        }

        String level = entity.level().dimension().location().toString();
        long now = System.currentTimeMillis();

        for (ItemEntity itemEntity : event.getDrops()) {
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) {
                continue;
            }

            int x = (int) Math.floor(itemEntity.getX());
            int y = (int) Math.floor(itemEntity.getY());
            int z = (int) Math.floor(itemEntity.getZ());

            com.itemgraph.canon.CanonicalItem canonical =
                    com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack);

            InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                    now,
                    "DEATH_DROP",
                    player.getUUID().toString(),
                    player.getGameProfile().getName(),
                    level,
                    player.getX(), player.getY(), player.getZ(),
                    level,
                    (double) x, (double) y, (double) z,
                    "GROUND",
                    canonical,
                    stack.getCount(),
                    itemEntity.getUUID().toString()
            ));
        }
    }
}
