package io.github.raincandyfx.rainium;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The drawing kit the layouts are written against, shaped like the one JIZURA's layouts use, so each
 * layout can be ported close to line for line: text items laid out character by character, the
 * "main" drawing that applies a cut's way in, hold and way out, and plain shapes. Everything is in a
 * 1920×1080 design space; the caller scales the Graphics2D to the picture.
 * Pure Java2D, so the tests can run it.
 */
final class JizuraKit {
    private JizuraKit() {}

    // ------------------------------------------------------------------ numbers
    static double clamp(double v) { return v < 0 ? 0 : Math.min(1, v); }
    static double clamp(double v, double a, double b) { return v < a ? a : Math.min(b, v); }
    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    static final double TAU = Math.PI * 2, DEG = Math.PI / 180;
    static double smooth(double a, double b, double x) { double t = clamp((x - a) / (b - a)); return t * t * (3 - 2 * t); }
    static boolean isKanji(String ch) { return ch.matches("[㐀-鿿豈-﫿々〆ヶ]"); }

    /** JIZURA's easing functions. */
    static final class E {
        static double outCubic(double x) { x = clamp(x); return 1 - Math.pow(1 - x, 3); }
        static double inCubic(double x) { x = clamp(x); return x * x * x; }
        static double outExpo(double x) { x = clamp(x); return x >= 1 ? 1 : 1 - Math.pow(2, -10 * x); }
        static double inExpo(double x) { x = clamp(x); return x <= 0 ? 0 : Math.pow(2, 10 * x - 10); }
        static double outBack(double x) { return outBack(x, 1.9); }
        static double outBack(double x, double s) { x = clamp(x); double c = s + 1; return 1 + c * Math.pow(x - 1, 3) + s * Math.pow(x - 1, 2); }
        static double inOutCubic(double x) { x = clamp(x); return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2; }
        static double inQuad(double x) { x = clamp(x); return x * x; }
        static double inOutSine(double x) { x = clamp(x); return -(Math.cos(Math.PI * x) - 1) / 2; }
        static double bounce(double p) {
            if (p < 1 / 2.75) return 7.5625 * p * p;
            if (p < 2 / 2.75) { p -= 1.5 / 2.75; return 7.5625 * p * p + 0.75; }
            if (p < 2.5 / 2.75) { p -= 2.25 / 2.75; return 7.5625 * p * p + 0.9375; }
            p -= 2.625 / 2.75; return 7.5625 * p * p + 0.984375;
        }
    }

    /** Deterministic hashing: the same numbers always give the same 0..1. */
    static double r(long a, long b, long c, long d) {
        long h = a * 0x9E3779B97F4A7C15L;
        h ^= (b + 0x7F4A7C15L) * 0xC2B2AE3D27D4EB4FL;
        h ^= (c + 0x1234567L) * 0x165667B19E3779F9L;
        h ^= (d + 0x5851F42DL) * 0x27D4EB2F165667C5L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32; h *= 0x94D049BB133111EBL; h ^= h >>> 29;
        return (h & 0xFFFFFFL) / (double) 0x1000000;
    }
    static double r(long a, long b, long c) { return r(a, b, c, 0); }
    static double r(long a, long b) { return r(a, b, 0, 0); }
    static double rs(long a, long b, long c) { return r(a, b, c, 0) * 2 - 1; }
    static double rr(double lo, double hi, long a, long b, long c) { return lo + (hi - lo) * r(a, b, c, 0); }

    /** A seeded random stream (mulberry32) with the picks the layout plans use. */
    static final class Rng {
        private int a;
        Rng(long seed) { a = (int) (seed ^ (seed >>> 32)); }
        double next() {
            a += 0x6D2B79F5;
            int t = a;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
        @SafeVarargs final <T> T pick(T... items) { return items[(int) (next() * items.length) % items.length]; }
        <T> T pick(List<T> items) { return items.get((int) (next() * items.size()) % items.size()); }
        double range(double lo, double hi) { return lo + (hi - lo) * next(); }
        int integer(int lo, int hi) { return lo + (int) (next() * (hi - lo + 1)); }
        boolean chance(double p) { return next() < p; }
    }

