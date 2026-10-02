package com.itemgraph.neoforge.mixin;

import com.itemgraph.automation.VanillaDispenserCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Captures DropperBlock's override of dispenseFrom; it does not call super. */
@Mixin(DropperBlock.class)
public abstract class DropperBlockMixin {
    @WrapMethod(method = "dispenseFrom")
    protected void itemgraph$captureDropper(ServerLevel level, BlockState state, BlockPos pos,
                                             Operation<Void> original) {
        VanillaDispenserCapture.begin(level, state, pos);
        try {
            original.call(level, state, pos);
        } finally {
            VanillaDispenserCapture.finish(level, pos);
        }
    }
}
