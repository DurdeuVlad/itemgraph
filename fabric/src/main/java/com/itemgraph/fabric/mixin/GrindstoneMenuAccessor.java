package com.itemgraph.fabric.mixin;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.GrindstoneMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the grindstone menu's input container and level access. */
@Mixin(GrindstoneMenu.class)
public interface GrindstoneMenuAccessor {
    @Accessor("repairSlots")
    Container itemgraph$getRepairSlots();

    @Accessor("access")
    ContainerLevelAccess itemgraph$getAccess();
}
