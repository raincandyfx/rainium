package io.github.raincandyfx.rainium.mixin;

import io.github.raincandyfx.rainium.ReactHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells reactions about damage, critical hits and entity events (totem pops, shield blocks) as they
 * arrive. Each handler first runs on the network thread and is then re-run on the game thread; only
 * the game-thread run is passed on.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void rainium$damage(ClientboundDamageEventPacket p, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread()) ReactHooks.damage(p.entityId(), p.sourceCauseId());
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void rainium$entityEvent(ClientboundEntityEventPacket p, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread() && mc.level != null) ReactHooks.entityEvent(p.getEntity(mc.level), p.getEventId());
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void rainium$animate(ClientboundAnimatePacket p, CallbackInfo ci) {
        if (Minecraft.getInstance().isSameThread() && (p.getAction() == ClientboundAnimatePacket.CRITICAL_HIT || p.getAction() == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT))
            ReactHooks.crit(p.getId());
    }
}
