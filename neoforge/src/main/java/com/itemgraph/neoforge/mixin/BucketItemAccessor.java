package com.itemgraph.neoforge.mixin;

import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the fluid stored by the returned BucketItem on Minecraft 1.21.1. */
@Mixin(BucketItem.class)
public interface BucketItemAccessor {
    @Accessor("content")
    Fluid itemgraph$getContent();
}
