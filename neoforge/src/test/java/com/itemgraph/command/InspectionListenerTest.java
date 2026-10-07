package com.itemgraph.command;

import com.itemgraph.listener.ContainerSessionListener;
import com.itemgraph.listener.NativeAuditEventListener;
import com.itemgraph.ingest.InternalObservationService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InspectionListenerTest {

    private static final BlockPos CONTAINER_POS = new BlockPos(10, 64, -20);
    private final InspectionService service = InspectionService.getInstance();

    @BeforeAll
    static void initMinecraftRegistries() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.Map.of());
        }
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
        }
    }

    @AfterEach
    void tearDown() {
        service.clear();
        ItemGraphPermissions.setChecker(null);
    }

    @Test
    void explicitInspectDenialDisablesModeNotifiesAndConsumesClick() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.INSPECT));
        AtomicInteger opens = new AtomicInteger();
        InspectionListener inspector = new InspectionListener(service,
                (p, targetLevel, pos) -> { opens.incrementAndGet(); return 1; });
        PlayerInteractEvent.LeftClickBlock event = leftClick(player, CONTAINER_POS);

        inspector.onLeftClickBlock(event);

        assertFalse(service.isEnabled(playerUuid));
        assertTrue(event.isCanceled(), "the revocation-detecting click must not break the inspected block");
        assertEquals(0, opens.get(), "the explicit node denial must win even when vanilla level 2 is present");
        verify(player).sendSystemMessage(any(net.minecraft.network.chat.Component.class));
    }

    @Test
    void activeSupportedClickCancelsVanillaInteractionAndOpensExactContainer() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            assertSame(player, openingPlayer);
            assertSame(level, openingLevel);
            assertEquals(CONTAINER_POS, clickedPos);
            opens.incrementAndGet();
            return 1;
        }).onRightClickBlock(event);

        assertTrue(event.isCanceled());
        assertEquals(InteractionResult.SUCCESS, event.getCancellationResult());
        assertEquals(1, opens.get());
    }

    @Test
    void containerRightClickRoutesToFlowBrowserAndKeepsExactDimensionAndPosition() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(level.dimension()).thenReturn(Level.NETHER);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        AtomicInteger blockHistoryOpens = new AtomicInteger();
        AtomicInteger flowBrowserOpens = new AtomicInteger();

        new InspectionListener(service,
                (p, l, pos) -> { blockHistoryOpens.incrementAndGet(); return 1; },
                (openingPlayer, openingLevel, clickedPos) -> {
                    assertSame(player, openingPlayer);
                    assertSame(level, openingLevel);
                    assertEquals(CONTAINER_POS, clickedPos);
                    assertEquals(Level.NETHER, openingLevel.dimension());
                    flowBrowserOpens.incrementAndGet();
                    return 1;
                }).onRightClickBlock(event);

        assertTrue(event.isCanceled());
        assertEquals(0, blockHistoryOpens.get());
        assertEquals(1, flowBrowserOpens.get());
    }

    @Test
    void bothDoubleChestHalvesOpenTheCanonicalFlowBrowserTarget() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        var leftState = Blocks.CHEST.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                        net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        var rightState = leftState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        BlockPos left = CONTAINER_POS;
        BlockPos right = left.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(leftState));
        BlockPos anchor = left.getX() <= right.getX() ? left : right;
        when(level.getBlockState(left)).thenReturn(leftState);
        when(level.getBlockState(right)).thenReturn(rightState);
        BlockEntity chest = mock(BlockEntity.class, withSettings().extraInterfaces(Container.class));
        when(level.getBlockEntity(left)).thenReturn(chest);
        when(level.getBlockEntity(right)).thenReturn(chest);
        when(player.level()).thenReturn(level);
        InspectionListener inspector = new InspectionListener(service,
                (p, l, pos) -> fail("double chest right-click must open the flow browser"),
                (p, l, pos) -> { assertEquals(anchor, pos); return 1; });

        inspector.onRightClickBlock(rightClick(player, left));
        inspector.onRightClickBlock(rightClick(player, right));
    }

    @Test
    void rejectedContainerFlowQueryPreservesVanillaContainerInteraction() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        AtomicInteger blockHistoryOpens = new AtomicInteger();
        AtomicInteger flowBrowserOpens = new AtomicInteger();

        new InspectionListener(service,
                (p, l, pos) -> { blockHistoryOpens.incrementAndGet(); return 1; },
                (p, l, pos) -> { flowBrowserOpens.incrementAndGet(); return 0; }).onRightClickBlock(event);

        assertFalse(event.isCanceled());
        assertEquals(0, blockHistoryOpens.get(), "rejected flow must not silently switch query types");
        assertEquals(1, flowBrowserOpens.get());
    }

    @Test
    void inactiveAndRejectedClicksPreserveVanillaBehavior() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = permittedPlayer(playerUuid);
        AtomicInteger opens = new AtomicInteger();
        InspectionListener listener = listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            opens.incrementAndGet();
            return 0;
        });

        Level unsupportedLevel = mock(Level.class);
        when(unsupportedLevel.isClientSide()).thenReturn(false);
        when(unsupportedLevel.getBlockEntity(CONTAINER_POS)).thenReturn(mock(BlockEntity.class));
        when(player.level()).thenReturn(unsupportedLevel);

        PlayerInteractEvent.RightClickBlock inactive = rightClick(player, CONTAINER_POS);
        listener.onRightClickBlock(inactive);
        assertFalse(inactive.isCanceled());

        service.setEnabled(playerUuid, true);
        when(unsupportedLevel.getBlockState(CONTAINER_POS)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        PlayerInteractEvent.RightClickBlock unsupported = rightClick(player, CONTAINER_POS);

        listener.onRightClickBlock(unsupported);
        assertFalse(unsupported.isCanceled());
        assertEquals(1, opens.get());
    }

    @Test
    void permissionLossDisablesModeNotifiesAndConsumesDetectionClick() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = player(playerUuid, false);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            opens.incrementAndGet();
            return 1;
        }).onRightClickBlock(event);

        assertTrue(event.isCanceled(), "the revocation-detecting click is consumed so nothing mutates unexpectedly");
        assertEquals(InteractionResult.FAIL, event.getCancellationResult());
        assertFalse(service.isEnabled(playerUuid));
        assertEquals(0, opens.get());
        var notice = org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
        verify(player).sendSystemMessage(notice.capture());
        assertTrue(notice.getValue().getString().contains("permission was revoked"),
                "the revocation notice must explain why inspection stopped");
    }

    @Test
    void deniedAuditLeftClickIsConsumedAndPreservesTheBlock() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.AUDIT));
        AtomicInteger opens = new AtomicInteger();
        PlayerInteractEvent.LeftClickBlock event = leftClick(player, CONTAINER_POS);

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            opens.incrementAndGet();
            return 0;
        }).onLeftClickBlock(event);

        assertTrue(event.isCanceled(), "a stable permission denial must not let the click break the block");
        assertEquals(1, opens.get());
    }

    @Test
    void deniedAuditNonContainerRightClickIsConsumed() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockEntity(CONTAINER_POS)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(CONTAINER_POS)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        when(player.level()).thenReturn(level);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.AUDIT));
        AtomicInteger opens = new AtomicInteger();
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            opens.incrementAndGet();
            return 0;
        }).onRightClickBlock(event);

        assertTrue(event.isCanceled(), "a denied non-container right-click must not toggle or place");
        assertEquals(InteractionResult.FAIL, event.getCancellationResult());
        assertEquals(1, opens.get());
    }

    @Test
    void deniedAuditContainerRightClickPreservesVanillaAccess() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.AUDIT));
        AtomicInteger opens = new AtomicInteger();
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            opens.incrementAndGet();
            return 0;
        }).onRightClickBlock(event);

        assertFalse(event.isCanceled(), "rejected container right-clicks still open the chest normally");
        assertEquals(1, opens.get());
    }

    @Test
    void rejectedContainerInspectionPreservesTheVanillaInteraction() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
            opens.incrementAndGet();
            return 0;
        }).onRightClickBlock(event);

        assertFalse(event.isCanceled());
        assertEquals(1, opens.get());
    }

    @Test
    void activeRightClickOpensExactUnifiedHistoryAndCancelsInteraction() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockEntity(CONTAINER_POS)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(CONTAINER_POS)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
                    assertSame(player, openingPlayer);
                    assertSame(level, openingLevel);
                    assertEquals(CONTAINER_POS, clickedPos);
                    opens.incrementAndGet();
                    return 1;
                }).onRightClickBlock(event);

        assertTrue(event.isCanceled());
        assertEquals(InteractionResult.SUCCESS, event.getCancellationResult());
        assertEquals(1, opens.get());
    }

    @Test
    void containerInspectionCancellationDoesNotEmitGameplayInteractionEvidence() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        net.minecraft.server.level.ServerLevel level = mock(net.minecraft.server.level.ServerLevel.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockEntity(CONTAINER_POS)).thenReturn(mock(BlockEntity.class,
                org.mockito.Mockito.withSettings().extraInterfaces(Container.class)));
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        InspectionListener inspector = new InspectionListener(service,
                (p, l, pos) -> fail("container inspection must not run block-history lookup"),
                (p, l, pos) -> 1);
        InternalObservationService observations = mock(InternalObservationService.class);

        try (org.mockito.MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(observations);
            inspector.onRightClickBlock(event);
            assertTrue(event.isCanceled());
            new NativeAuditEventListener().onBlockInteract(event);
        }

        verifyNoInteractions(observations);
    }

    @Test
    void transientlyRejectedNonContainerRightClickPreservesGameplay() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockEntity(CONTAINER_POS)).thenReturn(mock(BlockEntity.class));
        when(level.getBlockState(CONTAINER_POS)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);

        listener(service, (openingPlayer, openingLevel, clickedPos) -> 0).onRightClickBlock(event);

        assertFalse(event.isCanceled());
    }

    @Test
    void offHandInspectionClickIsConsumedWithoutOpeningHistory() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, InteractionHand.OFF_HAND, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (p, l, pos) -> { opens.incrementAndGet(); return 1; }).onRightClickBlock(event);

        // The off-hand twin of an inspection click must not place or use the
        // off-hand item, but it must not open a second history view either.
        assertTrue(event.isCanceled());
        assertEquals(0, opens.get());
    }

    @Test
    void offHandClickFallsThroughWhenNotInspecting() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, InteractionHand.OFF_HAND, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (p, l, pos) -> { opens.incrementAndGet(); return 1; }).onRightClickBlock(event);

        assertFalse(event.isCanceled());
        assertEquals(0, opens.get());
    }

    @Test
    void rightClickItemIsConsumedWhileInspecting() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(CONTAINER_POS);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        PlayerInteractEvent.RightClickItem event =
                new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);

        listener(service, (p, l, pos) -> 1).onRightClickItem(event);

        assertTrue(event.isCanceled());
        assertEquals(InteractionResult.FAIL, event.getCancellationResult());
        verify(player.containerMenu).sendAllDataToRemote();
    }

    @Test
    void rightClickItemPassesWhenNotInspecting() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        when(player.blockPosition()).thenReturn(CONTAINER_POS);
        PlayerInteractEvent.RightClickItem event =
                new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);

        listener(service, (p, l, pos) -> 1).onRightClickItem(event);

        assertFalse(event.isCanceled());
    }

    @Test
    void rightClickItemTwinIsConsumedAfterRevocationClearedTheMode() {
        // The revocation-detecting block click clears the mode before its twin
        // item-use packet arrives, so the consumed-interaction marker — not
        // isEnabled — must still consume the twin.
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenReturn(100L);
        when(player.level()).thenReturn(level);
        ItemGraphPermissions.setChecker((source, node) -> !node.equals(ItemGraphPermissions.INSPECT));
        InspectionListener inspector = listener(service, (p, l, pos) -> 1);
        inspector.onRightClickBlock(rightClick(player, CONTAINER_POS));

        when(player.blockPosition()).thenReturn(CONTAINER_POS);
        player.containerMenu = mock(net.minecraft.world.inventory.AbstractContainerMenu.class);
        PlayerInteractEvent.RightClickItem twin =
                new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        inspector.onRightClickItem(twin);

        assertFalse(service.isEnabled(playerUuid));
        assertTrue(twin.isCanceled());
    }

    @Test
    void rightClickItemPassesAfterConsumedMarkerExpires() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getGameTime()).thenReturn(0L);
        when(player.level()).thenReturn(level);
        service.markInteractionConsumed(playerUuid, 0L);

        // A packet arriving well past the twin-packet window is an unrelated use.
        when(level.getGameTime()).thenReturn(0L + InspectionService.CONSUMED_PACKET_WINDOW_TICKS + 1);
        when(player.blockPosition()).thenReturn(CONTAINER_POS);
        PlayerInteractEvent.RightClickItem event =
                new PlayerInteractEvent.RightClickItem(player, InteractionHand.MAIN_HAND);
        listener(service, (p, l, pos) -> 1).onRightClickItem(event);

        assertFalse(event.isCanceled());
    }

    @Test
    void ordinaryBlockClickInspectsAdjacentFaceTarget() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockState(CONTAINER_POS)).thenReturn(Blocks.STONE.defaultBlockState());
        BlockPos target = CONTAINER_POS.relative(Direction.EAST);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS, Direction.EAST);

        listener(service, (p, l, pos) -> { assertEquals(target, pos); return 1; }).onRightClickBlock(event);

        assertTrue(event.isCanceled());
    }

    @Test
    void transientlyRejectedOrdinaryBlockFallbackPreservesGameplay() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockState(CONTAINER_POS)).thenReturn(Blocks.STONE.defaultBlockState());
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS, Direction.EAST);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (p, l, target) -> { opens.incrementAndGet(); return 0; }).onRightClickBlock(event);

        assertFalse(event.isCanceled());
        assertEquals(1, opens.get());
    }

    @Test
    void activeLeftClickOpensExactBlockHistoryAndCancelsBreaking() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.LeftClickBlock event = leftClick(player, CONTAINER_POS);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
                    assertSame(player, openingPlayer);
                    assertSame(level, openingLevel);
                    assertEquals(CONTAINER_POS, clickedPos);
                    opens.incrementAndGet();
                    return 1;
                }).onLeftClickBlock(event);

        assertTrue(event.isCanceled());
        assertEquals(1, opens.get());
    }

    @Test
    void transientlyRejectedLeftClickPreservesBlockBreaking() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.LeftClickBlock event = leftClick(player, CONTAINER_POS);

        listener(service, (openingPlayer, openingLevel, clickedPos) -> 0).onLeftClickBlock(event);

        assertFalse(event.isCanceled());
    }

    @Test
    void repeatedLeftClickHoldPacketsDoNotOpenAnotherInspection() {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = mock(Level.class);
        when(level.isClientSide()).thenReturn(false);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.LeftClickBlock event = new PlayerInteractEvent.LeftClickBlock(
                player, CONTAINER_POS, Direction.NORTH,
                PlayerInteractEvent.LeftClickBlock.Action.STOP);
        AtomicInteger opens = new AtomicInteger();

        listener(service, (openingPlayer, openingLevel, clickedPos) -> {
                    opens.incrementAndGet();
                    return 1;
                }).onLeftClickBlock(event);

        assertFalse(event.isCanceled());
        assertEquals(0, opens.get());
    }

    @Test
    void logoutClearsOnlyThatPlayersInspectionMode() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        service.setEnabled(first, true);
        service.setEnabled(second, true);
        ServerPlayer player = permittedPlayer(first);

        new InspectionListener().onPlayerLogout(new PlayerEvent.PlayerLoggedOutEvent(player));

        assertFalse(service.isEnabled(first));
        assertTrue(service.isEnabled(second));
    }

    @Test
    void canceledInspectionClickIsNotRememberedAsAContainerSessionOpening() throws Exception {
        UUID playerUuid = UUID.randomUUID();
        service.setEnabled(playerUuid, true);
        ServerPlayer player = permittedPlayer(playerUuid);
        Level level = serverLevelWithContainer(CONTAINER_POS);
        when(player.level()).thenReturn(level);
        PlayerInteractEvent.RightClickBlock event = rightClick(player, CONTAINER_POS);
        event.setCanceled(true);

        ContainerSessionListener sessionListener = new ContainerSessionListener();
        sessionListener.onRightClickBlock(event);

        Field pendingClicks = ContainerSessionListener.class.getDeclaredField("pendingClicks");
        pendingClicks.setAccessible(true);
        assertTrue(((Map<?, ?>) pendingClicks.get(sessionListener)).isEmpty());
    }

    private InspectionListener listener(InspectionService inspections, InspectionListener.BlockHistoryOpener opener) {
        return new InspectionListener(inspections, opener);
    }

    private ServerPlayer permittedPlayer(UUID uuid) {
        return player(uuid, true);
    }

    private ServerPlayer player(UUID uuid, boolean permitted) {
        ServerPlayer player = mock(ServerPlayer.class);
        var source = mock(net.minecraft.commands.CommandSourceStack.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.createCommandSourceStack()).thenReturn(source);
        when(source.hasPermission(2)).thenReturn(permitted);
        return player;
    }

    private Level serverLevelWithContainer(BlockPos pos) {
        Level level = mock(Level.class);
        BlockEntity container = mock(BlockEntity.class,
                withSettings().extraInterfaces(Container.class));
        when(level.isClientSide()).thenReturn(false);
        when(level.getBlockEntity(pos)).thenReturn(container);
        return level;
    }

    private PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos) {
        return rightClick(player, InteractionHand.MAIN_HAND, pos);
    }

    private PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, InteractionHand hand, BlockPos pos) {
        return new PlayerInteractEvent.RightClickBlock(
                player, hand, pos, mock(BlockHitResult.class));
    }

    private PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos, Direction face) {
        BlockHitResult hit = mock(BlockHitResult.class);
        when(hit.getDirection()).thenReturn(face);
        return new PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
    }

    private PlayerInteractEvent.LeftClickBlock leftClick(ServerPlayer player, BlockPos pos) {
        return new PlayerInteractEvent.LeftClickBlock(player, pos, Direction.NORTH,
                PlayerInteractEvent.LeftClickBlock.Action.START);
    }
}
