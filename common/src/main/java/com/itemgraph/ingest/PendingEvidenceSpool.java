package com.itemgraph.ingest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itemgraph.db.DatabaseSettings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Atomic, ItemGraph-owned recovery file for records still in memory when a JDBC
 * worker cannot finish during orderly shutdown. It is deleted only after every
 * recovered record has been acknowledged by ItemGraph's database.
 */
final class PendingEvidenceSpool {
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_FILE_BYTES = 256L * 1024L * 1024L;
    private static final Gson GSON = new Gson();

    record Snapshot(
            List<InternalObservationService.InternalObservation> observations,
            List<InternalObservationService.InternalTransformation> transformations,
            List<InternalObservationService.InternalAuditEvent> auditEvents) {
        Snapshot {
            observations = List.copyOf(observations);
            transformations = List.copyOf(transformations);
            auditEvents = List.copyOf(auditEvents);
        }

        int size() {
            return observations.size() + transformations.size() + auditEvents.stream()
                    .mapToInt(event -> 1 + event.relatedObservations().size()).sum();
        }
    }

    private PendingEvidenceSpool() {}

    static Path pathFor(DatabaseSettings settings) {
        Path parent = settings != null && settings.sqlitePath() != null
                ? settings.sqlitePath().toAbsolutePath().getParent()
                : Path.of("itemgraph").toAbsolutePath();
        return (parent == null ? Path.of(".").toAbsolutePath() : parent)
                .resolve("itemgraph-pending-evidence.json");
    }

    static Path overflowPath(Path primaryPath) {
        return primaryPath.resolveSibling(primaryPath.getFileName() + ".overflow");
    }

    static Snapshot merge(Snapshot... snapshots) {
        LinkedHashMap<String, InternalObservationService.InternalObservation> observations = new LinkedHashMap<>();
        LinkedHashMap<String, InternalObservationService.InternalTransformation> transformations = new LinkedHashMap<>();
        LinkedHashMap<String, InternalObservationService.InternalAuditEvent> auditEvents = new LinkedHashMap<>();
        for (Snapshot snapshot : snapshots) {
            snapshot.observations().forEach(event -> observations.put(event.ingestEventUuid(), event));
            snapshot.transformations().forEach(event -> transformations.put(event.ingestEventUuid(), event));
            snapshot.auditEvents().forEach(event -> auditEvents.put(event.ingestEventUuid(), event));
        }
        return new Snapshot(new ArrayList<>(observations.values()),
                new ArrayList<>(transformations.values()), new ArrayList<>(auditEvents.values()));
    }

