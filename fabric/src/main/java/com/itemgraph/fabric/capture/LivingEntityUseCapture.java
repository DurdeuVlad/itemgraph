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

    /** Stable invocation marker shared by the HEAD and RETURN callbacks. */
    public record Site(String callerClass, String callerMethod, int stackDepth) {
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
                ? removeMatching(pending, player, currentSite())
                : (pending == null || pending.isEmpty() ? null : pending.pop());
        if (pending != null && pending.isEmpty()) {
            STACKS.remove();
        }
        return capture;
    }

    private static Site currentSite() {
        return STACK_WALKER.walk(frames -> {
            String callerClass = null;
            String callerMethod = null;
            int depth = 0;
            int index = 0;
            Iterator<StackWalker.StackFrame> iterator = frames.iterator();
            while (iterator.hasNext()) {
                StackWalker.StackFrame frame = iterator.next();
                // 0=currentSite, 1=begin/finish, 2=mixin handler,
                // 3=LivingEntity.completeUsingItem, 4=its caller. The target
                // method has different source lines at HEAD and RETURN, so
                // use the stable caller class/method and stack depth instead
                // of comparing a full StackTraceElement.
                if (index == 4) {
                    callerClass = frame.getClassName();
                    callerMethod = frame.getMethodName();
                }
                depth++;
                index++;
            }
            return new Site(callerClass, callerMethod, depth);
        });
    }

    private static Capture removeMatching(Deque<Capture> pending, ServerPlayer player, Site site) {
        if (pending == null || pending.isEmpty()) {
            if (pending != null) {
                pending.clear();
            }
            return null;
        }
        Capture match = null;
        for (Capture candidate : pending) {
            if (candidate.player() == player && Objects.equals(candidate.site(), site)) {
                match = candidate;
                break;
            }
        }
        if (match == null) {
            // Never pair a completed use with a different nested invocation's
            // original stack. Clear stale markers and drop only this capture.
            pending.clear();
            return null;
        }
        while (pending.peek() != match) {
            pending.pop();
        }
        return pending.pop();
    }
}
