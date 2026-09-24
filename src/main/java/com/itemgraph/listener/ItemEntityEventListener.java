package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.tracker.ItemEntityTracker;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * NeoForge event listener for tracking item drops and pickups (Phase 8A + 0.2.0).
 *
 * <p>Phase 8A: Records ItemEntity UUIDs in {@link ItemEntityTracker} for UUID-based
 * ground-bridge correlation confidence boosting.
 *
 * <p>0.2.0: Also writes {@code ITEMGRAPH_INTERNAL} observations so ItemGraph captures
 * drop and pickup movement even when GriefLogger is not installed. Drop observations are
 * emitted only after the ItemEntity is confirmed in the level; canceled tosses remain UNKNOWN.
 *
 * <p>Partial pickups: {@code ItemEntityPickupEvent.Post} is documented to fire "if part
 * of the item was picked up", but {@code ItemEntity.playerTouch} gates it on
 * {@code Inventory.add()} returning true — and {@code Inventory.addItem} returns false
 * when only part of the stack fit (the return check compares against the count before
 * the final, failed add attempt). Partial pickups therefore never fire Post — no event,
 * no stat. To keep that movement observable, each {@code Pre} records the entity's
 * stack count before {@code add()}; a still-alive entity with a reduced count on the
 * next server tick proves an absorbed partial pickup by the Pre's player. A consumed
 * {@code Post} clears the pending entry, so full pickups are never double-counted.
 */
public class ItemEntityEventListener {

    private static final long PENDING_PICKUP_TTL_MS = 1000;
    private static final int PENDING_PICKUP_MAX = 512;
    private static final long PENDING_DROP_TTL_MS = 1000;
    private static final int PENDING_DROP_MAX = 512;

    private static final class PendingDrop {
        final ItemEntity entity;
        final String actionType;
        final UUID playerUuid;
        final String playerName;
        final String level;
        final double playerX;
        final double playerY;
        final double playerZ;
        final int amount;
        final com.itemgraph.canon.CanonicalItem canonical;
        final long timestampMs;
        final long expiresAtMs;

