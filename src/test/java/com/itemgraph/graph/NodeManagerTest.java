package com.itemgraph.graph;

import com.itemgraph.db.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class NodeManagerTest {

    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private NodeManager nodeManager;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        dbManager = DatabaseManager.getInstance();
        dbManager.initialize(tempDir.resolve("itemgraph.db"));
        conn = dbManager.getConnection();
        nodeManager = new NodeManager();
    }

    @AfterEach
    void tearDown() {
        if (dbManager != null) {
            dbManager.close();
        }
    }

    private String nodeTypeOf(long id) throws Exception {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT node_type FROM ig_nodes WHERE id = ?")) {
            pstmt.setLong(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next(), "node " + id + " should exist");
                return rs.getString("node_type");
            }
        }
    }

    private long countNodes(String nodeType) throws Exception {
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM ig_nodes WHERE node_type = ?")) {
            pstmt.setString(1, nodeType);
            try (ResultSet rs = pstmt.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    @Test
    void testGroundNodeIsStableForSameBlock() throws Exception {
        long first = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);
        long second = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);

        assertEquals(first, second, "same coordinates must resolve to the same ground node");
        assertEquals(NodeType.GROUND.name(), nodeTypeOf(first));
        assertEquals(1, countNodes(NodeType.GROUND.name()));
    }

    @Test
    void testGroundNodeBucketsFractionalCoordinatesToBlock() throws Exception {
        // A dropped item entity rests at a fractional position; every position inside
        // the same block must collapse onto one ground node so a drop and the pickup
        // that recovers it share an endpoint.
        long a = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10.0, 64.0, -20.0);
        long b = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10.9, 64.4, -19.1);

        assertEquals(a, b, "positions within the same block must bucket to one ground node");
        assertEquals(1, countNodes(NodeType.GROUND.name()));
    }

    @Test
    void testGroundNodeBucketingUsesFloorForNegativeCoordinates() throws Exception {
        // floor(-20.5) == -21, not -20: truncation would merge two distinct blocks.
        long lower = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20.5);
        long upper = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20.0);

        assertNotEquals(lower, upper, "floor() must separate -20.5 (block -21) from -20.0 (block -20)");
        assertEquals(2, countNodes(NodeType.GROUND.name()));
    }

    @Test
    void testDifferentCoordinatesGetDifferentGroundNodes() throws Exception {
        long a = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);
        long b = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 11, 64, -20);

        assertNotEquals(a, b);
        assertEquals(2, countNodes(NodeType.GROUND.name()));
    }

    @Test
    void testDifferentLevelsNeverShareAGroundNode() throws Exception {
        long overworld = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);
        long nether = nodeManager.getOrCreateGroundNode(conn, NETHER, 10, 64, -20);

        assertNotEquals(overworld, nether, "identical coordinates in different dimensions are different places");
        assertEquals(2, countNodes(NodeType.GROUND.name()));
    }

    @Test
    void testGroundNodeSurvivesCacheLoss() throws Exception {
        long first = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);

        // A fresh NodeManager (e.g. after a server restart) must resolve the same row
        // from the database rather than inserting a duplicate.
        NodeManager cold = new NodeManager();
        long second = cold.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);

        assertEquals(first, second);
        assertEquals(1, countNodes(NodeType.GROUND.name()));
    }

    @Test
    void testUnknownNodeIsOneSentinelPerLevel() throws Exception {
        long first = nodeManager.getOrCreateUnknownNode(conn, OVERWORLD);
        long second = nodeManager.getOrCreateUnknownNode(conn, OVERWORLD);

        assertEquals(first, second, "the UNKNOWN sentinel must be a single node per level");
        assertEquals(NodeType.UNKNOWN.name(), nodeTypeOf(first));
        assertEquals(1, countNodes(NodeType.UNKNOWN.name()));
    }

    @Test
    void testUnknownNodeIsPerLevelAndCarriesNoCoordinates() throws Exception {
        long overworld = nodeManager.getOrCreateUnknownNode(conn, OVERWORLD);
        long nether = nodeManager.getOrCreateUnknownNode(conn, NETHER);

        assertNotEquals(overworld, nether);
        assertEquals(2, countNodes(NodeType.UNKNOWN.name()));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT x, y, z FROM ig_nodes WHERE node_type = 'UNKNOWN'")) {
            while (rs.next()) {
                rs.getDouble("x");
                assertTrue(rs.wasNull(), "UNKNOWN nodes must not claim a position");
                rs.getDouble("y");
                assertTrue(rs.wasNull(), "UNKNOWN nodes must not claim a position");
                rs.getDouble("z");
                assertTrue(rs.wasNull(), "UNKNOWN nodes must not claim a position");
            }
        }
    }

    @Test
    void testUnknownNodeSurvivesCacheLoss() throws Exception {
        long first = nodeManager.getOrCreateUnknownNode(conn, OVERWORLD);

        NodeManager cold = new NodeManager();
        long second = cold.getOrCreateUnknownNode(conn, OVERWORLD);

        assertEquals(first, second);
        assertEquals(1, countNodes(NodeType.UNKNOWN.name()));
    }

    @Test
    void testArmorStandNodeCreationIsStableAndIndependent() throws Exception {
        long first = nodeManager.getOrCreateArmorStandNode(conn, OVERWORLD, 10, 64, -20);
        long second = nodeManager.getOrCreateArmorStandNode(conn, OVERWORLD, 10, 64, -20);

        assertEquals(first, second, "same coordinates must resolve to the same armor stand node");
        assertEquals(NodeType.ARMOR_STAND.name(), nodeTypeOf(first));

        // An armor stand, the ground beneath it, and a chest at the same block are
        // three distinct inventories and must never collapse into one node.
        long ground = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 10, 64, -20);
        long container = nodeManager.getOrCreateContainerNode(conn, OVERWORLD, 10, 64, -20);

        assertNotEquals(first, ground);
        assertNotEquals(first, container);
        assertNotEquals(ground, container);

        assertEquals(1, countNodes(NodeType.ARMOR_STAND.name()));
        assertEquals(1, countNodes(NodeType.GROUND.name()));
        assertEquals(1, countNodes(NodeType.CONTAINER.name()));
    }

    @Test
    void testArmorStandNodesAreSeparatePerLevel() throws Exception {
        long overworld = nodeManager.getOrCreateArmorStandNode(conn, OVERWORLD, 10, 64, -20);
        long nether = nodeManager.getOrCreateArmorStandNode(conn, NETHER, 10, 64, -20);

        assertNotEquals(overworld, nether);
        assertEquals(2, countNodes(NodeType.ARMOR_STAND.name()));
    }

    @Test
    void testPlayerNodeIdentityIsByUuidNotPosition() throws Exception {
        long first = nodeManager.getOrCreatePlayerNode(conn, "uuid-1", "zKampeR", OVERWORLD, 0, 64, 0);
        // Same player, later, somewhere else entirely - still one node.
        long second = nodeManager.getOrCreatePlayerNode(conn, "uuid-1", "zKampeR", NETHER, 900, 12, -33);

        assertEquals(first, second);
        assertEquals(NodeType.PLAYER.name(), nodeTypeOf(first));
        assertEquals(1, countNodes(NodeType.PLAYER.name()));
    }

    @Test
    void testBlankLevelFallsBackToOverworld() throws Exception {
        long explicit = nodeManager.getOrCreateGroundNode(conn, OVERWORLD, 5, 64, 5);

        NodeManager cold = new NodeManager();
        long implicit = cold.getOrCreateGroundNode(conn, null, 5, 64, 5);

        assertEquals(explicit, implicit, "a null level must normalize to minecraft:overworld");
        assertEquals(1, countNodes(NodeType.GROUND.name()));
    }
}