    /** A layout's plan: named choices made once per cut. */
    static final class Params extends LinkedHashMap<String, Object> {
        Params with(String k, Object v) { put(k, v); return this; }
        double d(String k) { Object o = get(k); return o instanceof Number n ? n.doubleValue() : o instanceof Boolean b ? (b ? 1 : 0) : 0; }
        int i(String k) { return (int) d(k); }
        boolean b(String k) { Object o = get(k); return o instanceof Boolean bo ? bo : o instanceof Number n && n.doubleValue() != 0; }
        String s(String k) { Object o = get(k); return o == null ? "" : String.valueOf(o); }
    }

    record BB(double x0, double y0, double x1, double y1) {
        double cx() { return (x0 + x1) / 2; }
        double cy() { return (y0 + y1) / 2; }
        static BB union(BB a, BB b) { return a == null ? b : b == null ? a : new BB(Math.min(a.x0, b.x0), Math.min(a.y0, b.y0), Math.max(a.x1, b.x1), Math.max(a.y1, b.y1)); }
    }

    // ------------------------------------------------------------------ type faces
    /** JIZURA's font keys (gothic_black, mincho, mono, ...) map to what a Java program can reliably draw: sans, serif or mono, bold or not. */
    record Face(int kind, boolean bold) {}   // kind: 0 sans, 1 serif, 2 mono

    private static final Map<String, Face> FACES = new HashMap<>();
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static String sansFamily, serifFamily, monoFamily;
    private static final Map<String, Object[]> SHAPES = new HashMap<>();   // key → {Shape at 100 px, Float advance}

    static void defineFace(String key, String kind, double weight) {
        int k = switch (kind) { case "mincho", "brush", "hand" -> 1; case "mono" -> 2; default -> 0; };
        FACES.put(key, new Face(k, weight >= 600));
    }

    static Face face(String key) {
        Face f = FACES.get(key);
        if (f != null) return f;
        boolean serif = key.contains("mincho") || key.contains("serif");
        return new Face(key.contains("mono") ? 2 : serif ? 1 : 0, !key.contains("light") && !key.contains("med"));
    }

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

    /** The glyph outline at 100 px (origin on the baseline, left edge) and its advance. */
    private static synchronized Object[] shaped(String font, String ch) {
        families();
        Face f = face(font);
        String key = f.kind + (f.bold ? "b" : "p") + ch;
        Object[] s = SHAPES.get(key);
        if (s == null) {
            Font fo = new Font(f.kind == 2 ? monoFamily : f.kind == 1 ? serifFamily : sansFamily, f.bold ? Font.BOLD : Font.PLAIN, 100);
            if (!fo.canDisplay(ch.codePointAt(0))) fo = new Font(Font.DIALOG, f.bold ? Font.BOLD : Font.PLAIN, 100);
            GlyphVector gv = fo.createGlyphVector(FRC, ch);
            s = new Object[]{gv.getOutline(), gv.getGlyphMetrics(0).getAdvance()};
            if (SHAPES.size() > 6000) SHAPES.clear();
            SHAPES.put(key, s);
        }
        return s;
    }

    /** Advance of a character in ems. */
    static double adv(String font, String ch) { return ((Float) shaped(font, ch)[1]) / 100.0; }

    // ------------------------------------------------------------------ text items
    /** One piece of text to draw: its font, size and place, and how it looks. Fields are set directly or with the chained setters. */
    static final class Item {
        String text = "", font = "gothic_black", align = "center";
        double size, x, y, sx = 1, sy = 1, track, lead = 1.3, rot, alpha = 1, stroke, mi;
        Color color = Color.WHITE, strokeColor;
        boolean fill = true, ghost = true, vertical, noHold, halo = true;
        String enter;   // overrides the cut's way in (the Enter name)
        double[] clip;  // only the part between these two x values is drawn
        Item plain() { ghost = false; halo = false; return this; }

