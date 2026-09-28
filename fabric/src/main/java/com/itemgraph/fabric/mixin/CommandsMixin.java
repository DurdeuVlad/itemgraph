package com.itemgraph.fabric.mixin;

import com.itemgraph.fabric.FabricNativeAuditEventListener;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no public server callback for general command dispatch. This
 * narrowly scoped entry hook records the same pre-execution evidence as the
 * NeoForge CommandEvent adapter; it does not claim command success.
 */
@Mixin(Commands.class)
public final class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void itemgraph$recordCommandAttempt(ParseResults<CommandSourceStack> parse,
                                                 String command, CallbackInfo callback) {
        FabricNativeAuditEventListener.onCommandAttempt(parse, command);
    }
}
