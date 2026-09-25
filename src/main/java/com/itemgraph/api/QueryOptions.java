package com.itemgraph.api;

public record QueryOptions(
        int limit,
        Long sinceMinutes) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public static QueryOptions defaults() {
        return new QueryOptions(DEFAULT_LIMIT, null);
    }
}
