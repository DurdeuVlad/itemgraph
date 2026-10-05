package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemMetadataSqlTest {

    @Test
    void exactComponentPredicateIsBoundAndRetainsLegacyUnknownCandidates() {
        ItemMetadataPredicate predicate = AuditLookupFilters.parse(
                "radius.1 component.example:relic={\"charge\":2}", 0L).itemPredicates().getFirst();
        StringBuilder sql = new StringBuilder("SELECT id FROM ig_item_fingerprints f WHERE 1=1");
        List<Object> args = new ArrayList<>();

        ItemMetadataSql.append(sql, args, List.of(predicate), "f.id", "f.item_id", "f.fingerprint_hash");

        assertTrue(sql.toString().contains("EXISTS (SELECT 1 FROM ig_fingerprint_components c"));
        assertTrue(sql.toString().contains("component_index_state"));
        assertTrue(sql.toString().contains("<> 'COMPLETE'"));
        assertTrue(sql.toString().contains("c.canonical_value = ?"));
        assertEquals(3, args.size());
        assertEquals("example:relic", args.get(0));
        assertEquals(predicate.indexedValue(), args.get(2));
    }

    @Test
    void transformationPredicateRequiresEitherTheSourceOrResultFingerprint() {
        ItemMetadataPredicate predicate = AuditLookupFilters.parse(
                "radius.1 damage.8", 0L).itemPredicates().getFirst();
        StringBuilder sql = new StringBuilder("SELECT id FROM ig_item_transformations t WHERE 1=1");
        List<Object> args = new ArrayList<>();

        ItemMetadataSql.appendEither(sql, args, List.of(predicate),
                "src.id", "src.item_id", "src.fingerprint_hash",
                "dst.id", "dst.item_id", "dst.fingerprint_hash");

        assertTrue(sql.toString().contains("src.id"));
        assertTrue(sql.toString().contains("dst.id"));
        assertTrue(sql.toString().contains(" OR "));
        assertEquals(6, args.size());
    }

    @Test
    void customNamePredicateTargetsCanonicalPlainTextAlias() {
        ItemMetadataPredicate predicate = AuditLookupFilters.parse(
                "radius.1 name.\"Named relic\"", 0L).itemPredicates().getFirst();
        StringBuilder sql = new StringBuilder("SELECT id FROM ig_item_fingerprints f WHERE 1=1");
        List<Object> args = new ArrayList<>();

        ItemMetadataSql.append(sql, args, List.of(predicate), "f.id", "f.item_id", "f.fingerprint_hash");

        assertEquals("minecraft:custom_name#plain_text", predicate.key());
        assertEquals("minecraft:custom_name#plain_text", args.get(0));
        assertTrue(sql.toString().contains("c.component_id = ?"));
    }
}
