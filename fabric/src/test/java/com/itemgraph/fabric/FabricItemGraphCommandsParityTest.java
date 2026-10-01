package com.itemgraph.fabric;

import com.itemgraph.command.ItemGraphCommands;
import com.itemgraph.query.AuditLookupFilters;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void commandPermissionAndInvalidPageBehaviorMatchOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack denied = source();
        when(denied.hasPermission(2)).thenReturn(false);

        for (String command : List.of("ig lookup radius.10", "itemgraph lookup radius.10",
                "ig page 2", "itemgraph page 2", "ig inspect on", "itemgraph inspect on")) {
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, denied);
            assertTrue(parsed.getReader().canRead(),
                    "permission level 2 must protect " + command);
        }
        assertTrue(suggestions(dispatcher, denied, "ig ").isEmpty(),
                "an unauthorized player must not receive protected command suggestions");

        assertTrue(dispatcher.parse("ig page 0", source()).getReader().canRead(),
                "page zero must be rejected deterministically");
        assertTrue(dispatcher.parse("itemgraph page -1", source()).getReader().canRead(),
                "negative page numbers must be rejected deterministically");
    }

    @Test
    void allPublishedLookupExamplesParseForBothItemGraphRootsOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();
        List<String> publishedExamples = List.of(
                "action.break_block include.diamond_ore radius.50",
                "user.Griefer42 time.1h radius.100",
                "action.remove_item \"include.diamond,netherite_ingot\" radius.30 time.6h",
                "\"action.break_block,place_block\" radius.15",
                "\"action.join,quit\" time.10m radius.100",
                "user.MinerJoe action.break_block \"exclude.stone,dirt,cobblestone,gravel\" radius.50",
                "radius.20 action.remove_item exclude.cobblestone",
                "include.diamond_block radius.100",
                "action.remove_item \"include.netherite_sword,netherite_pickaxe,netherite_ingot\" time.1d radius.50",
                "radius.5",
                "include.tnt time.30m radius.25",
                "\"action.add_item,remove_item\" time.90m radius.50",
                "action.join time.3d radius.50",
                "user.Notch radius.50",
                "\"user.Player1,Player2\" action.break_block time.1d radius.50");

        for (String example : publishedExamples) {
            assertParsedCompletely(dispatcher.parse("ig lookup " + example, source),
                    "published /ig lookup " + example);
            assertParsedCompletely(dispatcher.parse("itemgraph lookup " + example, source),
                    "published /itemgraph lookup " + example);
        }
    }

    @Test
    void publishedFilterAliasesAndBoundsAreAppliedOnFabric() {
        AuditLookupFilters parsed = AuditLookupFilters.parse(
                "a.break_block u.Alex i.diamond_ore t.1h r.50", 10_000_000L);
        assertEquals(List.of("BREAK_BLOCK"), parsed.eventTypes());
        assertEquals(List.of("Alex"), parsed.playerNames());
        assertEquals(List.of("minecraft:diamond_ore"), parsed.includeSubjects());
        assertEquals(50.0, parsed.radiusBlocks());
        AuditLookupFilters fiveFilters = AuditLookupFilters.parse(
                "action.break_block user.Alex include.stone time.1h radius.50", 10_000_000L);
        assertEquals(List.of("BREAK_BLOCK"), fiveFilters.eventTypes());
        assertEquals(List.of("Alex"), fiveFilters.playerNames());
        assertEquals(List.of("minecraft:stone"), fiveFilters.includeSubjects());
        assertEquals(List.of("PROJECTILE_SPAWN_ACCEPTED"),
                AuditLookupFilters.parse("action.projectile_spawn_accepted radius.10", 10_000_000L)
                        .eventTypes());

        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("action.break_block", 10_000_000L),
                "radius is required by the published lookup contract");
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse(
                        "action.break_block user.Alex time.1h include.stone exclude.dirt radius.50",
                        10_000_000L),
                "a lookup is limited to five filters");
        assertThrows(IllegalArgumentException.class,
                () -> AuditLookupFilters.parse("include.stone exclude.dirt radius.50", 10_000_000L),
                "include and exclude are mutually exclusive");
    }

    @Test
    void malformedLookupFiltersReturnExactFailuresOnBothRootsAndFormsOnFabric() throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        List<InvalidLookup> invalidLookups = List.of(
                new InvalidLookup("action.break_block", "radius filter is required"),
                new InvalidLookup("radius.", "invalid filter 'radius.'; use name.value"),
                new InvalidLookup("radius.0", "radius must be a positive number"),
                new InvalidLookup("who.Alex radius.10", "unknown filter 'who'"),
                new InvalidLookup("action.break_block a.join radius.10", "filter 'action' may be used once"),
                new InvalidLookup("include.stone exclude.dirt radius.10",
                        "include and exclude filters cannot be combined"),
                new InvalidLookup("action.break_block user.Alex include.stone time.1h radius.10 exclude.dirt",
                        "at most 5 filters are allowed"));

        for (String root : List.of("ig", "itemgraph")) {
            for (String lookupPrefix : List.of(root + " lookup ", root + " lookup filters ")) {
                for (InvalidLookup invalid : invalidLookups) {
                    CommandSourceStack source = source();
                    ArgumentCaptor<Component> failure = ArgumentCaptor.forClass(Component.class);

                    assertEquals(0, dispatcher.execute(lookupPrefix + invalid.filters(), source),
                            lookupPrefix + invalid.filters());
                    verify(source).sendFailure(failure.capture());
                    assertEquals("[ItemGraph] Invalid lookup filter: " + invalid.expectedDetail(),
                            failure.getValue().getString(), lookupPrefix + invalid.filters());
                    verify(source, never()).sendSuccess(any(), anyBoolean());
                }
            }
        }
    }

    private record InvalidLookup(String filters, String expectedDetail) { }

    @Test
    void filterSuggestionsOfferPublishedValuesAndRespectUsedFilterLimitsOnFabric() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack source = source();

        assertSuggestions(dispatcher, source, "itemgraph lookup action.",
                "place_block", "break_block", "interact_block", "join", "quit", "craft_item");
        assertSuggestions(dispatcher, source, "itemgraph lookup a.", "break_block", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup user.", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph lookup u.", "Alex", "Steve");
        assertSuggestions(dispatcher, source, "itemgraph lookup include.", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph lookup exclude.", "minecraft:stone");
        assertSuggestions(dispatcher, source, "itemgraph lookup action.break_block,",
                "place_block", "join");
        assertSuggestions(dispatcher, source, "itemgraph lookup \"action.break_block,",
                "place_block", "join");

        assertTrue(suggestions(dispatcher, source, "itemgraph lookup action.join ").stream()
                        .noneMatch(value -> value.startsWith("action.")),
                "the same filter must not be suggested twice");
        assertTrue(suggestions(dispatcher, source, "itemgraph lookup include.stone ").stream()
                        .noneMatch(value -> value.startsWith("exclude.")),
                "exclude must not be suggested after include");
        assertTrue(suggestions(dispatcher, source,
                "itemgraph lookup action.join user.Alex include.stone time.1h radius.50 ").isEmpty(),
                "no sixth filter is suggested");
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
        List<String> suggestions = suggestions(dispatcher, source, input);
        for (String text : expected) {
            assertTrue(suggestions.contains(text),
                    input + " did not suggest " + text + ": " + suggestions);
        }
    }

    private static List<String> suggestions(CommandDispatcher<CommandSourceStack> dispatcher,
                                            CommandSourceStack source, String input) {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, source))
                .join().getList().stream().map(Suggestion::getText).toList();
    }
}
