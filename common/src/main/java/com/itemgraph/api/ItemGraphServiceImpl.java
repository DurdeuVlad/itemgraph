package com.itemgraph.api;

import com.itemgraph.command.ApiQueryBridge;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.graph.NodeManager;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.query.EdgeExplanation;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.NodeRef;
import com.itemgraph.query.TraceHop;
import com.itemgraph.query.TraceResult;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ItemGraphServiceImpl implements ItemGraphService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ItemGraphServiceImpl.class);
    private static final int SUBMISSION_QUEUE_CAPACITY = 1024;
    private static final long DRAIN_MS = 2_000;

    private final MinecraftServer server;
    private final long generation;
    private final DatabaseManager db;
    private final NodeManager nodeManager;
    private final Set<Pending<?>> pending = ConcurrentHashMap.newKeySet();

    private volatile boolean accepting;
    private volatile ExecutorService submissionExecutor;

    ItemGraphServiceImpl(MinecraftServer server, long generation) {
        this.server = server;
        this.generation = generation;
        this.db = DatabaseManager.getInstance();
        this.nodeManager = IngestionService.getInstance().getNodeManager();
    }

    void start() {
        submissionExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(SUBMISSION_QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "ItemGraph-Api-Worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        accepting = true;
    }

    void shutdown() {
        accepting = false;
        ExecutorService executor = submissionExecutor;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(DRAIN_MS, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (Pending<?> task : pending) {
            task.completeShutdown();
        }
    }

    MinecraftServer server() {
        return server;
    }

    @Override
    public ApiVersion apiVersion() {
        return ApiVersion.PREVIEW_1;
    }

    @Override
    public CompletableFuture<RegistrationResult> registerSource(SourceRegistration registration) {
        ApiValidation.ValidationFailure invalid = ApiValidation.validateRegistration(registration);
        if (invalid != null) {
            return CompletableFuture.completedFuture(new RegistrationResult(
                    RegistrationStatus.INVALID_INPUT, null, invalid.code(), invalid.message()));
        }
        return submit(() -> registrationResult(registration),
                new RegistrationResult(RegistrationStatus.SHUTDOWN, null, "SHUTDOWN",
                        "the API is shutting down"),
                new RegistrationResult(RegistrationStatus.FAILED, null, "QUEUE_FULL",
                        "the API submission queue is full"));
    }

    @Override
    public CompletableFuture<SubmissionResult> submitObservation(
            SourceHandle source, DirectObservation observation) {
        String sourceModId = source == null ? null : source.modId();
        long eventId = observation == null ? 0 : observation.sourceEventId();
        if (source == null) {
            return completedSubmission(SubmissionStatus.INVALID_INPUT, sourceModId, eventId,
                    "INVALID_SOURCE", "a service-issued SourceHandle is required");
        }
        if (source.serviceGeneration() != generation
                || source.apiVersion() != ApiVersion.PREVIEW_1) {
            return completedSubmission(SubmissionStatus.INVALID_INPUT, sourceModId, eventId,
                    "STALE_SOURCE", "the SourceHandle belongs to a different server lifetime");
        }
        ApiValidation.ValidationFailure invalid = ApiValidation.validateObservation(observation);
        if (invalid != null) {
            return completedSubmission(SubmissionStatus.INVALID_INPUT, sourceModId, eventId,
                    invalid.code(), invalid.message());
        }
        byte[] rawData = rawObservation(source, observation);
        if (rawData.length > ApiValidation.MAX_RAW_OBSERVATION_BYTES) {
            return completedSubmission(SubmissionStatus.INVALID_INPUT, sourceModId, eventId,
                    "PAYLOAD_TOO_LARGE", "the serialized submission exceeds 64 KiB");
        }
        return submit(() -> submissionResult(source, observation, rawData),
                new SubmissionResult(SubmissionStatus.SHUTDOWN, sourceModId, eventId,
                        "SHUTDOWN", "the API is shutting down"),
                new SubmissionResult(SubmissionStatus.QUEUE_FULL, sourceModId, eventId,
                        "QUEUE_FULL", "the API submission queue is full"));
    }

    @Override
    public CompletableFuture<QueryResult> traceItem(ItemQuery query, QueryOptions options) {
        options = options == null ? QueryOptions.defaults() : options;
        String invalid = ApiValidation.validateOptions(options);
        if (invalid != null) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY", invalid);
        }
        if (query == null || ApiValidation.selectorCount(query) != 1) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    "exactly one item selector is required");
        }
        if (query.itemId() != null && ResourceLocation.tryParse(query.itemId()) == null) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    "itemId must be a valid resource location");
        }
        if (query.customName() != null
                && (query.customName().isBlank() || query.customName().length() > 256)) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    "customName must be non-blank and at most 256 characters");
        }
        if (query.fingerprintHash() != null
                && !query.fingerprintHash().matches("[0-9a-f]{64}")) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    "fingerprintHash must be lowercase SHA-256 hex");
        }
        if (!accepting) {
            return completedQuery(QueryStatus.SHUTDOWN, "SHUTDOWN", "the API is shutting down");
        }
        if (!db.isInitialized()) {
            return completedQuery(QueryStatus.DATABASE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                    "the ItemGraph database is not connected");
        }
        ItemQuery effectiveQuery = query.itemId() != null
                ? ItemQuery.itemId(ResourceLocation.tryParse(query.itemId()).toString())
                : query;
        CompletableFuture<ApiQueryBridge.ApiTraceResponse> response;
        try {
            response = ApiQueryBridge.traceItem(effectiveQuery, options);
        } catch (RejectedExecutionException e) {
            return completedQuery(QueryStatus.QUEUE_FULL, "QUEUE_FULL",
                    "the shared query queue is full");
        }
        return response.handle(this::queryResult);
    }

    @Override
    public CompletableFuture<QueryResult> tracePlayer(PlayerQuery query, QueryOptions options) {
        QueryOptions effectiveOptions = options == null ? QueryOptions.defaults() : options;
        String invalid = ApiValidation.validateOptions(effectiveOptions);
        if (invalid != null || query == null || query.playerUuid() == null) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    invalid != null ? invalid : "playerUuid is required");
        }
        return query(() -> ApiQueryBridge.tracePlayer(query, effectiveOptions));
    }

    @Override
    public CompletableFuture<QueryResult> traceContainer(ContainerQuery query, QueryOptions options) {
        QueryOptions effectiveOptions = options == null ? QueryOptions.defaults() : options;
        String invalid = ApiValidation.validateOptions(effectiveOptions);
        if (invalid != null || query == null || query.level() == null || query.position() == null) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    invalid != null ? invalid : "level and position are required");
        }
        return query(() -> ApiQueryBridge.traceContainer(query, effectiveOptions));
    }

    @Override
    public CompletableFuture<QueryResult> traceExternalInventory(
            ExternalInventoryEndpoint inventory, QueryOptions options) {
        QueryOptions effectiveOptions = options == null ? QueryOptions.defaults() : options;
        String invalid = ApiValidation.validateOptions(effectiveOptions);
        if (invalid != null || inventory == null
                || !ApiValidation.validModId(inventory.ownerModId())
                || !ApiValidation.validInventoryId(inventory.inventoryId())) {
            return completedQuery(QueryStatus.INVALID_INPUT, "INVALID_QUERY",
                    invalid != null ? invalid : "a valid external inventory endpoint is required");
        }
        return query(() -> ApiQueryBridge.traceExternalInventory(inventory, effectiveOptions));
    }

    private CompletableFuture<QueryResult> query(
            Supplier<CompletableFuture<ApiQueryBridge.ApiTraceResponse>> supplier) {
        if (!accepting) {
            return completedQuery(QueryStatus.SHUTDOWN, "SHUTDOWN", "the API is shutting down");
        }
        if (!db.isInitialized()) {
            return completedQuery(QueryStatus.DATABASE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                    "the ItemGraph database is not connected");
        }
        try {
            return supplier.get().handle(this::queryResult);
        } catch (RejectedExecutionException e) {
            return completedQuery(QueryStatus.QUEUE_FULL, "QUEUE_FULL",
                    "the shared query queue is full");
        }
    }

    private QueryResult queryResult(ApiQueryBridge.ApiTraceResponse response, Throwable throwable) {
        if (throwable != null) {
            QueryStatus status = accepting ? QueryStatus.FAILED : QueryStatus.SHUTDOWN;
            LOGGER.debug("ItemGraph preview API query failed", throwable);
            return new QueryResult(status, null,
                    accepting ? "QUERY_FAILED" : "SHUTDOWN",
                    accepting ? "the ItemGraph query failed" : "the API is shutting down");
        }
        if (response.resolution() == ApiQueryBridge.Resolution.NOT_FOUND) {
            return new QueryResult(QueryStatus.NOT_FOUND, null, null, null);
        }
        if (response.resolution() == ApiQueryBridge.Resolution.AMBIGUOUS) {
            return new QueryResult(QueryStatus.AMBIGUOUS, ambiguousFlow(response), null, null);
        }
        return new QueryResult(QueryStatus.OK, flow(response), null, null);
    }

    private FlowResult ambiguousFlow(ApiQueryBridge.ApiTraceResponse response) {
        List<ItemDescriptor> items = response.itemCandidates().stream()
                .map(this::itemDescriptor)
                .toList();
        List<EndpointDescriptor> endpoints = response.endpointCandidates().stream()
                .map(this::endpointDescriptor)
                .filter(Objects::nonNull)
                .toList();
        return new FlowResult("ambiguous target", items, endpoints, List.of(),
                window(response.window()), response.requestedLimit(),
                Math.min(QueryOptions.MAX_LIMIT, response.requestedLimit()), false);
    }

    private FlowResult flow(ApiQueryBridge.ApiTraceResponse response) {
        TraceResult result = response.result();
        List<FlowHop> hops = new ArrayList<>();
        for (TraceHop hop : result.hops()) {
            hops.add(flowHop(hop, response.explanations().get(hop.refId())));
        }
        return new FlowResult(result.targetDescription(), List.of(), List.of(), hops,
                window(result.window()), result.requestedLimit(), result.appliedLimit(),
                result.truncated());
    }

    private FlowHop flowHop(TraceHop hop, EdgeExplanation explanation) {
        boolean inferred = hop.kind() == TraceHop.Kind.INFERRED;
        List<EvidenceRef> supporting = List.of();
        String explanationText = null;
        boolean supportingTruncated = false;
        if (inferred && explanation != null) {
            supporting = explanation.evidence().stream()
                    .map(detail -> new EvidenceRef(EvidenceKind.OBSERVATION,
                            "itemgraph:observation:" + detail.id()))
                    .toList();
            explanationText = explanation.explanation();
            supportingTruncated = explanation.evidenceTruncated();
        }
        return new FlowHop(
                inferred ? Provenance.INFERRED : Provenance.OBSERVED,
                evidence(hop),
                endpointDescriptor(hop.origin()),
                endpointDescriptor(hop.destination()),
                itemDescriptor(hop.item()),
                hop.amount(),
                hop.timestampMs(),
                hop.endMs(),
                hop.confidence(),
                hop.detail(),
                explanationText,
                supporting,
                supportingTruncated);
    }

    private EvidenceRef evidence(TraceHop hop) {
        return switch (hop.source()) {
            case OBSERVATION -> new EvidenceRef(EvidenceKind.OBSERVATION,
                    "itemgraph:observation:" + hop.refId());
            case TRANSFORMATION -> new EvidenceRef(EvidenceKind.TRANSFORMATION,
                    "itemgraph:transformation:" + hop.refId());
            case INFERRED_EDGE -> new EvidenceRef(EvidenceKind.INFERRED_EDGE,
                    "itemgraph:inferred-edge:" + hop.refId());
        };
    }

    private ItemDescriptor itemDescriptor(FingerprintRef fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        return new ItemDescriptor(fingerprint.itemId(), fingerprint.customName(),
                fingerprint.fingerprintHash());
    }

    private EndpointDescriptor endpointDescriptor(NodeRef node) {
        if (node == null || !node.resolved()) {
            return null;
        }
        EndpointKind kind = switch (node.nodeType()) {
            case "PLAYER" -> EndpointKind.PLAYER;
            case "CONTAINER" -> EndpointKind.CONTAINER;
            case "GROUND" -> EndpointKind.GROUND;
            case "ARMOR_STAND" -> EndpointKind.ARMOR_STAND;
            case "EXTERNAL_INVENTORY" -> EndpointKind.EXTERNAL_INVENTORY;
            default -> EndpointKind.UNKNOWN;
        };
        WorldLocation location = null;
        if ((kind == EndpointKind.CONTAINER || kind == EndpointKind.GROUND
                || kind == EndpointKind.ARMOR_STAND || kind == EndpointKind.EXTERNAL_INVENTORY)
                && node.x() != null && node.y() != null && node.z() != null) {
            ResourceLocation level = ResourceLocation.tryParse(node.levelId());
            if (level != null) {
                location = new WorldLocation(
                        ResourceKey.create(Registries.DIMENSION, level),
                        new BlockPos(node.x().intValue(), node.y().intValue(), node.z().intValue()));
            }
        }
        return new EndpointDescriptor(kind, stableKey(node, kind), node.label(), location);
    }

    private String stableKey(NodeRef node, EndpointKind kind) {
        return switch (kind) {
            case PLAYER -> node.ownerUuid() != null
                    ? "player:" + node.ownerUuid()
                    : "player-name:" + node.label();
            case CONTAINER, GROUND, ARMOR_STAND -> node.x() != null && node.y() != null && node.z() != null
                    ? node.levelId() + "/" + node.x().intValue() + "/"
                            + node.y().intValue() + "/" + node.z().intValue()
                    : "unresolved-node:" + node.id();
            case EXTERNAL_INVENTORY -> {
                String externalKey = node.externalKey();
                int separator = externalKey == null ? -1 : externalKey.indexOf('/');
                yield separator < 0
                        ? "external:" + externalKey
                        : "external:" + externalKey.substring(0, separator)
                                + ":" + externalKey.substring(separator + 1);
            }
            case UNKNOWN -> "unknown:" + node.levelId();
        };
    }

    private TimeWindow window(com.itemgraph.query.QueryWindow window) {
        return new TimeWindow(window.sinceMs(), window.untilMs());
    }

    private RegistrationResult registrationResult(SourceRegistration registration) {
        try {
            return persistRegistration(registration);
        } catch (SQLException e) {
            LOGGER.warn("ItemGraph API source registration failed for {}", registration.modId(), e);
            return new RegistrationResult(RegistrationStatus.FAILED, null,
                    "PERSISTENCE_FAILED", "the source registration could not be persisted");
        }
    }

    private RegistrationResult persistRegistration(SourceRegistration registration) throws SQLException {
        if (!db.isInitialized()) {
            return new RegistrationResult(RegistrationStatus.DATABASE_UNAVAILABLE, null,
                    "DATABASE_UNAVAILABLE", "the ItemGraph database is not connected");
        }
        long now = System.currentTimeMillis();
        synchronized (db.getConnection()) {
            Connection conn = db.getConnection();
            String existing = null;
            try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT display_name FROM ig_api_sources WHERE source_mod_id = ?")) {
                pstmt.setString(1, registration.modId());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        existing = rs.getString(1);
                    }
                }
            }
            try (PreparedStatement pstmt = conn.prepareStatement("""
                    INSERT INTO ig_api_sources
                        (source_mod_id, display_name, api_version, registered_at_ms, last_seen_ms)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(source_mod_id) DO UPDATE SET
                        display_name = excluded.display_name,
                        api_version = excluded.api_version,
                        last_seen_ms = excluded.last_seen_ms
                    """)) {
                pstmt.setString(1, registration.modId());
                pstmt.setString(2, registration.displayName());
                pstmt.setInt(3, ApiVersion.PREVIEW_1.number());
                pstmt.setLong(4, now);
                pstmt.setLong(5, now);
                pstmt.executeUpdate();
            }
            RegistrationStatus status = existing == null
                    ? RegistrationStatus.REGISTERED
                    : existing.equals(registration.displayName())
                            ? RegistrationStatus.UNCHANGED
                            : RegistrationStatus.UPDATED;
            LOGGER.info("ItemGraph API source registration {}: {} ({})",
                    status, registration.modId(), registration.displayName());
            return new RegistrationResult(status,
                    new SourceHandle(registration.modId(), registration.displayName(),
                            ApiVersion.PREVIEW_1, generation),
                    null, null);
        }
    }

    private SubmissionResult submissionResult(SourceHandle source, DirectObservation observation,
                                              byte[] rawData) {
        try {
            return persistObservation(source, observation, rawData);
        } catch (SQLException e) {
            LOGGER.warn("ItemGraph API observation persistence failed for {} event {}",
                    source.modId(), observation.sourceEventId(), e);
            return new SubmissionResult(SubmissionStatus.FAILED, source.modId(),
                    observation.sourceEventId(), "PERSISTENCE_FAILED",
                    "the observation could not be persisted");
        }
    }

    private SubmissionResult persistObservation(SourceHandle source, DirectObservation observation,
                                                byte[] rawData) throws SQLException {
        if (!db.isInitialized()) {
            return new SubmissionResult(SubmissionStatus.DATABASE_UNAVAILABLE, source.modId(),
                    observation.sourceEventId(), "DATABASE_UNAVAILABLE",
                    "the ItemGraph database is not connected");
        }
        String sourceType = "EXTERNAL_API:" + source.modId();
        synchronized (db.getConnection()) {
            Connection conn = db.getConnection();
            try (PreparedStatement existing = conn.prepareStatement(
                    "SELECT id FROM ig_observations WHERE source_type = ? AND source_event_id = ? LIMIT 1")) {
                existing.setString(1, sourceType);
                existing.setLong(2, observation.sourceEventId());
                try (ResultSet rs = existing.executeQuery()) {
                    if (rs.next()) {
                        return new SubmissionResult(SubmissionStatus.DUPLICATE, source.modId(),
                                observation.sourceEventId(), null, null);
                    }
                }
            }

            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long originNode = endpointNode(conn, observation.origin());
                long destinationNode = endpointNode(conn, observation.destination());
                long fingerprint = fingerprint(conn, observation.item());
                touchSource(conn, source.modId());

                try (PreparedStatement pstmt = conn.prepareStatement("""
                        INSERT OR IGNORE INTO ig_observations
                            (source_type, source_event_id, timestamp_ms, node_id, target_node_id,
                             fingerprint_id, action_type, amount, raw_data, correlation_status,
                             item_entity_uuid, timestamp_end_ms)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        """)) {
                    pstmt.setString(1, sourceType);
                    pstmt.setLong(2, observation.sourceEventId());
                    pstmt.setLong(3, observation.timestampMs());
                    pstmt.setLong(4, originNode);
                    pstmt.setLong(5, destinationNode);
                    pstmt.setLong(6, fingerprint);
                    pstmt.setString(7, observation.action().name());
                    pstmt.setInt(8, observation.item().amount());
                    pstmt.setBytes(9, rawData);
                    if (observation.itemEntityUuid() == null) {
                        pstmt.setNull(10, Types.VARCHAR);
                    } else {
                        pstmt.setString(10, observation.itemEntityUuid().toString());
                    }
                    if (observation.timestampEndMs() == null) {
                        pstmt.setNull(11, Types.INTEGER);
                    } else {
                        pstmt.setLong(11, observation.timestampEndMs());
                    }
                    int inserted = pstmt.executeUpdate();
                    conn.commit();
                    if (inserted == 0) {
                        return new SubmissionResult(SubmissionStatus.DUPLICATE, source.modId(),
                                observation.sourceEventId(), null, null);
                    }
                    return new SubmissionResult(SubmissionStatus.PERSISTED, source.modId(),
                            observation.sourceEventId(), null, null);
                }
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
        }
    }

    private void touchSource(Connection conn, String modId) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(
                "UPDATE ig_api_sources SET last_seen_ms = ? WHERE source_mod_id = ?")) {
            pstmt.setLong(1, System.currentTimeMillis());
            pstmt.setString(2, modId);
            pstmt.executeUpdate();
        }
    }

    private long endpointNode(Connection conn, EndpointRef endpoint) throws SQLException {
        if (endpoint instanceof PlayerEndpoint player) {
            return nodeManager.getOrCreatePlayerNode(conn, player.playerUuid().toString(),
                    player.displayName(), "minecraft:overworld", 0, 0, 0);
        }
        if (endpoint instanceof WorldEndpoint world) {
            String level = world.level().location().toString();
            double x = world.position().getX();
            double y = world.position().getY();
            double z = world.position().getZ();
            return switch (world.kind()) {
                case CONTAINER -> nodeManager.getOrCreateContainerNode(conn, level, x, y, z);
                case GROUND -> nodeManager.getOrCreateGroundNode(conn, level, x, y, z);
                case ARMOR_STAND -> nodeManager.getOrCreateArmorStandNode(conn, level, x, y, z);
                default -> throw new SQLException("unsupported world endpoint " + world.kind());
            };
        }
        if (endpoint instanceof ExternalInventoryEndpoint external) {
            String key = external.ownerModId() + "/" + external.inventoryId();
            String display = external.displayName() != null && !external.displayName().isBlank()
                    ? external.displayName() : key;
            WorldLocation location = external.lastKnownLocation();
            if (location == null) {
                return nodeManager.getOrCreateExternalInventoryNode(
                        conn, key, display, null, null, null, null);
            }
            return nodeManager.getOrCreateExternalInventoryNode(
                    conn, key, display, location.level().location().toString(),
                    (double) location.position().getX(),
                    (double) location.position().getY(),
                    (double) location.position().getZ());
        }
        if (endpoint instanceof UnknownEndpoint unknown) {
            return nodeManager.getOrCreateUnknownNode(conn, unknown.level().location().toString());
        }
        throw new SQLException("unsupported endpoint");
    }

    private long fingerprint(Connection conn, ItemSnapshot item) throws SQLException {
        ResourceLocation itemId = ResourceLocation.tryParse(item.itemId());
        String normalizedItemId = itemId.toString();
        Map<String, String> components = item.components() == null ? Map.of() : item.components();
        String effectiveCustomName = item.customName() != null
                ? item.customName()
                : components.get("minecraft:custom_name");
        String hash = com.itemgraph.canon.ItemCanonicalizer.sha256Hex(canonicalPayload(
                normalizedItemId, effectiveCustomName, components));

        try (PreparedStatement select = conn.prepareStatement(
                "SELECT id FROM ig_item_fingerprints WHERE fingerprint_hash = ? LIMIT 1")) {
            select.setString(1, hash);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }

        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO ig_item_fingerprints
                    (item_id, fingerprint_hash, custom_name, rarity, component_summary)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, normalizedItemId);
            insert.setString(2, hash);
            insert.setString(3, effectiveCustomName);
            insert.setString(4, components.get("minecraft:rarity"));
            insert.setString(5, componentSummary(components));
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("failed to persist item fingerprint");
    }

    private String canonicalPayload(String itemId, String customName,
                                    Map<String, String> components) {
        StringBuilder payload = new StringBuilder("id=").append(itemId);
        if (customName != null) {
            payload.append(";custom_name=").append(escapeCanonicalValue(customName));
        }
        appendComponent(payload, "enchantments", components.get("minecraft:enchantments"));
        appendComponent(payload, "damage", components.get("minecraft:damage"));
        appendComponent(payload, "trim", components.get("minecraft:trim"));
        appendComponent(payload, "lore", components.get("minecraft:lore"));
        List<String> extras = new ArrayList<>();
        for (Map.Entry<String, String> entry : components.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("minecraft:custom_name")
                    && !key.equals("minecraft:enchantments")
                    && !key.equals("minecraft:damage")
                    && !key.equals("minecraft:trim")
                    && !key.equals("minecraft:lore")
                    && !key.equals("minecraft:rarity")) {
                extras.add(key + "=" + escapeComponentValue(entry.getValue()));
            }
        }
        extras.sort(Comparator.naturalOrder());
        if (!extras.isEmpty()) {
            payload.append(";components=").append(String.join(",", extras));
        }
        return payload.toString();
    }

    private void appendComponent(StringBuilder payload, String key, String value) {
        if (value != null) {
            payload.append(';').append(key).append('=').append(escapeCanonicalValue(value));
        }
    }

    private String escapeCanonicalValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == ';' || c == '=') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private String escapeComponentValue(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == ';' || c == '=' || c == ',') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private String componentSummary(Map<String, String> components) {
        if (components.isEmpty()) {
            return null;
        }
        return components.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse(null);
    }

    private byte[] rawObservation(SourceHandle source, DirectObservation observation) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        field(json, "api_version", String.valueOf(ApiVersion.PREVIEW_1.number()), false);
        field(json, "source_mod_id", source.modId(), true);
        field(json, "source_event_id", String.valueOf(observation.sourceEventId()), false);
        field(json, "timestamp_ms", String.valueOf(observation.timestampMs()), false);
        if (observation.timestampEndMs() != null) {
            field(json, "timestamp_end_ms", String.valueOf(observation.timestampEndMs()), false);
        }
        field(json, "action", observation.action().name(), true);
        rawField(json, "origin", endpointJson(observation.origin()));
        rawField(json, "destination", endpointJson(observation.destination()));
        rawField(json, "item", itemJson(observation.item()));
        if (observation.itemEntityUuid() != null) {
            field(json, "item_entity_uuid", observation.itemEntityUuid().toString(), true);
        }
        if (observation.attributes() != null && !observation.attributes().isEmpty()) {
            rawField(json, "attributes", mapJson(observation.attributes()));
        }
        trimComma(json);
        json.append('}');
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String endpointJson(EndpointRef endpoint) {
        StringBuilder json = new StringBuilder(192).append('{');
        field(json, "kind", endpoint.kind().name(), true);
        if (endpoint instanceof PlayerEndpoint player) {
            field(json, "player_uuid", player.playerUuid().toString(), true);
            if (player.displayName() != null) {
                field(json, "display_name", player.displayName(), true);
            }
        } else if (endpoint instanceof WorldEndpoint world) {
            field(json, "level", world.level().location().toString(), true);
            field(json, "x", String.valueOf(world.position().getX()), false);
            field(json, "y", String.valueOf(world.position().getY()), false);
            field(json, "z", String.valueOf(world.position().getZ()), false);
            if (world.displayName() != null) {
                field(json, "display_name", world.displayName(), true);
            }
        } else if (endpoint instanceof ExternalInventoryEndpoint external) {
            field(json, "owner_mod_id", external.ownerModId(), true);
            field(json, "inventory_id", external.inventoryId(), true);
            if (external.displayName() != null) {
                field(json, "display_name", external.displayName(), true);
            }
            if (external.lastKnownLocation() != null) {
                rawField(json, "last_known_location", locationJson(external.lastKnownLocation()));
            }
        } else if (endpoint instanceof UnknownEndpoint unknown) {
            field(json, "level", unknown.level().location().toString(), true);
            if (unknown.reason() != null) {
                field(json, "reason", unknown.reason(), true);
            }
        }
        trimComma(json);
        return json.append('}').toString();
    }

    private String locationJson(WorldLocation location) {
        StringBuilder json = new StringBuilder(96).append('{');
        field(json, "level", location.level().location().toString(), true);
        field(json, "x", String.valueOf(location.position().getX()), false);
        field(json, "y", String.valueOf(location.position().getY()), false);
        field(json, "z", String.valueOf(location.position().getZ()), false);
        trimComma(json);
        return json.append('}').toString();
    }

    private String itemJson(ItemSnapshot item) {
        StringBuilder json = new StringBuilder(256).append('{');
        field(json, "item_id", item.itemId(), true);
        field(json, "amount", String.valueOf(item.amount()), false);
        if (item.customName() != null) {
            field(json, "custom_name", item.customName(), true);
        }
        if (item.components() != null && !item.components().isEmpty()) {
            rawField(json, "components", mapJson(item.components()));
        }
        trimComma(json);
        return json.append('}').toString();
    }

    private String mapJson(Map<String, String> map) {
        StringBuilder json = new StringBuilder(128).append('{');
        map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> field(json, entry.getKey(), entry.getValue(), true));
        trimComma(json);
        return json.append('}').toString();
    }

    private void field(StringBuilder json, String key, String value, boolean quoted) {
        json.append('"').append(escape(key)).append("\":");
        if (quoted) {
            json.append('"').append(escape(value)).append('"');
        } else {
            json.append(value);
        }
        json.append(',');
    }

    private void rawField(StringBuilder json, String key, String value) {
        json.append('"').append(escape(key)).append("\":").append(value).append(',');
    }

    private void trimComma(StringBuilder json) {
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
    }

    private String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws SQLException;
    }

    private <T> CompletableFuture<T> submit(CheckedSupplier<T> work, T shutdownResult, T queueResult) {
        if (!accepting) {
            return CompletableFuture.completedFuture(shutdownResult);
        }
        Pending<T> task = new Pending<>(work, shutdownResult);
        pending.add(task);
        ExecutorService executor = submissionExecutor;
        try {
            if (executor == null) {
                throw new RejectedExecutionException("API worker is not running");
            }
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            pending.remove(task);
            return CompletableFuture.completedFuture(queueResult);
        }
        return task.future;
    }

    private CompletableFuture<SubmissionResult> completedSubmission(
            SubmissionStatus status, String sourceModId, long sourceEventId,
            String errorCode, String message) {
        return CompletableFuture.completedFuture(new SubmissionResult(
                status, sourceModId, sourceEventId, errorCode, message));
    }

    private CompletableFuture<QueryResult> completedQuery(
            QueryStatus status, String errorCode, String message) {
        return CompletableFuture.completedFuture(
                new QueryResult(status, null, errorCode, message));
    }

    private final class Pending<T> implements Runnable {
        private final CheckedSupplier<T> work;
        private final T shutdownResult;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private Pending(CheckedSupplier<T> work, T shutdownResult) {
            this.work = work;
            this.shutdownResult = shutdownResult;
        }

        @Override
        public void run() {
            try {
                if (!accepting) {
                    future.complete(shutdownResult);
                    return;
                }
                future.complete(work.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            } finally {
                pending.remove(this);
            }
        }

        private void completeShutdown() {
            future.complete(shutdownResult);
        }
    }
}
