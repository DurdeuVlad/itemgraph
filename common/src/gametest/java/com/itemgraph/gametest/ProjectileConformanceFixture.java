package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import net.minecraft.gametest.framework.GameTestHelper;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Shared durable projectile contract exercised by both loader GameTests. */
public final class ProjectileConformanceFixture {
    private static final Map<String, ExpectedProjectile> EXPECTED = Map.of(
            "THROW_ITEM", new ExpectedProjectile("minecraft:snowball", 1),
            "SHOOT_ITEM", new ExpectedProjectile("minecraft:arrow", 1));

    private ProjectileConformanceFixture() { }

    public static Watermark watermark() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement()) {
            long observationId;
            try (var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_observations")) {
                if (!rows.next()) {
                    throw new SQLException("SQLite did not return the ItemGraph observation watermark");
                }
                observationId = rows.getLong(1);
            }
            long auditId;
            try (var rows = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM ig_audit_events")) {
                if (!rows.next()) {
                    throw new SQLException("SQLite did not return the ItemGraph audit watermark");
                }
                auditId = rows.getLong(1);
            }
            return new Watermark(observationId, auditId);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph evidence watermarks", failure);
        }
    }

    /**
     * Prove that the only new quantity rows in the replay are one throw and
     * one shot, then compare their paired native-audit projections.
     */
    public static void assertPersisted(GameTestHelper helper, Watermark prior, String playerUuid,
                                       List<List<String>> priorQuantityRows) {
        EntityInteractionConformanceFixture.assertQuantityObservationsUnchanged(helper, priorQuantityRows);

        List<ObservationRow> observations = readProjectileObservations(prior.observationId(), playerUuid);
        helper.assertValueEqual(2, observations.size(),
                "one throw and one shot must each persist exactly one quantity observation");

        Map<String, ObservationRow> byAction = new TreeMap<>();
        for (ObservationRow row : observations) {
            helper.assertTrue(byAction.put(row.actionType(), row) == null,
                    "projectile replay produced a duplicate quantity row for " + row.actionType());
            ExpectedProjectile expected = EXPECTED.get(row.actionType());
            helper.assertTrue(expected != null,
                    "unexpected quantity action from projectile replay: " + row.actionType());
            helper.assertValueEqual(expected.itemId(), row.itemId(),
                    row.actionType() + " canonical item changed");
            helper.assertValueEqual(expected.amount(), row.amount(),
                    row.actionType() + " quantity changed");
            helper.assertTrue(row.sourceEventId() != null,
                    row.actionType() + " attempt must have a durable idempotency key");
            helper.assertValueEqual(playerUuid, row.ownerUuid(),
                    row.actionType() + " quantity must originate from the player");
            helper.assertValueEqual("UNKNOWN", row.targetType(),
                    row.actionType() + " must not claim a landing location");
            helper.assertTrue(row.rawData().contains("projectile_shoot_attempt")
                            && row.rawData().contains("\"outcome\":\"attempt\""),
                    row.actionType() + " raw payload must identify the shoot attempt boundary");
        }
        helper.assertValueEqual(EXPECTED.keySet(), byAction.keySet(),
                "cross-loader replay must retain both canonical projectile actions");
        Map<Long, String> quantityActionsByEventId = new TreeMap<>();
        observations.forEach(row -> quantityActionsByEventId.put(row.sourceEventId(), row.actionType()));
        helper.assertValueEqual(2, quantityActionsByEventId.size(),
                "throw and shot quantity rows must have distinct idempotency keys");
        Set<Long> quantityEventIds = quantityActionsByEventId.keySet();

        List<AuditRow> auditRows = readProjectileAuditRows(playerUuid, prior.auditId());
        helper.assertValueEqual(4, auditRows.size(),
                "each projectile must have one attempt projection and one accepted-spawn evidence row");
        Map<Long, String> attemptActionsByEventId = new TreeMap<>();
        Set<Long> allAuditEventIds = new TreeSet<>();
        Set<String> acceptedActions = new TreeSet<>();
        int attempts = 0;
        int accepted = 0;
        for (AuditRow row : auditRows) {
            helper.assertTrue(row.sourceEventId() != null,
                    row.eventType() + " must have a durable idempotency key");
            helper.assertTrue(allAuditEventIds.add(row.sourceEventId()),
                    "projectile audit evidence reused an event identity");
            if ("PROJECTILE_SPAWN_ACCEPTED".equals(row.eventType())) {
                accepted++;
                String action = row.detail().startsWith("action=")
                        ? row.detail().substring("action=".length()).split(" ", 2)[0] : "";
                acceptedActions.add(action);
                ExpectedProjectile expected = EXPECTED.get(action);
                helper.assertTrue(expected != null,
                        "accepted spawn has an unexpected action: " + action);
                helper.assertValueEqual(expected.itemId(), row.subjectId(),
                        "accepted spawn subject must match the projectile item for " + action);
                helper.assertTrue(row.detail().contains("projectile=" + expected.itemId() + " ")
                                && row.rawData().contains("\"projectile\":\"" + expected.itemId() + "\""),
                        "accepted spawn projectile identifier must match " + action);
                helper.assertTrue(row.rawData().contains("\"outcome\":\"accepted\""),
                        "accepted spawn extension must record the authoritative addFreshEntity result");
                helper.assertFalse(row.detail().contains("quantity="),
                        "accepted spawn evidence must not create a second quantity claim");
                helper.assertFalse(quantityEventIds.contains(row.sourceEventId()),
                        "accepted-spawn evidence must have a distinct event identity from the quantity attempt");
            } else {
                attempts++;
                helper.assertTrue(EXPECTED.containsKey(row.eventType()),
                        "unexpected projectile audit action: " + row.eventType());
                helper.assertTrue(row.detail().contains("outcome=attempt evidence=shoot_from_rotation quantity=1"),
                        "attempt projection must retain the pre-acceptance boundary and quantity");
                helper.assertTrue(attemptActionsByEventId.put(row.sourceEventId(), row.eventType()) == null,
                        "projectile attempt audit projection was duplicated");
            }
        }
        helper.assertValueEqual(2, attempts, "one attempt projection is required per projectile");
        helper.assertValueEqual(2, accepted, "one accepted-spawn evidence row is required per accepted projectile");
        helper.assertValueEqual(EXPECTED.keySet(), acceptedActions,
                "accepted-spawn extensions must preserve their action classification");
        helper.assertValueEqual(quantityActionsByEventId, attemptActionsByEventId,
                "each quantity observation and legacy audit projection must share its event identity and action");
    }

    private static List<ObservationRow> readProjectileObservations(long priorObservationId, String playerUuid) {
        String sql = """
                SELECT o.action_type, o.amount, o.source_event_id, o.raw_data,
                       source.owner_uuid, target.node_type, fingerprint.item_id
                FROM ig_observations o
                JOIN ig_nodes source ON source.id = o.node_id
                JOIN ig_nodes target ON target.id = o.target_node_id
                JOIN ig_item_fingerprints fingerprint ON fingerprint.id = o.fingerprint_id
                WHERE o.id > ? AND o.source_type = 'ITEMGRAPH_INTERNAL'
                  AND o.action_type IN ('THROW_ITEM', 'SHOOT_ITEM')
                  AND source.owner_uuid = ?
                ORDER BY o.id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, priorObservationId);
            statement.setString(2, playerUuid);
            try (var rows = statement.executeQuery()) {
                List<ObservationRow> result = new ArrayList<>();
                while (rows.next()) {
                    byte[] raw = rows.getBytes("raw_data");
                    long id = rows.getLong("source_event_id");
                    Long eventId = rows.wasNull() ? null : id;
                    result.add(new ObservationRow(rows.getString("action_type"), rows.getInt("amount"),
                            eventId, raw == null ? "" : new String(raw, StandardCharsets.UTF_8),
                            rows.getString("owner_uuid"), rows.getString("node_type"),
                            rows.getString("item_id")));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph projectile quantity evidence", failure);
        }
    }

    private static List<AuditRow> readProjectileAuditRows(String playerUuid, long priorAuditId) {
        String sql = """
                SELECT event_type, subject_id, detail, source_event_id, raw_data
                FROM ig_audit_events
                WHERE id > ? AND player_uuid = ?
                  AND event_type IN ('THROW_ITEM', 'SHOOT_ITEM', 'PROJECTILE_SPAWN_ACCEPTED')
                ORDER BY id
                """;
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, priorAuditId);
            statement.setString(2, playerUuid);
            try (var rows = statement.executeQuery()) {
                List<AuditRow> result = new ArrayList<>();
                while (rows.next()) {
                    byte[] raw = rows.getBytes("raw_data");
                    long id = rows.getLong("source_event_id");
                    Long eventId = rows.wasNull() ? null : id;
                    result.add(new AuditRow(rows.getString("event_type"), rows.getString("subject_id"),
                            rows.getString("detail"),
                            eventId,
                            raw == null ? "" : new String(raw, StandardCharsets.UTF_8)));
                }
                return result;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read ItemGraph projectile audit evidence", failure);
        }
    }

    public record Watermark(long observationId, long auditId) { }

    private record ExpectedProjectile(String itemId, int amount) { }

    private record ObservationRow(String actionType, int amount, Long sourceEventId, String rawData,
                                  String ownerUuid, String targetType, String itemId) { }

    private record AuditRow(String eventType, String subjectId, String detail, Long sourceEventId, String rawData) { }
}
