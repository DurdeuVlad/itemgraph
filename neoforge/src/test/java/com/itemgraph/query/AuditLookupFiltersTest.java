package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditLookupFiltersTest {

    @Test
    void parsesQuotedUnicodeNamesEscapesAndComponentJsonWithSpaces() {
        AuditLookupFilters filters = AuditLookupFilters.parse(
                "radius.64 item.minecraft:diamond_sword name.\"Épée \\\"A\\\", ancient\" "
                        + "lore.\"first, second\" component.example:relic={\"charge\": 2, \"kind\": \"void\"}",
                0L);

        assertEquals(4, filters.itemPredicates().size());
        assertEquals("Épée \"A\", ancient", filters.itemPredicates().get(1).value());
        assertEquals("first, second", filters.itemPredicates().get(2).value());
        assertEquals("{\"charge\":2,\"kind\":\"void\"}", filters.itemPredicates().get(3).value());
        assertTrue(filters.describe().contains("name.\"Épée \\\"A\\\", ancient\""));
    }

    @Test
    void convertsExclusiveAbsoluteBoundsToMillisecondInclusiveStorageBounds() {
        AuditLookupFilters filters = AuditLookupFilters.parse(
                "radius.10 after.2026-10-02T12:00:00.100Z before.2026-10-02T12:00:00.105Z", 0L);

        assertEquals(Instant.parse("2026-10-02T12:00:00.101Z").toEpochMilli(), filters.window().sinceMs());
        assertEquals(Instant.parse("2026-10-02T12:00:00.104Z").toEpochMilli(), filters.window().untilMs());
    }

    @Test
    void betweenKeepsBothTimestampBoundariesInclusive() {
        AuditLookupFilters filters = AuditLookupFilters.parse(
                "radius.10 between.2026-10-02T12:00:00.100Z,2026-10-02T12:00:00.105Z", 0L);

        assertEquals(Instant.parse("2026-10-02T12:00:00.100Z").toEpochMilli(), filters.window().sinceMs());
        assertEquals(Instant.parse("2026-10-02T12:00:00.105Z").toEpochMilli(), filters.window().untilMs());
    }

    @Test
    void canonicalizesComponentJsonObjectKeyOrderAndNumberForms() {
        AuditLookupFilters first = AuditLookupFilters.parse(
                "radius.5 component.example:state={\"b\": 2.0, \"a\": [1.00, true]}", 0L);
        AuditLookupFilters second = AuditLookupFilters.parse(
                "radius.5 component.example:state={\"a\":[1,true],\"b\":2}", 0L);

        assertEquals(first.itemPredicates(), second.itemPredicates());
    }

    @Test
    void rejectsInvalidTimezoneEmptyAndReversedAbsoluteRanges() {
        assertThrows(IllegalArgumentException.class, () -> AuditLookupFilters.parse(
                "radius.5 after.2026-10-02T12:00:00.100+02:00", 0L));
        assertThrows(IllegalArgumentException.class, () -> AuditLookupFilters.parse(
                "radius.5 between.2026-10-02T12:00:00.100Z,2026-10-02T12:00:00.099Z", 0L));
        assertThrows(IllegalArgumentException.class, () -> AuditLookupFilters.parse(
                "radius.5 after.2026-10-02T12:00:00.100Z before.2026-10-02T12:00:00.101Z", 0L));
        assertThrows(IllegalArgumentException.class, () -> AuditLookupFilters.parse(
                "radius.5 time.1h after.2026-10-02T12:00:00.100Z", 0L));
    }

    @Test
    void rejectsEmptyAndOversizedMetadataPredicates() {
        assertThrows(IllegalArgumentException.class, () -> AuditLookupFilters.parse(
                "radius.5 name.\"\"", 0L));
        assertThrows(IllegalArgumentException.class, () -> AuditLookupFilters.parse(
                "radius.5 component.example:state=" + "[".repeat(33) + "0" + "]".repeat(33), 0L));
    }

    @Test
    void acceptsJsonEscapesInsidePersistentComponentPredicates() {
        AuditLookupFilters filters = AuditLookupFilters.parse(
                "radius.1 component.example:text={\"text\":\"line\\nnext\"}", 0L);

        assertEquals("{\"text\":\"line\\nnext\"}", filters.itemPredicates().getFirst().value());
    }

    @Test
    void preservesExistingRadiusAndRelativeTimeForm() {
        AuditLookupFilters filters = AuditLookupFilters.parse("action.drop_item radius.12.5 time.2h", 500_000L);

        assertEquals(List.of("DROP_ITEM"), filters.eventTypes());
        assertEquals(12.5, filters.radiusBlocks());
        assertEquals(-6_700_000L, filters.window().sinceMs());
        assertEquals(500_000L, filters.window().untilMs());
    }
}
