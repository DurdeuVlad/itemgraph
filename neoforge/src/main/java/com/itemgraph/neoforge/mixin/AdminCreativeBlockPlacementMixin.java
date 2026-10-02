package com.itemgraph.neoforge.mixin;

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

/** Confirms creative placement only after BlockItem.place returns and final states are visible. */
@Mixin(BlockItem.class)
abstract class AdminCreativeBlockPlacementMixin {
    private static final int SNAPSHOT_RADIUS = 2;

    @WrapMethod(method = "place")
    private InteractionResult itemgraph$recordCompletedPlacement(BlockPlaceContext context,
                                                                   Operation<InteractionResult> original)
            throws Throwable {
        if (context == null || !(context.getPlayer() instanceof ServerPlayer player)
                || !player.getAbilities().instabuild || !(context.getLevel() instanceof ServerLevel)) {
            return original.call(context);
        }
        Map<BlockPos, BlockState> beforeStates;
        try {
            beforeStates = captureBefore(context);
        } catch (Throwable failure) {
            AdminMutationCapture.recordCreativeBlockCaptureFailureSafely("placement_snapshot", failure);
            beforeStates = Map.of();
        }
        InteractionResult result;
        try {
            result = original.call(context);
        } catch (Throwable failure) {
            recordPartialCreativePlacement(context, beforeStates, (BlockItem) (Object) this);
            throw failure;
        }
        try {
            if (result.consumesAction() && context.getLevel() instanceof ServerLevel level) {
                for (Map.Entry<BlockPos, BlockState> entry : beforeStates.entrySet()) {
                    BlockPos position = entry.getKey();
                    BlockState after = level.getBlockState(position);
                    if (AdminMutationCapture.isCreativePlacedBlockChange(entry.getValue(), after,
                            ((BlockItem) (Object) this).getBlock(), position.equals(context.getClickedPos()))) {
                        AdminMutationCapture.recordCreativeBlockConfirmedSafely(player, "place",
                                BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString(), position,
                                "neoforge_block_item_place_return");
                    }
                }
            }
        } catch (Throwable failure) {
            AdminMutationCapture.recordCreativeBlockCaptureFailureSafely("placement_result", failure);
        }
        return result;
    }

    private static void recordPartialCreativePlacement(BlockPlaceContext context,
                                                        Map<BlockPos, BlockState> beforeStates,
                                                        BlockItem item) {
        try {
            if (context == null || !(context.getPlayer() instanceof ServerPlayer player)
                    || !player.getAbilities().instabuild || !(context.getLevel() instanceof ServerLevel level)) {
                return;
            }
            for (Map.Entry<BlockPos, BlockState> entry : beforeStates.entrySet()) {
                BlockState after = level.getBlockState(entry.getKey());
                if (AdminMutationCapture.isCreativePlacedBlockChange(entry.getValue(), after, item.getBlock(),
                        entry.getKey().equals(context.getClickedPos()))) {
                    AdminMutationCapture.recordCreativeBlockUnresolvedSafely(player, "place",
                            BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString(), entry.getKey(),
                            "neoforge_block_item_place_exception");
                }
            }
        } catch (Throwable failure) {
            AdminMutationCapture.recordCreativeBlockCaptureFailureSafely("neoforge_placement_exception", failure);
        }
    }

    private static Map<BlockPos, BlockState> captureBefore(BlockPlaceContext context) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        if (context != null && context.getLevel() instanceof ServerLevel level) {
            BlockPos center = context.getClickedPos();
            for (int dx = -SNAPSHOT_RADIUS; dx <= SNAPSHOT_RADIUS; dx++) {
                for (int dy = -SNAPSHOT_RADIUS; dy <= SNAPSHOT_RADIUS; dy++) {
                    for (int dz = -SNAPSHOT_RADIUS; dz <= SNAPSHOT_RADIUS; dz++) {
                        BlockPos position = center.offset(dx, dy, dz).immutable();
                        before.put(position, level.getBlockState(position));
                    }
                }
            }
        }
        return before;
    }
}
