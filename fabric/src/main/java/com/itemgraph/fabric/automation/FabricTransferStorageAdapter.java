package com.itemgraph.fabric.automation;

import com.itemgraph.api.AutomationEndpoint;
import com.itemgraph.api.AutomationTransferAdapter;
import com.itemgraph.api.ItemGraphService;
import com.itemgraph.api.ItemSnapshot;
import com.itemgraph.api.SourceHandle;
import com.itemgraph.api.SubmissionResult;
import com.itemgraph.api.SubmissionStatus;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in observer for a Fabric Transfer API item storage. It preserves the
 * delegate's return values and records deltas only after the outer transaction
 * commits. Wrap one endpoint slot at a time when the storage exposes stable slots.
 */
public final class FabricTransferStorageAdapter implements Storage<ItemVariant> {
    private static final Logger LOGGER = LoggerFactory.getLogger(FabricTransferStorageAdapter.class);
    private static final int MAX_DISTINCT_DELTAS_PER_TRANSACTION = 64;
    private static final long MAX_QUANTITY_PER_DELTA = 262_144;
    private static final AtomicLong REJECTED_SUBMISSIONS = new AtomicLong();
    private static final AtomicLong BOUNDED_CAPTURE_DROPS = new AtomicLong();

    private final Storage<ItemVariant> delegate;
    private final ItemGraphService service;
    private final SourceHandle source;
    private final AutomationEndpoint endpoint;
    private final ResourceKey<Level> dimension;
    private final String automationModId;
    private final int slot;
    private final ThreadLocal<Journal> journals = ThreadLocal.withInitial(Journal::new);

    public FabricTransferStorageAdapter(Storage<ItemVariant> delegate, ItemGraphService service,
                                        SourceHandle source, AutomationEndpoint endpoint,
                                        ResourceKey<Level> dimension, String automationModId,
                                        int slot) {
        if (delegate == null || service == null || source == null || endpoint == null
                || dimension == null || automationModId == null || slot < -1
                || !source.modId().equals(automationModId)) {
            throw new IllegalArgumentException("delegate, service, source, endpoint, dimension, mod, and slot are required");
        }
        if (slot >= 0 && !endpoint.slotPolicy().equals("slot:" + slot)) {
            throw new IllegalArgumentException("slot policy must match the wrapped storage slot");
        }
        this.delegate = delegate;
        this.service = service;
        this.source = source;
        this.endpoint = endpoint;
        this.dimension = dimension;
        this.automationModId = automationModId;
        this.slot = slot;
    }

    @Override
    public boolean supportsInsertion() {
        return delegate.supportsInsertion();
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        long inserted = delegate.insert(resource, maxAmount, transaction);
        if (inserted > 0 && inserted <= maxAmount && transaction != null
                && resource != null && !resource.isBlank()) {
            recordCommittedDelta(transaction, resource, maxAmount, inserted, true);
        }
        return inserted;
    }

    @Override
    public boolean supportsExtraction() {
        return delegate.supportsExtraction();
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        long extracted = delegate.extract(resource, maxAmount, transaction);
        if (extracted > 0 && extracted <= maxAmount && transaction != null
                && resource != null && !resource.isBlank()) {
            recordCommittedDelta(transaction, resource, maxAmount, extracted, false);
        }
        return extracted;
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        return delegate.iterator();
    }

    @Override
    public long getVersion() {
        return delegate.getVersion();
    }

    private void submitCommitted(JournalState state) {
        if (state.overflowed()) {
            long drops = BOUNDED_CAPTURE_DROPS.incrementAndGet();
            if (drops == 1 || drops % 100 == 0) {
                LOGGER.warn("ItemGraph Fabric automation adapter reached its per-transaction capture bound; excess deltas were omitted ({} transactions)", drops);
            }
            return;
        }
        state.deltas().forEach((key, amount) -> {
            if (amount.moved() <= 0) {
                return;
            }
            try {
                ItemSnapshot item = ItemSnapshot.of(key.variant().toStack(1));
                var result = AutomationTransferAdapter.reportCommittedEndpointDelta(
                        service, source, sourceEventId(), System.currentTimeMillis(), UUID.randomUUID().toString(),
                        endpoint, dimension, item, amount.requested(), amount.moved(), key.inserted(), true, false,
                        automationModId, slot);
                result.ifPresent(this::reportSubmissionResult);
            } catch (RuntimeException failure) {
                reportCaptureFailure(failure);
            }
        });
    }