        Item(String text, String font, double size) { this.text = text; this.font = font; this.size = size; }
        Item at(double x, double y) { this.x = x; this.y = y; return this; }
        Item color(Color c) { this.color = c; return this; }
        Item sx(double v) { sx = v; return this; }
        Item sy(double v) { sy = v; return this; }
        Item track(double v) { track = v; return this; }
        Item lead(double v) { lead = v; return this; }
        Item rot(double v) { rot = v; return this; }
        Item alpha(double v) { alpha = v; return this; }
        Item align(String v) { align = v; return this; }
        Item mi(double v) { mi = v; return this; }
        Item vertical(boolean v) { vertical = v; return this; }
        Item noGhost() { ghost = false; return this; }
        Item noHalo() { halo = false; return this; }
        Item outline(double w, Color c) { fill = false; stroke = w; strokeColor = c; return this; }
        Item copy() {
            Item c = new Item(text, font, size);
            c.align = align; c.x = x; c.y = y; c.sx = sx; c.sy = sy; c.track = track; c.lead = lead; c.rot = rot; c.alpha = alpha; c.stroke = stroke; c.mi = mi;
            c.color = color; c.strokeColor = strokeColor; c.fill = fill; c.ghost = ghost; c.vertical = vertical; c.noHold = noHold; c.halo = halo; c.enter = enter; c.clip = clip;
            return c;
        }
    }

    /** One laid-out character: its centre relative to the item, its box, and whether it's turned for vertical writing. */
    static final class GL {
        String ch;
        int i, li;
        double x, y, w, h, vx, vy;
        boolean r90;
    }

    static final class Layout extends ArrayList<GL> {
        double W, H;
        int N;
    }

    private static final String VERT_ROTATE = "ー〜～…―—-(（)）[「」『』【】〔〕]";

    private static double vAdv(String font, String ch, double size) { return ch.matches("[A-Za-z0-9]") ? adv(font, ch) * size : size; }

    private static List<String> chars(String s) {
        List<String> out = new ArrayList<>();
        s.codePoints().forEach(cp -> out.add(new String(Character.toChars(cp))));
        return out;
    }

    static Layout layoutText(Item it) {
        String text = it.text == null ? "" : it.text;
        double size = it.size, track = it.track;
        String[] lines = text.split("\n", -1);
        Layout out = new Layout();
        double lead = it.lead * size;
        int gi = 0;
        if (!it.vertical) {
            double[] widths = new double[lines.length];
            List<List<String>> sets = new ArrayList<>();
            for (int li = 0; li < lines.length; li++) {
                List<String> arr = chars(lines[li]);
                sets.add(arr);
                double w = 0;
                for (int i = 0; i < arr.size(); i++) w += adv(it.font, arr.get(i)) * size + (i < arr.size() - 1 ? track * size : 0);
                widths[li] = w;
            }
            double maxW = 1;
            for (double w : widths) maxW = Math.max(maxW, w);
            for (int li = 0; li < sets.size(); li++) {
                List<String> arr = sets.get(li);
                double x = it.align.equals("left") ? 0 : it.align.equals("right") ? -widths[li] : -widths[li] / 2;
                double y = (li - (lines.length - 1) / 2.0) * lead;
                for (int ci = 0; ci < arr.size(); ci++) {
                    String ch = arr.get(ci);
                    double a = adv(it.font, ch) * size;
                    GL g = new GL();
                    g.ch = ch; g.i = gi++; g.li = li; g.x = x + a / 2; g.y = y; g.w = a; g.h = size;
                    out.add(g);
                    x += a + track * size;
                }
            }
            out.W = maxW;
            out.H = lines.length * lead - (lead - size);
        } else {
            double maxH = 1;
            for (int li = 0; li < lines.length; li++) {
                List<String> arr = chars(lines[li]);
                double h = 0;
                for (String ch : arr) h += vAdv(it.font, ch, size) + track * size;
                h -= track * size;
                maxH = Math.max(maxH, h);
                double y = it.align.equals("left") ? 0 : -h / 2;
                double x = -(li - (lines.length - 1) / 2.0) * lead;
                for (String ch : arr) {
                    double a = vAdv(it.font, ch, size);
                    GL g = new GL();
                    g.ch = ch; g.i = gi++; g.li = li; g.x = x; g.y = y + a / 2; g.w = size; g.h = a;
                    g.r90 = VERT_ROTATE.contains(ch) || ch.matches("[A-Za-z0-9]");
                    if ("ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ".contains(ch)) { g.vx = 0.11 * size; g.vy = -0.11 * size; }
                    if ("、。，．".contains(ch)) { g.vx = 0.3 * size; g.vy = -0.3 * size; }
                    out.add(g);
                    y += a + track * size;
                }
            }
            out.W = lines.length * lead - (lead - size);
            out.H = maxH;
        }
        out.N = gi;
        return out;
    }

