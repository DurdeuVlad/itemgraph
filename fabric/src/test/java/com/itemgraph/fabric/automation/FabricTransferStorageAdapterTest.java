package com.itemgraph.fabric.automation;

import com.itemgraph.api.AutomationEndpoint;
import com.itemgraph.api.DirectObservation;
import com.itemgraph.api.ItemGraphService;
import com.itemgraph.api.SourceHandle;
import com.itemgraph.api.SubmissionResult;
import com.itemgraph.api.SubmissionStatus;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FabricTransferStorageAdapterTest {
    private ItemGraphService service;
    private SourceHandle source;
    private AutomationEndpoint endpoint;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // Registries may already be bootstrapped by another test class.
        }
    }

    @BeforeEach
    void setUp() {
        service = mock(ItemGraphService.class);
        source = mock(SourceHandle.class);
        when(source.modId()).thenReturn("pipe_mod");
        when(service.submitObservation(eq(source), any())).thenReturn(CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.PERSISTED, "pipe_mod", 1, null, "persisted")));
        endpoint = AutomationEndpoint.blockInventory("storage_mod", "Machine inventory", Level.OVERWORLD,
                new BlockPos(4, 64, 8), "slot:2", Direction.DOWN);
    }

    @Test
    void emitsOnlyAfterOutermostTransactionCommitWithExactPartialQuantity() {
        FabricTransferStorageAdapter adapter = adapter(5, 0);
        ItemVariant diamond = namedDiamond("insert");
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(5, adapter.insert(diamond, 12, transaction));
            verifyNoInteractions(service);
            transaction.commit();
        }

        var observation = captureObservation();
        assertEquals(5, observation.item().amount());
        assertEquals("12", observation.attributes().get("requested_amount"));
        assertEquals("5", observation.attributes().get("moved_amount"));
        assertEquals("committed", observation.attributes().get("transfer_state"));
        assertEquals("2", observation.attributes().get("endpoint_slot"));
        assertEquals("UNKNOWN", observation.origin().kind().name());
    }

    @Test
    void committedFullInsertionPreservesTheCompleteQuantity() {
        FabricTransferStorageAdapter adapter = adapter(16, 0);
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(16, adapter.insert(namedDiamond("full"), 16, transaction));
            transaction.commit();
        }

        DirectObservation observation = captureObservation();
        assertEquals(16, observation.item().amount());
        assertEquals("16", observation.attributes().get("requested_amount"));
        assertEquals("16", observation.attributes().get("moved_amount"));
    }

    @Test
    void suppressesAbortedAndNestedAbortedTransfers() {
        FabricTransferStorageAdapter adapter = adapter(2, 0);
        ItemVariant diamond = namedDiamond("rollback");
        try (Transaction outer = Transaction.openOuter()) {
            adapter.insert(diamond, 4, outer);
            try (Transaction nested = outer.openNested()) {
                adapter.insert(diamond, 7, nested);
            }
            outer.commit();
        }
        var observation = captureObservation();
        assertEquals(2, observation.item().amount(), "nested rollback must not survive in the evidence journal");
        assertEquals("4", observation.attributes().get("requested_amount"));

        reset(service);
        when(source.modId()).thenReturn("pipe_mod");
        when(service.submitObservation(eq(source), any())).thenReturn(CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.PERSISTED, "pipe_mod", 1, null, "persisted")));
        try (Transaction outer = Transaction.openOuter()) {
            adapter.insert(diamond, 4, outer);
        }
        verifyNoInteractions(service);
    }

    @Test
    void committedExtractionKeepsTheKnownEndpointAsOrigin() {
        FabricTransferStorageAdapter adapter = adapter(0, 3);
        ItemVariant diamond = namedDiamond("extract");
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(3, adapter.extract(diamond, 9, transaction));
            transaction.commit();
        }

        var observation = captureObservation();
        assertEquals(3, observation.item().amount());
        assertEquals("9", observation.attributes().get("requested_amount"));
        assertEquals(endpoint.reference(), observation.origin());
        assertEquals("UNKNOWN", observation.destination().kind().name());
    }

    @Test
    void rejectedTransferAndOverflowProduceNoPartialSuccessRows() {
        FabricTransferStorageAdapter rejected = adapter(0, 0);
        ItemVariant diamond = namedDiamond("rejected");
        try (Transaction transaction = Transaction.openOuter()) {
            assertEquals(0, rejected.insert(diamond, 1, transaction));
            transaction.commit();
        }
        verifyNoInteractions(service);

        FabricTransferStorageAdapter bounded = adapter(1, 0);
        try (Transaction transaction = Transaction.openOuter()) {
            java.util.stream.IntStream.range(0, 65).forEach(index -> assertEquals(1,
                    bounded.insert(namedDiamond("overflow-" + index), 1, transaction)));
            transaction.commit();
        }
        verifyNoInteractions(service);
    }

    private FabricTransferStorageAdapter adapter(long inserted, long extracted) {
        Storage<ItemVariant> delegate = new Storage<>() {
            @Override
            public long insert(ItemVariant resource, long maxAmount,
                               net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return Math.min(inserted, maxAmount);
            }

            @Override
            public long extract(ItemVariant resource, long maxAmount,
                                net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return Math.min(extracted, maxAmount);
            }

            @Override
            public Iterator<StorageView<ItemVariant>> iterator() {
                return java.util.Collections.emptyIterator();
            }
        };
        return new FabricTransferStorageAdapter(delegate, service, source, endpoint, Level.OVERWORLD,
                "pipe_mod", 2);
    }

    private static ItemVariant namedDiamond(String name) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return ItemVariant.of(stack);
    }

    private DirectObservation captureObservation() {
        var captor = org.mockito.ArgumentCaptor.forClass(DirectObservation.class);
        verify(service).submitObservation(eq(source), captor.capture());
        return captor.getValue();
    }
}
