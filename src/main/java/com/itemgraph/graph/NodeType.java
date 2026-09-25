package com.itemgraph.graph;

/**
 * Canonical set of inventory-node kinds stored in {@code ig_nodes.node_type}.
 *
 * <p>These names are the authoritative values for that column and must match the
 * schema comment documented in {@code docs/PHASE0_RECON_REPORT.md}, plus the V12
 * preview-API type:
 * {@code 'PLAYER', 'CONTAINER', 'GROUND', 'ARMOR_STAND', 'EXTERNAL_INVENTORY', 'UNKNOWN'}.
 *
 * <ul>
 *   <li>{@link #PLAYER} — a player's own inventory, identified by UUID where available.</li>
 *   <li>{@link #CONTAINER} — a block container, identified by level + block coordinate.</li>
 *   <li>{@link #GROUND} — the world itself at a block coordinate: dropped/thrown/shot
 *       item entities live here between a drop and the pickup that removes them.</li>
 *   <li>{@link #ARMOR_STAND} — an armor stand's equipment slots, identified by level +
 *       block coordinate. Reserved for Phase 8: GriefLogger has <em>zero</em> event
 *       coverage for armor stands (confirmed in Phase 0 recon), so nothing currently
 *       produces these nodes during ingestion.</li>
 *   <li>{@link #EXTERNAL_INVENTORY} — a preview-API inventory identified durably by
 *       {@code external_key = <ownerModId>/<inventoryId>}. Its last-known location is
 *       context only and never determines identity.</li>
 *   <li>{@link #UNKNOWN} — a per-level sentinel used when an action's other endpoint
 *       genuinely cannot be determined from GriefLogger evidence alone (e.g. the
 *       materials consumed by CRAFT_ITEM, or the destination of CONSUME_ITEM). It is
 *       an explicit "unresolved", never a guess.</li>
 * </ul>
 */
public enum NodeType {
    PLAYER,
    CONTAINER,
    GROUND,
    ARMOR_STAND,
    EXTERNAL_INVENTORY,
    UNKNOWN
}
