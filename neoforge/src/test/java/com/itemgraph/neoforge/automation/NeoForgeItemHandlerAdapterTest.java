package com.itemgraph.neoforge.automation;

import com.itemgraph.api.DirectObservation;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemGraphService;
import com.itemgraph.api.SourceHandle;
import com.itemgraph.api.SubmissionResult;
import com.itemgraph.api.SubmissionStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NeoForgeItemHandlerAdapterTest {
    private ItemGraphService service;
    private SourceHandle source;
    private IItemHandler delegate;
    private NeoForgeItemHandlerAdapter adapter;

    @BeforeEach
    void setUp() {
        service = mock(ItemGraphService.class);
        source = mock(SourceHandle.class);
        delegate = mock(IItemHandler.class);
        when(source.modId()).thenReturn("pipe_mod");
        when(service.submitObservation(eq(source), any())).thenReturn(CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.PERSISTED, "pipe_mod", 1, null, "persisted")));
        adapter = new NeoForgeItemHandlerAdapter(delegate, service, source, "storage_mod", "Machine",
                Level.OVERWORLD, new BlockPos(4, 64, 8), Direction.UP, "pipe_mod");
    }

    @Test
    void preservesHandlerResultsAndCapturesOnlyRealExactSlotDeltas() {
        ItemStack input = new ItemStack(Items.DIAMOND, 10);
        ItemStack partialRemainder = new ItemStack(Items.DIAMOND, 6);
        when(delegate.insertItem(2, input, true)).thenReturn(partialRemainder);
        when(delegate.insertItem(2, input, false)).thenReturn(partialRemainder);

        assertSame(partialRemainder, adapter.insertItem(2, input, true));
        verifyNoInteractions(service);
        assertSame(partialRemainder, adapter.insertItem(2, input, false));
        DirectObservation inserted = captureObservation();
        assertEquals(4, inserted.item().amount());
        assertEquals("10", inserted.attributes().get("requested_amount"));
        assertEquals("4", inserted.attributes().get("moved_amount"));
        assertEquals("2", inserted.attributes().get("endpoint_slot"));
        assertEquals("slot:2", inserted.attributes().get("endpoint_slot_policy"));
        assertEquals("pipe_mod", inserted.attributes().get("automation_mod_id"));
        assertEquals("UNKNOWN", inserted.origin().kind().name());

        reset(service);
        when(source.modId()).thenReturn("pipe_mod");
        when(service.submitObservation(eq(source), any())).thenReturn(CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.PERSISTED, "pipe_mod", 2, null, "persisted")));
        when(delegate.extractItem(1, 5, false)).thenReturn(new ItemStack(Items.DIAMOND, 3));
        ItemStack extracted = adapter.extractItem(1, 5, false);
        assertEquals(3, extracted.getCount());
        DirectObservation extraction = captureObservation();
        assertEquals(3, extraction.item().amount());
        assertEquals("5", extraction.attributes().get("requested_amount"));
        assertEquals("1", extraction.attributes().get("endpoint_slot"));
        assertEquals("EXTERNAL_INVENTORY", extraction.origin().kind().name());
        assertEquals("UNKNOWN", extraction.destination().kind().name());
    }

    @Test
    void fullInsertRecordsTheCompleteDeltaAndRejectedInsertRecordsNothing() {
        ItemStack input = new ItemStack(Items.DIAMOND, 8);
        when(delegate.insertItem(3, input, false)).thenReturn(ItemStack.EMPTY);

        assertSame(ItemStack.EMPTY, adapter.insertItem(3, input, false));
        DirectObservation fullInsert = captureObservation();
        assertEquals(8, fullInsert.item().amount());
        assertEquals("8", fullInsert.attributes().get("requested_amount"));
        assertEquals("8", fullInsert.attributes().get("moved_amount"));

        reset(service);
        when(source.modId()).thenReturn("pipe_mod");
        when(delegate.insertItem(3, input, false)).thenReturn(input);
        adapter.insertItem(3, input, false);
        verifyNoInteractions(service);
    }

    @Test
    void portableInventoryAdapterKeepsProviderIdentityAndExactSlot() {
        ExternalInventoryEndpoint portable = new ExternalInventoryEndpoint(
                "backpack_mod", "portable-storage:owner-token-7", "Backpack", null);
        NeoForgeItemHandlerAdapter portableAdapter = new NeoForgeItemHandlerAdapter(delegate, service, source,
                portable, "item", Level.OVERWORLD, "pipe_mod");
        when(delegate.extractItem(1, 5, false)).thenReturn(new ItemStack(Items.DIAMOND, 3));

        assertEquals(3, portableAdapter.extractItem(1, 5, false).getCount());

        DirectObservation extraction = captureObservation();
        assertEquals("portable-storage:owner-token-7",
                ((ExternalInventoryEndpoint) extraction.origin()).inventoryId());
        assertEquals("backpack_mod", ((ExternalInventoryEndpoint) extraction.origin()).ownerModId());
        assertEquals("slot:1", extraction.attributes().get("endpoint_slot_policy"));
        assertEquals("item", extraction.attributes().get("endpoint_side"));
        assertEquals("UNKNOWN", extraction.destination().kind().name());
    }

    private DirectObservation captureObservation() {
        var captor = org.mockito.ArgumentCaptor.forClass(DirectObservation.class);
        verify(service).submitObservation(eq(source), captor.capture());
        return captor.getValue();
    }
}
