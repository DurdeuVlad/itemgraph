package com.itemgraph.neoforge.mixin;

import com.itemgraph.automation.VanillaDispenserCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/** Binds accepted vanilla dispense item entities to their exact source block. */
@Mixin(DispenserBlock.class)
public abstract class DispenserBlockMixin {
    @WrapMethod(method = "dispenseFrom")
    protected void itemgraph$captureDispense(ServerLevel level, BlockState state, BlockPos pos,
                                              Operation<Void> original) {
        VanillaDispenserCapture.begin(level, state, pos);
        try {
            original.call(level, state, pos);
        } finally {
            VanillaDispenserCapture.finish(level, pos);
        }
    }
}
