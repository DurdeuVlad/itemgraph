package com.itemgraph.command;

import com.itemgraph.ItemGraph;
import com.itemgraph.correlation.CorrelationResult;
import com.itemgraph.db.DatabaseManager;
import com.itemgraph.ingest.IngestionResult;
import com.itemgraph.ingest.IngestionService;
import com.itemgraph.ingest.SourceCheckpoint;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.sql.SQLException;

public final class ItemGraphCommands {

    private ItemGraphCommands() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("itemgraph")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ItemGraphCommands::status))
                        .then(Commands.literal("ingest")
                                .then(Commands.literal("now").executes(ItemGraphCommands::ingestNow)))
        );

        dispatcher.register(Commands.literal("ig").redirect(root));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
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

        IngestionService ingestion = IngestionService.getInstance();
        long totalObservations = ingestion.getTotalObservationsCount();
        IngestionResult lastResult = ingestion.getLastResult();

        String checkpointsSummary;
        try {
            var igConn = db.getConnection();
            SourceCheckpoint itemsCp = ingestion.getCheckpoint(igConn, IngestionService.SOURCE_ITEMS);
            SourceCheckpoint containersCp = ingestion.getCheckpoint(igConn, IngestionService.SOURCE_CONTAINERS);
            checkpointsSummary = "items(rowid=" + itemsCp.lastSourceRowid() + ") containers(rowid=" + containersCp.lastSourceRowid() + ")";
        } catch (SQLException e) {
            checkpointsSummary = "unavailable (" + e.getMessage() + ")";
        }
        final String checkpointsSummaryFinal = checkpointsSummary;

        CorrelationResult lastCorrelation = ingestion.getCorrelationEngine().getLastResult();
        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] correlation: groundBridgeWindow=" + ingestion.getCorrelationEngine().getWindowSeconds() + "s" +
                " lastPass=" + (lastCorrelation == null ? "never run yet" :
                    (lastCorrelation.success() ? "OK" : "ERROR (" + lastCorrelation.errorMessage() + ")") +
                    " (" + lastCorrelation.observationsFinalised() + " evaluated, " +
                    lastCorrelation.edgesCreated() + " bridges inferred, " +
                    lastCorrelation.deferred() + " deferred, " + lastCorrelation.durationMs() + "ms)")
        ), false);

        source.sendSuccess(() -> Component.literal(
                "[ItemGraph] ingestion: running=" + ingestion.isRunning() +
                " totalObservations=" + totalObservations +
                " checkpoints=" + checkpointsSummaryFinal +
                " lastCycle=" + (lastResult == null ? "never run yet" :
                    (lastResult.success() ? "OK" : "ERROR (" + lastResult.errorMessage() + ")") +
                    " (" + lastResult.itemsIngested() + " items, " + lastResult.containersIngested() +
                    " containers, " + lastResult.durationMs() + "ms)")
        ), false);

        return 1;
    }

    private static int ingestNow(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        IngestionResult result = IngestionService.getInstance().runIngestion();

        if (result.success()) {
            // Correlation is queued onto the ingestion worker rather than run here:
            // candidate search must never happen on the server thread.
            boolean queued = IngestionService.getInstance().requestCorrelationAsync();
            source.sendSuccess(() -> Component.literal(
                    "[ItemGraph] Ingestion cycle complete: " + result.itemsIngested() + " new item observations, " +
                    result.containersIngested() + " new container observations (" + result.durationMs() + "ms). " +
                    (queued ? "Correlation pass queued on the ingestion worker."
                            : "Ingestion service is stopped; correlation not queued.")
            ), false);
        } else {
            source.sendFailure(Component.literal("[ItemGraph] Ingestion cycle failed: " + result.errorMessage()));
        }

        return result.success() ? 1 : 0;
    }
}
