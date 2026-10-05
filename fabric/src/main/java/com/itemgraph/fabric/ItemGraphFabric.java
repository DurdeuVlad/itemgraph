package com.itemgraph.fabric;

import com.itemgraph.api.ItemGraphApiLifecycle;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.command.ItemGraphPermissions;
import com.itemgraph.core.port.RuntimeInformationPort;
import com.itemgraph.correlation.CorrelationEngine;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.GriefLoggerAdapter;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.InternalObservationService;
import net.fabricmc.api.ModInitializer;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class ItemGraphFabric implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemGraph");
    private static final FabricLoader FABRIC = FabricLoader.getInstance();
    private static volatile FabricItemGraphConfig config;

    @Override
    public void onInitialize() {
        RuntimeInformationPort runtimeInformation = new RuntimeInformationPort() {
            @Override
            public String modVersion() {
                return FABRIC.getModContainer("itemgraph")
                        .map(container -> container.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown");
            }

            @Override
            public boolean isModLoaded(String modId) {
                return FABRIC.isModLoaded(modId);
            }
        };
        ItemGraphCommands.setRuntimeInformation(runtimeInformation);
        ItemGraphPermissions.setChecker((source, node) -> Permissions.check(source, node, 2));
        ItemGraphApiLifecycle.setRuntimeInformation(runtimeInformation);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ItemGraphCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        ServerTickEvents.END_SERVER_TICK.register(server -> InternalObservationService.getInstance().onServerTick());
        FabricNativeAuditEventListener.register();
        LOGGER.info("ItemGraph Fabric adapter initialized for Minecraft 1.21.1");
    }

    private void onServerStarting(MinecraftServer server) {
        try {
            config = FabricItemGraphConfig.load(FABRIC.getGameDir(), FABRIC.getConfigDir());
            com.itemgraph.command.CommandHelp.initializeMessages();
            com.itemgraph.i18n.ItemGraphLanguage.setLocale(config.language());
        } catch (IOException e) {
            throw new IllegalStateException("Could not load config/itemgraph.properties", e);
        }
        ItemCanonicalizer.setRegistryAccess(server.registryAccess());
        config.operationalSettings().apply();
        CorrelationEngine.setDefaultWindowSeconds(config.groundBridgeMaxSeconds());
        DatabaseManager.getInstance().prepareSettings(config.databaseSettings());
        // World-generation work can submit evidence while database setup is
        // in progress. Open the bounded queue first; the worker retries until
        // the database becomes available and persists accepted startup events.
        InternalObservationService.getInstance().start();
        DatabaseManager.getInstance().initialize(config.databaseSettings());
        IngestionService.getInstance().setAdapter(new GriefLoggerAdapter(
                config.griefLoggerDatabasePath(), config.griefLoggerIntegrationEnabled()));
        IngestionService.getInstance().start();
        ItemGraphApiLifecycle.start(server);
    }

    private void onServerStopping(MinecraftServer server) {
        ItemGraphCommands.clearPageSessions();
        ItemGraphApiLifecycle.stop(server);
        FabricContainerSessionListener.flushAll();
        com.itemgraph.command.InspectionService.getInstance().clear();
        // Wait for the importer to terminate before closing the database it writes to.
        IngestionService.getInstance().stop();
        com.itemgraph.command.QueryDispatcher.shutdown();
    }

    private void onServerStopped(MinecraftServer server) {
        InternalObservationService.getInstance().stop();
        DatabaseManager.getInstance().close();
    }
}
