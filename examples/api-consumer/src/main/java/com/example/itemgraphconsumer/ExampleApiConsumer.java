package com.example.itemgraphconsumer;

import com.itemgraph.api.ApiCompatibility;
import com.itemgraph.api.DirectObservation;
import com.itemgraph.api.RegistrationStatus;
import com.itemgraph.api.SubmissionStatus;
import com.itemgraph.api.QueryStatus;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
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
    private static final int REQUIRED_ITEMGRAPH_API_VERSION = 2;
    private static final long STABLE_FIXTURE_EVENT_ID = 1L;

    public ExampleApiConsumer(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
    }

    private void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        ApiCompatibility compatibility = ItemGraphApi.negotiate(REQUIRED_ITEMGRAPH_API_VERSION);
        if (!compatibility.compatible()) {
            finish(server, false, compatibility.message());
            return;
        }
        ItemGraphApi.get(server).ifPresentOrElse(
                service -> service.registerSource(
                                SourceRegistration.of(MOD_ID, "ItemGraph API Consumer Example"))
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
                                    ItemSnapshot.of("minecraft:diamond", 1, "API Fixture Diamond",
                                            Map.of()),
                                    null,
                                    null,
                                    Map.of("fixture", "server-started"));
                            return service.submitObservation(registration.source(), observation);
                        })
                        .thenCompose(submission -> {
                            if (submission.status() != SubmissionStatus.PERSISTED) {
                                return CompletableFuture.failedFuture(new IllegalStateException(
                                        "fixture evidence returned " + submission.status()));
                            }
                            return service.traceItem(
                                ItemQuery.itemId("minecraft:diamond"),
                                new QueryOptions(10, 60L))
                                    .thenApply(query -> {
                                        if (query.status() != QueryStatus.OK) {
                                            throw new IllegalStateException(
                                                    "fixture query returned " + query.status());
                                        }
                                        var fixtureHop = query.result().hops().stream()
                                                .filter(hop -> hop.evidenceClass()
                                                        == com.itemgraph.audit.EventTaxonomy.EvidenceClass.OBSERVED)
                                                .findFirst().orElseThrow(() -> new IllegalStateException(
                                                        "fixture query returned no observed API hop"));
                                        if (fixtureHop.quantityImpact() != fixtureHop.amount()
                                                || fixtureHop.reasonCode() != null
                                                || !fixtureHop.candidateEvidenceIds().isEmpty()
                                                || fixtureHop.candidateEvidenceTruncated()) {
                                            throw new IllegalStateException(
                                                    "observed fixture hop has invalid PREVIEW_2 state fields");
                                        }
                                        return "source=" + registration.source().modId()
                                                + ", event=" + STABLE_FIXTURE_EVENT_ID
                                                + ", submission=" + submission.status()
                                                + ", query=" + query.status();
                                    });
                        })
                        .whenComplete((result, failure) -> finish(server, failure == null,
                                failure == null ? result : failure.toString())),
                () -> finish(server, false, "ItemGraph service is not available"));
    }

    private void finish(MinecraftServer server, boolean successful, String detail) {
        server.execute(() -> {
            String outcome = (successful ? "PASS: " : "FAIL: ") + detail;
            LOGGER.info("ItemGraph NeoForge API fixture {}", outcome);
            String resultPath = System.getProperty("itemgraph.api.consumer.result");
            if (resultPath != null) {
                try {
                    Path path = Path.of(resultPath);
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, outcome, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Could not write ItemGraph API fixture result", exception);
                }
            }
            server.halt(false);
        });
    }
}
