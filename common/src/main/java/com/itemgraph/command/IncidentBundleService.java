package com.itemgraph.command;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.itemgraph.query.AuditLookupFilters;
import com.itemgraph.query.EdgeExplanation;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.NodeRef;
import com.itemgraph.query.ObservationDetail;
import com.itemgraph.query.UnifiedEvidenceDetail;
import com.itemgraph.query.UnifiedEvidenceQueryService;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded, read-only JSON incident bundle creation and verification. */
public final class IncidentBundleService {
    public static final String SCHEMA = "itemgraph-incident-bundle";
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_EVIDENCE_RECORDS = 100;
    public static final int MAX_RECORDS = 200;
    public static final long MAX_BUNDLE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_FILENAME_LENGTH = 48;
    private static final int MAX_JSON_DEPTH = 64;
    private static final String GENESIS_HASH = "0".repeat(64);
    private static final Logger LOGGER = LoggerFactory.getLogger(IncidentBundleService.class);
    private static final Pattern SCORE_FACTORS = Pattern.compile(
            "Confidence ([0-9.]+) = base ([0-9.]+) x proximity ([0-9.]+) x pickup-ambiguity ([0-9.]+) x drop-ambiguity ([0-9.]+)\\.");
    private static final Pattern ALLOCATION_FACTORS = Pattern.compile(
            "Allocated ([0-9]+) units? \\(drop: ([0-9]+)/([0-9]+) allocated, residual ([0-9]+); pickup: ([0-9]+)/([0-9]+) allocated, residual ([0-9]+)\\)\\.");
    private static final Pattern WINDOW_FACTOR = Pattern.compile(
            "within the ([0-9]+)s correlation window\\.");
    private static final Pattern CANDIDATE_FACTORS = Pattern.compile(
            "Candidates: ([0-9]+) admissible pickups? for this drop(?: \\(chose the temporally closest; next-best was ([^)]+) further away\\))?; ([0-9]+) admissible drops? for that pickup(?: \\(nearest competing drop was ([^)]+) away\\))?\\.");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public enum RedactionProfile {
        REDACTED,
        FULL
    }

    public record ExportResult(String filename, int evidenceCount, int inferredEdgeCount, String finalHash) {}

    public record VerificationResult(boolean valid, int recordCount, String finalHash, String message) {}

    private IncidentBundleService() {}

    public static String normalizeFilename(String requested) {
        if (requested == null || requested.length() > MAX_FILENAME_LENGTH
                || !requested.matches("[A-Za-z0-9_-]{1,48}")) {
            throw new IllegalArgumentException("filename must use 1-48 letters, digits, '_' or '-'");
        }
        return requested + ".json";
    }

    public static ExportResult export(
            Connection connection,
            AuditLookupFilters filters,
            String dimension,
            double centerX,
            double centerY,
            double centerZ,
            int requestedLimit,
            RedactionProfile profile,
            Path exportDirectory,
            String filename,
            BooleanSupplier cancelled,
            BooleanSupplier commit,
            IntConsumer progress) throws SQLException, IOException {
        if (connection == null || filters == null || dimension == null || dimension.isBlank()
                || profile == null || exportDirectory == null || filename == null
                || cancelled == null || commit == null || progress == null) {
            throw new IllegalArgumentException("connection, query, location, profile, and output are required");
        }
        int limit = Math.max(1, Math.min(requestedLimit, MAX_EVIDENCE_RECORDS));
        Path output = safeOutputPath(exportDirectory, filename);
        checkCancelled(cancelled, "export was cancelled before query execution");
        List<UnifiedEvidenceDetail> matchingRows = new UnifiedEvidenceQueryService().findFiltered(
                connection, filters, dimension, centerX, centerY, centerZ, limit, 0);
        checkCancelled(cancelled, "export was cancelled during query execution");
        List<UnifiedEvidenceDetail> evidence = matchingRows.stream()
                .filter(row -> !"INFERRED".equals(row.source()))
                .toList();
        List<EdgeExplanation> linkedEdges = findSelectedEdges(connection, matchingRows, evidence);
        int edgeCount = Math.min(linkedEdges.size(), MAX_EVIDENCE_RECORDS);
        List<String> payloadHashes = new ArrayList<>(evidence.size());
        EvidenceReferences evidenceReferences = new EvidenceReferences(profile);
        for (int i = 0; i < evidence.size(); i++) {
            evidenceReferences.addObserved(evidence.get(i), i + 1);
        }
        for (EdgeExplanation edge : linkedEdges.subList(0, edgeCount)) {
            for (ObservationDetail observation : edge.evidence()) {
                evidenceReferences.registerSupportingObservation(observation.id());
            }
        }
        JsonArray records = new JsonArray();
        String previousHash = GENESIS_HASH;
        long serializedRecordBytes = 0;
        for (int i = 0; i < evidence.size(); i++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("export was cancelled");
            }
            UnifiedEvidenceDetail row = evidence.get(i);
            String evidenceRef = evidenceReferences.observedReference(row, i + 1);
            JsonObject payload = payload(row, profile, evidenceRef, evidenceReferences);
            String payloadHash = jsonStats(payload, MAX_BUNDLE_BYTES).sha256();
            String chainHash = sha256(previousHash + payloadHash);
            JsonObject record = new JsonObject();
            record.add("payload", payload);
            record.addProperty("payload_sha256", payloadHash);
            record.addProperty("previous_hash", previousHash);
            record.addProperty("chain_hash", chainHash);
            serializedRecordBytes = appendBoundedRecord(records, record, serializedRecordBytes);
            records.add(record);
            payloadHashes.add(payloadHash);
            previousHash = chainHash;
            if ((i + 1) % 25 == 0 || i + 1 == evidence.size()) {
                progress.accept(i + 1);
            }
        }

