package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.Params;
import io.github.raincandyfx.rainium.LyricEngine.Cut;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

import static io.github.raincandyfx.rainium.LyricKit.DEG;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricLayouts.def;
import static io.github.raincandyfx.rainium.LyricLayouts.fontPick;

/**
 * Compositional layouts from the reference "layoutsA" pack: telops, corner pairs, stairs, zigzags, arcs,
 * character grids, subtitle bars and news tickers (see Credits).
 * The picture is landscape, so the portrait branches of the originals are left out.
 */
final class LyricCompose {
    private LyricCompose() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    // ------------------------------------------------------------------ helpers
    static String strip(String t) { return t == null ? "" : t.replaceAll("\\s+", ""); }
    static boolean hasLatin(String t) { return t != null && t.matches("(?s).*[A-Za-z].*"); }
    static String flat(String t) { return hasLatin(t) ? t.trim().replaceAll("\\s+", " ") : strip(t); }
    static int gcount(String t) { return LyricKit.glyphCount(t); }
    static List<String> chars(String s) { List<String> o = new ArrayList<>(); s.codePoints().forEach(cp -> o.add(new String(Character.toChars(cp)))); return o; }
    static String body(Env e) { List<String> f = e.st.fonts.get("body"); return f == null || f.isEmpty() ? "gothic_med" : f.get(0); }
    static String mono(Env e) { List<String> f = e.st.fonts.get("mono"); return f == null || f.isEmpty() ? "mono" : f.get(0); }
    static String two(int n) { return String.format(Locale.ROOT, "%02d", n); }
    static String lineNo(Env e) { return two(Math.max(0, e.cut.line) + 1); }
    static String fmtTime(double t) { int cs = (int) Math.max(0, t * 100); return String.format(Locale.ROOT, "%02d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100); }
    static double tout(Env e) { return 1 - E.inCubic(e.pOut); }
    static BB box(double x0, double y0, double x1, double y1) { return new BB(x0, y0, x1, y1); }
    static double smallSize(Env e) { return clamp(U * 0.024, 14, 34); }
    static double tin(Env e, double d, double len, DoubleUnaryOperator ease) { return ease.applyAsDouble(clamp((e.lt - d) / Math.max(0.01, len))); }
    static double tin(Env e, double d, double len) { return tin(e, d, len, E::outExpo); }
    static boolean isSmallKana(String c) { return "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ".contains(c); }
    static boolean isPunct(String c) { return c.matches("[、。，．,.!?！？…‥・「」『』（）()【】〈〉《》〔〕［］\\[\\]'\"“”‘’〜～:：;；\\-—―]"); }
    static boolean isBad(String c) { return isSmallKana(c) || isPunct(c) || c.equals("ー") || c.equals(" "); }

    static LyricKit.Measure meas(String text, String font, double size, double track, double lead) {
        return LyricKit.measure(new Item(text, font, size).track(track).lead(lead));
    }
    static double fit(String text, String font, double maxW, double maxH, double track, double lead) {
        return LyricKit.fitSize(text, font, maxW, maxH, new Item("", "", 1).track(track).lead(lead));
    }
    static Item it(String text, String font, double size, double x, double y) { return new Item(text, font, size).at(x, y); }

    /** Readable text colour on a plate, preferring the scheme's own colours. */
    static Color onCol(LyricEngine.Scheme sc, Color plate) {
        double a = LyricKit.contrast(sc.bg(), plate), b = LyricKit.contrast(sc.fg(), plate);
        if (Math.max(a, b) >= 2.4) return a >= b ? sc.bg() : sc.fg();
        return LyricKit.lum(plate) > 0.5 ? new Color(0x111111) : Color.WHITE;
    }

    /** A second line of copy: the plain lyric line when it differs, else the note, else the line number. */
    static String altCopy(Env e) {
        Cut c = e.cut;
        if (c.lineText != null && !strip(c.lineText).isEmpty() && !strip(c.lineText).equals(strip(c.text))) return c.lineText;
        if (c.note != null && !c.note.isBlank()) return c.note;
        return "No." + lineNo(e);
    }

    /** Text broken into balanced lines of at most about maxPer characters. */
    static String brk(String text, int maxPer) { return LyricKit.splitLines(text.trim(), Math.max(1, maxPer)); }

    /** k chunks of the text, at word boundaries where there are any. */
    static List<String> splitK(String text, int k) {
        String t = text.trim();
        List<String> out = new ArrayList<>();
        if (k <= 1 || gcount(t) <= 1) { out.add(t); return out; }
        k = Math.min(k, gcount(t));
        for (String s : brk(t, (int) Math.ceil(gcount(t) / (double) k)).split("\n")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    /** Single glyphs, with small kana and punctuation kept on the glyph before. */
    static List<String> charUnits(String text) {
        List<String> out = new ArrayList<>();
        for (String ch : chars(strip(text))) {
            if (!out.isEmpty() && isBad(ch)) out.set(out.size() - 1, out.get(out.size() - 1) + ch);
            else out.add(ch);
        }
        return out;
    }

    static void init() {}

    static {
        def("lowerThird", "下部テロップ", n -> n <= 20,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "display", "serif")).with("side", rng.pick("left", "left", "right"))
                .with("bar", rng.pick("line", "tab", "line")).with("label", rng.pick("no", "copy", "copy")).with("lift", rng.range(0, 0.04)),
            LyricCompose::lowerThird);
        def("corners", "対角配置", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> {
                List<String> ch = splitK(cut.text, 2);
                return new Params().with("a", ch.get(0)).with("b", ch.size() > 1 ? String.join(hasLatin(ch.get(0)) ? " " : "", ch.subList(1, ch.size())) : "")
                    .with("font", fontPick(rng, st, "display", "serif")).with("diag", rng.pick("main", "main", "anti")).with("link", rng.pick("elbow", "straight", "elbow")).with("ratio", rng.pick(1.0, 1.0, 0.76));
            },
            LyricCompose::corners);
        def("staircase", "階段", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> {
                int n = gcount(cut.text);
                List<String> units = hasLatin(cut.text) ? splitK(cut.text, 4) : n <= 6 ? charUnits(cut.text) : splitK(cut.text, n <= 10 ? 3 : 4);
                return new Params().with("units", String.join("\u0001", units)).with("dir", rng.pick("down", "down", "up")).with("flip", rng.chance(0.22)).with("shrink", rng.range(0.82, 0.9))
                    .with("font", fontPick(rng, st, "display", "display", "serif")).with("tread", rng.pick("line", "line", "none")).with("nums", rng.chance(0.55));
            },
            LyricCompose::staircase);
        def("zigzag", "ジグザグ", n -> n >= 3 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("amp", rng.range(0.24, 0.34)).with("phase", rng.pick(1.0, -1.0))
                .with("rails", rng.pick("under", "both", "under", "over")).with("tilt", rng.chance(0.35)),
            LyricCompose::zigzag);
        def("arcTop", "虹の弧", n -> n >= 3 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif", "display")).with("span", rng.range(105, 145))
                .with("guide", rng.pick("double", "ticks", "double")).with("under", rng.pick("copy", "no", "copy")),
            LyricCompose::arcTop);
        def("gridCells", "升目", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("gap", rng.pick(0.0, 0.0, 0.1, 0.16)).with("acc", rng.integer(0, 99))
                .with("fill", rng.pick("outline", "outline", "ink")).with("nums", rng.chance(0.6)),
            LyricCompose::gridCells);
        def("subtitleBar", "字幕帯", n -> n <= 26,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "body", "serif")).with("bigFont", fontPick(rng, st, "display", "serif")).with("big", rng.pick("dim", "outline"))
                .with("bar", rng.range(0.1, 0.13)).with("tc", rng.pick("rec", "scene")).with("place", rng.pick("bar", "band")).with("drift", rng.pick(1.0, -1.0)),
            LyricCompose::subtitleBar);
        def("ticker", "ティッカー", n -> n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("tag", rng.pick("LIVE", "NOW", "ON AIR", "LIVE")).with("speed", rng.range(0.8, 1.3))
                .with("main", rng.pick("center", "left", "center")).with("bug", rng.chance(0.6)),
            LyricCompose::ticker);
    }

