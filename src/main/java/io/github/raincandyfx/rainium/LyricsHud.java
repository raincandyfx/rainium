package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Lyrics over the game, like subtitles (the On screen placement): the sung line with a line or two around
 * it, in Scatter's font and colours, the sung part lit as it's sung. Also the short notes the lyrics keys
 * give ("Lyrics 0.25 s later"), whatever the placement. Drawn with the HUD, so F1 hides it too.
 */
final class LyricsHud {
    private LyricsHud() {}

    private static String note;
    private static long noteNanos;
    private static final FontDescription[] FONTS = new FontDescription[LyricsWords.FONT_KEYS.length];
    private static double slideShown;   // where the lines are, in lines, as they slide up (smoothed)
    private static int slideLine = -2;
    private static long lastNanos;

    /** Shows a short note over the hotbar for a moment. */
    static void note(String text) { note = text; noteNanos = System.nanoTime(); }

    static void draw(GuiGraphicsExtractor g, Object deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double scale = Math.max(1, mc.getWindow().getGuiScale());
        int sw = (int) Math.ceil(mc.getWindow().getWidth() / scale), sh = (int) Math.ceil(mc.getWindow().getHeight() / scale);
        LayerConfig.LyricsSettings s = RainiumClient.ASSETS.editable().lyrics;
        if (s.enabled && s.onScreen()) lines(g, mc, s, sw, sh);
        noteDraw(g, mc, sw, sh);
    }

    // ------------------------------------------------------------------ the note from a key
    private static void noteDraw(GuiGraphicsExtractor g, Minecraft mc, int sw, int sh) {
        if (note == null) return;
        double age = (System.nanoTime() - noteNanos) / 1e9;
        if (age > 2.2) { note = null; return; }
        double a = age < 1.6 ? 1 : 1 - (age - 1.6) / 0.6;
        int alpha = (int) Math.round(a * 255);
        if (alpha < 6) return;
        Component c = Component.literal(note);
        int w = mc.font.width(c);
        int x = (sw - w) / 2, y = sh - 72;
        g.fill(x - 4, y - 3, x + w + 4, y + 11, ((int) (alpha * 0.55) << 24));
        g.text(mc.font, c, x, y, (alpha << 24) | 0xFFFFFF, true);
    }

