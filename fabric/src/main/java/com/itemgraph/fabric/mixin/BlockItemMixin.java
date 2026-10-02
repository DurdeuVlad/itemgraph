package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.itemgraph.audit.AdminMutationCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

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

    @WrapMethod(method = "place")
    private InteractionResult itemgraph$capturePlacement(BlockPlaceContext context,
                                                          Operation<InteractionResult> original) throws Throwable {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        boolean creativeServerPlayer = context != null && context.getPlayer() instanceof ServerPlayer player
                && player.getAbilities().instabuild && context.getLevel() instanceof ServerLevel;
        try {
            if (creativeServerPlayer && context.getLevel() instanceof ServerLevel level) {
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
        } catch (Throwable failure) {
            AdminMutationCapture.recordCreativeBlockCaptureFailureSafely("fabric_placement_snapshot", failure);
            before.clear();
        }
        InteractionResult result;
        try {
            result = original.call(context);
        } catch (Throwable failure) {
            recordPartialCreativePlacement(context, before, (BlockItem) (Object) this,
                    "fabric_block_item_place_exception");
            throw failure;
        }
        if (creativeServerPlayer && !before.isEmpty()) {
            FabricNativeAuditEventListener.onBlockItemPlacedSafely(context, (BlockItem) (Object) this,
                    result, before);
        }
        return result;
    }

    private static void recordPartialCreativePlacement(BlockPlaceContext context,
                                                       Map<BlockPos, BlockState> before,
                                                       BlockItem item, String causeStatus) {
        try {
            if (context == null || !(context.getPlayer() instanceof ServerPlayer player)
                    || !player.getAbilities().instabuild || !(context.getLevel() instanceof ServerLevel level)) {
                return;
            }
            for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
                BlockState after = level.getBlockState(entry.getKey());
                if (AdminMutationCapture.isCreativePlacedBlockChange(entry.getValue(), after, item.getBlock(),
                        entry.getKey().equals(context.getClickedPos()))) {
                    AdminMutationCapture.recordCreativeBlockUnresolvedSafely(player, "place",
                            BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString(), entry.getKey(), causeStatus);
                }
            }
        } catch (Throwable failure) {
            AdminMutationCapture.recordCreativeBlockCaptureFailureSafely("fabric_placement_exception", failure);
        }
    }
}
