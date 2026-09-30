package com.itemgraph.ingest;

import com.mojang.authlib.GameProfile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EntityInteractionEvidenceTest {
    @BeforeAll
    static void initializeMinecraft() {
        if (LoadingModList.get() == null) {
            LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recordsOnlyTheCompletedResultAndTargetIdentity() {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ArmorStand stand = mock(ArmorStand.class);
        UUID playerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(stand.level()).thenReturn(level);
        when(stand.getUUID()).thenReturn(targetId);
        doReturn(EntityType.ARMOR_STAND).when(stand).getType();
        when(stand.blockPosition()).thenReturn(new BlockPos(8, 65, -2));

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    player, stand, InteractionHand.MAIN_HAND, InteractionResult.SUCCESS);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        var event = captured.getValue();
        assertEquals("INTERACT_ENTITY_COMPLETED", event.eventType());
        assertEquals(playerId.toString(), event.playerUuid());
        assertEquals("minecraft:armor_stand", event.subjectId());
        assertEquals("minecraft:overworld", event.levelName());
        assertEquals(8.0, event.x());
        assertEquals(65.0, event.y());
        assertEquals(-2.0, event.z());
        org.junit.jupiter.api.Assertions.assertTrue(event.detail().contains("outcome=handled"));
        org.junit.jupiter.api.Assertions.assertTrue(event.detail().contains("hand=main_hand"));
        org.junit.jupiter.api.Assertions.assertTrue(event.detail().contains("target_uuid=" + targetId));
    }

    @Test
    void doesNotRecordPassAsACompletedInteraction() {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ArmorStand stand = mock(ArmorStand.class);
        UUID playerId = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(stand.level()).thenReturn(level);
        UUID targetId = UUID.randomUUID();
        when(stand.getUUID()).thenReturn(targetId);
        doReturn(EntityType.ARMOR_STAND).when(stand).getType();
        when(stand.blockPosition()).thenReturn(BlockPos.ZERO);

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    player, stand, InteractionHand.OFF_HAND, InteractionResult.PASS);
        }
        verifyNoInteractions(service);
    }

    @Test
    void recordsExplicitDeniedArmorStandResultWithoutCallingItACompletedEffect() {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ArmorStand stand = mock(ArmorStand.class);
        UUID playerId = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(stand.level()).thenReturn(level);
        when(stand.getUUID()).thenReturn(UUID.randomUUID());
        doReturn(EntityType.ARMOR_STAND).when(stand).getType();
        when(stand.blockPosition()).thenReturn(BlockPos.ZERO);

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    player, stand, InteractionHand.MAIN_HAND, InteractionResult.FAIL);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY_DENIED", captured.getValue().eventType());
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail().contains("outcome=denied"));
        org.junit.jupiter.api.Assertions.assertTrue(captured.getValue().detail().contains("result=fail"));
    }

    @Test
    void missingTargetUuidDoesNotProduceAMisleadingPlaceholder() {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ArmorStand stand = mock(ArmorStand.class);
        UUID playerId = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Alex"));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(stand.level()).thenReturn(level);
        when(stand.getUUID()).thenReturn(null);
        doReturn(EntityType.ARMOR_STAND).when(stand).getType();
        when(stand.blockPosition()).thenReturn(BlockPos.ZERO);

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    player, stand, InteractionHand.MAIN_HAND, InteractionResult.SUCCESS);
        }

        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        org.junit.jupiter.api.Assertions.assertFalse(captured.getValue().detail().contains("target_uuid"));
    }

    @Test
    void doesNotAcceptNonArmorEntitiesAsArmorStandCompletion() {
        ServerPlayer player = mock(ServerPlayer.class);
        net.minecraft.world.entity.Entity target = mock(net.minecraft.world.entity.Entity.class);
        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    player, target, InteractionHand.MAIN_HAND, InteractionResult.SUCCESS);
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsClientSideResults() {
        ServerLevel clientLevel = mock(ServerLevel.class);
        when(clientLevel.isClientSide()).thenReturn(true);
        ServerPlayer player = mock(ServerPlayer.class);
        ArmorStand stand = mock(ArmorStand.class);
        when(player.level()).thenReturn(clientLevel);

        InternalObservationService service = mock(InternalObservationService.class);
        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            EntityInteractionEvidence.recordArmorStandHandledResult(
                    player, stand, InteractionHand.MAIN_HAND, InteractionResult.SUCCESS);
        }
        verifyNoInteractions(service);
    }
}
