package com.itemgraph.gametest;

import com.itemgraph.audit.AuditReport;
import com.itemgraph.audit.AuditService;
import com.itemgraph.db.DatabaseDialect;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exports the durable movement and allowlisted audit rows from the shared loader replay fixture. */
public final class ItemGraphReplayReportFixture {
    private static final String SCENARIO_ID = "item-movement-projectile-block-entity-audit-replay";
    private static final Set<String> EXPECTED_OBSERVATION_ACTIONS = Set.of(
            "ADD_ITEM", "REMOVE_ITEM", "DROP_ITEM", "PICKUP_ITEM", "THROW_ITEM", "SHOOT_ITEM");
    private static final Map<String, ExpectedEvent> EXPECTED_OBSERVATION_EVENTS = Map.of(
            "ADD_ITEM", new ExpectedEvent("minecraft:dirt", 2),
            "REMOVE_ITEM", new ExpectedEvent("minecraft:cobblestone", 3),
            "DROP_ITEM", new ExpectedEvent("minecraft:diamond", 4),
            "PICKUP_ITEM", new ExpectedEvent("minecraft:diamond", 4),
            "THROW_ITEM", new ExpectedEvent("minecraft:snowball", 1),
            "SHOOT_ITEM", new ExpectedEvent("minecraft:arrow", 1));
    private static final Map<String, Integer> EXPECTED_AUDIT_ACTION_COUNTS = Map.of(
            "BREAK_BLOCK", 1,
            "PLACE_BLOCK", 1,
            "INTERACT_BLOCK_ATTEMPT", 1,
            "KILL_ENTITY", 1,
            "INTERACT_ENTITY", 3,
            "INTERACT_ENTITY_COMPLETED", 2,
            "INTERACT_ENTITY_UNRESOLVED", 1);
    private static final Map<String, Integer> EXPECTED_AUDIT_SUBJECT_COUNTS = Map.of(
            "minecraft:water", 1,
            "minecraft:diamond_block", 1,
            "minecraft:chest", 1,
            "minecraft:cow", 2,
            "minecraft:armor_stand", 5);
    private static final Map<ExpectedActionSubject, Integer> EXPECTED_AUDIT_ACTION_SUBJECT_COUNTS = Map.of(
            new ExpectedActionSubject("BREAK_BLOCK", "minecraft:water"), 1,
            new ExpectedActionSubject("PLACE_BLOCK", "minecraft:diamond_block"), 1,
            new ExpectedActionSubject("INTERACT_BLOCK_ATTEMPT", "minecraft:chest"), 1,
            new ExpectedActionSubject("KILL_ENTITY", "minecraft:cow"), 1,
            new ExpectedActionSubject("INTERACT_ENTITY", "minecraft:cow"), 1,
            new ExpectedActionSubject("INTERACT_ENTITY", "minecraft:armor_stand"), 2,
            new ExpectedActionSubject("INTERACT_ENTITY_COMPLETED", "minecraft:armor_stand"), 2,
            new ExpectedActionSubject("INTERACT_ENTITY_UNRESOLVED", "minecraft:armor_stand"), 1);

    private ItemGraphReplayReportFixture() { }

