package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BooleanSupplier;

/** Captures vanilla hopper transfers as endpoint-unknown container deltas. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    @Inject(method = "tryMoveItems", at = @At("HEAD"))
    private static void itemgraph$captureTransfer(Level level, BlockPos pos, BlockState state,
                                                   HopperBlockEntity hopper, BooleanSupplier enabled,
                                                   CallbackInfoReturnable<Boolean> callback) {
        FabricNativeAuditEventListener.beginHopperTransferCapture(level, pos, hopper);
    }

    @Inject(method = "tryMoveItems", at = @At("RETURN"))
    private static void itemgraph$recordTransfer(Level level, BlockPos pos, BlockState state,
                                                  HopperBlockEntity hopper, BooleanSupplier enabled,
                                                  CallbackInfoReturnable<Boolean> callback) {
        FabricNativeAuditEventListener.finishHopperTransferCapture(
                level, pos, hopper, callback.getReturnValueZ());
    }
}
