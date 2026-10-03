package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.WorldEventCapture;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.UUID;

@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanLeaveBlockGoal")
public abstract class EndermanLeaveBlockGoalMixin {
    @Shadow @Final private EnderMan enderman;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean itemgraph$capturePlaceBlock(Level level, BlockPos pos, BlockState state, int flags,
                                                 Operation<Boolean> original) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        try {
            return original.call(level, pos, state, flags);
        } finally {
            WorldEventCapture.recordDirectBlockChange(level, pos, before, "ENDERMAN_BLOCK_MOVE", "enderman",
                    "EndermanLeaveBlockGoal.tick.setBlock", UUID.randomUUID().toString(),
                    Map.of("entity_uuid", enderman.getUUID().toString(), "movement", "PLACE_BLOCK"), false);
        }
    }
}
