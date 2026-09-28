package com.itemgraph.command;

import com.itemgraph.listener.ContainerSessionListener;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
    void inactiveAndUnsupportedClicksPreserveVanillaBehavior() {
        UUID playerUuid = UUID.randomUUID();
        ServerPlayer player = permittedPlayer(playerUuid);
        AtomicInteger opens = new AtomicInteger();
        InspectionListener listener = listener(service,
                (openingPlayer, openingLevel, clickedPos) -> {
                    opens.incrementAndGet();
                    return 1;
                });

        Level unsupportedLevel = mock(Level.class);
        when(unsupportedLevel.isClientSide()).thenReturn(false);
        when(unsupportedLevel.getBlockEntity(CONTAINER_POS)).thenReturn(mock(BlockEntity.class));
        when(player.level()).thenReturn(unsupportedLevel);

        PlayerInteractEvent.RightClickBlock inactive = rightClick(player, CONTAINER_POS);
        listener.onRightClickBlock(inactive);
        assertFalse(inactive.isCanceled());

        service.setEnabled(playerUuid, true);
        PlayerInteractEvent.RightClickBlock unsupported = rightClick(player, CONTAINER_POS);

        listener.onRightClickBlock(unsupported);
        assertFalse(unsupported.isCanceled());
        assertEquals(0, opens.get());
    }

    @Test
    void permissionLossDisablesModeWithoutSuppressingVanillaInteraction() {
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

        assertFalse(event.isCanceled());
        assertFalse(service.isEnabled(playerUuid));
        assertEquals(0, opens.get());
    }

    @Test
    void rejectedBrowserOpenPreservesTheVanillaInteraction() {
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

    private InspectionListener listener(InspectionService inspections, InspectionListener.BrowserOpener opener) {
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
        return new PlayerInteractEvent.RightClickBlock(
                player, InteractionHand.MAIN_HAND, pos, mock(BlockHitResult.class));
    }
}
