package com.itemgraph.gametest;

import com.itemgraph.db.DatabaseManager;
import com.itemgraph.query.AuditEventDetail;
import com.itemgraph.query.AuditEventQueryService;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Shared expected contract run by both loader GameTests against the same persisted read path. */
public final class EntityInteractionConformanceFixture {
    private static final String DIMENSION = "minecraft:overworld";
    private static final List<String> COW_EVENT_TYPES = List.of("INTERACT_ENTITY");
    private static final List<String> ARMOR_STAND_EVENT_TYPES = List.of(
            "INTERACT_ENTITY", "INTERACT_ENTITY", "INTERACT_ENTITY_COMPLETED", "INTERACT_ENTITY_COMPLETED");

    private EntityInteractionConformanceFixture() { }

    /** Snapshot the item-flow ledger before an interaction-only replay begins. */
    public static long countQuantityObservations() {
        try (var connection = DatabaseManager.getInstance().openReadOnlyConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM ig_observations")) {
            if (!rows.next()) {
                throw new SQLException("COUNT(*) returned no row for ig_observations");
            }
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count ItemGraph quantity observations", e);
        }
    }

    /** Entity interaction evidence must not create item-flow observations. */
    public static void assertQuantityObservationsUnchanged(GameTestHelper helper, long before) {
        helper.assertValueEqual(before, countQuantityObservations(),
                "entity interactions must not create quantity-flow observations");
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
}
