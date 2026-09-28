package com.itemgraph.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record WorldLocation(
        ResourceKey<Level> level,
        BlockPos position) {
}
