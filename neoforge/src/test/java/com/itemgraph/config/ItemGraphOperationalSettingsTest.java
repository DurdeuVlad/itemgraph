package com.itemgraph.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemGraphOperationalSettingsTest {
    @Test
    void defaultsMatchThePublishedGriefLoggerPageSizeAndImmutableRetentionPolicy() {
        var settings = new ItemGraphOperationalSettings(10, true, 250, 100, 30_000, true, "indefinite");
        assertEquals(10, settings.maxPageSize());
        assertTrue(settings.serverSideOnly());
        assertEquals(250, settings.queuePollIntervalMs());
        assertEquals(100, settings.maxBatchSize());
        assertEquals(30_000, settings.databaseHeartbeatIntervalMs());
        assertTrue(settings.captureEnabled());
        assertEquals("indefinite", settings.rawEvidenceRetention());
    }

    @Test
    void rejectsPageSizesOutsideTheDocumentedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(0, true, 250, 100, 30_000, true, "indefinite"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(101, true, 250, 100, 30_000, true, "indefinite"));
    }

    @Test
    void rejectsNetworkHeartbeatIntervalsOutsideTheDocumentedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 100, 999, true, "indefinite"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 100, 3_600_001, true, "indefinite"));
    }

    @Test
    void rejectsUnsupportedClientModeAndDestructiveRetentionValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, false, 250, 100, 30_000, true, "indefinite"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 100, 30_000, true, "30d"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 100, 30_000, true, "archive_then_purge"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 9, 100, 30_000, true, "indefinite"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 1001, 30_000, true, "indefinite"));
    }
}
