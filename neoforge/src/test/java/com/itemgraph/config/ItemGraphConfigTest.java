package com.itemgraph.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItemGraphConfigTest {

    @Test
    void testDefaultValues() {
        assertEquals("itemgraph/itemgraph.db", ItemGraphConfig.DATABASE_PATH.getDefault());
        assertEquals("sqlite", ItemGraphConfig.DATABASE_BACKEND.getDefault());
        assertEquals(3306, ItemGraphConfig.DATABASE_PORT.getDefault());
        assertEquals("itemgraph", ItemGraphConfig.DATABASE_NAME.getDefault());
        assertEquals("itemgraph", ItemGraphConfig.DATABASE_USERNAME.getDefault());
        assertEquals(5_000, ItemGraphConfig.DATABASE_CONNECTION_TIMEOUT_MS.getDefault());
        assertEquals(Boolean.TRUE, ItemGraphConfig.USE_INDEXES.getDefault());
        assertEquals("disable", ItemGraphConfig.DATABASE_SSL_MODE.getDefault());
        assertEquals("database.db", ItemGraphConfig.GRIEFLOGGER_DATABASE_PATH.getDefault());
        assertEquals(Boolean.FALSE, ItemGraphConfig.DEBUG_LOGGING.getDefault());
        assertEquals(300, ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS.getDefault());
        assertEquals(10, ItemGraphConfig.MAX_PAGE_SIZE.getDefault());
        assertEquals(Boolean.TRUE, ItemGraphConfig.SERVER_SIDE_ONLY.getDefault());
        assertEquals("indefinite", ItemGraphConfig.RAW_EVIDENCE_RETENTION.getDefault());
        assertEquals(250, ItemGraphConfig.QUEUE_POLL_INTERVAL_MS.getDefault());
        assertEquals(100, ItemGraphConfig.MAX_BATCH_SIZE.getDefault());
        assertEquals(Boolean.TRUE, ItemGraphConfig.CAPTURE_ENABLED.getDefault());
        assertEquals(300, ItemGraphConfig.DEFAULT_GROUND_BRIDGE_MAX_SECONDS);
    }

    @Test
    void testConfigPaths() {
        assertEquals(List.of("general", "database_path"), ItemGraphConfig.DATABASE_PATH.getPath());
        assertEquals(List.of("general", "database_backend"), ItemGraphConfig.DATABASE_BACKEND.getPath());
        assertEquals(List.of("general", "database_connection_timeout_ms"), ItemGraphConfig.DATABASE_CONNECTION_TIMEOUT_MS.getPath());
        assertEquals(List.of("storage", "use_indexes"), ItemGraphConfig.USE_INDEXES.getPath());
        assertEquals(List.of("general", "grieflogger_database_path"), ItemGraphConfig.GRIEFLOGGER_DATABASE_PATH.getPath());
        assertEquals(List.of("general", "debug_logging"), ItemGraphConfig.DEBUG_LOGGING.getPath());
        assertEquals(List.of("correlation", "ground_bridge_max_seconds"), ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS.getPath());
        assertEquals(List.of("query", "max_page_size"), ItemGraphConfig.MAX_PAGE_SIZE.getPath());
        assertEquals(List.of("operations", "server_side_only"), ItemGraphConfig.SERVER_SIDE_ONLY.getPath());
        assertEquals(List.of("retention", "raw_evidence"), ItemGraphConfig.RAW_EVIDENCE_RETENTION.getPath());
        assertEquals(List.of("ingestion", "poll_interval_ms"), ItemGraphConfig.QUEUE_POLL_INTERVAL_MS.getPath());
        assertEquals(List.of("ingestion", "max_batch_size"), ItemGraphConfig.MAX_BATCH_SIZE.getPath());
        assertEquals(List.of("capture", "enabled"), ItemGraphConfig.CAPTURE_ENABLED.getPath());
    }

    @Test
    void testGroundBridgeMaxSecondsRangeValidation() {
        var spec = ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS.getSpec();

        // Valid values
        assertTrue(spec.test(300), "Default 300s must be valid");
        assertTrue(spec.test(1), "Lower bound 1s must be valid");
        assertTrue(spec.test(86_400), "Upper bound 86400s (24h) must be valid");

        // Invalid values
        assertFalse(spec.test(0), "0s must be rejected (min is 1s)");
        assertFalse(spec.test(-10), "Negative values must be rejected");
        assertFalse(spec.test(86_401), "Values above 86400s must be rejected");
    }

    @Test
    void testSpecNotNullAndNotEmpty() {
        assertNotNull(ItemGraphConfig.SPEC);
        assertFalse(ItemGraphConfig.SPEC.isEmpty());
    }

    @Test
    void testNightConfigValidationAndCorrection() {
        CommentedConfig config = CommentedConfig.inMemory();

        // An empty config is not yet correct according to the spec
        assertFalse(ItemGraphConfig.SPEC.isCorrect(config), "Empty config should not be considered correct");

        // correct() should populate all defaults from the spec
        ItemGraphConfig.SPEC.correct(config);
        assertTrue(ItemGraphConfig.SPEC.isCorrect(config), "Config populated by correct() must be correct");
        assertEquals("itemgraph/itemgraph.db", config.get(List.of("general", "database_path")));
        assertEquals("database.db", config.get(List.of("general", "grieflogger_database_path")));
        assertEquals(Boolean.FALSE, config.get(List.of("general", "debug_logging")));
        assertEquals(300, config.getInt(List.of("correlation", "ground_bridge_max_seconds")));
        assertEquals(10, config.getInt(List.of("query", "max_page_size")));
        assertEquals(Boolean.TRUE, config.get(List.of("operations", "server_side_only")));
        assertEquals("indefinite", config.get(List.of("retention", "raw_evidence")));
        assertEquals(250, config.getInt(List.of("ingestion", "poll_interval_ms")));
        assertEquals(100, config.getInt(List.of("ingestion", "max_batch_size")));
        assertEquals(Boolean.TRUE, config.get(List.of("capture", "enabled")));

        // Setting a valid custom value preserves correctness
        config.set(List.of("correlation", "ground_bridge_max_seconds"), 600);
        assertTrue(ItemGraphConfig.SPEC.isCorrect(config), "Custom in-range value must be correct");

        // Setting an invalid out-of-range value violates spec correctness
        config.set(List.of("correlation", "ground_bridge_max_seconds"), -999);
        assertFalse(ItemGraphConfig.SPEC.isCorrect(config), "Out-of-range value must fail isCorrect check");

        // correct() clamps underflowing value to min bound (1)
        ItemGraphConfig.SPEC.correct(config);
        assertEquals(1, config.getInt(List.of("correlation", "ground_bridge_max_seconds")),
                "Underflowing ground_bridge_max_seconds must be clamped to min bound of 1s");
        assertTrue(ItemGraphConfig.SPEC.isCorrect(config), "Config must be correct after clamp to min bound");

        // Overflowing value is clamped to max bound (86,400)
        config.set(List.of("correlation", "ground_bridge_max_seconds"), 100_000);
        assertFalse(ItemGraphConfig.SPEC.isCorrect(config));
        ItemGraphConfig.SPEC.correct(config);
        assertEquals(86_400, config.getInt(List.of("correlation", "ground_bridge_max_seconds")),
                "Overflowing ground_bridge_max_seconds must be clamped to max bound of 86400s");
        assertTrue(ItemGraphConfig.SPEC.isCorrect(config), "Config must be correct after clamp to max bound");
    }
}
