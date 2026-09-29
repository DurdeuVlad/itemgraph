package com.itemgraph.listener;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.ingest.InternalObservationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class NativeItemActionEventListenerTest {

    @Test
    void rejectedProjectileSpawnIsNotRecorded() {
        // The RETURN boundary receives the authoritative addFreshEntity result.
        NativeItemActionEventListener.onProjectileAdded(null, false);
    }

    @Test
    void projectileRecorderPreservesItemQuantityAndUnknownEndpoint() {
        InternalObservationService service = mock(InternalObservationService.class);
        when(service.submit(any(InternalObservationService.InternalObservation.class))).thenReturn(true);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            NativeItemActionEventListener.recordProjectileObservation(
                    "THROW_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    10.0, 65.0, -4.0, 11.5, 65.0, -2.5,
                    new CanonicalItem("minecraft:ender_pearl", "fingerprint", null, null, null), 2,
                    "minecraft:ender_pearl");
        }

        ArgumentCaptor<InternalObservationService.InternalObservation> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalObservation.class);
        verify(service).submit(captured.capture());
        assertEquals("THROW_ITEM", captured.getValue().actionType());
        assertEquals("minecraft:ender_pearl", captured.getValue().item().itemId());
        assertEquals(2, captured.getValue().amount());
        assertEquals("UNKNOWN", captured.getValue().targetType());
        assertTrue(captured.getValue().sourceEventId() != null);
        org.junit.jupiter.api.Assertions.assertNull(captured.getValue().targetX());
        assertTrue(new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("projectile_shoot_attempt"));

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> audit =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(audit.capture());
        assertEquals("THROW_ITEM", audit.getValue().eventType());
        assertEquals("minecraft:ender_pearl", audit.getValue().subjectId());
        org.junit.jupiter.api.Assertions.assertTrue(audit.getValue().detail().contains("quantity=2"));
    }

    @Test
    void rejectedProjectileObservationDoesNotQueueLegacyProjection() {
        InternalObservationService service = mock(InternalObservationService.class);
        when(service.submit(any(InternalObservationService.InternalObservation.class))).thenReturn(false);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            NativeItemActionEventListener.recordProjectileObservation(
                    "THROW_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    10.0, 65.0, -4.0, 11.5, 65.0, -2.5,
                    new CanonicalItem("minecraft:ender_pearl", "fingerprint", null, null, null), 1,
                    "minecraft:ender_pearl");
        }
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never()).submitAuditEvent(any());
    }

    @Test
    void acceptedProjectileSpawnIsRawEvidenceWithoutSecondQuantityRow() {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            NativeItemActionEventListener.recordProjectileSpawnAccepted(
                    "SHOOT_ITEM", "player-uuid", "Alex", "minecraft:overworld",
                    11.5, 65.0, -2.5,
                    new CanonicalItem("minecraft:arrow", "fingerprint", null, null, null), 1,
                    "minecraft:arrow");
        }
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("PROJECTILE_SPAWN_ACCEPTED", captured.getValue().eventType());
        assertTrue(new String(captured.getValue().rawData(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"outcome\":\"accepted\""));
        assertTrue(!captured.getValue().detail().contains("quantity="));
        org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                .submit(any(InternalObservationService.InternalObservation.class));
    }
}
