package com.itemgraph.api;

import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Entry point for the preview ItemGraph Java API. */
public final class ItemGraphApi {
    public static final ApiVersion API_VERSION = ApiVersion.PREVIEW_1;

    private ItemGraphApi() {}

    /** Service for the server returned by ServerLifecycleHooks.getCurrentServer(). */
    public static Optional<ItemGraphService> current() {
        return get(ServerLifecycleHooks.getCurrentServer());
    }

    /** Service for a known server instance. */
    public static Optional<ItemGraphService> get(MinecraftServer server) {
        return ItemGraphApiLifecycle.serviceFor(server);
    }
}
