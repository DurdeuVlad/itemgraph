package com.itemgraph.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;

/**
 * Server-lifecycle holder for the preview service. This class is mod-lifecycle
 * infrastructure, not a consumer extension point.
 */
public final class ItemGraphApiLifecycle {
    private static final AtomicLong GENERATIONS = new AtomicLong();
    private static volatile ItemGraphServiceImpl service;
    private static volatile boolean accepting;

    private ItemGraphApiLifecycle() {}

    public static synchronized void start(MinecraftServer server) {
        stop(service);
        ItemGraphServiceImpl created = new ItemGraphServiceImpl(server, GENERATIONS.incrementAndGet());
        created.start();
        service = created;
        accepting = true;
    }

    public static synchronized void stop(MinecraftServer server) {
        ItemGraphServiceImpl current = service;
        if (current == null || current.server() != server) {
            return;
        }
        accepting = false;
        current.shutdown();
    }

    static synchronized Optional<ItemGraphService> serviceFor(MinecraftServer server) {
        ItemGraphServiceImpl current = service;
        if (!accepting || current == null || server == null || current.server() != server) {
            return Optional.empty();
        }
        return Optional.of(current);
    }

    private static void stop(ItemGraphServiceImpl current) {
        if (current != null) {
            accepting = false;
            current.shutdown();
        }
        service = null;
    }
}
