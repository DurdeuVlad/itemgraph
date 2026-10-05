package com.itemgraph.ingest;

import com.itemgraph.canon.CanonicalItem;
import com.itemgraph.db.DatabaseSettings;
import com.itemgraph.ingest.InternalObservationService.InternalAuditEvent;
import com.itemgraph.ingest.InternalObservationService.InternalObservation;
import com.itemgraph.ingest.InternalObservationService.InternalTransformation;
import com.itemgraph.query.AuditEventQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PendingEvidenceSpoolTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyRoundTripsAllAcceptedEvidenceKinds() throws Exception {
        Path path = tempDir.resolve("pending.json");
        CanonicalItem item = new CanonicalItem("minecraft:diamond", "fingerprint", "named", "RARE", "custom_name=named");
        InternalObservation observation = new InternalObservation(
                1234L, "PICKUP_ITEM", "00000000-0000-0000-0000-000000000001", "Alex",
                "minecraft:overworld", 1, 2, 3, "minecraft:overworld", 4.0, 5.0, 6.0,
                "PLAYER", item.itemId(), new byte[]{0, 1, -1}, item, 3,
                "00000000-0000-0000-0000-000000000002", null, null,
                "00000000-0000-0000-0000-000000000003");
        InternalTransformation transformation = new InternalTransformation(
                2345L, "CRAFT", "00000000-0000-0000-0000-000000000001", "Alex",
                "minecraft:overworld", 7, 8, 9, item, item, 2, "recipe=diamond",
                "00000000-0000-0000-0000-000000000004");
        InternalAuditEvent audit = new InternalAuditEvent(
                3456L, "BREAK_BLOCK", "00000000-0000-0000-0000-000000000001", "Alex",
                "minecraft:overworld", 10, 11, 12, "minecraft:stone", "test", new byte[]{4, 5},
                null, "00000000-0000-0000-0000-000000000005",
                List.of(new AuditEventQueryService.ExactPosition(10, 11, 12)))
                .withRelatedObservations(List.of(new InternalObservation(
                        3456L, "REMOVE_ITEM", "00000000-0000-0000-0000-000000000001", "Alex",
                        "minecraft:overworld", 10, 11, 12, "minecraft:overworld", 10.0, 11.0, 12.0,
                        "DESTROYED_CONTAINER", item.itemId(), new byte[]{9}, item, 3, null, null, null,
                        "00000000-0000-0000-0000-000000000006")));
        PendingEvidenceSpool.Snapshot expected = new PendingEvidenceSpool.Snapshot(
                List.of(observation), List.of(transformation), List.of(audit));

        PendingEvidenceSpool.write(path, expected);
        PendingEvidenceSpool.Snapshot actual = PendingEvidenceSpool.read(path);

        assertEquals(4, actual.size());
        assertEquals(observation.ingestEventUuid(), actual.observations().getFirst().ingestEventUuid());
        assertArrayEquals(observation.rawData(), actual.observations().getFirst().rawData());
        assertEquals(item, actual.observations().getFirst().item());
        assertEquals(transformation, actual.transformations().getFirst());
        assertEquals(audit.ingestEventUuid(), actual.auditEvents().getFirst().ingestEventUuid());
        assertArrayEquals(audit.rawData(), actual.auditEvents().getFirst().rawData());
        assertEquals(audit.supersessionPositions(), actual.auditEvents().getFirst().supersessionPositions());
        assertEquals("00000000-0000-0000-0000-000000000006",
                actual.auditEvents().getFirst().relatedObservations().getFirst().ingestEventUuid());
    }

    @Test
    void legacySeparateContainerRowsAreRegroupedBeforeRecovery() throws Exception {
        Path path = tempDir.resolve("legacy-container.json");
        String parentId = "00000000-0000-0000-0000-000000000011";
        InternalObservation slot = new InternalObservation(
                100L, "REMOVE_ITEM", "00000000-0000-0000-0000-000000000001", "Alex",
                "minecraft:overworld", 1, 64, 2, "minecraft:overworld", 3.0, 64.0, 2.0,
                "DESTROYED_CONTAINER", "minecraft:diamond", ("{\"cause_event_id\":\"" + parentId + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new CanonicalItem("minecraft:diamond", "fingerprint", null, null, null), 3,
                null, null, null, "00000000-0000-0000-0000-000000000012");
        InternalAuditEvent completed = new InternalAuditEvent(
                100L, "CONTAINER_BREAK_COMPLETED", "00000000-0000-0000-0000-000000000001", "Alex",
                "minecraft:overworld", 1, 64, 2, "minecraft:chest", "contents_snapshot=complete",
                ("{\"event_id\":\"" + parentId + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        PendingEvidenceSpool.write(path, new PendingEvidenceSpool.Snapshot(
                List.of(slot), List.of(), List.of(completed)));
        PendingEvidenceSpool.Snapshot recovered = PendingEvidenceSpool.read(path);

        assertTrue(recovered.observations().isEmpty(), "legacy slot rows must not recover in a separate queue");
        assertEquals(1, recovered.auditEvents().getFirst().relatedObservations().size());
        assertEquals(slot.ingestEventUuid(),
                recovered.auditEvents().getFirst().relatedObservations().getFirst().ingestEventUuid());
        assertEquals(2, recovered.size(), "the grouped recovery snapshot must still count both records");
    }

    @Test
    void malformedSpoolIsPreservedForOperatorRecovery() throws Exception {
        Path path = tempDir.resolve("pending.json");
        byte[] original = "{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, original);

        assertThrows(IOException.class, () -> PendingEvidenceSpool.read(path));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test
    void boundedShutdownReturnsWhenWorkerIgnoresInterrupts() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "spool-shutdown-test");
            thread.setDaemon(true);
            return thread;
        });
        executor.execute(() -> {
            started.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // Simulates a JDBC driver blocked in an operation that ignores interruption.
                }
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        long began = System.nanoTime();
        boolean terminated = WorkerShutdown.stop(executor, 50, 50);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        assertFalse(terminated, "an interrupt-ignoring JDBC worker must not hold server shutdown forever");
        assertTrue(elapsedMs < 1_000, "shutdown should honor its bounded waits");
        release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void boundedSpoolWriteDoesNotHoldShutdownWhenFilesystemStalls() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        long began = System.nanoTime();
        WorkerShutdown.SpoolWriteResult result = WorkerShutdown.writeSpoolBounded(() -> {
            started.countDown();
            try {
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Simulates an operating-system filesystem call that ignores interruption.
                    }
                }
            } finally {
                finished.countDown();
            }
            return 1;
        }, 50);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertFalse(result.completed(), "stalled recovery-file I/O must time out instead of holding the lifecycle callback");
        assertNull(result.failure());
        assertTrue(elapsedMs < 1_000, "the spool wait must honor its bound");
        release.countDown();
        assertTrue(finished.await(1, TimeUnit.SECONDS), "the daemon spool worker should finish after the simulated filesystem returns");
    }

    @Test
    void recoveryPathLivesBesideItemGraphSqliteDatabase() {
        Path database = tempDir.resolve("server/itemgraph.db");
        var settings = DatabaseSettings.sqlite(database);
        assertEquals(database.toAbsolutePath().getParent().resolve("itemgraph-pending-evidence.json"),
                PendingEvidenceSpool.pathFor(settings));
    }
}
