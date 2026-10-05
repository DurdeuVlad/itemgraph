package com.itemgraph.query;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * An inclusive epoch-millis time range used to bound a multi-row query.
 *
 * <h2>Chosen UX</h2>
 *
 * <p>The command surface exposes the window as <b>relative minutes</b>
 * ({@code /ig trace item <fingerprintId> [limit] [sinceMinutes]}), not as absolute epoch
 * millis. A forensic question is almost always asked in relative terms — "what happened
 * to this in the last hour" — and a human cannot sanity-check a 13-digit epoch value they
 * typed by hand, so an off-by-a-factor-of-1000 typo would silently return an empty result
 * that looks like a real negative finding. Relative minutes fail visibly instead.
 *
 * <p>{@code sinceMinutes} is resolved against wall-clock "now" at query time into
 * {@code [now - minutes, now]}. Omitting it yields {@link #unbounded()}: for a single
 * fingerprint the row count is already hard-capped by the limit (see {@link QueryLimits}),
 * so an unbounded-in-time trace is still a bounded query. The time filter narrows a noisy
 * fingerprint; it is not what makes the query safe.
 *
 * @param sinceMs inclusive lower bound, or null for open-ended
 * @param untilMs inclusive upper bound, or null for open-ended
 */
public record QueryWindow(Long sinceMs, Long untilMs) {

    private static final java.util.regex.Pattern UTC_MILLIS = java.util.regex.Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

    private static final QueryWindow UNBOUNDED = new QueryWindow(null, null);

    /** No time restriction. The caller is responsible for bounding the result some other way. */
    public static QueryWindow unbounded() {
        return UNBOUNDED;
    }

    /**
     * The last {@code minutes} minutes, ending at {@code nowMs}.
     *
     * @param minutes size of the window; values below 1 are treated as 1, and an
     *                unrepresentable duration is clamped to the widest representable range
     * @param nowMs   the instant the window ends at, passed in rather than read from the
     *                clock so callers and tests share one definition of "now"
     */
    public static QueryWindow lastMinutes(long minutes, long nowMs) {
        long clamped = Math.max(1L, minutes);
        boolean durationOverflow = clamped > Long.MAX_VALUE / 60_000L;
        long durationMs = durationOverflow ? Long.MAX_VALUE : clamped * 60_000L;
        long sinceMs;
        if (durationOverflow) {
            sinceMs = nowMs >= 0 ? nowMs - Long.MAX_VALUE : Long.MIN_VALUE;
        } else if (nowMs < Long.MIN_VALUE + durationMs) {
            sinceMs = Long.MIN_VALUE;
        } else {
            sinceMs = nowMs - durationMs;
        }
        return new QueryWindow(sinceMs, nowMs);
    }

    /**
     * Creates an open-ended range strictly after a UTC instant with millisecond precision.
     * Stored event times are integer epoch milliseconds, so adding one millisecond converts
     * the exclusive boundary into the inclusive range represented by this record.
     */
    public static QueryWindow after(String utcInstant) {
        long boundary = parseUtcMillis(utcInstant);
        final long inclusiveStart;
        try {
            inclusiveStart = Math.addExact(boundary, 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("after timestamp leaves an empty time range", overflow);
        }
        return new QueryWindow(inclusiveStart, null);
    }

    /** Creates an open-ended range strictly before a UTC instant with millisecond precision. */
    public static QueryWindow before(String utcInstant) {
        long boundary = parseUtcMillis(utcInstant);
        final long inclusiveEnd;
        try {
            inclusiveEnd = Math.subtractExact(boundary, 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("before timestamp leaves an empty time range", overflow);
        }
        return new QueryWindow(null, inclusiveEnd);
    }

    /** Creates an inclusive range between two UTC instants with millisecond precision. */
    public static QueryWindow between(String startUtcInstant, String endUtcInstant) {
        long start = parseUtcMillis(startUtcInstant);
        long end = parseUtcMillis(endUtcInstant);
        if (start > end) {
            throw new IllegalArgumentException("between start must not be after end");
        }
        return new QueryWindow(start, end);
    }

    private static long parseUtcMillis(String value) {
        if (value == null || !UTC_MILLIS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "timestamp must use ISO-8601 UTC with exactly three fractional digits, for example 2026-10-02T12:34:56.789Z");
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException invalid) {
            throw new IllegalArgumentException("timestamp is not a valid UTC instant", invalid);
        }
    }

    public boolean bounded() {
        return sinceMs != null || untilMs != null;
    }

    /** True if {@code timestampMs} falls inside the window (inclusive on both ends). */
    public boolean contains(long timestampMs) {
        if (sinceMs != null && timestampMs < sinceMs) {
            return false;
        }
        return untilMs == null || timestampMs <= untilMs;
    }

    /**
     * True if the closed interval {@code [startMs, endMs]} overlaps the window at all.
     *
     * <p>Used for inferred edges, which span time rather than happening at an instant. An
     * edge whose drop predates the window but whose pickup falls inside it is genuinely
     * part of what happened during the window, so excluding it would hide a hop from the
     * timeline and make the reconstruction look discontinuous.
     */
    public boolean overlaps(long startMs, long endMs) {
        if (sinceMs != null && endMs < sinceMs) {
            return false;
        }
        return untilMs == null || startMs <= untilMs;
    }

    public String describe() {
        if (!bounded()) {
            return "all time";
        }
        String from = sinceMs != null ? QueryFormatter.formatTime(sinceMs) : "beginning";
        String to = untilMs != null ? QueryFormatter.formatTime(untilMs) : "now";
        String span = "";
        if (sinceMs != null && untilMs != null) {
            long duration;
            try {
                duration = Math.subtractExact(untilMs, sinceMs);
            } catch (ArithmeticException overflow) {
                duration = Long.MAX_VALUE;
            }
            span = String.format(Locale.ROOT, " (%s)", QueryFormatter.formatDuration(duration));
        }
        return from + " -> " + to + span;
    }

    /** Exact inclusive bounds suitable for query logs and reproducible API results. */
    public String normalizedPredicate() {
        return "window[since=" + (sinceMs == null ? "open" : sinceMs)
                + ",until=" + (untilMs == null ? "open" : untilMs) + "]";
    }
}
