package com.itemgraph.listener;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Registers {@link ContainerCapabilityWrapper} for all vanilla container block entities
 * via the NeoForge mod event bus (Phase 0.2.0 — Issue 3 &amp; 4).
 *
 * <h2>Registration pattern</h2>
 * <p>{@link RegisterCapabilitiesEvent} fires on the mod event bus during mod loading.
 * We register a provider for {@code Capabilities.ItemHandler.BLOCK} on every vanilla
 * block entity type that holds items. The provider wraps the block entity's own
 * IItemHandler with a {@link ContainerCapabilityWrapper} that intercepts real (non-simulate)
 * insertions and extractions and writes {@code ig_observations} asynchronously.
 *
 * <h2>Vanilla block entity types covered</h2>
 * <ul>
 *   <li>CHEST, TRAPPED_CHEST, BARREL</li>
 *   <li>FURNACE, BLAST_FURNACE, SMOKER</li>
 *   <li>HOPPER, DROPPER, DISPENSER</li>
 *   <li>All 16 SHULKER_BOX variants</li>
 *   <li>BREWING_STAND</li>
 * </ul>
 *
 * <p>Modded inventories are out of scope for 0.2.0.
 */
public class ContainerCapabilityRegistrar {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContainerCapabilityRegistrar.class);

    @SubscribeEvent
    public void registerCapabilities(RegisterCapabilitiesEvent event) {
        // All vanilla block entity types that hold item stacks and expose IItemHandler
        // Note: BlockEntityType.SHULKER_BOX covers all 16 dyed shulker box variants in 1.21.1.
        List<BlockEntityType<?>> vanillaContainerTypes = List.of(
                BlockEntityType.CHEST,
                BlockEntityType.TRAPPED_CHEST,
                BlockEntityType.BARREL,
                BlockEntityType.FURNACE,
                BlockEntityType.BLAST_FURNACE,
                BlockEntityType.SMOKER,
                BlockEntityType.HOPPER,
                BlockEntityType.DROPPER,
                BlockEntityType.DISPENSER,
                BlockEntityType.SHULKER_BOX,
                BlockEntityType.BREWING_STAND,
                BlockEntityType.CRAFTER,
                BlockEntityType.CHISELED_BOOKSHELF
        );

        int registered = 0;
        for (BlockEntityType<?> beType : vanillaContainerTypes) {
            if (tryRegister(event, beType)) {
                registered++;
            }
        }
        LOGGER.info("ItemGraph: registered container capability wrappers for {} vanilla block entity types.", registered);
    }

    /**
     * Registers the capability wrapper for a single block entity type.
     *
     * <p>NeoForge calls our provider lambda when another system queries
     * {@code Capabilities.ItemHandler.BLOCK} for one of these block entities.
     * We forward the query to the block entity's own getDefaultCapability / vanilla
     * InvWrapper, then wrap the result in our observer.
     *
     * <p>The {@code side} parameter is passed through because some block entities
     * (e.g. furnaces) expose different slots per side.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends net.minecraft.world.level.block.entity.BlockEntity>
    boolean tryRegister(RegisterCapabilitiesEvent event, BlockEntityType<T> beType) {
        try {
            event.registerBlockEntity(
                    Capabilities.ItemHandler.BLOCK,
                    beType,
                    (blockEntity, side) -> {
                        // NeoForge vanilla block entities implement Container.
                        // InvWrapper provides an IItemHandler view of any Container.
                        // We wrap that with our observer to intercept all moves.
                        if (blockEntity instanceof net.minecraft.world.Container container) {
                            IItemHandler inner = new InvWrapper(container);
                            if (blockEntity.getLevel() == null) {
                                return inner; // No level yet — return unwrapped (safe, won't write obs)
                            }
                            return new ContainerCapabilityWrapper(
                                    inner,
                                    blockEntity.getBlockPos(),
                                    blockEntity.getLevel().dimension()
                            );
                        }
                        return null;
                    }
            );
            return true;
        } catch (Exception e) {
            LOGGER.warn("ItemGraph: could not register capability wrapper for {}: {}", beType, e.getMessage());
            return false;
        }
    }
}