    /**
     * Writes a redacted raw report only when CI supplies an output directory.
     * The query is read-only and the caller invokes this after every durable
     * GameTest assertion has passed.
     */
    public static void writeIfRequested(GameTestHelper helper, String loader, long priorObservationId,
                                        long priorAuditId, Map<String, String> actorAliases,
                                        BlockPos successfulWaterPickupPos,
                                        Map<String, BlockPos> expectedAuditPositions) {
        String configuredDirectory = System.getenv("ITEMGRAPH_DIFFERENTIAL_REPORT_DIR");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        if (!Set.of("fabric", "neoforge").contains(loader)) {
            throw new IllegalArgumentException("unsupported replay report loader: " + loader);
        }

        List<ReplayEvent> observationEvents = readObservationEvents(priorObservationId, actorAliases);
        helper.assertValueEqual(EXPECTED_OBSERVATION_ACTIONS,
                observationEvents.stream().map(ReplayEvent::action).collect(Collectors.toSet()),
                "native report must contain the six expected durable replay observations");
        helper.assertValueEqual(EXPECTED_OBSERVATION_EVENTS.size(), observationEvents.size(),
                "native report must not omit or duplicate a replay observation");
        List<ReplayEvent> auditEvents = readAuditEvents(priorAuditId, actorAliases, successfulWaterPickupPos);
        Map<String, Integer> actualAuditActionCounts = auditEvents.stream().collect(Collectors.groupingBy(
                ReplayEvent::action, Collectors.summingInt(ignored -> 1)));
        helper.assertValueEqual(EXPECTED_AUDIT_ACTION_COUNTS, actualAuditActionCounts,
                "native report must include every expected durable audit event exactly once");
        Map<String, Integer> actualAuditSubjectCounts = auditEvents.stream().collect(Collectors.groupingBy(
                ReplayEvent::subjectId, Collectors.summingInt(ignored -> 1)));
        helper.assertValueEqual(EXPECTED_AUDIT_SUBJECT_COUNTS, actualAuditSubjectCounts,
                "native report must retain each expected namespaced block or entity subject exactly");
        Map<ExpectedActionSubject, Integer> actualActionSubjectCounts = auditEvents.stream().collect(
                Collectors.groupingBy(event -> new ExpectedActionSubject(event.action(), event.subjectId()),
                        Collectors.summingInt(ignored -> 1)));
        helper.assertValueEqual(EXPECTED_AUDIT_ACTION_SUBJECT_COUNTS, actualActionSubjectCounts,
                "native report must retain every audit action with its expected namespaced subject");
        List<ReplayEvent> events = new ArrayList<>(observationEvents.size() + auditEvents.size());
        events.addAll(observationEvents);
        events.addAll(auditEvents);
        events.sort(Comparator.comparingLong(ReplayEvent::occurredAtMs)
                .thenComparing(ReplayEvent::sourceTable)
                .thenComparingLong(ReplayEvent::rowId));
        AuditReport audit = readWholeGraphAudit();
        helper.assertValueEqual(true, audit.healthy(),
                "native report requires a healthy whole-database quantity and integrity audit");
        InternalObservationService ingestion = InternalObservationService.getInstance();
        long droppedSinceServiceStart = ingestion.getTotalDropped();

        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        for (ReplayEvent event : auditEvents) {
            BlockPos expectedPosition = expectedAuditPositions.get(event.action());
            if (expectedPosition != null) {
                helper.assertValueEqual(new BlockPos(expectedPosition.getX() - origin.getX(),
                                expectedPosition.getY() - origin.getY(), expectedPosition.getZ() - origin.getZ()),
                        new BlockPos(event.x() - origin.getX(), event.y() - origin.getY(), event.z() - origin.getZ()),
                        "native audit event position differs from its expected replay target: " + event.action());
            }
        }
        // Keep persisted row order when events from one source share a millisecond timestamp.
        StringBuilder json = new StringBuilder(2048);
        json.append("{\n  \"raw_schema_version\": 5,\n  \"loader\": ").append(quote(loader))
                .append(",\n  \"scenario_id\": ").append(quote(SCENARIO_ID))
                .append(",\n  \"seed\": 0,\n  \"events\": [\n");
        Map<String, Integer> actionOccurrences = new HashMap<>();
        for (int i = 0; i < events.size(); i++) {
            ReplayEvent event = events.get(i);
            if ("ig_observations".equals(event.sourceTable())) {
                ExpectedEvent expected = EXPECTED_OBSERVATION_EVENTS.get(event.action());
                helper.assertValueEqual(expected, new ExpectedEvent(event.itemId(), event.amount()),
                        "native report event differs from the durable replay expectation for " + event.action());
            }
            int occurrence = actionOccurrences.getOrDefault(event.action(), 0);
            actionOccurrences.put(event.action(), occurrence + 1);
            if (i > 0) {
                json.append(",\n");
            }
            int x = event.x() - origin.getX();
            int y = event.y() - origin.getY();
            int z = event.z() - origin.getZ();
            json.append("    {\"event_key\": ").append(quote("replay-" + event.action().toLowerCase(Locale.ROOT)
                            + "-" + occurrence))
                    .append(", \"sequence\": ").append(i)
                    .append(", \"action\": ").append(quote(event.action()))
                    .append(", \"evidence_class\": ").append(quote(event.evidenceClass()))
                    .append(", \"quantity\": ").append(event.amount() == null ? "null" : event.amount())
                    .append(", \"item_id\": ").append(event.itemId() == null ? "null" : quote(event.itemId()))
                    .append(", \"occurred_at_ms\": ").append(event.occurredAtMs())
                    .append(", \"dimension\": ").append(quote(event.dimension()))
                    .append(", \"position\": {\"x\": ").append(x).append(", \"y\": ").append(y)
                    .append(", \"z\": ").append(z).append("}")
                    .append(", \"subject_id\": ")
                    .append(event.subjectId() == null ? "null" : quote(event.subjectId()))
                    .append(", \"actor_ref\": ").append(quote(event.actorRef()))
                    .append(", \"source_table\": ").append(quote(event.sourceTable()))
                    .append(", \"source_action_id\": ").append(quote(event.sourceActionId()))
                    .append(", \"compatibility_table\": ").append(quote(event.compatibilityTable()))
                    .append(", \"privacy_class\": \"replay_fixture_only\"")
                    .append(", \"unresolved_reason\": ")
                    .append(event.unresolvedReason() == null ? "null" : quote(event.unresolvedReason()))
                    .append("}");
        }
        json.append("\n  ],\n  \"invariants\": ").append(auditJson(audit, ingestion, droppedSinceServiceStart))
                .append("\n}\n");
        writeAtomically(Path.of(configuredDirectory).resolve("itemgraph-" + loader + ".raw.json"), json.toString());
    }