    record Measure(double w, double h) {}

    static Measure measure(Item it) {
        Layout l = layoutText(it);
        return new Measure(l.W * it.sx, l.H * it.sy);
    }

    /** The size that makes the text fit a box. */
    static double fitSize(String text, String font, double maxW, double maxH, Item opt) {
        Item probe = opt == null ? new Item(text, font, 100) : opt.copy();
        probe.text = text; probe.font = font; probe.size = 100;
        Measure m = measure(probe);
        double k = Math.min(maxW / Math.max(1, m.w), maxH / Math.max(1, m.h));
        return 100 * k;
    }

    static double fitSize(String text, String font, double maxW, double maxH) { return fitSize(text, font, maxW, maxH, null); }

    /** Long text split into balanced lines of at most about maxPer characters. */
    static String splitLines(String text, int maxPer) {
        List<String> arr = chars(text);
        if (arr.size() <= maxPer) return text;
        if (text.trim().contains(" ") && !JizuraNative.cjk(text)) {   // Latin text breaks between words only
            String[] ws = text.trim().split("\\s+");
            int total = text.trim().length();
            int nLines = Math.min(ws.length, (int) Math.ceil(total / (double) maxPer));
            if (nLines <= 1) return String.join(" ", ws);
            double ideal = total / (double) nLines;
            List<String> rows = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (String w : ws) {
                if (cur.length() > 0 && cur.length() + 1 + w.length() / 2.0 > ideal && rows.size() < nLines - 1) { rows.add(cur.toString()); cur.setLength(0); }
                if (cur.length() > 0) cur.append(' ');
                cur.append(w);
            }
            rows.add(cur.toString());
            return String.join("\n", rows);
        }
        if (!JizuraNative.cjk(text)) return text;   // one long Latin word stays whole
        int nLines = (int) Math.ceil(arr.size() / (double) maxPer);
        double per = arr.size() / (double) nLines;
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int l = 1; l < nLines; l++) {
            int target = (int) Math.round(per * l), best = target;
            double bestScore = -1;
            for (int k = Math.max(start + 1, target - 3); k <= Math.min(arr.size() - 1, target + 3); k++) {
                String a = arr.get(k - 1), b = arr.get(k);
                double s = 3 - Math.abs(k - target);
                if (a.matches("[ぁ-ゟ]") && !b.matches("[ぁ-ゟ]")) s += 3;
                if (a.matches("[、。，．,.!?！？…・ー\\s]")) s += 5;
                if ("ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ".contains(b) || "ーっ、。".contains(b)) s -= 6;
                if (s > bestScore) { bestScore = s; best = k; }
            }
            out.add(String.join("", arr.subList(start, best)).trim());
            start = best;
        }
        out.add(String.join("", arr.subList(start, arr.size())).trim());
        return String.join("\n", out);
    }

    static int glyphCount(String t) { return chars(t.replaceAll("\\s", "")).size(); }

