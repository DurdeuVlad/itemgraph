package com.itemgraph.fabric.mixin;

import com.itemgraph.audit.WorldEventCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.UUID;

/** Captures exact fire-caused writes without scanning a neighborhood each tick. */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean itemgraph$captureTickSet(ServerLevel level, BlockPos pos, BlockState state, int flags,
                                              Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.tick.setBlock", true,
                () -> original.call(level, pos, state, flags));
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean itemgraph$captureTickRemove(ServerLevel level, BlockPos pos, boolean moved,
                                                 Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.tick.removeBlock", true,
                () -> original.call(level, pos, moved));
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;onCaughtFire(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void itemgraph$captureCaughtFire(BlockState state, Level level, BlockPos pos, Direction face,
                                              LivingEntity igniter, Operation<Void> original) {
        itemgraph$captureVoid(level, pos, "FireBlock.checkBurnOut.onCaughtFire",
                () -> original.call(state, level, pos, face, igniter));
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean itemgraph$captureBurnOutSet(Level level, BlockPos pos, BlockState state, int flags,
                                                 Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.checkBurnOut.setBlock", true,
                () -> original.call(level, pos, state, flags));
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean itemgraph$captureBurnOutRemove(Level level, BlockPos pos, boolean moved,
                                                    Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.checkBurnOut.removeBlock", true,
                () -> original.call(level, pos, moved));
    }

    private static boolean itemgraph$capture(Level level, BlockPos pos, String boundary,
                                               boolean typeOnly, java.util.function.BooleanSupplier write) {
        return WorldEventCapture.captureDirectBlockWrite(level, pos, "FIRE_BLOCK_CHANGE", "fire",
                boundary, typeOnly, write);
    }

    private static void itemgraph$captureVoid(Level level, BlockPos pos, String boundary, Runnable write) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        boolean returned = false;
        try {
            write.run();
            returned = true;
        } finally {
            itemgraph$record(level, pos, before, boundary, true, null, !returned);
        }
    }

    private static void itemgraph$record(Level level, BlockPos pos, BlockState before,
                                         String boundary, boolean typeOnly, Boolean callbackResult,
                                         boolean callbackThrew) {
        WorldEventCapture.recordDirectBlockChange(level, pos, before, "FIRE_BLOCK_CHANGE", "fire",
                boundary, UUID.randomUUID().toString(), Map.of(), typeOnly, callbackResult, callbackThrew);
    }

}
