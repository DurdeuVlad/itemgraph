package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exports only the durable item-movement rows from the shared loader replay fixture. */
public final class ItemGraphReplayReportFixture {
    private static final String SCENARIO_ID = "item-movement-projectile-replay";
    private static final Set<String> EXPECTED_ACTIONS = Set.of(
            "ADD_ITEM", "REMOVE_ITEM", "DROP_ITEM", "PICKUP_ITEM", "THROW_ITEM", "SHOOT_ITEM");
    private static final Map<String, ExpectedEvent> EXPECTED_EVENTS = Map.of(
            "ADD_ITEM", new ExpectedEvent("minecraft:dirt", 2),
            "REMOVE_ITEM", new ExpectedEvent("minecraft:cobblestone", 3),
            "DROP_ITEM", new ExpectedEvent("minecraft:diamond", 4),
            "PICKUP_ITEM", new ExpectedEvent("minecraft:diamond", 4),
            "THROW_ITEM", new ExpectedEvent("minecraft:snowball", 1),
            "SHOOT_ITEM", new ExpectedEvent("minecraft:arrow", 1));

    private ItemGraphReplayReportFixture() { }

    /**
     * Writes a redacted raw report only when CI supplies an output directory.
     * The query is read-only and the caller invokes this after every durable
     * GameTest assertion has passed.
     */
    public static void writeIfRequested(GameTestHelper helper, String loader, long priorObservationId,
                                        String movementPlayerUuid, String projectilePlayerUuid) {
        String configuredDirectory = System.getenv("ITEMGRAPH_DIFFERENTIAL_REPORT_DIR");
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            return;
        }
        if (!Set.of("fabric", "neoforge").contains(loader)) {
            throw new IllegalArgumentException("unsupported replay report loader: " + loader);
        }

        List<ReplayEvent> events = readEvents(priorObservationId, movementPlayerUuid, projectilePlayerUuid);
        helper.assertValueEqual(EXPECTED_ACTIONS, events.stream().map(ReplayEvent::action).collect(Collectors.toSet()),
                "native report must contain the six expected durable replay observations");
        helper.assertValueEqual(EXPECTED_EVENTS.size(), events.size(),
                "native report must not omit or duplicate a replay observation");

        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // readEvents orders by persisted timestamp and row ID. Keep that durable
        // order when two observations share one millisecond timestamp.
        StringBuilder json = new StringBuilder(2048);
        json.append("{\n  \"raw_schema_version\": 1,\n  \"loader\": ").append(quote(loader))
                .append(",\n  \"scenario_id\": ").append(quote(SCENARIO_ID))
                .append(",\n  \"seed\": 0,\n  \"events\": [\n");
        for (int i = 0; i < events.size(); i++) {
            ReplayEvent event = events.get(i);
            ExpectedEvent expected = EXPECTED_EVENTS.get(event.action());
            helper.assertValueEqual(expected, new ExpectedEvent(event.itemId(), event.amount()),
                    "native report event differs from the durable replay expectation for " + event.action());
            if (i > 0) {
                json.append(",\n");
            }
            int x = event.x() - origin.getX();
            int y = event.y() - origin.getY();
            int z = event.z() - origin.getZ();
            json.append("    {\"event_key\": ").append(quote("replay-" + event.action().toLowerCase(Locale.ROOT)))
                    .append(", \"sequence\": ").append(i)
                    .append(", \"action\": ").append(quote(event.action()))
                    .append(", \"evidence_class\": \"observed\"")
                    .append(", \"quantity\": ").append(event.amount())
                    .append(", \"item_id\": ").append(quote(event.itemId()))
                    .append(", \"occurred_at_ms\": ").append(event.occurredAtMs())
                    .append(", \"dimension\": ").append(quote(event.dimension()))
                    .append(", \"position\": {\"x\": ").append(x).append(", \"y\": ").append(y)
                    .append(", \"z\": ").append(z).append("}")
                    .append(", \"actor_ref\": ").append(quote(event.actorRef()))
                    .append(", \"source_table\": \"ig_observations\"")
                    .append(", \"source_action_id\": ").append(quote(event.action()))
                    .append(", \"compatibility_table\": ").append(quote(event.compatibilityTable()))
                    .append(", \"privacy_class\": \"replay_fixture_only\"")
                    .append(", \"unresolved_reason\": null}");
        }
        json.append("\n  ]\n}\n");
        writeAtomically(Path.of(configuredDirectory).resolve("itemgraph-" + loader + ".raw.json"), json.toString());
    }

    private static List<ReplayEvent> readEvents(long priorObservationId, String movementPlayerUuid,
                                                String projectilePlayerUuid) {
        String sql = """
                SELECT o.timestamp_ms, o.action_type, o.amount, fingerprint.item_id,
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
                  AND (source.owner_uuid IN (?, ?) OR target.owner_uuid IN (?, ?))
                ORDER BY o.timestamp_ms, o.id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, priorObservationId);
            statement.setString(2, movementPlayerUuid);
            statement.setString(3, projectilePlayerUuid);
            statement.setString(4, movementPlayerUuid);
            statement.setString(5, projectilePlayerUuid);
            try (var rows = statement.executeQuery()) {
                List<ReplayEvent> result = new ArrayList<>();
                while (rows.next()) {
                    String action = rows.getString("action_type");
                    String sourceOwner = rows.getString("source_owner");
                    String targetOwner = rows.getString("target_owner");
                    String actorUuid = sourceOwner != null ? sourceOwner : targetOwner;
                    String actorRef = movementPlayerUuid.equals(actorUuid) ? "actor:replay-mover"
                            : projectilePlayerUuid.equals(actorUuid) ? "actor:replay-shooter" : null;
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
                    result.add(new ReplayEvent(rows.getLong("timestamp_ms"), action, rows.getInt("amount"),
                            rows.getString("item_id"), dimension, (int) Math.floor(xValue.doubleValue()),
                            (int) Math.floor(yValue.doubleValue()), (int) Math.floor(zValue.doubleValue()), actorRef,
                            containerBound ? "containers" : "items"));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read native replay report evidence", failure);
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

    private record ReplayEvent(long occurredAtMs, String action, int amount, String itemId, String dimension,
                               int x, int y, int z, String actorRef, String compatibilityTable) { }
}
