package com.itemgraph;

import com.itemgraph.command.ItemGraphPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** NeoForge 1.21.1 adapter for ItemGraph's exact named permission nodes. */
public final class NeoForgeItemGraphPermissions {
    private static final List<PermissionNode<Boolean>> NODES = List.of(
            node(ItemGraphPermissions.COMMAND),
            node(ItemGraphPermissions.LOOKUP),
            node(ItemGraphPermissions.INSPECT),
            node(ItemGraphPermissions.PAGE),
            node(ItemGraphPermissions.TRACE),
            node(ItemGraphPermissions.EVENT),
            node(ItemGraphPermissions.EXPLAIN),
            node(ItemGraphPermissions.AUDIT),
            node(ItemGraphPermissions.GUI),
            node(ItemGraphPermissions.INGEST),
            node(ItemGraphPermissions.IMPORT));
    private static final Map<String, PermissionNode<Boolean>> BY_NAME = NODES.stream()
            .collect(Collectors.toUnmodifiableMap(PermissionNode::getNodeName, Function.identity()));

    private NeoForgeItemGraphPermissions() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(NeoForgeItemGraphPermissions::registerNodes);
        ItemGraphPermissions.setChecker(NeoForgeItemGraphPermissions::check);
    }

    private static void registerNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES.toArray(PermissionNode<?>[]::new));
    }

    private static PermissionNode<Boolean> node(String name) {
        return new PermissionNode<>("itemgraph", name.substring("itemgraph.".length()), PermissionTypes.BOOLEAN,
                (player, playerId, context) -> player != null
                        && player.createCommandSourceStack().hasPermission(2));
    }

    private static boolean check(CommandSourceStack source, String name) {
        PermissionNode<Boolean> node = BY_NAME.get(name);
        if (node == null) {
            return false;
        }
        if (source.getEntity() instanceof ServerPlayer player) {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, node));
        }
        // PermissionAPI only accepts players; console and command blocks retain vanilla's level-2 check.
        return source.hasPermission(2);
    }
}
