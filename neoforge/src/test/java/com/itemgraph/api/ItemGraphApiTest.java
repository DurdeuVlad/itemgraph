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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentCaptor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        assertEquals(2, ApiVersion.PREVIEW_2.number());
        assertEquals("preview-2", ItemGraphApi.API_VERSION.label());
        assertEquals(ApiVersion.PREVIEW_2, service.apiVersion());
    }

    @Test
    void apiNegotiationRequiresAnExactPositivePreviewNumber() throws Exception {
        long evidenceBefore = observationCount();
        ApiCompatibility compatible = ItemGraphApi.negotiate(2);
        assertTrue(compatible.compatible());
        assertEquals(ApiCompatibility.Status.COMPATIBLE, compatible.status());
        assertEquals(2, compatible.requiredVersion());
        assertEquals(2, compatible.runtimeVersion());

        ApiCompatibility oldVersion = ItemGraphApi.negotiate(1);
        assertFalse(oldVersion.compatible());
        assertEquals(ApiCompatibility.Status.INCOMPATIBLE, oldVersion.status());
        assertEquals("ItemGraph API version mismatch: consumer requires preview-1 but runtime provides preview-2",
                oldVersion.message());

        ApiCompatibility futureVersion = ItemGraphApi.negotiate(3);
        assertFalse(futureVersion.compatible());
        assertEquals(ApiCompatibility.Status.INCOMPATIBLE, futureVersion.status());
        assertEquals("ItemGraph API version mismatch: consumer requires preview-3 but runtime provides preview-2",
                futureVersion.message());

        ApiCompatibility invalidVersion = ItemGraphApi.negotiate(0);
        assertFalse(invalidVersion.compatible());
        assertTrue(invalidVersion.message().contains("must be positive"));
        assertEquals(evidenceBefore, observationCount(), "negotiation must not persist evidence");
    }

    private long observationCount() throws Exception {
        try (PreparedStatement statement = conn.prepareStatement("SELECT COUNT(*) FROM ig_observations");
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    @Test
    void registrationPersistsAndDistinguishesRegistrationStates() {
        RegistrationResult first = service.registerSource(
                SourceRegistration.of("testmod", "Test Mod")).join();
        assertEquals(RegistrationStatus.REGISTERED, first.status());
        assertEquals("testmod", first.source().modId());
        assertEquals("Test Mod", first.source().displayName());
        assertEquals(ApiVersion.PREVIEW_2, first.source().apiVersion());

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
                assertTrue(raw.contains("\"api_version\":2"));
                assertTrue(raw.contains("\"evidence_class\":\"OBSERVED\""));
                assertTrue(raw.contains("\"source_reliability\":\"DIRECT_STATE_DELTA\""));
                assertTrue(raw.contains("\"privacy_class\":\"SENSITIVE_LOCATION\""));
                assertTrue(raw.contains("\"fixture\":\"true\""));
            }
        }
    }

    @Test
    void queuedApiObservationsRetainSubmissionOrderSourceIdsAndPositiveQuantityDirection() throws Exception {
        SourceHandle source = register("testmod", "Test Mod");
        ExternalInventoryEndpoint store = new ExternalInventoryEndpoint(
                "testmod", "fixture-store", "Fixture store", null);
        UnknownEndpoint unknown = new UnknownEndpoint(Level.OVERWORLD, "explicit fixture boundary");
        DirectObservation creation = new DirectObservation(
                900, 1_700_000_000_002L, ObservationAction.CREATE_ITEM,
                unknown, store, diamond(3), null, null, Map.of());
        DirectObservation destruction = new DirectObservation(
                901, 1_700_000_000_001L, ObservationAction.DESTROY_ITEM,
                store, unknown, diamond(2), null, null, Map.of());

        CompletableFuture<SubmissionResult> first = service.submitObservation(source, creation);
        CompletableFuture<SubmissionResult> second = service.submitObservation(source, destruction);
        assertEquals(SubmissionStatus.PERSISTED, first.join().status());
        assertEquals(SubmissionStatus.PERSISTED, second.join().status());

        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT source_event_id, action_type, amount, raw_data
                FROM ig_observations WHERE source_type = 'EXTERNAL_API:testmod' ORDER BY id
                """ ); ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(900L, rows.getLong("source_event_id"));
            assertEquals("CREATE_ITEM", rows.getString("action_type"));
            assertEquals(3, rows.getInt("amount"));
            String createRaw = new String(rows.getBytes("raw_data"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(createRaw.contains("\"source_event_id\":900"));
            assertTrue(createRaw.contains("\"origin\":{\"kind\":\"UNKNOWN\""));
            assertTrue(createRaw.contains("\"destination\":{\"kind\":\"EXTERNAL_INVENTORY\""));

            assertTrue(rows.next());
            assertEquals(901L, rows.getLong("source_event_id"));
            assertEquals("DESTROY_ITEM", rows.getString("action_type"));
            assertEquals(2, rows.getInt("amount"));
            String destroyRaw = new String(rows.getBytes("raw_data"), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(destroyRaw.contains("\"origin\":{\"kind\":\"EXTERNAL_INVENTORY\""));
            assertTrue(destroyRaw.contains("\"destination\":{\"kind\":\"UNKNOWN\""));
            assertFalse(rows.next());
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
    void automationEndpointIdentityIsStableAndSeparatesDimensionSlotPolicyAndSide() {
        AutomationEndpoint endpoint = AutomationEndpoint.blockInventory(
                "storage_mod", "Storage", Level.OVERWORLD, new BlockPos(10, 64, -20),
                "slot:3", Direction.NORTH);
        AutomationEndpoint restarted = AutomationEndpoint.blockInventory(
                "storage_mod", "Renamed Storage", Level.OVERWORLD, new BlockPos(10, 64, -20),
                "slot:3", Direction.NORTH);
        AutomationEndpoint otherSlot = AutomationEndpoint.blockInventory(
                "storage_mod", "Storage", Level.OVERWORLD, new BlockPos(10, 64, -20),
                "slot:4", Direction.NORTH);
        AutomationEndpoint otherSide = AutomationEndpoint.blockInventory(
                "storage_mod", "Storage", Level.OVERWORLD, new BlockPos(10, 64, -20),
                "slot:3", Direction.SOUTH);
        AutomationEndpoint otherDimension = AutomationEndpoint.blockInventory(
                "storage_mod", "Storage", Level.NETHER, new BlockPos(10, 64, -20),
                "slot:3", Direction.NORTH);

        assertEquals(endpoint.reference().inventoryId(), restarted.reference().inventoryId());
        assertTrue(endpoint.reference().inventoryId().matches("automation:[0-9a-f]{64}"));
        assertNotEquals(endpoint.reference().inventoryId(), otherSlot.reference().inventoryId());
        assertNotEquals(endpoint.reference().inventoryId(), otherSide.reference().inventoryId());
        assertNotEquals(endpoint.reference().inventoryId(), otherDimension.reference().inventoryId());
        assertNull(endpoint.reference().lastKnownLocation(), "opaque identity must not disclose coordinates");
        assertThrows(IllegalArgumentException.class, () -> new AutomationEndpoint(
                new ExternalInventoryEndpoint("storage_mod", "coordinates", "Leaked",
                        new WorldLocation(Level.OVERWORLD, new BlockPos(10, 64, -20))),
                "slot:3", "north"));
    }

    @Test
    void portableInventoryEndpointPreservesOpaqueModOwnedIdentityWithoutCoordinates() {
        AutomationEndpoint first = AutomationEndpoint.externalInventory(
                "backpack_mod", "portable-storage:owner-token-7", "Backpack", "slot:2", "item");
        AutomationEndpoint afterRestart = AutomationEndpoint.externalInventory(
                "backpack_mod", "portable-storage:owner-token-7", "Renamed Backpack", "slot:2", "item");
        AutomationEndpoint anotherSlot = AutomationEndpoint.externalInventory(
                "backpack_mod", "portable-storage:owner-token-7", "Backpack", "slot:3", "item");

        assertEquals(first.reference().inventoryId(), afterRestart.reference().inventoryId());
        assertEquals(first.reference().inventoryId(), anotherSlot.reference().inventoryId());
        assertNotEquals(first.slotPolicy(), anotherSlot.slotPolicy());
        assertNull(first.reference().lastKnownLocation());
        assertEquals("item", first.side());
        assertThrows(IllegalArgumentException.class, () -> AutomationEndpoint.externalInventory(
                "backpack_mod", "bad\nidentity", "Backpack", "aggregate", "item"));
    }

    @Test
    void automationAdapterRecordsOnlyCommittedExactPartialQuantity() throws Exception {
        SourceHandle source = register("storage_mod", "Storage Mod");
        AutomationEndpoint origin = AutomationEndpoint.blockInventory(
                "storage_mod", "Source", Level.OVERWORLD, new BlockPos(1, 64, 2), "slot:0", Direction.EAST);
        AutomationEndpoint destination = AutomationEndpoint.blockInventory(
                "storage_mod", "Destination", Level.OVERWORLD, new BlockPos(2, 64, 2), "slot:4", Direction.WEST);

        assertTrue(AutomationTransferAdapter.reportCommittedTransfer(service, source, 1, 1_700_000_000_001L,
                "simulated", origin, destination, diamond(10), 10, 10, false, true,
                "storage_mod", 0, 4).isEmpty());
        assertTrue(AutomationTransferAdapter.reportCommittedTransfer(service, source, 2, 1_700_000_000_002L,
                "rolled-back", origin, destination, diamond(10), 10, 10, false, false,
                "storage_mod", 0, 4).isEmpty());
        assertTrue(AutomationTransferAdapter.reportCommittedTransfer(service, source, 3, 1_700_000_000_003L,
                "rejected", origin, destination, diamond(10), 10, 0, true, false,
                "storage_mod", 0, 4).isEmpty());

        CompletableFuture<SubmissionResult> result = AutomationTransferAdapter.reportCommittedTransfer(
                service, source, 4, 1_700_000_000_004L, "partial-commit", origin, destination,
                diamond(10), 10, 4, true, false, "storage_mod", 0, 4).orElseThrow();
        assertEquals(SubmissionStatus.PERSISTED, result.join().status());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT amount, raw_data FROM ig_observations WHERE source_event_id = 4")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1));
                String raw = new String(rs.getBytes(2), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(raw.contains("\"transfer_state\":\"committed\""));
                assertTrue(raw.contains("\"requested_amount\":\"10\""));
                assertTrue(raw.contains("\"moved_amount\":\"4\""));
                assertTrue(raw.contains("\"automation_mod_id\":\"storage_mod\""));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> AutomationTransferAdapter.reportCommittedTransfer(
                service, source, 5, 1_700_000_000_005L, "over-count", origin, destination,
                diamond(10), 10, 11, true, false, "storage_mod", 0, 4));
        assertThrows(IllegalArgumentException.class, () -> AutomationTransferAdapter.reportCommittedTransfer(
                service, source, 6, 1_700_000_000_006L, "spoofed-source", origin, destination,
                diamond(1), 1, 1, true, false, "other_mod", 0, 4));
        assertThrows(IllegalArgumentException.class, () -> AutomationTransferAdapter.reportCommittedTransfer(
                service, source, 7, 1_700_000_000_007L, "wrong-slot", origin, destination,
                diamond(1), 1, 1, true, false, "storage_mod", 1, 4));

        QueryResult trace = service.traceItem(
                ItemQuery.itemId("minecraft:diamond"), QueryOptions.defaults()).join();
        assertEquals(QueryStatus.OK, trace.status());
        assertEquals(1, trace.result().hops().size());
        FlowHop hop = trace.result().hops().get(0);
        assertEquals(Provenance.OBSERVED, hop.provenance());
        assertEquals(4, hop.amount());
        assertEquals(EndpointKind.EXTERNAL_INVENTORY, hop.origin().kind());
        assertEquals(EndpointKind.EXTERNAL_INVENTORY, hop.destination().kind());
        assertEquals(EvidenceKind.OBSERVATION, hop.evidence().kind());
    }

    @Test
    void automationAdapterRetriesQueueSaturationWithSameObservationIdentity() {
        SourceHandle source = register("storage_mod", "Storage Mod");
        ItemGraphService retryingService = mock(ItemGraphService.class);
        AutomationEndpoint endpoint = AutomationEndpoint.blockInventory(
                "storage_mod", "Storage", Level.OVERWORLD, new BlockPos(1, 64, 2),
                "slot:0", Direction.NORTH);
        long eventId = 808;
        CompletableFuture<SubmissionResult> full = CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.QUEUE_FULL, "storage_mod", eventId,
                        "QUEUE_FULL", "queue saturated"));
        CompletableFuture<SubmissionResult> persisted = CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.PERSISTED, "storage_mod", eventId, null, null));
        when(retryingService.submitObservation(eq(source), any())).thenReturn(full, persisted);

        SubmissionResult result = AutomationTransferAdapter.reportCommittedEndpointDelta(
                retryingService, source, eventId, 1_700_000_000_808L, "retry-transfer", endpoint,
                Level.OVERWORLD, diamond(4), 8, 4, true, true, false,
                "storage_mod", 0).orElseThrow().join();

        assertEquals(SubmissionStatus.PERSISTED, result.status());
        ArgumentCaptor<DirectObservation> observations = ArgumentCaptor.forClass(DirectObservation.class);
        verify(retryingService, times(2)).submitObservation(eq(source), observations.capture());
        assertSame(observations.getAllValues().get(0), observations.getAllValues().get(1));
        assertEquals(eventId, observations.getAllValues().get(0).sourceEventId());
        assertEquals("retry-transfer", observations.getAllValues().get(0).attributes().get("transfer_id"));
        assertEquals("4", observations.getAllValues().get(0).attributes().get("moved_amount"));
    }

    @Test
    void automationAdapterReturnsQueueFullAfterBoundedRetries() {
        SourceHandle source = register("storage_mod", "Storage Mod");
        ItemGraphService retryingService = mock(ItemGraphService.class);
        AutomationEndpoint endpoint = AutomationEndpoint.blockInventory(
                "storage_mod", "Storage", Level.OVERWORLD, new BlockPos(1, 64, 2),
                "aggregate", null);
        long eventId = 809;
        CompletableFuture<SubmissionResult> full = CompletableFuture.completedFuture(
                new SubmissionResult(SubmissionStatus.QUEUE_FULL, "storage_mod", eventId,
                        "QUEUE_FULL", "queue saturated"));
        when(retryingService.submitObservation(eq(source), any()))
                .thenReturn(full, full, full, full);

        SubmissionResult result = AutomationTransferAdapter.reportCommittedEndpointDelta(
                retryingService, source, eventId, 1_700_000_000_809L, "retry-exhausted", endpoint,
                Level.OVERWORLD, diamond(1), 1, 1, true, true, false,
                "storage_mod", -1).orElseThrow().join();

        assertEquals(SubmissionStatus.QUEUE_FULL, result.status());
        verify(retryingService, times(4)).submitObservation(eq(source), any());
    }

    @Test
    void concurrentQueueFullSubmissionsReturnVisibleResultWhenRetryQueueIsSaturated() throws Exception {
        SourceHandle source = register("storage_mod", "Storage Mod");
        ItemGraphService busyService = mock(ItemGraphService.class);
        AutomationEndpoint endpoint = AutomationEndpoint.externalInventory(
                "storage_mod", "portable-storage:concurrency-fixture", "Backpack", "aggregate", "item");
        when(busyService.submitObservation(eq(source), any())).thenAnswer(invocation -> {
            DirectObservation observation = invocation.getArgument(1);
            return CompletableFuture.completedFuture(new SubmissionResult(SubmissionStatus.QUEUE_FULL,
                    "storage_mod", observation.sourceEventId(), "QUEUE_FULL", "queue saturated"));
        });

        CountDownLatch retryWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseRetryWorker = new CountDownLatch(1);
        ThreadPoolExecutor saturatedExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(AutomationTransferAdapter.MAX_PENDING_RETRIES), runnable -> {
                    Thread thread = new Thread(runnable, "automation-retry-saturation-test");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        Executor previous = null;
        ExecutorService producers = null;
        try {
            saturatedExecutor.execute(() -> {
                retryWorkerStarted.countDown();
                try {
                    releaseRetryWorker.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(retryWorkerStarted.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < AutomationTransferAdapter.MAX_PENDING_RETRIES; index++) {
                saturatedExecutor.execute(() -> { });
            }
            assertEquals(AutomationTransferAdapter.MAX_PENDING_RETRIES, saturatedExecutor.getQueue().size(),
                    "retry queue must respect its 128-entry bound");

            previous = AutomationTransferAdapter.setRetryExecutorForTesting(saturatedExecutor);
            ExecutorService concurrentProducers = Executors.newFixedThreadPool(8);
            producers = concurrentProducers;
            var concurrent = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(index -> concurrentProducers.submit(() -> AutomationTransferAdapter
                            .reportCommittedEndpointDelta(busyService, source, 20_000L + index,
                                    1_700_000_020_000L + index, "saturated-" + index, endpoint,
                                    Level.OVERWORLD, diamond(1), 1, 1, true, true, false,
                                    "storage_mod", -1).orElseThrow().join()))
                    .toList();
            for (var result : concurrent) {
                SubmissionResult submission = result.get(5, TimeUnit.SECONDS);
                assertEquals(SubmissionStatus.QUEUE_FULL, submission.status());
                assertEquals("QUEUE_FULL", submission.errorCode());
            }
            assertEquals(AutomationTransferAdapter.MAX_PENDING_RETRIES, saturatedExecutor.getQueue().size(),
                    "rejected retries must not grow the bounded queue");
            verify(busyService, times(32)).submitObservation(eq(source), any());
        } finally {
            if (previous != null) {
                AutomationTransferAdapter.setRetryExecutorForTesting(previous);
            }
            if (producers != null) {
                producers.shutdownNow();
            }
            releaseRetryWorker.countDown();
            saturatedExecutor.shutdownNow();
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
        assertEquals(com.itemgraph.audit.EventTaxonomy.EvidenceClass.UNRESOLVED, hop.evidenceClass());
        assertEquals("UNKNOWN_ENDPOINT", hop.reasonCode());
        assertEquals(0, hop.quantityImpact());
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
                "testmod", "Test Mod", ApiVersion.PREVIEW_2, 8);
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
