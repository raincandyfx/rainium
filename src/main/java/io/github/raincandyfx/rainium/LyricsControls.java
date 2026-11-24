package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * Lyrics without opening the editor: three keys (unbound until you bind them in Controls) to switch the
 * lyrics on and off and nudge their timing, /rainium lyrics, and the On screen placement's HUD layer.
 */
final class LyricsControls {
    private LyricsControls() {}

    static final float STEP = 0.25f;   // seconds per press
    private static KeyMapping toggleKey, earlierKey, laterKey;

    static void register(KeyMapping.Category category) {
        int none = InputConstants.UNKNOWN.getValue();
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rainium.lyrics_toggle", InputConstants.Type.KEYBOARD, none, category));
        earlierKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rainium.lyrics_earlier", InputConstants.Type.KEYBOARD, none, category));
        laterKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rainium.lyrics_later", InputConstants.Type.KEYBOARD, none, category));
        try {
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("rainium", "lyrics"), (g, dt) -> LyricsHud.draw(g, dt));
        } catch (Throwable t) {
            Assets.LOG.warn("[rainium] on-screen lyrics are unavailable: " + t);
        }
    }

    static void tick(Minecraft mc) {
        if (toggleKey == null) return;
        while (toggleKey.consumeClick()) LyricsHud.note(setEnabled(!settings().enabled));
        while (earlierKey.consumeClick()) LyricsHud.note(nudge(-STEP));
        while (laterKey.consumeClick()) LyricsHud.note(nudge(STEP));
    }

    private static LayerConfig.LyricsSettings settings() { return RainiumClient.ASSETS.editable().lyrics; }

    static String setEnabled(boolean on) {
        settings().enabled = on;
        RainiumClient.ASSETS.save();
        return Lang.t(on ? "lyrics.key.on" : "lyrics.key.off");
    }

    /** + shows the lyrics later (when they come too soon), - sooner. */
    static String nudge(float by) { return setOffset(settings().offset + by); }

    static String setOffset(float to) {
        LayerConfig.LyricsSettings s = settings();
        s.offset = Math.round(Math.max(-5f, Math.min(5f, to)) * 100f) / 100f;
        RainiumClient.ASSETS.save();
        return Lang.t("lyrics.key.offset", String.format(Locale.ROOT, "%+.2f", s.offset));
    }

    /** /rainium lyrics [on | off | earlier | later | offset <seconds> | again] */
    static LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return ClientCommands.literal("lyrics")
            .executes(c -> { c.getSource().sendFeedback(Component.literal(status())); return 1; })
            .then(ClientCommands.literal("on").executes(c -> say(c.getSource(), setEnabled(true))))
            .then(ClientCommands.literal("off").executes(c -> say(c.getSource(), setEnabled(false))))
            .then(ClientCommands.literal("toggle").executes(c -> say(c.getSource(), setEnabled(!settings().enabled))))
            .then(ClientCommands.literal("earlier").executes(c -> say(c.getSource(), nudge(-STEP))))
            .then(ClientCommands.literal("later").executes(c -> say(c.getSource(), nudge(STEP))))
            .then(ClientCommands.literal("offset")
                .executes(c -> say(c.getSource(), Lang.t("lyrics.key.offset", String.format(Locale.ROOT, "%+.2f", settings().offset))))
                .then(ClientCommands.argument("seconds", FloatArgumentType.floatArg(-5f, 5f))
                    .executes(c -> say(c.getSource(), setOffset(FloatArgumentType.getFloat(c, "seconds"))))))
            .then(ClientCommands.literal("again").executes(c -> { Lyrics.retry(); return say(c.getSource(), Lang.t("lyrics.key.again")); }));
    }

    private static int say(FabricClientCommandSource src, String text) {
        src.sendFeedback(Component.literal(text));
        return 1;
    }

    /** One line: on or off, the song, and where its lyrics stand. */
    static String status() {
        LayerConfig.LyricsSettings s = settings();
        if (!s.enabled) return Lang.t("lyrics.key.off");
        NowPlaying.Track tr = Lyrics.track();
        String song = tr == null ? "" : tr.title() + (tr.artist().isBlank() ? "" : " — " + tr.artist());
        Lyrics.Status st = Lyrics.status();
        String line = st == Lyrics.Status.SHOWING && Lyrics.estimated() ? Lang.t("lyrics.status.estimated", song)
            : Lang.t("lyrics.status." + st.name().toLowerCase(Locale.ROOT), song);
        return line + "  " + Lang.t("lyrics.key.offset", String.format(Locale.ROOT, "%+.2f", s.offset));
    }
}
