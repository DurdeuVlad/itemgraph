package com.itemgraph.audit;

import com.google.gson.JsonObject;
import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** Immutable pre-break snapshot for successful player destruction of container block entities. */
public final class ContainerBreakCapture {
    private static final int MAX_SLOTS = 512;

    public record Slot(int index, CanonicalItem item, int count) {
        public Slot {
            if (index < 0 || item == null || count < 1) throw new IllegalArgumentException("invalid container slot snapshot");
        }
    }

    public record Snapshot(String eventId, String breakEventId, long timestampMs, String playerUuid, String playerName,
                           String levelName, double playerX, double playerY, double playerZ,
                           String blockId, int x, int y, int z, List<Slot> slots, String unresolvedReason) {
        public Snapshot {
            slots = List.copyOf(slots);
        }
    }

    private static final ThreadLocal<Deque<Snapshot>> ACTIVE_BREAKS = new ThreadLocal<>();

    private ContainerBreakCapture() { }

    /** Returns null unless the block entity implements the inventory contract captured by #140. */
    public static Snapshot begin(ServerPlayer player, ServerLevel level, BlockPos pos,
                                 BlockState state, BlockEntity blockEntity) {
        if (level == null || pos == null || state == null || !state.hasBlockEntity()) return null;
        // Signs and other non-inventory block entities are not container-loss incidents.
        // Non-Container inventory adapters have no safe shared snapshot contract here and
        // remain owned by #34; do not turn them into false unresolved container events.
        if (blockEntity != null && !(blockEntity instanceof Container)) return null;

        String eventId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        String dimension = level.dimension().location().toString();
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String unresolvedReason = null;
        if (player == null) {
            unresolvedReason = "CONTAINER_BREAK_ACTOR_UNAVAILABLE";
        } else if (blockEntity == null) {
            unresolvedReason = "CONTAINER_BLOCK_ENTITY_UNAVAILABLE";
        }

        List<Slot> slots = new ArrayList<>();
        if (unresolvedReason == null) {
            Container container = (Container) blockEntity;
            try {
                int size = container.getContainerSize();
                if (size < 0 || size > MAX_SLOTS) {
                    unresolvedReason = "CONTAINER_SLOT_LIMIT_EXCEEDED";
                } else {
                    for (int slot = 0; slot < size; slot++) {
                        ItemStack stack = container.getItem(slot);
                        if (stack == null || stack.isEmpty()) continue;
                        slots.add(new Slot(slot, ItemCanonicalizer.canonicalizeStack(stack), stack.getCount()));
                    }
                }
            } catch (RuntimeException failure) {
                slots.clear();
                unresolvedReason = "CONTAINER_SNAPSHOT_FAILED";
            }
        }
        String playerUuid = player == null ? null : player.getUUID().toString();
        String playerName = player == null ? null : player.getGameProfile().getName();
        double actorX = player == null ? pos.getX() : player.getX();
        double actorY = player == null ? pos.getY() : player.getY();
        double actorZ = player == null ? pos.getZ() : player.getZ();
        Snapshot snapshot = new Snapshot(eventId,
                UUID.nameUUIDFromBytes((eventId + ":break-block").getBytes(StandardCharsets.UTF_8)).toString(),
                timestamp, playerUuid,
                playerName, dimension, actorX, actorY, actorZ,
                blockId, pos.getX(), pos.getY(), pos.getZ(), slots, unresolvedReason);
        Deque<Snapshot> active = ACTIVE_BREAKS.get();
        if (active == null) {
            active = new ArrayDeque<>();
            ACTIVE_BREAKS.set(active);
        }
        active.push(snapshot);
        return snapshot;
    }

    /** Returns the stable standard BREAK_BLOCK identity while a loader callback is inside this break. */
    public static String activeBreakEventId(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Deque<Snapshot> active = ACTIVE_BREAKS.get();
        if (active == null || active.isEmpty() || player == null || level == null || pos == null) return null;
        Snapshot snapshot = active.peek();
        if (!player.getUUID().toString().equals(snapshot.playerUuid())
                || !level.dimension().location().toString().equals(snapshot.levelName())
                || pos.getX() != snapshot.x() || pos.getY() != snapshot.y() || pos.getZ() != snapshot.z()) {
            return null;
        }
        return snapshot.breakEventId();
    }

