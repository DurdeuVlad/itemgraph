package com.itemgraph.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceCursor;
import com.itemgraph.query.TraceHop;
import com.itemgraph.query.TracePage;
import com.mojang.brigadier.tree.CommandNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemGraphCommandsGuiTest {

    @Test
    void registersPlayerGuiCommandsWithExplicitContainerDimension() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        ItemGraphCommands.register(dispatcher);

        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("itemgraph");
        CommandNode<CommandSourceStack> gui = root.getChild("gui");
        assertNotNull(gui);
        assertNotNull(gui.getChild("item").getChild("itemQuery"));
        assertNotNull(gui.getChild("player").getChild("player"));
        CommandNode<CommandSourceStack> container = gui.getChild("container").getChild("dimension");
        assertNotNull(container.getChild("x").getChild("y").getChild("z").getChild("sinceMinutes"));
        CommandNode<CommandSourceStack> inspect = root.getChild("inspect");
        assertNotNull(inspect);
        assertNotNull(inspect.getChild("on"));
        assertNotNull(inspect.getChild("off"));
        assertNotNull(inspect.getChild("status"));
        assertNotNull(dispatcher.getRoot().getChild("ig"));
        assertSame(root, dispatcher.getRoot().getChild("ig").getRedirect());
    }

    @Test
    void explicitFingerprintIdPrefixParsesWhenQuoted() throws Exception {
        StringReader reader = new StringReader("\"id:123\"");

        assertEquals("id:123", StringArgumentType.string().parse(reader));
        assertFalse(reader.canRead());
    }

    @Test
    void cursorPageThatBecameEmptyIsNotRenderedAsAnEmptyTimeline() {
        TraceCursor cursor = new TraceCursor(1_000L, TraceHop.Kind.OBSERVED, 1,
                TraceHop.Source.OBSERVATION);
        TracePage emptyResolvedPage = new TracePage("item test", TracePage.Resolution.RESOLVED,
                null, null, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                QueryWindow.unbounded(), 45, null, false, null, false);
        assertTrue(FlowBrowserService.isStaleEmptyContinuation(cursor, emptyResolvedPage));
        assertFalse(FlowBrowserService.isStaleEmptyContinuation(null, emptyResolvedPage));
    }
}
