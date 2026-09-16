package com.itemgraph.query;

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

    private static final QueryWindow UNBOUNDED = new QueryWindow(null, null);

    /** No time restriction. The caller is responsible for bounding the result some other way. */
    public static QueryWindow unbounded() {
        return UNBOUNDED;
    }

    /**
     * The last {@code minutes} minutes, ending at {@code nowMs}.
     *
     * @param minutes size of the window; values below 1 are treated as 1
     * @param nowMs   the instant the window ends at, passed in rather than read from the
     *                clock so callers and tests share one definition of "now"
     */
    public static QueryWindow lastMinutes(long minutes, long nowMs) {
        long clamped = Math.max(1L, minutes);
        return new QueryWindow(nowMs - clamped * 60_000L, nowMs);
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
        String span = (sinceMs != null && untilMs != null)
                ? String.format(Locale.ROOT, " (%s)", QueryFormatter.formatDuration(untilMs - sinceMs))
                : "";
        return from + " -> " + to + span;
    }
}