    /** Enqueues immutable evidence only after the loader confirms that the break succeeded. */
    public static void complete(Snapshot snapshot, boolean success) {
        if (snapshot == null) return;
        Deque<Snapshot> active = ACTIVE_BREAKS.get();
        if (active != null) {
            active.removeFirstOccurrence(snapshot);
            if (active.isEmpty()) ACTIVE_BREAKS.remove();
        }
        if (!success) return;
        InternalObservationService service = InternalObservationService.getInstance();
        if (snapshot.unresolvedReason() != null) {
            JsonObject details = eventDetails(snapshot, "unresolved");
            details.addProperty("reason", snapshot.unresolvedReason());
            details.addProperty("reason_code", snapshot.unresolvedReason());
            service.submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                    snapshot.timestampMs(), "CONTAINER_BREAK_UNRESOLVED", snapshot.playerUuid(),
                    snapshot.playerName(), snapshot.levelName(), snapshot.x(), snapshot.y(), snapshot.z(),
                    snapshot.blockId(), "reason=" + snapshot.unresolvedReason(), bytes(details),
                    InternalObservationService.sourceEventIdForUuid(snapshot.eventId()), snapshot.eventId(), List.of()));
            return;
        }

        List<InternalObservationService.InternalObservation> observations = new ArrayList<>(snapshot.slots().size());
        for (Slot slot : snapshot.slots()) {
            String eventId = UUID.nameUUIDFromBytes(
                    (snapshot.eventId() + ":slot:" + slot.index()).getBytes(StandardCharsets.UTF_8)).toString();
            JsonObject details = eventDetails(snapshot, "observed");
            details.addProperty("event_id", eventId);
            details.addProperty("cause_event_id", snapshot.eventId());
            details.addProperty("slot", slot.index());
            details.addProperty("item_id", slot.item().itemId());
            details.addProperty("fingerprint", slot.item().fingerprintHash());
            details.addProperty("quantity", slot.count());
            details.addProperty("actor_status", "PLAYER");
            details.addProperty("endpoint_type", "CONTAINER");
            details.addProperty("evidence_class", "OBSERVED");
            observations.add(new InternalObservationService.InternalObservation(
                    snapshot.timestampMs(), "REMOVE_ITEM", snapshot.playerUuid(), snapshot.playerName(),
                    snapshot.levelName(), snapshot.playerX(), snapshot.playerY(), snapshot.playerZ(),
                    snapshot.levelName(), (double) snapshot.x(), (double) snapshot.y(), (double) snapshot.z(),
                    "DESTROYED_CONTAINER", slot.item().itemId(), bytes(details), slot.item(), slot.count(),
                    null, null, InternalObservationService.sourceEventIdForUuid(eventId), eventId));
        }

        // Admit all slot rows with their parent event as one bounded evidence group.
        JsonObject details = eventDetails(snapshot, "observed");
        details.addProperty("stack_slots", snapshot.slots().size());
        details.addProperty("total_items", snapshot.slots().stream().mapToInt(Slot::count).sum());
        if (!snapshot.slots().isEmpty()) {
            details.addProperty("drop_link_status", "UNRESOLVED");
            details.addProperty("drop_link_reason", "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED");
        }
        InternalObservationService.InternalAuditEvent successEvent = new InternalObservationService.InternalAuditEvent(
                snapshot.timestampMs(), "CONTAINER_BREAK_COMPLETED", snapshot.playerUuid(),
                snapshot.playerName(), snapshot.levelName(), snapshot.x(), snapshot.y(), snapshot.z(),
                snapshot.blockId(), "contents_snapshot=complete", bytes(details),
                InternalObservationService.sourceEventIdForUuid(snapshot.eventId()), snapshot.eventId(), List.of());
        List<InternalObservationService.InternalAuditEvent> auditEvents = new ArrayList<>();
        auditEvents.add(successEvent);
        if (!snapshot.slots().isEmpty()) {
            String dropUnresolvedEventId = UUID.nameUUIDFromBytes(
                    (snapshot.eventId() + ":drop-link-unresolved").getBytes(StandardCharsets.UTF_8)).toString();
            JsonObject dropUnresolvedDetails = eventDetails(snapshot, "unresolved");
            dropUnresolvedDetails.addProperty("event_id", dropUnresolvedEventId);
            dropUnresolvedDetails.addProperty("parent_event_id", snapshot.eventId());
            dropUnresolvedDetails.addProperty("reason_code", "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED");
            dropUnresolvedDetails.addProperty("drop_link_status", "UNRESOLVED");
            auditEvents.add(new InternalObservationService.InternalAuditEvent(
                    snapshot.timestampMs(), "CONTAINER_BREAK_UNRESOLVED", snapshot.playerUuid(),
                    snapshot.playerName(), snapshot.levelName(), snapshot.x(), snapshot.y(), snapshot.z(),
                    snapshot.blockId(), "drop_link_status=UNRESOLVED reason_code="
                    + "CONTAINER_DROP_RELATIONSHIP_NOT_AUTHORITATIVELY_LINKED", bytes(dropUnresolvedDetails),
                    InternalObservationService.sourceEventIdForUuid(dropUnresolvedEventId), dropUnresolvedEventId, List.of()));
        }
        if (!service.submitEvidenceBatch(observations, auditEvents)) {
            JsonObject rejectedDetails = eventDetails(snapshot, "unresolved");
            rejectedDetails.addProperty("reason", "CONTAINER_OBSERVATION_QUEUE_REJECTED");
            rejectedDetails.addProperty("reason_code", "CONTAINER_OBSERVATION_QUEUE_REJECTED");
            service.submitAuditEvent(new InternalObservationService.InternalAuditEvent(
                    snapshot.timestampMs(), "CONTAINER_BREAK_UNRESOLVED", snapshot.playerUuid(),
                    snapshot.playerName(), snapshot.levelName(), snapshot.x(), snapshot.y(), snapshot.z(),
                    snapshot.blockId(), "reason=CONTAINER_OBSERVATION_QUEUE_REJECTED", bytes(rejectedDetails),
                    InternalObservationService.sourceEventIdForUuid(snapshot.eventId()), snapshot.eventId(), List.of()));
        }
    }

    private static JsonObject eventDetails(Snapshot snapshot, String outcome) {
        JsonObject details = new JsonObject();
        details.addProperty("capture", "player_container_break_contents");
        details.addProperty("event_id", snapshot.eventId());
        details.addProperty("break_event_id", snapshot.breakEventId());
        details.addProperty("break_event_type", "BREAK_BLOCK");
        details.addProperty("outcome", outcome);
        details.addProperty("block_id", snapshot.blockId());
        details.addProperty("dimension", snapshot.levelName());
        details.addProperty("x", snapshot.x());
        details.addProperty("y", snapshot.y());
        details.addProperty("z", snapshot.z());
        details.addProperty("actor_status", snapshot.playerUuid() == null ? "UNKNOWN" : "PLAYER");
        return details;
    }

    private static byte[] bytes(JsonObject details) {
        return details.toString().getBytes(StandardCharsets.UTF_8);
    }
}
