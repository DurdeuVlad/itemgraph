package com.itemgraph.fabric.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the enchantment menu's input container and level access. */
@Mixin(EnchantmentMenu.class)
public interface EnchantmentMenuAccessor {
    @Accessor("enchantSlots")
    Container itemgraph$getEnchantSlots();

    @Accessor("access")
    ContainerLevelAccess itemgraph$getAccess();
}
