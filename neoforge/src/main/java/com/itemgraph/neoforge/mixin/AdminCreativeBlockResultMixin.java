package com.itemgraph.neoforge.mixin;

import com.itemgraph.audit.AdminMutationCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * NeoForge's BreakEvent runs before block removal. This return hook pairs the
 * creative break callback with vanilla's actual success result. This wrap is a
 * last resort because NeoForge exposes no post-result block-break event in
 * 1.21.1; the event alone cannot distinguish a canceled or failed mutation.
 */
@Mixin(ServerPlayerGameMode.class)
abstract class AdminCreativeBlockResultMixin {
    @Shadow @Final protected ServerPlayer player;

    @WrapMethod(method = "destroyBlock")
    private boolean itemgraph$recordBreakResult(BlockPos position, Operation<Boolean> original) throws Throwable {
        boolean completed = false;
        boolean broken = false;
        try {
            broken = original.call(position);
            completed = true;
            return broken;
        } finally {
            AdminMutationCapture.finishCreativeBlockBreakSafely(player, position, completed, broken);
        }
    }
}
