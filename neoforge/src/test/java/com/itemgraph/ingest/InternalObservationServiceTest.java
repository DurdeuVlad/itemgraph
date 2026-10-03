package com.itemgraph.ingest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.ingest.InternalObservationService.InternalObservation;
import com.itemgraph.ingest.InternalObservationService.InternalAuditEvent;
import com.itemgraph.ingest.InternalObservationService.InternalTransformation;
import com.itemgraph.listener.ContainerCapabilityWrapper;
import com.itemgraph.gametest.PerformanceReportFixture;
import com.itemgraph.metrics.OperationalMetrics;
import com.itemgraph.listener.ContainerInteractionTracker;
import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.UnifiedEvidenceQueryService;
import com.itemgraph.query.UnifiedEvidenceDetail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

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
        service.configureOperations(250, 1, 100, 30_000, true);
        service.setEvidenceSpoolPathForTests(tempDir.resolve("pending-evidence.json"));
        service.clear();
        IngestionService.getInstance().getNodeManager().clearCaches();
    }

    @AfterEach
    void tearDown() {
        service.stop();
        service.clear();
        service.setEvidenceSpoolPathForTests(null);
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

    @SuppressWarnings("unchecked")
    private void persistAudit(InternalAuditEvent... events) throws Exception {
        Method m = InternalObservationService.class.getDeclaredMethod("persistAuditEvents", List.class);
        m.setAccessible(true);
        m.invoke(service, List.of(events));
    }

    private void persistTransformation(InternalTransformation transformation) throws Exception {
        Method m = InternalObservationService.class.getDeclaredMethod("persistTransformations", List.class);
        m.setAccessible(true);
        m.invoke(service, List.of(transformation));
    }

    @Test
    void nativeAuditEventsPersistOutsideItemObservationLedger() throws Exception {
        initializeTopologyDatabase();
        persistAudit(new InternalAuditEvent(
                1234L, "BREAK_BLOCK", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:stone", null, null));

        try (PreparedStatement audit = conn.prepareStatement(
                "SELECT event_type, player_name, subject_id FROM ig_audit_events");
             ResultSet rs = audit.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("BREAK_BLOCK", rs.getString(1));
            assertEquals("Alex", rs.getString(2));
            assertEquals("minecraft:stone", rs.getString(3));
            assertFalse(rs.next());
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "non-quantity audit events must not become item transfers");
        }
    }

    @Test
    void entityInteractionOutcomeSurvivesDatabaseRestartWithoutQuantityObservation() throws Exception {
        initializeTopologyDatabase();
        Path databasePath = tempDir.resolve("itemgraph.db");
        assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                1234L, "INTERACT_ENTITY_DENIED", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:armor_stand",
                "outcome=denied callback=fabric_use_entity callback_result=fail "
                        + "reason=FABRIC_USE_ENTITY_CALLBACK_SHORT_CIRCUITED held_item=minecraft:diamond "
                        + "held_count=1 held_fingerprint=fp-diamond", null)),
                "capture=" + service.isCaptureEnabled() + " queue=" + service.getQueueSize());
        service.stop();

        DatabaseManager.getInstance().close();
        DatabaseManager.getInstance().initialize(databasePath);
        conn = DatabaseManager.getInstance().getConnection();

        try (PreparedStatement audit = conn.prepareStatement(
                "SELECT event_type, player_name, subject_id, detail FROM ig_audit_events");
             ResultSet rs = audit.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("INTERACT_ENTITY_DENIED", rs.getString("event_type"));
            assertEquals("Alex", rs.getString("player_name"));
            assertEquals("minecraft:armor_stand", rs.getString("subject_id"));
            assertTrue(rs.getString("detail").contains("callback_result=fail"));
            assertTrue(rs.getString("detail").contains("held_fingerprint=fp-diamond"));
            assertFalse(rs.next());
        }
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1), "interaction outcomes are not item quantity transfers");
        }
    }

    @Test
    void blockRemovalLinksEarlierInteractionsWithoutDeletingRawEvidence() throws Exception {
        initializeTopologyDatabase();
        InternalAuditEvent lowerHalfInteraction = new InternalAuditEvent(
                1_000L, "INTERACT_BLOCK_ATTEMPT", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:oak_door", null, null);
        InternalAuditEvent upperHalfInteraction = new InternalAuditEvent(
                1_100L, "INTERACT_BLOCK_ATTEMPT", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 65, -20, "minecraft:oak_door", null, null);
        List<AuditEventQueryService.ExactPosition> doorPositions = List.of(
                new AuditEventQueryService.ExactPosition(10, 64, -20),
                new AuditEventQueryService.ExactPosition(10, 65, -20));
        InternalAuditEvent breakDoor = new InternalAuditEvent(
                2_000L, "BREAK_BLOCK", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:oak_door", null, null, doorPositions);
        String longImportedSourceKey = "interaction-row-" + "𐐀".repeat(600);
        String caseVariantAfterBreak = "Interaction-row-" + "𐐀".repeat(600);
        InternalAuditEvent laterInteraction = new InternalAuditEvent(
                3_000L, "INTERACT_BLOCK_ATTEMPT", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:air", null, null);
        try (PreparedStatement imported = conn.prepareStatement("""
                INSERT INTO ig_grieflogger_lookup
                    (source_sha256, table_name, source_key, timestamp_ms, level_name, x, y, z,
                     player_name, action_type, quantity, evidence_class)
                VALUES ('fixture-source-hash', 'blocks', ?, 900,
                        'minecraft:overworld', 10, 64, -20, 'Alex', 'INTERACT_BLOCK_ATTEMPT', 0, 'OBSERVED')
                """)) {
            imported.setString(1, longImportedSourceKey);
            imported.executeUpdate();
        }
        try (PreparedStatement imported = conn.prepareStatement("""
                INSERT INTO ig_grieflogger_lookup
                    (source_sha256, table_name, source_key, timestamp_ms, level_name, x, y, z,
                     player_name, action_type, quantity, evidence_class)
                VALUES ('fixture-source-hash', 'blocks', ?, 2_500,
                        'minecraft:overworld', 10, 64, -20, 'Alex', 'INTERACT_BLOCK_ATTEMPT', 0, 'OBSERVED')
                """)) {
            imported.setString(1, caseVariantAfterBreak);
            imported.executeUpdate();
        }

        persistAudit(lowerHalfInteraction, upperHalfInteraction, breakDoor, laterInteraction);
        persistAudit(breakDoor);

        try (Statement statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT superseded_event_id, superseding_event_id, reason_code
                     FROM ig_audit_event_supersessions ORDER BY superseded_event_id
                     """)) {
            assertTrue(rows.next());
            assertEquals("BLOCK_REMOVED_AT_TARGET", rows.getString("reason_code"));
            long firstInteractionId = rows.getLong("superseded_event_id");
            long breakId = rows.getLong("superseding_event_id");
            assertTrue(rows.next());
            assertNotEquals(firstInteractionId, rows.getLong("superseded_event_id"));
            assertEquals(breakId, rows.getLong("superseding_event_id"));
            assertFalse(rows.next());
        }
        try (Statement statement = conn.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events")) {
            assertTrue(rows.next());
            assertEquals(4, rows.getInt(1), "supersession must not delete or rewrite raw event rows");
        }
        try (PreparedStatement statement = conn.prepareStatement("""
                     SELECT superseding_event_id, reason_code
                     FROM ig_grieflogger_row_supersessions
                     WHERE source_sha256 = 'fixture-source-hash' AND source_key = ?
                     """)) {
            statement.setString(1, longImportedSourceKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), "imported immutable history gets its own supersession link");
                assertTrue(rows.getLong("superseding_event_id") > 0);
                assertEquals("BLOCK_REMOVED_AT_TARGET", rows.getString("reason_code"));
                assertFalse(rows.next());
            }
        }

        AuditLookupFilters attempts = new AuditLookupFilters(List.of("INTERACT_BLOCK_ATTEMPT"),
                List.of(), List.of(), List.of(), 1.0, QueryWindow.unbounded());
        List<UnifiedEvidenceDetail> raw = new UnifiedEvidenceQueryService().findFiltered(
                conn, attempts, "minecraft:overworld", 10, 64, -20, 10, 0);
        assertEquals(5, raw.size());
        assertTrue(raw.stream().filter(row -> row.timestampMs() < 2_000L)
                .allMatch(row -> row.detail().contains("superseded_by=audit#")
                        && row.detail().contains("reason=BLOCK_REMOVED_AT_TARGET")));
        UnifiedEvidenceDetail postBreak = raw.stream()
                .filter(row -> row.timestampMs() == 3_000L).findFirst().orElseThrow();
        assertFalse(postBreak.detail() != null && postBreak.detail().contains("superseded_by="),
                "the post-break interaction remains active raw evidence");

        List<UnifiedEvidenceDetail> activeInspector = new UnifiedEvidenceQueryService().findExact(
                conn, "minecraft:overworld", doorPositions, 10, 0);
        assertTrue(activeInspector.stream().noneMatch(row -> "INTERACT_BLOCK_ATTEMPT".equals(row.actionType())
                && row.timestampMs() < 2_000L));
        assertEquals(2, activeInspector.stream()
                .filter(row -> "INTERACT_BLOCK_ATTEMPT".equals(row.actionType())).count());
        assertTrue(activeInspector.stream().noneMatch(row -> row.evidenceId()
                .equals("historical#blocks#" + longImportedSourceKey)));
        UnifiedEvidenceDetail activeCaseVariant = activeInspector.stream()
                .filter(row -> row.evidenceId().equals("historical#blocks#" + caseVariantAfterBreak))
                .findFirst().orElseThrow();
        assertTrue(activeCaseVariant.detail() == null || !activeCaseVariant.detail().contains("superseded_by="),
                "binary source-key comparison must not hide a case-variant post-break row");
    }

    @Test
    void durableAuditSourceEventIdDeduplicatesProjectileAcceptanceRetry() throws Exception {
        initializeTopologyDatabase();
        long sourceEventId = InternalObservationService.sourceEventIdForUuid(
                "123e4567-e89b-12d3-a456-426614174001");
        InternalAuditEvent event = new InternalAuditEvent(
                1234L, "PROJECTILE_SPAWN_ACCEPTED", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:arrow", "outcome=accepted",
                eventPayload("123e4567-e89b-12d3-a456-426614174001", "accepted"), sourceEventId);

        persistAudit(event);
        persistAudit(event);

        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT source_event_id, COUNT(*) FROM ig_audit_events GROUP BY source_event_id")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(sourceEventId, result.getLong(1));
                assertEquals(1, result.getInt(2));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void distinctAuditPayloadsSurviveAProjectedSourceIdCollision() throws Exception {
        initializeTopologyDatabase();
        long sourceEventId = InternalObservationService.sourceEventIdForUuid(
                "123e4567-e89b-12d3-a456-426614174001");
        InternalAuditEvent first = new InternalAuditEvent(
                1234L, "PROJECTILE_SPAWN_ACCEPTED", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:arrow", "outcome=accepted",
                eventPayload("123e4567-e89b-12d3-a456-426614174001", "first"), sourceEventId);
        InternalAuditEvent second = new InternalAuditEvent(
                1235L, "PROJECTILE_SPAWN_ACCEPTED", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:arrow", "outcome=accepted",
                eventPayload("123e4567-e89b-12d3-a456-426614174002", "second"), sourceEventId);

        persistAudit(first, second);
        persistAudit(first, second);

        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT source_event_id, raw_data FROM ig_audit_events ORDER BY id")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                long firstId = result.getLong(1);
                assertArrayEquals(eventPayload("123e4567-e89b-12d3-a456-426614174001", "first"), result.getBytes(2));
                assertTrue(result.next());
                long secondId = result.getLong(1);
                assertArrayEquals(eventPayload("123e4567-e89b-12d3-a456-426614174002", "second"), result.getBytes(2));
                assertNotEquals(firstId, secondId,
                        "a projected source ID collision must not drop a distinct audit payload");
                assertFalse(result.next());
            }
        }
    }

    @Test
    void auditQueueWakeDoesNotPersistBeforeConfiguredServerTickCadence() throws Exception {
        service.configureOperations(2_000, 2, 100, 30_000, true);
        initializeTopologyDatabase();
        service.start();

        assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                System.currentTimeMillis(), "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, null, "idle-poll-wakeup", null)));

        Thread.sleep(100);
        assertEquals(0, service.getTotalAuditEvents(),
                "enqueue wakeups must not bypass the configured batch cadence");
        assertEquals(1, service.getQueueSize());

        service.onServerTick();
        Thread.sleep(100);
        assertEquals(0, service.getTotalAuditEvents(),
                "the first of two configured server ticks must not flush the queue");

        service.onServerTick();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (service.getTotalAuditEvents() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertEquals(1, service.getTotalAuditEvents(),
                "audit submission must wake the worker even when no item-observation row is queued");
        assertEquals(1, service.getTotalPersisted());
        assertEquals(0, service.getQueueSize());
        try (Statement statement = conn.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*) FROM ig_audit_events WHERE detail = 'idle-poll-wakeup'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "worker wakeup must produce a durable audit row");
        }
    }

    @Test
    void uninitializedDatabaseSpoolsAcceptedAuditEventForRestartRecovery() throws Exception {
        Path databasePath = tempDir.resolve("audit-recovery/itemgraph.db");
        DatabaseManager.getInstance().initialize(databasePath);
        DatabaseManager.getInstance().close();
        assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                1234L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, null, "hello", null)));

        service.stop();

        assertEquals(0, service.getTotalAuditEvents());
        assertEquals(0, service.getTotalPersisted());
        assertEquals(0, service.getTotalDropped(), "the recovery file preserves an accepted event after shutdown database failure");
        assertEquals(0, service.getTotalPersistenceOutcomeUnknown(),
                "no commit was attempted because the database was not initialized");
        assertEquals(0, service.getQueueSize());

        Path spoolPath = tempDir.resolve("pending-evidence.json");
        assertTrue(java.nio.file.Files.exists(spoolPath));
        DatabaseManager.getInstance().initialize(databasePath);
        service.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalPersisted() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, service.getTotalPersisted());
        service.stop();
        assertFalse(java.nio.file.Files.exists(spoolPath));
        try (Statement statement = DatabaseManager.getInstance().getConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events WHERE detail = 'hello'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void failedRecoveryLoadClosesAdmissionAndPreservesOriginalFile() throws Exception {
        initializeTopologyDatabase();
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        Path overflowPath = PendingEvidenceSpool.overflowPath(spoolPath);
        service.start();
        service.stop(); // Records this exact recovery path before the failed restart.
        service.clear(); // Simulate already accepted pre-start work across lifecycle reset.
        byte[] corruptSpool = "{unsupported-recovery-data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(spoolPath, corruptSpool);
        assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                2345L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                1, 2, 3, null, "accepted-before-recovery-check", null)));

        service.start();

        java.lang.reflect.Field admissionField = InternalObservationService.class
                .getDeclaredField("acceptingSubmissions");
        admissionField.setAccessible(true);
        long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (admissionField.getBoolean(service) && System.nanoTime() < recoveryDeadline) {
            Thread.sleep(10);
        }
        assertFalse(admissionField.getBoolean(service), "malformed recovery must close intake after asynchronous validation");
        assertFalse(service.submitAuditEvent(new InternalAuditEvent(
                3456L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                1, 2, 3, null, "must-not-be-accepted-while-recovery-is-blocked", null)));
        assertEquals(1, service.getTotalDropped(), "a failed recovery load must leave admission closed and count rejected evidence");
        assertArrayEquals(corruptSpool, java.nio.file.Files.readAllBytes(spoolPath),
                "the unsupported recovery file must remain intact for operator recovery");
        service.stop();
        assertTrue(java.nio.file.Files.exists(overflowPath),
                "already accepted pre-start evidence must be preserved separately if the primary recovery file cannot be read");
        assertEquals(1, PendingEvidenceSpool.read(overflowPath).size());
        assertArrayEquals(corruptSpool, java.nio.file.Files.readAllBytes(spoolPath),
                "overflow preservation must not replace or delete the original recovery file");

        java.nio.file.Files.delete(spoolPath); // Simulate operator repair after preserving the original bytes above.
        service.start();
        long replayDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalPersisted() == 0 && System.nanoTime() < replayDeadline) {
            Thread.sleep(10);
        }
        assertEquals(1, service.getTotalPersisted());
        service.stop();
        assertFalse(java.nio.file.Files.exists(overflowPath), "the overflow file is removed after its evidence commits");
        try (Statement statement = DatabaseManager.getInstance().getConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events WHERE detail = 'accepted-before-recovery-check'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void startupRecoveryIoRunsOffCallerAndPersistsSavedEventsBeforeNewEvents() throws Exception {
        initializeTopologyDatabase();
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        InternalAuditEvent recovered = new InternalAuditEvent(
                1000L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                1, 2, 3, null, "recovered-before-new", null);
        InternalAuditEvent current = new InternalAuditEvent(
                2000L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                1, 2, 3, null, "new-after-recovery", null);
        PendingEvidenceSpool.write(spoolPath, new PendingEvidenceSpool.Snapshot(
                List.of(), List.of(), List.of(recovered)));
        java.lang.reflect.Field lockField = InternalObservationService.class
                .getDeclaredField("evidenceSpoolLock");
        lockField.setAccessible(true);
        Object spoolLock = lockField.get(service);

        synchronized (spoolLock) {
            long startedAt = System.nanoTime();
            service.start();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2_000,
                    "start must return without waiting for recovery-file parsing");
            assertTrue(service.submitAuditEvent(current),
                    "new evidence must remain bounded and accepted while the recovery worker loads the saved events");
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalPersisted() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(2, service.getTotalPersisted());
        service.stop();
        try (Statement statement = DatabaseManager.getInstance().getConnection().createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT detail FROM ig_audit_events WHERE detail IN ('recovered-before-new', 'new-after-recovery') ORDER BY id")) {
            assertTrue(result.next());
            assertEquals("recovered-before-new", result.getString(1));
            assertTrue(result.next());
            assertEquals("new-after-recovery", result.getString(1));
            assertFalse(result.next());
        }
    }

    @Test
    void producerRacingCorruptRecoveryIsEitherDurablyPreservedOrRejected() throws Exception {
        initializeTopologyDatabase();
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        Path overflowPath = PendingEvidenceSpool.overflowPath(spoolPath);
        java.nio.file.Files.writeString(spoolPath, "{unsupported-recovery-data");
        java.lang.reflect.Field queueLockField = InternalObservationService.class
                .getDeclaredField("queueMutationLock");
        java.lang.reflect.Field workerField = InternalObservationService.class
                .getDeclaredField("workerThread");
        queueLockField.setAccessible(true);
        workerField.setAccessible(true);
        Object queueLock = queueLockField.get(service);
        CountDownLatch producerStarted = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean();
        InternalAuditEvent racingEvent = new InternalAuditEvent(
                3000L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                1, 2, 3, null, "race-with-corrupt-recovery", null);
        Thread producer = new Thread(() -> {
            producerStarted.countDown();
            accepted.set(service.submitAuditEvent(racingEvent));
            producerFinished.countDown();
        }, "ItemGraph-Recovery-Race-Producer");

        synchronized (queueLock) {
            service.start();
            long recoveryBlockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            Thread recoveryWorker;
            do {
                recoveryWorker = (Thread) workerField.get(service);
                if (recoveryWorker != null && recoveryWorker.getState() == Thread.State.BLOCKED) break;
                Thread.sleep(1);
            } while (System.nanoTime() < recoveryBlockedDeadline);
            assertNotNull(recoveryWorker);
            assertEquals(Thread.State.BLOCKED, recoveryWorker.getState(),
                    "recovery worker must be waiting to close admission under the same queue lock");
            producer.start();
            assertTrue(producerStarted.await(2, TimeUnit.SECONDS));
            long producerBlockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (producer.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < producerBlockedDeadline) {
                Thread.sleep(1);
            }
            assertEquals(Thread.State.BLOCKED, producer.getState(),
                    "producer must have passed its initial admission check and be waiting to enqueue");
        }

        assertTrue(producerFinished.await(5, TimeUnit.SECONDS));
        long failureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.isCaptureEnabled() && System.nanoTime() < failureDeadline) {
            java.lang.reflect.Field admissionField = InternalObservationService.class
                    .getDeclaredField("acceptingSubmissions");
            admissionField.setAccessible(true);
            if (!admissionField.getBoolean(service)) break;
            Thread.sleep(10);
        }
        if (accepted.get()) {
            long overflowDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!java.nio.file.Files.exists(overflowPath) && System.nanoTime() < overflowDeadline) {
                Thread.sleep(10);
            }
            assertTrue(java.nio.file.Files.exists(overflowPath),
                    "any event reported accepted during the race must be present in overflow recovery");
            PendingEvidenceSpool.Snapshot overflow = PendingEvidenceSpool.read(overflowPath);
            assertEquals(1, overflow.auditEvents().size());
            assertEquals("race-with-corrupt-recovery", overflow.auditEvents().getFirst().detail());
        } else {
            assertTrue(service.getTotalDropped() > 0,
                    "a producer blocked behind the failed recovery gate must be counted as rejected");
        }
        service.stop();
    }

    @Test
    void observationBatchIsRejectedAtomicallyWhenRecoveredQueueExceedsProducerLimit() throws Exception {
        initializeTopologyDatabase();
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        java.lang.reflect.Field capacityField = InternalObservationService.class
                .getDeclaredField("RECOVERY_QUEUE_CAPACITY");
        capacityField.setAccessible(true);
        int recoveryCapacity = capacityField.getInt(null);
        List<InternalObservation> recovered = new java.util.ArrayList<>(recoveryCapacity);
        for (int i = 0; i < recoveryCapacity; i++) {
            recovered.add(createDummyObservation(20_000 + i));
        }
        PendingEvidenceSpool.write(spoolPath, new PendingEvidenceSpool.Snapshot(
                recovered, List.of(), List.of()));
        Method restore = InternalObservationService.class.getDeclaredMethod(
                "restorePendingEvidence", Path.class);
        restore.setAccessible(true);
        restore.invoke(service, spoolPath);
        assertEquals(recoveryCapacity, service.getQueueSize());

        assertFalse(service.submitAll(List.of(
                createDummyObservation(50_001), createDummyObservation(50_002))));
        assertEquals(recoveryCapacity, service.getQueueSize(),
                "capacity rejection must not partially enqueue an observation batch");
        assertEquals(2, service.getTotalDropped(),
                "every event in a rejected batch must be counted explicitly");

        service.clear();
        java.nio.file.Files.deleteIfExists(spoolPath);
        java.nio.file.Files.deleteIfExists(PendingEvidenceSpool.overflowPath(spoolPath));
    }

    @Test
    void stalledJdbcShutdownReturnsAndRecoversTheAcceptedEvent() throws Exception {
        Path databasePath = tempDir.resolve("jdbc-stall/itemgraph.db");
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize(databasePath);
        Connection original = database.getConnection();
        CountDownLatch executeStarted = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        AtomicBoolean stallOnce = new AtomicBoolean(true);
        Connection stalledConnection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    Object result = invokeJdbc(original, method, args);
                    if (method.getName().equals("prepareStatement") && result instanceof PreparedStatement prepared) {
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                    if (statementMethod.getName().equals("executeUpdate") && stallOnce.compareAndSet(true, false)) {
                                        executeStarted.countDown();
                                        awaitUninterruptibly(releaseExecute);
                                        throw new SQLException("simulated interrupted JDBC request after shutdown timeout");
                                    }
                                    return invokeJdbc(prepared, statementMethod, statementArgs);
                                });
                    }
                    return result;
                });
        java.lang.reflect.Field connectionField = DatabaseManager.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(database, stalledConnection);

        assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                5678L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                1, 2, 3, null, "stalled-write", null)));
        service.start();
        service.onServerTick();
        assertTrue(executeStarted.await(2, TimeUnit.SECONDS), "the test must hold the worker inside JDBC");

        long began = System.nanoTime();
        service.stop();
        long shutdownMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        assertTrue(shutdownMs < 12_000, "a stalled JDBC call must not block server shutdown indefinitely");
        assertTrue(java.nio.file.Files.exists(spoolPath), "all accepted in-flight evidence must be in the recovery file before shutdown returns");
        assertEquals(0, service.getTotalDropped());

        database.close();
        releaseExecute.countDown();
        java.lang.reflect.Field workerField = InternalObservationService.class.getDeclaredField("workerThread");
        workerField.setAccessible(true);
        Thread worker = (Thread) workerField.get(service);
        long workerDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (worker.isAlive() && System.nanoTime() < workerDeadline) {
            Thread.sleep(10);
        }
        assertFalse(worker.isAlive(), "the interrupted JDBC worker should exit after its driver call returns");

        database.initialize(databasePath);
        service.start();
        long persistDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalPersisted() == 0 && System.nanoTime() < persistDeadline) {
            Thread.sleep(10);
        }
        assertEquals(1, service.getTotalPersisted(), "the spooled event must be replayed on restart");
        service.stop();
        assertFalse(java.nio.file.Files.exists(spoolPath), "durable replay must remove the recovery file");
        try (Statement statement = database.getConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events WHERE detail = 'stalled-write'")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    private static Object invokeJdbc(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException invocationFailure) {
            throw invocationFailure.getCause();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try {
                latch.await();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @Test
    void nativeAuditRawBytesAreDefensivelyCopied() {
        byte[] raw = {1, 2};
        InternalAuditEvent event = new InternalAuditEvent(
                1234L, "CHAT_MESSAGE", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, null, "hello", raw);

        raw[0] = 9;
        byte[] returned = event.rawData();
        returned[1] = 8;

        assertArrayEquals(new byte[]{1, 2}, event.rawData());
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
    void atomicObservationBatchCannotBePartiallyAcceptedDuringWorkerRequeue() throws Exception {
        for (int i = 0; i < 9_999; i++) {
            assertTrue(service.submit(createDummyObservation(i)));
        }
        Method requeue = InternalObservationService.class.getDeclaredMethod(
                "requeueObservationBatch", List.class, Exception.class);
        requeue.setAccessible(true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Boolean> acceptedBatch = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> batchProducer = executor.submit(() -> {
                ready.countDown();
                start.await();
                acceptedBatch.set(service.submitAll(List.of(
                        createDummyObservation(20_001), createDummyObservation(20_002))));
                return null;
            });
            Future<?> retryProducer = executor.submit(() -> {
                ready.countDown();
                start.await();
                requeue.invoke(service, List.of(createDummyObservation(20_003)),
                        new IllegalStateException("synthetic persistence failure"));
                return null;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            batchProducer.get();
            retryProducer.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(Boolean.FALSE, acceptedBatch.get(), "one free slot cannot accept half of a two-row batch");
        assertEquals(10_000, service.getQueueSize(), "the retry producer and batch producer share bounded capacity");
        assertEquals(9_999, service.getTotalEnqueued(), "rejected related rows must not increment enqueue count");
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
        assertEquals(1, service.getTotalDropped(), "Rejected transformations must be reported as evidence loss");
    }

    @Test
    void nativeCaptureCanBeDisabledWithoutTreatingSuppressionAsQueueLoss() {
        service.configureOperations(125, 1, 7, 30_000, false);
        try {
            assertEquals(125, service.getQueuePollIntervalMs());
            assertEquals(7, service.getMaxBatchSize());
            assertFalse(service.isCaptureEnabled());
            assertTrue(service.submit(createDummyObservation(1)));
            assertTrue(service.submitTransformation(createDummyTransformation(1)));
            assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                    1L, "TEST", PLAYER_UUID, "Alex", "minecraft:overworld", 0, 0, 0,
                    null, null, null)));
            assertEquals(0, service.getQueueSize());
            assertEquals(0, service.getTotalEnqueued());
            assertEquals(0, service.getTotalDropped());
        } finally {
            service.configureOperations(250, 1, 100, 30_000, true);
        }
    }

    @Test
    void failedNetworkDatabaseInitializationIsCountedAsAHeartbeatFailure() throws Exception {
        DatabaseManager database = DatabaseManager.getInstance();
        database.initialize(DatabaseSettings.mysqlMariaDb(
                "127.0.0.1", 1, "itemgraph_test", "test", "test", 250, true));
        assertFalse(database.isInitialized(), "the closed loopback port should not initialize a network database");

        service.configureOperations(10, 1, 100, 1_000, true);
        service.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalDatabaseHeartbeatFailures() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        service.stop();

        assertEquals(0, service.getTotalDatabaseHeartbeats(),
                "an unavailable network database cannot be reported as a successful keepalive");
        assertTrue(service.getTotalDatabaseHeartbeatFailures() >= 1);
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

        // stop() must wait for the worker-owned drain before returning
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
    void saturatedQueueDrainsOnWorkerDuringShutdownAndReportsExplicitRejection(
            @TempDir Path testTempDir) throws Exception {
        DatabaseManager.getInstance().initialize(testTempDir.resolve("shutdown_saturation.db"));
        OperationalMetrics.getInstance().reset();
        String prefix = "issue32-shutdown:" + UUID.randomUUID() + ":";
        long started = System.nanoTime();

        for (int index = 0; index < 10_000; index++) {
            assertTrue(service.submitAuditEvent(new InternalAuditEvent(
                    System.currentTimeMillis(), "SHUTDOWN_SATURATION", null, null,
                    "minecraft:overworld", index % 256, 64, (index / 256) % 256,
                    "itemgraph:shutdown-probe", prefix + index, null)),
                    "all events within the bounded audit queue must be accepted");
        }
        assertFalse(service.submitAuditEvent(new InternalAuditEvent(
                System.currentTimeMillis(), "SHUTDOWN_SATURATION", null, null,
                "minecraft:overworld", 0, 64, 0, "itemgraph:shutdown-probe", prefix + "rejected", null)),
                "the event beyond the audit queue capacity must be rejected explicitly");
        assertEquals(10_000, service.getQueueSize());
        assertEquals(1, service.getTotalDropped());

        service.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(0, service.getQueueSize(), "shutdown must drain all retained events");
        assertEquals(10_000, service.getTotalAuditEvents());
        assertEquals(10_000, service.getTotalPersisted());
        assertEquals(1, service.getTotalDropped(), "the overflow event must remain visible as explicit loss");

        long durableRows;
        try (PreparedStatement statement = DatabaseManager.getInstance().getConnection().prepareStatement(
                "SELECT COUNT(*) FROM ig_audit_events WHERE detail LIKE ?")) {
            statement.setString(1, prefix + "%");
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                durableRows = rows.getLong(1);
            }
        }
        assertEquals(10_000, durableRows, "every accepted event must be durable after shutdown returns");
        OperationalMetrics.Snapshot metrics = OperationalMetrics.getInstance().snapshot();
        assertEquals(10_000, metrics.peakQueueDepth());
        assertEquals(1, metrics.queueRejectedItems());
        assertTrue(metrics.largestBatchSize() <= service.getMaxBatchSize(),
                "shutdown persistence must honor the configured batch bound");

        PerformanceReportFixture.writeIfRequested("neoforge", "shutdown_saturation", java.util.Map.of(
                "accepted_events", 10_000L,
                "attempted_events", 10_001L,
                "persisted_counter_delta", service.getTotalPersisted(),
                "dropped_counter_delta", service.getTotalDropped(),
                "durable_rows", durableRows,
                "queue_remaining", (long) service.getQueueSize(),
                "elapsed_ms", elapsedMillis),
                neoForgeGriefLoggerRuntimeState());
    }

    private static String neoForgeGriefLoggerRuntimeState() {
        net.neoforged.fml.ModList modList = net.neoforged.fml.ModList.get();
        return modList == null ? "unavailable" : modList.isLoaded("grieflogger") ? "present" : "absent";
    }

    @Test
    void failedTransformationBatchIsSpooledAndReplayedAfterDatabaseReturns() throws Exception {
        Path databasePath = tempDir.resolve("recovery/itemgraph.db");
        DatabaseManager.getInstance().initialize(databasePath);
        DatabaseManager.getInstance().close();
        service.configureOperations(10, 1, 2, 30_000, true);
        service.start();
        for (int i = 0; i < 5; i++) {
            assertTrue(service.submitTransformation(createDummyTransformation(i)));
        }

        Thread.sleep(100);
        assertEquals(5, service.getQueueSize(),
                "a failed in-flight batch remains part of the outstanding transformation count");
        assertEquals(0, service.getTotalPersisted(),
                "an unavailable database cannot be reported as a successful write");

        service.stop();

        assertEquals(0, service.getQueueSize());
        assertEquals(0, service.getTotalPersisted());
        assertEquals(0, service.getTotalDropped(),
                "accepted records must be retained in the ItemGraph recovery file rather than counted as dropped");
        assertEquals(0, service.getTotalPersistenceOutcomeUnknown(),
                "no transaction was started for the unavailable database");
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        assertTrue(java.nio.file.Files.exists(spoolPath), "shutdown must save the failed batch for restart recovery");
        java.lang.reflect.Field pendingField = InternalObservationService.class
                .getDeclaredField("pendingTransformations");
        pendingField.setAccessible(true);
        java.util.concurrent.atomic.AtomicInteger pending =
                (java.util.concurrent.atomic.AtomicInteger) pendingField.get(service);
        assertEquals(0, pending.get(), "shutdown must release each failed transformation exactly once");

        DatabaseManager.getInstance().initialize(databasePath);
        service.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalPersisted() < 5 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(5, service.getTotalPersisted(), "every spooled transformation must be replayed into ItemGraph storage");
        service.stop();
        assertFalse(java.nio.file.Files.exists(spoolPath), "the recovery file is removed only after durable replay succeeds");
        try (Statement statement = DatabaseManager.getInstance().getConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_item_transformations")) {
            assertTrue(result.next());
            assertEquals(5, result.getInt(1));
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
        service.onServerTick();

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
    void workerPersistsConcurrentQueueLoadWithoutDuplicateOrLostRows(@TempDir Path testTempDir) throws Exception {
        initializeTopologyDatabase();
        service.configureOperations(10, 1, 250, 30_000, true);
        service.start();

        int threadCount = 8;
        int eventsPerThread = 250;
        int expected = threadCount * eventsPerThread;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService producers = Executors.newFixedThreadPool(threadCount);
        try {
            for (int thread = 0; thread < threadCount; thread++) {
                int firstId = thread * eventsPerThread;
                producers.submit(() -> {
                    try {
                        startLatch.await();
                        for (int offset = 0; offset < eventsPerThread; offset++) {
                            if (service.submit(createDummyObservation(firstId + offset))) {
                                accepted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS),
                    "concurrent event producers should finish without waiting on database writes");
            assertEquals(expected, accepted.get(), "all events fit within the bounded queue");
            service.onServerTick();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (service.getTotalPersisted() < expected && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(expected, service.getTotalPersisted(),
                    "the worker should drain the queued load into SQLite");
            assertEquals(0, service.getQueueSize());

            try (Statement statement = conn.createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT COUNT(*), COUNT(DISTINCT ingest_event_uuid)
                         FROM ig_observations
                         WHERE action_type = 'EQUIP_ARMOR_STAND'
                         """)) {
                assertTrue(result.next());
                assertEquals(expected, result.getInt(1));
                assertEquals(expected, result.getInt(2), "every durable event must have one unique retry key");
            }
        } finally {
            producers.shutdownNow();
            service.stop();
            service.configureOperations(250, 1, 100, 30_000, true);
        }
    }

    @Test
    void confirmedRollbackIsClassifiedAsDefiniteLoss() throws Exception {
        int[] rollbackCalls = {0};
        Connection transaction = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getAutoCommit" -> true;
                        case "setAutoCommit" -> null;
                        case "rollback" -> {
                            rollbackCalls[0]++;
                            yield null;
                        }
                        case "commit" -> throw new AssertionError("commit must not run after the body fails");
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        Class<?> bodyType = Class.forName(
                "com.itemgraph.ingest.InternalObservationService$SqlTransactionBody");
        Object failingBody = Proxy.newProxyInstance(bodyType.getClassLoader(), new Class<?>[]{bodyType},
                (proxy, method, args) -> {
                    if (method.getName().equals("run")) {
                        throw new java.sql.SQLException("simulated statement failure");
                    }
                    return null;
                });
        Method runTransaction = InternalObservationService.class.getDeclaredMethod(
                "runTransaction", Connection.class, bodyType);
        runTransaction.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> runTransaction.invoke(null, transaction, failingBody));

        assertEquals(1, rollbackCalls[0], "the failed transaction must be rolled back");
        Method unknownCommit = InternalObservationService.class.getDeclaredMethod(
                "hasUnknownCommitOutcome", Exception.class);
        unknownCommit.setAccessible(true);
        assertFalse((boolean) unknownCommit.invoke(service, failure.getCause()),
                "a successful rollback must be classified as definite loss");
    }

    @Test
    void replayAfterCommittedBatchLostAcknowledgementDoesNotDuplicateEvidence() throws Exception {
        initializeTopologyDatabase();
        Path databasePath = tempDir.resolve("itemgraph.db");
        service.start();
        InternalObservation observation = createDummyObservation(9100);
        InternalTransformation transformation = createDummyTransformation(9100);
        InternalAuditEvent auditEvent = new InternalAuditEvent(
                9100L, "BREAK_BLOCK", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:stone", "test", null);

        // Commit to SQLite, then simulate a lost network acknowledgement. The
        // worker sees SQLException after the row is already durable and replays it.
        Method persistBatch = InternalObservationService.class.getDeclaredMethod(
                "persistBatchLocked", Connection.class, List.class);
        persistBatch.setAccessible(true);
        boolean[] loseAcknowledgement = {true};
        Connection acknowledgementLossConnection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(conn, args);
                        if (method.getName().equals("commit") && loseAcknowledgement[0]) {
                            loseAcknowledgement[0] = false;
                            throw new java.sql.SQLException("simulated lost commit acknowledgement");
                        }
                        return result;
                    } catch (InvocationTargetException invocationFailure) {
                        throw invocationFailure.getCause();
                    }
                });
        InvocationTargetException commitAcknowledgementFailure = assertThrows(InvocationTargetException.class,
                () -> persistBatch.invoke(service, acknowledgementLossConnection, List.of(observation)));
        assertInstanceOf(java.sql.SQLException.class, commitAcknowledgementFailure.getCause());
        Method unknownCommit = InternalObservationService.class.getDeclaredMethod(
                "hasUnknownCommitOutcome", Exception.class);
        unknownCommit.setAccessible(true);
        assertTrue((boolean) unknownCommit.invoke(service, commitAcknowledgementFailure.getCause()),
                "an exception after commit was attempted must be classified as unknown");
        assertTrue(loseAcknowledgement[0] == false, "the simulated exception must follow a successful commit");

        // Preserve the uncertain batch, stop, and replay it through startup's
        // normal recovery path. Its UUID must suppress the row already committed.
        Path spoolPath = tempDir.resolve("pending-evidence.json");
        Method writeSpool = InternalObservationService.class.getDeclaredMethod(
                "writePendingEvidenceSpool", PendingEvidenceSpool.Snapshot.class, Path.class);
        writeSpool.setAccessible(true);
        PendingEvidenceSpool.Snapshot recovery = new PendingEvidenceSpool.Snapshot(
                List.of(observation), List.of(transformation), List.of(auditEvent));
        writeSpool.invoke(service, recovery, spoolPath);
        assertTrue(java.nio.file.Files.exists(spoolPath));
        service.stop();
        DatabaseManager.getInstance().close();
        DatabaseManager.getInstance().initialize(databasePath);
        conn = DatabaseManager.getInstance().getConnection();
        service.start();

        long replayDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (service.getTotalPersisted() < 3 && System.nanoTime() < replayDeadline) {
            Thread.sleep(10);
        }
        assertEquals(3, service.getTotalPersisted(), "recovery restart must process every spooled evidence kind");
        service.stop();
        assertFalse(java.nio.file.Files.exists(spoolPath), "the recovery file is removed after all replayed records commit");

        try (Statement statement = conn.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT COUNT(*) FROM ig_observations WHERE action_type = 'EQUIP_ARMOR_STAND'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_item_transformations")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_audit_events")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void distinctEventsSharingLegacyDedupFieldsBothPersist() throws Exception {
        initializeTopologyDatabase();
        String itemEntityUuid = "123e4567-e89b-12d3-a456-426614174020";
        InternalObservation first = new InternalObservation(
                5000L, "PICKUP_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 10, 64, -20,
                "minecraft:overworld", 10.0, 64.0, -20.0,
                "GROUND", DIAMOND, 1, itemEntityUuid);
        InternalObservation second = new InternalObservation(
                5000L, "PICKUP_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 10, 64, -20,
                "minecraft:overworld", 10.0, 64.0, -20.0,
                "GROUND", DIAMOND, 1, itemEntityUuid);

        assertNotEquals(first.ingestEventUuid(), second.ingestEventUuid());
        persist(first, second);

        try (PreparedStatement statement = conn.prepareStatement("""
                SELECT COUNT(*), COUNT(DISTINCT ingest_event_uuid)
                FROM ig_observations
                WHERE action_type = 'PICKUP_ITEM' AND item_entity_uuid = ?
                """)) {
            statement.setString(1, itemEntityUuid);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1),
                        "distinct events must survive even when every V11 dedup field is identical");
                assertEquals(2, result.getInt(2));
            }
        }
    }

    @Test
    void concurrentSubmitAndStopNeverStrandsAnAcceptedObservation() throws Exception {
        initializeTopologyDatabase();
        service.start();
        CountDownLatch race = new CountDownLatch(1);
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> accepted = producer.submit(() -> {
                assertTrue(race.await(5, TimeUnit.SECONDS));
                return service.submit(containerObs("ADD_ITEM", PLAYER_UUID, "TestPlayer", 1));
            });
            race.countDown();
            service.stop();
            boolean wasAccepted = accepted.get(5, TimeUnit.SECONDS);

            assertEquals(0, service.getQueueSize(), "stop must leave no accepted record stranded in memory");
            assertEquals(wasAccepted ? 1 : 0, observationCount(),
                    "every accepted record must be durable before stop returns");

            long droppedBeforeLateSubmit = service.getTotalDropped();
            assertFalse(service.submit(containerObs("ADD_ITEM", PLAYER_UUID, "TestPlayer", 1)),
                    "submissions after stop must be rejected");
            assertEquals(droppedBeforeLateSubmit + 1, service.getTotalDropped(),
                    "a post-stop submission must be counted as explicit loss");
        } finally {
            producer.shutdownNow();
            assertTrue(producer.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private int observationCount() throws Exception {
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
            assertTrue(result.next());
            return result.getInt(1);
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
    void enderDepositUsesDurablePlayerOwnedExternalInventoryEndpoint() throws Exception {
        initializeTopologyDatabase();
        persist(new InternalObservation(
                System.currentTimeMillis(), "ADD_ITEM_ENDER", PLAYER_UUID, "Steve",
                "minecraft:overworld", 10, 64, -20,
                "minecraft:overworld", null, null, null,
                "ENDER_CHEST", DIAMOND, 3, null));

        ObsRow row = singleObservation();
        assertEquals("PLAYER", nodeTypeOf(row.nodeId()));
        assertEquals("EXTERNAL_INVENTORY", nodeTypeOf(row.targetNodeId()));
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT external_key FROM ig_nodes WHERE id = ?")) {
            statement.setLong(1, row.targetNodeId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("minecraft:ender_chest/" + PLAYER_UUID, result.getString(1));
            }
        }
    }

    @Test
    void enderSessionDeltaRemainsQueryableAfterDatabaseRestart() throws Exception {
        Path databasePath = tempDir.resolve("itemgraph.db");
        initializeTopologyDatabase();
        CanonicalItem opaqueEnderItem = new CanonicalItem(
                "example:opaque_item", "sha256:canonical-fingerprint", null, null, null);
        byte[] enderSession = "{\"capture\":\"ender_inventory_session_net_delta\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        service.submit(new InternalObservation(
                System.currentTimeMillis(), "ADD_ITEM_ENDER", PLAYER_UUID, "Steve",
                "minecraft:overworld", 10, 64, -20,
                "minecraft:overworld", null, null, null,
                "ENDER_CHEST", opaqueEnderItem.itemId(), enderSession, opaqueEnderItem, 2,
                null, System.currentTimeMillis()));
        service.stop();
        DatabaseManager.getInstance().close();

        DatabaseManager.getInstance().initialize(databasePath);
        IngestionService.getInstance().getNodeManager().clearCaches();
        conn = DatabaseManager.getInstance().getConnection();
        try (Statement statement = conn.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT action_type, amount, raw_data, source_type, target_node_id
                     FROM ig_observations
                     """)) {
            assertTrue(result.next());
            assertEquals("ADD_ITEM_ENDER", result.getString("action_type"));
            assertEquals(2, result.getInt("amount"));
            assertEquals("ITEMGRAPH_INTERNAL", result.getString("source_type"));
            assertEquals("EXTERNAL_INVENTORY", nodeTypeOf(result.getLong("target_node_id")));
            String enderKey;
            try (PreparedStatement endpoint = conn.prepareStatement(
                    "SELECT external_key FROM ig_nodes WHERE id = ?")) {
                endpoint.setLong(1, result.getLong("target_node_id"));
                try (ResultSet node = endpoint.executeQuery()) {
                    assertTrue(node.next());
                    enderKey = node.getString(1);
                }
            }
            assertEquals("minecraft:ender_chest/" + PLAYER_UUID, enderKey);
            assertFalse(new String(result.getBytes("raw_data"), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("canonical-fingerprint"));
            assertFalse(result.next(), "restart must not duplicate the persisted Ender delta");
        }
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

    @Test
    void durableSourceEventIdDeduplicatesProjectileRetry() throws Exception {
        initializeTopologyDatabase();
        long sourceEventId = InternalObservationService.sourceEventIdForUuid(
                "123e4567-e89b-12d3-a456-426614174000");
        byte[] raw = "{\"capture\":\"projectile_shoot_attempt\",\"event_id\":\"123e4567-e89b-12d3-a456-426614174000\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        InternalObservation observation = new InternalObservation(
                1234L, "THROW_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 5, 64, 5,
                "minecraft:overworld", null, null, null,
                "UNKNOWN", DIAMOND.itemId(), raw, DIAMOND, 1, null, null, sourceEventId);

        persist(observation);
        persist(observation);

        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT source_event_id, COUNT(*) FROM ig_observations GROUP BY source_event_id")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(sourceEventId, result.getLong(1));
                assertEquals(1, result.getInt(2));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void distinctObservationPayloadsSurviveAProjectedSourceIdCollision() throws Exception {
        initializeTopologyDatabase();
        long sourceEventId = InternalObservationService.sourceEventIdForUuid(
                "123e4567-e89b-12d3-a456-426614174010");
        InternalObservation first = new InternalObservation(
                1234L, "THROW_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 5, 64, 5,
                "minecraft:overworld", null, null, null,
                "UNKNOWN", DIAMOND.itemId(), eventPayload("123e4567-e89b-12d3-a456-426614174010", "first"),
                DIAMOND, 1, null, null, sourceEventId);
        InternalObservation second = new InternalObservation(
                1235L, "THROW_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 5, 64, 5,
                "minecraft:overworld", null, null, null,
                "UNKNOWN", DIAMOND.itemId(), eventPayload("123e4567-e89b-12d3-a456-426614174011", "second"),
                DIAMOND, 1, null, null, sourceEventId);

        persist(first, second);
        persist(first, second);

        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT source_event_id, raw_data FROM ig_observations ORDER BY id")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                long firstId = result.getLong(1);
                assertArrayEquals(eventPayload("123e4567-e89b-12d3-a456-426614174010", "first"), result.getBytes(2));
                assertTrue(result.next());
                long secondId = result.getLong(1);
                assertArrayEquals(eventPayload("123e4567-e89b-12d3-a456-426614174011", "second"), result.getBytes(2));
                assertNotEquals(firstId, secondId,
                        "a projected source ID collision must not drop a distinct observation payload");
                assertFalse(result.next());
            }
        }
    }

    @Test
    void pairedAuditAndObservationRowsShareCollisionRemap() throws Exception {
        initializeTopologyDatabase();
        long sourceEventId = InternalObservationService.sourceEventIdForUuid(
                "123e4567-e89b-12d3-a456-426614174020");
        try (Statement statement = conn.createStatement()) {
            statement.execute("INSERT INTO ig_nodes (id, node_type, level_id) "
                    + "VALUES (99998, 'PLAYER', 'minecraft:overworld')");
            statement.execute("INSERT INTO ig_item_fingerprints (id, item_id, fingerprint_hash) "
                    + "VALUES (99998, 'minecraft:stone', 'collision-test')");
            statement.execute("INSERT INTO ig_observations "
                    + "(source_type, source_event_id, timestamp_ms, node_id, fingerprint_id, action_type, amount, raw_data) "
                    + "VALUES ('ITEMGRAPH_INTERNAL', " + sourceEventId + ", 1234, 99998, 99998, 'BREAK_BLOCK', 1, X'7B226576656E745F6964223A2231323365343536372D653839622D313264332D613435362D343236363134313734303938227D')");
            statement.execute("INSERT INTO ig_audit_events "
                    + "(event_type, timestamp_ms, source_type, source_event_id, raw_data) "
                    + "VALUES ('BREAK_BLOCK', 1234, 'ITEMGRAPH_INTERNAL', " + sourceEventId
                    + ", X'7B226576656E745F6964223A2231323365343536372D653839622D313264332D613435362D343236363134313734303939227D')");
        }

        InternalObservation pairedObservation = new InternalObservation(
                1235L, "THROW_ITEM", PLAYER_UUID, "Steve",
                "minecraft:overworld", 5, 64, 5,
                "minecraft:overworld", null, null, null,
                "UNKNOWN", DIAMOND.itemId(), eventPayload("123e4567-e89b-12d3-a456-426614174020", "observation"),
                DIAMOND, 1, null, null, sourceEventId);
        persist(pairedObservation);
        persistAudit(new InternalAuditEvent(
                1236L, "PROJECTILE_SPAWN_ACCEPTED", PLAYER_UUID, "Alex", "minecraft:overworld",
                10, 64, -20, "minecraft:arrow", "paired-payload",
                eventPayload("123e4567-e89b-12d3-a456-426614174020", "audit"), sourceEventId));

        long observationId;
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT source_event_id FROM ig_observations WHERE action_type = 'THROW_ITEM'")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                observationId = result.getLong(1);
            }
        }
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT source_event_id FROM ig_audit_events WHERE detail = 'paired-payload'")) {
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(observationId, result.getLong(1),
                        "paired ledgers must use one collision remap for the shared source event");
            }
        }
    }

    private static byte[] eventPayload(String eventUuid, String payloadKind) {
        return ("{\"event_id\":\"" + eventUuid + "\",\"payload_kind\":\"" + payloadKind + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
