package com.itemgraph.fabric;

import com.itemgraph.ingest.EntityInteractionEvidence;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;

import java.util.function.Function;

/** Decorates Fabric's aggregate entity-use callback result without replaying listeners. */
public final class FabricUseEntityCallbackAudit {
    private FabricUseEntityCallbackAudit() { }

    public static <T> Event<T> createArrayBacked(Class<T> type, Function<T[], T> invokerFactory) {
        return EventFactory.createArrayBacked(type, listeners -> {
            T invoker = invokerFactory.apply(listeners);
            if (type != UseEntityCallback.class) {
                return invoker;
            }

            UseEntityCallback delegated = (UseEntityCallback) invoker;
            return type.cast((UseEntityCallback) (player, level, hand, target, hitResult) -> {
                EntityInteractionEvidence.FabricCallbackContext context = player instanceof ServerPlayer serverPlayer
                        && !level.isClientSide()
                        ? EntityInteractionEvidence.captureFabricCallbackContext(serverPlayer, target, hand,
                                player.getItemInHand(hand))
                        : null;
                InteractionResult result = delegated.interact(player, level, hand, target, hitResult);
                EntityInteractionEvidence.recordFabricCallbackResult(context, result);
                return result;
            });
        });
    }
}
