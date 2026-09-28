package com.itemgraph.fabric;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.listener.ContainerInteractionTracker;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class FabricContainerSessionListenerTest {
    private final ContainerInteractionTracker tracker = ContainerInteractionTracker.getInstance();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() {
        FabricContainerSessionListener.clearAll();
    }

    @AfterEach
    void tearDown() {
        FabricContainerSessionListener.clearAll();
    }

    @Test
    void snapshotTotalsSumsCanonicalStacks() {
        Container container = mock(Container.class);
        ItemStack stack = new ItemStack(Items.DIAMOND, 3);
        ItemStack second = new ItemStack(Items.DIAMOND, 2);
        CanonicalItem canonical = new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
        when(container.getContainerSize()).thenReturn(2);
        when(container.getItem(0)).thenReturn(stack);
        when(container.getItem(1)).thenReturn(second);

        try (MockedStatic<ItemCanonicalizer> canonicalizer = mockStatic(ItemCanonicalizer.class)) {
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(stack)).thenReturn(canonical);
            canonicalizer.when(() -> ItemCanonicalizer.canonicalizeStack(second)).thenReturn(canonical);
            var totals = FabricContainerSessionListener.snapshotTotals(container);
            assertEquals(Map.of("fp-diamond", 5L), totals.counts());
            assertEquals(Map.of("fp-diamond", canonical), totals.exemplars());
        }
    }
}