    static Snapshot read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new Snapshot(List.of(), List.of(), List.of());
        }
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) {
            throw new IOException("pending evidence spool exceeds the 256 MiB safety limit");
        }
        JsonObject root;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("pending evidence spool is malformed; original file was preserved", malformed);
        }
        int version;
        try {
            version = root.has("format_version") ? root.get("format_version").getAsInt() : -1;
        } catch (RuntimeException malformedVersion) {
            throw new IOException("pending evidence spool has an invalid format version; original file was preserved", malformedVersion);
        }
        if (version != FORMAT_VERSION) {
            throw new IOException("pending evidence spool has an unsupported format version; original file was preserved");
        }
        Snapshot snapshot = new Snapshot(
                decode(root, "observations", InternalObservationService.InternalObservation.class),
                decode(root, "transformations", InternalObservationService.InternalTransformation.class),
                decode(root, "audit_events", InternalObservationService.InternalAuditEvent.class));
        return regroupLegacyContainerBreaks(snapshot);
    }

    /** Upgrade pre-grouping shutdown snapshots so a completion row cannot outlive its slot rows. */
    private static Snapshot regroupLegacyContainerBreaks(Snapshot snapshot) {
        Map<String, List<InternalObservationService.InternalObservation>> slotsByParent = new HashMap<>();
        List<InternalObservationService.InternalObservation> ungrouped = new ArrayList<>();
        Map<String, Boolean> knownParents = new HashMap<>();
        for (InternalObservationService.InternalAuditEvent event : snapshot.auditEvents()) {
            String parentId = containerBreakParentId(event);
            if (parentId != null && event.relatedObservations().isEmpty()) knownParents.put(parentId, true);
        }
        for (InternalObservationService.InternalObservation observation : snapshot.observations()) {
            String parentId = containerBreakCauseId(observation);
            if (parentId != null && knownParents.containsKey(parentId)) {
                slotsByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(observation);
            } else {
                ungrouped.add(observation);
            }
        }
        if (slotsByParent.isEmpty()) return snapshot;
        List<InternalObservationService.InternalAuditEvent> auditEvents = new ArrayList<>(snapshot.auditEvents().size());
        for (InternalObservationService.InternalAuditEvent event : snapshot.auditEvents()) {
            String parentId = containerBreakParentId(event);
            List<InternalObservationService.InternalObservation> slots = parentId == null
                    ? null : slotsByParent.get(parentId);
            auditEvents.add(slots == null ? event : event.withRelatedObservations(slots));
        }
        return new Snapshot(ungrouped, snapshot.transformations(), auditEvents);
    }

    private static String containerBreakParentId(InternalObservationService.InternalAuditEvent event) {
        if (!"CONTAINER_BREAK_COMPLETED".equals(event.eventType()) || event.rawData() == null) return null;
        try {
            JsonObject details = JsonParser.parseString(new String(event.rawData(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return details.has("event_id") ? details.get("event_id").getAsString() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static String containerBreakCauseId(InternalObservationService.InternalObservation observation) {
        if (!"REMOVE_ITEM".equals(observation.actionType()) || observation.rawData() == null) return null;
        try {
            JsonObject details = JsonParser.parseString(new String(observation.rawData(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return details.has("cause_event_id") ? details.get("cause_event_id").getAsString() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static <T> List<T> decode(JsonObject root, String field, Class<T> type) throws IOException {
        JsonArray array;
        try {
            array = root.getAsJsonArray(field);
        } catch (RuntimeException malformedArray) {
            throw new IOException("pending evidence spool has an invalid " + field + " array; original file was preserved", malformedArray);
        }
        if (array == null) {
            throw new IOException("pending evidence spool is missing " + field + "; original file was preserved");
        }
        List<T> records = new ArrayList<>(array.size());
        try {
            for (var value : array) {
                if (!value.isJsonObject() || !value.getAsJsonObject().has("ingestEventUuid")
                        || !value.getAsJsonObject().get("ingestEventUuid").isJsonPrimitive()
                        || !value.getAsJsonObject().get("ingestEventUuid").getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException("record is missing its stable ingestEventUuid");
                }
                java.util.UUID.fromString(value.getAsJsonObject().get("ingestEventUuid").getAsString());
                T record = GSON.fromJson(value, type);
                if (record == null) {
                    throw new IllegalArgumentException("record decoded to null");
                }
                records.add(record);
            }
        } catch (RuntimeException malformed) {
            throw new IOException("pending evidence spool contains an invalid " + field + " record; original file was preserved", malformed);
        }
        return records;
    }

    static void write(Path path, Snapshot snapshot) throws IOException {
        if (snapshot.size() == 0) {
            delete(path);
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format_version", FORMAT_VERSION);
        root.add("observations", GSON.toJsonTree(snapshot.observations()));
        root.add("transformations", GSON.toJsonTree(snapshot.transformations()));
        root.add("audit_events", GSON.toJsonTree(snapshot.auditEvents()));
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("pending evidence spool would exceed the 256 MiB safety limit");
        }

        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp." + UUID.randomUUID());
        try {
            createPrivateTemporary(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void createPrivateTemporary(Path path) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException unsupported) {
            // Windows ACL inheritance applies when POSIX mode attributes are unavailable.
            Files.createFile(path);
        }
    }

    static void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
