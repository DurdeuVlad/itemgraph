package com.itemgraph.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves stable {@code ig_nodes} identities for every {@link NodeType}.
 *
 * <p>Node identity is the anchor for all downstream correlation: two observations can
 * only be linked into an inferred edge if they share a node. Each resolver is
 * get-or-create and idempotent, so re-ingesting the same GriefLogger rows reuses the
 * existing node rather than creating duplicates.
 *
 * <p>Identity keys per type:
 * <ul>
 *   <li>PLAYER — {@code owner_uuid} when GriefLogger recorded one, else {@code custom_label}
 *       (the username). Coordinates on a player node are only the first-seen position and
 *       are <em>not</em> part of its identity.</li>
 *   <li>CONTAINER / GROUND / ARMOR_STAND — {@code level_id} plus the block coordinate the
 *       raw coordinates floor into. Bucketing to a block is deliberate: GriefLogger logs
 *       integer block coordinates for containers, and a dropped stack is spatially
 *       localized to the block it landed on.</li>
 *   <li>UNKNOWN — one sentinel node per level, with no coordinates at all.</li>
 * </ul>
 *
 * <p>Caches are per-instance and are pure read-through accelerators keyed by the same
 * identity string used in the SQL lookup; dropping them only costs an extra SELECT.
 */
public class NodeManager {

    private final Map<String, Long> playerNodeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> containerNodeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> groundNodeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> armorStandNodeCache = new ConcurrentHashMap<>();
    private final Map<String, Long> unknownNodeCache = new ConcurrentHashMap<>();

    public NodeManager() {
    }

    /**
     * Drops all identity caches. The database remains the source of truth, so this only
     * forces the next resolution of each node to go back to SQL.
     */
    public void clearCaches() {
        playerNodeCache.clear();
        containerNodeCache.clear();
        groundNodeCache.clear();
        armorStandNodeCache.clear();
        unknownNodeCache.clear();
    }

    private static String normalizeLevel(String level) {
        return (level != null && !level.isBlank()) ? level : "minecraft:overworld";
    }

    private static String blockKey(String levelId, int x, int y, int z) {
        return levelId + ":" + x + ":" + y + ":" + z;
    }

    public long getOrCreatePlayerNode(Connection conn, String uuid, String name, String level, double x, double y, double z) throws SQLException {
        String levelId = normalizeLevel(level);
        String customLabel = (name != null && !name.isBlank()) ? name : "unknown";

        String cacheKey = (uuid != null && !uuid.isBlank()) ? uuid : ("name:" + customLabel);
        Long cached = playerNodeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (uuid != null && !uuid.isBlank()) {
            String selectSql = "SELECT id FROM ig_nodes WHERE node_type = '" + NodeType.PLAYER.name() + "' AND owner_uuid = ? LIMIT 1";
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, uuid);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }

