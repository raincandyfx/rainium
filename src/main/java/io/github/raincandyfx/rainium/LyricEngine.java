package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.GL;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.Params;
import io.github.raincandyfx.rainium.LyricKit.Rng;
import io.github.raincandyfx.rainium.LyricKit.State;

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

import static io.github.raincandyfx.rainium.LyricKit.clamp;

/**
 * A native (plain Java2D) lyric video as a kinetic-type lyric video (see Credits): kinetic typography with its colour schemes, chromatic ghosting, HUD frame and
 * Japanese motifs. The layouts (LyricLayouts) are ported from the reference source; the way each
 * character comes in and goes out is this class's own. The style data (palettes, which layouts and
 * motions each style prefers) is taken from the reference style table.
 *
 * Two transparent layers are drawn: BACK (decorations) and FRONT (the lyrics). Nothing is ever drawn
 * over the whole picture, so the world stays visible around the lyrics.
 *
 * Pure (no game classes), so the tests can render frames headlessly.
 */
final class LyricEngine {
    static final int W = 800, H = 450;
    static final double SCALE = W / Env.W;      // layouts are drawn in a 1920x1080 space
    static final double STEP = 1.0 / 12;        // The picture moves in steps, about twelve a second
    /** Room around the picture where effects may overshoot instead of being cut at its edge: the texture is this much bigger on every side. */
    static final int PX = 200, PY = 112, CW = W + 2 * PX, CH = H + 2 * PY;

