package com.itemgraph.command;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InspectionServiceTest {

    private final InspectionService service = InspectionService.getInstance();

    @AfterEach
    void tearDown() {
        service.clear();
    }

    @Test
    void toggleIsDeterministicAndScopedByPlayerUuid() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(service.toggle(first));
        assertTrue(service.isEnabled(first));
        assertFalse(service.isEnabled(second));

        assertTrue(service.toggle(second));
        assertTrue(service.isEnabled(first));
        assertTrue(service.isEnabled(second));

        assertFalse(service.toggle(first));
        assertFalse(service.isEnabled(first));
        assertTrue(service.isEnabled(second));
    }

    @Test
    void explicitSetAndClearDoNotToggleImplicitly() {
        UUID player = UUID.randomUUID();

        assertTrue(service.setEnabled(player, true));
        assertFalse(service.setEnabled(player, true));
        assertTrue(service.isEnabled(player));

        assertTrue(service.setEnabled(player, false));
        assertFalse(service.setEnabled(player, false));
        assertFalse(service.isEnabled(player));
    }

    @Test
    void clearRemovesOnePlayerAndClearAllRemovesEveryPlayer() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.setEnabled(first, true);
        service.setEnabled(second, true);

        service.clear(first);
        assertFalse(service.isEnabled(first));
        assertTrue(service.isEnabled(second));

        service.clear();
        assertFalse(service.isEnabled(second));
    }
}
