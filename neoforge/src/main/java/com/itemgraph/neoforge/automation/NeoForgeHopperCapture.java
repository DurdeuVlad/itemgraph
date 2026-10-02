package com.itemgraph.neoforge.automation;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.listener.ContainerInteractionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Captures bounded net inventory deltas around vanilla hopper transfers. */
public final class NeoForgeHopperCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(NeoForgeHopperCapture.class);
    private static final int MAX_NESTING = 16;
    private static final int MAX_POSITIONS = 7;
    private static final int MAX_TOTAL_SLOTS = 512;
    private static final AtomicLong INCOMPLETE_SNAPSHOTS = new AtomicLong();
    private static final AtomicLong CAPTURE_FAILURES = new AtomicLong();
    private static final ThreadLocal<Deque<Capture>> CAPTURES = new ThreadLocal<>();

    private record Capture(ServerLevel level, HopperBlockEntity hopper, BlockPos pos,
                           Map<BlockPos, Map<CanonicalItem, Integer>> before) { }
    record Delta(BlockPos pos, CanonicalItem item, int count, boolean inserted) { }

    private NeoForgeHopperCapture() { }

    public static void begin(Level level, BlockPos pos, HopperBlockEntity hopper) {
        if (!(level instanceof ServerLevel serverLevel) || pos == null || hopper == null) {
            return;
        }
        Deque<Capture> pending = CAPTURES.get();
        if (pending == null) {
            pending = new ArrayDeque<>();
            CAPTURES.set(pending);
        }
        if (pending.size() >= MAX_NESTING) {
            pending.clear();
            LOGGER.warn("ItemGraph NeoForge hopper capture nesting exceeded {}; cleared pending snapshots", MAX_NESTING);
        }
        Map<BlockPos, Map<CanonicalItem, Integer>> before;
        try {
            before = snapshot(serverLevel, pos);
        } catch (RuntimeException failure) {
            reportCaptureFailure("snapshot failed: " + failure);
            before = null;
        }
        pending.push(new Capture(serverLevel, hopper, pos.immutable(), before));
    }

    public static void finish(Level level, BlockPos pos, HopperBlockEntity hopper, boolean moved) {
        Deque<Capture> pending = CAPTURES.get();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Capture capture = pending.peek();
        if (!(level instanceof ServerLevel serverLevel) || capture.level() != serverLevel
                || capture.hopper() != hopper || !capture.pos().equals(pos)) {
            pending.clear();
            CAPTURES.remove();
            LOGGER.warn("ItemGraph NeoForge hopper capture lost nested-call alignment; discarded pending snapshots");
            return;
        }
        pending.pop();
        if (pending.isEmpty()) {
            CAPTURES.remove();
        }
        if (!moved || capture.before() == null) {
            return;
        }
        Map<BlockPos, Map<CanonicalItem, Integer>> after;
        try {
            after = snapshot(serverLevel, capture.before().keySet());
        } catch (RuntimeException failure) {
            reportCaptureFailure("post-transfer snapshot failed: " + failure);
            return;
        }
        if (after == null) {
            return;
        }
        for (Delta delta : deltas(capture.before(), after)) {
            String dimension = serverLevel.dimension().location().toString();
            String action = delta.inserted() ? "HOPPER_INSERT" : "HOPPER_EXTRACT";
            byte[] raw = "{\"capture\":\"neoforge_hopper_net_delta\",\"endpoint\":\"unknown\"}"
                    .getBytes(StandardCharsets.UTF_8);
            boolean accepted;
            try {
                accepted = InternalObservationService.getInstance().submit(
                    new InternalObservationService.InternalObservation(
                            System.currentTimeMillis(), action,
                            ContainerInteractionTracker.UNKNOWN_CALLER_UUID,
                            ContainerInteractionTracker.UNKNOWN_CALLER_NAME,
                            dimension, delta.pos().getX(), delta.pos().getY(), delta.pos().getZ(),
                            dimension, (double) delta.pos().getX(), (double) delta.pos().getY(),
                            (double) delta.pos().getZ(), "CONTAINER", delta.item().itemId(), raw,
                            delta.item(), delta.count(), null, null));
            } catch (RuntimeException failure) {
                reportCaptureFailure("observation submission failed: " + failure);
                continue;
            }
            if (!accepted) {
                reportCaptureFailure("bounded observation queue rejected a hopper delta");
            }
        }
    }

    private static Map<BlockPos, Map<CanonicalItem, Integer>> snapshot(ServerLevel level, BlockPos hopperPos) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        positions.add(hopperPos.immutable());
        for (Direction direction : Direction.values()) {
            positions.add(hopperPos.relative(direction).immutable());
        }
        return snapshot(level, positions, false);
    }

    static Map<BlockPos, Map<CanonicalItem, Integer>> snapshot(ServerLevel level, Iterable<BlockPos> positions) {
        return snapshot(level, positions, true);
    }

    private static Map<BlockPos, Map<CanonicalItem, Integer>> snapshot(
            ServerLevel level, Iterable<BlockPos> positions, boolean requireEveryContainer) {
        Map<BlockPos, Map<CanonicalItem, Integer>> result = new LinkedHashMap<>();
        int visitedSlots = 0;
        for (BlockPos pos : positions) {
            if (result.size() >= MAX_POSITIONS) {
                reportIncompleteSnapshot();
                return null;
            }
            if (!level.hasChunkAt(pos)) {
                if (requireEveryContainer) {
                    reportIncompleteSnapshot();
                    return null;
                }
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof Container container)) {
                if (requireEveryContainer) {
                    reportIncompleteSnapshot();
                    return null;
                }
                continue;
            }
            int size = container.getContainerSize();
            if (!slotCountWithinBound(visitedSlots, size)) {
                reportIncompleteSnapshot();
                return null;
            }
            visitedSlots += size;
            Map<CanonicalItem, Integer> totals = new LinkedHashMap<>();
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack != null && !stack.isEmpty()) {
                    totals.merge(ItemCanonicalizer.canonicalizeStack(stack), stack.getCount(), Integer::sum);
                }
            }
            result.put(pos.immutable(), Map.copyOf(totals));
        }
        return Map.copyOf(result);
    }

    static boolean slotCountWithinBound(int visitedSlots, int candidateSlots) {
        return visitedSlots >= 0 && visitedSlots <= MAX_TOTAL_SLOTS
                && candidateSlots >= 0
                && candidateSlots <= MAX_TOTAL_SLOTS - visitedSlots;
    }

    private static void reportIncompleteSnapshot() {
        long skipped = INCOMPLETE_SNAPSHOTS.incrementAndGet();
        if (skipped == 1 || skipped % 1_000 == 0) {
            LOGGER.warn("ItemGraph omitted incomplete NeoForge hopper snapshots ({} total); each capture is bounded to {} positions and {} slots",
                    skipped, MAX_POSITIONS, MAX_TOTAL_SLOTS);
        }
    }

    private static void reportCaptureFailure(String reason) {
        long failures = CAPTURE_FAILURES.incrementAndGet();
        if (failures == 1 || failures % 1_000 == 0) {
            LOGGER.warn("ItemGraph omitted NeoForge hopper evidence ({} total); latest reason: {}", failures, reason);
        }
    }

    static List<Delta> deltas(Map<BlockPos, Map<CanonicalItem, Integer>> before,
                              Map<BlockPos, Map<CanonicalItem, Integer>> after) {
        List<Delta> result = new ArrayList<>();
        for (var entry : before.entrySet()) {
            Map<CanonicalItem, Integer> beforeItems = entry.getValue();
            Map<CanonicalItem, Integer> afterItems = after.getOrDefault(entry.getKey(), Map.of());
            Set<CanonicalItem> items = new LinkedHashSet<>(beforeItems.keySet());
            items.addAll(afterItems.keySet());
            for (CanonicalItem item : items) {
                int delta = afterItems.getOrDefault(item, 0) - beforeItems.getOrDefault(item, 0);
                if (delta != 0) {
                    result.add(new Delta(entry.getKey(), item, Math.abs(delta), delta > 0));
                }
            }
        }
        return List.copyOf(result);
    }
}
