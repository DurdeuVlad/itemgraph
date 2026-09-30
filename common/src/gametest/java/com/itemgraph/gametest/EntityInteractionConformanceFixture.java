package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.query.AuditEventDetail;
import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Shared expected contract run by both loader GameTests against the same persisted read path. */
public final class EntityInteractionConformanceFixture {
    private static final String DIMENSION = "minecraft:overworld";
    private static final List<String> COW_EVENT_TYPES = List.of("INTERACT_ENTITY");
    private static final List<String> ARMOR_STAND_EVENT_TYPES = List.of(
            "INTERACT_ENTITY", "INTERACT_ENTITY", "INTERACT_ENTITY_COMPLETED",
            "INTERACT_ENTITY_COMPLETED", "INTERACT_ENTITY_UNRESOLVED");

    private EntityInteractionConformanceFixture() { }

    /** Snapshot the item-flow ledger before an interaction-only replay begins. */
    public static List<List<String>> snapshotQuantityObservations() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM ig_observations ORDER BY id")) {
            int columnCount = rows.getMetaData().getColumnCount();
            List<List<String>> snapshot = new ArrayList<>();
            while (rows.next()) {
                List<String> values = new ArrayList<>(columnCount);
                for (int column = 1; column <= columnCount; column++) {
                    byte[] value = rows.getBytes(column);
                    values.add(value == null && rows.wasNull()
                            ? "<NULL>" : Base64.getEncoder().encodeToString(value));
                }
                snapshot.add(List.copyOf(values));
            }
            return List.copyOf(snapshot);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not snapshot ItemGraph quantity observations", e);
        }
    }

    /** Entity interaction evidence must not create or mutate item-flow observations. */
    public static void assertQuantityObservationsUnchanged(GameTestHelper helper, List<List<String>> before) {
        helper.assertValueEqual(before, snapshotQuantityObservations(),
                "entity interactions must not create or mutate quantity-flow observations");
    }

    public static void assertCow(GameTestHelper helper, String playerUuid, String playerName,
                                 BlockPos position, String targetUuid) {
        assertTarget(helper, playerUuid, playerName, position, "minecraft:cow", targetUuid, COW_EVENT_TYPES);
    }

    public static void assertArmorStand(GameTestHelper helper, String playerUuid, String playerName,
                                       BlockPos position, String targetUuid) {
        assertTarget(helper, playerUuid, playerName, position, "minecraft:armor_stand", targetUuid,
                ARMOR_STAND_EVENT_TYPES);
    }

    private static void assertTarget(GameTestHelper helper, String playerUuid, String playerName,
                                     BlockPos position, String subjectId, String targetUuid,
                                     List<String> expectedEventTypes) {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection()) {
            List<AuditEventDetail> rows = new AuditEventQueryService().find(
                    connection, "all", null, QueryWindow.unbounded(), DIMENSION,
                    (double) position.getX(), (double) position.getY(), (double) position.getZ(),
                    0.0, 100, 0).stream()
                    .filter(row -> Objects.equals(playerUuid, row.playerUuid())
                            && Objects.equals(subjectId, row.subjectId())
                            && row.detail() != null && row.detail().contains("target_uuid=" + targetUuid))
                    .toList();

            List<String> actualEventTypes = rows.stream().map(AuditEventDetail::eventType).sorted().toList();
            List<String> expectedSortedTypes = expectedEventTypes.stream().sorted().toList();
            helper.assertValueEqual(expectedSortedTypes, actualEventTypes,
                    "normalized event-type multiset differs from shared loader conformance fixture");
            assertDetailContract(helper, subjectId, targetUuid, rows);

            List<String> formatted = QueryFormatter.formatAuditEvents(rows, "all");
            helper.assertValueEqual("[ItemGraph] === NATIVE AUDIT EVENTS (all) ===", formatted.get(0),
                    "normalized lookup output header changed");
            for (int i = 0; i < rows.size(); i++) {
                AuditEventDetail row = rows.get(i);
                helper.assertTrue(row.timestampMs() > 0, "normalized event timestamp must be present");
                helper.assertValueEqual(playerUuid, row.playerUuid(), "normalized event actor UUID changed");
                helper.assertValueEqual(playerName, row.playerName(), "normalized event actor name changed");
                helper.assertValueEqual(DIMENSION, row.levelName(), "normalized event dimension changed");
                helper.assertValueEqual((double) position.getX(), row.x(), "normalized event X changed");
                helper.assertValueEqual((double) position.getY(), row.y(), "normalized event Y changed");
                helper.assertValueEqual((double) position.getZ(), row.z(), "normalized event Z changed");
                helper.assertValueEqual(subjectId, row.subjectId(), "normalized event subject changed");

                String expectedLine = "[ItemGraph] [OBSERVED] audit#" + row.id() + " " + row.eventType()
                        + " actor=" + playerName + " at " + DIMENSION + " ["
                        + position.getX() + ", " + position.getY() + ", " + position.getZ()
                        + "] time=" + QueryFormatter.formatTime(row.timestampMs())
                        + " subject=" + subjectId + " detail=" + row.detail();
                helper.assertValueEqual(expectedLine, formatted.get(i + 1),
                        "console formatter output differs from normalized persisted interaction evidence");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not query the shared entity interaction conformance fixture", e);
        }
    }

    private static void assertDetailContract(GameTestHelper helper, String subjectId, String targetUuid,
                                             List<AuditEventDetail> rows) {
        if ("minecraft:cow".equals(subjectId)) {
            AuditEventDetail attempt = rows.get(0);
            helper.assertTrue(attempt.detail().contains("outcome=attempt hand=main_hand target_uuid=" + targetUuid),
                    "unsupported-target row must retain the cow interaction attempt");
            helper.assertTrue(attempt.detail().contains("target_support=callback_only"
                            + " target_support_reason=ENTITY_CLASS_UNSUPPORTED_FOR_RESULT"),
                    "unsupported-target row must retain its stable support reason");
            helper.assertTrue(attempt.detail().contains("held_item=minecraft:stick held_count=1"),
                    "unsupported-target attempt must retain the held stack identity and count");
            return;
        }

        int bootsAttempts = 0;
        int emptyHandAttempts = 0;
        int handledResults = 0;
        int unresolvedFallbackResults = 0;
        for (AuditEventDetail row : rows) {
            String detail = row.detail();
            helper.assertTrue(detail.contains("target_support=armor_stand_method_result"),
                    "armor-stand rows must identify the supported method-result target class");
            if ("INTERACT_ENTITY".equals(row.eventType())) {
                helper.assertTrue(detail.contains("outcome=attempt hand=main_hand target_uuid=" + targetUuid),
                        "armor-stand input row must remain an attempt with hand and target");
                if (detail.contains("held_item=minecraft:diamond_boots held_count=1")) {
                    bootsAttempts++;
                } else if (detail.contains("held_item=minecraft:air held_count=0")) {
                    emptyHandAttempts++;
                } else {
                    helper.fail("armor-stand attempt has unexpected held-stack evidence: " + detail);
                }
            } else {
                helper.assertTrue(detail.contains("hand=main_hand target_uuid=" + targetUuid),
                        "armor-stand result must retain hand and target");
                if ("INTERACT_ENTITY_COMPLETED".equals(row.eventType())) {
                    helper.assertTrue(detail.contains("outcome=handled result=success method=interact_at"),
                            "armor-stand result must match the observed interact_at success boundary");
                    handledResults++;
                } else {
                    helper.assertValueEqual("INTERACT_ENTITY_UNRESOLVED", row.eventType(),
                            "PASS from inherited Entity.interact must remain an unresolved method result");
                    helper.assertTrue(detail.contains("outcome=unresolved result=pass method=interact"),
                            "fallback Entity.interact PASS must use its own unresolved result boundary");
                    helper.assertTrue(detail.contains("reason=ENTITY_INTERACTION_METHOD_PASSED"),
                            "fallback PASS must explain why the interaction remains unresolved");
                    unresolvedFallbackResults++;
                }
            }
        }
        helper.assertValueEqual(1, bootsAttempts,
                "cross-loader differential fixture expects one held-boots armor-stand attempt");
        helper.assertValueEqual(1, emptyHandAttempts,
                "cross-loader differential fixture expects one empty-hand unequip attempt");
        helper.assertValueEqual(2, handledResults,
                "cross-loader differential fixture expects one handled result per equip/unequip click");
        helper.assertValueEqual(1, unresolvedFallbackResults,
                "cross-loader differential fixture expects one unresolved Entity.interact fallback result");
    }
}
