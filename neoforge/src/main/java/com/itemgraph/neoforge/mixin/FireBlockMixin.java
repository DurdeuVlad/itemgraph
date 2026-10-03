package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.WorldEventCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
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
        return itemgraph$capture(level, pos, "FireBlock.tick.setBlock",
                () -> original.call(level, pos, state, flags));
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean itemgraph$captureTickRemove(ServerLevel level, BlockPos pos, boolean moved,
                                                 Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.tick.removeBlock",
                () -> original.call(level, pos, moved));
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;onCaughtFire(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private void itemgraph$captureCaughtFire(BlockState state, Level level, BlockPos pos, Direction face,
                                              LivingEntity igniter, Operation<Void> original) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        try {
            original.call(state, level, pos, face, igniter);
        } finally {
            itemgraph$record(level, pos, before, "FireBlock.checkBurnOut.onCaughtFire");
        }
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean itemgraph$captureBurnOutSet(Level level, BlockPos pos, BlockState state, int flags,
                                                 Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.checkBurnOut.setBlock",
                () -> original.call(level, pos, state, flags));
    }

    @WrapOperation(method = "checkBurnOut", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean itemgraph$captureBurnOutRemove(Level level, BlockPos pos, boolean moved,
                                                    Operation<Boolean> original) {
        return itemgraph$capture(level, pos, "FireBlock.checkBurnOut.removeBlock",
                () -> original.call(level, pos, moved));
    }

    private static boolean itemgraph$capture(Level level, BlockPos pos, String boundary,
                                               java.util.function.BooleanSupplier write) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        try {
            return write.getAsBoolean();
        } finally {
            itemgraph$record(level, pos, before, boundary);
        }
    }

    private static void itemgraph$record(Level level, BlockPos pos, BlockState before, String boundary) {
        WorldEventCapture.recordDirectBlockChange(level, pos, before, "FIRE_BLOCK_CHANGE", "fire",
                boundary, UUID.randomUUID().toString(), Map.of(), true);
    }
}
