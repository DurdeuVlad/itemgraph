package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NativeAuditEventListenerTest {

    @Test
    void canceledCommandIsNotRecordedAsExecuted() {
        CommandEvent event = mock(CommandEvent.class);
        when(event.isCanceled()).thenReturn(true);
        assertNotRecorded(listener -> listener.onCommand(event));
    }

    @Test
    void canceledChatIsNotRecorded() {
        ServerChatEvent event = mock(ServerChatEvent.class);
        when(event.isCanceled()).thenReturn(true);
        assertNotRecorded(listener -> listener.onChat(event));
    }

    @Test
    void canceledDeathIsNotRecordedAsKill() {
        LivingDeathEvent event = mock(LivingDeathEvent.class);
        when(event.isCanceled()).thenReturn(true);
        assertNotRecorded(listener -> listener.onLivingDeath(event));
    }

    private static void assertNotRecorded(java.util.function.Consumer<NativeAuditEventListener> invocation) {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            invocation.accept(new NativeAuditEventListener());
            verifyNoInteractions(service);
        }
    }
}
