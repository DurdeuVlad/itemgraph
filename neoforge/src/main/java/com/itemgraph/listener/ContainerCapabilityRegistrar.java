package com.itemgraph.listener;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.WorldlyContainerHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.VanillaHopperItemHandler;
import net.neoforged.neoforge.items.wrapper.ForwardingItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registers {@link ContainerCapabilityWrapper} providers for vanilla container
 * blocks via the NeoForge mod event bus (0.2.0 — Issue 4).
 *
 * <h2>Provider ordering</h2>
 * <p>{@code BlockCapability.getCapability} returns the first non-null provider in
 * registration order, and NeoForge registers its own vanilla providers
 * ({@code InvWrapper}/{@code SidedInvWrapper}/{@code VanillaHopperItemHandler})
 * at normal priority before this mod's bus runs. Registering at
 * {@link EventPriority#HIGHEST} places ItemGraph's providers first so the wrapper
 * is actually reached.
 *
 * <h2>Behaviour preservation</h2>
 * <p>Each provider wraps the exact handler vanilla would have produced, so the
 * interception is observation-only:
 * <ul>
 *   <li>Sided containers (furnaces, brewing stand, shulker box) keep
 *       {@link SidedInvWrapper} face restrictions.</li>
 *   <li>Chests keep the merged {@code CompoundContainer} view via
 *       {@link ChestBlock#getContainer} (double chests stay intact).</li>
 *   <li>Hoppers keep {@link VanillaHopperItemHandler} cooldown semantics.</li>
 *   <li>The composter keeps its {@link ForwardingItemHandler} re-evaluation.</li>
 * </ul>
 * <p>Types vanilla does not serve (lectern, ender chest) are deliberately not
 * registered — adding a capability where vanilla has none would create new
 * automation behaviour, not observe existing behaviour.
 */
public class ContainerCapabilityRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCapabilityRegistrar.class);

    /** Mirrors NeoForge's sided vanilla registrations ({@code SidedInvWrapper::new}). */
    private static final List<BlockEntityType<? extends BlockEntity>> SIDED_TYPES = List.of(
            BlockEntityType.BLAST_FURNACE,
            BlockEntityType.BREWING_STAND,
            BlockEntityType.FURNACE,
            BlockEntityType.SMOKER,
            BlockEntityType.SHULKER_BOX
    );

    /** Mirrors NeoForge's non-sided vanilla registrations ({@code new InvWrapper(be)}). */
    private static final List<BlockEntityType<? extends BlockEntity>> NON_SIDED_TYPES = List.of(
            BlockEntityType.BARREL,
            BlockEntityType.CHISELED_BOOKSHELF,
            BlockEntityType.DISPENSER,
            BlockEntityType.DROPPER,
            BlockEntityType.JUKEBOX,
            BlockEntityType.CRAFTER,
            BlockEntityType.DECORATED_POT
    );

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        int registered = 0;

        for (BlockEntityType<? extends BlockEntity> type : SIDED_TYPES) {
            registered += tryRegister(event, type, (be, side) ->
                    be instanceof WorldlyContainer wc
                            ? wrap(new SidedInvWrapper(wc, side), be)
                            : null);
        }
        for (BlockEntityType<? extends BlockEntity> type : NON_SIDED_TYPES) {
            registered += tryRegister(event, type, (be, side) ->
                    be instanceof Container c
                            ? wrap(new InvWrapper(c), be)
                            : null);
        }
        registered += tryRegister(event, BlockEntityType.HOPPER, (hopper, side) ->
                wrap(new VanillaHopperItemHandler(hopper), hopper));

        // Chests are served at block level by vanilla so a double chest exposes one
        // merged CompoundContainer. Reproduce that view before wrapping.
        try {
            event.registerBlock(
                    Capabilities.ItemHandler.BLOCK,
                    (level, pos, state, blockEntity, side) -> {
                        Container container = ChestBlock.getContainer(
                                (ChestBlock) state.getBlock(), state, level, pos, true);
                        return container == null
                                ? null
                                : wrap(new InvWrapper(container), pos, level.dimension());
                    },
                    Blocks.CHEST, Blocks.TRAPPED_CHEST);
            registered += 2;
        } catch (Exception e) {
            LOGGER.warn("ItemGraph: could not register chest capability wrapper: {}", e.toString());
        }

        // Composter: vanilla serves a re-evaluating sided wrapper over the holder's
        // state-dependent container; keep that delegation inside our observer.
        try {
            event.registerBlock(
                    Capabilities.ItemHandler.BLOCK,
                    (level, pos, state, blockEntity, side) -> {
                        WorldlyContainerHolder holder = (WorldlyContainerHolder) state.getBlock();
                        return wrap(new ForwardingItemHandler(() ->
                                new SidedInvWrapper(holder.getContainer(level.getBlockState(pos), level, pos), side)),
                                pos, level.dimension());
                    },
                    Blocks.COMPOSTER);
            registered++;
        } catch (Exception e) {
            LOGGER.warn("ItemGraph: could not register composter capability wrapper: {}", e.toString());
        }

        LOGGER.info("ItemGraph: registered container capability wrappers for {} vanilla blocks/block entity types.", registered);
    }

    /**
     * Registers one block-entity provider, counting success. A provider returning
     * {@code null} defers to the next registered provider (vanilla's own).
     */
    private <BE extends BlockEntity> int tryRegister(
            RegisterCapabilitiesEvent event,
            BlockEntityType<BE> beType,
            net.neoforged.neoforge.capabilities.ICapabilityProvider<BE, net.minecraft.core.Direction, IItemHandler> provider) {
        try {
            event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, beType, provider);
            return 1;
        } catch (Exception e) {
            LOGGER.warn("ItemGraph: could not register capability wrapper for {}: {}", beType, e.toString());
            return 0;
        }
    }

    private static IItemHandler wrap(IItemHandler inner, BlockEntity be) {
        Level level = be.getLevel();
        // No level yet (block entity not placed): defer so vanilla's provider answers.
        return level == null ? null : new ContainerCapabilityWrapper(inner, be.getBlockPos(), level.dimension());
    }

    private static IItemHandler wrap(IItemHandler inner, BlockPos pos, ResourceKey<Level> dimension) {
        return new ContainerCapabilityWrapper(inner, pos, dimension);
    }
}