    // ================================================================== lowerThird
    private static BB lowerThird(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String t0 = env.cut.text.trim();
        int n = gcount(t0);
        String text = n > 13 ? brk(t0, (int) Math.ceil(n / 2.0)) : t0;
        double size = Math.min(fit(text, font, W * 0.84, H * 0.3, 0.03, 1.14), U * 0.14);
        var m = meas(text, font, size, 0.03, 1.14);
        boolean left = !p.s("side").equals("right");
        double mx = W * 0.07, barY = H * (0.83 - p.d("lift")), th = Math.max(3, size * 0.055);
        double cy = barY - size * 0.24 - m.h() / 2, x = left ? mx : W - mx;
        double reach = mx + m.w() + size * 0.45;
        double L = reach * tin(env, 0, 0.55) * (1 - E.inCubic(env.pOut));
        if (L > 1) {
            env.rect(left ? 0 : W - L, barY, L, th, sc.accent(), 1);
            double sq = th * 2.6, ex = left ? L : W - L;
            env.rect(ex - sq / 2, barY + th / 2 - sq / 2, sq, sq, sc.accent(), 1);
        }
        double ls = smallSize(env), la = tin(env, 0.12, 0.4, E::outCubic) * tout(env);
        if (la > 0.01) {
            double ly = cy - m.h() / 2 - size * 0.22 - ls * 0.7, sl = (1 - la) * ls * 2 * (left ? -1 : 1);
            String tag = lineNo(env);
            String copy = p.s("label").equals("no") ? "LINE " + tag : altCopy(env);
            if (copy.startsWith("No.")) copy = fmtTime(env.cut.start);
            var tm = meas(tag, mono(env), ls, 0.1, 1.3);
            double cx = x + sl, dirn = left ? 1 : -1;
            if (p.s("bar").equals("tab")) {
                double tw = tm.w() + ls * 1.1, th2 = ls * 1.6;
                env.rect(left ? cx : cx - tw, ly - th2 / 2, tw, th2, sc.ink(), la);
                env.draw(it(tag, mono(env), ls, left ? cx + tw / 2 : cx - tw / 2, ly).track(0.1).color(onCol(sc, sc.ink())).alpha(la).noGhost());
                cx += (tw + ls * 0.7) * dirn;
            } else {
                double q = ls * 0.55;
                env.rect(left ? cx : cx - q, ly - q / 2, q, q, sc.accent(), la);
                cx += (q + ls * 0.6) * dirn;
                env.draw(it(tag, mono(env), ls, cx, ly).track(0.1).align(left ? "left" : "right").color(sc.fg()).alpha(la).noGhost());
                cx += (tm.w() + ls * 0.8) * dirn;
            }
            env.draw(it(copy, p.s("label").equals("copy") ? body(env) : mono(env), ls, cx, ly).track(0.12).align(left ? "left" : "right").color(sc.sub()).alpha(la).noGhost());
        }
        BB bb = env.mainDraw(it(text, font, size, x, cy).align(left ? "left" : "right").track(0.03).lead(1.14).color(sc.fg()));
        return bb != null ? bb : box(left ? x : x - m.w(), cy - m.h() / 2, left ? x + m.w() : x, cy + m.h() / 2);
    }

