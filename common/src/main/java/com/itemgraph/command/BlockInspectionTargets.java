package com.itemgraph.command;

import com.itemgraph.query.AuditEventQueryService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves one clicked block into the bounded logical target used by inspection queries. */
public final class BlockInspectionTargets {
    private static final int MAX_TARGET_POSITIONS = 2;

    private BlockInspectionTargets() {
    }

    /**
     * Restricts right-click inspection to GriefLogger's pinned functional block
     * classes, while extending it to modded block entities that expose the
     * vanilla Container contract used by ItemGraph's own flow capture.
     */
    public static boolean isInspectableRightClickTarget(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (level.getBlockEntity(pos) instanceof Container) {
            return true;
        }
        BlockState state = level.getBlockState(pos);
        return isGriefLoggerFunctionalBlock(state);
    }

    /**
     * Resolve the history target used by a right-click inspector. Known
     * functional blocks and Container-backed modded blocks use the clicked
     * block; ordinary blocks use the adjacent block on the clicked face.
     */
    public static BlockPos resolveRightClickTarget(Level level, BlockPos clicked, Direction face) {
        if (clicked == null) {
            return null;
        }
        if (level == null || face == null || isInspectableRightClickTarget(level, clicked)) {
            return clicked.immutable();
        }
        return clicked.relative(face).immutable();
    }

    /**
     * Matches the exact vanilla functional-block predicate used by the pinned
     * GriefLogger release. Modded {@link Container} implementations remain
     * inspectable through {@link #isInspectableRightClickTarget(Level, BlockPos)},
     * but are not silently promoted into GriefLogger's native action set.
     */
    public static boolean isGriefLoggerFunctionalBlock(Level level, BlockPos pos) {
        return level != null && pos != null && isGriefLoggerFunctionalBlock(level.getBlockState(pos));
    }

    /** GriefLogger's block-action hook records main-hand clicks only. */
    public static boolean isGriefLoggerBlockInteraction(Level level, BlockPos pos, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND && isGriefLoggerFunctionalBlock(level, pos);
    }

    private static boolean isGriefLoggerFunctionalBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block instanceof FenceGateBlock
                || block instanceof DispenserBlock
                || block instanceof NoteBlock
                || block instanceof AbstractChestBlock<?>
                || block instanceof AbstractFurnaceBlock
                || block instanceof LeverBlock
                || block instanceof TrapDoorBlock
                || block instanceof DoorBlock
                || block instanceof BrewingStandBlock
                || block instanceof DiodeBlock
                || block instanceof HopperBlock
                || block instanceof DropperBlock
                || block instanceof ShulkerBoxBlock
                || block instanceof BarrelBlock
                || block instanceof GrindstoneBlock
                || block instanceof ButtonBlock
                || block instanceof LoomBlock
                || block instanceof CraftingTableBlock
                || block instanceof CartographyTableBlock
                || block instanceof EnchantingTableBlock
                || block instanceof SmithingTableBlock
                || block instanceof StonecutterBlock
                || block instanceof CrafterBlock
                || block instanceof VaultBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof SignBlock
                || block instanceof LecternBlock
                || block instanceof BeaconBlock;
    }

    /**
     * Returns the clicked position plus the second physical half for a double chest
     * or door when the partner is present and belongs to the same block structure.
     */
    public static List<AuditEventQueryService.ExactPosition> resolve(Level level, BlockPos clicked) {
        return resolveBlockPositions(level, clicked).stream().map(BlockInspectionTargets::position).toList();
    }

    /** Returns the physical positions belonging to the clicked logical structure. */
    public static List<BlockPos> resolveBlockPositions(Level level, BlockPos clicked) {
        if (level == null || clicked == null) {
            return List.of();
        }
        return resolveBlockPositions(level, clicked, level.getBlockState(clicked), true);
    }

    /** Resolves a removal target from the pre-removal state supplied by a loader event. */
    public static List<BlockPos> resolveBlockPositions(Level level, BlockPos clicked, BlockState clickedState) {
        return resolveBlockPositions(level, clicked, clickedState, false);
    }

    private static List<BlockPos> resolveBlockPositions(Level level, BlockPos clicked,
                                                        BlockState clickedState, boolean validatePartner) {
        if (level == null || clicked == null) {
            return List.of();
        }
        List<BlockPos> positions = new ArrayList<>(MAX_TARGET_POSITIONS);
        positions.add(clicked.immutable());

        BlockState state = clickedState;
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

        if (partner != null && (!validatePartner || isMatchingPartner(level, state, partner))) {
            positions.add(partner.immutable());
        }
        return List.copyOf(positions);
    }

    /** Returns the stable anchor used for future container observations. */
    public static BlockPos canonicalPosition(Level level, BlockPos clicked) {
        return resolveBlockPositions(level, clicked).stream()
                .min(Comparator.<BlockPos>comparingInt(pos -> pos.getX())
                        .thenComparingInt(pos -> pos.getY())
                        .thenComparingInt(pos -> pos.getZ()))
                .orElse(clicked == null ? null : clicked.immutable());
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
                    && clickedType != partnerType
                    && state.getValue(ChestBlock.FACING) == partnerState.getValue(ChestBlock.FACING);
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
