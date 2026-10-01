package com.itemgraph.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
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
        assertRejectedValue(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, 0, "correlation.ground_bridge_max_seconds");
        assertRejectedValue(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, -10, "correlation.ground_bridge_max_seconds");
        assertRejectedValue(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, 86_401, "correlation.ground_bridge_max_seconds");
    }

    @Test
    void rejectsOutOfRangeIntegersBeforeNeoForgeCanNormalizeThem() {
        assertAcceptedRange(ItemGraphConfig.DATABASE_PORT, 1, 65_535);
        assertAcceptedRange(ItemGraphConfig.DATABASE_CONNECTION_TIMEOUT_MS, 250, 120_000);
        assertAcceptedRange(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, 1, 86_400);
        assertAcceptedRange(ItemGraphConfig.MAX_PAGE_SIZE, 1, 100);
        assertAcceptedRange(ItemGraphConfig.QUEUE_POLL_INTERVAL_MS, 10, 5_000);
        assertAcceptedRange(ItemGraphConfig.MAX_BATCH_SIZE, 1, 1_000);
        assertAcceptedRange(ItemGraphConfig.DATABASE_HEARTBEAT_INTERVAL_MS, 1_000, 3_600_000);

        assertRejectedValue(ItemGraphConfig.DATABASE_PORT, 0, "general.database_port");
        assertRejectedValue(ItemGraphConfig.DATABASE_PORT, 65_536, "general.database_port");
        assertRejectedValue(ItemGraphConfig.DATABASE_CONNECTION_TIMEOUT_MS, 249, "general.database_connection_timeout_ms");
        assertRejectedValue(ItemGraphConfig.DATABASE_CONNECTION_TIMEOUT_MS, 120_001, "general.database_connection_timeout_ms");
        assertRejectedValue(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, 0, "correlation.ground_bridge_max_seconds");
        assertRejectedValue(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, 86_401, "correlation.ground_bridge_max_seconds");
        assertRejectedValue(ItemGraphConfig.MAX_PAGE_SIZE, 0, "query.max_page_size");
        assertRejectedValue(ItemGraphConfig.MAX_PAGE_SIZE, 101, "query.max_page_size");
        assertRejectedValue(ItemGraphConfig.QUEUE_POLL_INTERVAL_MS, 9, "ingestion.poll_interval_ms");
        assertRejectedValue(ItemGraphConfig.QUEUE_POLL_INTERVAL_MS, 5_001, "ingestion.poll_interval_ms");
        assertRejectedValue(ItemGraphConfig.MAX_BATCH_SIZE, 0, "ingestion.max_batch_size");
        assertRejectedValue(ItemGraphConfig.MAX_BATCH_SIZE, 1_001, "ingestion.max_batch_size");
        assertRejectedValue(ItemGraphConfig.DATABASE_HEARTBEAT_INTERVAL_MS, 999, "operations.database_heartbeat_interval_ms");
        assertRejectedValue(ItemGraphConfig.DATABASE_HEARTBEAT_INTERVAL_MS, 3_600_001,
                "operations.database_heartbeat_interval_ms");
    }

    @Test
    void malformedConfigTypesFailWithTheExactKeyInsteadOfFallingBackToDefaults() {
        assertRejectedValue(ItemGraphConfig.MAX_PAGE_SIZE, "twenty-five", "query.max_page_size");
        assertRejectedValue(ItemGraphConfig.CAPTURE_ENABLED, "enabled", "capture.enabled");
        assertRejectedValue(ItemGraphConfig.DATABASE_BACKEND, 42, "general.database_backend");
    }

    @Test
    void configValuesRetainNeoForgeTypeMetadata() {
        assertConfigType(String.class, ItemGraphConfig.DATABASE_PATH, ItemGraphConfig.DATABASE_BACKEND,
                ItemGraphConfig.DATABASE_HOST, ItemGraphConfig.DATABASE_NAME, ItemGraphConfig.DATABASE_USERNAME,
                ItemGraphConfig.DATABASE_PASSWORD, ItemGraphConfig.DATABASE_SSL_MODE,
                ItemGraphConfig.GRIEFLOGGER_DATABASE_PATH, ItemGraphConfig.RAW_EVIDENCE_RETENTION);
        assertConfigType(Integer.class, ItemGraphConfig.DATABASE_PORT, ItemGraphConfig.DATABASE_CONNECTION_TIMEOUT_MS,
                ItemGraphConfig.QUEUE_POLL_INTERVAL_MS, ItemGraphConfig.MAX_BATCH_SIZE,
                ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS, ItemGraphConfig.MAX_PAGE_SIZE,
                ItemGraphConfig.DATABASE_HEARTBEAT_INTERVAL_MS);
        assertConfigType(Boolean.class, ItemGraphConfig.USE_INDEXES, ItemGraphConfig.DEBUG_LOGGING,
                ItemGraphConfig.CAPTURE_ENABLED, ItemGraphConfig.SERVER_SIDE_ONLY);
    }

    private static void assertConfigType(Class<?> expected, ModConfigSpec.ConfigValue<?>... configValues) {
        for (ModConfigSpec.ConfigValue<?> configValue : configValues) {
            assertEquals(expected, configValue.getSpec().getClazz(), String.join(".", configValue.getPath()));
        }
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

        // Invalid ranges fail before ModConfigSpec can normalize them to a bound/default.
        config.set(List.of("correlation", "ground_bridge_max_seconds"), -999);
        IllegalArgumentException underflow = assertThrows(IllegalArgumentException.class,
                () -> ItemGraphConfig.SPEC.correct(config));
        assertTrue(underflow.getMessage().contains("correlation.ground_bridge_max_seconds"));
        assertEquals(-999, config.getInt(List.of("correlation", "ground_bridge_max_seconds")),
                "Invalid values must be preserved for the startup validator to reject");

        // Overflow must be rejected too, without replacing the operator's invalid value.
        config.set(List.of("correlation", "ground_bridge_max_seconds"), 100_000);
        IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> ItemGraphConfig.SPEC.correct(config));
        assertTrue(overflow.getMessage().contains("correlation.ground_bridge_max_seconds"));
        assertEquals(100_000, config.getInt(List.of("correlation", "ground_bridge_max_seconds")));
    }

    private static void assertRejectedValue(ModConfigSpec.ConfigValue<?> configValue, Object value, String key) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> configValue.getSpec().test(value), key + " must reject " + value);
        assertTrue(error.getMessage().contains(key), key + " error should identify the full config key");
    }

    private static void assertAcceptedRange(ModConfigSpec.ConfigValue<Integer> configValue, int minimum, int maximum) {
        assertTrue(configValue.getSpec().test(minimum), String.join(".", configValue.getPath()) + " minimum");
        assertTrue(configValue.getSpec().test(maximum), String.join(".", configValue.getPath()) + " maximum");
    }
}
