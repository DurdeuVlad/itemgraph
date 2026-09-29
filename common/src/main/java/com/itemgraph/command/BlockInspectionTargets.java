package com.itemgraph.command;

import com.itemgraph.query.AuditEventQueryService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.List;

/** Resolves one clicked block into the bounded logical target used by inspection queries. */
public final class BlockInspectionTargets {
    private static final int MAX_TARGET_POSITIONS = 2;

    private BlockInspectionTargets() {
    }

    /**
     * Returns the clicked position plus the second physical half for a double chest
     * or door when the partner is present and belongs to the same block structure.
     */
    public static List<AuditEventQueryService.ExactPosition> resolve(Level level, BlockPos clicked) {
        if (level == null || clicked == null) {
            return List.of();
        }
        List<AuditEventQueryService.ExactPosition> positions = new ArrayList<>(MAX_TARGET_POSITIONS);
        positions.add(position(clicked));

        BlockState state = level.getBlockState(clicked);
        if (state == null) {
            return List.copyOf(positions);
        }
        BlockPos partner = null;
        if (state.getBlock() instanceof ChestBlock
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            partner = clicked.relative(ChestBlock.getConnectedDirection(state));
        } else if (state.getBlock() instanceof DoorBlock) {
            DoubleBlockHalf half = state.getValue(DoorBlock.HALF);
            partner = clicked.relative(half == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN);
        }

        if (partner != null && isMatchingPartner(level, state, partner)) {
            positions.add(position(partner));
        }
        return List.copyOf(positions);
    }

    private static boolean isMatchingPartner(Level level, BlockState state, BlockPos partner) {
        BlockState partnerState = level.getBlockState(partner);
        if (partnerState == null || partnerState.getBlock() != state.getBlock()) {
            return false;
        }
        if (state.getBlock() instanceof ChestBlock) {
            ChestType clickedType = state.getValue(ChestBlock.TYPE);
            ChestType partnerType = partnerState.getValue(ChestBlock.TYPE);
            return clickedType != ChestType.SINGLE && partnerType != ChestType.SINGLE
                    && clickedType != partnerType;
        }
        if (state.getBlock() instanceof DoorBlock) {
            return partnerState.getValue(DoorBlock.HALF) != state.getValue(DoorBlock.HALF);
        }
        return false;
    }

    private static AuditEventQueryService.ExactPosition position(BlockPos pos) {
        return new AuditEventQueryService.ExactPosition(pos.getX(), pos.getY(), pos.getZ());
    }
}
