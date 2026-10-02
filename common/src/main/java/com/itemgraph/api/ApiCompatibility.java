package com.itemgraph.api;

/** Result of negotiating a consumer's required preview API number. */
public record ApiCompatibility(
        int requiredVersion,
        int runtimeVersion,
        Status status,
        String message) {

    public enum Status {
        COMPATIBLE,
        INCOMPATIBLE
    }

    public boolean compatible() {
        return status == Status.COMPATIBLE;
    }

    static ApiCompatibility negotiate(int requiredVersion, ApiVersion runtimeVersion) {
        if (requiredVersion <= 0) {
            return new ApiCompatibility(requiredVersion, runtimeVersion.number(), Status.INCOMPATIBLE,
                    "Required ItemGraph API version must be positive; received " + requiredVersion);
        }
        int current = runtimeVersion.number();
        if (requiredVersion == current) {
            return new ApiCompatibility(requiredVersion, current, Status.COMPATIBLE,
                    "ItemGraph API preview-" + current + " is compatible");
        }
        return new ApiCompatibility(requiredVersion, current, Status.INCOMPATIBLE,
                "ItemGraph API version mismatch: consumer requires preview-" + requiredVersion
                        + " but runtime provides preview-" + current);
    }
}
