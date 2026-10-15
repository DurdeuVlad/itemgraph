package com.itemgraph.listener;

import com.itemgraph.audit.EntityLifecycleCapture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.Map;
import java.util.TreeMap;

/**
 * Entity, projectile, and item-entity lifecycle capture (#56) on NeoForge's
 * documented event boundaries:
 *
 * <ul>
 *   <li>{@link EntityJoinLevelEvent} fires for fresh spawns and chunk loads;
 *       {@code loadedFromDisk()} keeps only authoritative new spawns.
 *   <li>{@link EntityLeaveLevelEvent} plus {@link Entity.RemovalReason#shouldDestroy()}
 *       keeps only genuine removals; chunk unloads and dimension transfers are
 *       not lifecycle endpoints and record nothing.
 *   <li>{@link ProjectileImpactEvent} is the authoritative impact boundary;
 *       a canceled impact is still observed evidence with outcome=cancelled.
 *   <li>{@link LivingDeathEvent} attributes non-player kills to a typed entity
 *       or world cause; player kills stay on {@code KILL_ENTITY}.
 * </ul>
 *
 * Item-entity quantities are the event-reported stack counts only; lifecycle
 * rows never manufacture item flow.
 */
public class EntityLifecycleListener {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        if (level.isClientSide() || entity == null || event.loadedFromDisk()
                || entity instanceof ServerPlayer) {
            return;
        }
        String dimension = level.dimension().location().toString();
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String entityUuid = entity.getUUID().toString();
        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            EntityLifecycleCapture.submit("ITEM_ENTITY_SPAWN", dimension,
                    entity.getX(), entity.getY(), entity.getZ(),
                    stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    entityUuid, stack.getCount(),
                    "entity_join_level", Map.of("subject_entity_type", entityType));
        } else if (entity instanceof Projectile projectile) {
            Map<String, String> metadata = new TreeMap<>();
            Entity owner = projectile.getOwner();
            if (owner != null) {
                metadata.put("owner_entity_uuid", owner.getUUID().toString());
                metadata.put("owner_entity_type",
                        BuiltInRegistries.ENTITY_TYPE.getKey(owner.getType()).toString());
            }
            EntityLifecycleCapture.submit("PROJECTILE_LAUNCH", dimension,
                    entity.getX(), entity.getY(), entity.getZ(),
                    entityType, entityUuid, null, "entity_join_level", metadata);
        } else {
            EntityLifecycleCapture.submit("ENTITY_SPAWN", dimension,
                    entity.getX(), entity.getY(), entity.getZ(),
                    entityType, entityUuid, null, "entity_join_level", Map.of());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        Level level = event.getLevel();
        Entity.RemovalReason reason = entity == null ? null : entity.getRemovalReason();
        if (level.isClientSide() || entity instanceof ServerPlayer
                || reason == null || !reason.shouldDestroy()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String entityUuid = entity.getUUID().toString();
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("removal_reason", reason.name());
        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            EntityLifecycleCapture.submit("ITEM_ENTITY_DESPAWN", dimension,
                    entity.getX(), entity.getY(), entity.getZ(),
                    stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    entityUuid, stack.getCount(),
                    "entity_removal", merge(metadata, "subject_entity_type", entityType));
        } else {
            EntityLifecycleCapture.submit("ENTITY_DESPAWN", dimension,
                    entity.getX(), entity.getY(), entity.getZ(),
                    entityType, entityUuid, null, "entity_removal", metadata);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (projectile == null || projectile.level().isClientSide()) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("impact_type", hit == null ? "MISS" : hit.getType().name());
        double x = projectile.getX();
        double y = projectile.getY();
        double z = projectile.getZ();
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() != null) {
            Entity target = entityHit.getEntity();
            metadata.put("target_entity_type",
                    BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
            metadata.put("target_entity_uuid", target.getUUID().toString());
            x = target.getX();
            y = target.getY();
            z = target.getZ();
        } else if (hit instanceof BlockHitResult blockHit) {
            metadata.put("target_pos", blockHit.getBlockPos().toShortString());
        }
        if (event.isCanceled()) {
            metadata.put("outcome", "cancelled");
        }
        Entity owner = projectile.getOwner();
        if (owner != null) {
            metadata.put("owner_entity_uuid", owner.getUUID().toString());
            metadata.put("owner_entity_type",
                    BuiltInRegistries.ENTITY_TYPE.getKey(owner.getType()).toString());
        }
        EntityLifecycleCapture.submit("PROJECTILE_IMPACT",
                projectile.level().dimension().location().toString(), x, y, z,
                BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType()).toString(),
                projectile.getUUID().toString(), null, "projectile_impact", metadata);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeathNonPlayer(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim == null || victim.level().isClientSide()) {
            return;
        }
        Entity killer = event.getSource() == null ? null : event.getSource().getEntity();
        if (killer instanceof ServerPlayer) {
            // The player-kill path stays on the existing KILL_ENTITY listener.
            return;
        }
        String dimension = victim.level().dimension().location().toString();
        String victimType = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        Map<String, String> metadata = new TreeMap<>();
        metadata.put("victim_entity_uuid", victim.getUUID().toString());
        if (event.getSource() != null && event.getSource().type() != null) {
            metadata.put("damage_type",
                    event.getSource().typeHolder().unwrapKey()
                            .map(key -> key.location().toString()).orElse("unknown"));
        }
        if (killer != null) {
            metadata.putAll(EntityLifecycleCapture.entityActor(
                    killer.getUUID().toString(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()).toString()));
        } else {
            metadata.putAll(EntityLifecycleCapture.worldActor(
                    metadata.getOrDefault("damage_type", "unknown")));
        }
        EntityLifecycleCapture.submit("ENTITY_KILL", dimension,
                victim.getX(), victim.getY(), victim.getZ(),
                victimType, victim.getUUID().toString(), null, "living_death", metadata);
    }

    private static Map<String, String> merge(Map<String, String> base, String key, String value) {
        Map<String, String> merged = new TreeMap<>(base);
        merged.put(key, value);
        return merged;
    }
}