            String insertSql = "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, x, y, z, custom_label) VALUES ('" + NodeType.PLAYER.name() + "', ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, uuid);
                pstmt.setString(2, levelId);
                pstmt.setDouble(3, x);
                pstmt.setDouble(4, y);
                pstmt.setDouble(5, z);
                pstmt.setString(6, customLabel);
                pstmt.executeUpdate();
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }
        } else {
            String selectSql = "SELECT id FROM ig_nodes WHERE node_type = '" + NodeType.PLAYER.name() + "' AND custom_label = ? LIMIT 1";
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, customLabel);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }

            String insertSql = "INSERT INTO ig_nodes (node_type, owner_uuid, level_id, x, y, z, custom_label) VALUES ('" + NodeType.PLAYER.name() + "', NULL, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                pstmt.setString(1, levelId);
                pstmt.setDouble(2, x);
                pstmt.setDouble(3, y);
                pstmt.setDouble(4, z);
                pstmt.setString(5, customLabel);
                pstmt.executeUpdate();
                try (ResultSet keys = pstmt.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        playerNodeCache.put(cacheKey, id);
                        return id;
                    }
                }
            }
        }

        throw new SQLException("Failed to create player node for " + customLabel);
    }

    public long getOrCreateContainerNode(Connection conn, String level, double x, double y, double z) throws SQLException {
        return getOrCreateLocatedNode(conn, NodeType.CONTAINER, containerNodeCache, level, x, y, z);
    }

    /**
     * Resolves the GROUND node for a world position: the "item is lying in the world"
     * endpoint shared by a DROP_ITEM and the PICKUP_ITEM that later recovers it.
     * Bucketed to the block the coordinates floor into, exactly like container nodes.
     */
    public long getOrCreateGroundNode(Connection conn, String level, double x, double y, double z) throws SQLException {
        return getOrCreateLocatedNode(conn, NodeType.GROUND, groundNodeCache, level, x, y, z);
    }

    /**
     * Resolves the ARMOR_STAND node at a world position.
     *
     * <p>Deliberately not wired into ingestion: GriefLogger has zero armor stand event
     * coverage (Phase 0 recon), so there is no evidence source to anchor to one yet.
     * This exists so the Phase 8 armor stand integration has an identity resolver
     * already in place and under test.
     */
    public long getOrCreateArmorStandNode(Connection conn, String level, double x, double y, double z) throws SQLException {
        return getOrCreateLocatedNode(conn, NodeType.ARMOR_STAND, armorStandNodeCache, level, x, y, z);
    }

    /**
     * Resolves the single UNKNOWN sentinel node for a level.
     *
     * <p>Used as the unresolved endpoint of an action whose other side cannot be
     * determined from GriefLogger data alone (CRAFT_ITEM's source materials,
     * CONSUME_ITEM/BREAK_ITEM's destination, a bare ADD_ITEM's origin). Carries no
     * coordinates, because the whole point is that the position is not known either.
     */
    public long getOrCreateUnknownNode(Connection conn, String level) throws SQLException {
        String levelId = normalizeLevel(level);

        Long cached = unknownNodeCache.get(levelId);
        if (cached != null) {
            return cached;
        }

        String selectSql = "SELECT id FROM ig_nodes WHERE node_type = '" + NodeType.UNKNOWN.name() + "' AND level_id = ? AND x IS NULL AND y IS NULL AND z IS NULL LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, levelId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    unknownNodeCache.put(levelId, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT INTO ig_nodes (node_type, level_id, x, y, z, custom_label) VALUES ('" + NodeType.UNKNOWN.name() + "', ?, NULL, NULL, NULL, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, levelId);
            pstmt.setString(2, "unknown holder (" + levelId + ")");
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    unknownNodeCache.put(levelId, id);
                    return id;
                }
            }
        }

        throw new SQLException("Failed to create unknown node for level " + levelId);
    }

    /**
     * Shared get-or-create for every coordinate-anchored node type. The node_type is
     * part of the identity key, so a GROUND node and a CONTAINER node at the same
     * block are distinct nodes (a chest and the floor in front of it are not the
     * same inventory).
     */
    private long getOrCreateLocatedNode(Connection conn, NodeType type, Map<String, Long> cache,
                                        String level, double x, double y, double z) throws SQLException {
        String levelId = normalizeLevel(level);
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        String cacheKey = blockKey(levelId, ix, iy, iz);

        Long cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String selectSql = "SELECT id FROM ig_nodes WHERE node_type = '" + type.name() + "' AND level_id = ? AND x = ? AND y = ? AND z = ? LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setString(1, levelId);
            pstmt.setDouble(2, ix);
            pstmt.setDouble(3, iy);
            pstmt.setDouble(4, iz);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong("id");
                    cache.put(cacheKey, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT INTO ig_nodes (node_type, level_id, x, y, z) VALUES ('" + type.name() + "', ?, ?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, levelId);
            pstmt.setDouble(2, ix);
            pstmt.setDouble(3, iy);
            pstmt.setDouble(4, iz);
            pstmt.executeUpdate();
            try (ResultSet keys = pstmt.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    cache.put(cacheKey, id);
                    return id;
                }
            }
        }

        throw new SQLException("Failed to create " + type.name() + " node at " + cacheKey);
    }
}
