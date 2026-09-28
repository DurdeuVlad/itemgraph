package com.itemgraph.api;

import com.itemgraph.command.QueryDispatcher;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionService;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ItemGraphApiTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @TempDir
    Path tempDir;

    private ItemGraphServiceImpl service;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("itemgraph.db"));
        IngestionService.getInstance().getNodeManager().clearCaches();
        conn = DatabaseManager.getInstance().getConnection();
        service = new ItemGraphServiceImpl(null, 7);
        service.start();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        QueryDispatcher.shutdown();
        IngestionService.getInstance().getNodeManager().clearCaches();
        DatabaseManager.getInstance().close();
    }

    private SourceHandle register(String modId, String name) {
        RegistrationResult result = service.registerSource(
                SourceRegistration.of(modId, name)).join();
        assertNotNull(result.source(), result.message());
        return result.source();
    }

    private DirectObservation observation(long eventId, EndpointRef origin,
                                          EndpointRef destination, ItemSnapshot item) {
        return new DirectObservation(eventId, 1_700_000_000_000L + eventId,
                ObservationAction.DIRECT_OBSERVED, origin, destination, item,
                null, null, Map.of("fixture", "true"));
    }

    private ItemSnapshot diamond(int amount) {
        return ItemSnapshot.of("minecraft:diamond", amount, null, Map.of());
    }

    @Test
    void apiVersionValuesMatchPreviewContract() {
        assertEquals(1, ApiVersion.PREVIEW_1.number());
        assertEquals("preview", ApiVersion.PREVIEW_1.channel());
        assertTrue(ApiVersion.PREVIEW_1.preview());
        assertEquals("preview-1", ApiVersion.PREVIEW_1.label());
    }

    @Test
    void registrationPersistsAndDistinguishesRegistrationStates() {
        RegistrationResult first = service.registerSource(
                SourceRegistration.of("testmod", "Test Mod")).join();
        assertEquals(RegistrationStatus.REGISTERED, first.status());
        assertEquals("testmod", first.source().modId());
        assertEquals("Test Mod", first.source().displayName());
        assertEquals(ApiVersion.PREVIEW_1, first.source().apiVersion());

        RegistrationResult unchanged = service.registerSource(
                SourceRegistration.of("testmod", "Test Mod")).join();
        assertEquals(RegistrationStatus.UNCHANGED, unchanged.status());

        RegistrationResult updated = service.registerSource(
                SourceRegistration.of("testmod", "Test Mod Plus")).join();
        assertEquals(RegistrationStatus.UPDATED, updated.status());
        assertEquals("Test Mod Plus", updated.source().displayName());
    }

    @Test
    void invalidRegistrationReturnsExplicitInvalidInput() {
        RegistrationResult badMod = service.registerSource(
                SourceRegistration.of("Bad-Mod", "Bad")).join();
        assertEquals(RegistrationStatus.INVALID_INPUT, badMod.status());
        assertEquals("INVALID_MOD_ID", badMod.errorCode());
        assertNull(badMod.source());

        RegistrationResult blankName = service.registerSource(
                SourceRegistration.of("testmod", " ")).join();
        assertEquals(RegistrationStatus.INVALID_INPUT, blankName.status());
    }

    @Test
    void observationPersistsAndDeduplicatesBySourceModAndEventId() throws Exception {
        SourceHandle first = register("testmod", "Test Mod");
        SourceHandle second = register("othermod", "Other Mod");
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");
        UnknownEndpoint unknown = new UnknownEndpoint(Level.OVERWORLD, "fixture");

        assertEquals(SubmissionStatus.PERSISTED,
                service.submitObservation(first, observation(42, player, unknown, diamond(3))).join().status());
        assertEquals(SubmissionStatus.DUPLICATE,
                service.submitObservation(first, observation(42, player, unknown, diamond(3))).join().status());
        assertEquals(SubmissionStatus.PERSISTED,
                service.submitObservation(second, observation(42, player, unknown, diamond(3))).join().status());

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*), COUNT(DISTINCT source_type) FROM ig_observations WHERE source_event_id = 42")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals(2, rs.getInt(2));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT source_type, correlation_status, raw_data FROM ig_observations ORDER BY source_type LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getString(1).startsWith("EXTERNAL_API:"));
                assertEquals("PENDING", rs.getString(2));
                String raw = new String(rs.getBytes(3), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(raw.contains("\"api_version\":1"));
                assertTrue(raw.contains("\"fixture\":\"true\""));
            }
        }
    }

    @Test
    void externalInventoryIdentityIsDurableAcrossDisplayAndLocationChanges() throws Exception {
        SourceHandle source = register("storage_mod", "Storage Mod");
        ExternalInventoryEndpoint first = new ExternalInventoryEndpoint(
                "storage_mod", "A-1/Backpack", "Backpack A",
                new WorldLocation(Level.OVERWORLD, new BlockPos(10, 64, -20)));
        ExternalInventoryEndpoint moved = new ExternalInventoryEndpoint(
                "storage_mod", "A-1/Backpack", "Renamed Backpack",
                new WorldLocation(Level.NETHER, new BlockPos(100, 70, 200)));
        ExternalInventoryEndpoint otherOwner = new ExternalInventoryEndpoint(
                "other_storage", "A-1/Backpack", "Other", null);
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");

        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(1, first, player, diamond(1))).join().status());
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(2, moved, player, diamond(1))).join().status());
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(3, otherOwner, player, diamond(1))).join().status());

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*), COUNT(DISTINCT external_key), MIN(level_id), MIN(x)
                FROM ig_nodes WHERE node_type = 'EXTERNAL_INVENTORY'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
                assertEquals(2, rs.getInt(2));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT custom_label, level_id, x, y, z FROM ig_nodes
                WHERE external_key = 'storage_mod/A-1/Backpack'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("Renamed Backpack", rs.getString(1));
                assertEquals("minecraft:the_nether", rs.getString(2));
                assertEquals(100, rs.getInt(3));
                assertEquals(70, rs.getInt(4));
                assertEquals(200, rs.getInt(5));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT level_id, x FROM ig_nodes
                WHERE external_key = 'other_storage/A-1/Backpack'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("external:other_storage", rs.getString(1));
                rs.getDouble(2);
                assertTrue(rs.wasNull(), "coordinate-less external inventory must not be UNKNOWN or located");
            }
        }
    }

    @Test
    void itemTraceReturnsObservedOpaqueEvidenceAndCopiedDtos() throws Exception {
        SourceHandle source = register("testmod", "Test Mod");
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");
        UnknownEndpoint unknown = new UnknownEndpoint(Level.OVERWORLD, "fixture");
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(7, player, unknown, diamond(9))).join().status());

        QueryResult result = service.traceItem(
                ItemQuery.itemId("minecraft:diamond"), QueryOptions.defaults()).join();
        assertEquals(QueryStatus.OK, result.status());
        assertNotNull(result.result());
        assertEquals(1, result.result().hops().size());

        FlowHop hop = result.result().hops().get(0);
        assertEquals(Provenance.OBSERVED, hop.provenance());
        assertEquals(EvidenceKind.OBSERVATION, hop.evidence().kind());
        assertTrue(hop.evidence().value().startsWith("itemgraph:observation:"));
        assertNull(hop.confidence());
        assertTrue(hop.supportingEvidence().isEmpty());
        assertEquals(EndpointKind.PLAYER, hop.origin().kind());
        assertEquals("player:" + PLAYER, hop.origin().stableKey());
        assertNull(hop.origin().location(), "a player inventory endpoint must not leak first-seen coordinates");
        assertEquals("minecraft:diamond", hop.item().itemId());
        assertNotNull(hop.item().fingerprintHash());

        assertThrows(UnsupportedOperationException.class,
                () -> result.result().hops().add(hop));
    }

    @Test
    void ambiguousAndMissingItemQueriesReturnBoundedResults() throws Exception {
        SourceHandle source = register("testmod", "Test Mod");
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");
        UnknownEndpoint unknown = new UnknownEndpoint(Level.OVERWORLD, "fixture");
        service.submitObservation(source, observation(1, player, unknown,
                ItemSnapshot.of("minecraft:diamond", 1, "Relic Sword", Map.of()))).join();
        service.submitObservation(source, observation(2, player, unknown,
                ItemSnapshot.of("minecraft:emerald", 1, "Relic Sword", Map.of()))).join();

        QueryResult ambiguous = service.traceItem(
                ItemQuery.customName("Relic"), QueryOptions.defaults()).join();
        assertEquals(QueryStatus.AMBIGUOUS, ambiguous.status());
        assertNotNull(ambiguous.result());
        assertEquals(2, ambiguous.result().itemCandidates().size());
        assertTrue(ambiguous.result().hops().isEmpty());

        QueryResult missing = service.traceItem(
                ItemQuery.itemId("minecraft:nether_star"), QueryOptions.defaults()).join();
        assertEquals(QueryStatus.NOT_FOUND, missing.status());
        assertNull(missing.result());
    }

    @Test
    void externalInventoryTraceUsesDurableStableKey() {
        SourceHandle source = register("storage_mod", "Storage Mod");
        ExternalInventoryEndpoint inventory = new ExternalInventoryEndpoint(
                "storage_mod", "remote-1", "Remote Inventory", null);
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(8, inventory, player, diamond(2))).join().status());

        QueryResult result = service.traceExternalInventory(
                inventory, QueryOptions.defaults()).join();
        assertEquals(QueryStatus.OK, result.status());
        assertEquals(1, result.result().hops().size());
        FlowHop hop = result.result().hops().get(0);
        assertEquals(EndpointKind.EXTERNAL_INVENTORY, hop.origin().kind());
        assertEquals("external:storage_mod:remote-1", hop.origin().stableKey());
        assertNull(hop.origin().location());
        assertEquals(EndpointKind.PLAYER, hop.destination().kind());
    }

    @Test
    void invalidAndBoundedQueryInputsAreExplicit() {
        QueryResult multipleSelectors = service.traceItem(
                new ItemQuery("minecraft:diamond", "name", null), null).join();
        assertEquals(QueryStatus.INVALID_INPUT, multipleSelectors.status());
        assertEquals("INVALID_QUERY", multipleSelectors.errorCode());
        assertEquals(QueryStatus.INVALID_INPUT, service.traceItem(
                ItemQuery.itemId("minecraft:diamond"), new QueryOptions(0, null)).join().status());
        assertEquals(QueryStatus.INVALID_INPUT, service.traceItem(
                ItemQuery.itemId("minecraft:diamond"), new QueryOptions(20, 0L)).join().status());

        QueryResult capped = service.traceItem(
                ItemQuery.itemId("minecraft:diamond"), new QueryOptions(10_000, null)).join();
        assertEquals(QueryStatus.NOT_FOUND, capped.status());
    }

    @Test
    void invalidObservationsAndOversizedPayloadAreRejectedWithoutPersistence() throws Exception {
        SourceHandle source = register("testmod", "Test Mod");
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");
        UnknownEndpoint unknown = new UnknownEndpoint(Level.OVERWORLD, "fixture");

        SubmissionResult badEvent = service.submitObservation(source,
                observation(0, player, unknown, diamond(1))).join();
        assertEquals(SubmissionStatus.INVALID_INPUT, badEvent.status());
        assertEquals("INVALID_EVENT_ID", badEvent.errorCode());
        SubmissionResult badItem = service.submitObservation(source,
                observation(1, player, unknown, ItemSnapshot.of("minecraft:diamond", 0, null, Map.of())))
                .join();
        assertEquals(SubmissionStatus.INVALID_INPUT, badItem.status());
        assertEquals("INVALID_ITEM", badItem.errorCode());

        Map<String, String> largeAttributes = Map.of("large", "x".repeat(17 * 1024));
        assertEquals(SubmissionStatus.INVALID_INPUT, service.submitObservation(source,
                new DirectObservation(2, 1_700_000_000_000L, ObservationAction.DIRECT_OBSERVED,
                        player, unknown, diamond(1), null, null, largeAttributes)).join().status());

        Map<String, String> contractSizedComponents = Map.of(
                "minecraft:lore", "x".repeat(20 * 1024));
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(5, player, unknown,
                        ItemSnapshot.of("minecraft:diamond", 1, null, contractSizedComponents)))
                .join().status());

        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ig_observations");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void malformedMapValuesAndQueryCustomNamesReturnInvalidInput() {
        SourceHandle source = register("testmod", "Test Mod");
        Map<String, String> nullAttributes = new HashMap<>();
        nullAttributes.put("optional", null);
        assertEquals(SubmissionStatus.INVALID_INPUT, service.submitObservation(source,
                new DirectObservation(3, 1_700_000_000_000L, ObservationAction.DIRECT_OBSERVED,
                        new PlayerEndpoint(PLAYER, "Tester"),
                        new UnknownEndpoint(Level.OVERWORLD, "fixture"),
                        diamond(1), null, null, nullAttributes)).join().status());

        Map<String, String> nullComponents = new HashMap<>();
        nullComponents.put("minecraft:custom_name", null);
        assertEquals(SubmissionStatus.INVALID_INPUT, service.submitObservation(source,
                observation(4, new PlayerEndpoint(PLAYER, "Tester"),
                        new UnknownEndpoint(Level.OVERWORLD, "fixture"),
                        ItemSnapshot.of("minecraft:diamond", 1, null, nullComponents)))
                .join().status());

        assertEquals(QueryStatus.INVALID_INPUT, service.traceItem(
                ItemQuery.customName(" "), null).join().status());
        assertEquals(QueryStatus.INVALID_INPUT, service.traceItem(
                ItemQuery.customName("x".repeat(257)), null).join().status());
    }

    @Test
    void fingerprintPayloadSeparatorsCannotBeForgedAcrossFields() throws Exception {
        SourceHandle source = register("testmod", "Test Mod");
        PlayerEndpoint player = new PlayerEndpoint(PLAYER, "Tester");
        UnknownEndpoint unknown = new UnknownEndpoint(Level.OVERWORLD, "fixture");

        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(11, player, unknown,
                        ItemSnapshot.of("minecraft:diamond", 1, "named;damage=1", Map.of())))
                .join().status());
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(12, player, unknown,
                        ItemSnapshot.of("minecraft:diamond", 1, "named",
                                Map.of("minecraft:damage", "1"))))
                .join().status());
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(13, player, unknown,
                        ItemSnapshot.of("minecraft:diamond", 1, null,
                                Map.of("test:a", "x", "test:b", "y"))))
                .join().status());
        assertEquals(SubmissionStatus.PERSISTED, service.submitObservation(source,
                observation(14, player, unknown,
                        ItemSnapshot.of("minecraft:diamond", 1, null,
                                Map.of("test:a", "x,test:b=y"))))
                .join().status());

        Set<String> hashes = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT fingerprint_hash FROM ig_item_fingerprints");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                hashes.add(rs.getString(1));
            }
        }
        assertEquals(4, hashes.size());
    }

    @Test
    void sourceHandleIsServiceIssuedAndStaleHandlesAreRejected() throws Exception {
        assertTrue(Modifier.isFinal(SourceHandle.class.getModifiers()));
        assertFalse(Modifier.isPublic(
                SourceHandle.class.getDeclaredConstructor(
                        String.class, String.class, ApiVersion.class, long.class).getModifiers()));

        SourceHandle stale = new SourceHandle("testmod", "Test Mod", ApiVersion.PREVIEW_1, 6);
        SubmissionResult result = service.submitObservation(stale,
                observation(9, new PlayerEndpoint(PLAYER, "Tester"),
                        new UnknownEndpoint(Level.OVERWORLD, "fixture"), diamond(1))).join();
        assertEquals(SubmissionStatus.INVALID_INPUT, result.status());
        assertEquals("STALE_SOURCE", result.errorCode());
    }

    @Test
    void databaseUnavailableIsAnExplicitResult() {
        register("testmod", "Test Mod");
        service.shutdown();
        DatabaseManager.getInstance().close();

        ItemGraphServiceImpl unavailable = new ItemGraphServiceImpl(null, 8);
        SourceHandle unavailableGeneration = new SourceHandle(
                "testmod", "Test Mod", ApiVersion.PREVIEW_1, 8);
        unavailable.start();
        try {
            assertEquals(RegistrationStatus.DATABASE_UNAVAILABLE,
                    unavailable.registerSource(SourceRegistration.of("testmod", "Test Mod"))
                            .join().status());
            assertEquals(SubmissionStatus.DATABASE_UNAVAILABLE,
                    unavailable.submitObservation(unavailableGeneration,
                            observation(10, new PlayerEndpoint(PLAYER, "Tester"),
                                    new UnknownEndpoint(Level.OVERWORLD, "fixture"), diamond(1)))
                            .join().status());
            assertEquals(QueryStatus.DATABASE_UNAVAILABLE,
                    unavailable.traceItem(ItemQuery.itemId("minecraft:diamond"), null)
                            .join().status());
        } finally {
            unavailable.shutdown();
        }
    }

    @Test
    void shutdownChangesExistingServiceToShutdownResults() {
        SourceHandle source = register("testmod", "Test Mod");
        service.shutdown();

        SubmissionResult submission = service.submitObservation(source,
                observation(1, new PlayerEndpoint(PLAYER, "Tester"),
                        new UnknownEndpoint(Level.OVERWORLD, "fixture"), diamond(1))).join();
        assertEquals(SubmissionStatus.SHUTDOWN, submission.status());
        assertEquals(QueryStatus.SHUTDOWN, service.traceItem(
                ItemQuery.itemId("minecraft:diamond"), null).join().status());
    }
}
