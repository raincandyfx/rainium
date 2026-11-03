package io.github.raincandyfx.rainium.mixin;

import io.github.raincandyfx.rainium.ReactHooks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells reactions when a player swings an arm (your own swings, and others' as the server reports them). */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySwingMixin {
    @Inject(method = "swing(Lnet/minecraft/world/InteractionHand;Z)V", at = @At("HEAD"))
    private void rainium$swing(CallbackInfo ci) {
        Object self = this;
        if (self instanceof Entity e && e.level().isClientSide()) ReactHooks.swing(e);
    }
}
