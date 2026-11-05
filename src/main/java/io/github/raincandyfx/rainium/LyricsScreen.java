package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The lyrics page: everything about the lyrics in the world on one screen, like the reactions page.
 * Song and sources, the look of the lyric video, which effects may appear, and how the lyrics are placed.
 * Numbers are typed in (click, type, Enter) and have no limits. Every change is saved straight away.
 */
public final class LyricsScreen extends Screen {
    private static final int INK = 0xFF050B33, SEA = 0xFF1436C9, BRIGHT = 0xFF1F7CFF, CYAN = 0xFF48E6FF, MENU = 0xFF8FF0FF,
        HAZE = 0xFFC9D8FF, PAPER = 0xFFFFFFFF, RED = 0xFFF2163A;
    private static final int FIELD = 0xF2050B33, FIELD_HOVER = 0xFF1A2D86, FIELD_EDGE = 0xFF3C4C90;
    private static final int TEXT = 0xFFF4F7FF, MUTED = HAZE, FAINT = 0xFF8590C4, WARN = 0xFFFFC266;
    private static final int ROW = 14, GAP = 3, BAR = 24;

    private final RainiumScreen parent;
    private final Assets assets = RainiumClient.ASSETS;

    private int scroll, content;
    private String editId;
    private final StringBuilder editBuf = new StringBuilder();
    private boolean editNumeric, editReplace;
    private Consumer<String> editCommit;
    private int[] editRect;
    private String tip, toast = "";
    private int toastColor = TEXT;
    private long toastUntil;

    private record Hit(int x, int y, int w, int h, Runnable click, IntConsumer scrollFn) {}
    private final List<Hit> hits = new ArrayList<>();

    LyricsScreen(RainiumScreen parent) {
        super(Component.translatable("rainium.lyrics.title"));
        this.parent = parent;
    }

    private LayerConfig.LyricsSettings ly() { return assets.editable().lyrics; }

