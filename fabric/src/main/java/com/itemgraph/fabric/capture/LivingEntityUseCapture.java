package com.itemgraph.fabric.capture;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.lang.StackWalker;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;

/**
 * Thread-local state for the Fabric item-use mixin. This helper is outside the
 * mixin package so Mixin treats it as ordinary application code rather than a
 * class that must be transformed into a Minecraft target.
 */
public final class LivingEntityUseCapture {
    private static final int MAX_DEPTH = 32;
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Deque<Capture>> STACKS = new ThreadLocal<>();

    private LivingEntityUseCapture() {
    }

    public record Site(StackTraceElement caller, int stackDepth) {
    }

    public record Capture(ServerPlayer player, ItemStack original, InteractionHand hand, Site site) {
        public static Capture empty() {
            return new Capture(null, ItemStack.EMPTY, InteractionHand.MAIN_HAND, null);
        }
    }

    public static void begin(Object entity) {
        Deque<Capture> pending = STACKS.get();
        if (pending == null) {
            pending = new ArrayDeque<>();
            STACKS.set(pending);
        }
        if (pending.size() >= MAX_DEPTH) {
            pending.clear();
        }
        if (!(entity instanceof ServerPlayer player) || player.level().isClientSide()) {
            pending.push(Capture.empty());
            return;
        }
        Site site = currentSite();
        ItemStack stack = player.getUseItem();
        InteractionHand hand = player.getUsedItemHand();
        pending.push(new Capture(player, stack == null ? ItemStack.EMPTY : stack.copy(),
                hand == null ? InteractionHand.MAIN_HAND : hand, site));
    }

    public static Capture finish(Object entity) {
        Deque<Capture> pending = STACKS.get();
        Capture capture = (entity instanceof ServerPlayer player && !player.level().isClientSide())
                ? removeMatching(pending, currentSite())
                : (pending == null || pending.isEmpty() ? null : pending.pop());
        if (pending != null && pending.isEmpty()) {
            STACKS.remove();
        }
        return capture;
    }

    private static Site currentSite() {
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
            return new Site(caller, depth);
        });
    }

    private static Capture removeMatching(Deque<Capture> pending, Site site) {
        if (pending == null || pending.isEmpty()) {
            return null;
        }
        Capture match = null;
        for (Capture candidate : pending) {
            if (Objects.equals(candidate.site(), site)) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            // A transformed or modded call path can add one frame between the
            // HEAD and RETURN callbacks. Preserve the completed-use event by
            // consuming the most recent bounded capture instead of dropping it.
            return pending.pop();
        }
        while (pending.peek() != match) {
            pending.pop();
        }
        return pending.pop();
    }
}
