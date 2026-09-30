package com.itemgraph.listener;

import com.itemgraph.ingest.InternalObservationService;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.CommandContextBuilder;
import net.minecraft.SharedConstants;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.Bootstrap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import com.mojang.authlib.GameProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

class NativeAuditEventListenerTest {

    @BeforeAll
    static void initMinecraft() {
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
    void canceledCommandIsNotRecordedAsAttempt() {
        CommandEvent event = mock(CommandEvent.class);
        when(event.isCanceled()).thenReturn(true);
        assertNotRecorded(listener -> listener.onCommand(event));
    }

    @Test
    void commandHookRecordsAnAttemptBecauseTheEventPrecedesExecution() {
        ParseResults<CommandSourceStack> parse = mock(ParseResults.class);
        CommandContextBuilder<CommandSourceStack> context = mock(CommandContextBuilder.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        net.minecraft.server.level.ServerPlayer player = mock(net.minecraft.server.level.ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(parse.getContext()).thenReturn(context);
        when(parse.getReader()).thenReturn(new StringReader("give Alex dirt"));
        when(context.getSource()).thenReturn(source);
        when(source.getEntity()).thenReturn(player);
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(player.blockPosition()).thenReturn(BlockPos.ZERO);
        UUID playerUuid = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            new NativeAuditEventListener().onCommand(new CommandEvent(parse));
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("COMMAND_ATTEMPT", captured.getValue().eventType());
    }

    @Test
    void canceledChatIsNotRecorded() {
        ServerChatEvent event = mock(ServerChatEvent.class);
        when(event.isCanceled()).thenReturn(true);
        assertNotRecorded(listener -> listener.onChat(event));
    }

    @Test
    void canceledDeathIsNotRecordedAsKill() {
        LivingDeathEvent event = mock(LivingDeathEvent.class);
        when(event.isCanceled()).thenReturn(true);
        assertNotRecorded(listener -> listener.onLivingDeath(event));
    }

    @Test
    void blockInteractionRecordsOnlyMainHandPinnedFunctionalAttempts() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        BlockPos pos = new BlockPos(4, 64, 9);
        UUID playerUuid = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));

        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getBlockState(pos)).thenReturn(Blocks.CRAFTING_TABLE.defaultBlockState());

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            NativeAuditEventListener listener = new NativeAuditEventListener();
            listener.onBlockInteract(rightClick(player, pos, InteractionHand.MAIN_HAND));
            listener.onBlockInteract(rightClick(player, pos, InteractionHand.OFF_HAND));
            listener.onBlockInteract(rightClick(player, pos, InteractionHand.MAIN_HAND, true));
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_BLOCK_ATTEMPT", captured.getValue().eventType());
        assertEquals("outcome=attempt", captured.getValue().detail());
        assertEquals(playerUuid.toString(), captured.getValue().playerUuid());
        assertEquals("Alex", captured.getValue().playerName());
        assertEquals("minecraft:crafting_table", captured.getValue().subjectId());
        assertEquals("minecraft:overworld", captured.getValue().levelName());
        assertEquals(4.0, captured.getValue().x());
        assertEquals(64.0, captured.getValue().y());
        assertEquals(9.0, captured.getValue().z());
    }

    @Test
    void armorStandSpecificCallbackRecordsOnlyAnAttemptWithTargetUuid() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ArmorStand target = mock(ArmorStand.class);
        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        when(event.isCanceled()).thenReturn(false);
        when(event.getEntity()).thenReturn(player);
        when(event.getLevel()).thenReturn(level);
        when(event.getTarget()).thenReturn(target);
        when(event.getHand()).thenReturn(InteractionHand.OFF_HAND);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.blockPosition()).thenReturn(new BlockPos(8, 65, -2));
        when(target.getUUID()).thenReturn(targetUuid);
        doReturn(EntityType.ARMOR_STAND).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            new NativeAuditEventListener().onEntityInteractSpecific(event);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY", captured.getValue().eventType());
        assertEquals("outcome=attempt hand=off_hand target_uuid=" + targetUuid
                + " completion=armor_stand_return_hook", captured.getValue().detail());
    }

    @Test
    void armorStandSpecificThenGenericCallbacksRecordOneAttempt() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ArmorStand target = mock(ArmorStand.class);
        PlayerInteractEvent.EntityInteractSpecific specific = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        PlayerInteractEvent.EntityInteract generic = mock(PlayerInteractEvent.EntityInteract.class);
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        when(specific.isCanceled()).thenReturn(false);
        when(specific.getEntity()).thenReturn(player);
        when(specific.getLevel()).thenReturn(level);
        when(specific.getTarget()).thenReturn(target);
        when(specific.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(generic.isCanceled()).thenReturn(false);
        when(generic.getEntity()).thenReturn(player);
        when(generic.getLevel()).thenReturn(level);
        when(generic.getTarget()).thenReturn(target);
        when(generic.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.getUUID()).thenReturn(targetUuid);
        when(target.blockPosition()).thenReturn(new BlockPos(8, 65, -2));
        doReturn(EntityType.ARMOR_STAND).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            NativeAuditEventListener listener = new NativeAuditEventListener();
            listener.onEntityInteractSpecific(specific);
            listener.onEntityInteract(generic);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY", captured.getValue().eventType());
        assertEquals("outcome=attempt hand=main_hand target_uuid=" + targetUuid
                + " completion=armor_stand_return_hook", captured.getValue().detail());
    }
    @Test
    void canceledArmorStandGenericCallbackRecordsItsTerminalOutcome() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ArmorStand target = mock(ArmorStand.class);
        PlayerInteractEvent.EntityInteract event = mock(PlayerInteractEvent.EntityInteract.class);
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        when(event.isCanceled()).thenReturn(true);
        when(event.getEntity()).thenReturn(player);
        when(event.getLevel()).thenReturn(level);
        when(event.getTarget()).thenReturn(target);
        when(event.getHand()).thenReturn(InteractionHand.OFF_HAND);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(player.level()).thenReturn(level);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.level()).thenReturn(level);
        when(target.getUUID()).thenReturn(targetUuid);
        when(target.blockPosition()).thenReturn(new BlockPos(8, 65, -2));
        doReturn(EntityType.ARMOR_STAND).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            new NativeAuditEventListener().onEntityInteract(event);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY_DENIED", captured.getValue().eventType());
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail()
                .contains("outcome=canceled callback=entity_interact"));
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail()
                .contains("target_uuid=" + targetUuid));
    }

    @Test
    void nonArmorSpecificCallbackRecordsAttemptWhenItsMethodHandlesTheInteraction() {
        PlayerInteractEvent.EntityInteractSpecific specific = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        PlayerInteractEvent.EntityInteract generic = mock(PlayerInteractEvent.EntityInteract.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        Entity target = mock(Entity.class);
        UUID playerUuid = UUID.randomUUID();
        when(specific.isCanceled()).thenReturn(false);
        when(specific.getEntity()).thenReturn(player);
        when(specific.getLevel()).thenReturn(level);
        when(specific.getTarget()).thenReturn(target);
        when(specific.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(generic.isCanceled()).thenReturn(false);
        when(generic.getEntity()).thenReturn(player);
        when(generic.getLevel()).thenReturn(level);
        when(generic.getTarget()).thenReturn(target);
        when(generic.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.getUUID()).thenReturn(UUID.randomUUID());
        when(target.blockPosition()).thenReturn(new BlockPos(2, 64, 3));
        doReturn(EntityType.ZOMBIE).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            NativeAuditEventListener listener = new NativeAuditEventListener();
            listener.onEntityInteractSpecific(specific);
            listener.onEntityInteract(generic);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY", captured.getValue().eventType());
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail()
                .contains("completion=specific_result_unobserved"));
    }
    @Test
    void canceledArmorStandSpecificCallbackIsRetainedAsCanceledAttempt() {
        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        ArmorStand target = mock(ArmorStand.class);
        UUID playerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        when(event.isCanceled()).thenReturn(true);
        when(event.getEntity()).thenReturn(player);
        when(event.getLevel()).thenReturn(level);
        when(event.getTarget()).thenReturn(target);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.getUUID()).thenReturn(targetUuid);
        when(target.blockPosition()).thenReturn(new BlockPos(8, 65, -2));
        doReturn(EntityType.ARMOR_STAND).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            new NativeAuditEventListener().onEntityInteractSpecific(event);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY_DENIED", captured.getValue().eventType());
        assertEquals("outcome=canceled hand=main_hand target_uuid=" + targetUuid
                + " completion=callback_canceled", captured.getValue().detail());
    }

    @Test
    void canceledNonArmorSpecificCallbackIsRetainedWithoutNullUuidPlaceholder() {
        PlayerInteractEvent.EntityInteractSpecific event = mock(PlayerInteractEvent.EntityInteractSpecific.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        Entity target = mock(Entity.class);
        UUID playerUuid = UUID.randomUUID();
        when(event.isCanceled()).thenReturn(true);
        when(event.getEntity()).thenReturn(player);
        when(event.getLevel()).thenReturn(level);
        when(event.getTarget()).thenReturn(target);
        when(event.getHand()).thenReturn(InteractionHand.MAIN_HAND);
        when(player.getUUID()).thenReturn(playerUuid);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerUuid, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(target.getUUID()).thenReturn(null);
        when(target.blockPosition()).thenReturn(new BlockPos(2, 64, 3));
        doReturn(EntityType.ZOMBIE).when(target).getType();

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            new NativeAuditEventListener().onEntityInteractSpecific(event);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY_DENIED", captured.getValue().eventType());
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail().contains("outcome=canceled"));
        org.junit.jupiter.api.Assertions.assertFalse(captured.getValue().detail().contains("target_uuid"));
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail()
                .contains("completion=callback_canceled"));
    }

    private static void assertNotRecorded(java.util.function.Consumer<NativeAuditEventListener> invocation) {
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            invocation.accept(new NativeAuditEventListener());
            verifyNoInteractions(service);
        }
    }

    private PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos,
                                                            InteractionHand hand) {
        return rightClick(player, pos, hand, false);
    }

    private PlayerInteractEvent.RightClickBlock rightClick(ServerPlayer player, BlockPos pos,
                                                            InteractionHand hand, boolean canceled) {
        PlayerInteractEvent.RightClickBlock event = new PlayerInteractEvent.RightClickBlock(
                player, hand, pos, mock(BlockHitResult.class));
        event.setCanceled(canceled);
        return event;
    }
}
