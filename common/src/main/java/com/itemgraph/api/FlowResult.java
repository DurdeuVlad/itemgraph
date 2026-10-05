package com.itemgraph.api;

import java.util.List;

public record FlowResult(
        String targetDescription,
        List<ItemDescriptor> itemCandidates,
        List<EndpointDescriptor> endpointCandidates,
        List<FlowHop> hops,
        TimeWindow window,
        int requestedLimit,
        int appliedLimit,
        boolean truncated,
        String normalizedPredicate,
        boolean metadataMatchUnconfirmed) {

    public FlowResult {
        itemCandidates = itemCandidates == null ? List.of() : List.copyOf(itemCandidates);
        endpointCandidates = endpointCandidates == null ? List.of() : List.copyOf(endpointCandidates);
        hops = hops == null ? List.of() : List.copyOf(hops);
    }

    public FlowResult(String targetDescription, List<ItemDescriptor> itemCandidates,
                      List<EndpointDescriptor> endpointCandidates, List<FlowHop> hops,
                      TimeWindow window, int requestedLimit, int appliedLimit, boolean truncated) {
        this(targetDescription, itemCandidates, endpointCandidates, hops, window,
                requestedLimit, appliedLimit, truncated, null, false);
    }

    public FlowResult(String targetDescription, List<ItemDescriptor> itemCandidates,
                      List<EndpointDescriptor> endpointCandidates, List<FlowHop> hops,
                      TimeWindow window, int requestedLimit, int appliedLimit, boolean truncated,
                      String normalizedPredicate) {
        this(targetDescription, itemCandidates, endpointCandidates, hops, window,
                requestedLimit, appliedLimit, truncated, normalizedPredicate, false);
    }
}
