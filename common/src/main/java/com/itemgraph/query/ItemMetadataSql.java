package com.itemgraph.query;

import com.itemgraph.canon.ItemCanonicalizer;

import java.util.List;

/** SQL predicate builder for exact matches against ItemGraph's normalized fingerprint index. */
public final class ItemMetadataSql {
    private ItemMetadataSql() {}

    public static void append(StringBuilder sql, List<Object> args,
                       List<ItemMetadataPredicate> predicates,
                       String fingerprintIdExpression,
                       String itemIdExpression,
                       String fingerprintHashExpression) {
        for (ItemMetadataPredicate predicate : predicates) {
            sql.append(" AND ");
            appendPredicate(sql, args, predicate, fingerprintIdExpression,
                    itemIdExpression, fingerprintHashExpression);
        }
    }

    public static void appendEither(StringBuilder sql, List<Object> args,
                             List<ItemMetadataPredicate> predicates,
                             String leftFingerprintId, String leftItemId, String leftFingerprintHash,
                             String rightFingerprintId, String rightItemId, String rightFingerprintHash) {
        if (predicates.isEmpty()) {
            return;
        }
        sql.append(" AND ((1 = 1");
        append(sql, args, predicates, leftFingerprintId, leftItemId, leftFingerprintHash);
        sql.append(") OR (1 = 1");
        append(sql, args, predicates, rightFingerprintId, rightItemId, rightFingerprintHash);
        sql.append("))");
    }

    private static void appendPredicate(StringBuilder sql, List<Object> args,
                                        ItemMetadataPredicate predicate,
                                        String fingerprintIdExpression,
                                        String itemIdExpression,
                                        String fingerprintHashExpression) {
        switch (predicate.kind()) {
            case ITEM_ID -> {
                sql.append(itemIdExpression).append(" = ?");
                args.add(predicate.value());
            }
            case FINGERPRINT -> {
                sql.append(fingerprintHashExpression).append(" = ?");
                args.add(predicate.value());
            }
            default -> {
                sql.append("(EXISTS (SELECT 1 FROM ig_fingerprint_components c WHERE c.fingerprint_id = ")
                        .append(fingerprintIdExpression);
                if (predicate.kind() == ItemMetadataPredicate.Kind.LORE) {
                    sql.append(" AND c.component_id LIKE ?");
                    args.add(predicate.key() + "/%");
                } else {
                    sql.append(" AND c.component_id = ?");
                    args.add(predicate.key());
                }
                String indexedValue = predicate.indexedValue();
                if (indexedValue != null) {
                    sql.append(" AND c.value_hash = ? AND c.canonical_value = ?");
                    args.add(ItemCanonicalizer.sha256Hex(indexedValue));
                    args.add(indexedValue);
                }
                sql.append(')');
                sql.append(" OR COALESCE((SELECT fpc.component_index_state FROM ig_item_fingerprints fpc "
                        + "WHERE fpc.id = " + fingerprintIdExpression + "), 'LEGACY_UNKNOWN') <> 'COMPLETE'");
                sql.append(')');
            }
        }
    }
}
