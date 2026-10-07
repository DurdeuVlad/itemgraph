package com.itemgraph.command;

import net.minecraft.SharedConstants;
import com.itemgraph.query.FingerprintRef;
import com.itemgraph.query.QueryWindow;
import com.itemgraph.query.QueryLimits;
import com.itemgraph.query.TraceHop;
import com.itemgraph.query.TracePage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowBrowserMenuTest {

    @AfterEach
    void resetPermissionChecker() {
        ItemGraphPermissions.setChecker(null);
        QueryLimits.configureMaxPageSize(100);
    }

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
    void usesVanillaSixRowMenuAndRejectsEveryItemMovementClick() {
        Inventory inventory = mock(Inventory.class);
        ServerPlayer player = mock(ServerPlayer.class);
        CommandSourceStack commandSource = mock(CommandSourceStack.class);
        when(player.createCommandSourceStack()).thenReturn(commandSource);
        when(commandSource.hasPermission(2)).thenReturn(true);
        ItemStack displayed = new ItemStack(Items.DIAMOND, 1);
        AtomicReference<FlowBrowserMenu.Action> selected = new AtomicReference<>();
        FlowBrowserMenu menu = new FlowBrowserMenu(1, inventory, List.of(displayed),
                Map.of(0, new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.ENTRY, 0)),
                (clickedPlayer, action) -> selected.set(action));

        assertEquals(MenuType.GENERIC_9x6, menu.getType());
        assertEquals(54, menu.getContainer().getContainerSize());
        assertFalse(menu.canDragTo(menu.slots.get(0)));
        assertFalse(menu.canTakeItemForPickAll(displayed, menu.slots.get(0)));
        assertTrue(menu.quickMoveStack(player, 0).isEmpty());

        menu.clicked(0, 0, ClickType.PICKUP, player);
        assertEquals(new FlowBrowserMenu.Action(FlowBrowserMenu.ActionType.ENTRY, 0), selected.get());
        assertEquals(1, menu.getContainer().getItem(0).getCount(), "opening details must not take the display item");

        selected.set(null);
        menu.clicked(0, 1, ClickType.PICKUP, player);
        menu.clicked(54, 0, ClickType.PICKUP, player);
        for (ClickType clickType : ClickType.values()) {
            if (clickType != ClickType.PICKUP) {
                menu.clicked(0, 0, clickType, player);
            }
            assertEquals(1, menu.getContainer().getItem(0).getCount(),
                    clickType + " must not mutate the display container");
        }
        assertNull(selected.get(), "right-click, player inventory clicks, shift-click, drag, throw, swap, clone, and pickup-all are ignored");
        assertFalse(menu.clickMenuButton(player, 0));

        ItemGraphPermissions.setChecker((checkedSource, node) ->
                !node.equals(ItemGraphPermissions.GUI) && checkedSource.hasPermission(2));
        menu.clicked(0, 0, ClickType.PICKUP, player);
        assertNull(selected.get(), "an explicit GUI denial must block every menu action even for an operator");
        verify(player).closeContainer();
        assertFalse(menu.stillValid(player), "the menu must close if permission level 2 is lost");
    }

    @Test
    void configuredMaximumControlsTimelineAndAmbiguousCandidatePages() {
        QueryLimits.configureMaxPageSize(4);
        assertEquals(4, FlowBrowserService.pageSize());
        List<FingerprintRef> candidates = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(id -> new FingerprintRef(id, "minecraft:diamond", "candidate " + id, null)).toList();
        TracePage page = new TracePage("diamond", TracePage.Resolution.AMBIGUOUS, null, null,
                candidates, List.of(), List.of(), QueryWindow.unbounded(), FlowBrowserService.pageSize(),
                null, false, null, false);
        List<String> first = FlowBrowserService.pageCompanionLines(page, 0, 0);
        List<String> second = FlowBrowserService.pageCompanionLines(page, 1, 1);
        List<String> third = FlowBrowserService.pageCompanionLines(page, 2, 2);
        assertEquals(4, first.size());
        assertEquals(4, second.size());
        assertEquals(2, third.size());
        assertTrue(third.get(1).contains("candidate 10"));
    }

    @Test
    void flowEntryNamesExposeProvenanceConfidenceAndEvidenceWithoutUsingStackCount() {
        FingerprintRef fingerprint = new FingerprintRef(7, "minecraft:diamond", null, "hash-diamond");
        TraceHop observed = new TraceHop(TraceHop.Kind.OBSERVED, 11, null, null, 3,
                1_000L, 1_000L, null, "DROP_ITEM", fingerprint);
        TraceHop inferred = new TraceHop(TraceHop.Kind.INFERRED, 12, null, null, 2,
                2_000L, 3_000L, 0.9025, "ground bridge", fingerprint);

        ItemStack observedItem = FlowBrowserService.hopItem(observed);
        ItemStack inferredItem = FlowBrowserService.hopItem(inferred);
        String observedName = observedItem.get(DataComponents.CUSTOM_NAME).getString();
        String inferredName = inferredItem.get(DataComponents.CUSTOM_NAME).getString();

        assertTrue(observedName.startsWith("[OBSERVED]"), observedName);
        assertTrue(inferredName.startsWith("[INFERRED conf=0.9025]"), inferredName);
        assertEquals(1, observedItem.getCount(), "the icon count is not the observed movement quantity");
        assertTrue(observedItem.get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("observation #11")));
        assertTrue(inferredItem.get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("edge #12")));
        assertTrue(inferredItem.get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("Amount: 2x")));
        TraceHop unscored = new TraceHop(TraceHop.Kind.INFERRED, 14, null, null, 1,
                3_500L, 3_500L, null, "missing confidence", fingerprint, TraceHop.Source.INFERRED_EDGE);
        assertTrue(FlowBrowserService.hopItem(unscored).get(DataComponents.CUSTOM_NAME).getString()
                .contains("[INFERRED conf=(not recorded)]"));

        TraceHop transformation = new TraceHop(TraceHop.Kind.OBSERVED, 13, null, null, 1,
                4_000L, 4_000L, null, "[TRANSFORMATION CRAFTING -> minecraft:emerald] (1 item)",
                new FingerprintRef(8, "minecraft:emerald", null, "hash-emerald"), TraceHop.Source.TRANSFORMATION);
        ItemStack transformationItem = FlowBrowserService.hopItem(transformation);
        assertTrue(transformationItem.get(DataComponents.CUSTOM_NAME).getString()
                .startsWith("[OBSERVED] TRANSFORMATION #13"));
        assertEquals(Items.EMERALD, transformationItem.getItem(),
                "known transformation outputs should use their item icon instead of generic paper");
        assertTrue(transformationItem.get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("Related fingerprint: minecraft:emerald")));
    }

    @Test
    void selectableEntryHoverNamesMatchCompanionNumbersAndMenuSlots() {
        String previousLocale = com.itemgraph.i18n.ItemGraphLanguage.getLocale();
        try {
            com.itemgraph.i18n.ItemGraphLanguage.setLocale("en_us");
            ItemStack secondSlot = FlowBrowserService.withMenuSlotLabel(
                    FlowBrowserService.hopItem(new TraceHop(TraceHop.Kind.OBSERVED, 11, null, null, 1,
                            1_000L, 1_000L, null, "DROP_ITEM",
                            new FingerprintRef(7, "minecraft:diamond", null, null))), 2);
            assertEquals("Slot 2: [OBSERVED] 1x minecraft:diamond",
                    secondSlot.get(DataComponents.CUSTOM_NAME).getString());
            assertFalse(com.itemgraph.i18n.ItemGraphLanguage.keyFallbackInventory("nl_nl")
                    .contains("browser.menu_slot"), "Dutch needs a translated slot label");
            assertFalse(com.itemgraph.i18n.ItemGraphLanguage.keyFallbackInventory("zh_tw")
                    .contains("browser.menu_slot"), "Traditional Chinese needs a translated slot label");
        } finally {
            com.itemgraph.i18n.ItemGraphLanguage.setLocale(previousLocale);
        }
    }

    @Test
    void resolvedMenuTitlesStayInsideTheVanillaChestHeader() {
        FingerprintRef fingerprint = new FingerprintRef(100, "minecraft:netherite_boots",
                null, "0123456789abcdef".repeat(4));
        TracePage page = new TracePage(
                "item " + fingerprint.describeFull(), TracePage.Resolution.RESOLVED,
                fingerprint, null, List.of(), List.of(), List.of(), QueryWindow.unbounded(),
                45, null, false, null, false);

        assertEquals("ItemGraph: item #100", FlowBrowserService.menuTitle(page));
    }

    @Test
    void pageCompanionLabelsObservedInferredAndUnresolvedRowsWithoutExposingRawMetadata() {
        FingerprintRef diamond = new FingerprintRef(7, "minecraft:diamond", "Named stack", "private-hash");
        TraceHop observed = new TraceHop(TraceHop.Kind.OBSERVED, 11, null, null, 3,
                1_700_000_000_000L, 1_700_000_000_000L, null, "DROP_ITEM", diamond);
        TraceHop inferred = new TraceHop(TraceHop.Kind.INFERRED, 12, null, null, 2,
                1_700_000_001_000L, 1_700_000_002_000L, 0.9, "ground bridge", diamond);
        TraceHop missing = new TraceHop(TraceHop.Kind.OBSERVED, 13, null, null, 1,
                1_700_000_003_000L, 1_700_000_003_000L, null, "PICKUP_ITEM", null);
        TracePage page = new TracePage("diamond", TracePage.Resolution.RESOLVED, diamond, null,
                List.of(), List.of(), List.of(observed, inferred, missing), QueryWindow.unbounded(),
                9, null, false, null, false);

        List<String> lines = FlowBrowserService.pageCompanionLines(page, 1);

        assertEquals(3, lines.size());
        assertTrue(lines.get(0).contains("1. OBSERVED"));
        assertTrue(lines.get(0).contains("Named stack (minecraft:diamond)"));
        assertTrue(lines.get(1).contains("2. INFERRED conf=0.9000"));
        assertTrue(lines.get(2).contains("3. OBSERVED") && lines.get(2).contains("item identity unavailable"));
        assertTrue(lines.stream().allMatch(line -> line.length() <= 80),
                "persistent chat rows should stay concise; hover details carry the full record");
        ItemStack observedHover = FlowBrowserService.hopItem(observed);
        assertTrue(observedHover.get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("Time: 2023-11-14 22:13:20 UTC")));
        assertTrue(observedHover.get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("Details: DROP_ITEM")));
        assertFalse(String.join(" ", lines).contains("private-hash"));
    }

    @Test
    void loadingStateDisablesPagingControlsAndMarksPageLabel() {
        Inventory inventory = mock(Inventory.class);
        ServerPlayer player = mock(ServerPlayer.class);
        java.util.List<ItemStack> items = new java.util.ArrayList<>(
                java.util.Collections.nCopies(FlowBrowserMenu.MENU_SLOT_COUNT, ItemStack.EMPTY));
        items.set(45, new ItemStack(Items.ARROW));
        ItemStack label = new ItemStack(Items.PAPER);
        label.set(DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Page 1"));
        items.set(49, label);
        items.set(53, new ItemStack(Items.ARROW));
        FlowBrowserMenu menu = new FlowBrowserMenu(1, inventory, items, Map.of(), (p, a) -> {});
        player.containerMenu = menu;

        FlowBrowserService.markMenuLoading(player);

        assertEquals(Items.GRAY_STAINED_GLASS_PANE, menu.getContainer().getItem(45).getItem(),
                "the previous-page control is visibly disabled while loading");
        assertEquals(Items.GRAY_STAINED_GLASS_PANE, menu.getContainer().getItem(53).getItem(),
                "the next-page control is visibly disabled while loading");
        assertEquals("Page 1 (loading)",
                menu.getContainer().getItem(49).get(DataComponents.CUSTOM_NAME).getString());
        assertSame(menu, player.containerMenu,
                "the loading state mutates slots in place so the expected-menu check stays valid");
    }

    @Test
    void loadingMarkerIgnoresPlayersWithoutAFlowMenuOpen() {
        ServerPlayer player = mock(ServerPlayer.class);
        player.containerMenu = null;
        assertDoesNotThrow(() -> FlowBrowserService.markMenuLoading(player));
    }

    @Test
    void pageCompanionKeepsContainerBreakCorrelationDetailsOnHover() {
        TraceHop removal = new TraceHop(TraceHop.Kind.OBSERVED, 31, null, null, 2,
                1_700_000_004_000L, 1_700_000_004_000L, null,
                "REMOVE_ITEM [evidence_event_id=evidence-uuid parent_event_id=parent-uuid break_event_id=break-uuid]",
                new FingerprintRef(7, "minecraft:diamond", null, null), TraceHop.Source.OBSERVATION);
        TracePage page = new TracePage("diamond", TracePage.Resolution.RESOLVED,
                removal.item(), null, List.of(), List.of(), List.of(removal), QueryWindow.unbounded(),
                9, null, false, null, false);

        List<String> lines = FlowBrowserService.pageCompanionLines(page, 0);

        assertTrue(lines.getFirst().contains("OBSERVED"), lines.getFirst());
        assertTrue(lines.getFirst().contains("minecraft:diamond"), lines.getFirst());
        assertTrue(FlowBrowserService.hopItem(removal).get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("Details: REMOVE_ITEM")));
        assertFalse(String.join(" ", lines).contains("evidence_event_id"));
        assertFalse(String.join(" ", lines).contains("parent-uuid"));
        assertFalse(String.join(" ", lines).contains("break-uuid"));
    }

    @Test
    void pageCompanionKeepsAmbiguousTransformationAndUnresolvedStatesDistinct() {
        TraceHop ambiguous = new TraceHop(TraceHop.Kind.OBSERVED, 21, null, null, 1,
                1_700_000_004_000L, 1_700_000_004_000L, null,
                "DROP_ITEM [source group ambiguous #4; no independent quantity capacity; candidate match]",
                new FingerprintRef(21, "moddeditems:very_long_named_stack_with_special_metadata", null, null),
                TraceHop.Source.OBSERVATION);
        TraceHop transformation = new TraceHop(TraceHop.Kind.OBSERVED, 22, null, null, 1,
                1_700_000_005_000L, 1_700_000_005_000L, null,
                "[TRANSFORMATION CRAFTING -> minecraft:emerald] (1 item)", null,
                TraceHop.Source.TRANSFORMATION);
        assertEquals("OBSERVED / AMBIGUOUS SOURCE GROUP", FlowBrowserService.companionEvidenceClass(ambiguous));
        assertEquals("OBSERVED / TRANSFORMATION", FlowBrowserService.companionEvidenceClass(transformation));
        TracePage transformationPage = new TracePage("diamond", TracePage.Resolution.RESOLVED,
                null, null, List.of(), List.of(), List.of(transformation), QueryWindow.unbounded(),
                9, null, false, null, false);
        assertTrue(FlowBrowserService.pageCompanionLines(transformationPage, 0).getFirst()
                .contains("OBSERVED / TRANSFORMATION"));
        assertTrue(FlowBrowserService.hopItem(transformation).get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("TRANSFORMATION CRAFTING")),
                "the hover detail retains transformation evidence after the chat row is shortened");

        TracePage ambiguousTarget = new TracePage("diamond", TracePage.Resolution.AMBIGUOUS, null, null,
                List.of(new FingerprintRef(31, "minecraft:diamond", null, null)), List.of(), List.of(),
                QueryWindow.unbounded(), 9, null, false, null, false);
        TracePage unresolvedTarget = new TracePage("missing", TracePage.Resolution.NOT_FOUND, null, null,
                List.of(), List.of(), List.of(), QueryWindow.unbounded(), 9, null, false, null, false);
        TracePage emptyHistory = new TracePage("diamond", TracePage.Resolution.RESOLVED,
                new FingerprintRef(32, "minecraft:diamond", null, null), null, List.of(), List.of(), List.of(),
                QueryWindow.unbounded(), 9, null, false, null, false);

        assertTrue(FlowBrowserService.pageCompanionLines(ambiguousTarget, 0).stream()
                .anyMatch(line -> line.contains("AMBIGUOUS candidate")));
        assertTrue(FlowBrowserService.pageCompanionLines(unresolvedTarget, 0).stream()
                .anyMatch(line -> line.contains("UNRESOLVED — no matching")));
        assertTrue(FlowBrowserService.pageCompanionLines(emptyHistory, 0).stream()
                .anyMatch(line -> line.contains("UNRESOLVED — no observed or inferred movement")));

        List<FingerprintRef> tenCandidates = java.util.stream.IntStream.rangeClosed(1, 10)
                .mapToObj(id -> new FingerprintRef(id, "minecraft:diamond", "candidate " + id, "hash" + id))
                .toList();
        TracePage candidatePage = new TracePage("diamond", TracePage.Resolution.AMBIGUOUS, null, null,
                tenCandidates.stream().map(candidate -> new FingerprintRef(candidate.id(), candidate.itemId(),
                        candidate.id() == 10 ? "candidate 10"
                                : "A long custom item name that would wrap the candidate row",
                        candidate.fingerprintHash())).toList(),
                List.of(), List.of(), QueryWindow.unbounded(), 9,
                null, false, null, false);
        List<String> firstCandidatePage = FlowBrowserService.pageCompanionLines(candidatePage, 0, 0);
        List<String> secondCandidatePage = FlowBrowserService.pageCompanionLines(candidatePage, 1, 1);
        assertEquals(9, firstCandidatePage.size(), "all first-page candidate labels fit the nine-row companion");
        assertTrue(firstCandidatePage.stream().allMatch(line -> line.length() <= 80));
        assertTrue(secondCandidatePage.stream().allMatch(line -> line.length() <= 80));
        assertTrue(secondCandidatePage.get(0).contains("1. AMBIGUOUS candidate"));
        assertTrue(secondCandidatePage.get(0).contains("candidate 10"), "candidate ten stays reachable on page two");

        TracePage classifiedRows = new TracePage("mixed", TracePage.Resolution.RESOLVED, null, null,
                List.of(), List.of(), List.of(ambiguous, transformation), QueryWindow.unbounded(),
                9, null, false, null, false);
        List<String> rows = FlowBrowserService.pageCompanionLines(classifiedRows, 0);
        assertTrue(rows.get(0).contains("OBSERVED / AMBIGUOUS SOURCE GROUP"));
        assertTrue(rows.get(1).contains("OBSERVED / TRANSFORMATION"));
        assertTrue(FlowBrowserService.hopItem(ambiguous).get(DataComponents.LORE).lines().stream()
                .anyMatch(line -> line.getString().contains("no independent quantity capacity")),
                "the ambiguity explanation remains available on hover");
        assertTrue(rows.stream().allMatch(line -> line.length() <= 80));
    }
}