    private void save() { parent.save(); }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fillGradient(0, 0, width, height, 0xFF0A1A7A, 0xFF050B33);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float pt) {
        super.extractRenderState(g, mx, my, pt);
        hits.clear();
        tip = null;
        editRect = null;
        LayerConfig.LyricsSettings ly = ly();
        int colW = Math.min(width - 24, 330);
        boolean two = width >= 2 * 330 + 36;
        int x0 = two ? (width - (2 * colW + 16)) / 2 : (width - colW) / 2;
        int top = BAR + 8, view = height - top - 4;
        g.enableScissor(0, BAR, width, height);
        int yL = top - scroll, yR = top - scroll;
        int endL, endR;
        if (two) {
            endL = placement(g, mx, my, x0, song(g, mx, my, x0, yL, colW, ly), colW, ly);
            endR = effects(g, mx, my, x0 + colW + 16, look(g, mx, my, x0 + colW + 16, yR, colW, ly), colW, ly);
        } else {
            int y = song(g, mx, my, x0, yL, colW, ly);
            y = look(g, mx, my, x0, y, colW, ly);
            y = placement(g, mx, my, x0, y, colW, ly);
            endL = endR = effects(g, mx, my, x0, y, colW, ly);
        }
        g.disableScissor();
        content = Math.max(endL, endR) + scroll - top + 8;
        scroll = Math.max(0, Math.min(scroll, content - view));
        topBar(g, mx, my, ly);
        toast(g);
        if (tip != null) tooltip(g, mx, my, tip);
    }

    // ------------------------------------------------------------------ sections
    private int song(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.LyricsSettings ly) {
        y = section(g, x, y, w, "section.lyrics_song");
        Lyrics.Status st = Lyrics.status();
        NowPlaying.Track tr = Lyrics.track();
        String song = tr == null ? "" : tr.title() + (tr.artist().isBlank() ? "" : " — " + tr.artist());
        String problem = NowPlaying.problem;
        String line = !ly.enabled ? Lang.t("lyrics.off_note")
            : st == Lyrics.Status.FAILED && problem != null ? Lang.t("lyrics.problem." + problem)
            : Lang.t("lyrics.status." + st.name().toLowerCase(Locale.ROOT), song);
        y = wrapped(g, x, y, w, line, !ly.enabled ? FAINT : st == Lyrics.Status.SHOWING ? CYAN : st == Lyrics.Status.FAILED ? WARN : MUTED) + 3;
        y = toggle(g, mx, my, x, y, w, "lyrics_demo", ly.demo, () -> { ly.demo = !ly.demo; save(); });
        List<String> players = List.of(Lang.t("value.lyrics_player.any"), Lang.t("value.lyrics_player.spotify"));
        int pi = ly.player.equals("spotify") ? 1 : 0;
        y = cycle(g, mx, my, x, y, w, "lyrics_player", ly.player.equals("any") || ly.player.equals("spotify") ? players.get(pi) : ly.player,
            d -> { ly.player = ly.player.equals("spotify") ? "any" : "spotify"; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_online", ly.online, () -> { ly.online = !ly.online; save(); });
        y = wrapped(g, x, y, w, Lang.t(ly.online ? "lyrics.online_on" : "lyrics.online_off"), FAINT) + 2;
        y = number(g, mx, my, x, y, w, "lyrics_offset", ly.offset, " s", v -> { ly.offset = (float) v; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_chat", ly.chat, () -> { ly.chat = !ly.chat; save(); });
        y = button(g, mx, my, x, y, w, Lang.t("button.lyrics_folder"), () -> {
            java.nio.file.Path d = assets.dir().resolve("lyrics");
            try { java.nio.file.Files.createDirectories(d); } catch (Exception ignored) {}
            assets.openFolder(d);
        });
        int saved = LyricsLookup.savedCount(assets.dir());
        y = button(g, mx, my, x, y, w, Lang.t("button.lyrics_clear", saved), () -> say(Lang.t("msg.lyrics_cleared", LyricsLookup.clearSaved(assets.dir())), MUTED));
        return y + 6;
    }

    private int look(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.LyricsSettings ly) {
        y = section(g, x, y, w, "section.lyrics_look");
        y = cycle(g, mx, my, x, y, w, "lyrics_mode", Lang.t("value.lyrics_mode." + (ly.video ? "video" : "simple")), d -> { ly.video = !ly.video; save(); });
        if (!ly.video) return y + 6;
        String[] ids = Lyrics.STYLES;
        int si = Math.max(0, java.util.Arrays.asList(ids).indexOf(ly.style));
        y = cycle(g, mx, my, x, y, w, "lyrics_style", Lang.t("value.lyrics_style." + ids[si]), d -> { ly.style = ids[Math.floorMod(si + d, ids.length)]; save(); });
        y = button(g, mx, my, x, y, w, Lang.t("button.lyrics_variation", ly.seed), () -> { ly.seed++; save(); });
        String[] fonts = {"auto", "sans", "serif", "mono"};
        int fi = Math.max(0, java.util.Arrays.asList(fonts).indexOf(ly.font));
        y = cycle(g, mx, my, x, y, w, "lyrics_font", Lang.t("value.lyrics_font." + fonts[fi]), d -> { ly.font = fonts[Math.floorMod(fi + d, fonts.length)]; save(); });
        y = hex(g, mx, my, x, y, w, "lyrics_text_color", ly.textColor, v -> { ly.textColor = v; save(); });
        y = hex(g, mx, my, x, y, w, "lyrics_accent_color", ly.accentColor, v -> { ly.accentColor = v; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_motifs", ly.motifs, () -> { ly.motifs = !ly.motifs; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_new_effects", ly.newEffects, () -> { ly.newEffects = !ly.newEffects; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_decor", ly.decor, () -> { ly.decor = !ly.decor; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_frame", ly.frame, () -> { ly.frame = !ly.frame; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_hud", ly.hud, () -> { ly.hud = !ly.hud; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_ghost", ly.ghostPct, " %", v -> { ly.ghostPct = v; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_grain", ly.grainPct, " %", v -> { ly.grainPct = v; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_halo", ly.haloPct, " %", v -> { ly.haloPct = v; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_backdrop", ly.backdropPct, " %", v -> { ly.backdropPct = v; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_speed", ly.speedPct, " %", v -> { ly.speedPct = Math.max(1, v); save(); });
        y = number(g, mx, my, x, y, w, "lyrics_fps", ly.fps, " /s", v -> { ly.fps = Math.max(0.5, v); save(); });
        return y + 6;
    }

    private int placement(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.LyricsSettings ly) {
        y = section(g, x, y, w, "section.lyrics_place");
        y = cycle(g, mx, my, x, y, w, "lyrics_placement", Lang.t("value.lyrics_placement." + (ly.placement == 1 ? "follow" : "fixed")),
            d -> { ly.placement = ly.placement == 1 ? 0 : 1; save(); });
        y = wrapped(g, x, y, w, Lang.t("lyrics.placement_note." + (ly.placement == 1 ? "follow" : "fixed")), FAINT) + 2;
        y = number(g, mx, my, x, y, w, "lyrics_size", ly.sizePct, " %", v -> { ly.sizePct = Math.max(0.1, v); save(); });
        y = number(g, mx, my, x, y, w, "lyrics_opacity", ly.opacityPct, " %", v -> { ly.opacityPct = v; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_float", ly.floatPct, " %", v -> { ly.floatPct = v; save(); });
        y = number(g, mx, my, x, y, w, "lyrics_roll", ly.rollDeg, " °", v -> { ly.rollDeg = v; save(); });
        y = cycle(g, mx, my, x, y, w, "lyrics_side_mode", Lang.t("value.lyrics_side_mode." + ly.sideMode), d -> { ly.sideMode = Math.floorMod(ly.sideMode + d, 3); save(); });
        if (ly.placement == 0) {
            y = number(g, mx, my, x, y, w, "lyrics_distance", ly.distance, " m", v -> { ly.distance = Math.max(0.1, v); save(); });
            y = number(g, mx, my, x, y, w, "lyrics_height", ly.heightPct, " %", v -> { ly.heightPct = v; save(); });
            y = number(g, mx, my, x, y, w, "lyrics_side", ly.sidePct, " %", v -> { ly.sidePct = v; save(); });
            y = toggle(g, mx, my, x, y, w, "lyrics_avoid", ly.avoidEnemies, () -> { ly.avoidEnemies = !ly.avoidEnemies; save(); });
        } else {
            y = number(g, mx, my, x, y, w, "lyrics_gap", ly.gapPct, " %", v -> { ly.gapPct = v; save(); });
        }
        y = toggle(g, mx, my, x, y, w, "lyrics_keep", ly.keepOnScreen, () -> { ly.keepOnScreen = !ly.keepOnScreen; save(); });
        y = toggle(g, mx, my, x, y, w, "lyrics_through_blocks", ly.throughBlocks, () -> { ly.throughBlocks = !ly.throughBlocks; save(); });
        return y + 6;
    }

    private int effects(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, LayerConfig.LyricsSettings ly) {
        y = section(g, x, y, w, "section.lyrics_effects");
        if (!ly.video) return wrapped(g, x, y, w, Lang.t("lyrics.effects_simple"), FAINT);
        List<String[]> layouts = new ArrayList<>();
        for (var e : JizuraLayouts.ALL.entrySet()) layouts.add(new String[]{e.getKey(), e.getValue().name()});
        y = checks(g, mx, my, x, y, w, "lyrics.layouts_count", layouts, ly.offLayouts, false);
        y = section(g, x, y + 4, w, "section.lyrics_in");
        List<String[]> ins = new ArrayList<>();
        for (JizuraNative.Enter e : JizuraNative.Enter.values()) if (e != JizuraNative.Enter.TYPE) ins.add(new String[]{e.name().toLowerCase(Locale.ROOT), Lang.t("value.lyrics_way." + e.name().toLowerCase(Locale.ROOT))});
        y = checks(g, mx, my, x, y, w, "lyrics.ways_count", ins, ly.offEnters, true);
        y = section(g, x, y + 4, w, "section.lyrics_out");
        List<String[]> outs = new ArrayList<>();
        for (JizuraNative.Exit e : JizuraNative.Exit.values()) outs.add(new String[]{e.name().toLowerCase(Locale.ROOT), Lang.t("value.lyrics_way." + e.name().toLowerCase(Locale.ROOT))});
        return checks(g, mx, my, x, y, w, "lyrics.ways_count", outs, ly.offExits, true);
    }

    /** A two-column list of things that can each be switched off, with All and None. */
    private int checks(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String countKey, List<String[]> all, java.util.Set<String> off, boolean allowNone) {
        int total = all.size(), on = 0;
        for (String[] e : all) if (!off.contains(e[0])) on++;
        g.text(font, RainiumScreen.body(Lang.t(countKey, on, total)), x, y + 3, MUTED, false);
        int bw = 34, bx = x + w - 2 * bw - 4;
        y = chips(g, mx, my, bx, y, bw, () -> { for (String[] e : all) off.remove(e[0]); save(); }, Lang.t("lyrics.all"), () -> {
            for (String[] e : all) off.add(e[0]);
            if (!allowNone) off.remove(all.get(0)[0]);   // keep one, or nothing would draw
            save();
        }, Lang.t("lyrics.none"));
        int cw = (w - 6) / 2, i = 0;
        for (String[] e : all) {
            String key = e[0];
            int cx = x + (i % 2) * (cw + 6), cy = y + (i / 2) * (ROW + 1);
            boolean isOff = off.contains(key), hov = in(mx, my, cx, cy, cw, ROW);
            g.fill(cx, cy, cx + cw, cy + ROW, hov ? FIELD_HOVER : FIELD);
            g.outline(cx + 3, cy + 3, 8, 8, isOff ? FIELD_EDGE : CYAN);
            if (!isOff) g.fill(cx + 5, cy + 5, cx + 9, cy + 9, CYAN);
            g.text(font, RainiumScreen.body(fit(e[1], cw - 18)), cx + 15, cy + 3, isOff ? FAINT : TEXT, false);
            hits.add(new Hit(cx, cy, cw, ROW, () -> { if (!off.remove(key)) off.add(key); save(); }, null));
            i++;
        }
        return y + ((i + 1) / 2) * (ROW + 1) + 6;
    }

    // ------------------------------------------------------------------ rows
    private int section(GuiGraphicsExtractor g, int x, int y, int w, String key) {
        String s = Lang.t(key).toUpperCase(Locale.ROOT);
        g.text(font, RainiumScreen.styled(s, true, true), x, y, CYAN, false);
        g.fill(x, y + 11, x + w, y + 12, 0x60FFFFFF);
        return y + 16;
    }

    private int toggle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String key, boolean on, Runnable flip) {
        boolean band = in(mx, my, x - 4, y - 1, w + 6, ROW + 2);
        if (band) { g.fill(x - 4, y - 1, x + w + 2, y + ROW + 1, 0xF2FFFFFF); g.fill(x - 4, y + ROW, x + w + 2, y + ROW + 1, RED); tip = Lang.optional("tip." + key); }
        g.text(font, RainiumScreen.styled(fit(Lang.t("label." + key), w - 54), false, true), x, y + 3, band ? INK : MENU, false);
        drawSwitch(g, x + w - 48, y + 1, on);
        hits.add(new Hit(x, y, w, ROW, flip, null));
        return y + ROW + GAP;
    }

    private int cycle(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String key, String shown, IntConsumer step) {
        rowLabel(g, mx, my, x, y, w, key);
        int vw = 124, vx = x + w - vw, bw = 14;
        stepBtn(g, mx, my, vx, y, bw, "<", () -> step.accept(-1));
        stepBtn(g, mx, my, vx + vw - bw, y, bw, ">", () -> step.accept(1));
        g.fill(vx + bw, y, vx + vw - bw, y + ROW, FIELD);
        String s = fit(shown, vw - 2 * bw - 4);
        g.text(font, RainiumScreen.body(s), vx + (vw - font.width(RainiumScreen.body(s))) / 2, y + 3, TEXT, false);
        hits.add(new Hit(vx + bw, y, vw - 2 * bw, ROW, () -> step.accept(1), null));
        return y + ROW + GAP;
    }

    /** A number you click and type; no limits beyond it being a number. */
    private int number(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String key, double value, String unit, java.util.function.DoubleConsumer set) {
        rowLabel(g, mx, my, x, y, w, key);
        int fw = 78, fx = x + w - fw;
        String shown = fmt(value) + unit, id = "n:" + key;
        boolean editing = id.equals(editId), hov = in(mx, my, fx, y, fw, ROW);
        g.fill(fx, y, fx + fw, y + ROW, editing ? INK : hov ? FIELD_HOVER : FIELD);
        g.outline(fx, y, fw, ROW, editing ? CYAN : FIELD_EDGE);
        String s = editing ? tail(editBuf.toString(), fw - 8) + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : fit(shown, fw - 4);
        int tx = editing ? fx + 3 : fx + (fw - font.width(RainiumScreen.body(s))) / 2;
        g.text(font, RainiumScreen.body(s), tx, y + 3, editing && editReplace ? CYAN : TEXT, false);
        if (editing) editRect = new int[]{fx, y, fw, ROW};
        hits.add(new Hit(fx, y, fw, ROW, () -> beginEdit(id, fmt(value), true, str -> {
            Double d = parse(str);
            if (d == null || d.isNaN() || d.isInfinite()) { say(Lang.t("lyrics.not_a_number"), WARN); return; }
            set.accept(d);
        }), null));
        return y + ROW + GAP;
    }

    /** A colour typed as six hex digits; blank means the style's own. */
    private int hex(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String key, String value, java.util.function.Consumer<String> set) {
        rowLabel(g, mx, my, x, y, w, key);
        int fw = 78, fx = x + w - fw;
        String id = "h:" + key;
        boolean editing = id.equals(editId), hov = in(mx, my, fx, y, fw, ROW);
        Color sw = JizuraNative.Look.color(value);
        g.fill(fx, y, fx + fw, y + ROW, editing ? INK : hov ? FIELD_HOVER : FIELD);
        g.outline(fx, y, fw, ROW, editing ? CYAN : FIELD_EDGE);
        String shown = editing ? "#" + editBuf + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : value.isBlank() ? Lang.t("value.lyrics_style_color") : "#" + value.replace("#", "");
        g.text(font, RainiumScreen.body(shown), fx + 4, y + 3, editing ? TEXT : sw == null ? FAINT : TEXT, false);
        if (sw != null && !editing) g.fill(fx + fw - 12, y + 3, fx + fw - 4, y + ROW - 3, 0xFF000000 | sw.getRGB());
        if (editing) editRect = new int[]{fx, y, fw, ROW};
        hits.add(new Hit(fx, y, fw, ROW, () -> beginEdit(id, value.replace("#", ""), false, str -> {
            String t = str.trim().replace("#", "");
            if (t.isEmpty()) { set.accept(""); return; }
            if (JizuraNative.Look.color(t) == null) { say(Lang.t("lyrics.not_a_colour"), WARN); return; }
            set.accept("#" + t.toLowerCase(Locale.ROOT));
        }), null));
        return y + ROW + GAP;
    }

    private void rowLabel(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String key) {
        boolean band = in(mx, my, x - 4, y - 1, w + 6, ROW + 2);
        if (band) tip = Lang.optional("tip." + key);
        g.text(font, RainiumScreen.styled(fit(Lang.t("label." + key), w - 130), false, true), x, y + 3, band ? PAPER : MENU, false);
    }

    private int button(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String label, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, hov ? BRIGHT : SEA);
        String s = fit(label, w - 8);
        g.text(font, RainiumScreen.body(s), x + (w - font.width(RainiumScreen.body(s))) / 2, y + 3, PAPER, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
        return y + ROW + GAP;
    }

    private int chips(GuiGraphicsExtractor g, int mx, int my, int x, int y, int bw, Runnable a, String an, Runnable b, String bn) {
        stepBtn(g, mx, my, x, y, bw, an, a);
        stepBtn(g, mx, my, x + bw + 4, y, bw, bn, b);
        return y + ROW + GAP;
    }

    private void stepBtn(GuiGraphicsExtractor g, int mx, int my, int x, int y, int w, String s, Runnable click) {
        boolean hov = in(mx, my, x, y, w, ROW);
        g.fill(x, y, x + w, y + ROW, hov ? BRIGHT : SEA);
        g.text(font, RainiumScreen.body(s), x + (w - font.width(RainiumScreen.body(s))) / 2, y + 3, PAPER, false);
        hits.add(new Hit(x, y, w, ROW, click, null));
    }

    private void drawSwitch(GuiGraphicsExtractor g, int x, int y, boolean on) {
        int w = 48, h = 12, half = w / 2;
        g.fill(x, y, x + w, y + h, FIELD);
        g.fill(on ? x + 1 : x + half - 1, y + 1, (on ? x + 1 : x + half - 1) + half, y + h - 1, PAPER);
        String a = Lang.t("value.on").toUpperCase(Locale.ROOT), b = Lang.t("value.off").toUpperCase(Locale.ROOT);
        g.text(font, RainiumScreen.body(a), x + (half - font.width(RainiumScreen.body(a))) / 2 + 1, y + 2, on ? INK : FAINT, false);
        g.text(font, RainiumScreen.body(b), x + half + (half - font.width(RainiumScreen.body(b))) / 2 - 1, y + 2, on ? FAINT : INK, false);
    }

    private void topBar(GuiGraphicsExtractor g, int mx, int my, LayerConfig.LyricsSettings ly) {
        g.fill(0, 0, width, BAR, 0xF0050B33);
        g.fill(0, BAR - 1, width, BAR, 0x50FFFFFF);
        String back = Lang.t("react.back").toUpperCase(Locale.ROOT);
        int bw = font.width(RainiumScreen.styled(back, true, true)) + 14;
        boolean hov = in(mx, my, 6, 5, bw, 14);
        g.fill(6, 5, 6 + bw, 19, hov ? BRIGHT : SEA);
        g.text(font, RainiumScreen.styled(back, true, true), 11, 8, PAPER, false);
        hits.add(new Hit(6, 5, bw, 14, this::back, null));
        String title = Lang.t("lyrics.title").toUpperCase(Locale.ROOT);
        int tw = font.width(RainiumScreen.styled(title, true, true)) + 12, tx = 6 + bw + 6;
        g.fill(tx, 5, tx + tw, 19, PAPER);
        g.text(font, RainiumScreen.styled(title, true, true), tx + 6, 8, INK, false);
        int sx = width - 54;
        drawSwitch(g, sx, 6, ly.enabled);
        hits.add(new Hit(sx, 5, 48, 14, () -> { ly.enabled = !ly.enabled; save(); }, null));
        if (in(mx, my, sx, 5, 48, 14)) tip = Lang.t("tip.lyrics_on");
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        if (editId != null) {
            if (editRect != null && in(mx, my, editRect[0], editRect[1], editRect[2], editRect[3])) return true;
            commitEdit();
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit h = hits.get(i);
            if (h.click() == null || !in(mx, my, h.x(), h.y(), h.w(), h.h())) continue;
            if (my < BAR && h.y() >= BAR) continue;   // scrolled up under the bar
            h.click().run();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0) return false;
        if (editId != null) commitEdit();
        scroll = Math.max(0, scroll - (int) Math.signum(sy) * 24);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (editId != null) {
            if (k == RainiumScreen.SC_ENTER || k == RainiumScreen.SC_KP_ENTER) { commitEdit(); return true; }
            if (k == RainiumScreen.SC_ESC) { editId = null; editCommit = null; return true; }
            if (k == RainiumScreen.SC_BACKSPACE || k == RainiumScreen.SC_DELETE) {
                if (editReplace) editBuf.setLength(0); else if (editBuf.length() > 0) editBuf.setLength(editBuf.length() - 1);
                editReplace = false;
                return true;
            }
            char c = editNumeric ? typed(k) : hexChar(k);
            if (c != 0) {
                if (editReplace) editBuf.setLength(0);
                editReplace = false;
                if (editBuf.length() < 24) editBuf.append(c);
            }
            return true;
        }
        if (k == RainiumScreen.SC_ESC) { back(); return true; }
        return super.keyPressed(event);
    }

    private static char hexChar(int k) {
        char d = RainiumScreen.digit(k);
        if (d != 0) return d;
        if (k >= RainiumScreen.SC_A && k <= RainiumScreen.SC_A + 5) return (char) ('a' + (k - RainiumScreen.SC_A));
        return 0;
    }

    private static char typed(int k) {
        char d = RainiumScreen.digit(k);
        if (d != 0) return d;
        if (k == RainiumScreen.SC_PERIOD || k == RainiumScreen.SC_KP_PERIOD || k == RainiumScreen.SC_COMMA) return '.';
        if (k == RainiumScreen.SC_MINUS || k == RainiumScreen.SC_KP_MINUS) return '-';
        return 0;
    }

    @Override
    public boolean isPauseScreen() { return false; }   // the lyrics keep playing in the world behind

    @Override
    public void onClose() { back(); }

    private void back() {
        commitEdit();
        Minecraft.getInstance().gui.setScreen(parent);
    }

    private void beginEdit(String id, String initial, boolean numeric, Consumer<String> commit) {
        commitEdit();
        editId = id;
        editBuf.setLength(0);
        editBuf.append(initial);
        editReplace = true;
        editNumeric = numeric;
        editCommit = commit;
    }

    private void commitEdit() {
        if (editId == null) return;
        String v = editBuf.toString();
        Consumer<String> c = editCommit;
        editId = null;
        editCommit = null;
        if (c != null) c.accept(v);
    }

    static Double parse(String s) {
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }

    static String fmt(double v) {
        StringBuilder b = new StringBuilder();
        JsonWriter.num(b, Math.round(v * 1000.0) / 1000.0);
        return b.toString();
    }

    // ------------------------------------------------------------------ small helpers
    private void say(String s, int color) { toast = s; toastColor = color; toastUntil = System.currentTimeMillis() + 2800; }

    private void toast(GuiGraphicsExtractor g) {
        if (System.currentTimeMillis() > toastUntil || toast.isEmpty()) return;
        String s = fit(toast, width - 30);
        int w = font.width(RainiumScreen.body(s)) + 14, x = (width - w) / 2, y = height - 24;
        g.fill(x, y, x + w, y + 15, 0xF4050B33);
        g.fill(x, y, x + 2, y + 15, toastColor);
        g.text(font, RainiumScreen.body(s), x + 8, y + 4, toastColor, false);
    }

    private void tooltip(GuiGraphicsExtractor g, int mx, int my, String s) {
        List<String> lines = wrap(s, 170);
        int w = 8, h = lines.size() * 10 + 6;
        for (String l : lines) w = Math.max(w, font.width(RainiumScreen.body(l)) + 10);
        int x = Math.max(2, Math.min(mx + 10, width - w - 4)), y = Math.min(my + 12, height - h - 4);
        g.fill(x, y, x + w, y + h, 0xF4050B33);
        g.outline(x, y, w, h, CYAN);
        int yy = y + 4;
        for (String l : lines) { g.text(font, RainiumScreen.body(l), x + 5, yy, TEXT, false); yy += 10; }
    }

    private String fit(String s, int w) {
        if (s == null) return "";
        if (font.width(RainiumScreen.body(s)) <= w) return s;
        int n = s.length();
        while (n > 0 && font.width(RainiumScreen.body(s.substring(0, n) + "…")) > w) n--;
        return s.substring(0, n) + "…";
    }

    private String tail(String s, int w) {
        if (font.width(RainiumScreen.body(s)) <= w) return s;
        int a = 0;
        while (a < s.length() && font.width(RainiumScreen.body("…" + s.substring(a))) > w) a++;
        return "…" + s.substring(a);
    }

    private List<String> wrap(String text, int w) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String t = line.length() == 0 ? word : line + " " + word;
                if (font.width(RainiumScreen.body(t)) > w && line.length() > 0) { out.add(line.toString()); line.setLength(0); line.append(word); }
                else { line.setLength(0); line.append(t); }
            }
            out.add(line.toString());
        }
        return out;
    }

    private int wrapped(GuiGraphicsExtractor g, int x, int y, int w, String text, int color) {
        for (String line : wrap(text, w)) { g.text(font, RainiumScreen.body(line), x, y, color, false); y += 10; }
        return y;
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }
}
