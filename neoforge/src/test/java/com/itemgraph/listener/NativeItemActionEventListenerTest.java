package com.itemgraph.listener;

import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class NativeItemActionEventListenerTest {

    @Test
    void canceledProjectileSpawnIsNotRecorded() {
        EntityJoinLevelEvent event = mock(EntityJoinLevelEvent.class);
        org.mockito.Mockito.when(event.isCanceled()).thenReturn(true);

        // Cancellation is checked before touching the entity or persistence service.
        new NativeItemActionEventListener().onProjectileSpawned(event);
        verify(event).isCanceled();
        verifyNoMoreInteractions(event);
    }
}
