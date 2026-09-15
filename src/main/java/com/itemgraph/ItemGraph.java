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
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ItemGraphCommands.register(event);
    }

    private void onServerStarting(ServerStartingEvent event) {
        DatabaseManager.getInstance().initialize();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        DatabaseManager.getInstance().close();
    }
}
