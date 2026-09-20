package com.itemgraph;

import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.config.ItemGraphConfig;
import com.itemgraph.db.DatabaseManager;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(ItemGraph.MOD_ID)
public class ItemGraph {
    public static final String MOD_ID = "itemgraph";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ItemGraph(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("ItemGraph initializing...");
        modContainer.registerConfig(ModConfig.Type.SERVER, ItemGraphConfig.SPEC);

        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);

        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.ItemEntityEventListener());
        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.ArmorStandEventListener());
        NeoForge.EVENT_BUS.register(new com.itemgraph.listener.TransformationEventListener());

        // Container capability wrapper: intercepts IItemHandler insertItem/extractItem on all
        // vanilla container block entities for player and automated transfer observation.
        // Must register on the mod event bus (RegisterCapabilitiesEvent fires on mod bus).
        modEventBus.register(new com.itemgraph.listener.ContainerCapabilityRegistrar());
        // Player context tracker: records which player has which container open for
        // attribution of capability wrapper calls to the responsible player.
        NeoForge.EVENT_BUS.register(com.itemgraph.listener.ContainerInteractionTracker.getInstance());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ItemGraphCommands.register(event);
    }

    private void onServerStarting(ServerStartingEvent event) {
        DatabaseManager.getInstance().initialize();
        com.itemgraph.ingest.InternalObservationService.getInstance().start();
        com.itemgraph.ingest.IngestionService.getInstance().start();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        com.itemgraph.ingest.InternalObservationService.getInstance().stop();
        com.itemgraph.ingest.IngestionService.getInstance().stop();
        // Stop the query worker before closing the database, so an in-flight /ig trace
        // cannot be reading through a connection that is about to disappear.
        com.itemgraph.command.QueryDispatcher.shutdown();
        DatabaseManager.getInstance().close();
    }
}
