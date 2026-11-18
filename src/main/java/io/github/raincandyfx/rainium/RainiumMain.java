package io.github.raincandyfx.rainium;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Runs on both sides: registers the multiplayer channel, and on a dedicated server starts the
 * server part (sharing cosmetics between players). Nothing here touches client classes.
 */
public final class RainiumMain implements ModInitializer {
    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(RainiumPayload.TYPE, RainiumPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(RainiumPayload.TYPE, RainiumPayload.CODEC);
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) RainiumServerFabric.init();
    }
}
