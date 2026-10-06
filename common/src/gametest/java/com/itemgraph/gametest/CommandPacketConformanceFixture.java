package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Shared durable expectations for command packets executed by both loader servers. */
public final class CommandPacketConformanceFixture {
    private static final long COMMAND_PERSISTENCE_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long COMMAND_POLL_INTERVAL_MILLIS = 50;
    private static final List<String> EXPECTED_COMMANDS = List.of(
            "itemgraph inspect on",
            "ig inspect status",
            "ig inspect off");

    private record Baseline(long auditWatermark, List<List<String>> playerObservations) { }
    private record CommandRow(long id, String eventType, String playerName, String detail, long timestampMs) { }

    private CommandPacketConformanceFixture() { }

    public static void run(GameTestHelper helper, ServerPlayer player, Consumer<Runnable> dispatchCommands) {
        var server = helper.getLevel().getServer();
        String playerUuid = player.getUUID().toString();
        String playerName = player.getGameProfile().getName();
        AtomicBoolean finished = new AtomicBoolean();
        CompletableFuture.supplyAsync(() -> captureBaseline(playerUuid))
                .whenComplete((baseline, failure) -> server.execute(() -> {
                    if (failure != null) {
                        fail(helper, finished, "Could not capture command packet baseline: " + failure);
                        return;
                    }
                    try {
                        dispatchCommands.accept(() -> {
                            long deadlineNanos = System.nanoTime() + COMMAND_PERSISTENCE_DEADLINE_NANOS;
                            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> server.execute(() ->
                                    fail(helper, finished,
                                            "Command packet evidence was not durable within 10 seconds")));
                            awaitCommandRows(helper, server, finished, baseline, playerUuid,
                                    playerName, deadlineNanos);
                        });
                    } catch (Throwable dispatchFailure) {
                        fail(helper, finished, "Command packet dispatch failed: " + dispatchFailure);
                    }
                }));
    }

    private static Baseline captureBaseline(String playerUuid) {
        return new Baseline(auditWatermark(), snapshotPlayerQuantityObservations(playerUuid));
    }

    private static long auditWatermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
            return rows.next() ? rows.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the ItemGraph command audit watermark", e);
        }
    }

    private static List<List<String>> snapshotPlayerQuantityObservations(String playerUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT o.* FROM ig_observations o
                     WHERE EXISTS (
                         SELECT 1 FROM ig_nodes source_node
                         WHERE source_node.id = o.node_id AND source_node.owner_uuid = ?
                     ) OR EXISTS (
                         SELECT 1 FROM ig_nodes target_node
                         WHERE target_node.id = o.target_node_id AND target_node.owner_uuid = ?
                     )
                     ORDER BY o.id
                     """)) {
            statement.setString(1, playerUuid);
            statement.setString(2, playerUuid);
            try (var rows = statement.executeQuery()) {
                int columnCount = rows.getMetaData().getColumnCount();
                List<List<String>> snapshot = new ArrayList<>();
                while (rows.next()) {
                    List<String> values = new ArrayList<>(columnCount);
                    for (int column = 1; column <= columnCount; column++) {
                        Object value = rows.getObject(column);
                        values.add(encodeJdbcValue(value));
                    }
                    snapshot.add(List.copyOf(values));
                }
                return List.copyOf(snapshot);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not snapshot the command sender's ItemGraph observations", e);
        }
    }

    private static String encodeJdbcValue(Object value) {
        if (value == null) {
            return "<NULL>";
        }
        if (value instanceof byte[] bytes) {
            return "byte[]:" + Base64.getEncoder().encodeToString(bytes);
        }
        return value.getClass().getName() + ":" + value;
    }

    private static List<CommandRow> readCommandRows(long watermark, String playerUuid) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, event_type, player_name, detail, timestamp_ms
                     FROM ig_audit_events
                     WHERE id > ? AND player_uuid = ? AND event_type = 'COMMAND_ATTEMPT'
                     ORDER BY id
                     """)) {
            statement.setLong(1, watermark);
            statement.setString(2, playerUuid);
            try (var rows = statement.executeQuery()) {
                List<CommandRow> commands = new ArrayList<>();
                while (rows.next()) {
                    commands.add(new CommandRow(rows.getLong("id"), rows.getString("event_type"),
                            rows.getString("player_name"), rows.getString("detail"),
                            rows.getLong("timestamp_ms")));
                }
                return List.copyOf(commands);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not query command packet evidence", e);
        }
    }

    private static void awaitCommandRows(GameTestHelper helper, net.minecraft.server.MinecraftServer server,
                                         AtomicBoolean finished, Baseline baseline, String playerUuid,
                                         String playerName, long deadlineNanos) {
        CompletableFuture.delayedExecutor(COMMAND_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS).execute(() ->
                CompletableFuture.supplyAsync(() -> readCommandRows(baseline.auditWatermark(), playerUuid))
                        .whenComplete((commands, failure) -> server.execute(() -> {
                            if (finished.get()) {
                                return;
                            }
                            if (failure != null) {
                                fail(helper, finished, "Could not read command packet evidence: " + failure);
                                return;
                            }
                            if (commands.size() < EXPECTED_COMMANDS.size()) {
                                if (System.nanoTime() >= deadlineNanos) {
                                    fail(helper, finished, "Only " + commands.size()
                                            + " of 3 command packet rows became durable");
                                } else {
                                    awaitCommandRows(helper, server, finished, baseline, playerUuid,
                                            playerName, deadlineNanos);
                                }
                                return;
                            }
                            if (commands.size() != EXPECTED_COMMANDS.size()) {
                                fail(helper, finished, "Expected exactly 3 command packet rows, found "
                                        + commands.size());
                                return;
                            }
                            try {
                                helper.assertValueEqual(EXPECTED_COMMANDS,
                                        commands.stream().map(CommandRow::detail).toList(),
                                        "packet-dispatched command records differ from the cross-loader fixture");
                                for (CommandRow command : commands) {
                                    helper.assertValueEqual("COMMAND_ATTEMPT", command.eventType(),
                                            "a command packet must not persist another audit event type");
                                    helper.assertValueEqual(playerName, command.playerName(),
                                            "command evidence must remain attributed to the sending player");
                                    helper.assertTrue(command.detail() != null,
                                            "command attempt must retain the command text");
                                    helper.assertTrue(command.timestampMs() > 0,
                                            "command attempt must retain its event timestamp");
                                    helper.assertTrue(command.id() > baseline.auditWatermark(),
                                            "command packet evidence must be newer than the fixture watermark");
                                }
                            } catch (Throwable assertionFailure) {
                                fail(helper, finished, "Command packet evidence validation failed: " + assertionFailure);
                                return;
                            }
                            CompletableFuture.supplyAsync(() -> snapshotPlayerQuantityObservations(playerUuid))
                                    .whenComplete((observationsAfter, snapshotFailure) -> server.execute(() -> {
                                        if (snapshotFailure != null) {
                                            fail(helper, finished, "Could not verify command sender quantity rows: "
                                                    + snapshotFailure);
                                            return;
                                        }
                                        try {
                                            helper.assertValueEqual(baseline.playerObservations(), observationsAfter,
                                                    "command packets must not create or mutate item quantity "
                                                            + "observations for the command sender");
                                            if (finished.compareAndSet(false, true)) {
                                                helper.runAfterDelay(1, helper::succeed);
                                            }
                                        } catch (Throwable assertionFailure) {
                                            fail(helper, finished, "Command packet quantity check failed: "
                                                    + assertionFailure);
                                        }
                                    }));
                        })));
    }

    private static void fail(GameTestHelper helper, AtomicBoolean finished, String message) {
        if (finished.compareAndSet(false, true)) {
            helper.runAfterDelay(1, () -> helper.fail(message));
        }
    }
}
