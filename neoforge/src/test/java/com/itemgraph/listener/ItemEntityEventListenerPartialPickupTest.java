package com.itemgraph.listener;

import com.itemgraph.listener.ItemEntityEventListener.PendingOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ItemEntityEventListener#resolvePendingPickup} (partial-pickup
 * Pre/Post pairing).
 *
 * <p>NeoForge 21.1.248 gates {@code ItemEntityPickupEvent.Post} on
 * {@code Inventory.add()} returning true, which is false for a partial absorb, so a
 * reduced-but-alive item entity after a Pre is the only observable signal of a
 * partial pickup.
 */
class ItemEntityEventListenerPartialPickupTest {

    private static final long NOW = 1_000_000L;
    private static final long EXPIRES = NOW + 1_000;

    @Test
    void emitsWhenAliveEntityCountDecreased() {
        // 10-stack entity reduced to 9 while still alive -> absorbed 1
        assertEquals(PendingOutcome.EMIT,
                ItemEntityEventListener.resolvePendingPickup(10, 9, true, NOW, EXPIRES));
    }

    @Test
    void emitsForFullAbsorbThatSomehowMissedPost() {
        assertEquals(PendingOutcome.EMIT,
                ItemEntityEventListener.resolvePendingPickup(10, 0, true, NOW, EXPIRES));
    }

    @Test
    void keepsWaitingWhenCountUnchangedAndNotExpired() {
        assertEquals(PendingOutcome.KEEP,
                ItemEntityEventListener.resolvePendingPickup(10, 10, true, NOW, EXPIRES));
    }

    @Test
    void dropsWhenCountUnchangedAndExpired() {
        assertEquals(PendingOutcome.DROP,
                ItemEntityEventListener.resolvePendingPickup(10, 10, true, EXPIRES, EXPIRES));
    }

    @Test
    void dropsRemovedEntityEvenIfCountDropped() {
        // Merge/despawn also zero the count; a removed entity must not emit.
        assertEquals(PendingOutcome.DROP,
                ItemEntityEventListener.resolvePendingPickup(10, 0, false, NOW, EXPIRES));
    }

    @Test
    void keepsWhenCountGrewViaMergeIn() {
        // A neighbour merging into this entity raises the count; not an absorb.
        assertEquals(PendingOutcome.KEEP,
                ItemEntityEventListener.resolvePendingPickup(10, 25, true, NOW, EXPIRES));
    }
}
