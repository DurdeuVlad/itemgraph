package com.itemgraph.query;

import java.util.List;
import java.util.Objects;

public record TracePage(
        String targetDescription,
        Resolution resolution,
        FingerprintRef fingerprint,
        NodeRef targetNode,
        List<FingerprintRef> candidates,
        List<NodeRef> nodeCandidates,
        List<TraceHop> hops,
        QueryWindow window,
        int pageSize,
        TraceCursor previousCursor,
        boolean hasPrevious,
        TraceCursor nextCursor,
        boolean hasNext
) {
    public enum Direction {
        FORWARD,
        BACKWARD
    }

    public enum Resolution {
        NOT_FOUND,
        RESOLVED,
        AMBIGUOUS
    }

    public TracePage {
        Objects.requireNonNull(targetDescription, "targetDescription");
        Objects.requireNonNull(resolution, "resolution");
        candidates = List.copyOf(candidates);
        nodeCandidates = List.copyOf(nodeCandidates);
        hops = List.copyOf(hops);
        Objects.requireNonNull(window, "window");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        if (hasPrevious && previousCursor == null) {
            throw new IllegalArgumentException("a page with a previous page must carry its cursor");
        }
        if (hasNext && nextCursor == null) {
            throw new IllegalArgumentException("a page with a next page must carry its cursor");
        }
    }
}
