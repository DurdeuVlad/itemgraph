package com.itemgraph.core.port;

/** Loader-independent facts shown by ItemGraph's status command and API validation. */
public interface RuntimeInformationPort {
    String modVersion();

    boolean isModLoaded(String modId);
}
