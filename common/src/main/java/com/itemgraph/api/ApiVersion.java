package com.itemgraph.api;

/** Version marker for the preview Java API. */
public enum ApiVersion {
    PREVIEW_1,
    /** Adds explicit evidence class, reason, candidate, and quantity-impact fields to flow results. */
    PREVIEW_2;

    public int number() {
        return this == PREVIEW_1 ? 1 : 2;
    }

    public String channel() {
        return "preview";
    }

    public boolean preview() {
        return true;
    }

    public String label() {
        return channel() + "-" + number();
    }
}
