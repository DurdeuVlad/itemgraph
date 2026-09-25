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
        boolean truncated) {

    public FlowResult {
        itemCandidates = itemCandidates == null ? List.of() : List.copyOf(itemCandidates);
        endpointCandidates = endpointCandidates == null ? List.of() : List.copyOf(endpointCandidates);
        hops = hops == null ? List.of() : List.copyOf(hops);
    }
}
