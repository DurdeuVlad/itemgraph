package com.itemgraph.command;

import com.itemgraph.query.EventQueryService;
import com.itemgraph.query.ExplainQueryService;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.NodeRef;
import com.itemgraph.query.QueryFormatter;
import com.itemgraph.query.QueryLimits;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.TraceCursor;
import com.itemgraph.query.TraceHop;
import com.itemgraph.query.TracePage;
import com.itemgraph.query.TraceQueryService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class FlowBrowserService {

    private static final int PAGE_SIZE = QueryLimits.MAX_GUI_PAGE_SIZE;
    private static final int PREVIOUS_SLOT = 45;
    private static final int PAGE_LABEL_SLOT = 49;
    private static final int BACK_OR_CLOSE_SLOT = 52;
    private static final int NEXT_SLOT = 53;
    private static final TraceQueryService TRACE_QUERIES = new TraceQueryService();
    private static final EventQueryService EVENT_QUERIES = new EventQueryService();
    private static final ExplainQueryService EXPLAIN_QUERIES = new ExplainQueryService();

    private enum TargetKind {
        ITEM,
        PLAYER,
        CONTAINER
    }

    private record Target(TargetKind kind, String query, String dimension, int x, int y, int z,
                          Long resolvedId) {
        TracePage load(Connection conn, QueryWindow window, TraceCursor cursor,
                       TracePage.Direction direction) throws SQLException {
            return switch (kind) {
                case ITEM -> resolvedId == null
                        ? TRACE_QUERIES.traceItemPage(conn, query, PAGE_SIZE, window, cursor, direction)
                        : TRACE_QUERIES.traceFingerprintPage(conn, resolvedId, PAGE_SIZE, window, cursor, direction);
                case PLAYER -> resolvedId == null
                        ? TRACE_QUERIES.tracePlayerPage(conn, query, PAGE_SIZE, window, cursor, direction)
                        : TRACE_QUERIES.tracePlayerNodePage(conn, resolvedId, PAGE_SIZE, window, cursor, direction);
                case CONTAINER -> resolvedId == null
                        ? TRACE_QUERIES.traceContainerPage(conn, dimension, x, y, z, PAGE_SIZE, window, cursor, direction)
                        : TRACE_QUERIES.traceContainerNodePage(conn, resolvedId, PAGE_SIZE, window, cursor, direction);
            };
        }

        Target forFingerprint(long fingerprintId) {
            return new Target(TargetKind.ITEM, null, null, 0, 0, 0, fingerprintId);
        }

        Target forNode(long nodeId) {
            return new Target(kind, query, dimension, x, y, z, nodeId);
        }

        Target resolved(TracePage page) {
            if (page.resolution() != TracePage.Resolution.RESOLVED) {
                return this;
            }
            if (kind == TargetKind.ITEM && page.fingerprint() != null) {
                return new Target(kind, query, dimension, x, y, z, page.fingerprint().id());
            }
            return page.targetNode() == null
                    ? this
                    : new Target(kind, query, dimension, x, y, z, page.targetNode().id());
        }
    }

    private static final class BrowserSession {
        Target target;
        final QueryWindow window;
        TracePage page;
        int pageIndex;
        volatile boolean loading;

        BrowserSession(Target target, QueryWindow window) {
            this.target = target;
            this.window = window;
        }
    }

    private record DetailSession(BrowserSession browser, String title, List<String> lines, int pageIndex) {
        DetailSession {
            lines = List.copyOf(lines);
        }
    }

    private FlowBrowserService() {}

    static int openItem(CommandSourceStack source, String query, Long sinceMinutes) {
        return open(source, new Target(TargetKind.ITEM, query, null, 0, 0, 0, null), sinceMinutes);
    }

    static int openPlayer(CommandSourceStack source, String playerName, Long sinceMinutes) {
        return open(source, new Target(TargetKind.PLAYER, playerName, null, 0, 0, 0, null), sinceMinutes);
    }

    static int openContainer(CommandSourceStack source, String dimension, int x, int y, int z, Long sinceMinutes) {
        return open(source, new Target(TargetKind.CONTAINER, null, dimension, x, y, z, null), sinceMinutes);
    }

    private static int open(CommandSourceStack source, Target target, Long sinceMinutes) {
        if (!(source.getEntity() instanceof ServerPlayer) || !source.hasPermission(2)) {
            source.sendFailure(Component.literal("[ItemGraph] The flow browser requires a permission-level-2 player."));
            return 0;
        }
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());
        BrowserSession session = new BrowserSession(target, window);
        source.sendSuccess(() -> Component.literal("[ItemGraph] Loading read-only flow browser..."), false);
        return loadPage(source, session, null, TracePage.Direction.FORWARD, 0);
    }

    private static int loadPage(CommandSourceStack source, BrowserSession session, TraceCursor cursor,
                                TracePage.Direction direction, int pageIndex) {
        if (session.loading || pageIndex < 0 || !(source.getEntity() instanceof ServerPlayer requester)) {
            return 1;
        }
        AbstractContainerMenu expectedMenu = requester.containerMenu;
        session.loading = true;
        int accepted = QueryDispatcher.dispatchData(source, "gui flow",
                conn -> session.target.load(conn, session.window, cursor, direction),
                (returnedSource, page) -> {
                    session.loading = false;
                    if (!(returnedSource.getEntity() instanceof ServerPlayer player)
                            || !returnedSource.hasPermission(2) || player.containerMenu != expectedMenu) {
                        return;
                    }
                    if (isStaleEmptyContinuation(cursor, page)) {
                        returnedSource.sendFailure(Component.literal(
                                "[ItemGraph] The timeline changed while browsing; reopen the flow to refresh."));
                        return;
                    }
                    session.pageIndex = pageIndex;
                    session.page = page;
                    session.target = session.target.resolved(page);
                    showPage(player, session);
                }, () -> session.loading = false);
        if (accepted == 0) {
            session.loading = false;
        }
        return accepted;
    }

    static boolean isStaleEmptyContinuation(TraceCursor cursor, TracePage page) {
        return cursor != null && page.resolution() == TracePage.Resolution.RESOLVED && page.hops().isEmpty();
    }

    private static void showPage(ServerPlayer player, BrowserSession session) {
        TracePage page = session.page;
        List<ItemStack> items = emptySlots();
        Map<Integer, FlowBrowserMenu.Action> actions = new HashMap<>();

        if (page.resolution() == TracePage.Resolution.NOT_FOUND) {
            items.set(22, display(Items.BARRIER, "No matching target", List.of(page.targetDescription())));
        } else if (page.resolution() == TracePage.Resolution.AMBIGUOUS) {
            if (!page.candidates().isEmpty()) {
                int count = Math.min(FlowBrowserMenu.DISPLAY_SLOT_COUNT, page.candidates().size());
                for (int i = 0; i < count; i++) {
                    FingerprintRef candidate = page.candidates().get(i);
                    items.set(i, fingerprintItem(candidate));
                    actions.put(i, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.FINGERPRINT_CANDIDATE, i));
                }
            } else {
                int count = Math.min(FlowBrowserMenu.DISPLAY_SLOT_COUNT, page.nodeCandidates().size());
                for (int i = 0; i < count; i++) {
                    NodeRef candidate = page.nodeCandidates().get(i);
                    items.set(i, nodeCandidateItem(candidate));
                    actions.put(i, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.NODE_CANDIDATE, i));
                }
            }
        } else if (page.hops().isEmpty()) {
            items.set(22, display(Items.PAPER, "No recorded movement", List.of(
                    page.targetDescription(), "No observed or inferred movement was recorded in this time window.")));
        } else {
            for (int i = 0; i < page.hops().size(); i++) {
                items.set(i, hopItem(page.hops().get(i)));
                actions.put(i, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.ENTRY, i));
            }
        }

        boolean hasPrevious = page.hasPrevious() && !session.loading;
        control(items, actions, PREVIOUS_SLOT,
                hasPrevious ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                hasPrevious ? "Previous page" : "Previous page unavailable",
                hasPrevious ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.PREVIOUS_PAGE, 0) : null);
        List<String> pageLore = new ArrayList<>(List.of(page.targetDescription(),
                "Window: " + page.window().describe()));
        pageLore.add(page.resolution() == TracePage.Resolution.AMBIGUOUS
                ? "Candidate list capped at 10 matches."
                : "Up to " + PAGE_SIZE + " timeline entries per page.");
        items.set(PAGE_LABEL_SLOT, display(Items.PAPER,
                "Page " + (session.pageIndex + 1) + (session.loading ? " (loading)" : ""), pageLore));
        control(items, actions, BACK_OR_CLOSE_SLOT, Items.BARRIER, "Close flow browser",
                new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.CLOSE, 0));
        control(items, actions, NEXT_SLOT,
                page.hasNext() && !session.loading ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                page.hasNext() && !session.loading ? "Next page" : "Next page unavailable",
                page.hasNext() && !session.loading
                        ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.NEXT_PAGE, 0) : null);

        openMenu(player, shortText("ItemGraph: " + page.targetDescription()), items, actions,
                (clickingPlayer, action) -> handlePageAction(clickingPlayer, session, action));
    }

    private static void handlePageAction(ServerPlayer player, BrowserSession session, FlowBrowserMenu.Action action) {
        if (!player.createCommandSourceStack().hasPermission(2)) {
            player.closeContainer();
            return;
        }
        switch (action.type()) {
            case ENTRY -> {
                if (!session.loading && session.page != null && action.index() >= 0
                        && action.index() < session.page.hops().size()) {
                    loadDetail(player, session, session.page.hops().get(action.index()));
                }
            }
            case FINGERPRINT_CANDIDATE -> {
                if (!session.loading && session.page != null && action.index() >= 0
                        && action.index() < session.page.candidates().size()) {
                    session.target = session.target.forFingerprint(session.page.candidates().get(action.index()).id());
                    session.pageIndex = 0;
                    loadPage(player.createCommandSourceStack(), session, null,
                            TracePage.Direction.FORWARD, 0);
                }
            }
            case NODE_CANDIDATE -> {
                if (!session.loading && session.page != null && action.index() >= 0
                        && action.index() < session.page.nodeCandidates().size()) {
                    session.target = session.target.forNode(session.page.nodeCandidates().get(action.index()).id());
                    session.pageIndex = 0;
                    loadPage(player.createCommandSourceStack(), session, null,
                            TracePage.Direction.FORWARD, 0);
                }
            }
            case PREVIOUS_PAGE -> {
                if (session.page != null && session.page.hasPrevious() && !session.loading) {
                    loadPage(player.createCommandSourceStack(), session, session.page.previousCursor(),
                            TracePage.Direction.BACKWARD, session.pageIndex - 1);
                }
            }
            case NEXT_PAGE -> {
                if (session.page != null && session.page.hasNext() && !session.loading) {
                    loadPage(player.createCommandSourceStack(), session, session.page.nextCursor(),
                            TracePage.Direction.FORWARD, session.pageIndex + 1);
                }
            }
            case CLOSE -> player.closeContainer();
            default -> { }
        }
    }

    private static void loadDetail(ServerPlayer player, BrowserSession browser, TraceHop hop) {
        if (browser.loading) {
            return;
        }
        browser.loading = true;
        AbstractContainerMenu expectedMenu = player.containerMenu;
        int accepted = QueryDispatcher.dispatchData(player.createCommandSourceStack(), "gui detail",
                conn -> detailLines(conn, hop),
                (source, lines) -> {
                    browser.loading = false;
                    if (source.getEntity() instanceof ServerPlayer viewer && source.hasPermission(2)
                            && viewer.containerMenu == expectedMenu) {
                        DetailSession detail = new DetailSession(browser, detailTitle(hop), lines, 0);
                        showDetails(viewer, detail);
                    }
                },
                () -> browser.loading = false);
        if (accepted == 0) {
            browser.loading = false;
        }
    }

    private static List<String> detailLines(Connection conn, TraceHop hop) throws SQLException {
        return switch (hop.source()) {
            case OBSERVATION -> EVENT_QUERIES.findObservation(conn, hop.refId())
                    .map(QueryFormatter::formatEvent)
                    .orElseGet(() -> List.of(QueryFormatter.eventNotFound(hop.refId())));
            case INFERRED_EDGE -> EXPLAIN_QUERIES.findEdge(conn, hop.refId())
                    .map(QueryFormatter::formatExplain)
                    .orElseGet(() -> List.of(QueryFormatter.explainNotFound(hop.refId())));
            case TRANSFORMATION -> List.of(
                    "[OBSERVED] TRANSFORMATION #" + hop.refId(),
                    "time: " + QueryFormatter.formatTime(hop.timestampMs()),
                    "quantity: " + hop.amount() + "x",
                    "item: " + (hop.item() == null ? "(no related fingerprint)" : hop.item().describeFull()),
                    "stored details: " + hop.detail());
        };
    }

    private static String detailTitle(TraceHop hop) {
        return switch (hop.source()) {
            case OBSERVATION -> "Observation #" + hop.refId();
            case TRANSFORMATION -> "Transformation #" + hop.refId();
            case INFERRED_EDGE -> "Inference edge #" + hop.refId();
        };
    }

    private static void showDetails(ServerPlayer player, DetailSession detail) {
        int start = detail.pageIndex() * FlowBrowserMenu.DISPLAY_SLOT_COUNT;
        int end = Math.min(detail.lines().size(), start + FlowBrowserMenu.DISPLAY_SLOT_COUNT);
        List<ItemStack> items = emptySlots();
        Map<Integer, FlowBrowserMenu.Action> actions = new HashMap<>();
        for (int i = start; i < end; i++) {
            items.set(i - start, display(Items.PAPER, shortText(detail.lines().get(i)),
                    List.of(detail.lines().get(i))));
        }
        boolean hasPrevious = detail.pageIndex() > 0;
        boolean hasNext = end < detail.lines().size();
        control(items, actions, PREVIOUS_SLOT,
                hasPrevious ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                hasPrevious ? "Previous detail page" : "Previous detail page unavailable",
                hasPrevious ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.DETAIL_PREVIOUS, 0) : null);
        items.set(PAGE_LABEL_SLOT, display(Items.PAPER,
                "Details " + (detail.pageIndex() + 1), List.of(detail.title())));
        control(items, actions, BACK_OR_CLOSE_SLOT, Items.BARRIER, "Back to flow timeline",
                new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.DETAIL_BACK, 0));
        control(items, actions, NEXT_SLOT,
                hasNext ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                hasNext ? "Next detail page" : "Next detail page unavailable",
                hasNext ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.DETAIL_NEXT, 0) : null);
        openMenu(player, detail.title(), items, actions,
                (clickingPlayer, action) -> handleDetailAction(clickingPlayer, detail, action));
    }

    private static void handleDetailAction(ServerPlayer player, DetailSession detail,
                                           FlowBrowserMenu.Action action) {
        if (!player.createCommandSourceStack().hasPermission(2)) {
            player.closeContainer();
            return;
        }
        switch (action.type()) {
            case DETAIL_PREVIOUS -> {
                if (detail.pageIndex() > 0) {
                    showDetails(player, new DetailSession(detail.browser(), detail.title(),
                            detail.lines(), detail.pageIndex() - 1));
                }
            }
            case DETAIL_NEXT -> {
                if ((detail.pageIndex() + 1) * FlowBrowserMenu.DISPLAY_SLOT_COUNT < detail.lines().size()) {
                    showDetails(player, new DetailSession(detail.browser(), detail.title(),
                            detail.lines(), detail.pageIndex() + 1));
                }
            }
            case DETAIL_BACK -> showPage(player, detail.browser());
            case CLOSE -> player.closeContainer();
            default -> { }
        }
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> items = new ArrayList<>(FlowBrowserMenu.MENU_SLOT_COUNT);
        for (int i = 0; i < FlowBrowserMenu.MENU_SLOT_COUNT; i++) {
            items.add(ItemStack.EMPTY);
        }
        return items;
    }

    private static void control(List<ItemStack> items, Map<Integer, FlowBrowserMenu.Action> actions,
                                int slot, Item item, String label, FlowBrowserMenu.Action action) {
        items.set(slot, display(item, label, List.of("Flow browser control")));
        if (action != null) {
            actions.put(slot, action);
        }
    }

    static ItemStack hopItem(TraceHop hop) {
        boolean transformation = hop.source() == TraceHop.Source.TRANSFORMATION;
        String itemDescription = hop.item() == null ? "item" : hop.item().describe();
        String provenance = hop.kind() == TraceHop.Kind.OBSERVED
                ? "[OBSERVED]"
                : "[INFERRED conf=" + QueryFormatter.formatConfidence(hop.confidence()) + "]";
        String title = transformation
                ? provenance + " TRANSFORMATION #" + hop.refId() + " " + hop.amount() + "x"
                : provenance + " " + hop.amount() + "x " + itemDescription;
        List<String> lore = new ArrayList<>();
        lore.add("Amount: " + hop.amount() + "x");
        if (hop.endMs() > hop.timestampMs()) {
            lore.add("Time window: " + QueryFormatter.formatTime(hop.timestampMs())
                    + " -> " + QueryFormatter.formatTime(hop.endMs()));
        } else {
            lore.add("Time: " + QueryFormatter.formatTime(hop.timestampMs()));
        }
        lore.add("From: " + (hop.origin() == null ? "(none recorded)" : hop.origin().describeShort()));
        lore.add("To: " + (hop.destination() == null ? "(none recorded)" : hop.destination().describeShort()));
        lore.add(switch (hop.source()) {
            case OBSERVATION -> "Evidence: observation #" + hop.refId();
            case TRANSFORMATION -> "Evidence: transformation #" + hop.refId();
            case INFERRED_EDGE -> "Inference edge #" + hop.refId();
        });
        if (transformation && hop.item() != null) {
            lore.add("Related fingerprint: " + hop.item().describeFull());
        }
        lore.add("Details: " + hop.detail());
        ItemStack icon = transformation ? new ItemStack(Items.PAPER) : icon(hop.item());
        return display(icon, title, lore);
    }

    private static ItemStack fingerprintItem(FingerprintRef fingerprint) {
        List<String> lore = new ArrayList<>();
        lore.add("Fingerprint #" + fingerprint.id());
        if (fingerprint.fingerprintHash() != null) {
            lore.add("Hash: " + fingerprint.fingerprintHash());
        }
        lore.add("Select to open this item's flow.");
        return display(icon(fingerprint), "Select " + fingerprint.describe(), lore);
    }

    private static ItemStack nodeCandidateItem(NodeRef node) {
        Item icon = "PLAYER".equals(node.nodeType()) ? Items.PLAYER_HEAD : Items.CHEST;
        return display(icon, "Select " + node.describe(), List.of(
                "Node ID: " + node.id(),
                "Type: " + node.nodeType(),
                "Dimension: " + node.levelId()));
    }

    private static ItemStack icon(FingerprintRef fingerprint) {
        if (fingerprint == null || !fingerprint.resolved()) {
            return new ItemStack(Items.PAPER);
        }
        ResourceLocation key = ResourceLocation.tryParse(fingerprint.itemId());
        if (key == null) {
            return new ItemStack(Items.PAPER);
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? new ItemStack(Items.PAPER) : new ItemStack(item);
    }

    private static ItemStack display(Item item, String name, List<String> lore) {
        return display(new ItemStack(item), name, lore);
    }

    private static ItemStack display(ItemStack stack, String name, List<String> lore) {
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(
                    lore.stream().<Component>map(Component::literal).toList(), List.of()));
        }
        return stack;
    }

    private static String shortText(String line) {
        return line.length() <= 64 ? line : line.substring(0, 61) + "...";
    }

    private static void openMenu(ServerPlayer player, String title, List<ItemStack> items,
                                 Map<Integer, FlowBrowserMenu.Action> actions,
                                 java.util.function.BiConsumer<ServerPlayer, FlowBrowserMenu.Action> handler) {
        if (!player.createCommandSourceStack().hasPermission(2)) {
            player.closeContainer();
            return;
        }
        boolean opened = player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, ignored) -> new FlowBrowserMenu(
                        containerId, inventory, items, actions, handler),
                Component.literal(title))).isPresent();
        if (!opened) {
            player.sendSystemMessage(Component.literal("[ItemGraph] Could not open the flow browser."));
        }
    }
}
