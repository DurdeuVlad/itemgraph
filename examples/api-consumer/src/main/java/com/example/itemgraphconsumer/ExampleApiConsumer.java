package com.example.itemgraphconsumer;

import com.itemgraph.api.DirectObservation;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemGraphApi;
import com.itemgraph.api.ItemQuery;
import com.itemgraph.api.ItemSnapshot;
import com.itemgraph.api.ObservationAction;
import com.itemgraph.api.QueryOptions;
import com.itemgraph.api.SourceRegistration;
import com.itemgraph.api.WorldEndpoint;
import com.itemgraph.api.EndpointKind;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ExampleApiConsumer.MOD_ID)
public final class ExampleApiConsumer {
    public static final String MOD_ID = "itemgraph_api_consumer";
    private static final Logger LOGGER = LoggerFactory.getLogger(ExampleApiConsumer.class);
    private static final long STABLE_FIXTURE_EVENT_ID = 1L;

    public ExampleApiConsumer(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ItemGraphApi.get(server).ifPresentOrElse(
                service -> service.registerSource(
                                SourceRegistration.of(MOD_ID, "ItemGraph API Consumer Example"))
                        .thenCompose(registration -> {
                            if (registration.source() == null) {
                                return CompletableFuture.completedFuture(registration.status().name());
                            }
                            DirectObservation observation = new DirectObservation(
                                    STABLE_FIXTURE_EVENT_ID,
                                    System.currentTimeMillis(),
                                    ObservationAction.TRANSFER_ITEM,
                                    new ExternalInventoryEndpoint(
                                            MOD_ID, "fixture-inventory", "API fixture inventory", null),
                                    new WorldEndpoint(
                                            EndpointKind.CONTAINER,
                                            Level.OVERWORLD,
                                            new BlockPos(0, 64, 0),
                                            "API fixture container"),
                                    ItemSnapshot.of("minecraft:diamond", 1, "API Fixture Diamond",
                                            Map.of()),
                                    null,
                                    null,
                                    Map.of("fixture", "server-started"));
                            return service.submitObservation(registration.source(), observation)
                                    .thenApply(submission -> submission.status().name());
                        })
                        .thenCompose(status -> service.traceItem(
                                ItemQuery.itemId("minecraft:diamond"),
                                new QueryOptions(10, 60L))
                                .thenApply(query -> status + " / query=" + query.status()))
                        .thenAccept(status -> server.execute(() ->
                                LOGGER.info("ItemGraph API fixture completed: {}", status)))
                        .exceptionally(error -> {
                            LOGGER.error("ItemGraph API fixture failed", error);
                            return null;
                        }),
                () -> LOGGER.info("ItemGraph service is not available"));
    }
}
