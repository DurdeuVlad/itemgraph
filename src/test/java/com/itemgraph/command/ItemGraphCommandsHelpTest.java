package com.itemgraph.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemGraphCommandsHelpTest {

    @BeforeAll
    static void initMinecraftRegistries() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @Test
    void bareFullRootAndAliasShowOverview() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/itemgraph is the full command root")));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig help [topic]")));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig gui container <dimension>")));

        successes.clear();
        assertEquals(1, dispatcher.execute("ig", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("/ig inspect [on|off|status]")));
    }

    @Test
    void helpListsEveryRegisteredCommandPath() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("itemgraph");
        assertNotNull(root);
        assertEquals(Set.of("help", "status", "audit", "ingest", "event", "explain", "trace", "gui", "inspect"),
                root.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet()));
        assertEquals(Set.of("now"), childNames(root, "ingest"));
        assertEquals(Set.of("item", "player", "container"), childNames(root, "trace"));
        assertEquals(Set.of("item", "player", "container"), childNames(root, "gui"));
        assertEquals(Set.of("on", "off", "status"), childNames(root, "inspect"));

        for (String topLevel : Set.of("help", "status", "audit", "ingest", "event", "explain", "trace", "gui", "inspect")) {
            assertNotNull(CommandHelp.topicLines(topLevel), "missing help topic for /ig " + topLevel);
        }
        for (String path : List.of("ingest now", "trace item", "trace player", "trace container",
                "gui item", "gui player", "gui container")) {
            List<String> lines = CommandHelp.topicLines(path);
            assertNotNull(lines, "missing help topic for /ig " + path);
            assertTrue(String.join("\n", lines).contains("/ig " + path),
                    "help topic does not document its registered path: " + path);
        }
        String inspectHelp = String.join("\n", CommandHelp.topicLines("inspect"));
        assertTrue(inspectHelp.contains("/ig inspect [on|off|status]"));
        for (String child : List.of("on", "off", "status")) {
            assertTrue(inspectHelp.contains("/ig inspect " + child),
                    "inspect help does not document /ig inspect " + child);
        }
    }

    @Test
    void helpTopicReturnsSyntaxDefaultsPermissionAndExample() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        assertEquals(1, dispatcher.execute("itemgraph help", source));
        assertTrue(successes.stream().anyMatch(line -> line.contains("limit=20") && line.contains("100")));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help trace item", source));
        String itemHelp = String.join("\n", successes);
        assertTrue(itemHelp.contains("Syntax: /ig trace item <query> [limit] [sinceMinutes]"));
        assertTrue(itemHelp.contains("Permission: level 2"));
        assertTrue(itemHelp.contains("asynchronous"));
        assertTrue(itemHelp.contains("default 20"));
        assertTrue(itemHelp.contains("Example:"));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help gui container", source));
        String guiHelp = String.join("\n", successes);
        assertTrue(guiHelp.contains("Syntax: /ig gui container <dimension> <x> <y> <z> [sinceMinutes]"));
        assertTrue(guiHelp.contains("Example:"));

        successes.clear();
        assertEquals(1, dispatcher.execute("itemgraph help inspect", source));
        String inspectHelp = String.join("\n", successes);
        assertTrue(inspectHelp.contains("Permission: level 2"));
        assertTrue(inspectHelp.contains("does not consume the held item"));
        assertTrue(inspectHelp.contains("Example: /ig inspect on"));
    }

    @Test
    void everySuggestedHelpTopicExecutes() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> successes = captureSuccesses(source);

        for (String topic : CommandHelp.TOPIC_NAMES) {
            successes.clear();
            assertEquals(1, dispatcher.execute("itemgraph help " + topic, source), topic);
            assertFalse(successes.isEmpty(), topic);
            String lines = String.join("\n", successes);
            assertTrue(lines.contains("Permission: level 2"), topic);
            assertTrue(lines.contains("Example"), topic);
        }
        verify(source, never()).sendFailure(any());
    }

    @Test
    void unknownHelpTopicFailsWithValidTopics() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

        assertEquals(0, dispatcher.execute("itemgraph help not-a-topic", source));
        verify(source).sendFailure(failure.capture());
        assertTrue(failure.getValue().getString().contains("Unknown help topic 'not-a-topic'"));
        assertTrue(failure.getValue().getString().contains("trace item"));
        assertTrue(failure.getValue().getString().contains("gui container"));
    }

    @Test
    void helpRequiresPermissionLevelTwo() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        when(source.hasPermission(2)).thenReturn(false);

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("itemgraph", source));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("itemgraph help", source));
        verify(source, never()).sendSuccess(any(), anyBoolean());
    }

    @Test
    void suggestionsCoverLiteralsPlayersItemsTopicsAndDimensions() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertSuggestions(dispatcher, source, "itemgraph ", "help", "status", "trace", "gui", "inspect");
        assertSuggestions(dispatcher, source, "itemgraph help ", "trace item", "gui container", "inspect");
        assertSuggestions(dispatcher, source, "itemgraph inspect ", "on", "off", "status");
        assertSuggestions(dispatcher, source, "itemgraph trace player ", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph gui player ", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph trace item ", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph gui item ", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph gui container ", "minecraft:overworld");
    }

    private CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);
        return dispatcher;
    }

    private CommandSourceStack source() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getEntity()).thenReturn(mock(ServerPlayer.class));
        when(source.getOnlinePlayerNames()).thenReturn(List.of("Alex", "Steve"));
        when(source.levels()).thenReturn(Set.of(Level.OVERWORLD));
        return source;
    }

    private List<String> captureSuccesses(CommandSourceStack source) {
        List<String> lines = new ArrayList<>();
        doAnswer(invocation -> {
            Supplier<Component> message = invocation.getArgument(0);
            lines.add(message.get().getString());
            return null;
        }).when(source).sendSuccess(any(), anyBoolean());
        return lines;
    }

    private Set<String> childNames(CommandNode<CommandSourceStack> root, String child) {
        CommandNode<CommandSourceStack> node = root.getChild(child);
        assertNotNull(node, "missing /ig " + child);
        return node.getChildren().stream().map(CommandNode::getName).collect(Collectors.toSet());
    }

    private void assertSuggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                   CommandSourceStack source, String input, String... expected) {
        List<String> suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
                .join().getList().stream().map(Suggestion::getText).toList();
        for (String text : expected) {
            assertTrue(suggestions.contains(text), input + " did not suggest " + text + ": " + suggestions);
        }
    }
}
