package com.itemgraph.listener;

import com.itemgraph.tracker.ItemEntityTracker;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;

class ItemEntityEventListenerTest {

    private ItemEntityTracker mockTracker;
    private ItemEntityEventListener listener;

    @BeforeAll
    static void initMinecraft() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @BeforeEach
    void setUp() {
        mockTracker = mock(ItemEntityTracker.class);
        listener = new ItemEntityEventListener(mockTracker);
    }

    private Player createMockPlayer(UUID uuid) {
        Player player = mock(Player.class);
        Level level = mock(Level.class);
        when(player.level()).thenReturn(level);

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
        when(level.dimension()).thenReturn(dimKey);

        when(player.getUUID()).thenReturn(uuid);
        return player;
    }

    private ItemEntity createMockItemEntity(UUID uuid, ItemStack stack, double x, double y, double z) {
        ItemEntity entity = mock(ItemEntity.class);
        when(entity.getUUID()).thenReturn(uuid);
        when(entity.getItem()).thenReturn(stack);
        when(entity.getX()).thenReturn(x);
        when(entity.getY()).thenReturn(y);
        when(entity.getZ()).thenReturn(z);
        return entity;
    }

    @Test
    void testItemTossRecordsDrop() {
        UUID playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Player player = createMockPlayer(playerUuid);
        UUID itemUuid = UUID.randomUUID();
        ItemStack stack = new ItemStack(Items.DIAMOND, 5);
        ItemEntity entity = createMockItemEntity(itemUuid, stack, 15.7, 64.0, -20.3);

        ItemTossEvent event = mock(ItemTossEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEntity()).thenReturn(entity);

        listener.onItemToss(event);

        verify(mockTracker).recordDrop(
                eq(itemUuid),
                eq(playerUuid),
                eq("minecraft:overworld"),
                eq(15),
                eq(64),
                eq(-21), // Math.floor(-20.3) is -21
                eq("minecraft:diamond"),
                eq(5),
                anyLong()
        );
    }

    @Test
    void testItemTossIgnoredWhenPlayerNull() {
        ItemTossEvent event = mock(ItemTossEvent.class);
        when(event.getPlayer()).thenReturn(null);
        when(event.getEntity()).thenReturn(mock(ItemEntity.class));

        listener.onItemToss(event);
        verifyNoInteractions(mockTracker);
    }

    @Test
    void testItemTossIgnoredWhenEntityNull() {
        UUID playerUuid = UUID.randomUUID();
        Player player = createMockPlayer(playerUuid);
        ItemTossEvent event = mock(ItemTossEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEntity()).thenReturn(null);

        listener.onItemToss(event);
        verifyNoInteractions(mockTracker);
    }

    @Test
    void testItemTossIgnoredWhenStackEmpty() {
        UUID playerUuid = UUID.randomUUID();
        Player player = createMockPlayer(playerUuid);
        ItemEntity entity = createMockItemEntity(UUID.randomUUID(), ItemStack.EMPTY, 0, 0, 0);

        ItemTossEvent event = mock(ItemTossEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEntity()).thenReturn(entity);

        listener.onItemToss(event);
        verifyNoInteractions(mockTracker);
    }

    @Test
    void testItemPickupRecordsPickup() {
        UUID playerUuid = UUID.fromString("00000000-0000-0000-0000-000000000004");
        Player player = createMockPlayer(playerUuid);
        UUID itemUuid = UUID.randomUUID();
        ItemStack stack = new ItemStack(Items.EMERALD, 12);
        ItemEntity entity = createMockItemEntity(itemUuid, stack, 30.1, 70.0, 40.9);

        ItemEntityPickupEvent.Post event = mock(ItemEntityPickupEvent.Post.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItemEntity()).thenReturn(entity);
        when(event.getOriginalStack()).thenReturn(stack);

        listener.onItemPickup(event);

        verify(mockTracker).recordPickup(
                eq(itemUuid),
                eq(playerUuid),
                eq("minecraft:overworld"),
                eq(30),
                eq(70),
                eq(40),
                eq("minecraft:emerald"),
                eq(12),
                anyLong()
        );
    }

    @Test
    void testItemPickupIgnoredWhenStackEmpty() {
        UUID playerUuid = UUID.randomUUID();
        Player player = createMockPlayer(playerUuid);
        ItemEntity entity = createMockItemEntity(UUID.randomUUID(), ItemStack.EMPTY, 0, 0, 0);

        ItemEntityPickupEvent.Post event = mock(ItemEntityPickupEvent.Post.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItemEntity()).thenReturn(entity);
        when(event.getOriginalStack()).thenReturn(ItemStack.EMPTY);

        listener.onItemPickup(event);
        verifyNoInteractions(mockTracker);
    }
}
