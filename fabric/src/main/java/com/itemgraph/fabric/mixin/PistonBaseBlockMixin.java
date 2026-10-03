package com.itemgraph.fabric.mixin;

import com.itemgraph.audit.WorldEventCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

/** Records piston block-state results around the vanilla trigger boundary. */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {
    @WrapMethod(method = "triggerEvent")
    private boolean itemgraph$capturePistonResult(BlockState state, Level level, BlockPos pos,
                                                   int eventId, int data,
                                                   Operation<Boolean> original) {
        WorldEventCapture.beginPiston(level, state, pos, eventId);
        boolean completed = false;
        boolean result = false;
        try {
            result = original.call(state, level, pos, eventId, data);
            completed = true;
            return result;
        } finally {
            if (completed) {
                WorldEventCapture.finishPiston(level, pos, result);
            } else {
                WorldEventCapture.abortPiston(level, pos);
            }
        }
    }
}
