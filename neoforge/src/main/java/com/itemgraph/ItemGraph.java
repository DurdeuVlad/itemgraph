package com.itemgraph;

import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.config.ItemGraphConfig;
import com.itemgraph.canon.ItemCanonicalizer;
import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.core.port.RuntimeInformationPort;
import com.itemgraph.correlation.CorrelationEngine;
import com.itemgraph.db.DatabaseManager;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(ItemGraph.MOD_ID)
public class ItemGraph {
    public static final String MOD_ID = "itemgraph";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemGraph(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("ItemGraph initializing...");
        modContainer.registerConfig(ModConfig.Type.SERVER, ItemGraphConfig.SPEC);
        RuntimeInformationPort runtimeInformation = new RuntimeInformationPort() {
            @Override
            public String modVersion() {
                return modContainer.getModInfo().getVersion().toString();
            }

            @Override
            public boolean isModLoaded(String modId) {
                return net.neoforged.fml.ModList.get().isLoaded(modId);
            }
        };
        ItemGraphCommands.setRuntimeInformation(runtimeInformation);
        com.itemgraph.api.ItemGraphApiLifecycle.setRuntimeInformation(runtimeInformation);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);

        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.ItemEntityEventListener());
        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.TransformationEventListener());
        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.NativeAuditEventListener());
        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.NativeItemActionEventListener());

        // Container capability wrapper: intercepts IItemHandler insertItem/extractItem on all
        // vanilla container block entities for automated transfer observation.
        // Must register on the mod event bus (RegisterCapabilitiesEvent fires on mod bus).
        modEventBus.register(new com.itemgraph.listener.ContainerCapabilityRegistrar());
        // Command-toggled inspector: cancels supported container clicks at HIGHEST
        // priority so the inspection request itself never creates a session watch.
        NeoForge.EVENT_BUS.register(new com.itemgraph.command.InspectionListener());
        // Container session listener: binds PlayerContainerEvent open/close to
        // ContainerInteractionTracker watches so player-driven transfers are observed
        // as interval-bounded session net deltas (GUI clicks never traverse IItemHandler).
        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.ContainerSessionListener());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ItemGraphCommands.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        ItemCanonicalizer.setRegistryAccess(event.getServer().registryAccess());
        var operationalSettings = ItemGraphConfig.operationalSettings();
        var databaseSettings = ItemGraphConfig.databaseSettings();
        var griefLoggerIntegrationEnabled = ItemGraphConfig.griefLoggerIntegrationEnabled();
        var griefLoggerDatabasePath = ItemGraphConfig.griefLoggerDatabasePath();
        operationalSettings.apply();
        CorrelationEngine.setDefaultWindowSeconds(ItemGraphConfig.GROUND_BRIDGE_MAX_SECONDS.get());
        DatabaseManager.getInstance().initialize(databaseSettings);
        com.itemgraph.ingest.InternalObservationService.getInstance().start();
        com.itemgraph.ingest.IngestionService.getInstance().setAdapter(
                new com.itemgraph.ingest.GriefLoggerAdapter(griefLoggerDatabasePath, griefLoggerIntegrationEnabled));
        com.itemgraph.ingest.IngestionService.getInstance().start();
        com.itemgraph.api.ItemGraphApiLifecycle.start(event.getServer());
        LOGGER.info("GriefLogger source integration: {}", griefLoggerIntegrationEnabled
                ? "ENABLED (read-only migration sync/import)"
                : "DISABLED by default; ItemGraph using native capture and owned storage");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ItemGraphCommands.clearPageSessions();
        com.itemgraph.api.ItemGraphApiLifecycle.stop(event.getServer());
        com.itemgraph.listener.ContainerInteractionTracker.getInstance().closeAllSessions();
        com.itemgraph.listener.EnderChestInteractionTracker.getInstance().closeAllSessions();
        com.itemgraph.command.InspectionService.getInstance().clear();
        // Wait for the importer to terminate before closing the database it writes to.
        com.itemgraph.ingest.IngestionService.getInstance().stop();
        // Stop the query worker before closing the database, so an in-flight /ig trace
        // cannot be reading through a connection that is about to disappear. Keep the
        // observation queue and database available through disconnect callbacks that
        // run during orderly server shutdown.
        com.itemgraph.command.QueryDispatcher.shutdown();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        com.itemgraph.ingest.InternalObservationService.getInstance().stop();
        DatabaseManager.getInstance().close();
    }
}
