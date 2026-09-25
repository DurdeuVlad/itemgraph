package com.itemgraph.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record DirectObservation(
        long sourceEventId,
        long timestampMs,
        ObservationAction action,
        EndpointRef origin,
        EndpointRef destination,
        ItemSnapshot item,
        UUID itemEntityUuid,
        Long timestampEndMs,
        Map<String, String> attributes) {

    public DirectObservation {
        if (attributes != null) {
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }
}