    /** The look's own switches, from the lyrics screen. */
    record Look(java.util.Set<String> off, java.util.Set<String> offIn, java.util.Set<String> offOut, boolean decor, boolean frame, boolean hud,
                double ghost, double grain, double fps, double speed, double backdrop, double halo, int font, String text, String accent) {
        static final Look DEFAULT = new Look(java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), true, false, false, 100, 100, 12, 100, 0, 100, -1, "", "");
        /** Just the layouts switched off and the usual rest (for tests). */
        static Look off(java.util.Set<String> layouts) { return new Look(layouts, java.util.Set.of(), java.util.Set.of(), true, false, false, 100, 100, 12, 100, 0, 100, -1, "", ""); }
        String key() {
            return new java.util.TreeSet<>(off) + "|" + new java.util.TreeSet<>(offIn) + "|" + new java.util.TreeSet<>(offOut) + "|" + decor + "|" + frame + "|" + hud + "|" + ghost + "|" + grain + "|" + fps
                + "|" + speed + "|" + backdrop + "|" + halo + "|" + font + "|" + text + "|" + accent;
        }
        /** A colour typed as #rrggbb (or rrggbb), or null when blank or not a colour. */
        static Color color(String s) {
            if (s == null) return null;
            String t = s.trim();
            if (t.startsWith("#")) t = t.substring(1);
            if (!t.matches("[0-9a-fA-F]{6}")) return null;
            return new Color(Integer.parseInt(t, 16));
        }
    }

    // ------------------------------------------------------------------ styles
    record Scheme(Color bg, Color fg, Color sub, Color accent, Color accent2, Color ink, Color dim, Color ghostA, Color ghostB) {}

    static final class Style {
        String id, name;
        final List<Scheme> schemes = new ArrayList<>();
        double grain, scan, ghost = 1;
        final Map<String, Double> layoutBias = new HashMap<>(), enterBias = new HashMap<>(), exitBias = new HashMap<>(), decor = new HashMap<>();
        final Map<String, List<String>> fonts = new HashMap<>();
        boolean hud, horror;
    }

    private static Map<String, Style> styles;

    /** Every style in the table, in order. */
    static synchronized String[] styleIds() {
        if (styles == null) styles = loadStyles();
        return styles.keySet().toArray(new String[0]);
    }

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
        try (InputStream in = LyricEngine.class.getResourceAsStream("/assets/rainium/lyricvideo/styles.json")) {
            if (in != null) {
                Map<String, Object> root = (Map<String, Object>) Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (root.get("_fonts") instanceof Map<?, ?> fm)
                    for (var f : ((Map<String, Object>) fm).entrySet()) {
                        Map<String, Object> d = (Map<String, Object>) f.getValue();
                        LyricKit.defineFace(f.getKey(), String.valueOf(d.get("kind")), num(d.get("weight")));
                    }
                for (var e : root.entrySet()) {
                    if (e.getKey().startsWith("_")) continue;
                    Map<String, Object> m = (Map<String, Object>) e.getValue();
                    Style s = new Style();
                    s.id = e.getKey();
                    s.name = String.valueOf(m.get("name"));
                    for (Object so : (List<Object>) m.get("schemes")) {
                        Map<String, Object> c = (Map<String, Object>) so;
                        s.schemes.add(new Scheme(col(c.get("bg")), col(c.get("fg")), col(c.get("sub")), col(c.get("accent")), col(c.get("accent2")),
                            c.get("ink") == null ? col(c.get("fg")) : col(c.get("ink")), col(c.get("dim")), col(c.get("ghostA")), col(c.get("ghostB"))));
                    }
                    Map<String, Object> tex = (Map<String, Object>) m.get("texture");
                    if (tex != null) { s.grain = num(tex.get("grain")); s.scan = num(tex.get("scan")); }
                    s.ghost = m.get("ghost") == null ? 1 : num(m.get("ghost"));
                    Map<String, Object> bias = (Map<String, Object>) m.get("bias");
                    if (bias != null) {
                        fill(s.layoutBias, bias.get("layout"));
                        fillNorm(s.enterBias, bias.get("enter"));
                        fillNorm(s.exitBias, bias.get("exit"));
                    }
                    fill(s.decor, m.get("decor"));
                    if (m.get("fonts") instanceof Map<?, ?> fm)
                        for (var f : ((Map<String, Object>) fm).entrySet()) {
                            List<String> l = new ArrayList<>();
                            for (Object o : (List<Object>) f.getValue()) l.add(String.valueOf(o));
                            s.fonts.put(f.getKey(), l);
                        }
                    s.hud = Boolean.TRUE.equals(m.get("hud"));
                    s.horror = "horror".equals(m.get("set"));
                    out.put(s.id, s);
                }
            }
        } catch (Exception ignored) { }
        if (out.isEmpty()) {   // the data file is missing: one plain style so nothing breaks
            Style s = new Style();
            s.id = "noir"; s.name = "Noir";
            s.schemes.add(new Scheme(new Color(0x060607), new Color(0xF5EEEA), new Color(0xBDB6B2), new Color(0xF5A50C), new Color(0x16F4D4), new Color(0xF5EEEA), new Color(0x2A2A2E), new Color(0xF5A50C), new Color(0x16F4D4)));
            s.grain = 0.9; s.hud = true; s.decor.put("rings", 0.8);
            out.put("noir", s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void fill(Map<String, Double> into, Object o) {
        if (o instanceof Map<?, ?> m) for (var e : ((Map<String, Object>) m).entrySet()) into.put(e.getKey(), num(e.getValue()));
    }

    /** Way-in and way-out names are matched without case or underscores (slide_left and slideLeft are the same). */
    @SuppressWarnings("unchecked")
    private static void fillNorm(Map<String, Double> into, Object o) {
        if (o instanceof Map<?, ?> m) for (var e : ((Map<String, Object>) m).entrySet()) into.put(e.getKey().toLowerCase(Locale.ROOT).replace("_", ""), num(e.getValue()));
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }

    // ------------------------------------------------------------------ cuts
    enum Enter { POP, RISE, SLICE, STRETCH, ASSEMBLE, SPIN, WIPE, SCRAMBLE, DROP, TYPE,
        FLIP, ZOOM, SLIDE_LEFT, SLIDE_RIGHT, SWING, ELASTIC, WAVE, SPIRAL, SQUASH, BLINK, TUMBLE, SHUTTER, RUSH, HOP, FOCUS, UNFOLD, ZIP, FALL_IN, TILT, SPREAD,
        BLUR, FLICKER, RISE_MASK, DROP_MASK, FLIP_X, DOMINO, FOLD, UNROLL, STROKE_DRAW, OUTLINE_FILL, SPLIT_JOIN, V_SLICE, IRIS, DIAG_WIPE, BLINDS, CHECKER, RANDOM_ORDER, BOUNCE_BIG, SQUASH_DROP, RUBBER, GLITCH_IN, ECHO_IN, WHIP, SKEW_IN, TRACK_IN, TRACK_OUT, BLUR_STAGGER, FADE_STAGGER, INK_BLEED, NEON_ON, CURSOR_SWEEP, STAMP, SPRING_IN, PENDULUM, ROLL_IN, SLINGSHOT, ROCK_SETTLE, BOUNCE_BALL, SNAP_RAIL, FAN_OPEN, CYLINDER, SHUFFLE, STOP_MOTION, RIPPLE, STICKER_PEEL, CRUMPLE, TORN_JOIN, OVEREXPOSE, GLINT, LOUPE, FILM_FEED, BACKLIGHT, LIGHT_LEAK, HEAT_HAZE, CRT_ON, INTERLACE, DITHER, ODOMETER, QUARTERS, PRINT_REGISTER, LIQUID_FILL, WIND_BLOWN, SHADOW_FIRST, TOKOROTEN }
    enum Exit { FADE, DRIFT, FALL, EXPLODE, SLICE, SHRINK, GLITCH, SCATTER,
        SLIDE_LEFT, SLIDE_RIGHT, RISE_UP, ZOOM_OUT, SPIN_OUT, FLIP_OUT, COLLAPSE, BLINK, MELT, SWING_OUT, WAVE_OUT, BLUR, TUMBLE, FLY, POP_OUT, ZIP, SQUEEZE, UNRAVEL,
        WIPE, STRETCH, SINK_MASK, RISE_OUT, FLIP_OUT_X, FLIP_OUT_Y, FOLD_OUT, SQUASH_OUT, TRACK_OUT_WIDE, ZOOM_THROUGH, ZOOM_FAR, TWIST, BLUR_OUT_STAGGER, UNDRAW, OUTLINE_OUT, IRIS_CLOSE, DIAG_WIPE_OUT, BLINDS_CLOSE, CHECKER_OUT, SPLIT_APART, V_SLICE_DROP, DISSOLVE, BACKSPACE, SCRAMBLE_OUT, GLITCH_DISSOLVE, ECHO_OUT, WHIP_OUT, GRAVITY, BURN, SWEEP_COVER, SHATTER_LITE, PEEL_OFF, CRUMPLE_OUT, TEAR_OUT, OVEREXPOSE_OUT, SCAN_OUT, STRIPES_OUT, HALFTONE_OUT, VACUUM_OUT, SAND_OUT, SHRED_OUT, DOMINO_OUT, HINGE_OUT, ROCKET_OFF, BOUNCE_OFF, BALLOON_OFF, DEFLATE_OUT, HAZE_OUT, CLAP_SHUT, LAMP_OFF, SLOT_OUT, CLOCK_OUT, TORNADO_OUT, ROLL_UP_OUT, FLUTTER_OUT, ROLL_OFF, SLASH_OUT, MOSAIC_OUT, SCRIBBLE_OUT, CANDLE_OUT }

    /** One shot of the video: a line (or a part of a long line) with its look decided. */
    static final class Cut {
        int line;                       // index of the lyric line
        String text, lineText, note, layout = "center";
        final List<String> words = new ArrayList<>();
        double start, end, inDur = 0.3, outDur = 0.25, stagger = 0.04;
        Scheme sc;
        Enter enter;
        Exit exit;
        long seed;
        Params params = new Params();
        double[] box = {W * 0.3, H * 0.35, W * 0.7, H * 0.65};   // where the text was last drawn, in picture pixels
    }

    final Style style;
    final long seed;
    final boolean motifs, extra;
    final String title;
    private final List<Cut> cuts = new ArrayList<>();
    private double duration;
    private BufferedImage noise;

    final Look look;
    final double stepSec;

    LyricEngine(String styleId, long seed, boolean motifs, boolean extra, String title) { this(styleId, seed, motifs, extra, title, Look.DEFAULT); }

    LyricEngine(String styleId, long seed, boolean motifs, boolean extra, String title, Look look) {
        this.look = look;
        this.stepSec = 1.0 / Math.max(0.5, look.fps());
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
    private record Unit(String text, double t) {}

    void song(LyricsLrc lrc) {
        cuts.clear();
        duration = 0;
        if (lrc == null) return;
        List<String> recent = new ArrayList<>();
        for (int i = 0; i < lrc.lines.size(); i++) {
            LyricsLrc.Line line = lrc.lines.get(i);
            List<Unit> units = units(line);
            if (units.isEmpty()) continue;
            boolean jp = cjk(line.text());
            int limit = jp ? 12 : 16;
            List<List<Unit>> groups = new ArrayList<>();
            List<Unit> cur = new ArrayList<>();
            int n = 0;
            for (Unit u : units) {
                int g = LyricKit.glyphCount(u.text);
                if (!cur.isEmpty() && n + g > limit) { groups.add(cur); cur = new ArrayList<>(); n = 0; }
                cur.add(u); n += g;
            }
            if (!cur.isEmpty()) groups.add(cur);
            for (int gi = 0; gi < groups.size(); gi++) {
                List<Unit> grp = groups.get(gi);
                Cut c = new Cut();
                c.line = i;
                c.lineText = line.text();
                c.start = grp.get(0).t - 0.05;
                c.end = gi + 1 < groups.size() ? groups.get(gi + 1).get(0).t - 0.05 : Math.max(line.end(), c.start + 0.5);
                if (c.end < c.start + 0.3) c.end = c.start + 0.3;
                double dur = c.end - c.start;
                double spd = Math.max(0.05, look.speed() / 100.0);   // animation speed: faster means shorter ways in and out
                c.inDur = Math.min(0.3 / spd, dur * 0.4);
                c.outDur = Math.min(0.25 / spd, dur * 0.3);
                c.stagger = 0.04 / spd;
                StringBuilder sb = new StringBuilder();
                for (Unit u : grp) { if (sb.length() > 0 && !jp) sb.append(' '); sb.append(u.text); c.words.add(u.text); }
                c.text = sb.toString();
                c.seed = seed * 7919L + cuts.size() * 104729L + 17;
                Rng r = new Rng(c.seed);
                c.sc = recolour(cuts.isEmpty() || r.next() < 0.7 ? style.schemes.get(0) : style.schemes.get((int) (r.next() * style.schemes.size()) % style.schemes.size()));
                c.layout = pickLayout(c, r, recent);
                recent.add(c.layout);
                LyricLayouts.Def def = LyricLayouts.ALL.get(c.layout);
                c.params = def.plan().plan(new Rng(c.seed ^ 0x5BD1E995L), c, style);
                c.enter = pick(Enter.values(), enterWeights(), r);
                c.exit = pick(Exit.values(), exitWeights(), r);
                cuts.add(c);
                duration = Math.max(duration, c.end);
            }
        }
    }

    /** The scheme with the typed text and accent colours, when there are any. */
    private Scheme recolour(Scheme sc) {
        Color t = Look.color(look.text()), a = Look.color(look.accent());
        if (t == null && a == null) return sc;
        return new Scheme(sc.bg(), t != null ? t : sc.fg(), t != null ? LyricKit.mix(t, sc.bg(), 0.35) : sc.sub(), a != null ? a : sc.accent(), a != null ? a : sc.accent2(), sc.ink(), sc.dim(), sc.ghostA(), sc.ghostB());
    }

    /** The line's words, with long unspaced runs (Japanese) cut into short pieces so they can come in one by one. */
    private List<Unit> units(LyricsLrc.Line line) {
        List<Unit> out = new ArrayList<>();
        for (LyricsLrc.Word w : line.words()) {
            String t = w.text().strip();
            if (t.isEmpty()) continue;
            int[] cps = t.codePoints().toArray();
            int chunk = cjk(t) ? 3 : 99;
            if (cps.length <= chunk + 1) { out.add(new Unit(t, w.t())); continue; }
            int n = (cps.length + chunk - 1) / chunk;
            for (int p = 0; p < n; p++) {
                int a = p * cps.length / n, b = (p + 1) * cps.length / n;
                out.add(new Unit(new String(cps, a, b - a), w.t() + (w.end() - w.t()) * p / n));
            }
        }
        return out;
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
        for (int i = 0; i < e.length; i++) w[i] = look.offIn().contains(e[i].name().toLowerCase(Locale.ROOT)) ? 0 : e[i] == Enter.TYPE ? 0 : 1 + bias(style.enterBias, e[i].name().toLowerCase(Locale.ROOT).replace("_", ""));
        return anyOr(w);
    }

    private double[] exitWeights() {
        Exit[] e = Exit.values();
        double[] w = new double[e.length];
        for (int i = 0; i < e.length; i++) w[i] = look.offOut().contains(e[i].name().toLowerCase(Locale.ROOT)) ? 0 : 1 + bias(style.exitBias, e[i].name().toLowerCase(Locale.ROOT).replace("_", ""));
        return anyOr(w);
    }

    /** If everything was switched off, nothing would ever come in or go out: use them all instead. */
    private static double[] anyOr(double[] w) {
        for (double v : w) if (v > 0) return w;
        java.util.Arrays.fill(w, 1);
        return w;
    }

    private static double bias(Map<String, Double> m, String k) { return m.getOrDefault(k, 0.0); }

    private static <T> T pick(T[] items, double[] weights, Rng r) {
        double sum = 0;
        for (double w : weights) sum += w;
        double x = r.next() * sum;
        for (int i = 0; i < items.length; i++) { x -= weights[i]; if (x <= 0) return items[i]; }
        return items[items.length - 1];
    }

    /** A layout that suits the text, leaning on the style's taste and away from what was just shown. */
    private String pickLayout(Cut c, Rng r, List<String> recent) {
        int n = LyricKit.glyphCount(c.text);
        boolean jp = cjk(c.text);
        List<String> names = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (var e : LyricLayouts.ALL.entrySet()) {
            if (!e.getValue().fits().test(n) || look.off().contains(e.getKey())) continue;
            if (LyricWeights.horror(e.getKey()) && !style.horror) continue;   // the horror set belongs to the horror styles
            double k = style.layoutBias.getOrDefault(e.getKey(), LyricWeights.layout(e.getKey()));   // the style's own say, else the layout's usual weight
            int back = recent.lastIndexOf(e.getKey());
            if (back >= 0) {
                int ago = recent.size() - back;
                if (ago <= 2) k *= 0.2; else if (ago <= 6) k *= 0.6;
            }
            if (e.getKey().equals("vcols") && !jp) k *= 0.3;
            names.add(e.getKey()); w.add(k);
        }
        if (names.isEmpty()) return "center";
        double sum = 0;
        for (double k : w) sum += k;
        double x = r.next() * sum;
        for (int i = 0; i < names.size(); i++) { x -= w.get(i); if (x <= 0) return names.get(i); }
        return names.get(names.size() - 1);
    }

    // ------------------------------------------------------------------ motion
    private static final double K = Env.W / W;   // the old motion offsets were in picture pixels

    private static double bounce(double p) { return E.bounce(clamp(p)); }

    /** How one character moves at this moment: its way in, its way out, and a slow breath in between. */
    State state(Env env, Cut c, Enter enter, Item it, GL gl, double ltI, double lt, double outStart, long seed, long step) {
        State s = new State();
        s.ch = gl.ch;
        double reveal = gl.i * (enter == Enter.TYPE ? 0.05 : enter == Enter.BLUR_STAGGER || enter == Enter.FADE_STAGGER || enter == Enter.RANDOM_ORDER ? 0.045 : 0.016);
        if (ltI < reveal) { s.alpha = 0; return s; }
        double p = clamp((ltI - reveal) / Math.max(0.05, c.inDur));
        double e = E.outCubic(p), inv = 1 - e;
        int word = (int) it.mi;
        double r1 = LyricKit.r(seed, gl.i, 1), r2 = LyricKit.r(seed, gl.i, 2), r3 = LyricKit.r(seed, gl.i, 3);
        switch (enter) {
            case POP -> { double k = E.outBack(p); s.sx = s.sy = Math.max(0.01, k); s.alpha = clamp(p * 4); }
            case RISE -> { s.dy = inv * 38 * K; s.alpha = clamp(p * 2.2); }
            case SLICE -> { s.bands = 3; s.bandDx = inv * 70 * K * ((word & 1) == 0 ? -1 : 1); s.alpha = clamp(p * 3); s.ghost = inv * 6; }
            case STRETCH -> { s.sx = 1 + inv * 2.4; s.sy = 1 - inv * 0.6; s.alpha = clamp(p * 3); }
            case ASSEMBLE -> { s.dx = (r1 - 0.5) * 320 * K * inv; s.dy = (r2 - 0.5) * 200 * K * inv; s.rot = (r3 - 0.5) * 2.4 * inv; s.alpha = clamp(p * 2.5); }
            case SPIN -> { s.rot = inv * Math.PI * 1.5; s.sx = s.sy = Math.max(0.01, e); s.alpha = clamp(p * 3); }
            case WIPE -> { s.wipe = p; s.dx = -inv * 14 * K; }
            case SCRAMBLE -> { if (p < 1) s.ch = scramble(gl, step); s.alpha = clamp(p * 6); s.ghost = inv * 4; }
            case DROP -> { s.dy = -(1 - bounce(p)) * 120 * K; s.alpha = clamp(p * 5); }
            case TYPE -> s.alpha = 1;
            case FLIP -> { s.sy = Math.max(0.02, Math.abs(Math.cos(inv * Math.PI * 1.5))); s.alpha = clamp(p * 4); }
            case ZOOM -> { s.sx = s.sy = 1 + inv * 3.2; s.alpha = clamp(p * 3); s.ghost = inv * 8; }
            case SLIDE_LEFT -> { s.dx = -inv * 260 * K; s.alpha = clamp(p * 3); }
            case SLIDE_RIGHT -> { s.dx = inv * 260 * K; s.alpha = clamp(p * 3); }
            case SWING -> { s.rot = Math.sin(p * 9) * (1 - p) * 0.9; s.dy = -inv * 20 * K; s.alpha = clamp(p * 4); }
            case ELASTIC -> { double k = 1 - Math.exp(-6 * p) * Math.cos(p * 14); s.sx = s.sy = Math.max(0.01, k); s.alpha = clamp(p * 6); }
            case WAVE -> { s.dy = Math.sin(p * 8 + gl.i * 0.7) * (1 - p) * 34 * K; s.alpha = clamp(p * 3); }
            case SPIRAL -> { double ang = inv * 6, rad = inv * 110 * K; s.dx = Math.cos(ang) * rad; s.dy = Math.sin(ang) * rad; s.rot = inv * 3; s.sx = s.sy = Math.max(0.01, e); s.alpha = clamp(p * 3); }
            case SQUASH -> { s.sx = 1 + inv * 0.9; s.sy = Math.max(0.02, 1 - inv * 0.9); s.dy = inv * 14 * K; s.alpha = clamp(p * 4); }
            case BLINK -> { s.alpha = p < 1 ? (hash(step, gl.i + 7) < p ? 1 : 0.0) : 1; }
            case TUMBLE -> { s.rot = -inv * Math.PI * 2.2 * (r1 < 0.5 ? 1 : -1); s.dy = -inv * 90 * K; s.dx = (r2 - 0.5) * 80 * K * inv; s.alpha = clamp(p * 4); }
            case SHUTTER -> { s.bands = 5; s.bandDx = inv * 40 * K * ((word & 1) == 0 ? 1 : -1); s.sy = 1; s.alpha = clamp(p * 5); }
            case RUSH -> { s.dx = -inv * inv * 520 * K; s.sx = 1 + inv * 1.8; s.alpha = clamp(p * 5); s.ghost = inv * 12; }
            case HOP -> { s.dy = -Math.abs(Math.sin(p * Math.PI * 3)) * (1 - p) * 60 * K; s.alpha = clamp(p * 6); }
            case FOCUS -> { s.ghost = inv * 18; s.alpha = clamp(p * 2); }
            case UNFOLD -> { s.sy = Math.max(0.02, e); s.dy = -(1 - e) * 20 * K; s.alpha = clamp(p * 4); }
            case ZIP -> { s.wipe = p; s.dx = inv * 90 * K; s.ghost = inv * 5; }
            case FALL_IN -> { s.dy = -inv * inv * 260 * K; s.alpha = clamp(p * 6); }
            case TILT -> { s.rot = inv * 0.5 * ((word & 1) == 0 ? 1 : -1); s.dy = inv * 26 * K; s.alpha = clamp(p * 2.5); }
            case SPREAD -> { double c0 = (gl.i - 3) * 0.5; s.dx = c0 * inv * 40 * K; s.sx = s.sy = Math.max(0.01, 0.4 + 0.6 * e); s.alpha = clamp(p * 3); }
            case BLUR -> { s.ghost = inv * 14; s.sx = s.sy = 1 + inv * 0.1; s.alpha = clamp(p * 2.5); }
            case FLICKER -> s.alpha = p < 1 ? (hash(step, gl.i + 3) < 0.25 + 0.75 * p ? 1 : 0.15) : 1;
            case RISE_MASK -> { s.dy = inv * inv * 44 * K; s.alpha = clamp(p * 3); }
            case DROP_MASK -> { s.dy = -inv * inv * 44 * K; s.alpha = clamp(p * 3); }
            case FLIP_X -> { s.sx = Math.max(0.04, Math.abs(Math.cos(inv * Math.PI))); s.alpha = clamp(p * 5); }
            case DOMINO -> { s.rot = -inv * 1.3; s.dx = -inv * 26 * K; s.dy = inv * 10 * K; s.alpha = clamp(p * 3); }
            case FOLD -> { s.sx = Math.max(0.03, e); s.rot = inv * 0.25; s.alpha = clamp(p * 4); }
            case UNROLL -> { s.wipe = e; s.sy = 1; s.dy = -inv * 8 * K; }
            case STROKE_DRAW -> { s.wipe = p; s.ghost = inv * 3; s.alpha = clamp(p * 3); }
            case OUTLINE_FILL -> { s.ghost = inv * 6; s.alpha = clamp(p * 1.8); }
            case SPLIT_JOIN -> { s.bands = 2; s.bandDx = inv * 80 * K * ((word & 1) == 0 ? 1 : -1); s.alpha = clamp(p * 4); }
            case V_SLICE -> { s.bands = 6; s.bandDx = inv * 54 * K * (gl.i % 2 == 0 ? 1 : -1); s.alpha = clamp(p * 4); }
            case IRIS -> { s.sx = s.sy = Math.max(0.01, E.outExpo(p)); s.alpha = clamp(p * 6); }
            case DIAG_WIPE -> { s.wipe = p; s.dx = -inv * 24 * K; s.dy = inv * 14 * K; }
            case BLINDS -> { s.bands = 8; s.bandDx = inv * 16 * K * (gl.i % 2 == 0 ? 1 : -1); s.alpha = clamp(p * 5); }
            case CHECKER -> s.alpha = p < 1 ? (hash(step / 2, gl.i * 7 + word) < p * 1.1 ? 1 : 0) : 1;
            case RANDOM_ORDER -> { double k = E.outBack(p); s.sx = s.sy = Math.max(0.01, k); s.alpha = clamp(p * 6); }
            case BOUNCE_BIG -> { s.dy = -(1 - bounce(p)) * 190 * K; s.sx = s.sy = 1 + inv * 0.25; s.alpha = clamp(p * 5); }
            case SQUASH_DROP -> { s.dy = -inv * inv * 200 * K; double l = clamp((p - 0.72) * 3.5); s.sy = 1 - 0.35 * Math.sin(l * Math.PI); s.sx = 1 + 0.3 * Math.sin(l * Math.PI); s.alpha = clamp(p * 6); }
            case RUBBER -> { double k = 1 + Math.exp(-5 * p) * Math.sin(p * 18) * 0.7; s.sx = k; s.sy = 2 - k; s.alpha = clamp(p * 6); }
            case GLITCH_IN -> { s.dx = (hash(step, gl.i) - 0.5) * 60 * K * inv; s.ghost = inv * 14; s.alpha = p < 1 && hash(step + 5, gl.i) > 0.2 + 0.8 * p ? 0.1 : 1; }
            case ECHO_IN -> { s.ghost = inv * 16; s.sx = s.sy = 1 + inv * 0.18; s.alpha = clamp(p * 3); }
            case WHIP -> { s.dx = -inv * inv * 600 * K; s.sx = 1 + inv * 2.2; s.ghost = inv * 14; s.alpha = clamp(p * 6); }
            case SKEW_IN -> { s.rot = inv * 0.38; s.dx = inv * 60 * K; s.sx = 1 + inv * 0.5; s.alpha = clamp(p * 3); }
            case TRACK_IN -> { s.dx = ((gl.i % 10) - 4.5) * inv * 26 * K; s.alpha = clamp(p * 2.5); }
            case TRACK_OUT -> { s.dx = ((gl.i % 10) - 4.5) * -inv * 5 * K; s.sx = 1 + inv * 0.4; s.alpha = clamp(p * 3); }
            case BLUR_STAGGER -> { s.ghost = inv * 18; s.alpha = clamp(p * 2); }
            case FADE_STAGGER -> s.alpha = clamp(p * 1.6);
            case INK_BLEED -> { s.ghost = inv * 9; s.sx = s.sy = 0.88 + 0.12 * e; s.alpha = clamp(p * 1.8); }
            case NEON_ON -> { s.alpha = p < 1 ? (hash(step, gl.i + 9) < p ? 1 : 0.25 * p) : 1; s.ghost = (1 - p) * 4; }
            case CURSOR_SWEEP -> { s.wipe = p; s.ghost = inv * 4; }
            case STAMP -> { s.sx = s.sy = 1 + inv * inv * 2.6; s.rot = inv * 0.12; s.alpha = clamp(p * 9); s.ghost = p > 0.85 && p < 1 ? 6 : 0; }
            case SPRING_IN -> { s.dy = -Math.exp(-5 * p) * Math.cos(p * 16) * 46 * K; s.alpha = clamp(p * 6); }
            case PENDULUM -> { s.rot = Math.cos(p * 9) * (1 - p) * 0.9; s.dx = Math.sin(p * 9) * (1 - p) * 14 * K; s.alpha = clamp(p * 5); }
            case ROLL_IN -> { s.dx = -inv * 340 * K; s.rot = -inv * 7; s.alpha = clamp(p * 5); }
            case SLINGSHOT -> { s.dx = -inv * inv * 460 * K; s.dy = inv * 30 * K * Math.sin(p * 3); s.sx = 1 + inv * 0.8; s.alpha = clamp(p * 6); }
            case ROCK_SETTLE -> { s.rot = Math.sin(p * 22) * (1 - p) * 0.3; s.dy = -inv * 30 * K; s.alpha = clamp(p * 6); }
            case BOUNCE_BALL -> { s.dy = -Math.abs(Math.cos(p * Math.PI * 2.5)) * inv * 130 * K; s.alpha = clamp(p * 8); }
            case SNAP_RAIL -> { s.dx = (r1 - 0.5) * inv * 240 * K; s.dy = (r2 - 0.5) * inv * 40 * K; s.alpha = clamp(p * 4); }
            case FAN_OPEN -> { s.rot = -inv * 1.1; s.dx = -inv * 40 * K; s.dy = inv * 40 * K; s.alpha = clamp(p * 3); }
            case CYLINDER -> { s.sx = Math.max(0.05, Math.abs(Math.cos(inv * 1.6))); s.alpha = clamp(p * 3); }
            case SHUFFLE -> { s.dx = (r1 - 0.5) * inv * 300 * K; s.dy = (r2 - 0.5) * inv * 120 * K; s.rot = (r3 - 0.5) * inv * 1.6; s.alpha = clamp(p * 3); }
            case STOP_MOTION -> { double q2 = Math.floor(p * 6) / 6.0; s.dx = (hash((long) (q2 * 6), gl.i) - 0.5) * (1 - q2) * 24 * K; s.dy = (hash((long) (q2 * 6) + 9, gl.i) - 0.5) * (1 - q2) * 24 * K; s.alpha = p < 1 ? 0.3 + 0.7 * q2 : 1; }
            case RIPPLE -> { s.dy = Math.sin(p * 12 - gl.i * 0.9) * (1 - p) * 30 * K; s.sx = s.sy = 1 + Math.sin(p * 12 - gl.i * 0.9) * (1 - p) * 0.12; s.alpha = clamp(p * 4); }
            case STICKER_PEEL -> { s.sy = Math.max(0.05, e); s.rot = inv * 0.18; s.dy = -inv * 10 * K; s.ghost = inv * 2; s.alpha = clamp(p * 5); }
            case CRUMPLE -> { s.sx = 0.4 + 0.6 * e; s.sy = 0.4 + 0.6 * e; s.rot = Math.sin(p * 16) * (1 - p) * 0.5; s.alpha = clamp(p * 4); }
            case TORN_JOIN -> { s.bands = 2; s.bandDx = inv * 60 * K * ((gl.i & 1) == 0 ? 1 : -1); s.rot = inv * 0.1 * ((gl.i & 1) == 0 ? 1 : -1); s.alpha = clamp(p * 4); }
            case OVEREXPOSE -> { s.ghost = inv * 22; s.sx = s.sy = 1 + inv * 0.06; s.alpha = clamp(p * 2.2); }
            case GLINT -> { s.alpha = clamp(p * 2.5); s.ghost = Math.max(0, 1 - Math.abs(p * 2 - gl.i * 0.15)) * 12; }
            case LOUPE -> { s.sx = s.sy = 1 + inv * 1.6; s.ghost = inv * 6; s.alpha = clamp(p * 3); }
            case FILM_FEED -> { s.dy = inv * 160 * K; s.sy = 1 + inv * 0.3; s.alpha = clamp(p * 4); }
            case BACKLIGHT -> { s.ghost = inv * 20; s.alpha = clamp(p * 2.5); s.sx = s.sy = 1 - inv * 0.05; }
            case LIGHT_LEAK -> { s.alpha = clamp(p * 2.5); s.ghost = Math.sin(p * Math.PI) * 16; }
            case HEAT_HAZE -> { s.dx = Math.sin(p * 18 + gl.i) * inv * 10 * K; s.dy = Math.cos(p * 14 + gl.i * 0.7) * inv * 6 * K; s.ghost = inv * 7; s.alpha = clamp(p * 2.5); }
            case CRT_ON -> { s.sy = Math.max(0.02, E.outExpo(clamp(p * 1.8) - 0.0) * (p < 0.4 ? 0.05 : 1)); s.sx = 1 + inv * 0.8; s.ghost = inv * 8; s.alpha = clamp(p * 6); }
            case INTERLACE -> { s.bands = 10; s.bandDx = inv * 18 * K * (gl.i % 2 == 0 ? 1 : -1); s.alpha = clamp(p * 4); }
            case DITHER -> s.alpha = p < 1 ? (hash(step / 2, gl.i * 13 + word) < p ? 1 : 0) : 1;
            case ODOMETER -> { if (p < 1) s.ch = scramble(gl, step / 2); s.dy = inv * inv * 36 * K; s.alpha = clamp(p * 5); }
            case QUARTERS -> { double qd = (gl.i % 4); s.dx = (qd < 2 ? -1 : 1) * inv * 120 * K; s.dy = (qd % 2 == 0 ? -1 : 1) * inv * 70 * K; s.alpha = clamp(p * 3); }
            case PRINT_REGISTER -> { s.dx = inv * 18 * K; s.dy = -inv * 10 * K; s.ghost = inv * 16; s.alpha = clamp(p * 3); }
            case LIQUID_FILL -> { s.dy = inv * 40 * K + Math.sin(p * 14 + gl.i * 0.5) * inv * 6 * K; s.alpha = clamp(p * 2.5); s.sy = 0.6 + 0.4 * e; }
            case WIND_BLOWN -> { s.dx = -inv * inv * 380 * K * (0.6 + r1); s.dy = (r2 - 0.5) * inv * 60 * K; s.rot = -inv * 2.5 * r3; s.alpha = clamp(p * 3); }
            case SHADOW_FIRST -> { s.ghost = (1 - clamp(p * 2)) * 0 + inv * 3; s.alpha = clamp(p * 1.6); s.dy = inv * 8 * K; }
            case TOKOROTEN -> { s.wipe = p; s.sx = 1 + inv * 0.3; s.dx = -inv * 30 * K; }
        }
        double q = c.outDur > 0 ? clamp((lt - outStart - gl.i * (c.exit == Exit.BLUR_OUT_STAGGER || c.exit == Exit.DISSOLVE ? 0.04 : 0.01)) / c.outDur) : 0;
        if (q > 0) {
            double a = 1;
            switch (c.exit) {
                case FADE -> a = 1 - q;
                case DRIFT -> { s.dy -= q * 30 * K; a = 1 - q; }
                case FALL -> { s.dy += q * q * 170 * K; s.rot += (r1 - 0.5) * q * 1.3; a = 1 - q * q * q; }
                case EXPLODE -> { s.dx += (r1 - 0.5) * q * 520 * K; s.dy += (r2 - 0.5) * q * 380 * K; s.rot += (r3 - 0.5) * q * 4; a = 1 - q * q; }
                case SLICE -> { s.bands = 3; s.bandDx = q * 230 * K * ((word & 1) == 0 ? 1 : -1); a = 1 - q * q; }
                case SHRINK -> { double k = 1 - q; s.sx *= k; s.sy *= k; a = 1 - q * q; }
                case GLITCH -> { s.dx += (hash(step, gl.i) - 0.5) * 34 * K * q; s.ghost += 10 * q; a = hash(step + 3, gl.i) < 0.35 + 0.65 * (1 - q) ? 1 - q * 0.6 : 0; }
                case SCATTER -> { s.dx += (r1 - 0.5) * q * 640 * K; s.dy += (r2 - 0.5) * q * 60 * K; a = 1 - q; }
                case SLIDE_LEFT -> { s.dx -= q * q * 300 * K; a = 1 - q * q; }
                case SLIDE_RIGHT -> { s.dx += q * q * 300 * K; a = 1 - q * q; }
                case RISE_UP -> { s.dy -= q * q * 200 * K; a = 1 - q * q; }
                case ZOOM_OUT -> { s.sx *= 1 + q * 2.6; s.sy *= 1 + q * 2.6; s.ghost += q * 8; a = 1 - q * q; }
                case SPIN_OUT -> { s.rot += q * Math.PI * 1.5; double k = Math.max(0.01, 1 - q); s.sx *= k; s.sy *= k; a = 1 - q; }
                case FLIP_OUT -> { s.sy *= Math.max(0.02, Math.abs(Math.cos(q * Math.PI * 1.5))); a = 1 - q * q; }
                case COLLAPSE -> { s.sy *= Math.max(0.02, 1 - q); s.dy += q * 18 * K; a = 1 - q * q; }
                case BLINK -> { a = hash(step, gl.i + 11) < 1 - q ? 1 : 0; }
                case MELT -> { s.dy += q * q * 60 * K * (0.5 + r1); s.sy *= 1 + q * 0.8; a = 1 - q * q; }
                case SWING_OUT -> { s.rot += Math.sin(q * 8) * q * 0.7; s.dy += q * q * 90 * K; a = 1 - q * q; }
                case WAVE_OUT -> { s.dy += Math.sin(q * 8 + gl.i * 0.7) * q * 30 * K; a = 1 - q * q; }
                case BLUR -> { s.ghost += q * 20; a = 1 - q; }
                case TUMBLE -> { s.rot += q * Math.PI * 2 * (r1 < 0.5 ? 1 : -1); s.dy += q * q * 160 * K; s.dx += (r2 - 0.5) * q * 100 * K; a = 1 - q * q; }
                case FLY -> { s.dx += q * q * 560 * K; s.sx *= 1 + q * 1.6; s.ghost += q * 12; a = 1 - q * q; }
                case POP_OUT -> { double k = q < 0.4 ? 1 + q * 0.8 : Math.max(0.01, 1.32 * (1 - (q - 0.4) / 0.6)); s.sx *= k; s.sy *= k; a = q < 0.4 ? 1 : 1 - (q - 0.4) / 0.6; }
                case ZIP -> { s.wipe = 1 - q; s.dx += q * 60 * K; a = 1; }
                case SQUEEZE -> { s.sx *= Math.max(0.02, 1 - q); s.sy *= 1 + q * 0.8; a = 1 - q * q; }
                case UNRAVEL -> { s.rot += (r1 - 0.5) * q * 2.6; s.dx += (gl.i % 2 == 0 ? 1 : -1) * q * 90 * K; s.dy += q * q * 80 * K; a = 1 - q * q; }
                case WIPE -> { s.wipe = 1 - q; a = 1; }
                case STRETCH -> { s.sx *= 1 + q * 2.6; s.sy *= Math.max(0.02, 1 - q * 0.7); a = 1 - q * q; }
                case SINK_MASK -> { s.dy += q * q * 36 * K; a = 1 - q * q * q; }
                case RISE_OUT -> { s.dy -= q * 70 * K; a = 1 - q * q; }
                case FLIP_OUT_X -> { s.sx *= Math.max(0.03, Math.cos(q * Math.PI / 2)); a = 1 - q * q; }
                case FLIP_OUT_Y -> { s.sy *= Math.max(0.03, Math.cos(q * Math.PI / 2)); s.dy += q * 10 * K; a = 1 - q * q; }
                case FOLD_OUT -> { s.sx *= Math.max(0.03, 1 - q); s.rot += q * 0.25; a = 1 - q * q; }
                case SQUASH_OUT -> { s.sy *= Math.max(0.02, 1 - q); s.sx *= 1 + q * 0.7; s.dy += q * 14 * K; a = 1 - q * q; }
                case TRACK_OUT_WIDE -> { s.dx += ((gl.i % 10) - 4.5) * q * 30 * K; a = 1 - q * q; }
                case ZOOM_THROUGH -> { s.sx *= 1 + q * q * 5; s.sy *= 1 + q * q * 5; a = 1 - q * q; }
                case ZOOM_FAR -> { double k = Math.max(0.02, 1 - q * 0.9); s.sx *= k; s.sy *= k; s.ghost += q * 6; a = 1 - q * q; }
                case TWIST -> { s.rot += q * (gl.i % 2 == 0 ? 1 : -1) * 1.4; s.sx *= Math.max(0.1, 1 - q * 0.6); a = 1 - q * q; }
                case BLUR_OUT_STAGGER -> { s.ghost += q * 20; a = 1 - q; }
                case UNDRAW -> { s.wipe = 1 - q; s.ghost += q * 3; a = 1 - q * 0.4; }
                case OUTLINE_OUT -> { s.ghost += q * 8; a = 1 - q * 0.9; }
                case IRIS_CLOSE -> { double k = Math.max(0.01, 1 - E.inExpo(q)); s.sx *= k; s.sy *= k; a = 1 - q * q * q; }
                case DIAG_WIPE_OUT -> { s.wipe = 1 - q; s.dx += q * 24 * K; s.dy -= q * 14 * K; }
                case BLINDS_CLOSE -> { s.bands = 8; s.bandDx = q * 22 * K * (gl.i % 2 == 0 ? 1 : -1); a = 1 - q * q; }
                case CHECKER_OUT -> a = hash(step / 2, gl.i * 7 + word) < 1 - q ? 1 : 0;
                case SPLIT_APART -> { s.bands = 2; s.bandDx = q * 150 * K * ((word & 1) == 0 ? 1 : -1); a = 1 - q * q; }
                case V_SLICE_DROP -> { s.bands = 6; s.bandDx = q * 30 * K * (gl.i % 2 == 0 ? 1 : -1); s.dy += q * q * 120 * K; a = 1 - q * q; }
                case DISSOLVE -> { s.dy -= q * 14 * K; s.ghost += q * 10; a = 1 - q; }
                case BACKSPACE -> a = q < 1 ? (hash(step, gl.i) > q * 1.2 ? 1 : 0) : 0;
                case SCRAMBLE_OUT -> { if (q > 0.05 && q < 1) s.ch = scramble(gl, step); a = 1 - q * q; }
                case GLITCH_DISSOLVE -> { s.dx += (hash(step, gl.i) - 0.5) * 50 * K * q; a = hash(step + 4, gl.i) < 1 - q ? 1 : 0; s.ghost += q * 8; }
                case ECHO_OUT -> { s.ghost += q * 22; s.sx *= 1 + q * 0.2; s.sy *= 1 + q * 0.2; a = 1 - q; }
                case WHIP_OUT -> { s.dx += q * q * 640 * K; s.sx *= 1 + q * 2.2; s.ghost += q * 14; a = 1 - q * q; }
                case GRAVITY -> { s.dy += q * q * 280 * K; s.rot += (r1 - 0.5) * q * 2; a = 1 - q * q * q; }
                case BURN -> { s.dy -= q * 18 * K; s.ghost += q * 12; s.sy *= 1 - q * 0.25; a = 1 - q * q; }
                case SWEEP_COVER -> { s.wipe = 1 - q; a = 1; }
                case SHATTER_LITE -> { s.bands = 4; s.bandDx = q * 90 * K * (gl.i % 2 == 0 ? 1 : -1); s.dy += q * q * 110 * K; s.rot += (r1 - 0.5) * q * 1.2; a = 1 - q * q; }
                case PEEL_OFF -> { s.sy *= Math.max(0.05, 1 - q); s.rot += q * 0.3; s.dy -= q * 20 * K; a = 1 - q * q; }
                case CRUMPLE_OUT -> { double k = Math.max(0.02, 1 - q); s.sx *= k; s.sy *= k; s.rot += Math.sin(q * 16) * 0.4; s.dy += q * q * 60 * K; a = 1 - q * q * q; }
                case TEAR_OUT -> { s.bands = 2; s.bandDx = q * 110 * K * ((gl.i & 1) == 0 ? 1 : -1); s.rot += q * 0.2 * ((gl.i & 1) == 0 ? 1 : -1); a = 1 - q * q; }
                case OVEREXPOSE_OUT -> { s.ghost += q * 24; s.sx *= 1 + q * 0.06; a = 1 - q; }
                case SCAN_OUT -> { s.wipe = 1 - q; s.ghost += q * 6; }
                case STRIPES_OUT -> { s.bands = 10; s.bandDx = q * 40 * K * (gl.i % 2 == 0 ? 1 : -1); a = 1 - q * q; }
                case HALFTONE_OUT -> { s.ghost += q * 10; s.sx *= 1 - q * 0.3; s.sy *= 1 - q * 0.3; a = 1 - q * q; }
                case VACUUM_OUT -> { s.dx -= ((gl.i % 10) - 4.5) * q * 20 * K; double k = Math.max(0.02, 1 - q); s.sx *= k; s.sy *= k; a = 1 - q * q; }
                case SAND_OUT -> { s.dx += q * q * (120 + r1 * 260) * K; s.dy += (r2 - 0.5) * q * 80 * K; s.ghost += q * 8; a = 1 - q * q; }
                case SHRED_OUT -> { s.bands = 8; s.bandDx = 0; s.dy += q * q * (60 + 100 * hash(step / 100, gl.i * 3)) * K; a = 1 - q * q; }
                case DOMINO_OUT -> { s.rot += q * 1.3; s.dx += q * 26 * K; s.dy += q * q * 20 * K; a = 1 - q * q; }
                case HINGE_OUT -> { s.rot += q * q * 2.2; s.dx += q * 12 * K; s.dy += q * q * 160 * K; a = 1 - q * q; }
                case ROCKET_OFF -> { s.dy -= q * q * 460 * K; s.sy *= 1 + q * 1.4; s.ghost += q * 10; a = 1 - q * q; }
                case BOUNCE_OFF -> { s.dy -= Math.abs(Math.sin(q * Math.PI * 2)) * 50 * K; s.dx += q * q * 380 * K; a = 1 - q * q; }
                case BALLOON_OFF -> { s.dy -= q * q * 320 * K; s.dx += Math.sin(q * 6) * 20 * K; double k = 1 + q * 0.2; s.sx *= k; s.sy *= k; a = 1 - q * q; }
                case DEFLATE_OUT -> { s.sx *= 1 + q * 0.25; s.sy *= Math.max(0.02, 1 - q * 0.9); s.dx += q * q * 200 * K; s.dy -= q * 40 * K; a = 1 - q * q; }
                case HAZE_OUT -> { s.dx += Math.sin(q * 18 + gl.i) * q * 10 * K; s.dy -= q * 16 * K; s.ghost += q * 9; a = 1 - q; }
                case CLAP_SHUT -> { s.dx += (gl.i % 2 == 0 ? -1 : 1) * q * q * 60 * K; s.sx *= Math.max(0.03, 1 - q); a = 1 - q * q; }
                case LAMP_OFF -> { s.sy *= Math.max(0.02, 1 - E.inExpo(q)); s.sx *= 1 + q * 0.4; s.ghost += q * 8; a = q < 0.9 ? 1 : 1 - (q - 0.9) * 10; }
                case SLOT_OUT -> { if (q > 0.05 && q < 1) s.ch = scramble(gl, step / 2); s.dy += q * q * 180 * K; a = 1 - q * q; }
                case CLOCK_OUT -> { s.wipe = 1 - q; s.rot += q * 0.1; }
                case TORNADO_OUT -> { double ang = q * 9 + gl.i; s.dx += Math.cos(ang) * q * 90 * K; s.dy += Math.sin(ang) * q * 50 * K - q * q * 120 * K; s.rot += q * 6; a = 1 - q * q; }
                case ROLL_UP_OUT -> { s.sy *= Math.max(0.03, 1 - q); s.dy -= q * 14 * K; a = 1 - q * q * q; }
                case FLUTTER_OUT -> { s.dy += q * 110 * K; s.dx += Math.sin(q * 9 + gl.i) * 26 * K * q; s.rot += Math.sin(q * 9 + gl.i) * 0.6; a = 1 - q * q; }
                case ROLL_OFF -> { s.dx += q * q * 520 * K; s.rot += q * 8; a = 1 - q * q; }
                case SLASH_OUT -> { s.bands = 2; s.bandDx = q * 90 * K * ((gl.i & 1) == 0 ? 1 : -1); s.dy += q * q * 40 * K * ((gl.i & 1) == 0 ? -1 : 1); s.rot += q * 0.18; a = 1 - q * q; }
                case MOSAIC_OUT -> { double k = Math.max(0.1, 1 - q * 0.6); s.sx *= k; s.sy *= k; a = hash(step / 2, gl.i * 11 + word) < 1 - q ? 1 - q * 0.5 : 0; }
                case SCRIBBLE_OUT -> { s.rot += Math.sin(q * 30 + gl.i) * 0.25 * q; s.dx += Math.sin(q * 25 + gl.i * 2) * 8 * K * q; a = 1 - q * q; }
                case CANDLE_OUT -> { s.dx += Math.sin(q * 14) * q * 6 * K; s.sy *= 1 + q * 0.3; s.dy -= q * 24 * K; s.ghost += q * 5; a = 1 - q; }
            }
            s.alpha *= a;
        }
        if (p >= 1 && q <= 0 && !it.noHold) {
            s.dy += Math.sin(lt * 1.7 + gl.i * 0.6) * 1.4 * K;
            s.ghost += 0.5 + hash(step / 3, gl.i) * 1.5;
        }
        return s;
    }

    private String scramble(GL g, long step) {
        String pool = cjk(g.ch) ? "あいうえおかきくけこさしすせそ" : "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&";
        int i = (int) (hash(step, g.i) * pool.length());
        return pool.substring(i, i + 1);
    }

    static double hash(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L ^ b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static Color a(Color c, double alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * clamp(alpha))); }

    // ------------------------------------------------------------------ drawing
    /** Draws the picture at song time t into two transparent images of W x H: back (decorations) and front (lyrics). */
    void draw(double t, BufferedImage back, BufferedImage front) {
        long step = (long) Math.floor(t / stepSec + 1e-6);
        double ts = step * stepSec;
        int px = (front.getWidth() - W) / 2, py = (front.getHeight() - H) / 2;   // room for effects to spill past the picture
        java.awt.Graphics2D gb = init(back), gf = init(front);
        try {
            LyricKit.forceKind = look.font();
            gb.translate(px, py);
            gf.translate(px, py);
            if (look.backdrop() > 0) backdrop(gb, ts);
            gf.scale(SCALE, SCALE);
            for (Cut c : cuts) {
                if (ts < c.start || ts >= c.end) continue;
                try {
                    Env env = new Env(gf, this, c, ts, step);
                    BB bb = LyricLayouts.ALL.get(c.layout).render().render(env);
                    gf.setComposite(AlphaComposite.SrcOver);
                    if (bb != null) c.box = new double[]{bb.x0() * SCALE, bb.y0() * SCALE, bb.x1() * SCALE, bb.y1() * SCALE};
                } catch (RuntimeException ex) {
                    gf.setComposite(AlphaComposite.SrcOver);   // a layout that fails costs only its own cut
                }
                if (look.decor()) decor(gb, c, ts, step);
            }
            if (look.frame() || look.hud()) hud(gb, ts, step);
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

    private static double easeOut(double p) { return E.outCubic(p); }

    // ------------------------------------------------------------------ decorations (back layer)
    private double weight(String k) { return style.decor.getOrDefault(k, 0.0); }

    private boolean has(Cut c, String k, double bonus) {
        double p = Math.min(0.95, weight(k) * 0.6 + bonus);
        return hash(c.seed, k.hashCode()) < p;
    }

    private void decor(java.awt.Graphics2D g, Cut c, double ts, long step) {
        Scheme sc = c.sc;
        double life = clamp((ts - c.start + 0.1) / 0.5);
        double fade = 1 - clamp((ts - (c.end - 0.25)) / 0.3);
        double al = easeOut(life) * fade;
        if (al <= 0.01) return;
        double cx = (c.box[0] + c.box[2]) / 2, cy = (c.box[1] + c.box[3]) / 2;
        double bw = c.box[2] - c.box[0], bh = c.box[3] - c.box[1];
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        if (has(c, "rings", 0.12)) {
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
            double k = easeOut(clamp((ts - c.start) / 0.4));
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
            String n = String.format(Locale.ROOT, "%02d", c.line + 1);
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

    /** A dim plate behind the lyrics, in the colour of the line being shown, for reading over a busy scene. */
    private void backdrop(java.awt.Graphics2D g, double ts) {
        for (Cut c : cuts) {
            if (ts < c.start || ts >= c.end) continue;
            double in = clamp((ts - c.start) / 0.2), out = clamp((c.end - ts) / 0.2);
            g.setColor(a(c.sc.bg(), Math.min(1, look.backdrop() / 100.0) * Math.min(in, out)));
            g.fill(new java.awt.geom.Rectangle2D.Double(0, 0, W, H));
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
        if (look.frame()) for (int i = 0; i < 4; i++) {
            double x = (i & 1) == 0 ? m : W - m, y = (i & 2) == 0 ? m : H - m;
            double sx = (i & 1) == 0 ? 1 : -1, sy = (i & 2) == 0 ? 1 : -1;
            g.draw(new Line2D.Double(x, y, x + sx * len, y));
            g.draw(new Line2D.Double(x, y, x, y + sy * len));
        }
        if (!look.hud() || (!full && weight("hud") <= 0)) return;
        int cur = 0;
        for (int i = 0; i < cuts.size(); i++) if (cuts.get(i).start <= ts) cur = i;
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
        if ((style.grain <= 0 && style.scan <= 0) || look.grain() <= 0) return;
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
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, (float) Math.min(1, style.grain * look.grain() / 100 * 0.5)));
        int ox = (int) (hash(step, 1) * 256), oy = (int) (hash(step, 2) * 256);
        for (int y = -oy; y < img.getHeight(); y += 256) for (int x = -ox; x < img.getWidth(); x += 256) g.drawImage(noise, x, y, null);
        if (style.scan > 0) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, (float) Math.min(0.6, style.scan * look.grain() / 100 * 0.5)));
            g.setColor(Color.BLACK);
            for (int y = (int) (step % 3); y < img.getHeight(); y += 3) g.fillRect(0, y, img.getWidth(), 1);
        }
        g.dispose();
    }
}
