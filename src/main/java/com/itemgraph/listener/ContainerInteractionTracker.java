package com.itemgraph.listener;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks which player currently has a container open at a specific block position
 * and dimension (Phase 0.2.0 — Issue 3).
 *
 * <p>Used by {@link ContainerCapabilityWrapper} to attribute {@code IItemHandler}
 * {@code insertItem}/{@code extractItem} calls to the responsible player. When no
 * player context is found for a given position, the transfer is attributed to
 * automation (hopper, pipe, etc.) and the AUTOMATION sentinel UUID is used.
 *
 * <p>Thread-safe: {@link ConcurrentHashMap} is used because IItemHandler calls
 * may arrive from the hopper automation scheduler while this class is also
 * written from {@code PlayerContainerEvent} on the server thread.
 */
public class ContainerInteractionTracker {

    private static final ContainerInteractionTracker INSTANCE = new ContainerInteractionTracker();

    public static ContainerInteractionTracker getInstance() {
        return INSTANCE;
    }

    /** The player currently responsible for a container at a specific dimension + block position. */
    public record PlayerContext(UUID uuid, String name) {}

    /** Composite key: dimension key + block position. */
    private record DimPos(ResourceKey<Level> dimension, BlockPos pos) {}

    /** Open container associations: DimPos -> PlayerContext. */
    private final ConcurrentMap<DimPos, PlayerContext> openContainers = new ConcurrentHashMap<>();

    private ContainerInteractionTracker() {}

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (event.getEntity().level().isClientSide()) return;
        // Remove all entries attributed to this player (one player = one open container at a time)
        UUID playerUuid = event.getEntity().getUUID();
        openContainers.entrySet().removeIf(e -> e.getValue().uuid().equals(playerUuid));
    }

    /**
     * Registers that {@code player} has opened the container at {@code dimension:pos}.
     * Called from {@link ContainerCapabilityWrapper} cannot detect the container position
     * from the Close event alone, so capability wrappers call this on the first real
     * (non-simulate) call while a player is known to be interacting.
     *
     * <p>In practice, the capability wrapper is called immediately when the player
     * clicks a slot. The server-side container menu's block position is accessible
     * via the block entity directly inside the wrapper's provider closure, so the
     * wrapper knows both the block position and (via Minecraft's server context) can
     * look up which player has that menu open.
     */
    public void setPlayerContext(ResourceKey<Level> dimension, BlockPos pos, UUID playerUuid, String playerName) {
        openContainers.put(new DimPos(dimension, pos), new PlayerContext(playerUuid, playerName));
    }

    /**
     * Returns the player context for the container at {@code dimension:pos}, or
     * {@code null} if no player has it open (i.e., the transfer is automated).
     */
    public PlayerContext getPlayerContext(ResourceKey<Level> dimension, BlockPos pos) {
        return openContainers.get(new DimPos(dimension, pos));
    }
}
