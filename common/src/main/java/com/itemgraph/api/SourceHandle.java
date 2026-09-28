package com.itemgraph.api;

/** Service-issued source capability. Consumers cannot construct or subclass it. */
public final class SourceHandle {
    private final String modId;
    private final String displayName;
    private final ApiVersion apiVersion;
    private final long serviceGeneration;

    SourceHandle(String modId, String displayName, ApiVersion apiVersion, long serviceGeneration) {
        this.modId = modId;
        this.displayName = displayName;
        this.apiVersion = apiVersion;
        this.serviceGeneration = serviceGeneration;
    }

    public String modId() {
        return modId;
    }

    public String displayName() {
        return displayName;
    }

    public ApiVersion apiVersion() {
        return apiVersion;
    }

    long serviceGeneration() {
        return serviceGeneration;
    }
}