    // ------------------------------------------------------------------ colours
    static Color a(Color c, double alpha) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * clamp(alpha))); }
    private static double lin(int v) { double s = v / 255.0; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); }
    static double contrast(Color x, Color y) {
        double a = 0.2126 * lin(x.getRed()) + 0.7152 * lin(x.getGreen()) + 0.0722 * lin(x.getBlue()), b = 0.2126 * lin(y.getRed()) + 0.7152 * lin(y.getGreen()) + 0.0722 * lin(y.getBlue());
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }
    static double lum(Color c) { return (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0; }
    static Color mix(Color x, Color y, double t) {
        return new Color((int) Math.round(lerp(x.getRed(), y.getRed(), t)), (int) Math.round(lerp(x.getGreen(), y.getGreen(), t)), (int) Math.round(lerp(x.getBlue(), y.getBlue(), t)));
    }

    // ------------------------------------------------------------------ the environment a layout draws in
    /** How one character is moved at this moment (the way in, hold and way out). */
    static final class State {
        double dx, dy, rot, sx = 1, sy = 1, alpha = 1, wipe = 1, bandDx, ghost;
        int bands;
        String ch;
    }

    /** What a layout draws with: the cut, the time into it, and the drawing calls. */
    static final class Env {
        static final double W = 1920, H = 1080;
        final java.awt.Graphics2D g;
        final JizuraNative.Cut cut;
        final JizuraNative.Style st;
        final JizuraNative.Scheme sc;
        final JizuraNative engine;
        double lt, ltb, pIn, pOut, t;
        long step;
        double ghostK = 1;

        Env(java.awt.Graphics2D g, JizuraNative engine, JizuraNative.Cut cut, double t, long step) {
            this.g = g; this.engine = engine; this.cut = cut; this.st = engine.style; this.sc = cut.sc;
            this.t = t; this.step = step;
            this.lt = t - cut.start; this.ltb = lt;
            double dur = cut.end - cut.start;
            pIn = clamp(lt / Math.max(0.01, cut.inDur));
            pOut = cut.outDur > 0 ? clamp((lt - (dur - cut.outDur)) / cut.outDur) : 0;
            ghostK = st.ghost * engine.look.ghost() / 100;
        }

        double dur() { return cut.end - cut.start; }
        Params P() { return cut.params; }

        // ---- shapes
        private void alpha(double al) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(al))); }

        void rect(double x, double y, double w, double h, Color c, double al) {
            if (c == null || al <= 0 || w <= 0 || h <= 0) return;
            alpha(al); g.setColor(c); g.fill(new java.awt.geom.Rectangle2D.Double(x, y, w, h)); alpha(1);
        }

        void line(double[][] pts, Color c, double lw, double al) {
            if (c == null || al <= 0 || pts.length < 2) return;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(pts[0][0], pts[0][1]);
            for (int i = 1; i < pts.length; i++) p.lineTo(pts[i][0], pts[i][1]);
            alpha(al); g.setColor(c); g.setStroke(new BasicStroke((float) lw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)); g.draw(p); alpha(1);
        }

        /** A filled polygon. */
        void poly(double[][] pts, Color c, double al) {
            if (c == null || al <= 0 || pts.length < 3) return;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(pts[0][0], pts[0][1]);
            for (int i = 1; i < pts.length; i++) p.lineTo(pts[i][0], pts[i][1]);
            p.closePath();
            alpha(al); g.setColor(c); g.fill(p); alpha(1);
        }

        void dashed(double[][] pts, Color c, double lw, double al, float on, float off) {
            if (c == null || al <= 0 || pts.length < 2) return;
            Path2D.Double p = new Path2D.Double();
            p.moveTo(pts[0][0], pts[0][1]);
            for (int i = 1; i < pts.length; i++) p.lineTo(pts[i][0], pts[i][1]);
            alpha(al); g.setColor(c); g.setStroke(new BasicStroke((float) lw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{on, off}, 0f)); g.draw(p); alpha(1);
        }

        void polyPartial(double[][] pts, double e, Color c, double lw, double al) {
            if (e <= 0) return;
            double L = 0;
            double[] seg = new double[pts.length - 1];
            for (int i = 1; i < pts.length; i++) { seg[i - 1] = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); L += seg[i - 1]; }
            double rem = L * clamp(e);
            List<double[]> out = new ArrayList<>();
            out.add(pts[0]);
            for (int i = 1; i < pts.length && rem > 0; i++) {
                double d = seg[i - 1];
                if (rem >= d) { out.add(pts[i]); rem -= d; }
                else { double k = rem / d; out.add(new double[]{lerp(pts[i - 1][0], pts[i][0], k), lerp(pts[i - 1][1], pts[i][1], k)}); rem = 0; }
            }
            line(out.toArray(new double[0][]), c, lw, al);
        }

        /** fill and/or stroke may be null. */
        void circle(double cx, double cy, double r, Color fill, Color stroke, double lw, double al) {
            if (r <= 0 || al <= 0 || (fill == null && stroke == null)) return;
            Ellipse2D.Double e = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            alpha(al);
            if (fill != null) { g.setColor(fill); g.fill(e); }
            if (stroke != null) { g.setColor(stroke); g.setStroke(new BasicStroke((float) lw)); g.draw(e); }
            alpha(1);
        }

        /** An arc from angle a0 to a1 (degrees, clockwise from the right, as in a canvas). */
        void arc(double cx, double cy, double r, double a0, double a1, Color c, double lw, double al) {
            if (c == null || al <= 0 || r <= 0) return;
            alpha(al); g.setColor(c); g.setStroke(new BasicStroke((float) lw));
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -a0, -(a1 - a0), Arc2D.OPEN));
            alpha(1);
        }

        void rrect(double x, double y, double w, double h, double r, Color fill, double al, Color stroke, double lw) {
            if (al <= 0 || w <= 0 || h <= 0 || (fill == null && stroke == null)) return;
            r = Math.min(r, Math.min(w, h) / 2);
            RoundRectangle2D.Double rr = new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
            alpha(al);
            if (fill != null) { g.setColor(fill); g.fill(rr); }
            if (stroke != null) { g.setColor(stroke); g.setStroke(new BasicStroke((float) lw)); g.draw(rr); }
            alpha(1);
        }

        // ---- text
        /** Plain text: no way in or out of its own; the caller sets alpha. */
        BB draw(Item it) { return drawItem(it, null); }

        /** The lyric itself: staggered by it.mi, with the cut's way in, hold and way out. */
        BB mainDraw(Item it) {
            double stagger = cut.stagger, dur = dur();
            double delay = it.mi * stagger;
            double ltI = lt - delay;
            if (ltI < 0) return null;
            double outStart = dur - cut.outDur;
            long seed = cut.seed * 31 + (long) it.mi + 1;
            final Item item = it;
            JizuraNative.Enter enter = it.enter != null ? JizuraNative.Enter.valueOf(it.enter) : cut.enter;
            Function<GL, State> fn = gl -> engine.state(this, cut, enter, item, gl, ltI, lt, outStart, seed, step);
            return drawItem(it, fn);
        }

        BB measureBox(Item it) {
            Layout l = layoutText(it);
            return new BB(it.x - l.W * it.sx / 2, it.y - l.H * it.sy / 2, it.x + l.W * it.sx / 2, it.y + l.H * it.sy / 2);
        }

        private BB drawItem(Item it, Function<GL, State> fn) {
            if (it.text == null || it.text.isEmpty() || it.size <= 0.5) return null;
            Layout lay = layoutText(it);
            double base = it.alpha;
            if (base <= 0.002) return null;
            AffineTransform item = new AffineTransform();
            item.translate(it.x, it.y);
            if (it.rot != 0) item.rotate(it.rot * DEG);
            double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
            boolean any = false;
            for (GL gl : lay) {
                if (gl.ch.isBlank()) continue;
                State s = fn == null ? new State() : fn.apply(gl);
                if (s == null) continue;
                if (s.ch == null) s.ch = gl.ch;
                // where this glyph sits (item space → design space), for the bounding box even when it's invisible
                double gx = gl.x * it.sx + gl.vx * it.sx + s.dx, gy = gl.y * it.sy + gl.vy * it.sy + s.dy;
                double hw = gl.w * it.sx * 0.5, hh = gl.h * it.sy * 0.5;
                for (int k = 0; k < 4; k++) {
                    double[] p = {gx + ((k & 1) == 0 ? -hw : hw), gy + ((k & 2) == 0 ? -hh : hh)};
                    item.transform(p, 0, p, 0, 1);
                    x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); y0 = Math.min(y0, p[1]); y1 = Math.max(y1, p[1]);
                }
                any = true;
                double al = base * s.alpha;
                if (al <= 0.01) continue;
                paint(it, gl, s, item, gx, gy, al);
            }
            return any ? new BB(x0, y0, x1, y1) : null;
        }

        private void paint(Item it, GL gl, State s, AffineTransform item, double gx, double gy, double al) {
            Object[] sh = shaped(it.font, s.ch);
            Shape outline = (Shape) sh[0];
            double advance = (Float) sh[1];
            AffineTransform at = new AffineTransform(item);
            at.translate(gx, gy);
            if (s.rot != 0) at.rotate(s.rot);
            at.scale(it.sx * s.sx, it.sy * s.sy);
            if (gl.r90) at.rotate(Math.PI / 2);
            double k = it.size / 100.0;
            at.scale(k, k);
            at.translate(-advance / 2, 36);
            Shape shape = at.createTransformedShape(outline);
            int bands = Math.max(1, s.bands);
            double bandH = it.size * 1.3 / bands;
            double[] c = {gx, gy};
            item.transform(c, 0, c, 0, 1);
            for (int b = 0; b < bands; b++) {
                java.awt.Graphics2D gg = (java.awt.Graphics2D) g.create();
                if (it.clip != null) gg.clip(new java.awt.geom.Rectangle2D.Double(it.clip[0], -1e4, Math.max(0, it.clip[1] - it.clip[0]), 2e4));
                Shape use = shape;
                if (bands > 1) {
                    gg.clip(new Rectangle((int) (c[0] - it.size * 3), (int) (c[1] - it.size * 0.65 + b * bandH), (int) (it.size * 6), (int) Math.ceil(bandH)));
                    use = AffineTransform.getTranslateInstance(((b & 1) == 0 ? 1 : -1) * s.bandDx, 0).createTransformedShape(shape);
                } else if (s.wipe < 1) {
                    gg.clip(new Rectangle((int) (c[0] - gl.w * it.sx * 0.5 - 4), (int) (c[1] - it.size), (int) Math.ceil((gl.w * it.sx + 8) * s.wipe), (int) (it.size * 2)));
                }
                double gxo = it.ghost && ghostK > 0 ? ghostK * (it.size * 0.012 + 2 + s.ghost * 2.4) : 0;
                if (gxo > 0.8) {
                    gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(0.6 * al)));
                    gg.setColor(sc.ghostA());
                    gg.fill(AffineTransform.getTranslateInstance(-gxo, 0).createTransformedShape(use));
                    gg.setColor(sc.ghostB());
                    gg.fill(AffineTransform.getTranslateInstance(gxo, gxo * 0.15).createTransformedShape(use));
                }
                gg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(al)));
                if (it.halo && it.fill) {
                    gg.setStroke(new BasicStroke((float) Math.max(6, it.size * 0.12), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gg.setColor(a(sc.bg(), 0.85));
                    gg.draw(use);
                }
                if (it.fill) { gg.setColor(it.color); gg.fill(use); }
                if (it.stroke > 0) {
                    gg.setStroke(new BasicStroke((float) it.stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gg.setColor(it.strokeColor != null ? it.strokeColor : it.color);
                    gg.draw(use);
                }
                gg.dispose();
            }
        }
    }
}
