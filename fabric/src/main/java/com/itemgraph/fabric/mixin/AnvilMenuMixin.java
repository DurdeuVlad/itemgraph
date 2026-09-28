package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures anvil rename and repair results before input-slot consumption. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
    @Inject(method = "onTake", at = @At("HEAD"))
    private void itemgraph$recordAnvil(Player player, ItemStack output, CallbackInfo callback) {
        if (player instanceof ServerPlayer serverPlayer) {
            FabricNativeAuditEventListener.onAnvilResult(serverPlayer,
                    ((ItemCombinerMenuAccessor) (Object) this).itemgraph$getInputSlots(), output);
        }
    }
}
