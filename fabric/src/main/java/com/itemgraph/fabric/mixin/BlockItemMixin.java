package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fabric has no completed block-placement event. BlockItem.place returns only
 * after the block state has been written, so this hook records that boundary
 * without converting a pre-action interaction into completed evidence. A bounded
 * 5x5x5 before-state snapshot covers vanilla multi-cell placements while keeping
 * the capture allocation bounded.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    private static final int SNAPSHOT_RADIUS = 2;
    private static final ThreadLocal<Map<BlockPos, BlockState>> ITEMGRAPH_BEFORE_STATES = new ThreadLocal<>();

    @Inject(method = "place", at = @At("HEAD"))
    private void itemgraph$captureBeforeStates(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> callback) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        if (context != null && context.getLevel() instanceof ServerLevel level) {
            BlockPos center = context.getClickedPos();
            for (int dx = -SNAPSHOT_RADIUS; dx <= SNAPSHOT_RADIUS; dx++) {
                for (int dy = -SNAPSHOT_RADIUS; dy <= SNAPSHOT_RADIUS; dy++) {
                    for (int dz = -SNAPSHOT_RADIUS; dz <= SNAPSHOT_RADIUS; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz).immutable();
                        before.put(pos, level.getBlockState(pos));
                    }
                }
            }
        }
        // BlockItem.place is not recursive in vanilla. Replacing the prior map keeps
        // an exception path bounded: RETURN is not injected when a modded override
        // throws, so a stack would retain one 125-cell snapshot for every failure.
        ITEMGRAPH_BEFORE_STATES.set(before);
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void itemgraph$recordBlockPlacement(BlockPlaceContext context,
                                                 CallbackInfoReturnable<InteractionResult> callback) {
        Map<BlockPos, BlockState> before = ITEMGRAPH_BEFORE_STATES.get();
        ITEMGRAPH_BEFORE_STATES.remove();
        FabricNativeAuditEventListener.onBlockItemPlaced(context, (BlockItem) (Object) this,
                callback.getReturnValue(), before);
    }
}
