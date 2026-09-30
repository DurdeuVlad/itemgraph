package com.itemgraph.fabric;

import com.itemgraph.ingest.InternalObservationService;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FabricUseEntityCallbackAuditTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // The shared registries may already be bootstrapped by another test.
        }
    }

    @Test
    void recordsResultWhenAnEarlierCallbackShortCircuitsBeforeItemGraph() {
        var fixture = fixture();
        AtomicInteger laterCallbackCalls = new AtomicInteger();
        InternalObservationService service = mock(InternalObservationService.class);
        UseEntityCallback first = (player, level, hand, target, hit) -> InteractionResult.FAIL;
        UseEntityCallback later = (player, level, hand, target, hit) -> {
            laterCallbackCalls.incrementAndGet();
            return InteractionResult.SUCCESS;
        };

        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            var event = FabricUseEntityCallbackAudit.createArrayBacked(
                    UseEntityCallback.class, FabricUseEntityCallbackAuditTest::invokeUntilHandled);
            event.register(first);
            event.register(later);

            assertEquals(InteractionResult.FAIL, event.invoker().interact(
                    fixture.player(), fixture.level(), InteractionHand.MAIN_HAND, fixture.target(), null));
        }

        assertEquals(0, laterCallbackCalls.get());
        var captured = captureAuditEvent(service);
        assertEquals("INTERACT_ENTITY_DENIED", captured.eventType());
        org.junit.jupiter.api.Assertions.assertTrue(captured.detail().contains("callback_result=fail"));
        org.junit.jupiter.api.Assertions.assertTrue(captured.detail()
                .contains("held_item=minecraft:diamond held_count=1"));
        assertEquals(fixture.target().getUUID().toString(), extract(captured.detail(), "target_uuid="));
    }

    @Test
    void preservesAttemptAndSingleAggregateOutcomeWhenALaterCallbackShortCircuits() {
        var fixture = fixture();
        InternalObservationService service = mock(InternalObservationService.class);
        AtomicInteger afterItemGraphCalls = new AtomicInteger();
        UseEntityCallback itemGraph = (player, level, hand, target, hit) -> {
            FabricNativeAuditEventListener.recordEntityInteractionAttempt(
                    fixture.player(), fixture.level(), hand, fixture.target());
            return InteractionResult.PASS;
        };
        UseEntityCallback later = (player, level, hand, target, hit) -> {
            afterItemGraphCalls.incrementAndGet();
            player.getItemInHand(hand).shrink(1);
            return InteractionResult.SUCCESS;
        };

        try (MockedStatic<InternalObservationService> mocked = mockStatic(InternalObservationService.class)) {
            mocked.when(InternalObservationService::getInstance).thenReturn(service);
            var event = FabricUseEntityCallbackAudit.createArrayBacked(
                    UseEntityCallback.class, FabricUseEntityCallbackAuditTest::invokeUntilHandled);
            event.register(itemGraph);
            event.register(later);

            assertEquals(InteractionResult.SUCCESS, event.invoker().interact(
                    fixture.player(), fixture.level(), InteractionHand.OFF_HAND, fixture.target(), null));
        }

        assertEquals(1, afterItemGraphCalls.get());
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service, org.mockito.Mockito.times(2)).submitAuditEvent(captured.capture());
        assertEquals("INTERACT_ENTITY", captured.getAllValues().get(0).eventType());
        assertEquals("INTERACT_ENTITY_UNRESOLVED", captured.getAllValues().get(1).eventType());
        org.junit.jupiter.api.Assertions.assertTrue(captured.getAllValues().get(1).detail()
                .contains("callback_result=success reason=FABRIC_USE_ENTITY_CALLBACK_SHORT_CIRCUITED"));
        org.junit.jupiter.api.Assertions.assertTrue(captured.getAllValues().get(1).detail()
                .contains("held_item=minecraft:diamond held_count=1"),
                "outcome metadata must reflect the held stack at the start of the callback chain");
    }

    private static UseEntityCallback invokeUntilHandled(UseEntityCallback[] callbacks) {
        return (player, level, hand, target, hitResult) -> {
            for (UseEntityCallback callback : callbacks) {
                InteractionResult result = callback.interact(player, level, hand, target, hitResult);
                if (result != InteractionResult.PASS) {
                    return result;
                }
            }
            return InteractionResult.PASS;
        };
    }

    private static InternalObservationService.InternalAuditEvent captureAuditEvent(
            InternalObservationService service) {
        ArgumentCaptor<InternalObservationService.InternalAuditEvent> captured =
                ArgumentCaptor.forClass(InternalObservationService.InternalAuditEvent.class);
        verify(service).submitAuditEvent(captured.capture());
        return captured.getValue();
    }

    private static String extract(String detail, String prefix) {
        int start = detail.indexOf(prefix) + prefix.length();
        int end = detail.indexOf(' ', start);
        return detail.substring(start, end < 0 ? detail.length() : end);
    }

    private static Fixture fixture() {
        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        ArmorStand stand = mock(ArmorStand.class);
        UUID playerId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(player.level()).thenReturn(level);
        when(player.getUUID()).thenReturn(playerId);
        when(player.getGameProfile()).thenReturn(new GameProfile(playerId, "Alex"));
        when(player.getItemInHand(any())).thenReturn(new ItemStack(net.minecraft.world.item.Items.DIAMOND));
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(stand.level()).thenReturn(level);
        when(stand.getUUID()).thenReturn(targetId);
        doReturn(EntityType.ARMOR_STAND).when(stand).getType();
        when(stand.blockPosition()).thenReturn(BlockPos.ZERO);
        return new Fixture(level, player, stand);
    }

    private record Fixture(ServerLevel level, ServerPlayer player, ArmorStand target) { }
}
