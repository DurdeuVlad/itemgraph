package com.itemgraph.query;

import java.util.Objects;

public record TraceCursor(
        long timestampMs,
        TraceHop.Kind kind,
        long refId,
        TraceHop.Source source
) {
    public TraceCursor {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(source, "source");
    }

    public static TraceCursor after(TraceHop hop) {
        return new TraceCursor(hop.timestampMs(), hop.kind(), hop.refId(), hop.source());
    }
}