    // ------------------------------------------------------------------ the lines
    private static void lines(GuiGraphicsExtractor g, Minecraft mc, LayerConfig.LyricsSettings s, int sw, int sh) {
        LyricsLrc lrc = Lyrics.lyrics();
        double show = Lyrics.shown() * Math.max(0, Math.min(1, s.opacityPct / 100.0));
        if (lrc == null || show < 0.02) return;
        double pos = Lyrics.at();
        int cur = lrc.lineAt(pos);
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0.016 : Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        if (cur != slideLine) {   // a new line: the lines were one further down a moment ago, and slide up
            slideShown = s.screenSlide && slideLine >= -1 && cur == slideLine + 1 ? slideShown + 1 : 0;
            slideLine = cur;
        }
        slideShown -= slideShown * Math.min(1, dt * 12);
        if (Math.abs(slideShown) < 0.002) slideShown = 0;

        Font font = mc.font;
        float k = (float) Math.max(0.25, s.sizePct / 100.0 * 1.25);   // 100 % is a little bigger than chat
        int pitch = Math.floorMod(s.scatterFont, LyricsWords.FONT_KEYS.length) < 2 ? 10 : 12;
        int ctx = Math.max(0, Math.min(3, s.screenContext));
        double seconds = now / 1e9;
        int avail = (int) Math.max(60, sw * 0.86 / k);   // in unscaled pixels

        // the sung line, then those around it, each wrapped to fit; laid out from the sung line outwards
        float cx = (float) (sw * Math.max(0, Math.min(100, s.screenXPct)) / 100.0);
        float cy = (float) (sh * Math.max(0, Math.min(100, s.screenYPct)) / 100.0);
        int align = Math.floorMod(s.screenAlign, 3);
        float rowH = pitch * k * 1.15f;
        // backdrop: one band behind the whole block
        int first = Math.max(0, cur - ctx), last = Math.min(lrc.lines.size() - 1, Math.max(cur, 0) + ctx);
        if (cur < 0) { first = 0; last = Math.min(lrc.lines.size() - 1, ctx); }
        List<List<String>> wrapped = new java.util.ArrayList<>();
        int maxW = 0, rowsAbove = 0, rowsTotal = 0;
        for (int i = first; i <= last; i++) {
            String text = LyricsWords.clean(lrc.lines.get(i).text());
            List<String> rows = fit(font, s, text, avail);
            wrapped.add(rows);
            for (String r : rows) maxW = Math.max(maxW, font.width(styled(s, r, 0xFFFFFF, -1, 0, 0, 0, 0, seconds)));
            if (i < Math.max(cur, 0)) rowsAbove += rows.size();
            rowsTotal += rows.size();
        }
        if (rowsTotal == 0) return;
        float top = cy - rowsAbove * rowH - rowH / 2 + (float) (slideShown * rowH * 1.2);
        if (s.screenBackdropPct > 0) {
            int ba = (int) Math.round(Math.min(1, s.screenBackdropPct / 100.0) * show * 255);
            float bw = maxW * k + 16, bx = align == 1 ? cx - 8 : align == 2 ? cx - bw + 8 : cx - bw / 2;
            if (ba > 3) g.fill(Math.round(bx), Math.round(top - 5), Math.round(bx + bw), Math.round(top + rowsTotal * rowH + 3), ba << 24);
        }
        float y = top;
        for (int i = first; i <= last; i++) {
            LyricsLrc.Line line = lrc.lines.get(i);
            List<String> rows = wrapped.get(i - first);
            int dist = Math.abs(i - cur);
            boolean sung = i == cur;
            double a = show * (sung ? 1 : cur < 0 && i == 0 ? 0.75 : Math.max(0.25, 0.6 - 0.15 * (dist - 1)));
            if (sung) a *= Math.min(1, 0.4 + (pos - line.t()) / 0.25 * 0.6);   // comes up as it starts
            int alpha = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
            String joined = String.join(" ", rows);
            int lineLen = joined.length();
            int lit = !s.screenKaraoke ? lineLen : sung ? LyricsWords.typed(line, pos) : i < cur ? lineLen : 0;
            if (!sung && !s.screenKaraoke) lit = lineLen;
            int base = LyricsWords.tint(s.scatterColor, s.textColor, i, seconds);
            int before = 0;
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r);
                if (alpha >= 6 && !row.isEmpty()) {
                    Component c = styled(s, row, base, lit - before, r, rows.size(), before, lineLen, seconds);
                    float w = font.width(c) * k;
                    float x = align == 1 ? cx : align == 2 ? cx - w : cx - w / 2;
                    g.pose().pushMatrix();
                    g.pose().translate(x, y);
                    g.pose().scale(k, k);
                    g.text(font, c, 0, 0, (alpha << 24) | 0xFFFFFF, s.screenShadow);
                    g.pose().popMatrix();
                }
                before += row.length() + 1;
                y += rowH;
            }
        }
    }

    /** The line in rows that fit across the screen (most lines are one row). */
    private static List<String> fit(Font font, LayerConfig.LyricsSettings s, String text, int avail) {
        if (text.isEmpty()) return List.of();
        int w = font.width(styled(s, text, 0xFFFFFF, -1, 0, 0, 0, 0, 0));
        if (w <= avail) return List.of(text);
        int max = Math.max(8, (int) (text.length() * (double) avail / w));
        return LyricsWords.wrap(text, max);
    }

    /**
     * A row in the Scatter font and colour: the first [lit] characters as they are, the rest dimmed (karaoke);
     * lit below 0 means all lit. With a gradient each character gets its own colour by where it sits.
     */
    private static Component styled(LayerConfig.LyricsSettings s, String row, int base, int lit, int r, int rows, int before, int lineLen, double seconds) {
        FontDescription fd = font(s.scatterFont);
        boolean grad = s.scatterColor == LyricsWords.COLOR_GRADIENT;
        if (!grad && (lit < 0 || lit >= row.length())) return withColor(row, base, fd);
        int[] ends = grad ? LyricsWords.gradientEnds(s.scatterGradient, s.scatterGradA, s.scatterGradB) : null;
        MutableComponent out = Component.empty();
        if (!grad) {   // two runs: lit and dimmed
            int l = Math.max(0, lit);
            if (l > 0) out.append(withColor(row.substring(0, l), base, fd));
            out.append(withColor(row.substring(l), dim(base), fd));
            return out;
        }
        for (int i = 0; i < row.length(); ) {
            int cp = row.codePointAt(i), n = Character.charCount(cp);
            double t = LyricsWords.gradientAt(s.scatterGradDir, i, row.length(), r, rows, before + i, lineLen, seconds);
            int c = LyricsWords.mix(ends[0], ends[1], t);
            if (lit >= 0 && i >= lit) c = dim(c);
            out.append(withColor(row.substring(i, i + n), c, fd));
            i += n;
        }
        return out;
    }

    private static int dim(int rgb) { return LyricsWords.mix(rgb, 0x3A3A3A, 0.55); }

    private static MutableComponent withColor(String text, int rgb, FontDescription fd) {
        TextColor tc = TextColor.fromRgb(rgb & 0xFFFFFF);
        return Component.literal(text).withStyle(st -> fd == null ? st.withColor(tc) : st.withColor(tc).withFont(fd));
    }

    private static FontDescription font(int f) {
        int i = Math.floorMod(f, FONTS.length);
        String id = LyricsWords.fontId(i);
        if (id == null) return null;
        if (FONTS[i] == null) FONTS[i] = new FontDescription.Resource(Identifier.parse(id));
        return FONTS[i];
    }
}
