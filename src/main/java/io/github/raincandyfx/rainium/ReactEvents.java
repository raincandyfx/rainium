package io.github.raincandyfx.rainium;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.core.registries.BuiltInRegistries;

/** Fabric's client events that reactions listen to: entities appearing and leaving, your block breaks, chat. */
final class ReactEvents {
    private ReactEvents() {}

    static void register() {
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            try { ReactSensors.onEntityLoad(entity); } catch (Throwable ignored) { }
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            try { ReactSensors.onEntityUnload(entity); } catch (Throwable ignored) { }
        });
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            try {
                if (ReactSensors.usage().has("block_broken")) ReactSensors.onBlockBroken(String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock())));
            } catch (Throwable ignored) { }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            try {
                if (signed != null && ReactSensors.usage().has("chat")) ReactSensors.onChat(signed.sender(), signed.signedContent());
            } catch (Throwable ignored) { }
        });
    }
}
