package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.tracker.ItemEntityTracker;
import com.mojang.authlib.GameProfile;
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
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.*;
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

    @AfterEach
    void tearDown() {
        com.itemgraph.ingest.InternalObservationService.getInstance().clear();
    }

    private Player createMockPlayer(UUID uuid) {
        Player player = mock(Player.class);
        Level level = mock(Level.class);
        when(player.level()).thenReturn(level);

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
        when(level.dimension()).thenReturn(dimKey);

        when(player.getUUID()).thenReturn(uuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(uuid, "TestPlayer"));
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
        when(entity.isAddedToLevel()).thenReturn(true);

        listener.onItemToss(event);
        verifyNoInteractions(mockTracker);
        listener.onServerTick(mock(ServerTickEvent.Post.class));

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
        InternalObservationService.InternalObservation observation = pendingObservations().poll();
        assertNotNull(observation);
        assertEquals("DROP_ITEM", observation.actionType());
        assertEquals("GROUND", observation.targetType());
    }

    @Test
    void uncanceledTossWaitsForEntityJoinConfirmationBeforeRecordingGroundMovement() {
        Player player = createMockPlayer(UUID.randomUUID());
        ItemEntity entity = createMockItemEntity(UUID.randomUUID(), new ItemStack(Items.DIAMOND, 5), 15, 64, -20);
        ItemTossEvent event = mock(ItemTossEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEntity()).thenReturn(entity);
        when(event.isCanceled()).thenReturn(false);

        listener.onItemToss(event);

        verifyNoInteractions(mockTracker);
        assertTrue(pendingObservations().isEmpty(), "a toss attempt is not a ground transfer until the entity joins the level");
    }

    @Test
    void canceledItemTossIsRecordedAsUnresolvedInsteadOfGroundMovement() {
        UUID playerUuid = UUID.randomUUID();
        Player player = createMockPlayer(playerUuid);
        ItemEntity entity = createMockItemEntity(UUID.randomUUID(), new ItemStack(Items.DIAMOND, 5), 15, 64, -20);
        ItemTossEvent event = mock(ItemTossEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getEntity()).thenReturn(entity);
        when(event.isCanceled()).thenReturn(true);

        listener.onItemToss(event);

        verifyNoInteractions(mockTracker);
        InternalObservationService.InternalObservation observation = pendingObservations().poll();
        assertNotNull(observation);
        assertEquals("DROP_CANCELLED", observation.actionType());
        assertEquals("UNKNOWN", observation.targetType());
        assertNull(observation.itemEntityUuid());
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
    void canceledLivingDropsDoNotCreateGroundTransfers() {
        Player player = createMockPlayer(UUID.randomUUID());
        ItemEntity entity = createMockItemEntity(UUID.randomUUID(), new ItemStack(Items.DIAMOND, 5), 15, 64, -20);
        LivingDropsEvent event = mock(LivingDropsEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getDrops()).thenReturn(Set.of(entity));
        when(event.isCanceled()).thenReturn(true);

        listener.onLivingDrops(event);

        InternalObservationService.InternalObservation observation = pendingObservations().poll();
        assertNotNull(observation);
        assertEquals("DEATH_DROP_CANCELLED", observation.actionType());
        assertNull(observation.targetType(), "a canceled death-drop is a source event, not a ground transfer");
        assertNull(observation.itemEntityUuid());
        assertNull(pendingObservations().poll());
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
        when(event.getCurrentStack()).thenReturn(ItemStack.EMPTY);

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
    void testPartialItemPickupRecordsMovedQuantity() {
        UUID playerUuid = UUID.randomUUID();
        Player player = createMockPlayer(playerUuid);
        UUID itemUuid = UUID.randomUUID();
        ItemStack originalStack = new ItemStack(Items.EMERALD, 12);
        ItemStack remainder = new ItemStack(Items.EMERALD, 5);
        ItemEntity entity = createMockItemEntity(itemUuid, originalStack, 30.1, 70.0, 40.9);

        ItemEntityPickupEvent.Post event = mock(ItemEntityPickupEvent.Post.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getItemEntity()).thenReturn(entity);
        when(event.getOriginalStack()).thenReturn(originalStack);
        when(event.getCurrentStack()).thenReturn(remainder);

        listener.onItemPickup(event);

        verify(mockTracker).recordPickup(
                eq(itemUuid),
                eq(playerUuid),
                eq("minecraft:overworld"),
                eq(30),
                eq(70),
                eq(40),
                eq("minecraft:emerald"),
                eq(7),
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

    @SuppressWarnings("unchecked")
    private static BlockingQueue<InternalObservationService.InternalObservation> pendingObservations() {
        try {
            Field field = InternalObservationService.class.getDeclaredField("queue");
            field.setAccessible(true);
            return (BlockingQueue<InternalObservationService.InternalObservation>)
                    field.get(InternalObservationService.getInstance());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot reach InternalObservationService.queue", e);
        }
    }
}
