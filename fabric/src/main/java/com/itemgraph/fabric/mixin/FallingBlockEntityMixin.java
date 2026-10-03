package com.itemgraph.fabric.mixin;

import com.itemgraph.audit.WorldEventCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;

/** Captures exact source removal and landing writes for falling-block entities. */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockEntityMixin {
    @WrapMethod(method = "fall")
    private static FallingBlockEntity itemgraph$captureFallingSource(Level level, BlockPos pos,
                                                                     BlockState state,
                                                                     Operation<FallingBlockEntity> original) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        FallingBlockEntity result = null;
        boolean completed = false;
        try {
            result = original.call(level, pos, state);
            completed = true;
            return result;
        } finally {
            if (result != null) {
                WorldEventCapture.recordDirectBlockChange(level, pos, before, "FALLING_BLOCK_CHANGE",
                        "falling_block", "FallingBlockEntity.fall.return", result.getUUID().toString(),
                        Map.of("entity_uuid", result.getUUID().toString(),
                                "block_id", net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                        .getKey(result.getBlockState().getBlock()).toString(),
                                "movement", "SOURCE_REMOVED"), false);
            } else if (!completed) {
                WorldEventCapture.recordFallingBlockSourceFailure(level, pos, before, state);
            }
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean itemgraph$captureLanding(Level level, BlockPos pos, BlockState state, int flags,
                                              Operation<Boolean> original) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        try {
            return original.call(level, pos, state, flags);
        } finally {
            FallingBlockEntity self = (FallingBlockEntity) (Object) this;
            WorldEventCapture.recordDirectBlockChange(level, pos, before, "FALLING_BLOCK_CHANGE",
                    "falling_block", "FallingBlockEntity.tick.setBlock", self.getUUID().toString(),
                    Map.of("entity_uuid", self.getUUID().toString(), "movement", "LANDING"), false);
        }
    }
}
