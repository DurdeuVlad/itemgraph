package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.lang.StackWalker;

/**
 * Captures the completed item-use boundary shared by food and drinks. A
 * bounded nested stack protects the server thread if a modded implementation
 * throws before the return hook runs. Server captures carry a lightweight call
 * stack marker so an outer completion cannot consume an abandoned inner frame.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    private static final int MAX_USE_CAPTURE_DEPTH = 32;
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();

    private record UseSite(StackTraceElement caller, int stackDepth) {
    }

    private record UseCapture(ServerPlayer player, ItemStack original, InteractionHand hand,
                              UseSite site) {
    }

    private static final ThreadLocal<Deque<UseCapture>> ITEMGRAPH_USE_STACKS = new ThreadLocal<>();

    @Inject(method = "completeUsingItem", at = @At("HEAD"))
    private void itemgraph$captureUse(CallbackInfo callback) {
        Deque<UseCapture> pending = ITEMGRAPH_USE_STACKS.get();
        if (pending == null) {
            pending = new ArrayDeque<>();
            ITEMGRAPH_USE_STACKS.set(pending);
        }
        if (pending.size() >= MAX_USE_CAPTURE_DEPTH) {
            pending.clear();
        }
        if (!((Object) this instanceof ServerPlayer player) || player.level().isClientSide()) {
            pending.push(new UseCapture(null, ItemStack.EMPTY, InteractionHand.MAIN_HAND, null));
            return;
        }
        UseSite site = itemgraph$currentUseSite();
        ItemStack stack = player.getUseItem();
        InteractionHand hand = player.getUsedItemHand();
        pending.push(new UseCapture(player, stack == null ? ItemStack.EMPTY : stack.copy(),
                hand == null ? InteractionHand.MAIN_HAND : hand, site));
    }

    @Inject(method = "completeUsingItem", at = @At("RETURN"))
    private void itemgraph$recordUse(CallbackInfo callback) {
        Deque<UseCapture> pending = ITEMGRAPH_USE_STACKS.get();
        UseCapture capture = ((Object) this instanceof ServerPlayer player && !player.level().isClientSide())
                ? itemgraph$removeMatchingCapture(pending, itemgraph$currentUseSite())
                : (pending == null || pending.isEmpty() ? null : pending.pop());
        if (capture == null) {
            return;
        }
        if (pending != null && pending.isEmpty()) {
            ITEMGRAPH_USE_STACKS.remove();
        }
        if (capture.player() == null || capture.original() == null || capture.original().isEmpty()) {
            return;
        }
        ItemStack current = capture.player().getItemInHand(capture.hand());
        FabricNativeAuditEventListener.onItemUseFinished(capture.player(), capture.original(), current);
    }

    private static UseSite itemgraph$currentUseSite() {
        return STACK_WALKER.walk(frames -> {
            StackTraceElement caller = null;
            int depth = 0;
            int index = 0;
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                if (index == 3) {
                    caller = frame.toStackTraceElement();
                }
                depth++;
                index++;
            }
            return new UseSite(caller, depth);
        });
    }

    private static UseCapture itemgraph$removeMatchingCapture(Deque<UseCapture> pending, UseSite site) {
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        UseCapture match = null;
        for (UseCapture candidate : pending) {
            if (Objects.equals(candidate.site(), site)) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            pending.clear();
            ITEMGRAPH_USE_STACKS.remove();
            return null;
        }
        while (pending.peek() != match) {
            pending.pop();
        }
        return pending.pop();
    }
}
