package com.itemgraph.command;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

final class FlowBrowserMenu extends ChestMenu {

    static final int DISPLAY_SLOT_COUNT = 45;
    static final int MENU_SLOT_COUNT = 54;

    enum ActionType {
        ENTRY,
        FINGERPRINT_CANDIDATE,
        NODE_CANDIDATE,
        PREVIOUS_PAGE,
        NEXT_PAGE,
        CLOSE,
        DETAIL_PREVIOUS,
        DETAIL_NEXT,
        DETAIL_BACK
    }

    record Action(ActionType type, int index) {}

    private final Map<Integer, Action> actions;
    private final BiConsumer<ServerPlayer, Action> actionHandler;

    FlowBrowserMenu(int containerId, Inventory playerInventory, List<ItemStack> displayItems,
                          Map<Integer, Action> actions,
                          BiConsumer<ServerPlayer, Action> actionHandler) {
        super(MenuType.GENERIC_9x6, containerId, playerInventory, new SimpleContainer(MENU_SLOT_COUNT), 6);
        this.actions = Map.copyOf(actions);
        this.actionHandler = actionHandler;
        for (int slot = 0; slot < Math.min(displayItems.size(), MENU_SLOT_COUNT); slot++) {
            ItemStack stack = displayItems.get(slot);
            if (!stack.isEmpty()) {
                getContainer().setItem(slot, stack.copy());
            }
        }
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)
                || !serverPlayer.createCommandSourceStack().hasPermission(2)) {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
            return;
        }
        if (clickType != ClickType.PICKUP || button != 0 || slotId < 0 || slotId >= MENU_SLOT_COUNT) {
            return;
        }
        Action action = actions.get(slotId);
        if (action != null) {
            actionHandler.accept(serverPlayer, action);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return false;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return player instanceof ServerPlayer serverPlayer
                && serverPlayer.createCommandSourceStack().hasPermission(2);
    }
}
