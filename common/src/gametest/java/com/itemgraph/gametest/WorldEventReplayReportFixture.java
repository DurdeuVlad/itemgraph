package com.itemgraph.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.itemgraph.audit.AuditReport;
import com.itemgraph.audit.AuditService;
import com.itemgraph.audit.EventTaxonomy;
import com.itemgraph.db.DatabaseDialect;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.query.AuditEventQueryService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Emits a redacted, cross-loader report for persisted world-event evidence. */
public final class WorldEventReplayReportFixture {
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Map<String, Set<String>> SCENARIO_TYPES = Map.of(
            "explosion", Set.of("EXPLOSION_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"),
            "piston", Set.of("PISTON_BLOCK_MOVE", "PISTON_BLOCK_ATTEMPT", "WORLD_EFFECT_UNRESOLVED"),
            "environment", Set.of("FLUID_BLOCK_CHANGE", "FIRE_BLOCK_CHANGE", "ENDERMAN_BLOCK_MOVE",
                    "FALLING_BLOCK_CHANGE", "WORLD_EFFECT_UNRESOLVED"));
    private static final Map<String, String> CAUSE_ALIASES = Map.ofEntries(
            Map.entry("EXPLOSION_DESTROY", "explosion_destroy"),
            Map.entry("EXPLOSION_KEEP", "explosion_keep"),
            Map.entry("PISTON_EXTEND", "piston_extend"),
            Map.entry("FLUID", "fluid"),
            Map.entry("FlowingFluid.spreadTo.return", "fluid_spread"),
            Map.entry("FlowingFluid.spreadTo.throw", "fluid_exception"),
            Map.entry("FireBlock.checkBurnOut.removeBlock", "fire_burnout_remove"),
            Map.entry("FireBlock.checkBurnOut.setBlock", "fire_burnout_set"),
            Map.entry("FireBlock.tick.setBlock", "fire_tick_set"),
            Map.entry("EndermanTakeBlockGoal.tick.removeBlock", "enderman_take"),
            Map.entry("EndermanLeaveBlockGoal.tick.setBlock", "enderman_place"),
            Map.entry("FALLING_BLOCK", "falling_block"),
            Map.entry("FallingBlockEntity.fall.return", "falling_spawn"),
            Map.entry("FallingBlockEntity.fall.throw", "falling_exception"),
            Map.entry("FallingBlockEntity.tick.setBlock", "falling_land"));

    private WorldEventReplayReportFixture() { }

