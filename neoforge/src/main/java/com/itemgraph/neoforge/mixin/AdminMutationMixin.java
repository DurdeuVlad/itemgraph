package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.AdminMutationCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemInput;
import com.mojang.brigadier.ParseResults;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.commands.ClearInventoryCommands;
import net.minecraft.server.commands.GiveCommand;
import net.minecraft.server.commands.ItemCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.function.Predicate;

/** Captures item-command and creative-slot deltas at vanilla mutation boundaries. */
@Mixin(GiveCommand.class)
abstract class AdminGiveMutationMixin {
    @WrapOperation(method = "giveItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"))
    private static ItemEntity itemgraph$captureExactOverflowDrop(ServerPlayer player, ItemStack stack,
            boolean dropAround, Operation<ItemEntity> original) throws Throwable {
        boolean began = AdminMutationCapture.beginGiveDropSafely(player, stack);
        ItemEntity entity = null;
        try {
            entity = original.call(player, stack, dropAround);
            return entity;
        } finally {
            var deferred = AdminMutationCapture.finishGiveDropSafely(began, player, stack, entity,
                    AdminMutationCapture.wasGiveDropAcceptedSafely(player, stack, entity));
            com.itemgraph.listener.ItemEntityEventListener.replayDeferredGiveDrops(deferred);
        }
    }

    @WrapMethod(method = "giveItem")
    private static int itemgraph$capture(CommandSourceStack source, ItemInput input,
            Collection<ServerPlayer> targets, int count, Operation<Integer> original) throws Throwable {
        AdminMutationCapture.beginGiveSafely(source, targets);
        int result;
        try {
            result = original.call(source, input, targets, count);
        } catch (Throwable failure) {
            AdminMutationCapture.failCurrentSafely("give", "command_exception");
            throw failure;
        }
        AdminMutationCapture.finishCurrentSafely("give", result > 0, result);
        return result;
    }
}
@Mixin(ClearInventoryCommands.class)
abstract class AdminClearMutationMixin {
    @WrapMethod(method = "clearInventory")
    private static int itemgraph$capture(CommandSourceStack source, Collection<ServerPlayer> targets,
            Predicate<ItemStack> predicate, int maxCount, Operation<Integer> original) throws Throwable {
        AdminMutationCapture.beginClearSafely(source, targets);
        int result;
        try {
            result = original.call(source, targets, predicate, maxCount);
        } catch (Throwable failure) {
            AdminMutationCapture.failCurrentSafely("clear", "command_exception");
            throw failure;
        }
        AdminMutationCapture.finishCurrentSafely("clear", result > 0, result);
        return result;
    }
}

@Mixin(ItemCommands.class)
abstract class AdminItemMutationMixin {
    @WrapMethod(method = "getBlockItem")
    private static ItemStack itemgraph$captureBlockCopySource(CommandSourceStack source, BlockPos pos, int slot,
            Operation<ItemStack> original) throws Throwable {
        ItemStack result = original.call(source, pos, slot);
        AdminMutationCapture.recordBlockCopySourceSafely(source, pos, slot);
        return result;
    }

    @WrapMethod(method = "getEntityItem")
    private static ItemStack itemgraph$captureEntityCopySource(Entity source, int slot,
            Operation<ItemStack> original) throws Throwable {
        ItemStack result = original.call(source, slot);
        AdminMutationCapture.recordEntityCopySourceSafely(source, slot);
        return result;
    }

    @WrapMethod(method = "setBlockItem")
    private static int itemgraph$captureSetBlock(CommandSourceStack source, BlockPos pos, int slot,
            ItemStack stack, Operation<Integer> original) throws Throwable {
        return itemgraph$captureBlock(source, pos, slot, "item_replace",
                () -> original.call(source, pos, slot, stack));
    }

    @WrapMethod(method = "modifyBlockItem")
    private static int itemgraph$captureModifyBlock(CommandSourceStack source, BlockPos pos, int slot,
            Holder<LootItemFunction> modifier, Operation<Integer> original) throws Throwable {
        return itemgraph$captureBlock(source, pos, slot, "item_modify",
                () -> original.call(source, pos, slot, modifier));
    }

    @WrapMethod(method = "setEntityItem")
    private static int itemgraph$captureSetEntity(CommandSourceStack source,
            Collection<? extends Entity> targets, int slot, ItemStack stack, Operation<Integer> original)
            throws Throwable {
        return itemgraph$captureEntities(source, targets, slot, "item_replace",
                () -> original.call(source, targets, slot, stack));
    }

    @WrapMethod(method = "modifyEntityItem")
    private static int itemgraph$captureModifyEntity(CommandSourceStack source,
            Collection<? extends Entity> targets, int slot, Holder<LootItemFunction> modifier,
            Operation<Integer> original) throws Throwable {
        return itemgraph$captureEntities(source, targets, slot, "item_modify",
                () -> original.call(source, targets, slot, modifier));
    }

    private static int itemgraph$captureBlock(CommandSourceStack source, BlockPos pos, int slot,
            String operation, ThrowingIntSupplier call) throws Throwable {
        AdminMutationCapture.beginBlockItemCommandSafely(source, operation, pos, slot);
        int result;
        try {
            result = call.get();
        } catch (Throwable failure) {
            AdminMutationCapture.failCurrentSafely(operation, "command_exception");
            throw failure;
        }
        AdminMutationCapture.finishCurrentSafely(operation, result > 0, result);
        return result;
    }

    private static int itemgraph$captureEntities(CommandSourceStack source,
            Collection<? extends Entity> targets, int slot, String operation,
            ThrowingIntSupplier call) throws Throwable {
        AdminMutationCapture.beginEntityItemCommandSafely(source, operation, targets, slot);
        int result;
        try {
            result = call.get();
        } catch (Throwable failure) {
            AdminMutationCapture.failCurrentSafely(operation, "command_exception");
            throw failure;
        }
        AdminMutationCapture.finishCurrentSafely(operation, result > 0, result);
        return result;
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int get() throws Throwable;
    }
}

@Mixin(ServerGamePacketListenerImpl.class)
abstract class CreativeSlotMutationMixin {
    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
            shift = At.Shift.AFTER))
    private void itemgraph$beginOnServerThread(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        AdminMutationCapture.beginCreativeSlotSafely(player, packet.slotNum());
    }

    @WrapMethod(method = "handleSetCreativeModeSlot")
    private void itemgraph$capture(ServerboundSetCreativeModeSlotPacket packet,
                                    Operation<Void> original) throws Throwable {
        try {
            original.call(packet);
        } catch (Throwable failure) {
            AdminMutationCapture.failCurrentSafely("creative_slot", "packet_handler_exception");
            throw failure;
        }
        AdminMutationCapture.finishCurrentSafely("creative_slot", true, 1);
    }
}

@Mixin(Commands.class)
abstract class CommandDispatchCleanupMixin {
    @WrapMethod(method = "performCommand")
    private void itemgraph$clearPendingCommandContext(ParseResults<CommandSourceStack> parse,
                                                       String command, Operation<Void> original) throws Throwable {
        try {
            original.call(parse, command);
        } finally {
            AdminMutationCapture.finishCommandDispatch();
        }
    }
}
