package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.ingest.InternalObservationService.InternalObservation;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArmorStandEventListenerTest {

    private InternalObservationService mockObservationService;
    private ArmorStandEventListener listener;

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
        mockObservationService = mock(InternalObservationService.class);
        listener = new ArmorStandEventListener(mockObservationService);
    }

    private Player createMockPlayer(boolean clientSide) {
        Player player = mock(Player.class);
        Level level = mock(Level.class);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(clientSide);

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
        when(level.dimension()).thenReturn(dimKey);

        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(player.getUUID()).thenReturn(uuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(uuid, "Bob"));
        when(player.getX()).thenReturn(50.0);
        when(player.getY()).thenReturn(65.0);
        when(player.getZ()).thenReturn(70.0);
        return player;
    }

    private ArmorStand createMockArmorStand() {
        ArmorStand stand = mock(ArmorStand.class);
        when(stand.getX()).thenReturn(52.0);
        when(stand.getY()).thenReturn(65.0);
        when(stand.getZ()).thenReturn(72.0);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            when(stand.getItemBySlot(slot)).thenReturn(ItemStack.EMPTY);
        }
        return stand;
    }

    @Test
    void testEquipArmorStandWhenHandHasItem() {
        Player player = createMockPlayer(false);
        ArmorStand stand = createMockArmorStand();
        ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET, 1);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(helmet);

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(stand);
        when(event.getEntity()).thenReturn(player);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);

        listener.onEntityInteractSpecific(event);

        ArgumentCaptor<InternalObservation> captor = ArgumentCaptor.forClass(InternalObservation.class);
        verify(mockObservationService).submit(captor.capture());

        InternalObservation obs = captor.getValue();
        assertEquals("EQUIP_ARMOR_STAND", obs.actionType());
        assertEquals("ARMOR_STAND", obs.targetType());
        assertEquals("Bob", obs.playerName());
        assertEquals(player.getUUID().toString(), obs.playerUuid());
        assertEquals("minecraft:overworld", obs.levelName());
        assertEquals(50.0, obs.x());
        assertEquals(65.0, obs.y());
        assertEquals(70.0, obs.z());
        assertEquals(52.0, obs.targetX());
        assertEquals(65.0, obs.targetY());
        assertEquals(72.0, obs.targetZ());
        assertEquals(1, obs.amount());
        assertEquals("minecraft:diamond_helmet", obs.item().itemId());
    }

    @Test
    void testEquipArmorStandOffhand() {
        Player player = createMockPlayer(false);
        ArmorStand stand = createMockArmorStand();
        ItemStack chestplate = new ItemStack(Items.IRON_CHESTPLATE, 1);
        when(player.getItemInHand(InteractionHand.OFF_HAND)).thenReturn(chestplate);

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(stand);
        when(event.getEntity()).thenReturn(player);
        when(event.getHand()).thenReturn(InteractionHand.OFF_HAND);

        listener.onEntityInteractSpecific(event);

        ArgumentCaptor<InternalObservation> captor = ArgumentCaptor.forClass(InternalObservation.class);
        verify(mockObservationService).submit(captor.capture());

        InternalObservation obs = captor.getValue();
        assertEquals("EQUIP_ARMOR_STAND", obs.actionType());
        assertEquals("minecraft:iron_chestplate", obs.item().itemId());
    }

    @Test
    void testUnequipArmorStandWhenHandIsEmpty() {
        Player player = createMockPlayer(false);
        ArmorStand stand = createMockArmorStand();
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);

        ItemStack chestplate = new ItemStack(Items.DIAMOND_CHESTPLATE, 1);
        when(stand.getItemBySlot(EquipmentSlot.CHEST)).thenReturn(chestplate);

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(stand);
        when(event.getEntity()).thenReturn(player);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);

        listener.onEntityInteractSpecific(event);

        ArgumentCaptor<InternalObservation> captor = ArgumentCaptor.forClass(InternalObservation.class);
        verify(mockObservationService).submit(captor.capture());

        InternalObservation obs = captor.getValue();
        assertEquals("UNEQUIP_ARMOR_STAND", obs.actionType());
        assertEquals("ARMOR_STAND", obs.targetType());
        assertEquals(1, obs.amount());
        assertEquals("minecraft:diamond_chestplate", obs.item().itemId());
        assertEquals(52.0, obs.targetX());
    }

    @Test
    void testUnequipArmorStandWhenStandIsEmptyDoesNothing() {
        Player player = createMockPlayer(false);
        ArmorStand stand = createMockArmorStand();
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(ItemStack.EMPTY);

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(stand);
        when(event.getEntity()).thenReturn(player);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);

        listener.onEntityInteractSpecific(event);

        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testIgnoredWhenTargetNotArmorStand() {
        Player player = createMockPlayer(false);
        Entity regularEntity = mock(Entity.class);

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(regularEntity);

        listener.onEntityInteractSpecific(event);

        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testIgnoredOnClientSide() {
        Player player = createMockPlayer(true);
        ArmorStand stand = createMockArmorStand();

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(stand);
        when(event.getEntity()).thenReturn(player);

        listener.onEntityInteractSpecific(event);

        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testIgnoredWhenPlayerNull() {
        ArmorStand stand = createMockArmorStand();

        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        when(event.getTarget()).thenReturn(stand);
        when(event.getEntity()).thenReturn(null);

        listener.onEntityInteractSpecific(event);

        verifyNoInteractions(mockObservationService);
    }
}
