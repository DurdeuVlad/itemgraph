package com.itemgraph.config;

import com.itemgraph.ingest.InternalObservationService;
import com.itemgraph.query.QueryLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemGraphOperationalSettingsTest {
    @Test
    void legacyOperationsOverloadRetainsTheTwentyTickDefault() {
        var service = InternalObservationService.getInstance();
        try {
            service.configureOperations(250, 100, 30_000, true);
            assertEquals(20, service.getQueueFrequencyTicks());
        } finally {
            service.stop();
            service.clear();
        }
    }

    @Test
    void defaultsMatchThePublishedGriefLoggerPageSizeAndImmutableRetentionPolicy() {
        var settings = new ItemGraphOperationalSettings(10, true, 250, 20, 100, 30_000, true, "indefinite");
        assertEquals(10, settings.maxPageSize());
        assertTrue(settings.serverSideOnly());
        assertEquals(250, settings.queuePollIntervalMs());
        assertEquals(20, settings.queueFrequencyTicks());
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
    void rejectsQueueFrequenciesOutsideGriefLoggerTickBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 0, 100, 30_000, true, "indefinite"));
        assertThrows(IllegalArgumentException.class,
                () -> new ItemGraphOperationalSettings(10, true, 250, 101, 100, 30_000, true, "indefinite"));
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

    @Test
    void settingsChangeOnlyAfterWorkerStopsAndRestartAppliesTheNewSnapshot() {
        var service = InternalObservationService.getInstance();
        int originalPageSize = QueryLimits.getConfiguredMaxPageSize();
        try {
            var initial = new ItemGraphOperationalSettings(10, true, 250, 20, 100, 30_000, true, "indefinite");
            initial.apply();
            assertEquals(10, QueryLimits.getConfiguredMaxPageSize());
            assertEquals(250, service.getQueuePollIntervalMs());
            assertEquals(20, service.getQueueFrequencyTicks());
            assertEquals(100, service.getMaxBatchSize());
            assertEquals(30_000, service.getDatabaseHeartbeatIntervalMs());

            service.start();
            var changed = new ItemGraphOperationalSettings(25, true, 500, 25, 250, 60_000, false, "indefinite");
            assertThrows(IllegalStateException.class, changed::apply);
            assertEquals(10, QueryLimits.getConfiguredMaxPageSize(),
                    "a rejected live reload must not partially change the query cap");
            assertEquals(250, service.getQueuePollIntervalMs());
            assertEquals(20, service.getQueueFrequencyTicks());
            assertEquals(100, service.getMaxBatchSize());
            assertEquals(30_000, service.getDatabaseHeartbeatIntervalMs());

            service.stop();
            changed.apply();
            assertEquals(25, QueryLimits.getConfiguredMaxPageSize());
            assertEquals(500, service.getQueuePollIntervalMs());
            assertEquals(25, service.getQueueFrequencyTicks());
            assertEquals(250, service.getMaxBatchSize());
            assertEquals(60_000, service.getDatabaseHeartbeatIntervalMs());
            assertFalse(service.isCaptureEnabled());
        } finally {
            service.stop();
            service.clear();
            service.configureOperations(250, 100, 30_000, true);
            QueryLimits.configureMaxPageSize(originalPageSize);
        }
    }
}
