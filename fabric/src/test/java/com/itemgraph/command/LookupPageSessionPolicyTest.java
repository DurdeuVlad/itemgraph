package com.itemgraph.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookupPageSessionPolicyTest {
    @Test
    void pageSessionsRemainValidForThirtyMinutesThenExpire() {
        long createdAtMs = 1_000L;

        assertFalse(ItemGraphCommands.pageSessionExpired(createdAtMs, createdAtMs + 1_799_999L));
        assertFalse(ItemGraphCommands.pageSessionExpired(createdAtMs, createdAtMs + 1_800_000L));
        assertTrue(ItemGraphCommands.pageSessionExpired(createdAtMs, createdAtMs + 1_800_001L));
    }

    @Test
    void pagingControlStopsAtTheConfiguredOffsetCeiling() {
        assertTrue(ItemGraphCommands.canCheckNextAuditPage(1, 30, 0, 30));
        assertFalse(ItemGraphCommands.canCheckNextAuditPage(334, 30, 9_990, 30));
        assertFalse(ItemGraphCommands.canCheckNextAuditPage(1, 30, 0, 29));
    }
}