        PendingDrop(ItemEntity entity, String actionType, UUID playerUuid, String playerName,
                    String level, double playerX, double playerY, double playerZ,
                    int amount, com.itemgraph.canon.CanonicalItem canonical,
                    long timestampMs, long expiresAtMs) {
            this.entity = entity;
            this.actionType = actionType;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.level = level;
            this.playerX = playerX;
            this.playerY = playerY;
            this.playerZ = playerZ;
            this.amount = amount;
            this.canonical = canonical;
            this.timestampMs = timestampMs;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /** A pickup attempt recorded by Pre, awaiting count-delta resolution. */
    private record PendingPickup(
            ItemEntity entity,
            UUID playerUuid,
            String playerName,
            String level,
            double playerX, double playerY, double playerZ,
            int originalCount,
            com.itemgraph.canon.CanonicalItem canonical,
            long expiresAtMs) {}

    /** What a tick-end check should do with a pending pickup entry. */
    enum PendingOutcome { EMIT, KEEP, DROP }

    /**
     * Decides the fate of a pending pickup entry. Pure logic, unit-tested.
     *
     * <p>EMIT only while the entity is alive with a reduced count — a removed entity's
     * count drop cannot be distinguished from a merge/despawn, so it is dropped rather
     * than risk a false observation.
     */
    static PendingOutcome resolvePendingPickup(
            int originalCount, int currentCount, boolean entityAlive, long nowMs, long expiresAtMs) {
        if (entityAlive && currentCount < originalCount) {
            return PendingOutcome.EMIT;
        }
        if (!entityAlive || nowMs >= expiresAtMs) {
            return PendingOutcome.DROP;
        }
        return PendingOutcome.KEEP;
    }

    private final LinkedHashMap<UUID, PendingPickup> pendingPickups = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, PendingDrop> pendingDrops = new LinkedHashMap<>();
    private final ItemEntityTracker tracker;

    public ItemEntityEventListener() {
        this(ItemEntityTracker.getInstance());
    }

    public ItemEntityEventListener(ItemEntityTracker tracker) {
        this.tracker = tracker;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onItemToss(ItemTossEvent event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getEntity();
        if (player == null || itemEntity == null || player.level().isClientSide()) {
            return;
        }
        // A canceled toss removes the item from inventory but never adds the entity to the world.
        if (event.isCanceled()) {
            emitUnresolvedDrop(player, itemEntity, "DROP_CANCELLED", "item_toss_cancelled");
            return;
        }
        queuePendingDrop(player, itemEntity, "DROP_ITEM");
    }

    /**
     * Records a pending pickup attempt. {@code Pre} fires unconditionally before
     * {@code Inventory.add()}; if vanilla gating will skip the add entirely (pickup
     * delay or non-matching target while the event result is DEFAULT, or an explicit
     * FALSE veto), nothing can be absorbed and no entry is recorded.
     */
    @SubscribeEvent
    public void onItemPickupPre(ItemEntityPickupEvent.Pre event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getItemEntity();
        if (player == null || itemEntity == null || player.level().isClientSide()) {
            return;
        }
        TriState canPickup = event.canPickup();
        if (canPickup.isFalse()) {
            return;
        }
        if (!canPickup.isTrue()) {
            if (itemEntity.hasPickUpDelay()) {
                return;
            }
            UUID target = itemEntity.getTarget();
            if (target != null && !target.equals(player.getUUID())) {
                return;
            }
        }

        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }

        UUID id = itemEntity.getUUID();
        // A second touch on the same entity must resolve the earlier attempt first —
        // its absorbed delta is attributable to the earlier player.
        resolvePending(id);
        if (pendingPickups.size() >= PENDING_PICKUP_MAX) {
            pendingPickups.remove(pendingPickups.keySet().iterator().next());
        }
        long now = System.currentTimeMillis();
        pendingPickups.put(id, new PendingPickup(
                itemEntity,
                player.getUUID(),
                player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(),
                stack.getCount(),
                com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack),
                now + PENDING_PICKUP_TTL_MS));
    }

    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Post event) {
        Player player = event.getPlayer();
        ItemEntity itemEntity = event.getItemEntity();
        if (player == null || itemEntity == null) {
            return;
        }
        if (player.level().isClientSide()) {
            return;
        }

        confirmPendingDrop(itemEntity.getUUID());

        // Post reports the authoritative amount; cancel any pending Pre entry so the
        // tick sweep never double-counts this pickup.
        pendingPickups.remove(itemEntity.getUUID());

        ItemStack stack = event.getOriginalStack();
        if (stack.isEmpty()) {
            return;
        }

        // getOriginalStack() is the stack BEFORE pickup; a nearly-full inventory can
        // absorb only part of it. The moved quantity is original minus what the
        // ItemEntity still holds (NeoForge exposes getCurrentStack() for exactly this).
        int amount = stack.getCount() - event.getCurrentStack().getCount();
        if (amount <= 0) {
            return;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String level = player.level().dimension().location().toString();
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        long now = System.currentTimeMillis();

        // Phase 8A: UUID tracking for correlation boost
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
     * Captures player death-drop attempts but records ground movement only after the
     * ItemEntity is confirmed in the level. A canceled event records no ground endpoint.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onLivingDrops(LivingDropsEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player player) || entity.level().isClientSide()) {
            return;
        }

        if (event.isCanceled()) {
            for (ItemEntity itemEntity : event.getDrops()) {
                emitCancelledDeathDropAttempt(player, itemEntity);
            }
            return;
        }
        for (ItemEntity itemEntity : event.getDrops()) {
            if (!itemEntity.getItem().isEmpty()) {
                queuePendingDrop(player, itemEntity, "DEATH_DROP");
            }
        }
    }

