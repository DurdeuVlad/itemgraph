package com.itemgraph.automation;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.listener.ContainerInteractionTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/** Records only vanilla dispenser/dropper items accepted into the world. */
public final class VanillaDispenserCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(VanillaDispenserCapture.class);
    private static final int MAX_NESTING = 16;
    private static final AtomicLong CAPTURE_FAILURES = new AtomicLong();
    private static final ThreadLocal<Deque<Source>> SOURCES = new ThreadLocal<>();

    private record Source(ServerLevel level, BlockPos pos, String action) { }

    private VanillaDispenserCapture() { }

    public static void begin(ServerLevel level, BlockState state, BlockPos pos) {
        if (level == null || state == null || pos == null
                || (!state.is(Blocks.DISPENSER) && !state.is(Blocks.DROPPER))) {
            return;
        }
        Deque<Source> pending = SOURCES.get();
        if (pending == null) {
            pending = new ArrayDeque<>();
            SOURCES.set(pending);
        }
        if (pending.size() >= MAX_NESTING) {
            pending.clear();
            SOURCES.remove();
            reportFailure("nested dispenser call bound exceeded");
            return;
        }
        pending.push(new Source(level, pos.immutable(), state.is(Blocks.DROPPER)
                ? "DROPPER_DROP" : "DISPENSER_DROP"));
    }

    public static void finish(ServerLevel level, BlockPos pos) {
        Deque<Source> pending = SOURCES.get();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Source source = pending.peek();
        if (source.level() != level || !source.pos().equals(pos)) {
            pending.clear();
            SOURCES.remove();
            reportFailure("nested dispenser call lost alignment");
            return;
        }
        pending.pop();
        if (pending.isEmpty()) {
            SOURCES.remove();
        }
    }

    /** Called only after DefaultDispenseItemBehavior's addFreshEntity returns true. */
    public static void onAcceptedItemEntity(ServerLevel level, ItemEntity entity) {
        Deque<Source> pending = SOURCES.get();
        if (pending == null || pending.isEmpty() || level == null || entity == null) {
            return;
        }
        Source source = pending.peek();
        if (source.level() != level || entity.getItem().isEmpty()) {
            return;
        }
        try {
            CanonicalItem item = ItemCanonicalizer.canonicalizeStack(entity.getItem().copy());
            String dimension = level.dimension().location().toString();
            BlockPos target = entity.blockPosition();
            String entityUuid = entity.getUUID().toString();
            String raw = "{\"capture\":\"vanilla_dispenser_item_entity\",\"source\":\""
                    + source.pos().toShortString() + "\",\"item_entity_uuid\":\"" + entityUuid + "\"}";
            boolean accepted = InternalObservationService.getInstance().submit(
                    new InternalObservationService.InternalObservation(
                            System.currentTimeMillis(), source.action(),
                            ContainerInteractionTracker.UNKNOWN_CALLER_UUID,
                            ContainerInteractionTracker.UNKNOWN_CALLER_NAME,
                            dimension, source.pos().getX(), source.pos().getY(), source.pos().getZ(),
                            dimension, (double) target.getX(), (double) target.getY(), (double) target.getZ(),
                            "GROUND", item.itemId(), raw.getBytes(StandardCharsets.UTF_8), item,
                            entity.getItem().getCount(), entityUuid, null));
            if (!accepted) {
                reportFailure("bounded observation queue rejected dispenser item entity");
            }
        } catch (RuntimeException failure) {
            reportFailure(failure.toString());
        }
    }

    private static void reportFailure(String reason) {
        long failures = CAPTURE_FAILURES.incrementAndGet();
        if (failures == 1 || failures % 100 == 0) {
            LOGGER.warn("ItemGraph dispenser evidence capture had {} failures; latest reason: {}", failures, reason);
        }
    }
}
