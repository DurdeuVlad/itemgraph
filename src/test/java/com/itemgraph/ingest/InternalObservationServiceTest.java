package com.itemgraph.ingest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService.InternalObservation;
import com.itemgraph.ingest.InternalObservationService.InternalTransformation;
import com.itemgraph.listener.ContainerCapabilityWrapper;
import com.itemgraph.listener.ContainerInteractionTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Endpoint-mapping tests for {@link InternalObservationService#persistBatch}
 * (0.2.0 — Issues 3 &amp; 4).
 *
 * <p>Verifies that container observations are persisted with honest graph
 * topology: automation rows anchor the observed container and resolve the
 * unknowable remote endpoint to the per-level UNKNOWN node — never a
 * self-referencing container edge — and that the {@code [automation]} and
 * {@code [ambiguous]} sentinel identities never materialize PLAYER rows.
 */
class InternalObservationServiceTest {

    @TempDir
    Path tempDir;

    private Connection conn;
    private InternalObservationService service;

    private static final CanonicalItem DIAMOND =
            new CanonicalItem("minecraft:diamond", "fp-diamond", null, null, null);
    private static final String PLAYER_UUID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        service = InternalObservationService.getInstance();
        service.clear();
        IngestionService.getInstance().getNodeManager().clearCaches();
    }

    @AfterEach
    void tearDown() {
        service.stop();
        service.clear();
        IngestionService.getInstance().getNodeManager().clearCaches();
        DatabaseManager.getInstance().close();
    }

    private void initializeTopologyDatabase() throws Exception {
        DatabaseManager.getInstance().initialize(tempDir.resolve("itemgraph.db"));
        IngestionService.getInstance().getNodeManager().clearCaches();
        conn = DatabaseManager.getInstance().getConnection();
    }

    private InternalObservation containerObs(String actionType, String uuid, String name, int amount) {
        return new InternalObservation(
                System.currentTimeMillis(), actionType, uuid, name,
                "minecraft:overworld", 10, 64, -20,
                "minecraft:overworld", 10.0, 64.0, -20.0,
                "CONTAINER", DIAMOND, amount, null);
    }

    @SuppressWarnings("unchecked")
    private void persist(InternalObservation... obs) throws Exception {
        Method m = InternalObservationService.class.getDeclaredMethod("persistBatch", List.class);
        m.setAccessible(true);
        m.invoke(service, List.of(obs));
    }

    private String nodeTypeOf(long nodeId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT node_type FROM ig_nodes WHERE id = ?")) {
            ps.setLong(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "node " + nodeId + " must exist");
                return rs.getString(1);
            }
        }
    }

    private record ObsRow(long nodeId, Long targetNodeId) {}

    private ObsRow singleObservation() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT node_id, target_node_id FROM ig_observations")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "an observation row must exist");
                ObsRow row = new ObsRow(rs.getLong(1),
                        rs.getObject(2) == null ? null : rs.getLong(2));
                assertFalse(rs.next(), "exactly one observation expected");
                return row;
            }
        }
    }

    private int playerNodeCount() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM ig_nodes WHERE node_type = 'PLAYER'")) {
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private InternalObservation createDummyObservation(int id) {
        return new InternalObservation(
                1000L + id,
                "EQUIP_ARMOR_STAND",
                "00000000-0000-0000-0000-000000000001",
                "Player" + id,
                "minecraft:overworld",
                10.0, 64.0, 20.0,
                "minecraft:overworld",
                12.0, 64.0, 22.0,
                "ARMOR_STAND",
                new CanonicalItem("minecraft:diamond_helmet", ItemCanonicalizer.sha256Hex("id=minecraft:diamond_helmet"), null, null, null),
                1,
                null
        );
    }

    private InternalTransformation createDummyTransformation(int id) {
        return new InternalTransformation(
                1000L + id,
                "CRAFT",
                "00000000-0000-0000-0000-000000000001",
                "Player" + id,
                "minecraft:overworld",
                10.0, 64.0, 20.0,
                new CanonicalItem("minecraft:iron_ingot", ItemCanonicalizer.sha256Hex("id=minecraft:iron_ingot"), null, null, null),
                new CanonicalItem("minecraft:iron_sword", ItemCanonicalizer.sha256Hex("id=minecraft:iron_sword"), null, null, null),
                1,
                "Crafted iron sword"
        );
    }

    @Test
    void testQueueCapacityBound() {
        // Queue capacity is exactly 10,000
        for (int i = 0; i < 10_000; i++) {
            boolean accepted = service.submit(createDummyObservation(i));
            assertTrue(accepted, "Item " + i + " must be accepted within capacity");
        }

        assertEquals(10_000, service.getQueueSize());
        assertEquals(10_000, service.getTotalEnqueued());

        // 10,001st item must be rejected (bounded backpressure)
        boolean overCapacity = service.submit(createDummyObservation(10_001));
        assertFalse(overCapacity, "10,001st item must be rejected when queue is full");
        assertEquals(10_000, service.getQueueSize(), "Queue size must remain at capacity");
        assertEquals(10_000, service.getTotalEnqueued(), "Total enqueued counter must not increment on rejection");
    }

    @Test
    void testTransformationQueueCapacityBound() {
        for (int i = 0; i < 10_000; i++) {
            boolean accepted = service.submitTransformation(createDummyTransformation(i));
            assertTrue(accepted, "Transformation " + i + " must be accepted within capacity");
        }

        assertEquals(10_000, service.getQueueSize());
        assertEquals(10_000, service.getTotalEnqueued());

        boolean overCapacity = service.submitTransformation(createDummyTransformation(10_001));
        assertFalse(overCapacity, "10,001st transformation must be rejected when queue is full");
        assertEquals(10_000, service.getQueueSize());
        assertEquals(10_000, service.getTotalEnqueued());
    }

    @Test
    void testConcurrentEnqueue() throws InterruptedException {
        int threadCount = 10;
        int itemsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < itemsPerThread; i++) {
                        service.submit(createDummyObservation(threadId * 1000 + i));
                        service.submitTransformation(createDummyTransformation(threadId * 1000 + i));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Concurrent enqueue should finish within 10 seconds");
        executor.shutdown();

        int expectedTotal = threadCount * itemsPerThread * 2; // obs + trans
        assertEquals(expectedTotal, service.getQueueSize());
        assertEquals(expectedTotal, service.getTotalEnqueued());
    }

    @Test
    void testShutdownFlushesQueuedItemsToDatabase(@TempDir Path testTempDir) throws Exception {
        Path dbPath = testTempDir.resolve("flush_test.db");
        DatabaseManager.getInstance().initialize(dbPath);

        // Submit items without starting the background worker thread
        for (int i = 0; i < 5; i++) {
            service.submit(createDummyObservation(i));
        }
        for (int i = 0; i < 3; i++) {
            service.submitTransformation(createDummyTransformation(i));
        }

        assertEquals(8, service.getQueueSize());
        assertEquals(0, service.getTotalPersisted());

        // stop() must synchronously flush all remaining queued items
        service.stop();

        assertEquals(0, service.getQueueSize());
        assertEquals(8, service.getTotalPersisted());
        assertEquals(3, service.getTotalTransformations());

        Connection connection = DatabaseManager.getInstance().getConnection();
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ig_observations WHERE source_type = 'ITEMGRAPH_INTERNAL'")) {
                assertTrue(rs.next());
                assertEquals(5, rs.getInt(1));
            }
            try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ig_item_transformations")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }
    }

    @Test
    void testWorkerThreadDrainsAndPersistsInBackground(@TempDir Path testTempDir) throws Exception {
        Path dbPath = testTempDir.resolve("worker_test.db");
        DatabaseManager.getInstance().initialize(dbPath);

        service.start();

        for (int i = 0; i < 5; i++) {
            service.submit(createDummyObservation(i));
        }

        // Wait up to 5 seconds for worker thread to drain
        long deadline = System.currentTimeMillis() + 5000;
        while (service.getTotalPersisted() < 5 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(5, service.getTotalPersisted());
        assertEquals(0, service.getQueueSize());

        service.stop();

        Connection connection = DatabaseManager.getInstance().getConnection();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ig_observations WHERE action_type = 'EQUIP_ARMOR_STAND'")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt(1));
        }
    }

    @Test
    void testArmorStandTargetNodeMapping(@TempDir Path testTempDir) throws Exception {
        Path dbPath = testTempDir.resolve("armor_stand_nodes.db");
        DatabaseManager.getInstance().initialize(dbPath);

        CanonicalItem item = new CanonicalItem("minecraft:diamond_chestplate",
                ItemCanonicalizer.sha256Hex("id=minecraft:diamond_chestplate"), null, null, null);

        InternalObservation equip = new InternalObservation(
                1000L, "EQUIP_ARMOR_STAND", "00000000-0000-0000-0000-000000000001", "Steve",
                "minecraft:overworld", 10.0, 64.0, 10.0,
                "minecraft:overworld", 10.0, 64.0, 12.0,
                "ARMOR_STAND", item, 1, null
        );

        InternalObservation unequip = new InternalObservation(
                2000L, "UNEQUIP_ARMOR_STAND", "00000000-0000-0000-0000-000000000001", "Steve",
                "minecraft:overworld", 10.0, 64.0, 10.0,
                "minecraft:overworld", 10.0, 64.0, 12.0,
                "ARMOR_STAND", item, 1, null
        );

        service.submit(equip);
        service.submit(unequip);
        service.stop(); // Flushes

        Connection connection = DatabaseManager.getInstance().getConnection();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT action_type, node_id, target_node_id FROM ig_observations ORDER BY timestamp_ms ASC")) {

            assertTrue(rs.next());
            assertEquals("EQUIP_ARMOR_STAND", rs.getString("action_type"));
            long equipOrigin = rs.getLong("node_id");
            long equipTarget = rs.getLong("target_node_id");
            assertNotEquals(equipOrigin, equipTarget);

            assertTrue(rs.next());
            assertEquals("UNEQUIP_ARMOR_STAND", rs.getString("action_type"));
            long unequipOrigin = rs.getLong("node_id");
            long unequipTarget = rs.getLong("target_node_id");

            // Unequip origin must match equip target (the armor stand node)
            assertEquals(equipTarget, unequipOrigin);
            // Unequip target must match equip origin (the player node)
            assertEquals(equipOrigin, unequipTarget);
        }
    }

    @Test
    void testGetOrCreateFingerprintDedup(@TempDir Path testTempDir) throws Exception {
        Path dbPath = testTempDir.resolve("fp_test.db");
        DatabaseManager.getInstance().initialize(dbPath);
        Connection connection = DatabaseManager.getInstance().getConnection();

        CanonicalItem item = new CanonicalItem("minecraft:netherite_sword",
                ItemCanonicalizer.sha256Hex("id=minecraft:netherite_sword;custom_name=Doom"),
                "Doom", "EPIC", "custom_name=Doom");

        long id1 = service.getOrCreateFingerprint(connection, item);
        long id2 = service.getOrCreateFingerprint(connection, item);

        assertEquals(id1, id2, "Subsequent calls with identical hash must return the same fingerprint ID");

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ig_item_fingerprints WHERE item_id = 'minecraft:netherite_sword'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Only one fingerprint row should exist");
        }
    }

    @Test
    void canceledTossUsesUnknownDestinationInsteadOfGround() throws Exception {
        initializeTopologyDatabase();
        persist(new InternalObservation(
                System.currentTimeMillis(), "DROP_CANCELLED", PLAYER_UUID, "TestPlayer",
                "minecraft:overworld", 10, 64, -20,
                "minecraft:overworld", 10.0, 64.0, -20.0,
                "UNKNOWN", DIAMOND, 3, null));

        ObsRow row = singleObservation();
        assertEquals("PLAYER", nodeTypeOf(row.nodeId()));
        assertNotNull(row.targetNodeId());
        assertEquals("UNKNOWN", nodeTypeOf(row.targetNodeId()));
    }

    @Test
    void capabilityInsertAnchorsUnknownCallerToContainer() throws Exception {
        initializeTopologyDatabase();
        persist(containerObs("CAPABILITY_INSERT", "00000000-0000-0000-0000-000000000000",
                "[capability caller unknown]", 3));

        ObsRow row = singleObservation();
        assertNotNull(row.targetNodeId());
        assertNotEquals(row.nodeId(), row.targetNodeId(),
                "CAPABILITY_INSERT must not be a container self-loop");
        assertEquals("UNKNOWN", nodeTypeOf(row.nodeId()),
                "IItemHandler does not identify the capability caller");
        assertEquals("CONTAINER", nodeTypeOf(row.targetNodeId()));
        assertEquals(0, playerNodeCount(),
                "the unknown capability caller must not materialize a PLAYER node");
    }

    @Test
    void capabilityExtractAnchorsContainerToUnknownCaller() throws Exception {
        initializeTopologyDatabase();
        persist(containerObs("CAPABILITY_EXTRACT", "00000000-0000-0000-0000-000000000000",
                "[capability caller unknown]", 2));

        ObsRow row = singleObservation();
        assertNotNull(row.targetNodeId());
        assertNotEquals(row.nodeId(), row.targetNodeId());
        assertEquals("CONTAINER", nodeTypeOf(row.nodeId()));
        assertEquals("UNKNOWN", nodeTypeOf(row.targetNodeId()));
        assertEquals(0, playerNodeCount());
    }

    @Test
    void addItemLinksPlayerToContainer() throws Exception {
        initializeTopologyDatabase();
        persist(containerObs("ADD_ITEM", PLAYER_UUID, "Steve", 6));

        ObsRow row = singleObservation();
        assertEquals("PLAYER", nodeTypeOf(row.nodeId()));
        assertEquals("CONTAINER", nodeTypeOf(row.targetNodeId()));
        assertEquals(1, playerNodeCount());
    }

    @Test
    void removeItemLinksContainerToPlayer() throws Exception {
        initializeTopologyDatabase();
        persist(containerObs("REMOVE_ITEM", PLAYER_UUID, "Steve", 6));

        ObsRow row = singleObservation();
        assertEquals("CONTAINER", nodeTypeOf(row.nodeId()));
        assertEquals("PLAYER", nodeTypeOf(row.targetNodeId()));
    }

    @Test
    void ambiguousActorResolvesToUnknownNotAPlayerNode() throws Exception {
        initializeTopologyDatabase();
        persist(containerObs("ADD_ITEM", ContainerInteractionTracker.AMBIGUOUS_UUID,
                ContainerInteractionTracker.AMBIGUOUS_NAME, 4));

        ObsRow row = singleObservation();
        assertEquals("UNKNOWN", nodeTypeOf(row.nodeId()),
                "an ambiguous actor must resolve to UNKNOWN, keeping raw_data candidates");
        assertEquals("CONTAINER", nodeTypeOf(row.targetNodeId()));
        assertEquals(0, playerNodeCount(),
                "the [ambiguous] sentinel must not materialize a PLAYER node");
    }

    @Test
    void groundDropStillLinksPlayerToGround() throws Exception {
        initializeTopologyDatabase();
        persist(new InternalObservation(
                System.currentTimeMillis(), "DROP_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 5, 64, 5,
                "minecraft:overworld", 5.0, 64.0, 5.0,
                "GROUND", DIAMOND, 1, "entity-xyz"));

        ObsRow row = singleObservation();
        assertEquals("PLAYER", nodeTypeOf(row.nodeId()));
        assertEquals("GROUND", nodeTypeOf(row.targetNodeId()));
    }
}