        for (int i = 0; i < edgeCount; i++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("export was cancelled");
            }
            JsonObject edgePayload = inferredEdgePayload(linkedEdges.get(i), profile,
                    evidenceReferences.inferenceReference(i + 1, linkedEdges.get(i).id()), evidenceReferences);
            String payloadHash = jsonStats(edgePayload, MAX_BUNDLE_BYTES).sha256();
            String chainHash = sha256(previousHash + payloadHash);
            JsonObject record = new JsonObject();
            record.add("payload", edgePayload);
            record.addProperty("payload_sha256", payloadHash);
            record.addProperty("previous_hash", previousHash);
            record.addProperty("chain_hash", chainHash);
            serializedRecordBytes = appendBoundedRecord(records, record, serializedRecordBytes);
            records.add(record);
            payloadHashes.add(payloadHash);
            previousHash = chainHash;
            if ((i + 1) % 25 == 0 || i + 1 == edgeCount) {
                progress.accept(evidence.size() + i + 1);
            }
        }
        boolean inferredEdgesTruncated = linkedEdges.size() > MAX_EVIDENCE_RECORDS;

        JsonObject manifest = new JsonObject();
        manifest.addProperty("schema", SCHEMA);
        manifest.addProperty("schema_version", SCHEMA_VERSION);
        manifest.addProperty("exported_at", Instant.now().toString());
        manifest.addProperty("redaction_profile", profile.name().toLowerCase(Locale.ROOT));
        manifest.addProperty("hash_algorithm", "SHA-256");
        manifest.addProperty("chain_formula", "SHA-256(previous_hash_hex || payload_sha256_hex)");
        manifest.addProperty("genesis_hash", GENESIS_HASH);
        manifest.addProperty("record_count", records.size());
        manifest.addProperty("evidence_record_count", evidence.size());
        manifest.addProperty("inferred_edge_count", edgeCount);
        manifest.addProperty("inferred_edges_truncated", inferredEdgesTruncated);
        manifest.addProperty("final_hash", previousHash);
        manifest.addProperty("query", profile == RedactionProfile.FULL
                ? filters.describe() : filters.describeRedacted());
        manifest.addProperty("dimension", profile == RedactionProfile.FULL ? dimension : null);
        addNumberOrNull(manifest, "center_x", profile == RedactionProfile.FULL ? centerX : null);
        addNumberOrNull(manifest, "center_y", profile == RedactionProfile.FULL ? centerY : null);
        addNumberOrNull(manifest, "center_z", profile == RedactionProfile.FULL ? centerZ : null);
        manifest.addProperty("radius_blocks", filters.radiusBlocks());
        manifest.addProperty("limit", limit);
        JsonArray hashes = new JsonArray();
        payloadHashes.forEach(hashes::add);
        manifest.add("payload_hashes", hashes);

        JsonObject bundle = new JsonObject();
        bundle.add("manifest", manifest);
        bundle.addProperty("manifest_sha256", jsonStats(manifest, MAX_BUNDLE_BYTES).sha256());
        bundle.add("records", records);
        if (jsonStats(bundle, MAX_BUNDLE_BYTES).byteCount() + 1 > MAX_BUNDLE_BYTES) {
            throw new IOException("bundle exceeds the " + MAX_BUNDLE_BYTES + " byte export limit");
        }
        byte[] bytes = (canonicalJson(bundle) + "\n").getBytes(StandardCharsets.UTF_8);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("export was cancelled");
        }

        Files.createDirectories(exportDirectory);
        Path temporary = exportDirectory.resolve(filename + ".tmp-" + UUID.randomUUID());
        Path lockPath = exportDirectory.resolve("." + filename + ".lock");
        boolean published = false;
        Throwable primaryFailure = null;
        try {
            Files.write(temporary, bytes, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("export was cancelled");
            }
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock outputLock;
                try {
                    outputLock = lockChannel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    throw new IOException("another export is writing that filename", busy);
                }
                if (outputLock == null) {
                    throw new IOException("another export is writing that filename");
                }
                try (outputLock) {
                    if (Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("an export with that name already exists");
                    }
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                        throw new IOException("export was cancelled");
                    }
                    if (!commit.getAsBoolean()) {
                        throw new IOException("export was cancelled before the output commit");
                    }
                    publishNoReplace(temporary, output);
                    published = true;
                }
            }
        } catch (IOException failure) {
            if (published) {
                LOGGER.warn("Incident bundle {} was published, but closing its output lock failed",
                        filename, failure);
            } else {
                primaryFailure = failure;
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (published) {
                    LOGGER.warn("Unable to remove temporary incident export file {}", temporary.getFileName(),
                            cleanupFailure);
                } else if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure);
                    LOGGER.warn("Unable to remove temporary incident export file {} after export failure",
                            temporary.getFileName(), cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
        return new ExportResult(filename, evidence.size(), edgeCount, previousHash);
    }

    public static VerificationResult verify(Path exportDirectory, String filename) throws IOException {
        return verify(exportDirectory, filename, () -> false);
    }

    static VerificationResult verify(Path exportDirectory, String filename,
                                     BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("verification was cancelled");
        }
        Path input = safeOutputPath(exportDirectory, filename);
        if (Files.isSymbolicLink(input)) {
            return invalid("symbolic-link bundles are not accepted");
        }
        long size = Files.size(input);
        if (size > MAX_BUNDLE_BYTES) {
            return invalid("bundle exceeds the " + MAX_BUNDLE_BYTES + " byte verification limit");
        }
        JsonObject bundle;
        try {
            byte[] bytes;
            try (var stream = Files.newInputStream(input)) {
                bytes = stream.readNBytes((int) MAX_BUNDLE_BYTES + 1);
            }
            if (bytes.length > MAX_BUNDLE_BYTES) {
                return invalid("bundle exceeds the " + MAX_BUNDLE_BYTES + " byte verification limit");
            }
            String json = decodeUtf8Strictly(bytes);
            rejectDuplicateObjectKeys(json);
            JsonElement parsed = JsonParser.parseString(json);
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("verification was cancelled");
            }
            if (!parsed.isJsonObject()) {
                return invalid("bundle root must be a JSON object");
            }
            bundle = parsed.getAsJsonObject();
        } catch (RuntimeException malformed) {
            return invalid("bundle is not valid JSON");
        }
        if (bundle.size() != 3 || !bundle.has("manifest") || !bundle.has("manifest_sha256")
                || !bundle.has("records") || !bundle.get("manifest").isJsonObject()
                || !bundle.get("records").isJsonArray()) {
            return invalid("bundle envelope fields are invalid");
        }
        JsonObject manifest = bundle.getAsJsonObject("manifest");
        if (!SCHEMA.equals(string(manifest, "schema"))
                || integer(manifest, "schema_version") != SCHEMA_VERSION
                || !"SHA-256".equals(string(manifest, "hash_algorithm"))
                || !"SHA-256(previous_hash_hex || payload_sha256_hex)".equals(string(manifest, "chain_formula"))
                || !GENESIS_HASH.equals(string(manifest, "genesis_hash"))) {
            return invalid("manifest schema or hash contract is unsupported");
        }
        if (!sha256(canonicalJson(manifest)).equals(string(bundle, "manifest_sha256"))) {
            return invalid("manifest hash mismatch");
        }

        JsonArray records = bundle.getAsJsonArray("records");
        int declaredCount = integer(manifest, "record_count");
        if (declaredCount < 0 || declaredCount > MAX_RECORDS || declaredCount != records.size()) {
            return invalid("record count does not match the bounded manifest count");
        }
        int declaredEvidenceCount = integer(manifest, "evidence_record_count");
        int declaredEdgeCount = integer(manifest, "inferred_edge_count");
        JsonElement edgesTruncated = manifest.get("inferred_edges_truncated");
        if (declaredEvidenceCount < 0 || declaredEdgeCount < 0
                || declaredEvidenceCount + declaredEdgeCount != declaredCount
                || declaredEvidenceCount > MAX_EVIDENCE_RECORDS
                || declaredEdgeCount > MAX_EVIDENCE_RECORDS
                || edgesTruncated == null || !edgesTruncated.isJsonPrimitive()
                || !edgesTruncated.getAsJsonPrimitive().isBoolean()) {
            return invalid("manifest evidence and inference counts are invalid");
        }
        JsonElement payloadHashesElement = manifest.get("payload_hashes");
        if (payloadHashesElement == null || !payloadHashesElement.isJsonArray()) {
            return invalid("manifest payload hashes are missing or malformed");
        }
        JsonArray payloadHashes = payloadHashesElement.getAsJsonArray();
        if (payloadHashes.size() != records.size()) {
            return invalid("manifest payload hashes do not match the records");
        }

        String previousHash = GENESIS_HASH;
        int evidenceRecords = 0;
        int inferredEdges = 0;
        for (int i = 0; i < records.size(); i++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IOException("verification was cancelled");
            }
            JsonElement element = records.get(i);
            if (!element.isJsonObject()) {
                return invalid("record " + i + " is not an object");
            }
            JsonObject record = element.getAsJsonObject();
            if (record.size() != 4 || !record.has("payload") || !record.get("payload").isJsonObject()
                    || !record.has("payload_sha256") || !record.has("previous_hash")
                    || !record.has("chain_hash")) {
                return invalid("record " + i + " has invalid envelope fields");
            }
            JsonObject payload = record.getAsJsonObject("payload");
            String recordType = string(payload, "record_type");
            if ("OBSERVED_EVIDENCE".equals(recordType)) {
                evidenceRecords++;
            } else if ("INFERRED_EDGE".equals(recordType)) {
                inferredEdges++;
            } else {
                return invalid("record " + i + " has an unsupported record type");
            }
            String payloadHash = sha256(canonicalJson(payload));
            JsonElement declaredPayloadHash = payloadHashes.get(i);
            if (!payloadHash.equals(string(record, "payload_sha256"))
                    || declaredPayloadHash == null || !declaredPayloadHash.isJsonPrimitive()
                    || !declaredPayloadHash.getAsJsonPrimitive().isString()
                    || !payloadHash.equals(declaredPayloadHash.getAsString())) {
                return invalid("payload hash mismatch at record " + i);
            }
            if (!previousHash.equals(string(record, "previous_hash"))) {
                return invalid("record order or predecessor mismatch at record " + i);
            }
            String expectedChainHash = sha256(previousHash + payloadHash);
            if (!expectedChainHash.equals(string(record, "chain_hash"))) {
                return invalid("chain hash mismatch at record " + i);
            }
            previousHash = expectedChainHash;
        }
        if (!previousHash.equals(string(manifest, "final_hash"))) {
            return invalid("final hash mismatch");
        }
        if (evidenceRecords != declaredEvidenceCount || inferredEdges != declaredEdgeCount) {
            return invalid("manifest record types do not match the declared evidence and inference counts");
        }
        return new VerificationResult(true, records.size(), previousHash,
                "bundle chain and manifest are valid");
    }

    private static JsonObject payload(UnifiedEvidenceDetail row, RedactionProfile profile, String evidenceId,
                                      EvidenceReferences references) {
        JsonObject payload = new JsonObject();
        payload.addProperty("record_type", "OBSERVED_EVIDENCE");
        payload.addProperty("source", row.source());
        payload.addProperty("evidence_id", evidenceId);
        nullable(payload, "source_database_sha256", row.sourceSha256());
        nullable(payload, "source_raw_payload_sha256", row.sourcePayloadSha256());
        nullable(payload, "source_table", row.orderingTableName());
        nullable(payload, "source_key", profile == RedactionProfile.FULL ? row.orderingSourceKey() : null);
        payload.addProperty("timestamp_ms", row.timestampMs());
        payload.addProperty("action", row.actionType());
        payload.addProperty("evidence_class", row.evidenceClass());
        payload.addProperty("quantity", row.quantity());
        payload.addProperty("quantity_impact", row.quantityImpact());
        nullable(payload, "reason_code", row.reasonCode());
        JsonArray candidates = new JsonArray();
        for (String candidateId : row.candidateEvidenceIds()) {
            String reference = candidateId;
            if (profile == RedactionProfile.REDACTED && candidateId.startsWith("observation#")) {
                try {
                    reference = references.candidateReference(Long.parseLong(
                            candidateId.substring("observation#".length())));
                } catch (NumberFormatException invalidCandidateId) {
                    reference = "unresolved_candidate_reference";
                }
            }
            candidates.add(reference);
        }
        payload.add("candidate_evidence_ids", candidates);
        payload.addProperty("candidate_evidence_truncated", row.candidateEvidenceTruncated());
        if (profile == RedactionProfile.FULL) {
            nullable(payload, "dimension", row.levelName());
            nullable(payload, "x", row.x());
            nullable(payload, "y", row.y());
            nullable(payload, "z", row.z());
            nullable(payload, "player", row.playerName());
            nullable(payload, "subject", row.subjectId());
            nullable(payload, "detail", row.detail());
        } else {
            payload.add("dimension", null);
            payload.add("x", null);
            payload.add("y", null);
            payload.add("z", null);
            payload.add("player", null);
            payload.add("subject", null);
            payload.add("detail", null);
        }
        return payload;
    }

    private static List<EdgeExplanation> findLinkedEdges(Connection connection,
                                                         List<UnifiedEvidenceDetail> evidence)
            throws SQLException {
        Set<Long> observationIds = new TreeSet<>();
        for (UnifiedEvidenceDetail row : evidence) {
            if (!row.evidenceId().startsWith("observation#")) {
                continue;
            }
            try {
                observationIds.add(Long.parseLong(row.evidenceId().substring("observation#".length())));
            } catch (NumberFormatException ignored) {
                // A malformed projection key cannot identify an ItemGraph observation.
            }
        }
        if (observationIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(observationIds.size(), "?"));
        String sql = "SELECT DISTINCT edge_id FROM ig_edge_evidence WHERE observation_id IN ("
                + placeholders + ") ORDER BY edge_id ASC LIMIT ?";
        List<Long> edgeIds = new ArrayList<>(MAX_EVIDENCE_RECORDS + 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = 1;
            for (long observationId : observationIds) {
                statement.setLong(parameter++, observationId);
            }
            statement.setInt(parameter, MAX_EVIDENCE_RECORDS + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    edgeIds.add(result.getLong(1));
                }
            }
        }

        ExplainQueryService explain = new ExplainQueryService();
        List<EdgeExplanation> edges = new ArrayList<>(edgeIds.size());
        for (long edgeId : edgeIds) {
            explain.findEdge(connection, edgeId).ifPresent(edges::add);
        }
        return List.copyOf(edges);
    }

    private static List<EdgeExplanation> findSelectedEdges(Connection connection,
                                                            List<UnifiedEvidenceDetail> matchingRows,
                                                            List<UnifiedEvidenceDetail> evidence)
            throws SQLException {
        java.util.TreeMap<Long, EdgeExplanation> selected = new java.util.TreeMap<>();
        for (EdgeExplanation edge : findLinkedEdges(connection, evidence)) {
            selected.put(edge.id(), edge);
        }
        ExplainQueryService explain = new ExplainQueryService();
        for (UnifiedEvidenceDetail row : matchingRows) {
            if (!"INFERRED".equals(row.source()) || !row.evidenceId().startsWith("edge#")) {
                continue;
            }
            try {
                long edgeId = Long.parseLong(row.evidenceId().substring("edge#".length()));
                explain.findEdge(connection, edgeId).ifPresent(edge -> selected.put(edge.id(), edge));
            } catch (NumberFormatException invalidId) {
                // Invalid result identifiers cannot be opened as stored inferred rows.
            }
        }
        return List.copyOf(selected.values());
    }

    private static JsonObject inferredEdgePayload(EdgeExplanation edge, RedactionProfile profile,
                                                 String inferenceId, EvidenceReferences evidenceReferences) {
        JsonObject payload = new JsonObject();
        payload.addProperty("record_type", "INFERRED_EDGE");
        payload.addProperty("inference_id", inferenceId);
        payload.addProperty("evidence_class", "INFERRED");
        payload.addProperty("edge_state", edge.edgeState());
        payload.addProperty("amount", edge.amount());
        payload.addProperty("time_start_ms", edge.timeStart());
        payload.addProperty("time_end_ms", edge.timeEnd());
        payload.addProperty("confidence", edge.confidence());
        payload.addProperty("quantity_impact", edge.amount());
        JsonArray competingCandidates = new JsonArray();
        for (String candidate : edge.competingCandidateEvidenceIds()) {
            String reference = candidate;
            if (profile == RedactionProfile.REDACTED && candidate.startsWith("observation#")) {
                try {
                    reference = evidenceReferences.candidateReference(Long.parseLong(
                            candidate.substring("observation#".length())));
                } catch (NumberFormatException invalidCandidateId) {
                    reference = "unresolved_candidate_reference";
                }
            }
            competingCandidates.add(reference);
        }
        payload.add("candidate_evidence_ids", competingCandidates);
        payload.addProperty("candidate_evidence_truncated", edge.competingCandidatesTruncated());
        nullable(payload, "reason_code", edge.competingCandidatesReasonCode());
        payload.addProperty("candidate_evidence_available", edge.competingCandidatesAvailable());
        payload.add("origin", node(edge.from(), profile, evidenceReferences));
        payload.add("destination", node(edge.to(), profile, evidenceReferences));

        JsonObject fingerprint = new JsonObject();
        fingerprint.addProperty("fingerprint_id",
                evidenceReferences.fingerprintReference(edge.fingerprint().id()));
        if (profile == RedactionProfile.FULL) {
            nullable(fingerprint, "item_id", edge.fingerprint().itemId());
            nullable(fingerprint, "custom_name", edge.fingerprint().customName());
            nullable(fingerprint, "fingerprint_sha256", edge.fingerprint().fingerprintHash());
        } else {
            fingerprint.add("item_id", null);
            fingerprint.add("custom_name", null);
            fingerprint.add("fingerprint_sha256", null);
        }
        payload.add("fingerprint", fingerprint);

        JsonArray supportingEvidence = new JsonArray();
        JsonArray supportingObservations = new JsonArray();
        edge.evidence().forEach(observation -> {
            String evidenceId = evidenceReferences.observationReference(observation.id());
            supportingEvidence.add(evidenceId);
            supportingObservations.add(supportingObservation(observation, profile, evidenceId, evidenceReferences));
        });
        payload.add("supporting_evidence_ids", supportingEvidence);
        payload.add("supporting_observations", supportingObservations);
        payload.addProperty("supporting_evidence_truncated", edge.evidenceTruncated());
        if (profile == RedactionProfile.FULL) {
            nullable(payload, "stored_explanation", edge.explanation());
        } else {
            payload.add("stored_explanation", null);
            payload.add("scoring_factors", scoringFactors(edge));
        }
        return payload;
    }

    private static JsonObject node(NodeRef node, RedactionProfile profile, EvidenceReferences references) {
        if (node == null) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty("node_id", profile == RedactionProfile.FULL
                ? "node#" + node.id() : references.nodeReference(node.id()));
        nullable(result, "node_type", node.nodeType());
        if (profile == RedactionProfile.FULL) {
            nullable(result, "label", node.label());
            nullable(result, "dimension", node.levelId());
            nullable(result, "x", node.x());
            nullable(result, "y", node.y());
            nullable(result, "z", node.z());
            nullable(result, "owner_uuid", node.ownerUuid());
            nullable(result, "external_key", node.externalKey());
        } else {
            result.add("label", null);
            result.add("dimension", null);
            result.add("x", null);
            result.add("y", null);
            result.add("z", null);
            result.add("owner_uuid", null);
            result.add("external_key", null);
        }
        return result;
    }

    private static JsonObject supportingObservation(ObservationDetail observation,
                                                    RedactionProfile profile, String evidenceId,
                                                    EvidenceReferences references) {
        JsonObject payload = new JsonObject();
        payload.addProperty("record_type", "OBSERVED_EVIDENCE");
        payload.addProperty("evidence_id", evidenceId);
        nullable(payload, "source_type", observation.sourceType());
        nullable(payload, "source_event_id",
                profile == RedactionProfile.FULL ? observation.sourceEventId() : null);
        payload.addProperty("timestamp_ms", observation.timestampMs());
        payload.addProperty("action", observation.actionType());
        payload.addProperty("evidence_class", observation.evidenceClass().name());
        payload.addProperty("quantity", observation.amount());
        payload.addProperty("quantity_impact", observation.quantityImpact());
        nullable(payload, "reason_code", observation.reasonCode());
        JsonArray candidateEvidence = new JsonArray();
        if (observation.sourceGroup() != null) {
            for (String candidateId : observation.sourceGroup().candidateEvidenceIds()) {
                String reference = candidateId;
                if (profile == RedactionProfile.REDACTED && candidateId.startsWith("observation#")) {
                    try {
                    reference = references.candidateReference(Long.parseLong(
                                candidateId.substring("observation#".length())));
                    } catch (NumberFormatException invalidCandidateId) {
                        reference = "unresolved_candidate_reference";
                    }
                }
                candidateEvidence.add(reference);
            }
        }
        payload.add("candidate_evidence_ids", candidateEvidence);
        payload.addProperty("candidate_evidence_truncated", observation.sourceGroup() != null
                && observation.sourceGroup().candidatesTruncated());
        payload.add("origin", node(observation.origin(), profile, references));
        payload.add("destination", node(observation.destination(), profile, references));
        JsonObject fingerprint = new JsonObject();
        fingerprint.addProperty("fingerprint_id",
                references.fingerprintReference(observation.fingerprint().id()));
        if (profile == RedactionProfile.FULL) {
            nullable(fingerprint, "item_id", observation.fingerprint().itemId());
            nullable(fingerprint, "custom_name", observation.fingerprint().customName());
            nullable(fingerprint, "fingerprint_sha256", observation.fingerprint().fingerprintHash());
            nullable(payload, "item_entity_uuid", observation.itemEntityUuid());
        } else {
            fingerprint.add("item_id", null);
            fingerprint.add("custom_name", null);
            fingerprint.add("fingerprint_sha256", null);
            payload.add("item_entity_uuid", null);
        }
        payload.add("fingerprint", fingerprint);
        nullable(payload, "correlation_status", observation.correlationStatus());
        nullable(payload, "capture_type", observation.captureType());
        nullable(payload, "disposition_reason", observation.dispositionReason());
        return payload;
    }

    private static JsonObject scoringFactors(EdgeExplanation edge) {
        JsonObject factors = new JsonObject();
        factors.addProperty("confidence", edge.confidence());
        String explanation = edge.explanation() == null ? "" : edge.explanation();
        Matcher allocation = ALLOCATION_FACTORS.matcher(explanation);
        if (allocation.find()) {
            factors.addProperty("allocated_quantity", Integer.parseInt(allocation.group(1)));
            factors.addProperty("drop_allocated_quantity", Integer.parseInt(allocation.group(2)));
            factors.addProperty("drop_total_quantity", Integer.parseInt(allocation.group(3)));
            factors.addProperty("drop_residual_quantity", Integer.parseInt(allocation.group(4)));
            factors.addProperty("pickup_allocated_quantity", Integer.parseInt(allocation.group(5)));
            factors.addProperty("pickup_total_quantity", Integer.parseInt(allocation.group(6)));
            factors.addProperty("pickup_residual_quantity", Integer.parseInt(allocation.group(7)));
        }
        Matcher window = WINDOW_FACTOR.matcher(explanation);
        if (window.find()) {
            factors.addProperty("correlation_window_seconds", Integer.parseInt(window.group(1)));
        }
        Matcher candidates = CANDIDATE_FACTORS.matcher(explanation);
        if (candidates.find()) {
            int pickupCandidates = Integer.parseInt(candidates.group(1));
            int dropCandidates = Integer.parseInt(candidates.group(3));
            factors.addProperty("admissible_pickup_candidates", pickupCandidates);
            factors.addProperty("admissible_drop_candidates", dropCandidates);
            factors.addProperty("competing_candidates_present", pickupCandidates > 1 || dropCandidates > 1);
            nullable(factors, "next_best_pickup_gap", candidates.group(2));
            nullable(factors, "nearest_competing_drop_gap", candidates.group(4));
        }
        Matcher matcher = SCORE_FACTORS.matcher(explanation);
        if (matcher.find()) {
            factors.addProperty("base", Double.parseDouble(matcher.group(2)));
            factors.addProperty("proximity", Double.parseDouble(matcher.group(3)));
            factors.addProperty("pickup_ambiguity", Double.parseDouble(matcher.group(4)));
            factors.addProperty("drop_ambiguity", Double.parseDouble(matcher.group(5)));
            factors.addProperty("formula", "base * proximity * pickup_ambiguity * drop_ambiguity");
        } else if (explanation.contains("Authoritative Minecraft ItemEntity UUID match")) {
            factors.addProperty("method", "authoritative_item_entity_uuid_match");
            factors.addProperty("formula", "stored confidence from direct entity continuity");
        } else {
            factors.add("method", null);
            factors.addProperty("unavailable_reason", "stored explanation does not match a known scoring format");
        }
        return factors;
    }

    private static void addNumberOrNull(JsonObject object, String key, Double value) {
        if (value == null) {
            object.add(key, null);
        } else {
            object.addProperty(key, value);
        }
    }

    private static long appendBoundedRecord(JsonArray records, JsonObject record, long serializedRecordBytes)
            throws IOException {
        if (records.size() >= MAX_RECORDS) {
            throw new IOException("bundle exceeds the " + MAX_RECORDS + " record export limit");
        }
        long nextBytes = serializedRecordBytes + jsonStats(record, MAX_BUNDLE_BYTES).byteCount()
                + (records.isEmpty() ? 0 : 1);
        if (nextBytes > MAX_BUNDLE_BYTES) {
            throw new IOException("bundle records exceed the " + MAX_BUNDLE_BYTES + " byte export limit");
        }
        return nextBytes;
    }

    private record JsonStats(long byteCount, String sha256) {}

    private static JsonStats jsonStats(JsonElement value, long byteLimit) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        JsonMeasureOutputStream output = new JsonMeasureOutputStream(digest, byteLimit);
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
        } catch (com.google.gson.JsonIOException wrapped) {
            Throwable cause = wrapped;
            while (cause.getCause() != null && !(cause instanceof IOException)) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException ioFailure) {
                throw ioFailure;
            }
            throw wrapped;
        }
        return new JsonStats(output.byteCount(), HexFormat.of().formatHex(digest.digest()));
    }

    private static final class JsonMeasureOutputStream extends OutputStream {
        private final MessageDigest digest;
        private final long byteLimit;
        private long byteCount;

        private JsonMeasureOutputStream(MessageDigest digest, long byteLimit) {
            this.digest = digest;
            this.byteLimit = byteLimit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureWithinLimit(1);
            digest.update((byte) value);
            byteCount++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureWithinLimit(length);
            digest.update(bytes, offset, length);
            byteCount += length;
        }

        private void ensureWithinLimit(int nextBytes) throws IOException {
            if (nextBytes < 0 || byteCount + nextBytes > byteLimit) {
                throw new IOException("JSON value exceeds the " + byteLimit + " byte export limit");
            }
        }

        private long byteCount() {
            return byteCount;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled, String message) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException(message);
        }
    }

    private static void addNumberOrNull(JsonObject object, String key, double value) {
        object.addProperty(key, value);
    }

    private static final class EvidenceReferences {
        private final RedactionProfile profile;
        private final Map<Long, String> observationIds = new HashMap<>();
        private final Map<Long, String> supportingObservationIds = new HashMap<>();
        private final Map<Long, String> nodeIds = new HashMap<>();
        private final Map<Long, String> fingerprintIds = new HashMap<>();
        private int nextSupportReference = 1;
        private final Map<Long, String> externalCandidateIds = new HashMap<>();
        private int nextExternalCandidateReference = 1;
        private int nextNodeReference = 1;
        private int nextFingerprintReference = 1;

        private EvidenceReferences(RedactionProfile profile) {
            this.profile = profile;
        }

        private String addObserved(UnifiedEvidenceDetail row, int ordinal) {
            if (profile == RedactionProfile.FULL) {
                return row.evidenceId();
            }
            String reference = "evidence#" + ordinal;
            if (row.evidenceId().startsWith("observation#")) {
                try {
                    observationIds.put(Long.parseLong(
                            row.evidenceId().substring("observation#".length())), reference);
                } catch (NumberFormatException ignored) {
                    // Preserve the raw row in the bundle even if its local key is malformed.
                }
            }
            return reference;
        }

        private String observedReference(UnifiedEvidenceDetail row, int ordinal) {
            if (profile == RedactionProfile.FULL) {
                return row.evidenceId();
            }
            if (row.evidenceId().startsWith("observation#")) {
                try {
                    return observationIds.getOrDefault(Long.parseLong(
                            row.evidenceId().substring("observation#".length())), "evidence#" + ordinal);
                } catch (NumberFormatException ignored) {
                    // Preserve the raw row under its local ordinal if its key is malformed.
                }
            }
            return "evidence#" + ordinal;
        }

        private String inferenceReference(int ordinal, long edgeId) {
            return profile == RedactionProfile.FULL ? "edge#" + edgeId : "inference#" + ordinal;
        }

        private String observationReference(long observationId) {
            if (profile == RedactionProfile.FULL) {
                return "observation#" + observationId;
            }
            String includedReference = observationIds.get(observationId);
            if (includedReference != null) {
                return includedReference;
            }
            return registerSupportingObservation(observationId);
        }

        private String registerSupportingObservation(long observationId) {
            if (profile == RedactionProfile.FULL) {
                return "observation#" + observationId;
            }
            return supportingObservationIds.computeIfAbsent(observationId,
                    ignored -> "support#" + nextSupportReference++);
        }

        private String candidateReference(long observationId) {
            if (profile == RedactionProfile.FULL) {
                return "observation#" + observationId;
            }
            String includedReference = observationIds.get(observationId);
            if (includedReference != null) {
                return includedReference;
            }
            String supportingReference = supportingObservationIds.get(observationId);
            if (supportingReference != null) {
                return supportingReference;
            }
            return externalCandidateIds.computeIfAbsent(observationId,
                    ignored -> "external-candidate#" + nextExternalCandidateReference++);
        }

        private String nodeReference(long nodeId) {
            return nodeIds.computeIfAbsent(nodeId, ignored -> "node#" + nextNodeReference++);
        }

        private String fingerprintReference(long fingerprintId) {
            return profile == RedactionProfile.FULL ? "fingerprint#" + fingerprintId
                    : fingerprintIds.computeIfAbsent(fingerprintId,
                            ignored -> "fingerprint#" + nextFingerprintReference++);
        }
    }

    private static void nullable(JsonObject target, String key, Object value) {
        if (value instanceof String string) {
            target.addProperty(key, string);
        } else if (value instanceof Double number) {
            target.addProperty(key, number);
        } else if (value instanceof Long number) {
            target.addProperty(key, number);
        } else {
            target.add(key, null);
        }
    }

    private static Path safeOutputPath(Path directory, String filename) throws IOException {
        if (directory == null || filename == null || !filename.matches("[A-Za-z0-9_-]{1,48}\\.json")) {
            throw new IOException("export filename is invalid");
        }
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path output = normalizedDirectory.resolve(filename).normalize();
        if (!output.getParent().equals(normalizedDirectory)) {
            throw new IOException("export path escapes the ItemGraph export directory");
        }
        return output;
    }

    private static void publishNoReplace(Path temporary, Path output) throws IOException {
        // A same-filesystem hard link publishes the complete file atomically and
        // fails if any process has already created the destination. Fail closed on
        // filesystems that do not support hard links; a move fallback can race with
        // a non-cooperating writer on providers without atomic no-replace semantics.
        Files.createLink(output, temporary);
    }

    private static VerificationResult invalid(String message) {
        return new VerificationResult(false, 0, null, message);
    }

    private static void rejectDuplicateObjectKeys(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            consumeJsonValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("trailing content after JSON value");
            }
        } catch (IOException malformedJson) {
            throw new IllegalArgumentException("malformed JSON", malformedJson);
        }
    }

    private static String decodeUtf8Strictly(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("bundle is not valid UTF-8", malformedUtf8);
        }
    }

    private static void consumeJsonValue(JsonReader reader, int depth) throws IOException {
        JsonToken token = reader.peek();
        if ((token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY)
                && depth >= MAX_JSON_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds the verification limit");
        }
        switch (token) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> names = new HashSet<>();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (!names.add(name)) {
                        throw new IllegalArgumentException("duplicate JSON object key: " + name);
                    }
                    consumeJsonValue(reader, depth + 1);
                }
                reader.endObject();
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) {
                    consumeJsonValue(reader, depth + 1);
                }
                reader.endArray();
            }
            case STRING, NUMBER -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> reader.nextNull();
            default -> throw new IllegalArgumentException("unexpected JSON token in bundle");
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return -1;
        }
        try {
            return value.getAsInt();
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static String canonicalJson(JsonElement value) {
        return GSON.toJson(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
