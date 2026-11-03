package io.github.raincandyfx.rainium.mixin;

import io.github.raincandyfx.rainium.ReactHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells reactions about damage, critical hits, entity events (totem pops, shield blocks, things
 * breaking), particles (sweeps), world events (splash potions) and item pickups as they arrive.
 * Each handler first runs on the network thread and is then re-run on the game thread; only the
 * game-thread run is passed on, and only while some reaction uses anything.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    private static boolean rainium$wanted() { return ReactHooks.active() && Minecraft.getInstance().isSameThread(); }

    @Inject(method = "handleDamageEvent", at = @At("HEAD"))
    private void rainium$damage(ClientboundDamageEventPacket p, CallbackInfo ci) {
        if (rainium$wanted()) ReactHooks.damage(p.entityId(), p.sourceCauseId(), p.sourceDirectId(), p.sourceType().getRegisteredName());
    }

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void rainium$entityEvent(ClientboundEntityEventPacket p, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (rainium$wanted() && mc.level != null) ReactHooks.entityEvent(p.getEntity(mc.level), p.getEventId());
    }

    @Inject(method = "handleAnimate", at = @At("HEAD"))
    private void rainium$animate(ClientboundAnimatePacket p, CallbackInfo ci) {
        if (rainium$wanted() && (p.getAction() == ClientboundAnimatePacket.CRITICAL_HIT || p.getAction() == ClientboundAnimatePacket.MAGIC_CRITICAL_HIT))
            ReactHooks.crit(p.getId());
    }

    @Inject(method = "handleParticleEvent", at = @At("HEAD"))
    private void rainium$particle(ClientboundLevelParticlesPacket p, CallbackInfo ci) {
        if (rainium$wanted()) ReactHooks.particle(String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(p.getParticle().getType())), p.getX(), p.getY(), p.getZ());
    }

    @Inject(method = "handleLevelEvent", at = @At("HEAD"))
    private void rainium$levelEvent(ClientboundLevelEventPacket p, CallbackInfo ci) {
        if (rainium$wanted()) ReactHooks.levelEvent(p.getType(), p.getPos());
    }

    @Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
    private void rainium$pickUp(ClientboundTakeItemEntityPacket p, CallbackInfo ci) {
        if (rainium$wanted()) ReactHooks.pickUp(p.getPlayerId(), p.getItemId());
    }
}