    private static AuditReport readWholeGraphAudit() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection()) {
            // Keep every count on the same read-only database snapshot while the
            // asynchronous ingestion worker may be persisting other GameTests.
            if (DatabaseManager.getInstance().getDialect() == DatabaseDialect.MYSQL_MARIADB) {
                connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            }
            connection.setAutoCommit(false);
            try {
                return new AuditService().audit(connection);
            } finally {
                connection.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not audit whole-graph replay invariants", failure);
        }
    }

    private static String auditJson(AuditReport report, InternalObservationService ingestion,
                                    long droppedSinceServiceStart) {
        return "{\"healthy\":" + report.healthy()
                + ",\"total_observations\":" + report.totalObservations()
                + ",\"total_edges\":" + report.totalEdges()
                + ",\"total_allocations\":" + report.totalAllocations()
                + ",\"total_transformations\":" + report.totalTransformations()
                + ",\"over_allocated_observations\":" + report.overAllocatedObservations()
                + ",\"invalid_edge_allocations\":" + report.invalidEdgeAllocations()
                + ",\"invalid_edge_temporal\":" + report.invalidEdgeTemporal()
                + ",\"non_positive_quantities\":" + report.nonPositiveQuantities()
                + ",\"orphaned_allocations\":" + report.orphanedAllocations()
                + ",\"invalid_edge_nodes\":" + report.invalidEdgeNodes()
                + ",\"status_mismatches\":" + report.statusMismatches()
                + ",\"queue_health\":{\"observation_waiting_depth\":" + ingestion.getObservationQueueSize()
                + ",\"transformation_waiting_depth\":" + ingestion.getTransformationQueueSize()
                + ",\"audit_waiting_depth\":" + ingestion.getAuditEventQueueSize()
                + ",\"queue_capacity_each\":" + ingestion.getQueueCapacity()
                + ",\"dropped_since_service_start\":" + droppedSinceServiceStart + "}}";
    }

    private static List<ReplayEvent> readObservationEvents(long priorObservationId,
                                                           Map<String, String> actorAliases) {
        String sql = """
                SELECT o.id, o.timestamp_ms, o.action_type, o.amount, fingerprint.item_id,
                       source.node_type AS source_type, source.owner_uuid AS source_owner,
                       source.level_id AS source_dimension, source.x AS source_x,
                       source.y AS source_y, source.z AS source_z,
                       target.node_type AS target_type, target.owner_uuid AS target_owner,
                       target.level_id AS target_dimension, target.x AS target_x,
                       target.y AS target_y, target.z AS target_z
                FROM ig_observations o
                JOIN ig_item_fingerprints fingerprint ON fingerprint.id = o.fingerprint_id
                JOIN ig_nodes source ON source.id = o.node_id
                JOIN ig_nodes target ON target.id = o.target_node_id
                WHERE o.id > ? AND o.source_type = 'ITEMGRAPH_INTERNAL'
                  AND o.action_type IN ('ADD_ITEM', 'REMOVE_ITEM', 'DROP_ITEM', 'PICKUP_ITEM', 'THROW_ITEM', 'SHOOT_ITEM')
                  AND (source.owner_uuid IN (%s) OR target.owner_uuid IN (%s))
                ORDER BY o.timestamp_ms, o.id
                """.formatted(String.join(",", java.util.Collections.nCopies(actorAliases.size(), "?")),
                String.join(",", java.util.Collections.nCopies(actorAliases.size(), "?")));
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, priorObservationId);
            int parameter = 2;
            for (String uuid : actorAliases.keySet()) statement.setString(parameter++, uuid);
            for (String uuid : actorAliases.keySet()) statement.setString(parameter++, uuid);
            try (var rows = statement.executeQuery()) {
                List<ReplayEvent> result = new ArrayList<>();
                while (rows.next()) {
                    String action = rows.getString("action_type");
                    String sourceOwner = rows.getString("source_owner");
                    String targetOwner = rows.getString("target_owner");
                    String actorUuid = sourceOwner != null ? sourceOwner : targetOwner;
                    String actorRef = actorAliases.get(actorUuid);
                    if (actorRef == null) {
                        throw new IllegalStateException("replay observation has no expected player actor: " + action);
                    }
                    boolean containerBound = "CONTAINER".equals(rows.getString("source_type"))
                            || "CONTAINER".equals(rows.getString("target_type"));
                    boolean useTarget = "ADD_ITEM".equals(action) || "DROP_ITEM".equals(action);
                    String dimension = rows.getString(useTarget ? "target_dimension" : "source_dimension");
                    Number xValue = (Number) rows.getObject(useTarget ? "target_x" : "source_x");
                    Number yValue = (Number) rows.getObject(useTarget ? "target_y" : "source_y");
                    Number zValue = (Number) rows.getObject(useTarget ? "target_z" : "source_z");
                    if (dimension == null || xValue == null || yValue == null || zValue == null) {
                        throw new IllegalStateException("replay observation has no dimension or position: " + action);
                    }
                    result.add(new ReplayEvent(rows.getLong("id"), rows.getLong("timestamp_ms"), action,
                            "observed", rows.getInt("amount"), rows.getString("item_id"), null, dimension,
                            (int) Math.floor(xValue.doubleValue()), (int) Math.floor(yValue.doubleValue()),
                            (int) Math.floor(zValue.doubleValue()), actorRef, "ig_observations", action,
                            containerBound ? "containers" : "items", null));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read native replay report evidence", failure);
        }
    }

    private static List<ReplayEvent> readAuditEvents(long priorAuditId, Map<String, String> actorAliases,
                                                    BlockPos successfulWaterPickupPos) {
        String sql = """
                SELECT id, event_type, timestamp_ms, player_uuid, level_id, x, y, z, subject_id
                FROM ig_audit_events
                WHERE id > ? AND source_type = 'ITEMGRAPH_INTERNAL'
                  AND player_uuid IN (%s)
                  AND event_type IN ('BREAK_BLOCK', 'PLACE_BLOCK', 'INTERACT_BLOCK_ATTEMPT', 'KILL_ENTITY',
                                     'INTERACT_ENTITY', 'INTERACT_ENTITY_COMPLETED',
                                     'INTERACT_ENTITY_UNRESOLVED')
                  -- Exclude the direct-call guard probe with a synthetic lava-bucket result.
                  AND (event_type <> 'BREAK_BLOCK' OR
                       (subject_id = 'minecraft:water' AND x = ? AND y = ? AND z = ?))
                ORDER BY timestamp_ms, id
                """.formatted(String.join(",", java.util.Collections.nCopies(actorAliases.size(), "?")));
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, priorAuditId);
            int parameter = 2;
            for (String uuid : actorAliases.keySet()) statement.setString(parameter++, uuid);
            statement.setInt(parameter++, successfulWaterPickupPos.getX());
            statement.setInt(parameter++, successfulWaterPickupPos.getY());
            statement.setInt(parameter, successfulWaterPickupPos.getZ());
            try (var rows = statement.executeQuery()) {
                List<ReplayEvent> result = new ArrayList<>();
                while (rows.next()) {
                    String action = rows.getString("event_type");
                    String actorRef = actorAliases.get(rows.getString("player_uuid"));
                    String dimension = rows.getString("level_id");
                    Number xValue = (Number) rows.getObject("x");
                    Number yValue = (Number) rows.getObject("y");
                    Number zValue = (Number) rows.getObject("z");
                    if (actorRef == null || dimension == null || xValue == null || yValue == null || zValue == null) {
                        throw new IllegalStateException("replay audit event lacks a mapped actor or location: " + action);
                    }
                    String unresolvedReason = "INTERACT_ENTITY_UNRESOLVED".equals(action)
                            ? "ENTITY_INTERACTION_METHOD_PASSED" : null;
                    result.add(new ReplayEvent(rows.getLong("id"), rows.getLong("timestamp_ms"), action,
                            "observed", null, null, rows.getString("subject_id"), dimension,
                            (int) Math.floor(xValue.doubleValue()), (int) Math.floor(yValue.doubleValue()),
                            (int) Math.floor(zValue.doubleValue()), actorRef, "ig_audit_events", action,
                            "blocks", unresolvedReason));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read native replay audit report evidence", failure);
        }
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void writeAtomically(Path destination, String report) {
        Path directory = destination.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".itemgraph-replay-", ".tmp");
            Files.writeString(temporary, report, StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not atomically write the native replay report", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A failed report write already fails the GameTest; cleanup is best effort.
                }
            }
        }
    }

    private record ExpectedEvent(String itemId, int amount) { }

    private record ExpectedActionSubject(String action, String subjectId) { }

    private record ReplayEvent(long rowId, long occurredAtMs, String action, String evidenceClass, Integer amount,
                               String itemId, String subjectId, String dimension, int x, int y, int z,
                               String actorRef, String sourceTable, String sourceActionId,
                               String compatibilityTable, String unresolvedReason) { }
}
