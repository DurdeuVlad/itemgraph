package com.itemgraph.fabric;

import com.itemgraph.command.ItemGraphCommands;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that Fabric executes the loader-neutral command contract used for
 * GriefLogger-compatible lookup, paging, inspection, and suggestions.
 */
class FabricItemGraphCommandsParityTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // The shared registries may already be bootstrapped by another test.
        }
    }

    @Test
    void publishedFilterPageAndInspectSyntaxParsesOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertParsedCompletely(dispatcher.parse(
                "itemgraph lookup action.break_block include.diamond_ore radius.20", source),
                "direct GriefLogger filter syntax");
        assertParsedCompletely(dispatcher.parse(
                "ig lookup filters action.break_block radius.20", source),
                "explicit ItemGraph filter syntax");
        assertParsedCompletely(dispatcher.parse("itemgraph page 2", source),
                "standalone page syntax");
        assertParsedCompletely(dispatcher.parse("ig inspect on", source),
                "inspection syntax");
    }

    @Test
    void publishedFilterSuggestionsRemainAvailableOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertSuggestions(dispatcher, source, "itemgraph lookup ",
                "all", "BREAK_BLOCK", "CHAT_MESSAGE", "INTERACT_ENTITY");
        assertSuggestions(dispatcher, source, "itemgraph lookup action.break_block ",
                "user.", "include.", "exclude.", "time.", "radius.");
        assertSuggestions(dispatcher, source, "ig inspect ", "on", "off", "status");
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        return dispatcher;
    }

    private static CommandSourceStack source() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getEntity()).thenReturn(mock(ServerPlayer.class));
        when(source.getOnlinePlayerNames()).thenReturn(List.of("Alex", "Steve"));
        when(source.levels()).thenReturn(Set.of(Level.OVERWORLD));
        return source;
    }

    private static void assertParsedCompletely(ParseResults<CommandSourceStack> parsed,
                                               String description) {
        assertFalse(parsed.getReader().canRead(),
                description + " was not consumed: " + parsed.getReader().getRemaining());
        assertTrue(parsed.getExceptions().isEmpty(),
                description + " produced parse errors: " + parsed.getExceptions());
    }

    private static void assertSuggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                          CommandSourceStack source, String input,
                                          String... expected) {
        List<String> suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
                .join().getList().stream().map(Suggestion::getText).toList();
        for (String text : expected) {
            assertTrue(suggestions.contains(text),
                    input + " did not suggest " + text + ": " + suggestions);
        }
    }
}
