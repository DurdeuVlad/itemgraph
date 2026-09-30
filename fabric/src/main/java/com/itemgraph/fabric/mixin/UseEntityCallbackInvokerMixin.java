package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricUseEntityCallbackAudit;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

/** Decorates the aggregate Fabric callback once, preserving listener order and short-circuiting. */
@Mixin(UseEntityCallback.class)
public interface UseEntityCallbackInvokerMixin {
    @Redirect(method = "<clinit>",
            at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/api/event/EventFactory;createArrayBacked(Ljava/lang/Class;Ljava/util/function/Function;)Lnet/fabricmc/fabric/api/event/Event;",
                    remap = false),
            require = 1)
    private static <T> Event<T> itemgraph$decorateAggregateResult(Class<T> type,
                                                                   Function<T[], T> invokerFactory) {
        return FabricUseEntityCallbackAudit.createArrayBacked(type, invokerFactory);
    }
}
