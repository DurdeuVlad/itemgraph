package com.itemgraph.neoforge.mixin;

import com.itemgraph.neoforge.automation.NeoForgeHopperCapture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/** Captures committed vanilla hopper inventory deltas on NeoForge. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "tryMoveItems", at = @At("HEAD"), require = 1)
    private static void itemgraph$begin(Level level, BlockPos pos, BlockState state,
                                        HopperBlockEntity hopper, BooleanSupplier enabled,
                                        CallbackInfoReturnable<Boolean> callback) {
        NeoForgeHopperCapture.begin(level, pos, hopper);
    }

    @Inject(method = "tryMoveItems", at = @At("RETURN"), require = 1)
    private static void itemgraph$finish(Level level, BlockPos pos, BlockState state,
                                         HopperBlockEntity hopper, BooleanSupplier enabled,
                                         CallbackInfoReturnable<Boolean> callback) {
        NeoForgeHopperCapture.finish(level, pos, hopper, callback.getReturnValueZ());
    }
}
