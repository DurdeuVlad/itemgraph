package com.itemgraph.command;

import com.itemgraph.query.EventQueryService;
import com.itemgraph.i18n.ItemGraphLanguage;
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
import java.util.Locale;
import java.util.Map;

/** Shared read-only flow-browser entry points used by both loader adapters. */
public final class FlowBrowserService {

    private static final int ROWS_PER_PAGE = 9;

    static int pageSize() { return QueryLimits.clampGuiPageSize(ROWS_PER_PAGE); }
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
                        ? TRACE_QUERIES.traceItemPage(conn, query, pageSize(), window, cursor, direction)
                        : TRACE_QUERIES.traceFingerprintPage(conn, resolvedId, pageSize(), window, cursor, direction);
                case PLAYER -> resolvedId == null
                        ? TRACE_QUERIES.tracePlayerPage(conn, query, pageSize(), window, cursor, direction)
                        : TRACE_QUERIES.tracePlayerNodePage(conn, resolvedId, pageSize(), window, cursor, direction);
                case CONTAINER -> resolvedId == null
                        ? TRACE_QUERIES.traceContainerPage(conn, dimension, x, y, z, pageSize(), window, cursor, direction)
                        : TRACE_QUERIES.traceContainerNodePage(conn, resolvedId, pageSize(), window, cursor, direction);
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
        int candidatePageIndex;
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

    /**
     * Starts the asynchronous read-only flow browser for a container position.
     * The method is public so loader-specific interaction listeners can share
     * the same permission checks and query dispatch path.
     */
    public static int openContainer(CommandSourceStack source, String dimension, int x, int y, int z,
                                    Long sinceMinutes) {
        return open(source, new Target(TargetKind.CONTAINER, null, dimension, x, y, z, null), sinceMinutes);
    }

    private static int open(CommandSourceStack source, Target target, Long sinceMinutes) {
        if (!(source.getEntity() instanceof ServerPlayer)
                || !ItemGraphPermissions.canUseAll(source, ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)) {
            source.sendFailure(Component.literal(ItemGraphLanguage.text("flow.browser.permission_denied", "[ItemGraph] The flow browser requires GUI and audit permissions.")));
            return 0;
        }
        QueryWindow window = sinceMinutes == null
                ? QueryWindow.unbounded()
                : QueryWindow.lastMinutes(sinceMinutes, System.currentTimeMillis());
        BrowserSession session = new BrowserSession(target, window);
        source.sendSuccess(() -> Component.literal(ItemGraphLanguage.text("flow.browser.loading", "[ItemGraph] Loading read-only flow browser...")), false);
        return loadPage(source, session, null, TracePage.Direction.FORWARD, 0);
    }

    private static int loadPage(CommandSourceStack source, BrowserSession session, TraceCursor cursor,
                                TracePage.Direction direction, int pageIndex) {
        if (session.loading || pageIndex < 0 || !(source.getEntity() instanceof ServerPlayer requester)) {
            return 1;
        }
        AbstractContainerMenu expectedMenu = requester.containerMenu;
        session.loading = true;
        markMenuLoading(requester);
        int accepted = QueryDispatcher.dispatchData(source, List.of(ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT), "gui flow",
                conn -> session.target.load(conn, session.window, cursor, direction),
                (returnedSource, page) -> {
                    session.loading = false;
                    if (!(returnedSource.getEntity() instanceof ServerPlayer player)
                            || !ItemGraphPermissions.canUseAll(returnedSource, ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)
                            || player.containerMenu != expectedMenu) {
                        return;
                    }
                    if (isStaleEmptyContinuation(cursor, page)) {
                        returnedSource.sendFailure(Component.literal(
                                ItemGraphLanguage.text("browser.timeline_stale", "[ItemGraph] The timeline changed while browsing; reopen the flow to refresh.")));
                        return;
                    }
                    session.pageIndex = pageIndex;
                    session.page = page;
                    session.target = session.target.resolved(page);
                    showPage(player, session);
                }, () -> restoreMenuAfterFailedLoad(requester, expectedMenu, session));
        if (accepted == 0) {
            restoreMenuAfterFailedLoad(requester, expectedMenu, session);
        }
        return accepted;
    }

    /**
     * A load that never produces a page (queue rejection, failure, revoked permission)
     * must not leave the menu stuck in the "(loading)" state: reset the flag and
     * re-render the still-current page, but only while the original menu is still open.
     */
    private static void restoreMenuAfterFailedLoad(ServerPlayer player, AbstractContainerMenu expectedMenu,
                                                   BrowserSession session) {
        session.loading = false;
        if (session.page != null && player.containerMenu == expectedMenu) {
            showPage(player, session);
        }
    }

