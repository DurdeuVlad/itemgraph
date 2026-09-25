package com.itemgraph.api;

/** Version marker for the preview Java API. */
public enum ApiVersion {
    PREVIEW_1;

    public int number() {
        return 1;
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
