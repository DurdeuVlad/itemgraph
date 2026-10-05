package com.itemgraph.api;

/** Version marker for the preview Java API. */
public enum ApiVersion {
    PREVIEW_1,
    /** Adds explicit evidence class, reason, candidate, and quantity-impact fields to flow results. */
    PREVIEW_2,
    /** Adds canonical item metadata selectors and absolute UTC query windows. */
    PREVIEW_3;

    public int number() {
        return switch (this) {
            case PREVIEW_1 -> 1;
            case PREVIEW_2 -> 2;
            case PREVIEW_3 -> 3;
        };
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
