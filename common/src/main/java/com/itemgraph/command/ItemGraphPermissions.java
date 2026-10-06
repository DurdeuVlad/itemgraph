package com.itemgraph.command;

import net.minecraft.commands.CommandSourceStack;

import java.util.Objects;
import java.util.function.BiPredicate;

/** Exact named permission nodes shared by commands, deferred results, listeners, and menus. */
public final class ItemGraphPermissions {
    public static final String COMMAND = "itemgraph.command";
    public static final String LOOKUP = "itemgraph.command.lookup";
    public static final String INSPECT = "itemgraph.command.inspect";
    public static final String PAGE = "itemgraph.command.page";
    public static final String TRACE = "itemgraph.trace";
    public static final String EVENT = "itemgraph.event";
    public static final String EXPLAIN = "itemgraph.explain";
    public static final String AUDIT = "itemgraph.audit";
    public static final String GUI = "itemgraph.gui";
    public static final String INGEST = "itemgraph.ingest";
    public static final String IMPORT = "itemgraph.import";

    private static volatile BiPredicate<CommandSourceStack, String> checker =
            (source, node) -> source != null && source.hasPermission(2);

    private ItemGraphPermissions() {}

    /** Tests and loader adapters can replace the resolver; a null resolver restores level-2 fallback. */
    public static void setChecker(BiPredicate<CommandSourceStack, String> permissionChecker) {
        checker = permissionChecker == null
                ? (source, node) -> source != null && source.hasPermission(2)
                : permissionChecker;
    }

    public static boolean check(CommandSourceStack source, String node) {
        Objects.requireNonNull(node, "permission node");
        return source != null && checker.test(source, node);
    }

    public static boolean canUse(CommandSourceStack source, String node) {
        return check(source, COMMAND) && check(source, node);
    }

    public static boolean canUseAll(CommandSourceStack source, String... nodes) {
        if (!check(source, COMMAND)) return false;
        for (String node : nodes) {
            if (!check(source, node)) return false;
        }
        return true;
    }
}