    // ================================================================== corners
    private static BB corners(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String a0 = p.s("a"), b0 = p.s("b");
        String A = brk(a0, 9), B = b0.isEmpty() ? "" : brk(b0, 9);
        double bw = W * 0.58, bh = H * 0.3, ratio = p.d("ratio") == 0 ? 1 : p.d("ratio");
        double s = Math.min(fit(A, font, bw, bh, 0.02, 1.1), !B.isEmpty() ? fit(B, font, bw, bh, 0.02, 1.1) / ratio : 1e9);
        s = Math.min(s, U * 0.22);
        double sA = s, sB = s * ratio;
        var mA = meas(A, font, sA, 0.02, 1.1);
        var mB = B.isEmpty() ? new LyricKit.Measure(0, 0) : meas(B, font, sB, 0.02, 1.1);
        double mx = W * 0.075, my = H * 0.13;
        boolean aL = !p.s("diag").equals("anti");
        double ax = aL ? mx : W - mx, ay = my + mA.h() / 2, bx = aL ? W - mx : mx, by = H - my - mB.h() / 2;
        BB Ab = box(aL ? ax : ax - mA.w(), ay - mA.h() / 2, aL ? ax + mA.w() : ax, ay + mA.h() / 2);
        BB Bb = box(aL ? bx - mB.w() : bx, by - mB.h() / 2, aL ? bx : bx + mB.w(), by + mB.h() / 2);
        double g = U * 0.028;
        double[][] pts;
        if (p.s("link").equals("elbow")) {
            double xb = aL ? Bb.x0() + sB * 0.5 : Bb.x1() - sB * 0.5;
            boolean clear = aL ? xb > Ab.x1() + g * 2 : xb < Ab.x0() - g * 2;
            if (clear) pts = new double[][]{{aL ? Ab.x1() + g : Ab.x0() - g, Ab.cy()}, {xb, Ab.cy()}, {xb, Bb.y0() - g}};
            else { double xm = aL ? Math.max(Ab.x0(), Bb.x0()) + sB * 0.5 : Math.min(Ab.x1(), Bb.x1()) - sB * 0.5; pts = new double[][]{{xm, Ab.y1() + g}, {xm, Bb.y0() - g}}; }
        } else pts = new double[][]{{aL ? Ab.x1() : Ab.x0(), Ab.y1() + g}, {aL ? Bb.x0() : Bb.x1(), Bb.y0() - g}};
        double e = tin(env, env.cut.inDur * 0.5, 0.55, E::inOutCubic) * (1 - E.inCubic(env.pOut));
        double lw = Math.max(1.5, U * 0.0022);
        if (e > 0) {
            env.polyPartial(pts, e, sc.sub(), lw, 1);
            env.circle(pts[0][0], pts[0][1], U * 0.007, sc.bg(), sc.sub(), lw, Math.min(1, e * 4));
            if (e > 0.97) { double[] z = pts[pts.length - 1]; env.circle(z[0], z[1], U * 0.007, sc.accent(), null, 0, 1); }
            int bi = 1; double bl = 0;
            for (int i = 1; i < pts.length; i++) { double l = Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); if (l > bl) { bl = l; bi = i; } }
            double[] q0 = pts[bi - 1], q1 = pts[bi];
            double ls = smallSize(env) * 0.9, mxp = (q0[0] + q1[0]) / 2, myp = (q0[1] + q1[1]) / 2, ca = clamp((e - 0.5) * 3);
            boolean horiz = Math.abs(q1[1] - q0[1]) < Math.abs(q1[0] - q0[0]) * 0.3;
            String cap = "No." + lineNo(env);
            if (ca > 0 && bl > ls * 8) {
                if (horiz) env.draw(it(cap, mono(env), ls, mxp, myp - ls * 1.1).track(0.1).color(sc.sub()).alpha(ca).noGhost());
                else env.draw(it(cap, mono(env), ls, mxp + (aL ? ls * 0.8 : -ls * 0.8) * (p.s("link").equals("straight") ? -1 : 1), myp).track(0.1).align(aL ? "left" : "right").color(sc.sub()).alpha(ca).noGhost());
            }
        }
        int nA = gcount(a0);
        BB bb = env.mainDraw(it(A, font, sA, ax, ay).align(aL ? "left" : "right").track(0.02).lead(1.1).color(sc.fg()).mi(0));
        if (!B.isEmpty()) bb = BB.union(bb, env.mainDraw(it(B, font, sB, bx, by).align(aL ? "right" : "left").track(0.02).lead(1.1).color(sc.fg()).mi(Math.min(8, nA + 2))));
        return bb != null ? bb : box(Math.min(Ab.x0(), Bb.x0()), Ab.y0(), Math.max(Ab.x1(), Bb.x1()), Bb.y1());
    }

    // ================================================================== staircase
    private static BB staircase(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String[] units = p.s("units").isEmpty() ? new String[]{strip(env.cut.text)} : p.s("units").split("\u0001");
        int k = units.length;
        double r = k > 1 ? Math.max(p.d("shrink") == 0 ? 0.86 : p.d("shrink"), Math.pow(0.5, 1.0 / (k - 1))) : 1;
        boolean up = p.s("dir").equals("up");
        double fx = up ? 1 : 0.92;
        double[] qx = new double[k], qy = new double[k], qs = new double[k], qw = new double[k];
        for (int i = 0; i < k; i++) {
            double s = 100 * Math.pow(r, i), w = meas(units[i], font, s, 0.02, 1.3).w();
            double x = 0, y = 0;
            if (i > 0) { x = qx[i - 1] + qw[i - 1] * fx + qs[i - 1] * 0.14; y = qy[i - 1] + (qs[i - 1] + s) / 2 * 1.05 * (up ? -1 : 1); }
            qx[i] = x; qy[i] = y; qs[i] = s; qw[i] = w;
        }
        double x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
        for (int i = 0; i < k; i++) { x0 = Math.min(x0, qx[i]); x1 = Math.max(x1, qx[i] + qw[i]); y0 = Math.min(y0, qy[i] - qs[i] / 2); y1 = Math.max(y1, qy[i] + qs[i] * 0.62); }
        double k2 = Math.min(Math.min(W * 0.86 / (x1 - x0), H * 0.78 / (y1 - y0)), U * 0.3 / 100);
        double ox = W / 2 - (x0 + x1) / 2 * k2, oy = H / 2 - (y0 + y1) / 2 * k2;
        boolean flip = p.b("flip");
        boolean tread = !p.s("tread").equals("none"), nums = p.b("nums");
        if (tread || nums) {
            double e = tin(env, 0, Math.max(0.5, env.cut.inDur * 1.6), E::inOutCubic) * (1 - E.inCubic(env.pOut));
            double lw = Math.max(2, 100 * k2 * 0.022);
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                double X = ox + qx[i] * k2, Y = oy + qy[i] * k2, yb = Y + qs[i] * k2 * 0.58, gap = qs[i] * k2 * 0.14;
                if (i == 0) pts.add(new double[]{X - gap, yb});
                if (i < k - 1) { double xr = ox + qx[i + 1] * k2 - gap / 2; pts.add(new double[]{xr, yb}); pts.add(new double[]{xr, oy + qy[i + 1] * k2 + qs[i + 1] * k2 * 0.58}); }
                else pts.add(new double[]{X + qw[i] * k2 + gap, yb});
            }
            if (tread && e > 0) {
                double[][] arr = new double[pts.size()][];
                for (int i = 0; i < arr.length; i++) arr[i] = new double[]{flip ? W - pts.get(i)[0] : pts.get(i)[0], pts.get(i)[1]};
                env.polyPartial(arr, e, sc.accent(), lw, 1);
            }
            if (nums && e > 0) {
                double ls = smallSize(env) * 0.85;
                for (int i = 0; i < k; i++) {
                    double a = clamp((e * k - i) * 1.5);
                    if (a <= 0) continue;
                    double X = ox + qx[i] * k2, yb = oy + qy[i] * k2 + qs[i] * k2 * 0.58;
                    env.draw(it(two(i + 1), mono(env), ls, flip ? W - X : X, yb + ls * 1.1).align(flip ? "right" : "left").color(sc.sub()).alpha(a).noGhost());
                }
            }
        }
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double s = qs[i] * k2, xl = ox + qx[i] * k2;
            bb = BB.union(bb, env.mainDraw(it(units[i], font, s, flip ? W - xl - qw[i] * k2 : xl, oy + qy[i] * k2).align("left").track(0.02).color(sc.fg()).mi(i * 2)));
        }
        return bb != null ? bb : box(W / 2 - (x1 - x0) * k2 / 2, H / 2 - (y1 - y0) * k2 / 2, W / 2 + (x1 - x0) * k2 / 2, H / 2 + (y1 - y0) * k2 / 2);
    }

    // ================================================================== zigzag
    private static BB zigzag(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        List<String> cs = chars(hasLatin(env.cut.text) ? env.cut.text.trim().replaceAll("\\s+", " ") : strip(env.cut.text));
        int n = cs.size();
        double size = Math.min(W * 0.8 / n / 1.08, U * 0.2), step = size * 1.08, A = size * p.d("amp"), phase = p.d("phase") == 0 ? 1 : p.d("phase");
        java.util.function.IntFunction<double[]> pos = i -> { double s = (Math.floorMod(i, 2) == 1 ? 1 : -1) * phase, k = i - (n - 1) / 2.0; return new double[]{W / 2 + k * step, H / 2 + s * A}; };
        double off = size * (0.52 + p.d("amp") * 0.9);
        double e = tin(env, 0, Math.max(0.45, env.cut.inDur * 1.5), E::inOutCubic) * (1 - E.inCubic(env.pOut));
        if (e > 0) {
            double lw = Math.max(2, size * 0.03), lim = W * 0.04;
            int ext = (int) Math.ceil((W / 2 - n * step / 2 - lim) / step);
            String rails = p.s("rails");
            int[] sides = rails.equals("both") ? new int[]{1, -1} : rails.equals("over") ? new int[]{-1} : new int[]{1};
            for (int si = 0; si < sides.length; si++) {
                List<double[]> pts = new ArrayList<>();
                for (int i = -ext; i <= n - 1 + ext; i++) {
                    double[] q = pos.apply(i);
                    if (q[0] < lim || q[0] > W - lim) continue;
                    pts.add(new double[]{q[0], q[1] + sides[si] * off});
                }
                if (pts.size() < 2) continue;
                Color col = si == 0 ? sc.accent() : sc.sub();
                env.polyPartial(pts.toArray(new double[0][]), e, col, lw, 1);
                env.circle(pts.get(0)[0], pts.get(0)[1], lw * 2.2, col, null, 0, 1);
                if (e > 0.98) { double[] tip = pts.get(Math.min(pts.size() - 1, (int) Math.floor(e * (pts.size() - 1)))); env.circle(tip[0], tip[1], lw * 2.2, col, null, 0, 1); }
            }
        }
        BB bb = null;
        for (int i = 0; i < n; i++) {
            String ch = cs.get(i);
            if (ch.isBlank()) continue;
            double[] q = pos.apply(i);
            double rot = p.b("tilt") ? (Math.floorMod(i, 2) == 1 ? 1 : -1) * phase * 6 : 0;
            bb = BB.union(bb, env.mainDraw(it(ch, font, size, q[0], q[1]).rot(rot).color(sc.fg()).mi(i)));
        }
        return bb != null ? bb : box(W / 2 - step * n / 2, H / 2 - A - size / 2, W / 2 + step * n / 2, H / 2 + A + size / 2);
    }

    // ================================================================== arcTop
    private static BB arcTop(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        List<String> cs = chars(env.cut.text.trim());
        int n = cs.size();
        double spanDeg = Math.min(p.d("span"), Math.max(50, n * 30));
        double half = spanDeg / 2 * DEG, dth = spanDeg * DEG / n, k = dth / 1.1;
        double wid = half < Math.PI / 2 ? 2 * Math.sin(half) : 2;
        boolean hollow = half >= 70 * DEG;
        double R = Math.min(Math.min(W * 0.86 / (wid + k), H * 0.66 / (1 - Math.cos(half) + k * 1.3 + (hollow ? 0 : 0.18))), U * 0.21 / k);
        double size = R * k, ls = smallSize(env);
        double topOff = -(R + size * 0.8), endY = -R * Math.cos(half) + size * 0.55;
        double capY = hollow ? Math.max(0, -R * Math.cos(half) - R * 0.1) : endY + ls * 1.6;
        double botOff = Math.max(endY, capY + ls * 2.2);
        double cx = W / 2, cy = H / 2 - (topOff + botOff) / 2;
        double e = tin(env, 0, 0.7, E::inOutCubic) * (1 - E.inCubic(env.pOut));
        double lw = Math.max(1.2, size * 0.018);
        double a0 = -90 - spanDeg / 2 - 3, a1 = -90 + spanDeg / 2 + 3;
        if (e > 0) {
            double sp = (a1 - a0) / 2 * e;
            if (p.s("guide").equals("double")) {
                env.arc(cx, cy, R + size * 0.74, -90 - sp, -90 + sp, sc.sub(), lw, 0.9);
                env.arc(cx, cy, R - size * 0.72, -90 - sp, -90 + sp, sc.sub(), lw, 0.6);
            } else {
                env.arc(cx, cy, R - size * 0.72, -90 - sp, -90 + sp, sc.sub(), lw, 0.8);
                for (int i = 0; i <= n; i++) {
                    double th = -90 + (i - n / 2.0) * spanDeg / n;
                    if (Math.abs(th + 90) > sp + 0.01) continue;
                    double c = Math.cos(th * DEG), s = Math.sin(th * DEG), r0 = R - size * 0.72, r1 = r0 - size * (i % 2 == 1 ? 0.12 : 0.22);
                    env.line(new double[][]{{cx + c * r0, cy + s * r0}, {cx + c * r1, cy + s * r1}}, sc.sub(), lw, 0.8);
                }
            }
            double rr = R + size * 0.74;
            env.circle(cx + Math.cos((-90 - sp) * DEG) * rr, cy + Math.sin((-90 - sp) * DEG) * rr, size * 0.05, sc.accent(), null, 0, 1);
            env.circle(cx + Math.cos((-90 + sp) * DEG) * rr, cy + Math.sin((-90 + sp) * DEG) * rr, size * 0.05, sc.accent(), null, 0, 1);
        }
        double ca = tin(env, 0.25, 0.4, E::outCubic) * tout(env);
        if (ca > 0.01) {
            String copy = p.s("under").equals("no") ? "No." + lineNo(env) + "  —  " + fmtTime(env.cut.start) : altCopy(env);
            double maxW = hollow ? 2 * (R - size) * 0.8 : W * 0.7;
            double cs2 = Math.min(ls * 1.15, LyricKit.fitSize(copy, body(env), maxW, ls * 2, new Item("", "", 1).track(0.12)));
            env.draw(it(copy, body(env), cs2, cx, cy + capY).track(0.12).color(sc.sub()).alpha(ca).noGhost());
            double rw = Math.min(maxW * 0.5, size * 1.4) * ca;
            env.line(new double[][]{{cx - rw / 2, cy + capY + cs2 * 1.1}, {cx + rw / 2, cy + capY + cs2 * 1.1}}, sc.accent(), Math.max(2, lw * 1.4), ca);
        }
        BB bb = null;
        for (int i = 0; i < n; i++) {
            String ch = cs.get(i);
            if (ch.isBlank()) continue;
            double th = -90 + (i - (n - 1) / 2.0) * spanDeg / n;
            bb = BB.union(bb, env.mainDraw(it(ch, font, size, cx + Math.cos(th * DEG) * R, cy + Math.sin(th * DEG) * R).rot(th + 90).color(sc.fg()).mi(i)));
        }
        return bb != null ? bb : box(cx - R * wid / 2 - size / 2, cy - R - size / 2, cx + R * wid / 2 + size / 2, cy + endY);
    }

    // ================================================================== gridCells
    private static BB gridCells(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        List<String> cs = chars(strip(env.cut.text));
        int n = cs.size();
        int maxCols = n <= 10 ? 10 : 8;
        int rows = (int) Math.ceil(n / (double) Math.min(n, maxCols)), cols = (int) Math.ceil(n / (double) rows);
        double g = p.d("gap");
        double cell = Math.min(Math.min(W * 0.86 / (cols + (cols - 1) * g), H * 0.72 / (rows + (rows - 1) * g)), U * 0.3), stp = cell * (1 + g);
        double gw = cols * cell + (cols - 1) * cell * g, gh = rows * cell + (rows - 1) * cell * g, x0 = W / 2 - gw / 2, y0 = H / 2 - gh / 2;
        List<Integer> kan = new ArrayList<>(), ok = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String ch = cs.get(i);
            if (LyricKit.isKanji(ch)) kan.add(i);
            if (!isPunct(ch) && !isSmallKana(ch)) ok.add(i);
        }
        int acc = p.i("acc");
        int ai = !kan.isEmpty() ? kan.get(acc % kan.size()) : !ok.isEmpty() ? ok.get(acc % ok.size()) : acc % n;
        double out = tout(env), lw = Math.max(1.5, cell * 0.012);
        boolean plate = p.s("fill").equals("ink");
        int total = rows * cols;
        for (int i = 0; i < total; i++) {
            double cx = x0 + (i % cols) * stp, cy = y0 + (i / cols) * stp, d = i * 0.03;
            double e = tin(env, d, 0.35, E::inOutCubic) * out;
            if (e <= 0) continue;
            boolean empty = i >= n;
            if (i == ai || (plate && !empty)) {
                double q = E.outBack(clamp((env.lt - d - 0.08) / 0.28), 1.6) * out;
                if (q > 0) env.rect(cx + cell / 2 * (1 - q), cy + cell / 2 * (1 - q), cell * q, cell * q, i == ai ? sc.accent() : sc.ink(), 1);
            }
            double[][] loop = {{cx, cy}, {cx + cell, cy}, {cx + cell, cy + cell}, {cx, cy + cell}, {cx, cy}};
            env.polyPartial(loop, e, empty ? sc.sub() : sc.fg(), lw, empty ? 0.35 : 0.9);
            if (p.b("nums") && !empty) {
                double ns = Math.max(10, cell * 0.1);
                env.draw(it(two(i + 1), mono(env), ns, cx + ns * 0.6, cy + ns * 1.1).align("left")
                    .color(i == ai ? onCol(sc, sc.accent()) : plate ? onCol(sc, sc.ink()) : sc.sub()).alpha(clamp(e * 1.5 - 0.5) * 0.85).noGhost());
            }
        }
        BB bb = null;
        for (int i = 0; i < n; i++) {
            double cx = x0 + (i % cols) * stp + cell / 2, cy = y0 + (i / cols) * stp + cell / 2;
            Color col = i == ai ? onCol(sc, sc.accent()) : plate ? onCol(sc, sc.ink()) : sc.fg();
            bb = BB.union(bb, env.mainDraw(it(cs.get(i), font, cell * 0.64, cx, cy + cell * 0.02).color(col).mi(i)));
        }
        return bb != null ? bb : box(x0, y0, x0 + gw, y0 + gh);
    }

    // ================================================================== subtitleBar
    private static BB subtitleBar(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"), bigFont = p.s("bigFont");
        String text = env.cut.text.trim();
        double lb = env.ltb;
        Color barC = sc.bg();
        for (Color c : new Color[]{sc.bg(), sc.fg(), sc.ink(), sc.dim()}) if (LyricKit.lum(c) < LyricKit.lum(barC)) barC = c;
        boolean pic = barC.equals(sc.bg()) && Math.abs(LyricKit.lum(sc.dim()) - LyricKit.lum(sc.bg())) > 0.03;
        double bh = H * p.d("bar"), e = tin(env, 0, 0.55) * (1 - E.inCubic(env.pOut)), out = tout(env), hh0 = bh * e;
        if (pic && hh0 > 0.5) env.rect(-2, hh0, W + 4, H - hh0 * 2, sc.dim(), clamp(e * 1.4));
        String bt = flat(text);
        double bs = Math.min(LyricKit.fitSize(bt, bigFont, W * 0.94, H * 0.6), H * 0.6), ba = tin(env, 0.05, 0.6, E::outCubic) * out;
        if (ba > 0.01) {
            double bx = W / 2 + (lb / Math.max(1, env.dur()) - 0.5) * W * 0.03 * p.d("drift");
            if (p.s("big").equals("outline")) env.draw(it(bt, bigFont, bs, bx, H / 2).outline(Math.max(1.2, bs * 0.006), sc.sub()).alpha(0.4 * ba).noGhost());
            else env.draw(it(bt, bigFont, bs, bx, H / 2).color(pic ? sc.bg() : sc.dim()).alpha(ba * (pic ? 0.7 : 1)).noGhost());
        }
        double hh = bh * e;
        if (hh > 0.5) {
            env.rect(-2, -2, W + 4, hh + 2, barC, 1);
            env.rect(-2, H - hh, W + 4, hh + 2, barC, 1);
            env.line(new double[][]{{0, hh}, {W, hh}}, sc.sub(), 1.2, 0.35);
            env.line(new double[][]{{0, H - hh}, {W, H - hh}}, sc.sub(), 1.2, 0.35);
        }
        double ls = Math.min(smallSize(env) * 0.9, bh * 0.3), ta = clamp((e - 0.6) * 2.5) * out;
        if (ta > 0) {
            double y = hh / 2;
            if (p.s("tc").equals("rec")) {
                if (env.step % 2 == 0) env.circle(W * 0.05, y, ls * 0.35, sc.accent(), null, 0, ta);
                env.draw(it("REC", mono(env), ls, W * 0.05 + ls * 0.8, y).track(0.12).align("left").color(onCol(sc, barC)).alpha(ta).noGhost());
            } else env.draw(it("SCENE " + lineNo(env) + "  /  CUT " + lineNo(env), mono(env), ls, W * 0.05, y).track(0.12).align("left").color(onCol(sc, barC)).alpha(ta).noGhost());
            env.draw(it(fmtTime(env.t), mono(env), ls, W * 0.95, y).track(0.08).align("right").color(sc.sub()).alpha(ta).noGhost());
        }
        boolean bar = p.s("place").equals("bar");
        double maxH = bar ? bh * 0.62 : bh * 0.8;
        String t2 = text;
        double ss = Math.min(fit(t2, font, W * 0.84, maxH, 0.06, 1.25), U * 0.058);
        if (ss < U * 0.04 && gcount(text) > 8) { t2 = brk(text, (int) Math.ceil(gcount(text) / 2.0)); ss = Math.min(fit(t2, font, W * 0.84, maxH * 1.5, 0.06, 1.25), U * 0.05); }
        var m = meas(t2, font, ss, 0.06, 1.25);
        double sy;
        Color scol = onCol(sc, barC);
        if (bar) sy = H - bh / 2;
        else {
            sy = H - bh - m.h() / 2 - ss * 0.9;
            double pa = clamp((e - 0.3) * 2) * out;
            if (pa > 0) env.rect(W / 2 - m.w() / 2 - ss * 0.8, sy - m.h() / 2 - ss * 0.35, m.w() + ss * 1.6, m.h() + ss * 0.7, barC, 0.72 * pa);
        }
        BB bb = env.mainDraw(it(t2, font, ss, W / 2, sy).track(0.06).lead(1.25).color(scol));
        return bb != null ? bb : box(W / 2 - m.w() / 2, sy - m.h() / 2, W / 2 + m.w() / 2, sy + m.h() / 2);
    }

    // ================================================================== ticker
    private static BB ticker(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String text0 = env.cut.text.trim();
        double bandH = U * 0.075, bandY = H - bandH - H * 0.085;
        double out = 1 - E.inCubic(env.pOut), eB = tin(env, 0, 0.5) * out;
        Color tagC = sc.accent(), bandC = sc.ink().equals(sc.accent()) ? sc.fg() : sc.ink();
        double ts = bandH * 0.42, tagW = meas(p.s("tag"), mono(env), ts, 0.12, 1.3).w() + ts * 2.6, x0 = W * 0.04;
        double bw = (W - x0) * eB;
        if (bw > 1) {
            env.rect(x0, bandY, bw, bandH, bandC, 1);
            String unit = flat(env.cut.lineText == null || env.cut.lineText.isBlank() ? text0 : env.cut.lineText) + "　◆　";
            double fs = bandH * 0.46, per = Math.max(1, meas(unit, body(env), fs, 0, 1.3).w());
            int reps = (int) Math.min(40, Math.ceil(W * 1.2 / per) + 2);
            double off = ((env.ltb * p.d("speed") * U * 0.22) % per + per) % per;
            Item row = it(unit.repeat(reps), body(env), fs, x0 + tagW + ts * 0.8 - off, bandY + bandH / 2).align("left").color(onCol(sc, bandC)).noGhost().noHalo();
            row.clip = new double[]{x0 + tagW, x0 + bw};
            env.draw(row);
        }
        double tq = E.outBack(clamp((env.lt - 0.08) / 0.3), 1.6) * out;
        if (tq > 0.01) {
            double th = bandH * tq;
            env.rect(x0, bandY + bandH / 2 - th / 2 - bandH * 0.12 * tq, tagW, th + bandH * 0.24 * tq, tagC, 1);
            Color tc = onCol(sc, tagC);
            if (env.step % 3 != 0) env.circle(x0 + ts * 0.95, bandY + bandH / 2, ts * 0.28 * tq, tc, null, 0, 1);
            env.draw(it(p.s("tag"), mono(env), ts * tq, x0 + ts * 1.6, bandY + bandH / 2).track(0.12).align("left").color(tc).noGhost());
        }
        double ca = clamp((eB - 0.7) * 3.3);
        if (ca > 0) {
            String tt = fmtTime(env.t);
            double tsz = ts * 0.9, tw = meas(tt, mono(env), tsz, 0.08, 1.3).w() + tsz * 1.4;
            env.rect(W - W * 0.04 - tw, bandY - tsz * 1.7, tw, tsz * 1.7, sc.fg(), ca);
            env.draw(it(tt, mono(env), tsz, W - W * 0.04 - tw / 2, bandY - tsz * 0.85).track(0.08).color(onCol(sc, sc.fg())).alpha(ca).noGhost());
        }
        if (p.b("bug")) {
            double a = tin(env, 0.2, 0.4, E::outCubic) * out, bs = smallSize(env) * 0.85;
            env.draw(it("CH." + lineNo(env), mono(env), bs, W * 0.95, H * 0.07).track(0.2).align("right").color(sc.sub()).alpha(a).noGhost());
            env.rect(W * 0.95 - bs * 0.3, H * 0.07 + bs * 0.9, bs * 0.3, bs * 0.3, sc.accent(), a);
        }
        boolean left = p.s("main").equals("left");
        String text = brk(text0, 11);
        double avail = bandY - H * 0.1;
        double size = Math.min(fit(text, font, W * 0.84, avail * 0.7, 0.03, 1.15), U * 0.2);
        var m = meas(text, font, size, 0.03, 1.15);
        double y = left ? bandY - bandH * 0.55 - m.h() / 2 - size * 0.2 : H * 0.1 + avail / 2, x = left ? x0 + size * 0.1 : W / 2;
        if (left) { double le = tin(env, 0.1, 0.5) * out; env.rect(x0, y - m.h() / 2 - size * 0.28, Math.max(0, size * 1.2 * le), Math.max(3, size * 0.06), sc.accent(), 1); }
        BB bb = env.mainDraw(it(text, font, size, x, y).align(left ? "left" : "center").track(0.03).lead(1.15).color(sc.fg()));
        return bb != null ? bb : box(left ? x : x - m.w() / 2, y - m.h() / 2, left ? x + m.w() : x + m.w() / 2, y + m.h() / 2);
    }
}
