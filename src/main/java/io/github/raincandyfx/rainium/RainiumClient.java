package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Client entrypoint: adds the mesh render layer to players, the editor key (K) and /rainium. */
public final class RainiumClient implements ClientModInitializer {
    static final Assets ASSETS = new Assets();
    /** SDL scancode for K (Minecraft 26.x keys are SDL scancodes). Rebindable in Controls. */
    private static final int KEY_K = 14;
    private static KeyMapping openKey;
    private static boolean openNextTick;

    @Override
    public void onInitializeClient() {
        ASSETS.init();
        Updater.checkAsync(ASSETS.editable().autoUpdate, ASSETS.editable().devUpdates);

        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                helper.register(new MeshRenderLayer(avatar));
            }
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rainium", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rainium.open", InputConstants.Type.KEYBOARD, KEY_K, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (mc.gui.screen() == null) openNextTick = true;
            }
            if (openNextTick) {
                openNextTick = false;
                mc.gui.setScreen(new RainiumScreen());
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommands.literal("rainium")
                .executes(c -> { openNextTick = true; return 1; })   // open after the chat screen closes
                .then(ClientCommands.literal("reload").executes(c -> {
                    ASSETS.reload();
                    String err = ASSETS.lastError();
                    c.getSource().sendFeedback(Component.translatable(err == null ? "rainium.command.reloaded" : "rainium.command.problem", err));
                    return 1;
                })));
        });

        Assets.LOG.info("[rainium] ready; press K or run /rainium to open the editor ({})", Privacy.redactForLog(String.valueOf(ASSETS.dir())));
    }

    static void open() { openNextTick = true; }

}
