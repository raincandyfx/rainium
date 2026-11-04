package io.github.raincandyfx.rainium;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * A native (plain Java2D) lyric video in the manner of JIZURA (https://github.com/852wa/JIZURA, MIT):
 * kinetic typography with its colour schemes, chromatic ghosting, HUD frame, rings, slashes and
 * Japanese motifs. It is not JIZURA's engine; it is a re-creation of its look that runs inside the
 * game with nothing else installed. The style data (palettes, which motion each style prefers) is
 * taken from JIZURA's own style table.
 *
 * Two transparent layers are drawn: BACK (decorations) and FRONT (the lyrics). Nothing is ever drawn
 * over the whole picture, so the world stays visible around the lyrics.
 *
 * Pure (no game classes), so the tests can render frames headlessly.
 */
final class JizuraNative {
    static final int W = 800, H = 450;
    static final double STEP = 1.0 / 12;   // JIZURA moves in steps, about twelve a second

    // ------------------------------------------------------------------ styles
    record Scheme(Color bg, Color fg, Color sub, Color accent, Color accent2, Color dim, Color ghostA, Color ghostB) {}

    static final class Style {
        String id, name;
        final List<Scheme> schemes = new ArrayList<>();
        double grain, scan, ghost = 1;
        final Map<String, Double> layoutBias = new HashMap<>(), enterBias = new HashMap<>(), exitBias = new HashMap<>(), decor = new HashMap<>();
        boolean hud, serif;
    }

    private static Map<String, Style> styles;

    static synchronized Style style(String id) {
        if (styles == null) styles = loadStyles();
        Style s = styles.get(id);
        return s != null ? s : styles.values().iterator().next();
    }

    private static Color col(Object o) {
        String h = String.valueOf(o);
        if (h.startsWith("#") && h.length() == 7) return new Color(Integer.parseInt(h.substring(1), 16));
        return Color.WHITE;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Style> loadStyles() {
        Map<String, Style> out = new LinkedHashMap<>();
        try (InputStream in = JizuraNative.class.getResourceAsStream("/assets/rainium/jizura/styles.json")) {
            if (in != null) {
                Map<String, Object> root = (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                for (var e : root.entrySet()) {
                    Map<String, Object> m = (Map<String, Object>) e.getValue();
                    Style s = new Style();
                    s.id = e.getKey();
                    s.name = String.valueOf(m.get("name"));
                    for (Object so : (List<Object>) m.get("schemes")) {
                        Map<String, Object> c = (Map<String, Object>) so;
                        s.schemes.add(new Scheme(col(c.get("bg")), col(c.get("fg")), col(c.get("sub")), col(c.get("accent")), col(c.get("accent2")),
                            col(c.get("dim")), col(c.get("ghostA")), col(c.get("ghostB"))));
                    }
                    Map<String, Object> tex = (Map<String, Object>) m.get("texture");
                    if (tex != null) { s.grain = num(tex.get("grain")); s.scan = num(tex.get("scan")); }
                    s.ghost = m.get("ghost") == null ? 1 : num(m.get("ghost"));
                    Map<String, Object> bias = (Map<String, Object>) m.get("bias");
                    if (bias != null) {
                        fill(s.layoutBias, bias.get("layout"));
                        fill(s.enterBias, bias.get("enter"));
                        fill(s.exitBias, bias.get("exit"));
                    }
                    fill(s.decor, m.get("decor"));
                    s.hud = Boolean.TRUE.equals(m.get("hud"));
                    s.serif = e.getKey().equals("caution") || e.getKey().equals("paper") || e.getKey().equals("specimen");
                    out.put(s.id, s);
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {   // the data file is missing: one plain style so nothing breaks
            Style s = new Style();
            s.id = "noir"; s.name = "Noir";
            s.schemes.add(new Scheme(new Color(0x060607), new Color(0xF5EEEA), new Color(0xBDB6B2), new Color(0xF5A50C), new Color(0x16F4D4), new Color(0x2A2A2E), new Color(0xF5A50C), new Color(0x16F4D4)));
            s.grain = 0.9; s.hud = true; s.decor.put("rings", 0.8);
            out.put("noir", s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void fill(Map<String, Double> into, Object o) {
        if (o instanceof Map<?, ?> m) for (var e : ((Map<String, Object>) m).entrySet()) into.put(e.getKey(), num(e.getValue()));
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    // ------------------------------------------------------------------ layouts, motions
    enum Layout { CENTER, STACK, ZIGZAG, ARC, ORBIT, SCATTER, BIGSMALL, TYPE, VERTICAL }
    enum Enter { POP, RISE, SLICE, STRETCH, ASSEMBLE, SPIN, WIPE, SCRAMBLE, DROP, TYPE }
    enum Exit { FADE, DRIFT, FALL, EXPLODE, SLICE, SHRINK, GLITCH, SCATTER }

    /** One character of the lyrics, positioned (centre, in picture pixels). */
    static final class Glyph {
        String ch;
        double x, y, size, rot, adv;
        int word, idx, order;
        double reveal;       // when it appears (seconds), set after layout
        double r1, r2, r3;   // fixed random numbers for its motion
    }

    static final class Cut {
        int index;
        LyricsLrc.Line line;
        double t0, t1;
        Scheme sc;
        Layout layout;
        Enter enter;
        Exit exit;
        long seed;
        boolean mono, accentWord;
        int accentIdx = -1;
        final List<Glyph> glyphs = new ArrayList<>();
        final List<String> words = new ArrayList<>();
        final List<Double> wordT = new ArrayList<>();
        double[] box = {0, 0, 0, 0};   // x0, y0, x1, y1 of the text
        String ringText = "";
    }

    final Style style;
    final long seed;
    final boolean motifs, extra;
    final String title;
    private final List<Cut> cuts = new ArrayList<>();
    private double duration;
    private BufferedImage noise;

    JizuraNative(String styleId, long seed, boolean motifs, boolean extra, String title) {
        this.style = style(styleId);
        this.seed = seed;
        this.motifs = motifs;
        this.extra = extra;
        this.title = title == null ? "" : title;
    }

    int cutCount() { return cuts.size(); }
    List<Cut> cuts() { return cuts; }

    // ------------------------------------------------------------------ fonts and glyph shapes
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static String sansFamily, serifFamily, monoFamily;
    private record Shaped(Shape shape, float adv) {}
    private final Map<String, Shaped> shapeCache = new HashMap<>();

    private static synchronized void families() {
        if (sansFamily != null) return;
        Set<String> have = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        sansFamily = firstOf(have, "Noto Sans JP", "Noto Sans CJK JP", "Yu Gothic UI", "Yu Gothic", "Meiryo", "Hiragino Sans", "Segoe UI", Font.SANS_SERIF);
        serifFamily = firstOf(have, "Noto Serif JP", "Noto Serif CJK JP", "Yu Mincho", "MS Mincho", "Hiragino Mincho ProN", Font.SERIF);
        monoFamily = firstOf(have, "IBM Plex Mono", "Consolas", "Menlo", "DejaVu Sans Mono", Font.MONOSPACED);
    }

    private static String firstOf(Set<String> have, String... names) {
        for (String n : names) if (have.contains(n)) return n;
        return names[names.length - 1];
    }

    private Shaped shaped(String ch, double size, boolean serif, boolean mono) {
        families();
        int px = Math.max(6, (int) Math.round(size));
        String key = ch + '|' + px + (mono ? 'm' : serif ? 's' : 'g');
        Shaped s = shapeCache.get(key);
        if (s == null) {
            Font f = new Font(mono ? monoFamily : serif ? serifFamily : sansFamily, Font.BOLD, px);
            if (!f.canDisplay(ch.codePointAt(0))) f = new Font(Font.DIALOG, Font.BOLD, px);   // composite fallback for symbols and kana
            GlyphVector gv = f.createGlyphVector(FRC, ch);
            s = new Shaped(gv.getOutline(), gv.getGlyphMetrics(0).getAdvance());
            if (shapeCache.size() > 3000) shapeCache.clear();
            shapeCache.put(key, s);
        }
        return s;
    }

    private double adv(String ch, double size, boolean serif, boolean mono) { return shaped(ch, size, serif, mono).adv; }

    private double width(String text, double size, boolean serif, boolean mono) {
        double w = 0;
        for (int i = 0; i < text.length(); i++) w += adv(text.substring(i, i + 1), size, serif, mono) + size * 0.02;
        return w;
    }

    // ------------------------------------------------------------------ building the cuts from the lyrics
    void song(LyricsLrc lrc) {
        cuts.clear();
        duration = 0;
        if (lrc == null) return;
        int prev = -1;
        for (int i = 0; i < lrc.lines.size(); i++) {
            LyricsLrc.Line line = lrc.lines.get(i);
            Cut c = new Cut();
            c.index = i;
            c.line = line;
            c.t0 = line.t();
            c.t1 = line.end();
            c.seed = seed * 7919L + i * 104729L + 17;
            SplittableRandom r = new SplittableRandom(c.seed);
            c.sc = i == 0 || r.nextDouble() < 0.7 ? style.schemes.get(0) : style.schemes.get(r.nextInt(style.schemes.size()));
            words(c);
            if (c.words.isEmpty()) continue;
            c.layout = pickLayout(c, r, prev);
            prev = c.layout.ordinal();
            c.enter = c.layout == Layout.TYPE ? Enter.TYPE : pick(Enter.values(), enterWeights(), r);
            c.exit = pick(Exit.values(), exitWeights(), r);
            c.mono = c.layout == Layout.TYPE;
            int longest = 0;
            for (int k = 0; k < c.words.size(); k++) if (c.words.get(k).length() > c.words.get(longest).length()) longest = k;
            c.accentIdx = r.nextDouble() < 0.55 && c.words.size() > 1 ? longest : -1;
            layout(c, r);
            cuts.add(c);
            duration = Math.max(duration, c.t1);
        }
    }

    /** The line's words, with long unspaced runs (Japanese) cut into short pieces so they can come in one by one. */
    private void words(Cut c) {
        for (int k = 0; k < c.line.words().size(); k++) {
            LyricsLrc.Word w = c.line.words().get(k);
            String t = w.text().strip();
            if (t.isEmpty()) continue;
            int[] cps = t.codePoints().toArray();
            int chunk = cjk(t) ? 3 : 99;
            if (cps.length <= chunk + 1) { c.words.add(t); c.wordT.add(w.t()); continue; }
            int n = (cps.length + chunk - 1) / chunk;
            for (int p = 0; p < n; p++) {
                int a = p * cps.length / n, b = (p + 1) * cps.length / n;
                c.words.add(new String(cps, a, b - a));
                c.wordT.add(w.t() + (w.end() - w.t()) * p / n);
            }
        }
    }

    static boolean cjk(String s) {
        int n = 0, total = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            total++;
            Character.UnicodeBlock b = Character.UnicodeBlock.of(ch);
            if (b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) n++;
        }
        return total > 0 && n * 2 > total;
    }

    private double[] enterWeights() {
        Enter[] e = Enter.values();
        double[] w = new double[e.length];
        for (int i = 0; i < e.length; i++) {
            w[i] = 1;
            switch (e[i]) {
                case POP -> w[i] += bias(style.enterBias, "pop");
                case SLICE -> w[i] += bias(style.enterBias, "slice");
                case STRETCH -> w[i] += bias(style.enterBias, "stretch");
                case ASSEMBLE -> w[i] += bias(style.enterBias, "assemble");
                case SPIN -> w[i] += bias(style.enterBias, "spin");
                case WIPE -> w[i] += bias(style.enterBias, "wipe");
                case SCRAMBLE -> w[i] += bias(style.enterBias, "scramble");
                case TYPE -> w[i] = 0;
                default -> { }
            }
        }
        return w;
    }

    private double[] exitWeights() {
        Exit[] e = Exit.values();
        double[] w = new double[e.length];
        for (int i = 0; i < e.length; i++) {
            w[i] = 1;
            switch (e[i]) {
                case DRIFT -> w[i] += bias(style.exitBias, "drift");
                case FALL -> w[i] += bias(style.exitBias, "fall");
                case EXPLODE -> w[i] += bias(style.exitBias, "explode");
                case SLICE -> w[i] += bias(style.exitBias, "slice");
                case SHRINK -> w[i] += bias(style.exitBias, "shrink");
                case GLITCH -> w[i] += bias(style.exitBias, "glitch");
                case SCATTER -> w[i] += bias(style.exitBias, "scatter");
                default -> { }
            }
        }
        return w;
    }

    private static double bias(Map<String, Double> m, String k) { return m.getOrDefault(k, 0.0); }

    private static <T> T pick(T[] items, double[] weights, SplittableRandom r) {
        double sum = 0;
        for (double w : weights) sum += w;
        double x = r.nextDouble() * sum;
        for (int i = 0; i < items.length; i++) { x -= weights[i]; if (x <= 0) return items[i]; }
        return items[items.length - 1];
    }

    private Layout pickLayout(Cut c, SplittableRandom r, int prev) {
        Layout[] all = Layout.values();
        double[] w = new double[all.length];
        boolean jp = cjk(c.line.text());
        int n = c.words.size();
        for (int i = 0; i < all.length; i++) {
            w[i] = switch (all[i]) {
                case CENTER -> 1.2 + bias(style.layoutBias, "center") + bias(style.layoutBias, "tile") * 0.5 + bias(style.layoutBias, "marquee") * 0.5;
                case STACK -> (n >= 2 ? 1 : 0.1) + bias(style.layoutBias, "stack") + bias(style.layoutBias, "condensed");
                case ZIGZAG -> (n >= 3 ? 0.8 : 0.2) + bias(style.layoutBias, "mixed");
                case ARC -> 0.7 + bias(style.layoutBias, "ring") * 0.6;
                case ORBIT -> 0.7 + bias(style.layoutBias, "circle") + bias(style.layoutBias, "gloss") * 0.5;
                case SCATTER -> (n >= 3 ? 0.8 : 0.2) + bias(style.layoutBias, "scatter");
                case BIGSMALL -> (n >= 3 ? 0.9 : 0.2) + bias(style.layoutBias, "huge");
                case TYPE -> 0.45 + bias(style.layoutBias, "type");
                case VERTICAL -> jp ? 1.5 + bias(style.layoutBias, "vcols") * 1.5 : 0;
            };
            if (all[i].ordinal() == prev) w[i] *= 0.15;
        }
        return pick(all, w, r);
    }

    // ------------------------------------------------------------------ layout: where every character goes
    static final double MX = 64, MY = 56;   // the safe area is the picture minus these margins

    private Glyph glyph(Cut c, String ch, int word, int idx, double size, SplittableRandom r) {
        Glyph g = new Glyph();
        g.ch = ch; g.word = word; g.idx = idx; g.size = size;
        g.adv = adv(ch, size, style.serif, c.mono);
        g.r1 = r.nextDouble(); g.r2 = r.nextDouble(); g.r3 = r.nextDouble();
        return g;
    }

    /** Lays word `w` out left to right starting at x; returns the x after it. y is the row's centre. */
    private double addWord(Cut c, int w, double x, double y, double size, SplittableRandom r, int[] counter) {
        String t = c.words.get(w);
        int i = 0;
        for (int off = 0; off < t.length(); ) {
            int cp = t.codePointAt(off);
            String ch = new String(Character.toChars(cp));
            Glyph g = glyph(c, ch, w, i++, size, r);
            g.x = x + g.adv / 2;
            g.y = y;
            g.order = counter[0]++;
            c.glyphs.add(g);
            x += g.adv + size * 0.02;
            off += Character.charCount(cp);
        }
        return x;
    }

    private void layout(Cut c, SplittableRandom r) {
        int[] counter = {0};
        int n = c.words.size();
        boolean serif = style.serif;
        switch (c.layout) {
            case CENTER, ORBIT -> {
                double scale = c.layout == Layout.ORBIT ? 0.8 : 1;
                int size = 96;
                List<List<Integer>> rows = null;
                for (; size >= 18; size -= 4) {
                    rows = wrap(c, size, W * 0.84 * scale);
                    if (rows.size() <= 3 && rows.size() * size * 1.15 <= H * 0.6 * scale) break;
                }
                size = (int) Math.max(18, Math.min(size, 96 * scale));
                rows = wrap(c, size, W * 0.84 * scale);
                double rowH = size * 1.2, y0 = H / 2.0 - (rows.size() - 1) * rowH / 2;
                for (int ri = 0; ri < rows.size(); ri++) {
                    double rw = 0;
                    for (int w : rows.get(ri)) rw += width(c.words.get(w), size, serif, false) + size * 0.3;
                    rw -= size * 0.3;
                    double x = W / 2.0 - rw / 2;
                    for (int w : rows.get(ri)) x = addWord(c, w, x, y0 + ri * rowH, size, r, counter) + size * 0.28;
                }
                if (c.layout == Layout.ORBIT) c.ringText = c.line.text().toUpperCase(Locale.ROOT) + "  ·  ";
            }
            case STACK -> {
                double[] rel = {1.0, 0.62, 0.86, 0.7};
                double base = 110;
                for (; base > 16; base -= 4) {
                    double h = 0, mw = 0;
                    for (int w = 0; w < n; w++) {
                        double s = base * rel[w % 4];
                        h += s * 1.02;
                        mw = Math.max(mw, width(c.words.get(w), s, serif, false));
                    }
                    if (h <= H * 0.74 && mw <= W * 0.78) break;
                }
                double total = 0;
                for (int w = 0; w < n; w++) total += base * rel[w % 4] * 1.02;
                double y = H / 2.0 - total / 2;
                int mode = (int) (c.seed & 3);
                for (int w = 0; w < n; w++) {
                    double s = base * rel[w % 4];
                    double ww = width(c.words.get(w), s, serif, false);
                    double x = mode == 0 ? W * 0.16 + (w % 2) * 46 : mode == 1 ? W * 0.84 - ww - (w % 2) * 46 : (W - ww) / 2 + ((w % 2) * 2 - 1) * 38;
                    addWord(c, w, x, y + s * 0.51, s, r, counter);
                    y += s * 1.02;
                }
            }
            case ZIGZAG -> {
                double size = 46;
                String all = String.join(" ", c.words);
                double nat = width(all, size, serif, false);
                double target = Math.min(W * 0.84, Math.max(nat, nat * 1.35));
                if (nat > W * 0.84) { size = size * W * 0.84 / nat; nat = W * 0.84; target = nat; }
                double extra = (target - nat) / Math.max(1, all.length());
                double x = W / 2.0 - target / 2;
                int gi = 0;
                for (int w = 0; w < n; w++) {
                    String t = c.words.get(w);
                    for (int off = 0, i = 0; off < t.length(); i++) {
                        int cp = t.codePointAt(off);
                        Glyph g = glyph(c, new String(Character.toChars(cp)), w, i, size, r);
                        g.x = x + g.adv / 2;
                        g.y = H / 2.0 + ((gi & 1) == 0 ? -1 : 1) * size * 0.6;
                        g.rot = ((gi & 1) == 0 ? -1 : 1) * 0.1;
                        g.order = counter[0]++;
                        c.glyphs.add(g);
                        x += g.adv + size * 0.02 + extra;
                        gi++;
                        off += Character.charCount(cp);
                    }
                    x += adv(" ", size, serif, false) + extra;
                    gi++;
                }
            }
            case ARC -> {
                double size = 44, R = 300;
                String all = String.join(" ", c.words);
                double len = width(all, size, serif, false);
                if (len / R > 2.4) { size = size * 2.4 * R / len; len = 2.4 * R; }
                double s = -len / 2;
                double cx = W / 2.0, cy = H * 0.3 + R;
                for (int w = 0; w < n; w++) {
                    String t = c.words.get(w);
                    for (int off = 0, i = 0; off < t.length(); i++) {
                        int cp = t.codePointAt(off);
                        Glyph g = glyph(c, new String(Character.toChars(cp)), w, i, size, r);
                        double phi = (s + g.adv / 2) / R;
                        g.x = cx + R * Math.sin(phi);
                        g.y = cy - R * Math.cos(phi);
                        g.rot = phi;
                        g.order = counter[0]++;
                        c.glyphs.add(g);
                        s += g.adv + size * 0.02;
                        off += Character.charCount(cp);
                    }
                    s += adv(" ", size, serif, false);
                }
            }
            case SCATTER -> {
                int cells = 6;
                int[] order = new int[cells];
                for (int i = 0; i < cells; i++) order[i] = i;
                for (int i = cells - 1; i > 0; i--) { int j = r.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
                for (int w = 0; w < n; w++) {
                    int cell = order[w % cells];
                    double cw = W / 2.0, ch = H / 3.0;
                    double size = 34 + r.nextDouble() * 40;
                    double ww = width(c.words.get(w), size, serif, false);
                    if (ww > cw * 0.92) size *= cw * 0.92 / ww;
                    ww = width(c.words.get(w), size, serif, false);
                    double ccx = (cell % 2) * cw + cw / 2 + (r.nextDouble() - 0.5) * 30;
                    double ccy = (cell / 2) * ch + ch / 2 + (r.nextDouble() - 0.5) * 20;
                    int first = c.glyphs.size();
                    addWord(c, w, ccx - ww / 2, ccy, size, r, counter);
                    double rot = (r.nextDouble() - 0.5) * 0.36;
                    for (int k = first; k < c.glyphs.size(); k++) {
                        Glyph g = c.glyphs.get(k);
                        double dx = g.x - ccx, dy = g.y - ccy;
                        g.x = ccx + dx * Math.cos(rot) - dy * Math.sin(rot);
                        g.y = ccy + dx * Math.sin(rot) + dy * Math.cos(rot);
                        g.rot = rot;
                    }
                }
            }
            case BIGSMALL -> {
                int big = 0;
                for (int w = 0; w < n; w++) if (c.words.get(w).length() > c.words.get(big).length()) big = w;
                double size = 170;
                double bw = width(c.words.get(big), size, serif, false);
                if (bw > W * 0.82) size *= W * 0.82 / bw;
                bw = width(c.words.get(big), size, serif, false);
                double by = H / 2.0;
                double small = 26;
                List<Integer> bi = new ArrayList<>(), ai = new ArrayList<>();
                for (int w = 0; w < n; w++) if (w < big) bi.add(w); else if (w > big) ai.add(w);
                addWord(c, big, W / 2.0 - bw / 2, by, size, r, counter);
                for (int pass = 0; pass < 2; pass++) {
                    List<Integer> ws = pass == 0 ? bi : ai;
                    if (ws.isEmpty()) continue;
                    double rw = 0;
                    for (int w : ws) rw += width(c.words.get(w), small, serif, false) + small * 0.4;
                    double x = W / 2.0 - rw / 2, y = pass == 0 ? by - size * 0.62 - 12 : by + size * 0.62 + 12;
                    for (int w : ws) x = addWord(c, w, x, y, small, r, counter) + small * 0.4;
                }
            }
            case TYPE -> {
                int size = 38;
                List<List<Integer>> rows = null;
                for (; size >= 16; size -= 2) {
                    rows = wrap(c, size, W * 0.8);
                    if (rows.size() * size * 1.5 <= H * 0.6) break;
                }
                double rowH = size * 1.5, y0 = H / 2.0 - (rows.size() - 1) * rowH / 2;
                for (int ri = 0; ri < rows.size(); ri++) {
                    double x = W * 0.1;
                    for (int w : rows.get(ri)) x = addWord(c, w, x, y0 + ri * rowH, size, r, counter) + size * 0.6;
                }
            }
            case VERTICAL -> {
                int total = 0;
                for (String t : c.words) total += t.codePointCount(0, t.length());
                int size = 64, perCol = 1, cols = 1;
                for (; size >= 20; size -= 4) {
                    perCol = (int) Math.floor((H * 0.78) / (size * 1.06));
                    cols = (total + perCol - 1) / Math.max(1, perCol);
                    if (cols * size * 1.35 <= W * 0.8) break;
                }
                perCol = Math.max(1, perCol);
                int k = 0;
                double x0 = W / 2.0 + (Math.max(1, cols) - 1) * size * 1.35 / 2;
                for (int w = 0; w < n; w++) {
                    String t = c.words.get(w);
                    int i = 0;
                    for (int off = 0; off < t.length(); ) {
                        int cp = t.codePointAt(off);
                        Glyph g = glyph(c, new String(Character.toChars(cp)), w, i++, size, r);
                        int col = k / perCol, row = k % perCol;
                        int inCol = Math.min(perCol, total - col * perCol);
                        g.x = x0 - col * size * 1.35;
                        g.y = H / 2.0 - (inCol - 1) * size * 1.06 / 2 + row * size * 1.06;
                        g.order = counter[0]++;
                        c.glyphs.add(g);
                        k++;
                        off += Character.charCount(cp);
                    }
                }
            }
        }
        fit(c);
        // when each character appears: with its word, a little ahead of the singing
        for (Glyph g : c.glyphs) {
            double wt = c.wordT.get(g.word);
            g.reveal = c.layout == Layout.TYPE ? wt - 0.04 + g.idx * 0.045 : Math.max(c.t0 - 0.08, wt - 0.1) + g.idx * 0.016;
        }
    }

    private List<List<Integer>> wrap(Cut c, double size, double maxW) {
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> row = new ArrayList<>();
        double rw = 0;
        for (int w = 0; w < c.words.size(); w++) {
            double ww = width(c.words.get(w), size, style.serif, c.mono) + size * 0.28;
            if (!row.isEmpty() && rw + ww > maxW) { rows.add(row); row = new ArrayList<>(); rw = 0; }
            row.add(w);
            rw += ww;
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    /** Whatever the layout did, the lyrics are made to fit inside the picture's safe area. */
    private void fit(Cut c) {
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (Glyph g : c.glyphs) {
            double h = (Math.abs(g.rot) > 0.01 ? Math.max(g.adv, g.size * 0.9) : g.adv) * 0.5, v = g.size * 0.5;
            x0 = Math.min(x0, g.x - h); x1 = Math.max(x1, g.x + h);
            y0 = Math.min(y0, g.y - v); y1 = Math.max(y1, g.y + v);
        }
        double k = Math.min(1, Math.min((W - 2 * MX) / Math.max(1, x1 - x0), (H - 2 * MY) / Math.max(1, y1 - y0)));
        double cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        if (k < 1) {
            for (Glyph g : c.glyphs) { g.x = cx + (g.x - cx) * k; g.y = cy + (g.y - cy) * k; g.size *= k; g.adv *= k; }
            x0 = cx + (x0 - cx) * k; x1 = cx + (x1 - cx) * k; y0 = cy + (y0 - cy) * k; y1 = cy + (y1 - cy) * k;
        }
        double dx = Math.max(MX - x0, 0) + Math.min((W - MX) - x1, 0), dy = Math.max(MY - y0, 0) + Math.min((H - MY) - y1, 0);
        if (dx != 0 || dy != 0) for (Glyph g : c.glyphs) { g.x += dx; g.y += dy; }
        c.box = new double[]{x0 + dx, y0 + dy, x1 + dx, y1 + dy};
    }

    // ------------------------------------------------------------------ motion
    private static double clamp(double v) { return v < 0 ? 0 : Math.min(1, v); }
    private static double easeOut(double p) { return 1 - Math.pow(1 - p, 3); }
    private static double easeBack(double p) { double c1 = 1.9, c3 = c1 + 1; return 1 + c3 * Math.pow(p - 1, 3) + c1 * Math.pow(p - 1, 2); }
    private static double bounce(double p) {
        if (p < 1 / 2.75) return 7.5625 * p * p;
        if (p < 2 / 2.75) { p -= 1.5 / 2.75; return 7.5625 * p * p + 0.75; }
        if (p < 2.5 / 2.75) { p -= 2.25 / 2.75; return 7.5625 * p * p + 0.9375; }
        p -= 2.625 / 2.75; return 7.5625 * p * p + 0.984375;
    }

    static final class State {
        double dx, dy, rot, sx = 1, sy = 1, alpha = 1, wipe = 1, bandDx;
        int bands;
        String ch;
        double ghost;   // extra ghost spread
        boolean cursor;
    }

    static final double ENTER_DUR = 0.34, EXIT_DUR = 0.3;

    private State state(Cut c, Glyph g, double ts, long step) {
        State s = new State();
        s.ch = g.ch;
        double p = clamp((ts - g.reveal) / ENTER_DUR);
        if (ts < g.reveal) { s.alpha = 0; return s; }
        double e = easeOut(p);
        double inv = 1 - e;
        switch (c.enter) {
            case POP -> { double k = easeBack(p); s.sx = s.sy = Math.max(0.01, k); s.alpha = clamp(p * 4); }
            case RISE -> { s.dy = inv * 38; s.alpha = clamp(p * 2.2); }
            case SLICE -> { s.bands = 3; s.bandDx = inv * 70 * ((g.word & 1) == 0 ? -1 : 1); s.alpha = clamp(p * 3); s.ghost = inv * 6; }
            case STRETCH -> { s.sx = 1 + inv * 2.4; s.sy = 1 - inv * 0.6; s.alpha = clamp(p * 3); }
            case ASSEMBLE -> { s.dx = (g.r1 - 0.5) * 320 * inv; s.dy = (g.r2 - 0.5) * 200 * inv; s.rot = (g.r3 - 0.5) * 2.4 * inv; s.alpha = clamp(p * 2.5); }
            case SPIN -> { s.rot = inv * Math.PI * 1.5; s.sx = s.sy = Math.max(0.01, e); s.alpha = clamp(p * 3); }
            case WIPE -> { s.wipe = p; s.dx = -inv * 14; }
            case SCRAMBLE -> {
                if (p < 1) s.ch = scramble(g, step);
                s.alpha = clamp(p * 6);
                s.ghost = inv * 4;
            }
            case DROP -> { s.dy = -(1 - bounce(p)) * 120; s.alpha = clamp(p * 5); }
            case TYPE -> { s.alpha = 1; s.cursor = p < 1; }
        }
        // exit
        double q = clamp((ts - (c.t1 - EXIT_DUR) - g.order * 0.01) / EXIT_DUR);
        if (q > 0) {
            double a = 1;
            switch (c.exit) {
                case FADE -> a = 1 - q;
                case DRIFT -> { s.dy -= q * 30; a = 1 - q; }
                case FALL -> { s.dy += q * q * 170; s.rot += (g.r1 - 0.5) * q * 1.3; a = 1 - q * q * q; }
                case EXPLODE -> { s.dx += (g.r1 - 0.5) * q * 520; s.dy += (g.r2 - 0.5) * q * 380; s.rot += (g.r3 - 0.5) * q * 4; a = 1 - q * q; }
                case SLICE -> { s.bands = 3; s.bandDx = q * 230 * ((g.word & 1) == 0 ? 1 : -1); a = 1 - q * q; }
                case SHRINK -> { double k = 1 - q; s.sx *= k; s.sy *= k; a = 1 - q * q; }
                case GLITCH -> {
                    s.dx += (hash(step, g.order) - 0.5) * 34 * q;
                    s.ghost += 10 * q;
                    a = hash(step + 3, g.order) < 0.35 + 0.65 * (1 - q) ? 1 - q * 0.6 : 0;
                }
                case SCATTER -> { s.dx += (g.r1 - 0.5) * q * 640; s.dy += (g.r2 - 0.5) * q * 60; a = 1 - q; }
            }
            s.alpha *= a;
        }
        // hold: a slow breath
        if (p >= 1 && q <= 0) {
            s.dy += Math.sin(ts * 1.7 + g.order * 0.6) * 1.4;
            s.ghost += 0.5 + hash(step / 3, g.order) * 1.5;
        }
        return s;
    }

    private String scramble(Glyph g, long step) {
        String pool = cjk(g.ch) ? "あいうえおかきくけこさしすせそ" : "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&";
        int i = (int) (hash(step, g.order) * pool.length());
        return pool.substring(i, i + 1);
    }

    static double hash(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    // ------------------------------------------------------------------ drawing
    /** Draws the picture at song time t into two transparent images of W×H: back (decorations) and front (lyrics). */
    void draw(double t, BufferedImage back, BufferedImage front) {
        long step = (long) Math.floor(t / STEP + 1e-6);
        double ts = step * STEP;
        java.awt.Graphics2D gb = init(back), gf = init(front);
        try {
            for (Cut c : cuts) {
                if (ts < c.t0 - 0.12 || ts > c.t1 + 0.06) continue;
                decor(gb, c, ts, step);
                text(gf, c, ts, step);
            }
            hud(gb, ts, step);
            if (extra) tears(front, step);
            texture(front, step);
        } finally {
            gb.dispose();
            gf.dispose();
        }
    }

    private static java.awt.Graphics2D init(BufferedImage img) {
        java.awt.Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static Color a(Color c, double alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * clamp(alpha))); }

    private void text(java.awt.Graphics2D g, Cut c, double ts, long step) {
        Scheme sc = c.sc;
        double glow = style.ghost;
        double lastX = 0, lastY = 0;
        boolean cursor = false;
        for (Glyph gl : c.glyphs) {
            State s = state(c, gl, ts, step);
            if (c.layout == Layout.TYPE && s.alpha > 0.01) { lastX = gl.x + gl.adv / 2 + gl.size * 0.08; lastY = gl.y; cursor = true; }
            if (s.alpha <= 0.01 || gl.ch.isBlank()) continue;
            Shaped sh = shaped(s.ch, gl.size, style.serif, c.mono);
            Color fg = c.accentIdx == gl.word ? sc.accent() : sc.fg();
            if (c.accentIdx == gl.word && lowContrast(sc.accent(), sc.bg())) fg = sc.fg();
            paint(g, sh, gl, s, fg, sc, glow);
        }
        if (c.layout == Layout.TYPE && cursor && step % 6 < 4) {
            double size = c.glyphs.get(0).size;
            g.setColor(a(sc.accent(), 0.95));
            g.fill(new Rectangle((int) lastX, (int) (lastY - size * 0.5), (int) Math.max(3, size * 0.45), (int) (size * 0.95)));
        }
    }

    private static boolean lowContrast(Color x, Color y) {
        double l1 = lum(x), l2 = lum(y);
        return Math.abs(l1 - l2) < 0.12;
    }

    private static double lum(Color c) { return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0; }

    private void paint(java.awt.Graphics2D g, Shaped sh, Glyph gl, State s, Color fg, Scheme sc, double ghostK) {
        AffineTransform at = new AffineTransform();
        at.translate(gl.x + s.dx, gl.y + s.dy);
        at.rotate(gl.rot + s.rot);
        at.scale(s.sx, s.sy);
        at.translate(-sh.adv / 2.0, gl.size * 0.36);
        Shape shape = at.createTransformedShape(sh.shape);
        double al = clamp(s.alpha);
        int bands = Math.max(1, s.bands);
        double bandH = gl.size * 1.3 / bands;
        for (int b = 0; b < bands; b++) {
            java.awt.Graphics2D gg = (java.awt.Graphics2D) g.create();
            Shape use = shape;
            if (bands > 1) {
                gg.clip(new Rectangle((int) (gl.x + s.dx - gl.size * 3), (int) (gl.y + s.dy - gl.size * 0.65 + b * bandH), (int) (gl.size * 6), (int) Math.ceil(bandH)));
                double off = ((b & 1) == 0 ? 1 : -1) * s.bandDx;
                use = AffineTransform.getTranslateInstance(off, 0).createTransformedShape(shape);
            } else if (s.wipe < 1) {
                gg.clip(new Rectangle((int) (gl.x + s.dx - gl.adv / 2 - 2), (int) (gl.y - gl.size), (int) Math.ceil((gl.adv + 4) * s.wipe), (int) (gl.size * 2)));
            }
            double gx = ghostK * (1.2 + s.ghost);
            if (gx > 0.3) {
                gg.setColor(a(sc.ghostA(), 0.6 * al));
                gg.fill(AffineTransform.getTranslateInstance(-gx, 0).createTransformedShape(use));
                gg.setColor(a(sc.ghostB(), 0.6 * al));
                gg.fill(AffineTransform.getTranslateInstance(gx, gx * 0.15).createTransformedShape(use));
            }
            gg.setStroke(new BasicStroke((float) Math.max(3, gl.size * 0.13), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            gg.setColor(a(sc.bg(), 0.85 * al));
            gg.draw(use);
            gg.setColor(a(fg, al));
            gg.fill(use);
            gg.dispose();
        }
    }

    // ------------------------------------------------------------------ decorations (back layer)
    private double weight(String k) { return style.decor.getOrDefault(k, 0.0); }

    private boolean has(Cut c, String k, double bonus) {
        double p = Math.min(0.95, weight(k) * 0.6 + bonus);
        return hash(c.seed, k.hashCode()) < p;
    }

    private void decor(java.awt.Graphics2D g, Cut c, double ts, long step) {
        Scheme sc = c.sc;
        double life = clamp((ts - c.t0 + 0.1) / 0.5);
        double fade = 1 - clamp((ts - (c.t1 - 0.25)) / 0.3);
        double al = easeOut(life) * fade;
        if (al <= 0.01) return;
        double cx = (c.box[0] + c.box[2]) / 2, cy = (c.box[1] + c.box[3]) / 2;
        double bw = c.box[2] - c.box[0], bh = c.box[3] - c.box[1];
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (c.layout == Layout.ZIGZAG) {
            Path2D.Double path = new Path2D.Double();
            boolean first = true;
            for (Glyph gl : c.glyphs) {
                double px = gl.x, py = gl.y + ((gl.order & 1) == 0 ? gl.size * 0.62 : -gl.size * 0.62);
                if (first) { path.moveTo(px, py); first = false; } else path.lineTo(px, py);
            }
            g.setColor(a(sc.accent(), 0.8 * al));
            g.draw(path);
        }
        if (c.layout == Layout.ARC) {
            double R = 300 + c.glyphs.get(0).size * 0.7;
            g.setColor(a(sc.sub(), 0.7 * al));
            g.draw(new Arc2D.Double(W / 2.0 - R, H * 0.3 + 300 - R, 2 * R, 2 * R, 90 - 70, 140, Arc2D.OPEN));
        }
        if (c.layout == Layout.ORBIT && !c.ringText.isEmpty()) {
            double rx = Math.max(bw / 2 + 60, 230), ry = Math.max(bh / 2 + 50, 140);
            double ang0 = ts * 0.35 + c.seed % 6;
            double per = 12.5;
            int count = (int) Math.min(120, Math.PI * (rx + ry) / per);
            for (int i = 0; i < count; i++) {
                double th = ang0 + i * Math.PI * 2 / count;
                String ch = c.ringText.substring(i % c.ringText.length(), i % c.ringText.length() + 1);
                if (ch.isBlank()) continue;
                Shaped sh = shaped(ch, 11, false, true);
                AffineTransform at = new AffineTransform();
                at.translate(cx + rx * Math.cos(th), cy + ry * Math.sin(th));
                at.rotate(Math.atan2(ry * Math.cos(th), -rx * Math.sin(th)));
                at.translate(-sh.adv / 2.0, 4);
                g.setColor(a(sc.sub(), 0.75 * al));
                g.fill(at.createTransformedShape(sh.shape));
            }
        }
        if (has(c, "rings", 0.12) && c.layout != Layout.ORBIT) {
            double r0 = Math.max(70, Math.min(150, Math.hypot(bw, bh) * 0.3 + 14)) + 8 * Math.sin(ts * 1.3);
            g.setColor(a(sc.accent(), 0.55 * al));
            g.draw(new Ellipse2D.Double(cx - r0, cy - r0, 2 * r0, 2 * r0));
            g.setColor(a(sc.accent2(), 0.7 * al));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{14f, 9f}, (float) (ts * 30)));
            double r1 = r0 + 16;
            g.draw(new Ellipse2D.Double(cx - r1, cy - r1, 2 * r1, 2 * r1));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }
        if (has(c, "slash", 0.1)) {
            double k = easeOut(clamp((ts - c.t0) / 0.4));
            g.setColor(a(sc.accent(), 0.8 * al));
            double len = Math.min(bw + 80, 360) * k;
            g.draw(new Line2D.Double(cx - bw / 2 - 40, cy + bh / 2 + 14, cx - bw / 2 - 40 + len, cy + bh / 2 + 14 - len * 0.12));
            g.setColor(a(sc.accent2(), 0.6 * al));
            g.draw(new Line2D.Double(cx - bw / 2 - 20, cy + bh / 2 + 22, cx - bw / 2 - 20 + len * 0.7, cy + bh / 2 + 22 - len * 0.08));
        }
        if (has(c, "arrows", 0.05)) {
            g.setColor(a(sc.accent2(), 0.85 * al));
            double ax = c.box[0] - 34, ay = cy;
            for (int i = 0; i < 3; i++) {
                double o = i * 9 + (step % 3 == i ? 3 : 0);
                Path2D.Double ch = new Path2D.Double();
                ch.moveTo(ax + o, ay - 8); ch.lineTo(ax + o + 7, ay); ch.lineTo(ax + o, ay + 8);
                g.draw(ch);
            }
        }
        if (has(c, "barcode", 0.0)) {
            double bx = W - 130, by = H - 54;
            for (int i = 0; i < 22; i++) {
                double w = 1 + hash(c.seed, i) * 3;
                g.setColor(a(sc.sub(), 0.7 * al));
                g.fill(new Rectangle((int) bx, (int) by, (int) Math.ceil(w), 14));
                bx += w + 1.6;
            }
        }
        if (has(c, "counter", 0.0)) {
            double size = 52;
            String n = String.format(Locale.ROOT, "%02d", c.index + 1);
            double x = 22;
            for (int i = 0; i < n.length(); i++) {
                Shaped sh = shaped(n.substring(i, i + 1), size, false, false);
                g.setColor(a(sc.sub(), 0.55 * al));
                g.fill(AffineTransform.getTranslateInstance(x, H - 40).createTransformedShape(sh.shape));
                x += sh.adv;
            }
        }
        if (motifs) {
            int corner = (int) (hash(c.seed, 99) * 4);
            double sx = (corner & 1) == 0 ? 56 : W - 86, sy = (corner & 2) == 0 ? 80 : H - 120;
            if (!(Math.abs(sx - cx) < bw / 2 + 40 && Math.abs(sy - cy) < bh / 2 + 40)) {   // a seal never sits on the lyrics
                AffineTransform keep = g.getTransform();
                g.rotate(0.07, sx + 15, sy + 15);
                g.setColor(a(new Color(0xD7263D), 0.92 * al));
                g.fill(new RoundRectangle2D.Double(sx, sy, 30, 30, 5, 5));
                Shaped sh = shaped("歌", 22, true, false);
                g.setColor(a(Color.WHITE, 0.95 * al));
                g.fill(AffineTransform.getTranslateInstance(sx + 15 - sh.adv / 2.0, sy + 15 + 8).createTransformedShape(sh.shape));
                g.setTransform(keep);
            }
            if (hash(c.seed, 98) < 0.4) {   // a brush circle behind the words
                double r0 = Math.max(bw, bh) * 0.5 + 40;
                g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(a(sc.accent(), 0.35 * al));
                g.draw(new Arc2D.Double(cx - r0, cy - r0, 2 * r0, 2 * r0, 20 + ts * 8, 300, Arc2D.OPEN));
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            }
        }
    }

    private void hud(java.awt.Graphics2D g, double ts, long step) {
        if (cuts.isEmpty() || ts < -0.5 || ts > duration + 2) return;
        Scheme sc = style.schemes.get(0);
        boolean full = style.hud || weight("hud") >= 0.6;
        double al = full ? 0.8 : weight("hud") > 0 ? 0.5 : 0.35;
        double m = 16, len = 22;
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(a(sc.fg(), al));
        for (int i = 0; i < 4; i++) {
            double x = (i & 1) == 0 ? m : W - m, y = (i & 2) == 0 ? m : H - m;
            double sx = (i & 1) == 0 ? 1 : -1, sy = (i & 2) == 0 ? 1 : -1;
            g.draw(new Line2D.Double(x, y, x + sx * len, y));
            g.draw(new Line2D.Double(x, y, x, y + sy * len));
        }
        if (!full && weight("hud") <= 0) return;
        int cur = 0;
        for (Cut c : cuts) if (c.t0 <= ts) cur = c.index;
        label(g, title.isBlank() ? "UNTITLED" : title.toUpperCase(Locale.ROOT), m + 10, m + 12, sc.fg(), al, false);
        boolean rec = step % 12 < 8;
        label(g, (rec ? "● " : "  ") + "REC", W - m - 44, m + 12, sc.accent(), al, false);
        int cs = (int) Math.max(0, ts * 100);
        label(g, String.format(Locale.ROOT, "%02d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100), m + 10, H - m - 6, sc.fg(), al, false);
        label(g, String.format(Locale.ROOT, "LYRIC %02d/%02d", cur + 1, cuts.size()), W - m - 92, H - m - 6, sc.fg(), al, false);
        double prog = clamp(ts / Math.max(1, duration));
        g.setColor(a(sc.sub(), al * 0.6));
        g.draw(new Line2D.Double(m + 10, H - m - 18, W - m - 10, H - m - 18));
        g.setColor(a(sc.accent(), al));
        g.draw(new Line2D.Double(m + 10, H - m - 18, m + 10 + (W - 2 * m - 20) * prog, H - m - 18));
        if (motifs) {
            String tag = "リリック";
            for (int i = 0; i < tag.length(); i++) {
                Shaped sh = shaped(tag.substring(i, i + 1), 12, false, false);
                g.setColor(a(sc.sub(), al));
                g.fill(AffineTransform.getTranslateInstance(W - 22 - sh.adv / 2.0, 120 + i * 15).createTransformedShape(sh.shape));
            }
        }
    }

    private void label(java.awt.Graphics2D g, String text, double x, double y, Color color, double al, boolean serif) {
        double size = 11;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            Shaped sh = shaped(ch, size, serif, true);
            g.setColor(a(color, al));
            g.fill(AffineTransform.getTranslateInstance(x, y).createTransformedShape(sh.shape));
            x += sh.adv;
        }
    }

    // ------------------------------------------------------------------ finishing touches on the lyrics layer
    /** "New effects": a few strips of the picture slip sideways for a moment. */
    private void tears(BufferedImage img, long step) {
        if (hash(step, 5) > 0.22) return;
        java.awt.Graphics2D g = img.createGraphics();
        int n = 1 + (int) (hash(step, 6) * 3);
        for (int i = 0; i < n; i++) {
            int y = (int) (hash(step, 10 + i) * (H - 30)), h = 4 + (int) (hash(step, 20 + i) * 16);
            int dx = (int) ((hash(step, 30 + i) - 0.5) * 60);
            g.copyArea(0, y, W, h, dx, 0);
        }
        g.dispose();
    }

    /** Film grain and scan lines, drawn only where there are lyrics, so the world around them stays clean. */
    private void texture(BufferedImage img, long step) {
        if (style.grain <= 0 && style.scan <= 0) return;
        if (noise == null) {
            noise = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            SplittableRandom r = new SplittableRandom(12345);
            for (int y = 0; y < 256; y++)
                for (int x = 0; x < 256; x++) {
                    int v = r.nextInt(256), al = r.nextInt(70);
                    noise.setRGB(x, y, (al << 24) | (v << 16) | (v << 8) | v);
                }
        }
        java.awt.Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, (float) Math.min(1, style.grain * 0.5)));
        int ox = (int) (hash(step, 1) * 256), oy = (int) (hash(step, 2) * 256);
        for (int y = -oy; y < H; y += 256) for (int x = -ox; x < W; x += 256) g.drawImage(noise, x, y, null);
        if (style.scan > 0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, (float) Math.min(0.6, style.scan * 0.5)));
            g.setColor(Color.BLACK);
            for (int y = (int) (step % 3); y < H; y += 3) g.fillRect(0, y, W, 1);
        }
        g.dispose();
    }
}
