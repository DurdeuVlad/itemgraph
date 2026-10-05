package com.itemgraph.fabric.mixin;

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

/** Captures only the exact block removed by an Enderman take goal. */
@Mixin(targets = "net.minecraft.world.entity.monster.EnderMan$EndermanTakeBlockGoal")
public abstract class EndermanTakeBlockGoalMixin {
    @Shadow @Final private EnderMan enderman;

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean itemgraph$captureTakeBlock(Level level, BlockPos pos, boolean moved,
                                                Operation<Boolean> original) {
        BlockState before = WorldEventCapture.safeSnapshotBlockState(level, pos);
        try {
            return original.call(level, pos, moved);
        } finally {
            Map<String, String> metadata = new java.util.TreeMap<>(
                    WorldEventCapture.entityProvenanceMetadata(enderman, null, false));
            metadata.put("entity_uuid", enderman.getUUID().toString());
            metadata.put("movement", "TAKE_BLOCK");
            WorldEventCapture.recordDirectBlockChange(level, pos, before, "ENDERMAN_BLOCK_MOVE", "enderman",
                    "EndermanTakeBlockGoal.tick.removeBlock", UUID.randomUUID().toString(), metadata, false);
        }
    }
}
