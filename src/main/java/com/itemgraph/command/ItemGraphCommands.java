package com.itemgraph.command;

import com.itemgraph.ItemGraph;
import com.itemgraph.db.DatabaseManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ItemGraphCommands {

    private ItemGraphCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("itemgraph")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ItemGraphCommands::status))
        );

        dispatcher.register(Commands.literal("ig").redirect(root));
    }

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        String modVersion = ModList.get()
                .getModContainerById(ItemGraph.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");

        boolean griefLoggerLoaded = ModList.get().isLoaded("grieflogger");

        DatabaseManager db = DatabaseManager.getInstance();
        boolean dbConnected = db.isInitialized();
        String dbPath = db.getDatabasePath() != null ? db.getDatabasePath().toString() : "not set";
        String dbError = db.getLastError();

        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] version=" + modVersion +
                " griefLogger=" + (griefLoggerLoaded ? "detected" : "NOT DETECTED") +
                " db=" + (dbConnected ? "connected (schema v" + db.getCurrentSchemaVersion() + ")" : "NOT CONNECTED") +
                " dbPath=" + dbPath +
                (dbError != null ? " lastError=" + dbError : "")
        ), false);

        return 1;
    }
}
