package com.itemgraph.query;

/**
 * Hard bounds shared by every multi-row ItemGraph query.
 *
 * <p>The engineering rules forbid unbounded database scans, and the query model doc adds
 * "never dump an unbounded result set" into a chat window. Both are enforced in one
 * place: {@link #clampLimit(int)} is applied to whatever the caller asked for <em>before</em>
 * it reaches SQL, so a requested limit of 500 becomes {@value #MAX_LIMIT} rather than
 * being rejected with an error. Capping rather than refusing is deliberate — an admin
 * chasing an incident gets the first page of real output plus an explicit "truncated"
 * marker, instead of a usage message and no data.
 */
public final class QueryLimits {

    /** Rows returned when the caller does not specify a limit. Fits a chat window. */
    public static final int DEFAULT_LIMIT = 20;

    /** Absolute ceiling, applied even if the caller explicitly asks for more. */
    public static final int MAX_LIMIT = 100;

    public static final int MAX_GUI_PAGE_SIZE = 45;

    /**
     * Ceiling on evidence rows resolved for a single {@code /ig explain}.
     *
     * <p>Ground bridges currently cite exactly two observations, so this only matters if a
     * future correlation pattern cites many — in which case the command must still not
     * turn into an unbounded query.
     */
    public static final int MAX_EVIDENCE_ROWS = 50;

    private QueryLimits() {}

    /** Clamps a requested limit into {@code [1, MAX_LIMIT]}. */
    public static int clampLimit(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
