package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records the returned fluid block only after vanilla successfully picks it up. */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @WrapOperation(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"
            ),
            require = 1
    )
    private ItemStack itemgraph$recordBucketPickup(BucketPickup pickup, Player player,
                                                    LevelAccessor level, BlockPos pos,
                                                    BlockState sourceState,
                                                    Operation<ItemStack> original) {
        ItemStack result = original.call(pickup, player, level, pos, sourceState);
        if (player instanceof ServerPlayer serverPlayer
                && level instanceof net.minecraft.world.level.Level actualLevel) {
            FabricNativeAuditEventListener.recordBucketPickup(
                    serverPlayer, actualLevel, pos, sourceState, result);
        }
        return result;
    }
}
