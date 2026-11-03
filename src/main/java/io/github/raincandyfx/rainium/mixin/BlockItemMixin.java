package io.github.raincandyfx.rainium.mixin;

import io.github.raincandyfx.rainium.ReactHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells reactions when you place a block (on your side, as you place it). */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
    private void rainium$placed(BlockPlaceContext ctx, CallbackInfoReturnable<InteractionResult> cir) {
        if (!ReactHooks.active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (ctx.getLevel().isClientSide() && ctx.getPlayer() == mc.player && cir.getReturnValue() != null && cir.getReturnValue().consumesAction())
            ReactHooks.blockPlaced(String.valueOf(BuiltInRegistries.BLOCK.getKey(((BlockItem) (Object) this).getBlock())));
    }
}