    /**
     * Confirms pending ItemEntity spawns and resolves partial pickup count changes once
     * per server tick. Unconfirmed spawn attempts and removed partial-pickup entities
     * remain unresolved rather than receiving a fabricated ground transfer.
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        long now = System.currentTimeMillis();
        resolvePendingDrops(now);
        if (pendingPickups.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, PendingPickup>> it = pendingPickups.entrySet().iterator();
        while (it.hasNext()) {
            PendingPickup pending = it.next().getValue();
            boolean alive = !pending.entity().isRemoved();
            int remaining = alive ? pending.entity().getItem().getCount() : -1;
            switch (resolvePendingPickup(pending.originalCount(), remaining, alive, now, pending.expiresAtMs())) {
                case EMIT -> {
                    emitPartialPickup(pending, pending.originalCount() - remaining);
                    it.remove();
                }
                case DROP -> it.remove();
                case KEEP -> { }
            }
        }
    }

    private void emitCancelledDeathDropAttempt(Player player, ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        String level = player.level().dimension().location().toString();
        com.itemgraph.canon.CanonicalItem canonical =
                com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack);
        byte[] rawData = "{\"capture\":\"living_drops\",\"cancelled\":true,\"movement\":\"not_observed\"}"
                .getBytes(StandardCharsets.UTF_8);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                System.currentTimeMillis(),
                "DEATH_DROP_CANCELLED",
                player.getUUID().toString(),
                player.getGameProfile().getName(),
                level,
                player.getX(), player.getY(), player.getZ(),
                level,
                null, null, null,
                null,
                canonical.itemId(),
                rawData,
                canonical,
                stack.getCount(),
                null,
                null));
    }

    private void queuePendingDrop(Player player, ItemEntity itemEntity, String actionType) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        String level = player.level().dimension().location().toString();
        PendingDrop pending = new PendingDrop(
                itemEntity,
                actionType,
                player.getUUID(),
                player.getGameProfile().getName(),
                level,
                player.getX(), player.getY(), player.getZ(),
                stack.getCount(),
                com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack),
                now,
                now + PENDING_DROP_TTL_MS);

        PendingDrop replaced = pendingDrops.remove(itemEntity.getUUID());
        if (replaced != null) {
            emitUnresolvedDrop(replaced, "pending_entity_reused");
        }
        if (pendingDrops.size() >= PENDING_DROP_MAX) {
            UUID oldest = pendingDrops.keySet().iterator().next();
            emitUnresolvedDrop(pendingDrops.remove(oldest), "pending_capacity");
        }
        pendingDrops.put(itemEntity.getUUID(), pending);
    }

    private void resolvePendingDrops(long nowMs) {
        Iterator<Map.Entry<UUID, PendingDrop>> it = pendingDrops.entrySet().iterator();
        while (it.hasNext()) {
            PendingDrop pending = it.next().getValue();
            if (pending.entity.isAddedToLevel()) {
                emitConfirmedDrop(pending);
                it.remove();
            } else if (nowMs >= pending.expiresAtMs) {
                emitUnresolvedDrop(pending, "entity_not_added_to_level");
                it.remove();
            }
        }
    }

    private void confirmPendingDrop(UUID entityUuid) {
        PendingDrop pending = pendingDrops.remove(entityUuid);
        if (pending != null) {
            emitConfirmedDrop(pending);
        }
    }

    private void emitConfirmedDrop(PendingDrop pending) {
        ItemEntity itemEntity = pending.entity;
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        String itemId = pending.canonical.itemId();

        tracker.recordDrop(
                itemEntity.getUUID(), pending.playerUuid, pending.level, x, y, z,
                itemId, pending.amount, pending.timestampMs);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                pending.timestampMs,
                pending.actionType,
                pending.playerUuid.toString(),
                pending.playerName,
                pending.level,
                pending.playerX, pending.playerY, pending.playerZ,
                pending.level,
                (double) x, (double) y, (double) z,
                "GROUND",
                pending.canonical,
                pending.amount,
                itemEntity.getUUID().toString(),
                null));
    }

    private void emitUnresolvedDrop(PendingDrop pending, String reason) {
        String actionType = "DEATH_DROP".equals(pending.actionType)
                ? "DEATH_DROP_UNRESOLVED" : "DROP_UNRESOLVED";
        emitUnresolvedDrop(pending.playerUuid, pending.playerName, pending.level,
                pending.playerX, pending.playerY, pending.playerZ, pending.canonical,
                pending.amount, pending.timestampMs, actionType, reason);
    }

    private void emitUnresolvedDrop(Player player, ItemEntity itemEntity, String actionType, String reason) {
        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        String level = player.level().dimension().location().toString();
        emitUnresolvedDrop(player.getUUID(), player.getGameProfile().getName(), level,
                player.getX(), player.getY(), player.getZ(),
                com.itemgraph.canon.ItemCanonicalizer.canonicalizeStack(stack),
                stack.getCount(), System.currentTimeMillis(), actionType, reason);
    }

    private void emitUnresolvedDrop(UUID playerUuid, String playerName, String level,
                                    double playerX, double playerY, double playerZ,
                                    com.itemgraph.canon.CanonicalItem canonical, int amount,
                                    long timestampMs, String actionType, String reason) {
        byte[] rawData = ("{\"capture\":\"drop_attempt\",\"resolution\":\"unknown_destination\",\"reason\":\""
                + reason + "\"}").getBytes(StandardCharsets.UTF_8);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                timestampMs,
                actionType,
                playerUuid.toString(),
                playerName,
                level,
                playerX, playerY, playerZ,
                level,
                null, null, null,
                "UNKNOWN",
                canonical.itemId(),
                rawData,
                canonical,
                amount,
                null,
                null));
    }

    private void resolvePending(UUID entityUuid) {
        PendingPickup pending = pendingPickups.remove(entityUuid);
        if (pending == null || pending.entity().isRemoved()) {
            return;
        }
        int absorbed = pending.originalCount() - pending.entity().getItem().getCount();
        if (absorbed > 0) {
            emitPartialPickup(pending, absorbed);
        }
    }

    /**
     * Emits the PICKUP_ITEM a silent partial absorb produced. Tagged in raw_data so
     * the evidence records that the amount came from the Pre/stack-delta pairing
     * rather than a fired Post event.
     */
    private void emitPartialPickup(PendingPickup pending, int amount) {
        ItemEntity itemEntity = pending.entity();
        confirmPendingDrop(itemEntity.getUUID());
        int x = (int) Math.floor(itemEntity.getX());
        int y = (int) Math.floor(itemEntity.getY());
        int z = (int) Math.floor(itemEntity.getZ());
        long now = System.currentTimeMillis();
        String itemId = pending.canonical() != null
                ? pending.canonical().itemId()
                : BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem()).toString();

        this.tracker.recordPickup(
                itemEntity.getUUID(),
                pending.playerUuid(),
                pending.level(), x, y, z, itemId, amount, now
        );

        byte[] rawData = ("{\"detection\":\"pre_post_pairing\",\"originalEntityCount\":"
                + pending.originalCount() + "}")
                .getBytes(StandardCharsets.UTF_8);
        InternalObservationService.getInstance().submit(new InternalObservationService.InternalObservation(
                now,
                "PICKUP_ITEM",
                pending.playerUuid().toString(),
                pending.playerName(),
                pending.level(),
                pending.playerX(), pending.playerY(), pending.playerZ(),
                pending.level(),
                (double) x, (double) y, (double) z,
                "GROUND",
                itemId,
                rawData,
                pending.canonical(),
                amount,
                itemEntity.getUUID().toString()
        ));
    }
}
