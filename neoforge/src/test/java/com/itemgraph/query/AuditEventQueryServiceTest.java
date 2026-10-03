package com.itemgraph.query;

import com.itemgraph.db.migration.MigrationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditEventQueryServiceTest {
    private Connection conn;
    private AuditEventQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        MigrationRunner.runMigrations(conn);
        service = new AuditEventQueryService();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void migrationCreatesNativeAuditLedgerAndQueriesFilters() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO ig_audit_events
                    (event_type, timestamp_ms, player_uuid, player_name, level_id,
                     x, y, z, subject_id, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, "BREAK_BLOCK");
            insert.setLong(2, 1_000L);
            insert.setString(3, "uuid-alex");
            insert.setString(4, "Alex");
            insert.setString(5, "minecraft:overworld");
            insert.setDouble(6, 10);
            insert.setDouble(7, 64);
            insert.setDouble(8, -3);
            insert.setString(9, "minecraft:stone");
            insert.setString(10, null);
            insert.executeUpdate();

            insert.setString(1, "CHAT_MESSAGE");
            insert.setLong(2, 2_000L);
            insert.setString(3, "uuid-alex");
            insert.setString(4, "Alex");
            insert.setString(5, "minecraft:overworld");
            insert.setDouble(6, 10);
            insert.setDouble(7, 64);
            insert.setDouble(8, -3);
            insert.setString(9, null);
            insert.setString(10, "hello");
            insert.executeUpdate();

            insert.setString(1, "BREAK_BLOCK");
            insert.setLong(2, 3_000L);
            insert.setString(3, "uuid-sam");
            insert.setString(4, "Sam");
            insert.setString(5, "minecraft:overworld");
            insert.setDouble(6, 20);
            insert.setDouble(7, 64);
            insert.setDouble(8, -3);
            insert.setString(9, "minecraft:dirt");
            insert.setString(10, null);
            insert.executeUpdate();
        }

        List<AuditEventDetail> alexBreaks = service.find(
                conn, "break_block", "Alex", QueryWindow.unbounded(), 100);
        assertEquals(1, alexBreaks.size());
        assertEquals("BREAK_BLOCK", alexBreaks.get(0).eventType());
        assertEquals("minecraft:stone", alexBreaks.get(0).subjectId());

        List<AuditEventDetail> recent = service.find(
                conn, "all", null, new QueryWindow(2_000L, 3_000L), 100);
        assertEquals(List.of("BREAK_BLOCK", "CHAT_MESSAGE"),
                recent.stream().map(AuditEventDetail::eventType).toList());

        String formatted = String.join("\n", QueryFormatter.formatAuditEvents(
                alexBreaks, "type=BREAK_BLOCK player=Alex window=all time"));
        assertTrue(formatted.contains("[OBSERVED] audit#"));
        assertTrue(formatted.contains("minecraft:stone"));
        assertTrue(formatted.contains("Alex"));
    }

    @Test
    void formatterPreservesUnresolvedWorldEvidenceAndDoesNotInventAPlayer() {
        AuditEventDetail event = new AuditEventDetail(
                7L, "WORLD_EFFECT_UNRESOLVED", 1_000L, null, null,
                "minecraft:overworld", 1, 64, 2, "minecraft:stone",
                "cause=minecraft:creeper outcome=unconfirmed");

        String formatted = String.join("\n", QueryFormatter.formatAuditEvents(List.of(event), "all"));

        assertTrue(formatted.contains("[UNRESOLVED] audit#7 WORLD_EFFECT_UNRESOLVED"));
        assertTrue(formatted.contains("actor=(unknown actor)"));
        assertFalse(formatted.contains("actor=(unknown player)"));
    }

    @Test
    void resultLimitIsCapped() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms) VALUES ('CHAT_MESSAGE', ?)")) {
            for (int i = 0; i < 150; i++) {
                insert.setLong(1, i);
                insert.addBatch();
            }
            insert.executeBatch();
        }

        assertEquals(QueryLimits.MAX_LIMIT,
                service.find(conn, "all", null, QueryWindow.unbounded(), 10_000).size());
    }

    @Test
    void nativeEntityInteractionEventsAreSelectableByEventType() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, player_name, subject_id, detail) VALUES ('INTERACT_ENTITY', 1, 'Alex', 'minecraft:villager', 'outcome=attempt')")) {
            insert.executeUpdate();
        }

        List<AuditEventDetail> interactions = service.find(
                conn, "interact_entity", "Alex", QueryWindow.unbounded(), 10);

        assertEquals(1, interactions.size());
        assertEquals("INTERACT_ENTITY", interactions.get(0).eventType());
        assertEquals("minecraft:villager", interactions.get(0).subjectId());
        assertEquals("outcome=attempt", interactions.get(0).detail());
    }

    @Test
    void completedArmorStandInteractionHasSeparateSelectableEventType() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, player_name, subject_id, detail) "
                        + "VALUES ('INTERACT_ENTITY_COMPLETED', 2, 'Alex', 'minecraft:armor_stand', "
                        + "'outcome=handled result=success hand=main_hand target_uuid=stand-1')")) {
            insert.executeUpdate();
        }

        List<AuditEventDetail> interactions = service.find(
                conn, "INTERACT_ENTITY_COMPLETED", "Alex", QueryWindow.unbounded(), 10);

        assertEquals(1, interactions.size());
        assertEquals("INTERACT_ENTITY_COMPLETED", interactions.get(0).eventType());
        assertEquals("minecraft:armor_stand", interactions.get(0).subjectId());
        assertEquals("outcome=handled result=success hand=main_hand target_uuid=stand-1",
                interactions.get(0).detail());
    }

    @Test
    void deniedArmorStandInteractionHasSeparateSelectableEventType() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, player_name, subject_id, detail) "
                        + "VALUES ('INTERACT_ENTITY_DENIED', 3, 'Alex', 'minecraft:armor_stand', "
                        + "'outcome=denied result=fail hand=main_hand target_uuid=stand-2')")) {
            insert.executeUpdate();
        }

        List<AuditEventDetail> interactions = service.find(
                conn, "INTERACT_ENTITY_DENIED", "Alex", QueryWindow.unbounded(), 10);
        assertEquals(1, interactions.size());
        assertEquals("INTERACT_ENTITY_DENIED", interactions.get(0).eventType());
        assertTrue(interactions.get(0).detail().contains("outcome=denied"));
    }

    @Test
    void ordinaryLookupShowsWhyNativeAuditEventWasSuperseded() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, detail) VALUES (?, ?, ?)")) {
            insert.setString(1, "INTERACT_BLOCK_ATTEMPT");
            insert.setLong(2, 1_000L);
            insert.setString(3, "outcome=attempt");
            insert.executeUpdate();
            insert.setString(1, "BREAK_BLOCK");
            insert.setLong(2, 2_000L);
            insert.setString(3, "outcome=broken");
            insert.executeUpdate();
        }
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO ig_audit_event_supersessions
                    (superseded_event_id, superseding_event_id, reason_code, created_at_ms)
                VALUES (1, 2, 'BLOCK_BROKEN_AFTER_INTERACTION', 2_000)
                """)) {
            insert.executeUpdate();
        }

        AuditEventDetail event = service.find(
                conn, "INTERACT_BLOCK_ATTEMPT", null, QueryWindow.unbounded(), 10).get(0);
        String formatted = String.join("\n", QueryFormatter.formatAuditEvents(List.of(event), "all"));

        assertEquals(2L, event.supersedingEventId());
        assertEquals("BLOCK_BROKEN_AFTER_INTERACTION", event.supersessionReason());
        assertTrue(formatted.contains("superseded_by=audit#2 reason=BLOCK_BROKEN_AFTER_INTERACTION"));
    }

    @Test
    void exactLogicalTargetLookupMergesPositionsWithGlobalOrderingAndPagination() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, level_id, x, y, z, detail) VALUES ('INTERACT_BLOCK', ?, 'minecraft:overworld', ?, 64, 0, ?)")) {
            insert.setLong(1, 3L);
            insert.setDouble(2, 10.0);
            insert.setString(3, "left-new");
            insert.executeUpdate();
            insert.setLong(1, 2L);
            insert.setDouble(2, 11.0);
            insert.setString(3, "right-old");
            insert.executeUpdate();
            insert.setLong(1, 1L);
            insert.setDouble(2, 12.0);
            insert.setString(3, "outside");
            insert.executeUpdate();

            insert.setLong(1, 4L);
            insert.setDouble(2, 10.0);
            insert.setString(3, "wrong-dimension");
            insert.executeUpdate();
        }
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE ig_audit_events SET level_id = 'minecraft:the_nether' WHERE detail = 'wrong-dimension'")) {
            update.executeUpdate();
        }

        List<AuditEventDetail> first = service.findExact(
                conn, "INTERACT_BLOCK", null, QueryWindow.unbounded(), "minecraft:overworld",
                List.of(new AuditEventQueryService.ExactPosition(10, 64, 0),
                        new AuditEventQueryService.ExactPosition(11, 64, 0),
                        new AuditEventQueryService.ExactPosition(10, 64, 0)), 1, 0);
        List<AuditEventDetail> second = service.findExact(
                conn, "INTERACT_BLOCK", null, QueryWindow.unbounded(), "minecraft:overworld",
                List.of(new AuditEventQueryService.ExactPosition(10, 64, 0),
                        new AuditEventQueryService.ExactPosition(11, 64, 0)), 1, 1);

        assertEquals(List.of("left-new"), first.stream().map(AuditEventDetail::detail).toList());
        assertEquals(List.of("right-old"), second.stream().map(AuditEventDetail::detail).toList());
        assertThrows(IllegalArgumentException.class, () -> service.findExact(
                conn, "all", null, QueryWindow.unbounded(), "minecraft:overworld",
                java.util.stream.IntStream.range(0, 9)
                        .mapToObj(i -> new AuditEventQueryService.ExactPosition(i, 64, 0)).toList(), 10, 0));
    }

    @Test
    void formatterKeepsMultilineDetailsOnOneConsoleLine() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, detail) VALUES ('CHAT_MESSAGE', 1, ?)") ) {
            insert.setString(1, "hello\nworld\t\\quoted");
            insert.executeUpdate();
        }

        AuditEventDetail event = service.find(
                conn, "all", null, QueryWindow.unbounded(), 10).get(0);
        String formatted = String.join("\n", QueryFormatter.formatAuditEvents(List.of(event), "all"));

        assertTrue(formatted.contains("detail=hello\\nworld\\t\\\\quoted"));
        assertFalse(formatted.contains("detail=hello\nworld"));
    }

    @Test
    void dimensionAndRadiusFiltersAreAppliedBeforeTheResultLimit() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, level_id, x, y, z) VALUES ('BREAK_BLOCK', ?, ?, ?, 64, 0)")) {
            insert.setLong(1, 1L);
            insert.setString(2, "minecraft:overworld");
            insert.setDouble(3, 3);
            insert.executeUpdate();
            insert.setLong(1, 2L);
            insert.setString(2, "minecraft:overworld");
            insert.setDouble(3, 30);
            insert.executeUpdate();
            insert.setLong(1, 3L);
            insert.setString(2, "minecraft:the_nether");
            insert.setDouble(3, 2);
            insert.executeUpdate();
        }

        List<AuditEventDetail> nearby = service.find(
                conn, "BREAK_BLOCK", null, QueryWindow.unbounded(),
                "minecraft:overworld", 0.0, 64.0, 0.0, 5.0, 100);

        assertEquals(1, nearby.size());
        assertEquals("minecraft:overworld", nearby.get(0).levelName());
        assertEquals(3.0, nearby.get(0).x());
    }

    @Test
    void zeroRadiusInspectorLookupMatchesOnlyTheClickedBlock() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, level_id, x, y, z) VALUES ('BREAK_BLOCK', ?, 'minecraft:overworld', ?, ?, ?)")) {
            insert.setLong(1, 1L);
            insert.setDouble(2, 10.0);
            insert.setDouble(3, 64.0);
            insert.setDouble(4, -3.0);
            insert.executeUpdate();

            insert.setLong(1, 2L);
            insert.setDouble(2, 11.0);
            insert.setDouble(3, 64.0);
            insert.setDouble(4, -3.0);
            insert.executeUpdate();
        }

        List<AuditEventDetail> exact = service.find(
                conn, "all", null, QueryWindow.unbounded(),
                "minecraft:overworld", 10.0, 64.0, -3.0, 0.0, 100);

        assertEquals(1, exact.size());
        assertEquals(10.0, exact.get(0).x());
        assertEquals(64.0, exact.get(0).y());
        assertEquals(-3.0, exact.get(0).z());
    }

    @Test
    void pageOffsetUsesStableTimestampAndIdOrdering() throws Exception {
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO ig_audit_events (event_type, timestamp_ms, detail) VALUES ('CHAT_MESSAGE', ?, ?)")) {
            for (int i = 0; i < 3; i++) {
                insert.setLong(1, 10_000L);
                insert.setString(2, "message-" + i);
                insert.executeUpdate();
            }
        }

        List<AuditEventDetail> first = service.find(
                conn, "CHAT_MESSAGE", null, QueryWindow.unbounded(),
                null, null, null, null, null, 2, 0);
        List<AuditEventDetail> second = service.find(
                conn, "CHAT_MESSAGE", null, QueryWindow.unbounded(),
                null, null, null, null, null, 2, 2);

        assertEquals(2, first.size());
        assertEquals(1, second.size());
        assertNotEquals(first.get(1).id(), second.get(0).id());
    }

    @Test
    void griefLoggerStyleFiltersUseNativeActionsUsersSubjectsAndCubeRadius() throws Exception {
        long now = 10_000_000L;
        try (PreparedStatement insert = conn.prepareStatement("""
                INSERT INTO ig_audit_events
                    (event_type, timestamp_ms, player_name, level_id, x, y, z, subject_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, "BREAK_BLOCK");
            insert.setLong(2, now - 30 * 60_000L);
            insert.setString(3, "Alex");
            insert.setString(4, "minecraft:overworld");
            insert.setDouble(5, 5);
            insert.setDouble(6, 5);
            insert.setDouble(7, 5);
            insert.setString(8, "minecraft:diamond_ore");
            insert.executeUpdate();

            insert.setString(1, "BREAK_BLOCK");
            insert.setLong(2, now - 30 * 60_000L);
            insert.setString(3, "Alex");
            insert.setString(4, "minecraft:overworld");
            insert.setDouble(5, 6);
            insert.setDouble(6, 0);
            insert.setDouble(7, 0);
            insert.setString(8, "minecraft:diamond_ore");
            insert.executeUpdate();

            insert.setString(1, "PLACE_BLOCK");
            insert.setLong(2, now - 30 * 60_000L);
            insert.setString(3, "Alex");
            insert.setString(4, "minecraft:overworld");
            insert.setDouble(5, 0);
            insert.setDouble(6, 0);
            insert.setDouble(7, 0);
            insert.setString(8, "minecraft:diamond_ore");
            insert.executeUpdate();

            insert.setString(1, "BREAK_BLOCK");
            insert.setLong(2, now - 2 * 60 * 60_000L);
            insert.setString(3, "Alex");
            insert.setString(4, "minecraft:overworld");
            insert.setDouble(5, 0);
            insert.setDouble(6, 0);
            insert.setDouble(7, 0);
            insert.setString(8, "minecraft:diamond_ore");
            insert.executeUpdate();
        }

        AuditLookupFilters filters = AuditLookupFilters.parse(
                "action.break_block user.Alex include.diamond_ore time.1h radius.5", now);
        List<AuditEventDetail> matched = service.findFiltered(
                conn, filters, "minecraft:overworld", 0, 0, 0, 100, 0);

        assertEquals(1, matched.size());
        assertEquals(5.0, matched.get(0).x());
        assertEquals(List.of("BREAK_BLOCK"), filters.eventTypes());
        assertEquals(List.of("minecraft:diamond_ore"), filters.includeSubjects());
    }

    @Test
    void filterParserRejectsConflictingOrUnboundedRequests() {
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("include.stone exclude.dirt radius.5", 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("action.break_block user.Alex include.stone time.1h radius.5 extra.x", 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("action.break_block", 1_000L));
        assertEquals(List.of("DROP_ITEM"), AuditLookupFilters.parse(
                "action.drop_item radius.5", 1_000L).eventTypes());
        assertEquals(List.of("PICKUP_ITEM"), AuditLookupFilters.parse(
                "action.pickup_item radius.5", 1_000L).eventTypes());
        assertEquals(List.of("CRAFT"), AuditLookupFilters.parse(
                "action.craft_item radius.5", 1_000L).eventTypes());
        assertEquals(List.of("ANVIL_REPAIR"), AuditLookupFilters.parse(
                "action.anvil_repair radius.5", 1_000L).eventTypes());
        AuditLookupFilters dotted = AuditLookupFilters.parse(
                "include.modid:item.variant radius.5", 1_000L);
        assertEquals(List.of("modid:item.variant"), dotted.includeSubjects());
    }
}