    public static long watermark() {
        if (!reportOutputEnabled()) {
            return 0L;
        }
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not establish a world-event replay watermark", failure);
        }
    }

    public static boolean writeIfRequested(GameTestHelper helper, String loader, String scenario,
                                           long watermark, long cutoffWatermark) {
        if (!Set.of("fabric", "neoforge").contains(loader)) {
            throw new IllegalArgumentException("unsupported world-event report loader: " + loader);
        }
        Set<String> allowedTypes = SCENARIO_TYPES.get(scenario);
        if (allowedTypes == null) {
            throw new IllegalArgumentException("unsupported world-event report scenario: " + scenario);
        }

        String configuredDirectory = System.getenv("ITEMGRAPH_WORLD_EVENT_REPORT_DIR");
        if (!reportOutputEnabled()) {
            return false;
        }
        Path destination = Path.of(configuredDirectory).resolve(
                "itemgraph-world-" + loader + "-" + scenario + ".json");
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        CompletableFuture<JsonObject> report = CompletableFuture.supplyAsync(
                () -> createReport(loader, scenario, watermark, cutoffWatermark, allowedTypes, origin));
        helper.startSequence()
                .thenWaitUntil(report::isDone)
                .thenExecute(() -> {
                    try {
                        writeAtomically(destination, GSON.toJson(report.join()) + "\n");
                    } catch (CompletionException | IllegalStateException failure) {
                        helper.fail("Could not complete the world-event replay report: " + failure.getMessage());
                        return;
                    }
                    helper.succeed();
                });
        return true;
    }

    private static boolean reportOutputEnabled() {
        String configuredDirectory = System.getenv("ITEMGRAPH_WORLD_EVENT_REPORT_DIR");
        return configuredDirectory != null && !configuredDirectory.isBlank();
    }

    private static JsonObject createReport(String loader, String scenario, long watermark, long cutoffWatermark,
                                           Set<String> allowedTypes, BlockPos origin) {
        AuditReport audit = auditWholeGraph();
        JsonObject report = new JsonObject();
        report.addProperty("schema_version", 1);
        report.addProperty("loader", loader);
        report.addProperty("scenario", scenario);
        report.add("invariants", invariants(audit));
        report.add("events", readEvents(watermark, cutoffWatermark, allowedTypes, origin));
        return report;
    }

    private static AuditReport auditWholeGraph() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection()) {
            if (DatabaseManager.getInstance().getDialect() == DatabaseDialect.MYSQL_MARIADB) {
                connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            }
            connection.setAutoCommit(false);
            AuditReport report;
            try {
                report = new AuditService().audit(connection);
            } finally {
                connection.rollback();
            }
            require(report.healthy(), "world-event replay must preserve whole-graph quantity and integrity invariants: "
                    + report.violationDetails());
            return report;
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not audit ItemGraph after world-event replay", failure);
        }
    }

    private static JsonObject invariants(AuditReport report) {
        JsonObject result = new JsonObject();
        result.addProperty("healthy", report.healthy());
        result.addProperty("over_allocated_observations", report.overAllocatedObservations());
        result.addProperty("invalid_edge_allocations", report.invalidEdgeAllocations());
        result.addProperty("invalid_edge_temporal", report.invalidEdgeTemporal());
        result.addProperty("non_positive_quantities", report.nonPositiveQuantities());
        result.addProperty("orphaned_allocations", report.orphanedAllocations());
        result.addProperty("invalid_edge_nodes", report.invalidEdgeNodes());
        result.addProperty("status_mismatches", report.statusMismatches());
        return result;
    }

    private static JsonArray readEvents(long watermark, long cutoffWatermark, Set<String> allowedTypes,
                                        BlockPos origin) {
        JsonArray events = new JsonArray();
        Map<String, String> causes = new HashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(allowedTypes.size(), "?"));
        String sql = """
                SELECT id, event_type, player_uuid, level_id, x, y, z, subject_id, detail
                FROM ig_audit_events
                WHERE id > ? AND id <= ? AND event_type IN (%s)
                ORDER BY id
                """.formatted(placeholders);
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, watermark);
            statement.setLong(2, cutoffWatermark);
            int parameter = 3;
            for (String eventType : allowedTypes.stream().sorted().toList()) {
                statement.setString(parameter++, eventType);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String eventType = rows.getString("event_type");
                    EventTaxonomy.Definition definition = EventTaxonomy.find(
                                    eventType, EventTaxonomy.Surface.AUDIT_EVENT)
                            .orElseThrow(() -> new IllegalStateException("world-event row is absent from taxonomy: " + eventType));
                    require(allowedTypes.contains(eventType),
                            "world-event replay crossed scenario boundaries with " + eventType);
                    require(AuditEventQueryService.EVENT_TYPES.contains(eventType),
                            "world-event type is not selectable through audit lookup: " + eventType);
                    require(rows.getString("player_uuid") == null,
                            "world-event replay must not attribute a player actor");
                    require(EventTaxonomy.QuantitySemantics.NONE == definition.quantity(),
                            "world-event taxonomy must not account item quantity");
                    JsonObject payload = JsonParser.parseString(rows.getString("detail")).getAsJsonObject();
                    require("NONE".equals(payload.get("quantity_semantics").getAsString()),
                            "durable world-event payload must not claim item quantity");
                    require(definition.evidenceClass().name().equals(payload.get("evidence_class").getAsString()),
                            "durable evidence class must match the event taxonomy");
                    require(definition.sourceReliability().name().equals(payload.get("source_reliability").getAsString()),
                            "durable reliability must match the event taxonomy");
                    require("SENSITIVE_LOCATION".equals(definition.privacy().name()),
                            "world-event locations must remain permission-restricted");

                    JsonObject event = new JsonObject();
                    event.addProperty("sequence", events.size());
                    event.addProperty("event_type", eventType);
                    event.addProperty("evidence_class", payload.get("evidence_class").getAsString());
                    event.addProperty("source_reliability", payload.get("source_reliability").getAsString());
                    event.addProperty("quantity_semantics", payload.get("quantity_semantics").getAsString());
                    event.addProperty("outcome", payload.get("outcome").getAsString());
                    addOptional(event, "reason_code", payload.get("reason_code"));
                    String causeAlias = CAUSE_ALIASES.get(payload.get("cause").getAsString());
                    require(causeAlias != null, "world-event replay cause has no redacted alias");
                    event.addProperty("cause", causeAlias);
                    event.addProperty("privacy_class", definition.privacy().name());
                    event.addProperty("dimension", rows.getString("level_id"));
                    JsonObject position = new JsonObject();
                    position.addProperty("x", Math.round(rows.getDouble("x")) - origin.getX());
                    position.addProperty("y", Math.round(rows.getDouble("y")) - origin.getY());
                    position.addProperty("z", Math.round(rows.getDouble("z")) - origin.getZ());
                    event.add("position", position);

                    JsonObject endpoint = payload.getAsJsonObject("affected_endpoint");
                    addOptional(event, "subject_id", endpoint.get("subject_id"));
                    addOptional(event, "before_state", payload.get("before_state"));
                    addOptional(event, "after_state", payload.get("after_state"));
                    JsonElement causeId = payload.has("metadata")
                            ? payload.getAsJsonObject("metadata").get("cause_event_id") : null;
                    if (causeId != null && causeId.isJsonPrimitive()) {
                        String rawCause = causeId.getAsString();
                        event.addProperty("cause_ref", causes.computeIfAbsent(rawCause,
                                ignored -> "cause-" + causes.size()));
                    } else {
                        event.add("cause_ref", JsonNull.INSTANCE);
                    }
                    events.add(event);
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read persisted world-event replay evidence", failure);
        }
        require(!events.isEmpty(), "world-event replay report must contain durable events");
        return events;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void addOptional(JsonObject target, String key, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            target.add(key, JsonNull.INSTANCE);
        } else {
            target.add(key, value.deepCopy());
        }
    }

    private static void writeAtomically(Path destination, String report) {
        Path directory = destination.toAbsolutePath().getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".itemgraph-world-replay-", ".tmp");
            Files.writeString(temporary, report, StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not atomically write the world-event replay report", failure);
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
}