    static boolean isStaleEmptyContinuation(TraceCursor cursor, TracePage page) {
        return cursor != null && page.resolution() == TracePage.Resolution.RESOLVED && page.hops().isEmpty();
    }

    private static void showPage(ServerPlayer player, BrowserSession session) {
        TracePage page = session.page;
        List<ItemStack> items = emptySlots();
        Map<Integer, FlowBrowserMenu.Action> actions = new HashMap<>();

        if (page.resolution() == TracePage.Resolution.NOT_FOUND) {
            items.set(22, display(Items.BARRIER, t("browser.no_target", "No matching target"), List.of(page.targetDescription())));
        } else if (page.resolution() == TracePage.Resolution.AMBIGUOUS) {
            if (!page.candidates().isEmpty()) {
                int start = session.candidatePageIndex * page.pageSize();
                int count = Math.min(page.pageSize(), page.candidates().size() - start);
                for (int i = 0; i < count; i++) {
                    int candidateIndex = start + i;
                    FingerprintRef candidate = page.candidates().get(candidateIndex);
                    items.set(i, withMenuSlotLabel(fingerprintItem(candidate), i + 1));
                    actions.put(i, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.FINGERPRINT_CANDIDATE, candidateIndex));
                }
            } else {
                int start = session.candidatePageIndex * page.pageSize();
                int count = Math.min(page.pageSize(), page.nodeCandidates().size() - start);
                for (int i = 0; i < count; i++) {
                    int candidateIndex = start + i;
                    NodeRef candidate = page.nodeCandidates().get(candidateIndex);
                    items.set(i, withMenuSlotLabel(nodeCandidateItem(candidate), i + 1));
                    actions.put(i, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.NODE_CANDIDATE, candidateIndex));
                }
            }
        } else if (page.hops().isEmpty()) {
            items.set(22, display(Items.PAPER, t("browser.no_movement_title", "No recorded movement"), List.of(
                    page.targetDescription(), t("browser.no_movement_lore", "No observed or inferred movement was recorded in this time window."))));
        } else {
            for (int i = 0; i < Math.min(page.pageSize(), page.hops().size()); i++) {
                items.set(i, withMenuSlotLabel(hopItem(page.hops().get(i)), i + 1));
                actions.put(i, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.ENTRY, i));
            }
        }

        boolean candidateList = page.resolution() == TracePage.Resolution.AMBIGUOUS;
        int candidateCount = page.candidates().isEmpty() ? page.nodeCandidates().size() : page.candidates().size();
        boolean hasPrevious = (candidateList ? session.candidatePageIndex > 0 : page.hasPrevious()) && !session.loading;
        control(items, actions, PREVIOUS_SLOT,
                hasPrevious ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                hasPrevious ? "Previous page" : "Previous page unavailable",
                hasPrevious ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.PREVIOUS_PAGE, 0) : null);
        List<String> pageLore = new ArrayList<>(List.of(page.targetDescription(),
                t("browser.window", "Window: {0}", page.window().describe())));
        pageLore.add(page.resolution() == TracePage.Resolution.AMBIGUOUS
                ? t("browser.candidate_cap", "Candidate list capped at 10 matches.")
                : t("browser.timeline_page_size", "Up to {0} timeline entries per page.", pageSize()));
        int shownPage = candidateList ? session.candidatePageIndex + 1 : session.pageIndex + 1;
        String pageTitle = candidateList
                ? t("browser.candidate_title", "Candidates {0}{1}", shownPage,
                        session.loading ? t("browser.loading_suffix", " (loading)") : "")
                : t("browser.page_title", "Page {0}{1}", shownPage,
                        session.loading ? t("browser.loading_suffix", " (loading)") : "");
        items.set(PAGE_LABEL_SLOT, display(Items.PAPER,
                pageTitle, pageLore));
        control(items, actions, BACK_OR_CLOSE_SLOT, Items.BARRIER, "Close flow browser",
                new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.CLOSE, 0));
        control(items, actions, NEXT_SLOT,
                (candidateList ? (session.candidatePageIndex + 1) * page.pageSize() < candidateCount : page.hasNext())
                        && !session.loading ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                (candidateList ? (session.candidatePageIndex + 1) * page.pageSize() < candidateCount : page.hasNext())
                        && !session.loading ? "Next page" : "Next page unavailable",
                (candidateList ? (session.candidatePageIndex + 1) * page.pageSize() < candidateCount : page.hasNext())
                        && !session.loading
                        ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.NEXT_PAGE, 0) : null);

        sendPageCompanion(player, page, candidateList ? session.candidatePageIndex : session.pageIndex);
        openMenu(player, menuTitle(page), items, actions,
                (clickingPlayer, action) -> handlePageAction(clickingPlayer, session, action));
    }

    /**
     * Shows the pending state inside the currently open menu: the page label gains the
     * "(loading)" suffix and the paging controls are greyed out, matching the disabled
     * controls `showPage` would render. Slots are mutated in place — a `SimpleContainer`
     * update syncs to the client on the next tick — so `player.containerMenu` keeps its
     * identity and the completion callback's `expectedMenu` check still passes.
     */
    static void markMenuLoading(ServerPlayer player) {
        if (!(player.containerMenu instanceof FlowBrowserMenu menu)) {
            return;
        }
        net.minecraft.world.Container container = menu.getContainer();
        ItemStack label = container.getItem(PAGE_LABEL_SLOT);
        if (!label.isEmpty()) {
            net.minecraft.network.chat.Component name = label.get(DataComponents.CUSTOM_NAME);
            ItemStack loadingLabel = label.copy();
            loadingLabel.set(DataComponents.CUSTOM_NAME, Component.literal(
                    (name == null ? "" : name.getString()) + t("browser.loading_suffix", " (loading)")));
            container.setItem(PAGE_LABEL_SLOT, loadingLabel);
        }
        List<String> controlLore = List.of(t("browser.control_lore", "Flow browser control"));
        container.setItem(PREVIOUS_SLOT, display(Items.GRAY_STAINED_GLASS_PANE,
                localizeControl("Previous page unavailable"), controlLore));
        container.setItem(NEXT_SLOT, display(Items.GRAY_STAINED_GLASS_PANE,
                localizeControl("Next page unavailable"), controlLore));
    }

    /**
     * A vanilla chest menu renders item names only on hover. Send the current page rows
     * as numbered chat lines so each slot has a persistent, readable label at normal
     * scale. The row number is also the one-based menu slot number.
     */
    static List<String> pageCompanionLines(TracePage page, int pageIndex) {
        return pageCompanionLines(page, pageIndex, 0);
    }

    static List<String> pageCompanionLines(TracePage page, int pageIndex, int candidatePageIndex) {
        List<String> lines = new ArrayList<>();
        String pagePrefix = t("browser.page_prefix", "[ItemGraph] {0} {1}:",
                page.resolution() == TracePage.Resolution.AMBIGUOUS
                        ? t("browser.candidate_page", "candidate page") : t("browser.page", "page"), pageIndex + 1) + " ";
        if (page.resolution() == TracePage.Resolution.AMBIGUOUS) {
            if (!page.candidates().isEmpty()) {
                int start = candidatePageIndex * page.pageSize();
                for (int i = start; i < Math.min(start + page.pageSize(), page.candidates().size()); i++) {
                    FingerprintRef candidate = page.candidates().get(i);
                    String prefix = i == start ? pagePrefix : "[ItemGraph] ";
                    int ordinal = i - start + 1;
                    String label = t("browser.ambiguous_candidate", "{0}. AMBIGUOUS candidate — {1}", ordinal, "");
                    int identityLimit = Math.max(1, 80 - prefix.length() - label.length());
                    lines.add(prefix + t("browser.ambiguous_candidate", "{0}. AMBIGUOUS candidate — {1}",
                            ordinal, safeSummary(safeIdentity(candidate), identityLimit)));
                }
            } else {
                int start = candidatePageIndex * page.pageSize();
                for (int i = start; i < Math.min(start + page.pageSize(), page.nodeCandidates().size()); i++) {
                    NodeRef candidate = page.nodeCandidates().get(i);
                    String prefix = i == start ? pagePrefix : "[ItemGraph] ";
                    int ordinal = i - start + 1;
                    String label = t("browser.ambiguous_node_candidate",
                            "{0}. AMBIGUOUS node candidate — {1}", ordinal, "");
                    int descriptionLimit = Math.max(1, 80 - prefix.length() - label.length());
                    lines.add(prefix + t("browser.ambiguous_node_candidate",
                            "{0}. AMBIGUOUS node candidate — {1}", ordinal,
                            safeSummary(candidate.describeShort(), descriptionLimit)));
                }
            }
            if (lines.isEmpty()) lines.add(pagePrefix + t("browser.ambiguous_empty", "AMBIGUOUS — no candidate details were recorded."));
        } else if (page.resolution() == TracePage.Resolution.NOT_FOUND) {
            lines.add(pagePrefix + t("browser.not_found", "UNRESOLVED — no matching item, player, or container was recorded."));
        } else if (page.hops().isEmpty()) {
            lines.add(pagePrefix + t("browser.empty", "UNRESOLVED — no observed or inferred movement was recorded in this window."));
        } else {
            for (int i = 0; i < Math.min(page.pageSize(), page.hops().size()); i++) {
                TraceHop hop = page.hops().get(i);
                String evidence = companionEvidenceClass(hop);
                String rowPrefix = "[ItemGraph] " + (i + 1) + ". " + evidence + " — ";
                int identityLimit = Math.max(1, Math.min(40, 80 - rowPrefix.length()));
                lines.add("[ItemGraph] " + t("browser.row", "{0}. {1} — {2}",
                        i + 1, evidence, safeSummary(safeIdentity(hop.item()), identityLimit)));
            }
        }
        return List.copyOf(lines);
    }

    static String companionEvidenceClass(TraceHop hop) {
        if (hop.source() == TraceHop.Source.TRANSFORMATION) return "OBSERVED / TRANSFORMATION";
        if (hop.kind() == TraceHop.Kind.INFERRED) {
            return "INFERRED conf=" + QueryFormatter.formatConfidence(hop.confidence());
        }
        return isAmbiguousSourceGroup(hop) ? "OBSERVED / AMBIGUOUS SOURCE GROUP" : "OBSERVED";
    }

    private static boolean isAmbiguousSourceGroup(TraceHop hop) {
        return hop.kind() == TraceHop.Kind.OBSERVED && hop.detail() != null
                && hop.detail().contains("[source group ambiguous #");
    }

    private static void sendPageCompanion(ServerPlayer player, TracePage page, int pageIndex) {
        int candidatePageIndex = page.resolution() == TracePage.Resolution.AMBIGUOUS ? pageIndex : 0;
        for (String line : pageCompanionLines(page, pageIndex, candidatePageIndex)) {
            player.sendSystemMessage(Component.literal(line));
        }
    }

    private static String safeIdentity(FingerprintRef fingerprint) {
        if (fingerprint == null || !fingerprint.resolved()) return t("browser.identity_unavailable", "item identity unavailable");
        String identity = (fingerprint.customName() == null || fingerprint.customName().isBlank())
                ? fingerprint.itemId() : fingerprint.customName() + " (" + fingerprint.itemId() + ")";
        return safeSummary(identity, 72);
    }

    private static String safeSummary(String value, int maxLength) {
        if (value == null || value.isBlank()) return t("browser.event_kind_unavailable", "event kind unavailable");
        String singleLine = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        return singleLine.length() <= maxLength ? singleLine : singleLine.substring(0, maxLength - 3) + "...";
    }

    private static void handlePageAction(ServerPlayer player, BrowserSession session, FlowBrowserMenu.Action action) {
        if (!ItemGraphPermissions.canUseAll(player.createCommandSourceStack(), ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)) {
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
                    session.candidatePageIndex = 0;
                    loadPage(player.createCommandSourceStack(), session, null,
                            TracePage.Direction.FORWARD, 0);
                }
            }
            case NODE_CANDIDATE -> {
                if (!session.loading && session.page != null && action.index() >= 0
                        && action.index() < session.page.nodeCandidates().size()) {
                    session.target = session.target.forNode(session.page.nodeCandidates().get(action.index()).id());
                    session.pageIndex = 0;
                    session.candidatePageIndex = 0;
                    loadPage(player.createCommandSourceStack(), session, null,
                            TracePage.Direction.FORWARD, 0);
                }
            }
            case PREVIOUS_PAGE -> {
                if (session.page != null && session.page.resolution() == TracePage.Resolution.AMBIGUOUS
                        && session.candidatePageIndex > 0 && !session.loading) {
                    session.candidatePageIndex--;
                    showPage(player, session);
                } else if (session.page != null && session.page.hasPrevious() && !session.loading) {
                    loadPage(player.createCommandSourceStack(), session, session.page.previousCursor(),
                            TracePage.Direction.BACKWARD, session.pageIndex - 1);
                }
            }
            case NEXT_PAGE -> {
                if (session.page != null && session.page.resolution() == TracePage.Resolution.AMBIGUOUS
                        && !session.loading) {
                    int candidates = session.page.candidates().isEmpty()
                            ? session.page.nodeCandidates().size() : session.page.candidates().size();
                    if ((session.candidatePageIndex + 1) * session.page.pageSize() < candidates) {
                        session.candidatePageIndex++;
                        showPage(player, session);
                    }
                } else if (session.page != null && session.page.hasNext() && !session.loading) {
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
        markMenuLoading(player);
        AbstractContainerMenu expectedMenu = player.containerMenu;
        int accepted = QueryDispatcher.dispatchData(player.createCommandSourceStack(), List.of(ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT), "gui detail",
                conn -> detailLines(conn, hop),
                (source, lines) -> {
                    browser.loading = false;
                    if (source.getEntity() instanceof ServerPlayer viewer
                            && ItemGraphPermissions.canUseAll(source, ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)
                            && viewer.containerMenu == expectedMenu) {
                        DetailSession detail = new DetailSession(browser, detailTitle(hop), lines, 0);
                        showDetails(viewer, detail);
                    }
                },
                () -> restoreMenuAfterFailedLoad(player, expectedMenu, browser));
        if (accepted == 0) {
            restoreMenuAfterFailedLoad(player, expectedMenu, browser);
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
                    t("browser.detail.time", "time: {0}", QueryFormatter.formatTime(hop.timestampMs())),
                    t("browser.detail.quantity", "quantity: {0}x", hop.amount()),
                    t("browser.detail.item", "item: {0}", hop.item() == null ? "(no related fingerprint)" : hop.item().describeFull()),
                    t("browser.detail.stored_details", "stored details: {0}", hop.detail()));
        };
    }

    private static String detailTitle(TraceHop hop) {
        return switch (hop.source()) {
            case OBSERVATION -> t("browser.detail.observation", "Observation #{0}", hop.refId());
            case TRANSFORMATION -> t("browser.detail.transformation", "Transformation #{0}", hop.refId());
            case INFERRED_EDGE -> t("browser.detail.inference_edge", "Inference edge #{0}", hop.refId());
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
                hasPrevious ? t("browser.detail.previous", "Previous detail page")
                        : t("browser.detail.previous_unavailable", "Previous detail page unavailable"),
                hasPrevious ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.DETAIL_PREVIOUS, 0) : null);
        items.set(PAGE_LABEL_SLOT, display(Items.PAPER,
                t("browser.detail.title", "Details {0}", detail.pageIndex() + 1), List.of(detail.title())));
        control(items, actions, BACK_OR_CLOSE_SLOT, Items.BARRIER, t("browser.detail.back", "Back to flow timeline"),
                new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.DETAIL_BACK, 0));
        control(items, actions, NEXT_SLOT,
                hasNext ? Items.ARROW : Items.GRAY_STAINED_GLASS_PANE,
                hasNext ? t("browser.detail.next", "Next detail page")
                        : t("browser.detail.next_unavailable", "Next detail page unavailable"),
                hasNext ? new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.DETAIL_NEXT, 0) : null);
        openMenu(player, detail.title(), items, actions,
                (clickingPlayer, action) -> handleDetailAction(clickingPlayer, detail, action));
    }

    private static void handleDetailAction(ServerPlayer player, DetailSession detail,
                                           FlowBrowserMenu.Action action) {
        if (!ItemGraphPermissions.canUseAll(player.createCommandSourceStack(), ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)) {
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
        items.set(slot, display(item, localizeControl(label), List.of(t("browser.control_lore", "Flow browser control"))));
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
        lore.add(t("browser.amount", "Amount: {0}x", hop.amount()));
        if (hop.endMs() > hop.timestampMs()) {
            lore.add(t("browser.time_window", "Time window: {0} -> {1}", QueryFormatter.formatTime(hop.timestampMs()),
                    QueryFormatter.formatTime(hop.endMs())));
        } else {
            lore.add(t("browser.time", "Time: {0}", QueryFormatter.formatTime(hop.timestampMs())));
        }
        lore.add(t("browser.from", "From: {0}", hop.origin() == null ? "(none recorded)" : hop.origin().describeShort()));
        lore.add(t("browser.to", "To: {0}", hop.destination() == null ? "(none recorded)" : hop.destination().describeShort()));
        lore.add(switch (hop.source()) {
            case OBSERVATION -> t("browser.evidence_observation", "Evidence: observation #{0}", hop.refId());
            case TRANSFORMATION -> t("browser.evidence_transformation", "Evidence: transformation #{0}", hop.refId());
            case INFERRED_EDGE -> t("browser.inference_edge", "Inference edge #{0}", hop.refId());
        });
        if (transformation && hop.item() != null) {
            lore.add(t("browser.related_fingerprint", "Related fingerprint: {0}", hop.item().describeFull()));
        }
        lore.add(t("browser.details", "Details: {0}", hop.detail()));
        ItemStack icon = icon(hop.item());
        return display(icon, title, lore);
    }

    static ItemStack withMenuSlotLabel(ItemStack stack, int oneBasedSlot) {
        String name = stack.get(DataComponents.CUSTOM_NAME).getString();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(t("browser.menu_slot",
                "Slot {0}: {1}", oneBasedSlot, name)));
        return stack;
    }

    private static ItemStack fingerprintItem(FingerprintRef fingerprint) {
        List<String> lore = new ArrayList<>();
        lore.add(t("browser.fingerprint_id", "Fingerprint #{0}", fingerprint.id()));
        if (fingerprint.fingerprintHash() != null) {
            lore.add(t("browser.hash", "Hash: {0}", fingerprint.fingerprintHash()));
        }
        lore.add(t("browser.select_fingerprint", "Select to open this item's flow."));
        return display(icon(fingerprint), t("browser.select_item", "Select {0}", fingerprint.describe()), lore);
    }

    private static ItemStack nodeCandidateItem(NodeRef node) {
        Item icon = "PLAYER".equals(node.nodeType()) ? Items.PLAYER_HEAD : Items.CHEST;
        return display(icon, t("browser.select_node", "Select {0}", node.describe()), List.of(
                t("browser.node_id", "Node ID: {0}", node.id()),
                t("browser.node_type", "Type: {0}", node.nodeType()),
                t("browser.dimension", "Dimension: {0}", node.levelId())));
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
        // Names and lore may contain raw item/node values; translate only their authored template segments.
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (!lore.isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(
                    lore.stream().<Component>map(Component::literal).toList(), List.of()));
        }
        return stack;
    }

    static String menuTitle(TracePage page) {
        if (page.fingerprint() != null && page.fingerprint().resolved()) {
            return t("browser.title_item", "ItemGraph: item #{0}", page.fingerprint().id());
        }
        if (page.targetNode() != null && page.targetNode().resolved()) {
            return t("browser.title_node", "ItemGraph: {0} #{1}",
                    page.targetNode().nodeType().toLowerCase(Locale.ROOT), page.targetNode().id());
        }
        return shortText(t("browser.title_target", "ItemGraph: {0}", page.targetDescription()), 40);
    }

    private static String shortText(String line) {
        return shortText(line, 64);
    }

    private static String shortText(String line, int maxLength) {
        return line.length() <= maxLength ? line : line.substring(0, maxLength - 3) + "...";
    }

    private static String t(String key, String english, Object... arguments) {
        return ItemGraphLanguage.text(key, english, arguments);
    }

    private static String localizeControl(String label) {
        return switch (label) {
            case "Previous page" -> t("browser.previous", "Previous page");
            case "Previous page unavailable" -> t("browser.previous_unavailable", "Previous page unavailable");
            case "Next page" -> t("browser.next", "Next page");
            case "Next page unavailable" -> t("browser.next_unavailable", "Next page unavailable");
            case "Close flow browser" -> t("browser.close", "Close flow browser");
            default -> label;
        };
    }

    private static void openMenu(ServerPlayer player, String title, List<ItemStack> items,
                                 Map<Integer, FlowBrowserMenu.Action> actions,
                                 java.util.function.BiConsumer<ServerPlayer, FlowBrowserMenu.Action> handler) {
        if (!ItemGraphPermissions.canUseAll(player.createCommandSourceStack(), ItemGraphPermissions.GUI, ItemGraphPermissions.AUDIT)) {
            player.closeContainer();
            return;
        }
        boolean opened = player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, ignored) -> new FlowBrowserMenu(
                        containerId, inventory, items, actions, handler),
                Component.literal(title))).isPresent();
        if (!opened) {
            player.sendSystemMessage(Component.literal(ItemGraphLanguage.text("flow.browser.open_failed", "[ItemGraph] Could not open the flow browser.")));
        }
    }
}
