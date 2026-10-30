package io.github.raincandyfx.rainium;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Puts the tutorial on the main menu (and, while the tour is on step 1, on Mod Menu's list). Handlers are tied to a
 * token so that a screen being re-initialised (window resized) never ends up with two live copies.
 */
final class TutorialHooks {
    private TutorialHooks() {}

    private static final Map<Screen, Integer> tokens = new WeakHashMap<>();
    private static int counter;

    static void register() {
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            boolean title = screen instanceof TitleScreen;
            boolean mods = !title && screen.getClass().getName().endsWith(".ModsScreen");
            if (title) Tutorial.onTitle();
            if (!Tutorial.wants(title, mods)) return;
            int token = ++counter;
            tokens.put(screen, token);
            boolean block = title;   // on the title screen only the highlighted button works; the mod list stays free to use
            ScreenEvents.afterExtract(screen).register((s, g, mx, my, dt) -> {
                if (!live(s, token) || !Tutorial.wants(s instanceof TitleScreen, !(s instanceof TitleScreen))) return;
                Tutorial.draw(g, Minecraft.getInstance().font, s.width, s.height, mx, my, marks(s), HOST(s), block);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, e) -> !(live(s, token) && Tutorial.wants(s instanceof TitleScreen, !(s instanceof TitleScreen)) && Tutorial.click(e.x(), e.y())));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, e) -> !(live(s, token) && Tutorial.wants(s instanceof TitleScreen, !(s instanceof TitleScreen)) && Tutorial.key(e.key(), block)));
        });
    }

    private static boolean live(Screen s, int token) {
        Integer t = tokens.get(s);
        return t != null && t == token;
    }

    private static Tutorial.Host HOST(Screen from) {
        return new Tutorial.Host() {
            public void tab(String name) { }
            public void ensureHalo() { }
            public void selectHalo() { }
            public void ensureParticle() { }
            public void openEditor() { Minecraft.getInstance().gui.setScreen(new RainiumScreen(from)); }
        };
    }

    /** Where the buttons the tour points at are: Mods on the title screen, Configure on Mod Menu's screen. */
    private static Map<String, int[]> marks(Screen s) {
        Map<String, int[]> m = new HashMap<>();
        try {
            boolean title = s instanceof TitleScreen;
            String key = title ? "modmenu.title" : "modmenu.configure";
            String label = Component.translatable(key).getString();
            if (label.equals(key) || label.isBlank()) label = title ? "Mods" : "Configure";
            for (AbstractWidget w : Screens.getWidgets(s)) {
                if (w == null || !w.visible || w.getMessage() == null) continue;
                if (w.getMessage().getString().startsWith(label)) {
                    m.put(title ? "mods" : "configure", new int[]{w.getX(), w.getY(), w.getWidth(), w.getHeight()});
                    break;
                }
            }
        } catch (Throwable ignored) { }
        return m;
    }
}
