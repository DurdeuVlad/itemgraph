package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.ingest.InternalObservationService.InternalTransformation;
import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.entity.player.AnvilRepairEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransformationEventListenerTest {

    private InternalObservationService mockObservationService;
    private TransformationEventListener listener;

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
        listener = new TransformationEventListener(mockObservationService);
    }

    private Player createMockPlayer(boolean clientSide) {
        Player player = mock(Player.class);
        Level level = mock(Level.class);
        when(player.level()).thenReturn(level);
        when(level.isClientSide()).thenReturn(clientSide);

        ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
        when(level.dimension()).thenReturn(dimKey);

        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(player.getUUID()).thenReturn(uuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(uuid, "Alice"));
        when(player.getX()).thenReturn(10.5);
        when(player.getY()).thenReturn(64.0);
        when(player.getZ()).thenReturn(20.5);
        return player;
    }

    @Test
    void testAnvilRenameDetected() {
        Player player = createMockPlayer(false);
        ItemStack left = new ItemStack(Items.DIAMOND_SWORD);
        left.set(DataComponents.CUSTOM_NAME, Component.literal("Old Blade"));

        ItemStack output = new ItemStack(Items.DIAMOND_SWORD);
        output.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));

        AnvilRepairEvent event = mock(AnvilRepairEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getLeft()).thenReturn(left);
        when(event.getOutput()).thenReturn(output);

        listener.onAnvilRepair(event);

        ArgumentCaptor<InternalTransformation> captor = ArgumentCaptor.forClass(InternalTransformation.class);
        verify(mockObservationService).submitTransformation(captor.capture());

        InternalTransformation trans = captor.getValue();
        assertEquals("ANVIL_RENAME", trans.transformationType());
        assertEquals("Alice", trans.playerName());
        assertEquals(player.getUUID().toString(), trans.playerUuid());
        assertEquals("minecraft:overworld", trans.levelName());
        assertEquals(1, trans.quantity());
        assertTrue(trans.details().contains("Renamed 'Old Blade' -> 'Excalibur'"));
        assertEquals("Old Blade", trans.sourceItem().customName());
        assertEquals("Excalibur", trans.resultItem().customName());
        assertNotEquals(trans.sourceItem().fingerprintHash(), trans.resultItem().fingerprintHash());
    }

    @Test
    void testAnvilRenameFromUnnamed() {
        Player player = createMockPlayer(false);
        ItemStack left = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack output = new ItemStack(Items.DIAMOND_SWORD);
        output.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));

        AnvilRepairEvent event = mock(AnvilRepairEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getLeft()).thenReturn(left);
        when(event.getOutput()).thenReturn(output);

        listener.onAnvilRepair(event);

        ArgumentCaptor<InternalTransformation> captor = ArgumentCaptor.forClass(InternalTransformation.class);
        verify(mockObservationService).submitTransformation(captor.capture());

        InternalTransformation trans = captor.getValue();
        assertEquals("ANVIL_RENAME", trans.transformationType());
        assertTrue(trans.details().contains("Renamed 'minecraft:diamond_sword' -> 'Excalibur'"));
        assertNull(trans.sourceItem().customName());
        assertEquals("Excalibur", trans.resultItem().customName());
    }

    @Test
    void testAnvilRepairWithoutRename() {
        Player player = createMockPlayer(false);
        ItemStack left = new ItemStack(Items.DIAMOND_PICKAXE);
        left.set(DataComponents.DAMAGE, 100);

        ItemStack output = new ItemStack(Items.DIAMOND_PICKAXE);
        output.set(DataComponents.DAMAGE, 0);

        AnvilRepairEvent event = mock(AnvilRepairEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getLeft()).thenReturn(left);
        when(event.getOutput()).thenReturn(output);

        listener.onAnvilRepair(event);

        ArgumentCaptor<InternalTransformation> captor = ArgumentCaptor.forClass(InternalTransformation.class);
        verify(mockObservationService).submitTransformation(captor.capture());

        InternalTransformation trans = captor.getValue();
        assertEquals("ANVIL_REPAIR", trans.transformationType());
        assertTrue(trans.details().contains("Anvil repair/combine"));
    }

    @Test
    void testAnvilRepairClientSideIgnored() {
        Player player = createMockPlayer(true);
        AnvilRepairEvent event = mock(AnvilRepairEvent.class);
        when(event.getEntity()).thenReturn(player);

        listener.onAnvilRepair(event);

        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testAnvilRepairNullOrEmptyItemIgnored() {
        Player player = createMockPlayer(false);
        AnvilRepairEvent event = mock(AnvilRepairEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getLeft()).thenReturn(ItemStack.EMPTY);
        when(event.getOutput()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));

        listener.onAnvilRepair(event);
        verifyNoInteractions(mockObservationService);

        when(event.getLeft()).thenReturn(new ItemStack(Items.DIAMOND_SWORD));
        when(event.getOutput()).thenReturn(ItemStack.EMPTY);

        listener.onAnvilRepair(event);
        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testItemCraftedWithMatrixIngredients() {
        Player player = createMockPlayer(false);
        ItemStack output = new ItemStack(Items.IRON_SWORD, 1);

        SimpleContainer matrix = new SimpleContainer(4);
        matrix.setItem(0, new ItemStack(Items.IRON_INGOT, 2));

        PlayerEvent.ItemCraftedEvent event = mock(PlayerEvent.ItemCraftedEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCrafting()).thenReturn(output);
        when(event.getInventory()).thenReturn(matrix);

        listener.onItemCrafted(event);

        ArgumentCaptor<InternalTransformation> captor = ArgumentCaptor.forClass(InternalTransformation.class);
        verify(mockObservationService).submitTransformation(captor.capture());

        InternalTransformation trans = captor.getValue();
        assertEquals("CRAFT", trans.transformationType());
        assertEquals("minecraft:iron_ingot", trans.sourceItem().itemId());
        assertEquals("minecraft:iron_sword", trans.resultItem().itemId());
        assertEquals(1, trans.quantity());
        assertTrue(trans.details().contains("Crafted 1x minecraft:iron_sword from minecraft:iron_ingot"));
    }

    @Test
    void testItemCraftedWithEmptyMatrixFallback() {
        Player player = createMockPlayer(false);
        ItemStack output = new ItemStack(Items.BREAD, 1);

        SimpleContainer emptyMatrix = new SimpleContainer(4);

        PlayerEvent.ItemCraftedEvent event = mock(PlayerEvent.ItemCraftedEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCrafting()).thenReturn(output);
        when(event.getInventory()).thenReturn(emptyMatrix);

        listener.onItemCrafted(event);

        ArgumentCaptor<InternalTransformation> captor = ArgumentCaptor.forClass(InternalTransformation.class);
        verify(mockObservationService).submitTransformation(captor.capture());

        InternalTransformation trans = captor.getValue();
        assertEquals("CRAFT", trans.transformationType());
        assertEquals("minecraft:ingredient", trans.sourceItem().itemId());
        assertEquals("minecraft:bread", trans.resultItem().itemId());
    }

    @Test
    void testItemCraftedClientSideIgnored() {
        Player player = createMockPlayer(true);
        PlayerEvent.ItemCraftedEvent event = mock(PlayerEvent.ItemCraftedEvent.class);
        when(event.getEntity()).thenReturn(player);

        listener.onItemCrafted(event);
        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testItemCraftedEmptyOutputIgnored() {
        Player player = createMockPlayer(false);
        PlayerEvent.ItemCraftedEvent event = mock(PlayerEvent.ItemCraftedEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCrafting()).thenReturn(ItemStack.EMPTY);

        listener.onItemCrafted(event);
        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testItemSmelted() {
        Player player = createMockPlayer(false);
        ItemStack output = new ItemStack(Items.IRON_INGOT, 1);

        PlayerEvent.ItemSmeltedEvent event = mock(PlayerEvent.ItemSmeltedEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getSmelting()).thenReturn(output);

        listener.onItemSmelted(event);

        ArgumentCaptor<InternalTransformation> captor = ArgumentCaptor.forClass(InternalTransformation.class);
        verify(mockObservationService).submitTransformation(captor.capture());

        InternalTransformation trans = captor.getValue();
        assertEquals("SMELT", trans.transformationType());
        assertEquals("minecraft:smelt_ingredient", trans.sourceItem().itemId());
        assertEquals("minecraft:iron_ingot", trans.resultItem().itemId());
        assertEquals(1, trans.quantity());
        assertTrue(trans.details().contains("Smelted 1x minecraft:iron_ingot"));
    }

    @Test
    void testItemSmeltedClientSideIgnored() {
        Player player = createMockPlayer(true);
        PlayerEvent.ItemSmeltedEvent event = mock(PlayerEvent.ItemSmeltedEvent.class);
        when(event.getEntity()).thenReturn(player);

        listener.onItemSmelted(event);
        verifyNoInteractions(mockObservationService);
    }

    @Test
    void testItemSmeltedEmptyOutputIgnored() {
        Player player = createMockPlayer(false);
        PlayerEvent.ItemSmeltedEvent event = mock(PlayerEvent.ItemSmeltedEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getSmelting()).thenReturn(ItemStack.EMPTY);

        listener.onItemSmelted(event);
        verifyNoInteractions(mockObservationService);
    }
}
