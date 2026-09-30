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
    public static final int DEFAULT_LIMIT = 10;

    /** Absolute ceiling, applied even if the caller explicitly asks for more. */
    public static final int MAX_LIMIT = 100;

    private static volatile int configuredMaxLimit = MAX_LIMIT;

    /** Maximum page offset accepted by a bounded historical lookup. */
    public static final int MAX_OFFSET = 10_000;

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

    /** Applies the startup-loaded operator page cap to every query surface. */
    public static void configureMaxPageSize(int maxPageSize) {
        if (maxPageSize < 1 || maxPageSize > MAX_LIMIT) {
            throw new IllegalArgumentException("query.max_page_size must be in [1,100]");
        }
        configuredMaxLimit = maxPageSize;
    }

    public static int getConfiguredMaxPageSize() {
        return configuredMaxLimit;
    }

    /** Clamps a requested limit into {@code [1, configuredMaxLimit]}. */
    public static int clampLimit(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, configuredMaxLimit);
    }

    /** Clamps trace/browser pages by both the configured operator cap and menu limit. */
    public static int clampGuiPageSize(int requested) {
        return Math.max(1, Math.min(Math.min(MAX_GUI_PAGE_SIZE, configuredMaxLimit), requested));
    }

    public static int clampOffset(int requested) {
        return Math.max(0, Math.min(requested, MAX_OFFSET));
    }

    /**
     * Computes a bounded, page-aligned SQL offset for a one-based page request.
     * Aligning after the absolute offset cap keeps the reported effective page
     * consistent with the first row returned by the query.
     */
    public static int clampPageOffset(int requestedPage, int requestedLimit) {
        int limit = clampLimit(requestedLimit);
        int page = Math.max(1, requestedPage);
        long requestedOffset = ((long) page - 1L) * limit;
        int boundedOffset = clampOffset(requestedOffset > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) requestedOffset);
        return boundedOffset - boundedOffset % limit;
    }
}