    private void recordCommittedDelta(TransactionContext transaction, ItemVariant variant,
                                      long requested, long moved, boolean inserted) {
        try {
            journals.get().record(transaction, variant, requested, moved, inserted);
        } catch (RuntimeException failure) {
            reportCaptureFailure(failure);
        }
    }

    private static void reportCaptureFailure(RuntimeException failure) {
        long failures = REJECTED_SUBMISSIONS.incrementAndGet();
        if (failures == 1 || failures % 100 == 0) {
            LOGGER.warn("ItemGraph Fabric automation capture failed ({} total): {}",
                    failures, failure.toString());
        }
    }

    private void reportSubmissionResult(CompletableFuture<SubmissionResult> future) {
        future.whenComplete((result, failure) -> {
            if (failure != null) {
                long failures = REJECTED_SUBMISSIONS.incrementAndGet();
                if (failures == 1 || failures % 100 == 0) {
                    LOGGER.warn("ItemGraph Fabric automation evidence submission failed ({} total): {}",
                            failures, failure.toString());
                }
            } else if (result.status() != SubmissionStatus.PERSISTED
                    && result.status() != SubmissionStatus.DUPLICATE) {
                long failures = REJECTED_SUBMISSIONS.incrementAndGet();
                if (failures == 1 || failures % 100 == 0) {
                    LOGGER.warn("ItemGraph Fabric automation evidence was not persisted ({} total): status={}, code={}",
                            failures, result.status(), result.errorCode());
                }
            }
        });
    }

    private static long sourceEventId() {
        long eventId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return eventId == 0 ? 1 : eventId;
    }

    private record DeltaKey(ItemVariant variant, boolean inserted) {
    }

    private record DeltaAmount(long requested, long moved) {
        private DeltaAmount plus(long requestedAmount, long movedAmount) {
            return new DeltaAmount(requested + requestedAmount, moved + movedAmount);
        }
    }

    private record JournalState(Map<DeltaKey, DeltaAmount> deltas, boolean overflowed) {
        private JournalState copy() {
            return new JournalState(new LinkedHashMap<>(deltas), overflowed);
        }
    }

    private final class Journal extends SnapshotParticipant<JournalState> {
        private JournalState state = new JournalState(new LinkedHashMap<>(), false);

        void record(TransactionContext transaction, ItemVariant variant, long requested,
                    long moved, boolean inserted) {
            updateSnapshots(transaction);
            DeltaKey key = new DeltaKey(variant, inserted);
            DeltaAmount current = state.deltas().get(key);
            if (current == null && state.deltas().size() >= MAX_DISTINCT_DELTAS_PER_TRANSACTION
                    || current != null && (current.requested() > Long.MAX_VALUE - requested
                    || current.moved() > MAX_QUANTITY_PER_DELTA - moved)
                    || current == null && moved > MAX_QUANTITY_PER_DELTA) {
                state = new JournalState(state.deltas(), true);
                return;
            }
            Map<DeltaKey, DeltaAmount> updated = new LinkedHashMap<>(state.deltas());
            updated.merge(key, new DeltaAmount(requested, moved),
                    (left, right) -> left.plus(right.requested(), right.moved()));
            state = new JournalState(updated, state.overflowed());
        }

        @Override
        protected JournalState createSnapshot() {
            return state.copy();
        }

        @Override
        protected void readSnapshot(JournalState snapshot) {
            state = snapshot;
        }

        @Override
        protected void onFinalCommit() {
            JournalState committed = state;
            state = new JournalState(new LinkedHashMap<>(), false);
            journals.remove();
            try {
                submitCommitted(committed);
            } catch (RuntimeException failure) {
                reportCaptureFailure(failure);
            }
        }
    }
}
