package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryLimitsTest {

    @Test
    void configuredPageSizeCapsEverySharedQueryLimitAndRejectsInvalidValues() {
        int original = QueryLimits.getConfiguredMaxPageSize();
        try {
            QueryLimits.configureMaxPageSize(10);
            assertEquals(10, QueryLimits.clampLimit(20));
            assertEquals(1, QueryLimits.clampLimit(0));
            assertEquals(30, QueryLimits.clampPageOffset(4, 20));
            assertEquals(10, QueryLimits.clampGuiPageSize(45));
            assertThrows(IllegalArgumentException.class, () -> QueryLimits.configureMaxPageSize(0));
            assertThrows(IllegalArgumentException.class, () -> QueryLimits.configureMaxPageSize(101));
        } finally {
            QueryLimits.configureMaxPageSize(original);
        }
    }

    @Test
    void pageOffsetRemainsAlignedWhenAbsoluteCapCutsThroughPage() {
        assertEquals(9_990, QueryLimits.clampPageOffset(500, 30));
        assertEquals(334, QueryLimits.clampPageOffset(500, 30) / 30 + 1);
    }

    @Test
    void pageOffsetClampsInvalidPagesAndPreservesFirstPage() {
        assertEquals(0, QueryLimits.clampPageOffset(0, 20));
        assertEquals(0, QueryLimits.clampPageOffset(-10, 20));
        assertEquals(10_000, QueryLimits.clampPageOffset(Integer.MAX_VALUE, 100));
    }
}
