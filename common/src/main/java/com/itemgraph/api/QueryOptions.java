package com.itemgraph.api;

public record QueryOptions(
        int limit,
        Long sinceMinutes,
        TimeWindow absoluteWindow) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public static QueryOptions defaults() {
        return new QueryOptions(DEFAULT_LIMIT, null, null);
    }

    public QueryOptions(int limit, Long sinceMinutes) {
        this(limit, sinceMinutes, null);
    }

    public String normalizedTimePredicate() {
        if (absoluteWindow != null) {
            return "window[since=" + endpoint(absoluteWindow.sinceMs())
                    + ",until=" + endpoint(absoluteWindow.untilMs()) + "]";
        }
        return sinceMinutes == null ? "time.all" : "time." + sinceMinutes + "m";
    }

    private static String endpoint(Long epochMillis) {
        return epochMillis == null ? "open" : epochMillis.toString();
    }
}
