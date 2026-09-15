package com.itemgraph.ingest;

/**
 * Mapping of GriefLogger action IDs to canonical ItemGraph action types.
 * Authoritative byte-code verified legend from com.daqem.grieflogger.model.action.ItemAction:
 * 0=REMOVE_ITEM(REMOVE)
 * 1=ADD_ITEM(ADD)
 * 2=DROP_ITEM(REMOVE)
 * 3=PICKUP_ITEM(ADD)
 * 4=CRAFT_ITEM(ADD)
 * 5=BREAK_ITEM(REMOVE)
 * 6=CONSUME_ITEM(REMOVE)
 * 7=THROW_ITEM(REMOVE)
 * 8=SHOOT_ITEM(REMOVE)
 * 9=ADD_ITEM_ENDER(ADD)
 * 10=REMOVE_ITEM_ENDER(REMOVE)
 */
public enum ItemActionMapping {
    REMOVE_ITEM(0, "REMOVE_ITEM"),
    ADD_ITEM(1, "ADD_ITEM"),
    DROP_ITEM(2, "DROP_ITEM"),
    PICKUP_ITEM(3, "PICKUP_ITEM"),
    CRAFT_ITEM(4, "CRAFT_ITEM"),
    BREAK_ITEM(5, "BREAK_ITEM"),
    CONSUME_ITEM(6, "CONSUME_ITEM"),
    THROW_ITEM(7, "THROW_ITEM"),
    SHOOT_ITEM(8, "SHOOT_ITEM"),
    ADD_ITEM_ENDER(9, "ADD_ITEM_ENDER"),
    REMOVE_ITEM_ENDER(10, "REMOVE_ITEM_ENDER");

    private final int id;
    private final String actionName;

    ItemActionMapping(int id, String actionName) {
        this.id = id;
        this.actionName = actionName;
    }

    public int getId() {
        return id;
    }

    public String getActionName() {
        return actionName;
    }

    public static String getActionName(int id) {
        for (ItemActionMapping mapping : values()) {
            if (mapping.id == id) {
                return mapping.actionName;
            }
        }
        return "UNKNOWN_ACTION_" + id;
    }
}
