package com.itemgraph.query;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryLimitsTest {

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
