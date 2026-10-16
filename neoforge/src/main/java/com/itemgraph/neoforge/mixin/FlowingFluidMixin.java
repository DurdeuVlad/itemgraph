package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.WorldEventCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;

/** Captures successful fluid spread as one direct target-state delta. */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
    @WrapMethod(method = "spreadTo")
    private void itemgraph$captureFluidSpread(LevelAccessor level, BlockPos pos, BlockState state,
                                               Direction direction, FluidState fluidState,
                                               Operation<Void> original) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        boolean completed = false;
        try {
            original.call(level, pos, state, direction, fluidState);
            completed = true;
        } finally {
            WorldEventCapture.recordFluidSpread(level, pos, before, direction, fluidState, !completed);
        }
    }
}
