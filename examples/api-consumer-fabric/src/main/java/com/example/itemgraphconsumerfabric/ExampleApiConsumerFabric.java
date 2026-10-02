package com.example.itemgraphconsumerfabric;

import com.itemgraph.api.ApiCompatibility;
import com.itemgraph.api.DirectObservation;
import com.itemgraph.api.EndpointKind;
import com.itemgraph.api.ExternalInventoryEndpoint;
import com.itemgraph.api.ItemGraphApi;
import com.itemgraph.api.ItemQuery;
import com.itemgraph.api.ItemSnapshot;
import com.itemgraph.api.ObservationAction;
import com.itemgraph.api.QueryOptions;
import com.itemgraph.api.QueryStatus;
import com.itemgraph.api.RegistrationStatus;
import com.itemgraph.api.SourceRegistration;
import com.itemgraph.api.SubmissionStatus;
import com.itemgraph.api.WorldEndpoint;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Test consumer that exercises only ItemGraph's public API on Fabric. */
public final class ExampleApiConsumerFabric implements ModInitializer {
    public static final String MOD_ID = "itemgraph_api_consumer_fabric";
    private static final Logger LOGGER = LoggerFactory.getLogger(ExampleApiConsumerFabric.class);
    private static final int REQUIRED_ITEMGRAPH_API_VERSION = 1;
    private static final long STABLE_FIXTURE_EVENT_ID = 1L;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
    }

    private void onServerStarted(MinecraftServer server) {
        ApiCompatibility compatibility = ItemGraphApi.negotiate(REQUIRED_ITEMGRAPH_API_VERSION);
        if (!compatibility.compatible()) {
            finish(server, null, new IllegalStateException(compatibility.message()));
            return;
        }

        ItemGraphApi.get(server).ifPresentOrElse(
                service -> service.registerSource(
                                SourceRegistration.of(MOD_ID, "ItemGraph Fabric API Consumer Example"))
                        .thenCompose(registration -> {
                            if (registration.source() == null) {
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "source registration returned " + registration.status()));
                            }
                            if (registration.status() != RegistrationStatus.REGISTERED
                                    && registration.status() != RegistrationStatus.UNCHANGED
                                    && registration.status() != RegistrationStatus.UPDATED) {
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "source registration returned " + registration.status()));
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
                                    ItemSnapshot.of("minecraft:diamond", 1, "API Fixture Diamond", Map.of()),
                                    null,
                                    null,
                                    Map.of("fixture", "server-started"));
                            return service.submitObservation(registration.source(), observation)
                                    .thenApply(submission -> {
                                        if (submission.status() != SubmissionStatus.PERSISTED) {
                                            throw new IllegalStateException(
                                                    "fixture evidence returned " + submission.status());
                                        }
                                        return registration.source().modId();
                                    });
                        })
                        .thenCompose(sourceId -> service.traceItem(
                                ItemQuery.itemId("minecraft:diamond"), new QueryOptions(10, 60L))
                                .thenApply(query -> {
                                    if (query.status() != QueryStatus.OK) {
                                        throw new IllegalStateException(
                                                "fixture query returned " + query.status());
                                    }
                                    return "source=" + sourceId + ", event=" + STABLE_FIXTURE_EVENT_ID
                                            + ", query=" + query.status();
                                }))
                        .whenComplete((result, failure) -> finish(server, result, failure)),
                () -> finish(server, null, new IllegalStateException("ItemGraph service unavailable")));
    }

    private void finish(MinecraftServer server, String result, Throwable failure) {
        server.execute(() -> {
            if (failure != null) {
                LOGGER.error("ItemGraph Fabric API fixture failed: {}", failure.toString());
            } else {
                LOGGER.info("ItemGraph Fabric API fixture completed: {}", result);
            }
            String outcome = failure == null ? "PASS: " + result : "FAIL: " + failure;
            String resultPath = System.getProperty("itemgraph.api.consumer.result");
            if (resultPath != null) {
                try {
                    Path path = Path.of(resultPath);
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, outcome, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Could not write ItemGraph Fabric API fixture result", exception);
                }
            }
            server.halt(false);
        });
    }
}
