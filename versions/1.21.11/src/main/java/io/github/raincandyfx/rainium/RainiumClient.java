package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Client entrypoint (Minecraft 1.21.11): adds the mesh render layer to players, the editor key (K) and /rainium. */
public final class RainiumClient implements ClientModInitializer {
    static final Assets ASSETS = new Assets();
    /** GLFW key code for K (Minecraft 1.21.x keys are GLFW key codes). Rebindable in Controls. */
    private static final int KEY_K = 75;
    private static KeyMapping openKey;
    private static boolean openNextTick;

    @Override
    public void onInitializeClient() {
        ASSETS.init();
        Tutorial.init(ASSETS.dir());
        Updater.checkAsync(ASSETS.editable().autoUpdate, ASSETS.editable().devUpdates);
        try {
            CaptureRenderer.register();
        } catch (Throwable t) {   // pictures and recordings are optional; never stop the mod loading
            Assets.LOG.warn("[rainium] pictures and recordings are unavailable: " + t);
        }

        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                helper.register(new MeshRenderLayer(avatar));
            }
            if (renderer instanceof net.minecraft.client.renderer.entity.ArmorStandRenderer) registerStandLayer(renderer, helper);   // class names are obfuscated outside development
        });

        try {
            TutorialHooks.register();
        } catch (Throwable t) {   // the tour is optional; never stop the mod loading
            Assets.LOG.warn("[rainium] the tutorial is unavailable: " + t);
        }

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rainium", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.rainium.open", InputConstants.Type.KEYSYM, KEY_K, category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.consumeClick()) {
                if (mc.screen == null) openNextTick = true;
            }
            if (openNextTick) {
                openNextTick = false;
                mc.setScreen(new RainiumScreen());
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
            dispatcher.register(ClientCommandManager.literal("rainium")
                .executes(c -> { openNextTick = true; return 1; })   // open after the chat screen closes
                .then(ClientCommandManager.literal("reload").executes(c -> {
                    ASSETS.reload();
                    String err = ASSETS.lastError();
                    c.getSource().sendFeedback(Component.translatable(err == null ? "rainium.command.reloaded" : "rainium.command.problem", err));
                    return 1;
                })));
        });

        Assets.LOG.info("[rainium] ready; press K or run /rainium to open the editor ({})", Privacy.redactForLog(String.valueOf(ASSETS.dir())));
    }

    static void open() { openNextTick = true; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerStandLayer(Object renderer, LivingEntityFeatureRendererRegistrationCallback.RegistrationHelper helper) {
        helper.register(new StandRenderLayer((net.minecraft.client.renderer.entity.RenderLayerParent) renderer));
    }

}
