package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.JizuraKit.BB;
import io.github.raincandyfx.rainium.JizuraKit.E;
import io.github.raincandyfx.rainium.JizuraKit.Env;
import io.github.raincandyfx.rainium.JizuraKit.GL;
import io.github.raincandyfx.rainium.JizuraKit.Item;
import io.github.raincandyfx.rainium.JizuraKit.Params;
import io.github.raincandyfx.rainium.JizuraNative.Cut;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.raincandyfx.rainium.JizuraKit.DEG;
import static io.github.raincandyfx.rainium.JizuraKit.TAU;
import static io.github.raincandyfx.rainium.JizuraKit.clamp;
import static io.github.raincandyfx.rainium.JizuraKit.lerp;
import static io.github.raincandyfx.rainium.JizuraKit.r;
import static io.github.raincandyfx.rainium.JizuraLayouts.def;
import static io.github.raincandyfx.rainium.JizuraLayouts.fontPick;

/**
 * JIZURA's kinetic layouts (word-timed stacks, turns, swaps, dives, flows, cuts and so on), ported from
 * its source (https://github.com/852wa/JIZURA, MIT, (c) 2026 hakoniwa). The picture is landscape, so the
 * portrait branches of the originals are left out.
 */
final class JizuraKinetic {
    private JizuraKinetic() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    // ------------------------------------------------------------------ helpers
    private static String strip(String t) { return t == null ? "" : t.replaceAll("\\s+", ""); }
    private static boolean hasLatin(String t) { return t != null && t.matches("(?s).*[A-Za-z].*"); }
    private static String flat(String t) { return hasLatin(t) ? t.trim().replaceAll("\\s+", " ") : strip(t); }
    private static int gcount(String t) { return JizuraKit.glyphCount(t); }
    private static List<String> chars(String s) { List<String> o = new ArrayList<>(); s.codePoints().forEach(cp -> o.add(new String(Character.toChars(cp)))); return o; }
    private static String body(Env e) { List<String> f = e.st.fonts.get("body"); return f == null || f.isEmpty() ? "gothic_med" : f.get(0); }
    private static String mono(Env e) { List<String> f = e.st.fonts.get("mono"); return f == null || f.isEmpty() ? "mono" : f.get(0); }
    private static double tout(Env e) { return 1 - E.inCubic(e.pOut); }
    private static BB box(double x0, double y0, double x1, double y1) { return new BB(x0, y0, x1, y1); }
    private static BB ub(BB a, BB b) { return BB.union(a, b); }
    private static double miAt(Env e, double t) { return Math.max(0, t) / Math.max(0.005, e.cut.stagger); }
    private static double[] rotV(double x, double y, double a) { double c = Math.cos(a * DEG), s = Math.sin(a * DEG); return new double[]{x * c - y * s, x * s + y * c}; }
    private static double sprg(double t, double k, double f) { return t <= 0 ? 1 : Math.exp(-k * t) * Math.cos(f * t); }
    private static double bellK(double k) { return Math.sin(Math.PI * clamp(k)); }
    private static Color accOn(JizuraNative.Scheme sc) { return JizuraKit.contrast(sc.accent(), sc.bg()) >= 1.7 ? sc.accent() : sc.fg(); }

    private static Color onCol(JizuraNative.Scheme sc, Color fill) {
        Color best = null;
        double bv = 0;
        for (Color c : new Color[]{sc.bg(), sc.fg(), sc.ink(), sc.accent(), sc.sub()}) {
            if (c == null || c.equals(fill)) continue;
            double k = JizuraKit.contrast(c, fill);
            if (k > bv) { bv = k; best = c; }
        }
        return bv >= 2.6 ? best : JizuraKit.lum(fill) > 0.5 ? new Color(0x111111) : Color.WHITE;
    }

    private static Item it(String text, String font, double size, double x, double y) { return new Item(text, font, size).at(x, y); }

    private static JizuraKit.Measure meas(String text, String font, double size, double track, double lead) {
        return JizuraKit.measure(new Item(text, font, size).track(track).lead(lead));
    }
    private static double em(String text, String font, double track) { return meas(text, font, 100, track, 1.3).w() / 100; }
    private static double fit(String text, String font, double maxW, double maxH, double track) { return JizuraKit.fitSize(text, font, maxW, maxH, new Item("", "", 1).track(track)); }

    /** The cut's word chunks, merged or split to a usable count. */
    private static List<String> unitsOf(Cut cut, int maxU, int minU) {
        String text = cut.text == null ? "" : cut.text;
        boolean lat = hasLatin(text);
        List<String> w = new ArrayList<>();
        for (String s : cut.words) if (!s.trim().isEmpty()) w.add(s.trim());
        if (!strip(String.join("", w)).equals(strip(text))) { w.clear(); if (!text.trim().isEmpty()) w.addAll(Arrays.asList(text.trim().split(lat ? "\\s+" : "(?!)"))); }
        if (w.isEmpty()) w.add(text.trim().isEmpty() ? "…" : text.trim());
        while (w.size() > maxU) {
            int bi = 0, bv = 1 << 30;
            for (int i = 0; i < w.size() - 1; i++) { int v = gcount(w.get(i)) + gcount(w.get(i + 1)); if (v < bv) { bv = v; bi = i; } }
            String merged = w.get(bi) + (lat ? " " : "") + w.get(bi + 1);
            w.remove(bi + 1); w.set(bi, merged);
        }
        for (int g = 0; g < 8 && w.size() < minU; g++) {
            int bi = -1, bv = 1;
            for (int i = 0; i < w.size(); i++) {
                String s = w.get(i);
                int n = gcount(s);
                boolean ok = s.matches("(?s).*\\s.*") || (!hasLatin(s) && n >= 2);
                if (ok && n > bv) { bv = n; bi = i; }
            }
            if (bi < 0) break;
            String[] parts = splitUnit(w.get(bi));
            if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) break;
            w.remove(bi); w.add(bi, parts[1]); w.add(bi, parts[0]);
        }
        return w;
    }

    private static String[] splitUnit(String s) {
        String t = s.trim();
        if (t.matches("(?s).*\\s.*")) {
            double mid = t.length() / 2.0, bd = 1e9;
            int bi = -1;
            for (int i = 0; i < t.length(); i++) if (t.charAt(i) == ' ' && Math.abs(i - mid) < bd) { bd = Math.abs(i - mid); bi = i; }
            return new String[]{t.substring(0, bi).trim(), t.substring(bi + 1).trim()};
        }
        int n = chars(t).size();
        String[] parts = JizuraKit.splitLines(t, (int) Math.ceil(n / 2.0)).split("\n");
        return parts.length >= 2 ? new String[]{parts[0], String.join("", Arrays.copyOfRange(parts, 1, parts.length))} : new String[]{t};
    }

    /** When each word comes in, spread over the first part of the cut. */
    private static double[] onsets(Env env, int n, double frac, double gap) {
        double dur = env.dur(), last = Math.max(0, Math.min(dur * frac, (n - 1) * gap));
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = n > 1 ? last * i / (n - 1) : 0;
        return v;
    }

    private static int curIdx(double[] ts, double t) { int k = -1; for (int i = 0; i < ts.length; i++) if (t >= ts[i]) k = i; return k; }

    // ---- units flowed into balanced lines that fit a box
    record Pos(double x, double y, double w, int li) {}
    record Flow(double size, Pos[] pos, int L, double w, double h) {}

    private static void partitions(int n, int left, int start, List<int[]> acc, List<List<int[]>> out) {
        if (out.size() >= 80) return;
        if (left == 1) { List<int[]> p = new ArrayList<>(acc); p.add(new int[]{start, n}); out.add(p); return; }
        for (int e = start + 1; e <= n - left + 1; e++) { acc.add(new int[]{start, e}); partitions(n, left - 1, e, acc, out); acc.remove(acc.size() - 1); }
    }

    private static Flow flowUnits(List<String> units, String font, double maxW, double maxH, double maxSize, int maxLines, double track, double sp, double lead) {
        int n = units.size();
        double[] ws = new double[n];
        for (int i = 0; i < n; i++) ws[i] = em(units.get(i), font, track);
        double bestSize = -1; int bestL = 1; List<int[]> bestPt = null; double[] bestLw = null;
        for (int L = 1; L <= Math.min(n, maxLines); L++) {
            List<List<int[]>> parts = new ArrayList<>();
            partitions(n, L, 0, new ArrayList<>(), parts);
            List<int[]> bp = null; double bw = 1e9; double[] blw = null;
            for (List<int[]> pt : parts) {
                double[] lw = new double[pt.size()];
                double mw = 0;
                for (int li = 0; li < pt.size(); li++) {
                    double w = 0;
                    for (int i = pt.get(li)[0]; i < pt.get(li)[1]; i++) w += ws[i] + (i > pt.get(li)[0] ? sp : 0);
                    lw[li] = w; mw = Math.max(mw, w);
                }
                if (mw < bw) { bw = mw; bp = pt; blw = lw; }
            }
            if (bp == null) continue;
            double size = Math.min(Math.min(maxW / bw, maxH / (L * lead - (lead - 1))), maxSize);
            if (bestPt == null || size > bestSize * 1.1) { bestSize = size; bestL = L; bestPt = bp; bestLw = blw; }
        }
        Pos[] pos = new Pos[n];
        double size = bestSize;
        for (int li = 0; li < bestPt.size(); li++) {
            double y = (li - (bestL - 1) / 2.0) * lead * size, x = -bestLw[li] * size / 2;
            for (int i = bestPt.get(li)[0]; i < bestPt.get(li)[1]; i++) { double w = ws[i] * size; pos[i] = new Pos(x + w / 2, y, w, li); x += w + sp * size; }
        }
        double mw = 0;
        for (double w : bestLw) mw = Math.max(mw, w);
        return new Flow(size, pos, bestL, mw * size, (bestL * lead - (lead - 1)) * size);
    }

    private static Flow flow(List<String> units, String font, double maxW, double maxH, double maxSize, double track) {
        return flowUnits(units, font, maxW, maxH, maxSize, 4, track, units.stream().anyMatch(JizuraKinetic::hasLatin) ? 0.32 : 0.08, 1.22);
    }

    // ------------------------------------------------------------------ registration
    static void init() {
        def("knSlamStack", "積み上げ", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("align", rng.pick("center", "center", "left", "right")).with("from", rng.pick("scale", "scale", "drop", "side"))
                .with("acc", rng.integer(0, 5)).with("tilt", rng.chance(0.35) ? rng.range(2, 4) * rng.pick(1, -1) : 0).with("rule", rng.chance(0.55)),
            JizuraKinetic::slamStack);
        def("knQuarterTurn", "直角ターン", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("sgn", rng.pick(1, -1)).with("acc", rng.integer(0, 3)).with("joint", rng.chance(0.6)),
            JizuraKinetic::quarterTurn);
        def("knSwapCenter", "入れ替わり", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("fontL", fontPick(rng, st, "display", "serif")).with("mode", rng.pick("roll", "punch", "slide", "roll"))
                .with("ticks", rng.chance(0.7)).with("acc", rng.chance(0.5)),
            JizuraKinetic::swapCenter);
        def("knZoomDive", "文字へ潜る", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("cap", rng.chance(0.75)).with("acc", rng.chance(0.5)),
            JizuraKinetic::zoomDive);
        def("knFlowSnap", "流れて整列", n -> n >= 3 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")).with("amp", rng.range(0.1, 0.16)).with("lam", rng.range(0.45, 0.7)).with("grid", rng.pick("cells", "cells", "rules")).with("acc", rng.integer(0, 15)),
            JizuraKinetic::flowSnap);
        def("knSeesaw", "シーソー", n -> n >= 2 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("order", rng.pick("lr", "lr", "out")).with("acc", rng.chance(0.5)).with("fulc", rng.pick("tri", "tri", "round")),
            JizuraKinetic::seesaw);
        def("knTypeSlam", "タイプ→スラム", n -> n >= 2 && n <= 24,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("key", rng.pick("long", "long", "last")).with("side", rng.pick("below", "below", "above")).with("burst", rng.chance(0.7)),
            JizuraKinetic::typeSlam);
        def("knRhythmCuts", "語のカット割り", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> {
                List<String> sh = new ArrayList<>(SHOTS);
                for (int i = sh.size() - 1; i > 0; i--) { int j = rng.integer(0, i); String t = sh.get(i); sh.set(i, sh.get(j)); sh.set(j, t); }
                return new Params().with("font", fontPick(rng, st, "display")).with("fontB", fontPick(rng, st, "display", "serif")).with("shots", sh).with("side", rng.pick(1, -1)).with("fin", rng.pick("center", "center", "left"));
            },
            JizuraKinetic::rhythmCuts);
        def("knPathRide", "ループ軌道", n -> n >= 2 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("side", rng.pick(1, 1, -1)).with("rail", rng.pick("dash", "line", "dots")).with("acc", rng.chance(0.5)),
            JizuraKinetic::pathRide);
        def("knGearWords", "歯車", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("dir", rng.pick(1, -1)).with("fillMode", rng.pick("alt", "alt", "ink", "ring")),
            JizuraKinetic::gearWords);
        def("knCollide", "正面衝突", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("acc", rng.pick("A", "B", "none")).with("spark", rng.chance(0.8)).with("at", rng.range(0.28, 0.36)),
            JizuraKinetic::collide);
        def("knTumble", "箱転がし", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("box", rng.pick("plate", "frame", "none")).with("acc", rng.integer(0, 5)).with("floor", rng.chance(0.7)),
            JizuraKinetic::tumble);
        def("knReflow", "縦から横へ", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("dir", "v2h").with("swirl", rng.pick(1, -1)).with("at", rng.range(0.36, 0.46)),
            JizuraKinetic::reflow);
        def("knPadGrid", "パッド", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("round", rng.chance(0.5)).with("flash", rng.pick("accent", "accent", "ink")).with("beat", rng.chance(0.7)),
            JizuraKinetic::padGrid);
    }

    // ================================================================== 1 knSlamStack
    private record Lay(double[] sizes, double[] ys, double sw) {}

    private static Lay stackLay(int k, double[] ms, double maxW, double Hb, double cap, double gap) {
        double w = maxW;
        double[] sizes = new double[k];
        for (int q = 0; q < 5; q++) {
            double lo = 1e9;
            for (int i = 0; i < k; i++) { sizes[i] = Math.min(w / ms[i], cap); lo = Math.min(lo, sizes[i]); }
            lo *= 2.2;
            double S = 0;
            for (int i = 0; i < k; i++) { sizes[i] = Math.min(sizes[i], lo); S += sizes[i]; }
            S += gap * sizes[0] * (k - 1);
            if (S <= Hb) break;
            w *= Hb / S;
        }
        double g = gap * sizes[0], tot = 0;
        for (double s : sizes) tot += s;
        tot += g * (k - 1);
        double y = -tot / 2, sw = 0;
        double[] ys = new double[k];
        for (int i = 0; i < k; i++) { ys[i] = y + sizes[i] / 2; y += sizes[i] + g; sw = Math.max(sw, sizes[i] * ms[i]); }
        return new Lay(sizes, ys, sw);
    }

    private static BB slamStack(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc;
        List<String> units = unitsOf(c, 5, 2);
        int n = units.size();
        double[] ts = onsets(env, n, 0.42, 0.32);
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) ms[i] = em(units.get(i), Pm.s("font"), 0.02);
        double tilt = Pm.d("tilt");
        double maxW = W * (tilt != 0 ? 0.8 : 0.86), Hb = H * 0.8, cap = Math.min(H * 0.34, W * 0.44), gap = 0.08;
        int k = curIdx(ts, lt) + 1;
        if (k <= 0) return null;
        Lay A = stackLay(k, ms, maxW, Hb, cap, gap), B = k > 1 ? stackLay(k - 1, ms, maxW, Hb, cap, gap) : A;
        double e = E.outExpo((lt - ts[k - 1]) / 0.34);
        double sw = lerp(B.sw, A.sw, e), cx = W / 2, cy = H / 2;
        String al = Pm.s("align");
        double out = tout(env);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            boolean fresh = i == k - 1;
            double size = fresh ? A.sizes[i] : lerp(B.sizes[i], A.sizes[i], e);
            double y = fresh ? A.ys[i] : lerp(B.ys[i], A.ys[i], e);
            double x = al.equals("left") ? -sw / 2 : al.equals("right") ? sw / 2 : 0, rot = 0, alpha = 1;
            double q = (lt - ts[i]) / 0.24;
            if (q < 1) {
                double f = 1 - E.outExpo(clamp(q));
                switch (Pm.s("from")) {
                    case "scale" -> { size *= 1 + 1.6 * f; alpha = clamp(q * 5); rot = f * 8 * (i % 2 == 1 ? 1 : -1); }
                    case "drop" -> y -= H * 0.7 * Math.pow(1 - clamp(q), 2);
                    default -> x += (i % 2 == 1 ? 1 : -1) * W * 0.9 * f;
                }
            }
            double land = lt - ts[k - 1] - 0.12;
            if (!fresh && k > 1 && land > 0) y += A.sizes[k - 1] * 0.07 * sprg(land, 9, 26) * (land < 0.6 ? 1 : 0);
            double px = cx + x, py = cy + y;
            if (tilt != 0) { double[] rv = rotV(x, y, tilt); px = cx + rv[0]; py = cy + rv[1]; rot += tilt; }
            Item item = it(units.get(i), Pm.s("font"), size, px, py).align(al).track(0.02).rot(rot).alpha(alpha).color(n > 1 && i == Pm.i("acc") % n ? accOn(sc) : sc.fg()).mi(miAt(env, ts[i]));
            BB r = env.mainDraw(item);
            bb = ub(bb, r);
            if (Pm.b("rule") && r != null && tilt == 0 && i < k - 1) {
                double lw = Math.max(1.5, U * 0.003), gy = py + size * 0.5 + A.sizes[0] * gap * 0.5, w0 = cx - sw / 2;
                double re = E.outExpo((lt - ts[i + 1] - 0.05) / 0.35) * out;
                if (re > 0) env.line(new double[][]{{w0, gy}, {w0 + sw * re, gy}}, sc.sub(), lw, 0.8);
            }
        }
        return bb;
    }

    // ================================================================== 2 knQuarterTurn
    private record Cam(double r, double x, double y, double z) {}

    private static BB quarterTurn(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> units = unitsOf(c, 4, 2);
        int n = units.size();
        double[] ts = onsets(env, n, 0.46, 0.5);
        double[] L = new double[n], lastAdv = new double[n];
        for (int i = 0; i < n; i++) {
            L[i] = em(units.get(i), font, 0.03);
            List<String> cs = chars(units.get(i).trim());
            lastAdv[i] = JizuraKit.adv(font, cs.isEmpty() ? "字" : cs.get(cs.size() - 1));
        }
        double sgn = Pm.d("sgn");
        double[] ga = new double[n], gcx = new double[n], gcy = new double[n], gex = new double[n], gey = new double[n];
        double sx0 = 0, sy0 = 0, a = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                double[] pd = rotV(1, 0, a);
                double na = a + 90 * sgn * (i % 2 == 1 ? 1 : -1);
                double[] nd = rotV(1, 0, na);
                sx0 = gex[i - 1] - pd[0] * lastAdv[i - 1] * 0.5 + nd[0] * 0.62;
                sy0 = gey[i - 1] - pd[1] * lastAdv[i - 1] * 0.5 + nd[1] * 0.62;
                a = na;
            }
            double[] d = rotV(1, 0, a);
            ga[i] = a; gcx[i] = sx0 + d[0] * L[i] / 2; gcy[i] = sy0 + d[1] * L[i] / 2; gex[i] = sx0 + d[0] * L[i]; gey[i] = sy0 + d[1] * L[i];
        }
        Cam[] cams = new Cam[n];
        for (int i = 0; i < n; i++) cams[i] = new Cam(-ga[i], gcx[i], gcy[i], Math.min(Math.min(W * 0.76 / L[i], H * 0.3), W * 0.42));
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
        for (int i = 0; i < n; i++) {
            double hw = L[i] / 2, hh = 0.55;
            boolean v = Math.abs(ga[i] % 180) > 45;
            double w2 = v ? hh : hw, h2 = v ? hw : hh;
            x0 = Math.min(x0, gcx[i] - w2); x1 = Math.max(x1, gcx[i] + w2); y0 = Math.min(y0, gcy[i] - h2); y1 = Math.max(y1, gcy[i] + h2);
        }
        Cam ov = new Cam(0, (x0 + x1) / 2, (y0 + y1) / 2, Math.min(Math.min(W * 0.84 / (x1 - x0), H * 0.78 / (y1 - y0)), H * 0.3));
        int k = curIdx(ts, lt);
        if (k < 0) return null;
        double tOv = Math.min(ts[n - 1] + 0.62, Math.max(ts[n - 1] + 0.3, env.dur() - env.cut.outDur - 0.55));
        Cam A = cams[Math.max(0, k - 1)], B = cams[k];
        double e = k > 0 ? E.inOutCubic((lt - ts[k]) / 0.36) : 1;
        if (lt >= tOv) { A = cams[n - 1]; B = ov; e = E.inOutCubic((lt - tOv) / 0.5); }
        double dip = 1 - 0.22 * bellK(e) * (A == B ? 0 : 1);
        Cam cam = new Cam(lerp(A.r, B.r, e), lerp(A.x, B.x, e), lerp(A.y, B.y, e), Math.exp(lerp(Math.log(A.z), Math.log(B.z), e)) * dip);
        BB bb = null;
        double out = tout(env);
        for (int i = 0; i <= k; i++) {
            double[] dd = rotV(gcx[i] - cam.x, gcy[i] - cam.y, cam.r);
            double q = clamp((lt - ts[i]) / 0.22), pop = i == 0 ? 1 : lerp(0.3, 1, E.outBack(q, 2.2));
            Item item = it(units.get(i), font, cam.z * pop, W / 2 + dd[0] * cam.z, H / 2 + dd[1] * cam.z).rot(ga[i] + cam.r).track(0.03)
                .color(i == Pm.i("acc") % n ? accOn(sc) : sc.fg()).mi(miAt(env, ts[i])).alpha(i == 0 ? 1 : clamp(q * 4));
            bb = ub(bb, env.mainDraw(item));
            if (Pm.b("joint") && i > 0) {
                double[] pd = rotV(1, 0, ga[i - 1]);
                double jx = gex[i - 1] + pd[0] * 0.35, jy = gey[i - 1] + pd[1] * 0.35;
                double[] ev = rotV(jx - cam.x, jy - cam.y, cam.r);
                double s = cam.z * 0.16 * E.outBack(clamp(q * 1.3), 2) * out;
                if (s > 0.5) {
                    AffineTransform keep = env.g.getTransform();
                    env.g.translate(W / 2 + ev[0] * cam.z, H / 2 + ev[1] * cam.z); env.g.rotate((cam.r + 45) * DEG);
                    env.rect(-s / 2, -s / 2, s, s, sc.accent(), 1);
                    env.g.setTransform(keep);
                }
            }
        }
        return bb != null ? bb : box(W * 0.3, H * 0.4, W * 0.7, H * 0.6);
    }

    // ================================================================== 3 knSwapCenter
    private static BB swapCenter(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font"), fontL = Pm.s("fontL");
        List<String> units = unitsOf(c, 6, 2);
        int n = units.size();
        double[] ts = onsets(env, n, 0.4, 0.36);
        double[] big = new double[n];
        for (int i = 0; i < n; i++) big[i] = Math.min(Math.min(fit(units.get(i), font, W * 0.8, H * 0.4, 0.02), H * 0.34), W * 0.6);
        Flow F = flowUnits(units, fontL, W * 0.84, H * 0.34, H * 0.2, 4, 0.03, hasLatin(c.text) ? 0.32 : 0.08, 1.22);
        double tR = ts[n - 1] + 0.5, lim = env.dur() - c.outDur - 0.4;
        if (tR > lim) tR = Math.max(ts[n - 1] + 0.18, lim);
        int k = curIdx(ts, lt);
        if (k < 0) return null;
        double cx = W / 2, cy = H / 2, out = tout(env);
        BB[] bb = {null};
        Color accC = accOn(sc);
        boolean accMode = Pm.b("acc");
        if (lt < tR) {
            double e = k > 0 ? E.outExpo((lt - ts[k]) / 0.26) : 1;
            String mode = Pm.s("mode");
            java.util.function.BiConsumer<Integer, double[]> draw = (i, qo) -> {
                double q = qo[0]; boolean old = qo[1] > 0;
                double s0 = big[i], x = cx, y = cy, size = s0, sy = 1, alpha = 1;
                switch (mode) {
                    case "roll" -> { double d = s0 * 1.05 * (old ? -q : 1 - q); y += d; sy = old ? 1 - q : q; }
                    case "punch" -> { size = s0 * (old ? lerp(1, 0.45, q) : lerp(1.9, 1, q)); alpha = old ? 1 - q : clamp(q * 3); }
                    default -> { x += (old ? -q : 1 - E.outBack(q, 1.3)) * W * 0.7; alpha = old ? 1 - q * q : 1; }
                }
                if (sy < 0.02 || alpha < 0.01) return;
                Item item = it(units.get(i), font, size, x, y).sy(sy).track(0.02).alpha(alpha).color(accMode && i % 2 == 1 ? accC : sc.fg()).mi(miAt(env, ts[i]));
                item.noHold = old;
                bb[0] = ub(bb[0], env.mainDraw(item));
            };
            if (k > 0 && e < 1) draw.accept(k - 1, new double[]{e, 1});
            draw.accept(k, new double[]{e, 0});
            if (Pm.b("ticks") && n > 1) {
                double tw = U * 0.035, th = Math.max(3, U * 0.006), gx = tw * 1.5, y = Math.min(H * 0.9, cy + big[k] * 0.5 + U * 0.08);
                for (int i = 0; i < n; i++) {
                    double x = cx + (i - (n - 1) / 2.0) * gx - tw / 2;
                    boolean on = i <= k;
                    env.rect(x, y, tw, th, on ? accC : sc.sub(), (on ? 1 : 0.4) * out * clamp(lt / 0.2));
                }
            }
            return bb[0];
        }
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            double q = E.outExpo((lt - tR - (last ? 0 : 0.05 + i * 0.035)) / 0.42);
            Pos p = F.pos[i];
            double size = last ? lerp(big[i], F.size, q) : F.size * lerp(0.3, 1, q);
            Item item = it(units.get(i), last ? (q > 0.5 ? fontL : font) : fontL, size, lerp(cx, cx + p.x, q), lerp(cy, cy + p.y, q))
                .track(last ? lerp(0.02, 0.03, q) : 0.03).alpha(last ? 1 : clamp(q * 2.5)).color(accMode && i % 2 == 1 ? accC : sc.fg()).mi(last ? miAt(env, ts[i]) : miAt(env, tR));
            bb[0] = ub(bb[0], env.mainDraw(item));
        }
        return bb[0];
    }

    // ================================================================== 4 knZoomDive
    private static BB zoomDive(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> units = unitsOf(c, 5, 2);
        int n = units.size();
        double[] ts = onsets(env, n, 0.5, 0.55);
        int k = curIdx(ts, lt);
        if (k < 0) return null;
        double cx = W / 2, cy = H / 2;
        Color accC = accOn(sc);
        boolean acc = Pm.b("acc");
        BB bb = null;
        double T = 0.42;
        if (k > 0) {
            double e = clamp((lt - ts[k]) / T);
            if (e < 1) {
                int i = k - 1;
                double s0 = Math.min(Math.min(fit(units.get(i), font, W * 0.78, H * 0.4, 0.02), H * 0.34), W * 0.6);
                JizuraKit.Layout lay = JizuraKit.layoutText(new Item(units.get(i), font, 1).track(0.02));
                GL focusG = null;
                List<GL> gs = new ArrayList<>();
                for (GL g : lay) if (!g.ch.isBlank()) gs.add(g);
                for (GL g : gs) if (JizuraKit.isKanji(g.ch)) { focusG = g; break; }
                if (focusG == null && !gs.isEmpty()) focusG = gs.get(gs.size() / 2);
                double fx = focusG == null ? 0 : focusG.x, fy = focusG == null ? 0 : focusG.y;
                double px = lerp(cx + fx * s0, cx, E.inOutCubic(e)), py = lerp(cy + fy * s0, cy, E.inOutCubic(e));
                double s = Math.exp(Math.pow(e, 1.6) * Math.log(36));
                Item item = it(units.get(i), font, s0 * s, px - fx * s0 * s, py - fy * s0 * s).track(0.02).alpha(1 - JizuraKit.smooth(0.55, 1, e)).color(acc && i % 2 == 1 ? accC : sc.fg()).mi(miAt(env, ts[i]));
                item.noHold = true;
                env.mainDraw(item);
            }
        }
        double s1 = Math.min(Math.min(fit(units.get(k), font, W * 0.78, H * 0.4, 0.02), H * 0.34), W * 0.6);
        double q = k > 0 ? E.outExpo((lt - ts[k] - T * 0.35) / 0.45) : 1;
        if (q > 0.001) bb = env.mainDraw(it(units.get(k), font, s1 * lerp(0.03, 1, q), cx, cy).track(0.02).alpha(clamp(q * 3)).color(acc && k % 2 == 1 ? accC : sc.fg()).mi(miAt(env, ts[k])));
        if (Pm.b("cap") && n > 1 && k == n - 1 && bb != null) {
            double a = E.outCubic((lt - ts[k] - 0.55) / 0.35) * tout(env);
            if (a > 0.01) {
                String t = flat(c.text);
                double fs = Math.min(clamp(U * 0.034, 14, 40), fit(t, body(env), W * 0.8, H * 0.1, 0.18));
                double y = Math.min(H * 0.92, bb.y1() + fs * 1.6);
                env.draw(it(t, body(env), fs, cx, y).track(0.18).color(sc.sub()).alpha(a).noGhost());
                double w = meas(t, body(env), fs, 0.18, 1.3).w() * a;
                env.line(new double[][]{{cx - w / 2, y - fs * 1.1}, {cx + w / 2, y - fs * 1.1}}, sc.sub(), Math.max(1, U * 0.002), 0.6);
            }
        }
        return bb != null ? bb : box(W * 0.3, H * 0.4, W * 0.7, H * 0.6);
    }

    // ================================================================== 5 knFlowSnap
    private static BB flowSnap(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        boolean lat = hasLatin(c.text);
        String t0 = flat(c.text);
        List<String> cs = chars(t0);
        int n = cs.size();
        String ft; double trk;
        if (lat) { ft = JizuraKit.splitLines(t0, 18); trk = 0.02; }
        else {
            int m = n, c0 = Math.max(2, Math.min(6, (int) Math.ceil(Math.sqrt(m * 1.6)))), cols = (int) Math.ceil(m / Math.ceil(m / (double) c0));
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < m; i += cols) rows.add(String.join("", cs.subList(i, Math.min(m, i + cols))));
            ft = String.join("\n", rows); trk = 0.3;
        }
        double lead = lat ? 1.25 : 1.3;
        double fsz = Math.min(JizuraKit.fitSize(ft, font, W * 0.8, H * 0.62, new Item("", "", 1).track(trk).lead(lead)), H * 0.2);
        JizuraKit.Layout lay = JizuraKit.layoutText(new Item(ft, font, fsz).track(trk).lead(lead).align(lat ? "center" : "left"));
        double ox = W / 2 - (lat ? 0 : lay.W / 2), oy = H / 2;
        List<GL> gl = new ArrayList<>();
        for (GL g : lay) gl.add(g);
        int gn = gl.size();
        double tS = clamp(env.dur() * 0.42, 0.45, 1.5);
        double[] advs = new double[gn];
        double totA = 0;
        for (int i = 0; i < gn; i++) { advs[i] = JizuraKit.adv(font, gl.get(i).ch) * 1.08 + (i > 0 && gl.get(i).li != gl.get(i - 1).li ? 0.35 : 0); totA += advs[i]; }
        double fs = Math.min(H * 0.15, W * 0.95 / Math.max(4, totA));
        double[] offs = new double[gn];
        double acc0 = 0;
        for (int i = 0; i < gn; i++) { offs[i] = (acc0 + advs[i] / 2) * fs; acc0 += advs[i]; }
        double Xs = W / 2 - totA * fs / 2, X0 = W + fs * 0.8;
        double A = Math.min(H * Pm.d("amp"), W * 0.16), lam = W * Pm.d("lam");
        java.util.function.DoubleBinaryOperator cy0 = (x, t) -> H / 2 + A * Math.sin(x / lam * TAU + t * 3.2);
        double tf = Math.min(lt, tS);
        BB bb = null;
        double out = tout(env);
        Color accC = accOn(sc);
        for (int i = 0; i < gn; i++) {
            GL g = gl.get(i);
            if (g.ch.isBlank()) continue;
            double fx = lerp(X0, Xs, E.outCubic(tf / tS)) + offs[i], fy = cy0.applyAsDouble(fx, tf);
            double sl = (cy0.applyAsDouble(fx + 2, tf) - cy0.applyAsDouble(fx - 2, tf)) / 4, fr = Math.atan(sl) / DEG;
            double q = E.outExpo((lt - tS - i * 0.018) / 0.3);
            double x = lerp(fx, ox + g.x, q), y = lerp(fy, oy + g.y, q), rot = fr * (1 - q), size = lerp(fs, fsz, q);
            if (x > W + size && q <= 0) continue;
            bb = ub(bb, env.mainDraw(it(g.ch, font, size, x, y).rot(rot).color(!lat && i == Pm.i("acc") % Math.max(1, n) ? accC : sc.fg()).mi(i * 0.3)));
        }
        double ge = E.outExpo((lt - tS - 0.08) / 0.5) * out;
        if (ge > 0.01) {
            double lw = Math.max(1, U * 0.0016);
            String[] lines = ft.split("\n");
            double rh = fsz * lead;
            if (!lat && Pm.s("grid").equals("cells")) {
                double cell = fsz * (1 + trk);
                int rows = lines.length, cols = 0;
                for (String r : lines) cols = Math.max(cols, chars(r).size());
                double gx0 = ox - fsz * trk / 2, gy0 = oy - lay.H / 2 - (cell - fsz) / 2 - (fsz * lead - cell) / 2;
                for (int r = 0; r <= rows; r++) { double y = gy0 + r * rh; env.line(new double[][]{{gx0, y}, {gx0 + cols * cell * ge, y}}, sc.sub(), lw, 0.55); }
                for (int q2 = 0; q2 <= cols; q2++) { double x = gx0 + q2 * cell; env.line(new double[][]{{x, gy0}, {x, gy0 + rows * rh * ge}}, sc.sub(), lw, 0.55); }
            } else {
                for (int li = 0; li < lines.length; li++) {
                    double w = JizuraKit.measure(new Item(lines[li], font, fsz).track(trk)).w(), y = oy + (li - (lines.length - 1) / 2.0) * rh + fsz * 0.62;
                    double x0 = lat ? W / 2 - w / 2 : ox;
                    env.line(new double[][]{{x0, y}, {x0 + w * ge, y}}, sc.sub(), lw * 1.5, 0.7);
                }
            }
        }
        double pa = (1 - clamp((lt - tS) / 0.25)) * clamp(lt / 0.15) * 0.35;
        if (pa > 0.01) {
            List<double[]> pts = new ArrayList<>();
            for (double x = -20; x <= W + 20; x += W / 48) pts.add(new double[]{x, cy0.applyAsDouble(x, tf)});
            env.line(pts.toArray(new double[0][]), sc.sub(), Math.max(1, U * 0.0015), pa);
        }
        return bb;
    }

    // ================================================================== 6 knSeesaw
    private static BB seesaw(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> units = unitsOf(c, 6, 2);
        int n = units.size();
        Flow F = flowUnits(units, font, W * 0.78, H * 0.2, H * 0.17, 1, 0.03, hasLatin(c.text) ? 0.4 : 0.22, 1.22);
        double size = F.size, th = Math.max(4, size * 0.1), half = F.w / 2 + size * 0.6;
        double px = W / 2, py = H * 0.6;
        Integer[] ord = new Integer[n];
        for (int i = 0; i < n; i++) ord[i] = i;
        if (Pm.s("order").equals("out")) Arrays.sort(ord, (a, b) -> Double.compare(Math.abs(F.pos[a].x()), Math.abs(F.pos[b].x())));
        double[] ts0 = onsets(env, n, 0.45, 0.4);
        double fall = 0.24;
        double[] tl = new double[n];
        for (int r = 0; r < n; r++) tl[ord[r]] = ts0[r] + fall;
        double[] mass = new double[n];
        double M = 0, maxMass = 0;
        for (int i = 0; i < n; i++) { mass[i] = gcount(units.get(i)); M += mass[i]; maxMass = Math.max(maxMass, mass[i]); }
        double[] targ = new double[n + 1];
        double tq = 0;
        for (int r = 0; r < n; r++) { int i = ord[r]; tq += mass[i] * F.pos[i].x(); targ[r + 1] = r == n - 1 ? 0 : clamp(tq / (M * half) * 30, -11, 11); }
        double th0 = 0;
        for (int r = 0; r < n; r++) {
            double t0 = ts0[r] + fall;
            if (lt < t0) break;
            th0 = targ[r + 1] + (targ[r] - targ[r + 1]) * sprg(lt - t0, 3.6, 10);
        }
        double ang = th0;
        double e = E.outExpo(lt / 0.35), out = tout(env);
        Color plateC = sc.sub(), accC = accOn(sc);
        double fh = size * 0.62;
        if (Pm.s("fulc").equals("tri")) env.poly(new double[][]{{px, py + th / 2}, {px - fh * 0.62 * e, py + th / 2 + fh * e}, {px + fh * 0.62 * e, py + th / 2 + fh * e}}, accC, out);
        else env.circle(px, py + th / 2 + fh * 0.45, fh * 0.45 * e, accC, null, 0, out);
        AffineTransform keep = env.g.getTransform();
        env.g.translate(px, py); env.g.rotate(ang * DEG);
        env.rect(-half * e, -th / 2, half * 2 * e, th, plateC, 0.9 * out);
        env.g.setTransform(keep);
        BB bb = null;
        double cr = Math.cos(ang * DEG), sr = Math.sin(ang * DEG);
        for (int i = 0; i < n; i++) {
            double t0 = tl[i] - fall;
            if (lt < t0) continue;
            Pos p = F.pos[i];
            double v = -th / 2 - size * 0.54;
            double x = px + p.x() * cr - v * sr, y = py + p.x() * sr + v * cr, rot = ang, sy = 1, sx = 1;
            double d = lt - tl[i];
            if (d < 0) { double k = -d / fall; y -= H * 0.55 * k * k; rot = ang * (1 - k); }
            else { double q = Math.exp(-d * 11) * 0.22; sy = 1 - q; sx = 1 + q * 0.6; x += sr * size * q * 0.5; y += cr * size * q * 0.5; }
            bb = ub(bb, env.mainDraw(it(units.get(i), font, size, x, y).rot(rot).sx(sx).sy(sy).track(0.03).color(Pm.b("acc") && mass[i] == maxMass ? accC : sc.fg()).mi(miAt(env, t0))));
        }
        return bb;
    }

    // ================================================================== 7 knTypeSlam
    private static BB typeSlam(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        String t0 = flat(c.text);
        List<String> units = unitsOf(c, 6, 1);
        int ki = units.size() - 1;
        if (Pm.s("key").equals("long")) {
            double bv = -1;
            for (int i = 0; i < units.size(); i++) { double v = gcount(units.get(i)) + (units.get(i).matches("(?s).*[一-鿿].*") ? 0.5 : 0); if (v > bv) { bv = v; ki = i; } }
        }
        String key = units.get(ki), tf = mono(env);
        double ts = Math.min(fit(t0, tf, W * 0.86, H * 0.08, 0.06), U * 0.065);
        double tmW = meas(t0, tf, ts, 0.06, 1.3).w();
        double ks = Math.min(Math.min(fit(key, font, W * 0.84, H * 0.44, 0.01), H * 0.34), W * 0.5);
        boolean above = Pm.s("side").equals("above");
        double ky = H / 2 + (above ? ts * 1.2 : -ts * 1.2), ty = above ? ky - ks * 0.62 - ts * 1.5 : ky + ks * 0.62 + ts * 1.5;
        List<String> g0 = chars(t0);
        int nG = g0.size();
        double tType = clamp(nG * 0.045, 0.25, Math.min(1.1, env.dur() * 0.4)), tS = tType + 0.14;
        double x0 = W / 2 - tmW / 2;
        int k = (int) Math.floor(clamp(lt / tType) * nG + 1e-6);
        String pre = String.join("", g0.subList(0, Math.min(k, nG)));
        double wk = k >= nG ? tmW + ts : meas(pre, tf, ts, 0.06, 1.3).w() + (k > 0 ? ts * 0.06 : 0);
        double imp = lt - tS - 0.1, shake = imp > 0 ? Math.exp(-imp * 10) * ts * 0.35 : 0;
        Item itT = it(t0, tf, ts, x0 + (r(c.seed, env.step, 3, 0) * 2 - 1) * shake, ty + (r(c.seed, env.step, 4, 0) * 2 - 1) * shake).align("left").track(0.06).color(sc.fg()).mi(0);
        if (k < nG) itT.clip = new double[]{x0 - ts, x0 + wk};
        BB bb = env.mainDraw(itT);
        double out = tout(env);
        Color accC = accOn(sc);
        if ((k < nG || env.step % 2 == 0) && lt < tS + 0.6) env.rect(x0 + wk + ts * 0.1, ty - ts * 0.5, ts * 0.55, ts, accC, out);
        if (lt >= tS) {
            double q = clamp((lt - tS) / 0.16), f = 1 - E.inQuad(q), land = lt - tS - 0.16;
            double sq = land > 0 ? Math.exp(-land * 12) * 0.12 : 0;
            bb = ub(bb, env.mainDraw(it(key, font, ks * (1 + 2.4 * f), W / 2, ky).sx(1 + sq * 0.5).sy(1 - sq).track(0.01).alpha(clamp(q * 4)).color(sc.fg()).mi(miAt(env, tS))));
            if (Pm.b("burst") && land > 0 && land < 0.5) {
                double a = 1 - land / 0.5, R0 = ks * 0.9, lw = Math.max(2, U * 0.004);
                JizuraKit.Measure mK = meas(key, font, ks, 0.01, 1.3);
                for (int j = 0; j < 10; j++) {
                    double an = (j / 10.0) * TAU + r(c.seed, j, 5, 0) * 0.4, r0 = Math.max(mK.w(), mK.h()) * 0.55 + R0 * 0.15 + land * U * 0.25, r1 = r0 + U * 0.05 * a;
                    double syy = mK.h() / Math.max(mK.w(), mK.h()) + 0.35;
                    env.line(new double[][]{{W / 2 + Math.cos(an) * r0, ky + Math.sin(an) * r0 * syy}, {W / 2 + Math.cos(an) * r1, ky + Math.sin(an) * r1 * syy}}, accC, lw, a * out);
                }
            }
            int idx = t0.indexOf(key.trim());
            if (idx >= 0 && land > 0) {
                double a0 = idx > 0 ? meas(t0.substring(0, idx), tf, ts, 0.06, 1.3).w() + ts * 0.06 : 0;
                double kw = meas(key.trim(), tf, ts, 0.06, 1.3).w();
                double e2 = E.outExpo(land / 0.3) * out;
                env.rect(x0 + a0, ty + ts * 0.62, kw * e2, Math.max(2, ts * 0.12), accC, 1);
            }
        }
        return bb;
    }

    // ================================================================== 8 knRhythmCuts
    static final List<String> SHOTS = List.of("huge", "vert", "small", "crop", "band", "tilt");

    @SuppressWarnings("unchecked")
    private static BB rhythmCuts(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font"), fontB = Pm.s("fontB");
        List<String> units = unitsOf(c, 5, 2);
        int n = units.size();
        double[] ts = onsets(env, n, 0.5, 0.46);
        double tF = Math.min(ts[n - 1] + 0.55, env.dur() - c.outDur - 0.45);
        tF = Math.max(tF, ts[n - 1] + 0.28);
        int k = curIdx(ts, lt);
        if (k < 0) return null;
        double out = tout(env);
        Color accC = accOn(sc);
        double side = Pm.d("side");
        if (lt < tF) {
            String t = units.get(k);
            List<String> shots = Pm.get("shots") instanceof List<?> l ? (List<String>) l : SHOTS;
            String shot = shots.get(k % 6);
            double dt = lt - ts[k], punch = 1 + 0.1 * Math.exp(-dt * 14);
            Item item = it(t, font, 100, W / 2, H / 2).color(sc.fg()).track(0.02).mi(miAt(env, ts[k]));
            String vtxt = hasLatin(t) ? t : strip(t);
            if (shot.equals("huge")) item.size = Math.min(fit(t, font, W * 0.82, H * 0.6, 0.02), H * 0.54);
            else if (shot.equals("vert") && !hasLatin(t)) {
                item.text = vtxt; item.vertical = true; item.x = W / 2 + side * W * 0.22;
                item.size = Math.min(JizuraKit.fitSize(vtxt, font, W * 0.4, H * 0.8, new Item("", "", 1).vertical(true).track(0.02)), W * 0.26);
            } else if (shot.equals("small") || shot.equals("vert")) {
                item.size = Math.min(fit(t, fontB, W * 0.4, H * 0.12, 0.12), H * 0.1); item.font = fontB; item.track = 0.12;
                JizuraKit.Measure m = JizuraKit.measure(item);
                double pw = m.w() / 2 + item.size * 0.8, ph = m.h() / 2 + item.size * 0.6, L = item.size * 0.6, lw = Math.max(2, U * 0.003), e = E.outExpo(dt / 0.2);
                for (int[] ab : new int[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
                    double cx = W / 2 + ab[0] * pw * (1.3 - 0.3 * e), cy = H / 2 + ab[1] * ph * (1.3 - 0.3 * e);
                    env.line(new double[][]{{cx - ab[0] * L, cy}, {cx, cy}, {cx, cy - ab[1] * L}}, accC, lw, out);
                }
            } else if (shot.equals("crop")) {
                item.size = Math.min(fit(t, font, W * 1.02, H * 0.7, 0), H * 0.66); item.align = "left"; item.track = 0;
                JizuraKit.Measure m = JizuraKit.measure(item);
                item.x = W * 0.97 - m.w(); item.y = H / 2 + H * 0.04;
            } else if (shot.equals("band")) {
                item.size = Math.min(fit(t, font, W * 0.8, H * 0.24, 0.06), H * 0.22); item.track = 0.06;
                double bh = item.size * 1.55, e = E.outExpo(dt / 0.18);
                env.rect(0, H / 2 - bh / 2 * e, W, bh * e, sc.ink(), out);
                item.color = onCol(sc, sc.ink()); item.plain();
            } else {
                item.size = Math.min(fit(t, font, W * 0.72, H * 0.4, 0.02), H * 0.34); item.rot = -8 * side; item.color = accC;
            }
            item.size *= punch;
            return env.mainDraw(item);
        }
        Flow F = flow(units, fontB, W * 0.84, H * 0.34, H * 0.16, 0.04);
        boolean left = Pm.s("fin").equals("left");
        double ox = left ? W * 0.08 + F.w / 2 : W / 2;
        double dt = lt - tF, punch = 1 + 0.06 * Math.exp(-dt * 14);
        BB bb = null;
        for (int i = 0; i < n; i++) {
            Pos p = F.pos[i];
            bb = ub(bb, env.mainDraw(it(units.get(i), fontB, F.size * punch, ox + p.x() * punch, H / 2 + p.y() * punch).track(0.04).color(sc.fg()).mi(miAt(env, tF + i * 0.03))));
        }
        if (bb != null) {
            double e = E.outExpo(dt / 0.35) * out, lw = Math.max(2, U * 0.004);
            env.rect(left ? W * 0.08 : W / 2 - F.w / 2, bb.y1() + F.size * 0.25, F.w * e, lw, accC, 1);
        }
        return bb;
    }

    // ================================================================== 9 knPathRide
    private record LoopPath(double[][] pts, double[] L, double tot, double sT) {}
    private static final Map<String, LoopPath> PATHS = new HashMap<>();

    private static synchronized LoopPath loopPath(double xT, double yL, double R, int side) {
        String key = (int) xT + ":" + (int) yL + ":" + (int) R + ":" + side;
        LoopPath p0 = PATHS.get(key);
        if (p0 != null) return p0;
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{-W * 0.9, yL}); pts.add(new double[]{xT, yL});
        int N = 72;
        for (int i = 1; i <= N; i++) { double a = (double) i / N * TAU; pts.add(new double[]{xT + Math.sin(a) * R, yL - side * (1 - Math.cos(a)) * R}); }
        pts.add(new double[]{W * 2.2, yL});
        double[] L = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) L[i] = L[i - 1] + Math.hypot(pts.get(i)[0] - pts.get(i - 1)[0], pts.get(i)[1] - pts.get(i - 1)[1]);
        p0 = new LoopPath(pts.toArray(new double[0][]), L, L[L.length - 1], L[1]);
        if (PATHS.size() > 60) PATHS.clear();
        PATHS.put(key, p0);
        return p0;
    }

    private static double[] pathAt(LoopPath p, double s) {
        s = clamp(s, 0, p.tot - 0.01);
        int lo = 0, hi = p.L.length - 1;
        while (hi - lo > 1) { int m = (lo + hi) >> 1; if (p.L[m] <= s) lo = m; else hi = m; }
        double[] a = p.pts[lo], b = p.pts[hi];
        double k = (s - p.L[lo]) / Math.max(1e-6, p.L[hi] - p.L[lo]);
        return new double[]{lerp(a[0], b[0], k), lerp(a[1], b[1], k), Math.atan2(b[1] - a[1], b[0] - a[0]) / DEG};
    }

    private static BB pathRide(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        String t0 = flat(c.text);
        List<String> cs = chars(t0);
        double size = Math.min(fit(t0, font, W * 0.72, H * 0.2, 0.04), H * 0.17);
        int n = cs.size();
        double[] adv = new double[n], offs = new double[n];
        double Lt = 0;
        for (int i = 0; i < n; i++) { adv[i] = JizuraKit.adv(font, cs.get(i)) * size * 1.04; offs[i] = Lt + adv[i] / 2; Lt += adv[i]; }
        double R = clamp(Lt / TAU * 1.15, size * 1.5, Math.min(H * 0.28, W * 0.3));
        int side = Pm.i("side");
        double yL = H / 2 + side * R * 0.6;
        double xT = Math.max(W * 0.06 + R * 0.2, W / 2 - Lt / 2 - size * 0.4);
        LoopPath P0 = loopPath(xT, yL, R, side);
        double sEnd = P0.sT + TAU * R + (W / 2 + Lt / 2 - xT), sStart = P0.sT - (xT + W * 0.05);
        double T = clamp(env.dur() * 0.55, 0.7, 1.8), q = clamp(lt / T);
        double sHead = lerp(sStart, sEnd, 1 - Math.pow(1 - q, 2.6));
        sHead += E.inCubic(env.pOut) * W * 1.3;
        double out = tout(env);
        Color accC = accOn(sc);
        double ra = E.outCubic(lt / 0.3) * out * (1 - 0.6 * JizuraKit.smooth(T, T + 0.5, lt));
        if (ra > 0.01) {
            double lw = Math.max(1.2, U * 0.0022);
            List<double[]> pts = new ArrayList<>();
            for (double[] p : P0.pts) if (p[0] > -W * 0.1 && p[0] < W * 1.1) pts.add(p);
            if (Pm.s("rail").equals("dots")) { for (int i = 0; i < pts.size(); i += 3) env.circle(pts.get(i)[0], pts.get(i)[1], lw * 1.3, sc.sub(), null, 0, ra * 0.7); }
            else if (Pm.s("rail").equals("dash")) env.dashed(pts.toArray(new double[0][]), sc.sub(), lw, ra * 0.85, (float) (lw * 5), (float) (lw * 4));
            else env.line(pts.toArray(new double[0][]), sc.sub(), lw, ra * 0.85);
        }
        BB bb = null;
        for (int i = 0; i < n; i++) {
            if (cs.get(i).equals(" ")) continue;
            double[] p = pathAt(P0, sHead - (Lt - offs[i]));
            if (p[0] < -size || p[0] > W + size) continue;
            bb = ub(bb, env.mainDraw(it(cs.get(i), font, size, p[0], p[1]).rot(p[2]).color(Pm.b("acc") && i == n - 1 ? accC : sc.fg()).mi(i * 0.2)));
        }
        return bb != null ? bb : box(W * 0.3, H * 0.4, W * 0.7, H * 0.6);
    }

    // ================================================================== 10 knGearWords
    private static BB gearWords(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> units = unitsOf(c, 5, Math.min(5, Math.max(2, (int) Math.ceil(gcount(c.text) / 4.0))));
        int n = units.size();
        double[] ts = onsets(env, n, 0.45, 0.42);
        String[] txt = new String[n];
        double[] rs0 = new double[n];
        for (int i = 0; i < n; i++) {
            String t = units.get(i);
            txt[i] = !hasLatin(t) && gcount(t) > 3 ? JizuraKit.splitLines(strip(t), (int) Math.ceil(gcount(t) / 2.0)) : t;
            JizuraKit.Measure m = meas(txt[i], font, 1, 0.02, 1.05);
            rs0[i] = Math.hypot(m.w(), m.h()) / 2 * 1.12 + 0.18;
        }
        double[] sorted = rs0.clone();
        Arrays.sort(sorted);
        double med = sorted[n / 2];
        double[] rs = new double[n], tk = new double[n];
        for (int i = 0; i < n; i++) { rs[i] = clamp(rs0[i], med * 0.75, med * 1.3); tk[i] = Math.min(1, rs[i] / rs0[i]); }
        double gap = 0.1, tot = gap * (n - 1), mr = 0;
        for (double r : rs) { tot += r * 2; mr = Math.max(mr, r); }
        double k = Math.min(Math.min(W * 0.9 / tot, H * 0.6 / (mr * 2)), H * 0.22 / 0.7);
        double[][] cs = new double[n][];
        double acc = -tot / 2;
        for (int i = 0; i < n; i++) { acc += rs[i]; double z = (i % 2 == 1 ? 1 : -1) * mr * 0.12; cs[i] = new double[]{W / 2 + acc * k, H / 2 + z * k}; acc += rs[i] + gap; }
        double out = tout(env);
        BB bb = null;
        for (int i = 0; i < n; i++) {
            if (lt < ts[i]) continue;
            double ang = 0;
            for (int j = i; j < n; j++) if (lt >= ts[j]) ang += 360 * (1 - E.inOutCubic((lt - ts[j]) / 0.62)) * (j == i ? 1 : rs[j] / rs[i] * 0.35);
            ang *= (i % 2 == 1 ? -1 : 1) * Pm.d("dir");
            double pop = E.outBack(clamp((lt - ts[i]) / 0.3), 1.8), r = rs[i] * k * pop * out;
            double cx = cs[i][0], cy = cs[i][1];
            String fm = Pm.s("fillMode");
            Color fill = fm.equals("ink") ? sc.ink() : fm.equals("ring") ? null : (i % 2 == 1 ? sc.accent() : sc.ink());
            Color rimC = fill != null ? fill : accOn(sc);
            if (r > 1) {
                int nt = Math.max(10, (int) Math.round(TAU * rs[i] * 6));
                double th = r * 0.12;
                double[][] pts = new double[nt * 4][];
                for (int t = 0; t < nt; t++) {
                    double a0 = (t / (double) nt * 360 + ang) * DEG, w = TAU / nt;
                    pts[t * 4] = new double[]{cx + Math.cos(a0 - w * 0.25) * r, cy + Math.sin(a0 - w * 0.25) * r};
                    pts[t * 4 + 1] = new double[]{cx + Math.cos(a0 - w * 0.15) * (r + th), cy + Math.sin(a0 - w * 0.15) * (r + th)};
                    pts[t * 4 + 2] = new double[]{cx + Math.cos(a0 + w * 0.15) * (r + th), cy + Math.sin(a0 + w * 0.15) * (r + th)};
                    pts[t * 4 + 3] = new double[]{cx + Math.cos(a0 + w * 0.25) * r, cy + Math.sin(a0 + w * 0.25) * r};
                }
                env.poly(pts, rimC, 1);
                if (fill != null) env.circle(cx, cy, r * 0.96, fill, null, 0, 1);
                else env.circle(cx, cy, r * 0.93, sc.bg(), null, 0, 1);
                env.circle(cx, cy, r * 0.86, null, fill != null ? onCol(sc, fill) : rimC, Math.max(1, r * 0.02), 0.35);
            }
            Color tc = fill != null ? onCol(sc, fill) : sc.fg();
            bb = ub(bb, env.mainDraw(it(txt[i], font, k * pop * tk[i], cx, cy).rot(ang).lead(1.05).track(0.02).color(tc).mi(miAt(env, ts[i]))));
        }
        return bb;
    }

    // ================================================================== 11 knCollide
    private static BB collide(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> all = unitsOf(c, 8, 2);
        boolean lat = hasLatin(c.text);
        int best = 1, tot = 0;
        double bd = 1e9;
        for (String t : all) tot += gcount(t);
        int accn = 0;
        for (int i = 1; i < all.size(); i++) { accn += gcount(all.get(i - 1)); double d = Math.abs(accn - tot / 2.0); if (d < bd) { bd = d; best = i; } }
        String sep = lat ? " " : "";
        String A = String.join(sep, all.subList(0, best)), B = String.join(sep, all.subList(best, all.size()));
        double tC = clamp(env.dur() * Pm.d("at") * 0.65, 0.25, 0.55), d = lt - tC;
        Color accC = accOn(sc);
        double out = tout(env);
        double g = lat ? 0.35 : 0.14;
        String full = A + sep + B;
        double size = Math.min(fit(full, font, W * 0.86, H * 0.3, 0.02), H * 0.24);
        double wa = em(A, font, 0.02) * size, wb = em(B, font, 0.02) * size, x0 = W / 2 - (wa + wb + g * size) / 2;
        double[] pa = {x0 + wa / 2, H / 2}, pb = {x0 + wa + g * size + wb / 2, H / 2};
        double D = Math.max(pa[0] + wa / 2, W - pb[0] + wb / 2) + size * 0.2;
        double offA, sqA = 1;
        if (d < 0) { double q = clamp(lt / tC); offA = -D * (1 - Math.pow(q, 1.6)); }
        else { offA = -size * 0.28 * Math.exp(-d * 6) * Math.sin(d * 15); sqA = 1 - 0.22 * Math.exp(-d * 14); }
        double jit = d > 0 ? Math.exp(-d * 9) * size * 0.05 : 0;
        final double fOff = offA, fSq = sqA;
        java.util.function.BiFunction<String, Object[], BB> mk = (t, o) -> {
            double[] p = (double[]) o[0];
            int sgn = (Integer) o[1];
            Color col = (Color) o[2];
            double off = fOff * sgn;
            Item item = it(t, font, size, p[0] + off + (r(c.seed, env.step, sgn, 1) * 2 - 1) * jit, p[1] + (r(c.seed, env.step, sgn, 2) * 2 - 1) * jit).track(0.02).color(col).mi(0);
            item.sx = fSq;
            return env.mainDraw(item);
        };
        BB bb = mk.apply(A, new Object[]{pa, 1, Pm.s("acc").equals("A") ? accC : sc.fg()});
        bb = ub(bb, mk.apply(B, new Object[]{pb, -1, Pm.s("acc").equals("B") ? accC : sc.fg()}));
        double lw = Math.max(1.5, U * 0.0028);
        if (d < 0 && lt > 0.02) {
            double q = clamp(lt / tC), a = 0.7 * q * out;
            for (int j = 0; j < 5; j++) {
                double f = (j - 2) / 2.2 * size * 0.42, len = size * (1.2 + r(c.seed, j, 7, 0) * 1.6);
                double xa = pa[0] + offA - em(A, font, 0) * size / 2 - size * 0.2, xb = pb[0] - offA + em(B, font, 0) * size / 2 + size * 0.2;
                env.line(new double[][]{{xa - len, pa[1] + f}, {xa, pa[1] + f}}, sc.sub(), lw, a);
                env.line(new double[][]{{xb, pb[1] - f}, {xb + len, pb[1] - f}}, sc.sub(), lw, a);
            }
        }
        if (Pm.b("spark") && d > 0 && d < 0.45) {
            double a = (1 - d / 0.45) * out, cx = (pa[0] + em(A, font, 0) * size / 2 + pb[0] - em(B, font, 0) * size / 2) / 2, cy = H / 2;
            for (int j = 0; j < 12; j++) {
                double an = (j / 12.0 + r(c.seed, j, 9, 0) * 0.05) * TAU, r0 = size * (0.25 + d * 2.2), r1 = r0 + size * 0.5 * a;
                env.line(new double[][]{{cx + Math.cos(an) * r0, cy + Math.sin(an) * r0}, {cx + Math.cos(an) * r1, cy + Math.sin(an) * r1}}, accC, lw * 1.4, a);
            }
        }
        return bb;
    }

    // ================================================================== 12 knTumble
    private static BB tumble(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> units = unitsOf(c, 5, 2);
        int n = units.size();
        Flow F = flowUnits(units, font, W * 0.82, H * 0.3, H * 0.2, 4, 0.02, hasLatin(c.text) ? 0.5 : 0.3, 1.6);
        double size = F.size, pad = size * 0.14, h = size + pad * 2;
        double[] ts = onsets(env, n, 0.42, 0.36);
        double T = 0.5, out = tout(env);
        Color accC = accOn(sc);
        BB bb = null;
        double lw = Math.max(1.5, U * 0.0025);
        String boxMode = Pm.s("box");
        for (int i = 0; i < n; i++) {
            Pos p = F.pos[i];
            double w = p.w() + pad * 2, base = H / 2 + p.y() + h / 2, xF = W / 2 + p.x() - w / 2;
            if (Pm.b("floor") && (i == 0 || F.pos[i - 1].li() != p.li())) {
                double rx0 = 1e9, rx1 = -1e9;
                for (Pos q : F.pos) if (q.li() == p.li()) { rx0 = Math.min(rx0, W / 2 + q.x() - q.w() / 2); rx1 = Math.max(rx1, W / 2 + q.x() + q.w() / 2); }
                double x0 = rx0 - pad * 3, x1 = rx1 + pad * 3, e = E.outExpo(lt / 0.4) * out;
                env.line(new double[][]{{x1 - (x1 - x0) * e, base + lw}, {x1, base + lw}}, sc.sub(), lw, 0.8);
            }
            if (lt < ts[i]) continue;
            int m = Math.min(3, Math.max(1, (int) Math.ceil((W - xF) / (2 * (w + h)))));
            int steps = 4 * m;
            double Tm = T * (0.7 + 0.3 * m), q = clamp((lt - ts[i]) / Tm) * steps;
            int j = Math.min(steps - 1, (int) Math.floor(q));
            double f = q >= steps ? 1 : q - j;
            double xL = xF + m * 2 * (w + h);
            for (int s2 = 0; s2 < j; s2++) xL -= s2 % 2 == 1 ? w : h;
            double fw = j % 2 == 1 ? h : w, fh = j % 2 == 1 ? w : h, ph = -90 * E.inOutSine(f);
            double[] dd = rotV(fw / 2, -fh / 2, ph);
            double cx = xL + dd[0], cy = base + dd[1], rot = -90 * j + ph;
            boolean done = q >= steps;
            double land = done ? lt - ts[i] - Tm : -1, sq = land >= 0 ? Math.exp(-land * 14) * 0.1 : 0;
            boolean isAcc = i == Pm.i("acc") % n;
            if (!boxMode.equals("none")) {
                AffineTransform keep = env.g.getTransform();
                env.g.translate(cx, cy + sq * h * 0.5); env.g.rotate(rot * DEG); env.g.scale(1 + sq * 0.5, 1 - sq);
                if (boxMode.equals("plate")) env.rect(-w / 2, -h / 2, w, h, isAcc ? accC : sc.ink(), out);
                else env.rrect(-w / 2, -h / 2, w, h, 2, null, out, sc.fg(), lw);
                env.g.setTransform(keep);
            }
            boolean plate = boxMode.equals("plate");
            Item item = it(units.get(i), font, size, cx, cy + sq * h * 0.5).rot(rot).sx(1 + sq * 0.5).sy(1 - sq).track(0.02)
                .color(plate ? onCol(sc, isAcc ? accC : sc.ink()) : isAcc ? accC : sc.fg()).mi(miAt(env, ts[i]));
            if (plate) item.plain();
            item.noHold = !done;
            bb = ub(bb, env.mainDraw(item));
        }
        return bb;
    }

    // ================================================================== 13 knReflow
    private static BB reflow(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        boolean lat = hasLatin(c.text);
        String t0 = lat ? flat(c.text) : strip(c.text);
        List<String> cs = chars(t0);
        int n = cs.size();
        int vCols = n <= 5 ? 1 : n <= 12 ? 2 : 3, per = (int) Math.ceil(n / (double) vCols);
        List<String> vt = new ArrayList<>();
        for (int i = 0; i < n; i += per) vt.add(String.join("", cs.subList(i, Math.min(n, i + per))));
        String vtext = String.join("\n", vt), htext = JizuraKit.splitLines(t0, lat ? 20 : 14);
        double sv = Math.min(JizuraKit.fitSize(vtext, font, W * 0.5, H * 0.8, new Item("", "", 1).vertical(true).lead(1.3).track(0.04)), W * 0.3);
        double sh = Math.min(JizuraKit.fitSize(htext, font, W * 0.84, H * 0.4, new Item("", "", 1).lead(1.25).track(0.04)), H * 0.24);
        List<GL> LV = new ArrayList<>(), LH = new ArrayList<>();
        for (GL g : JizuraKit.layoutText(new Item(vtext, font, sv).vertical(true).lead(1.3).track(0.04))) if (!g.ch.isBlank()) LV.add(g);
        for (GL g : JizuraKit.layoutText(new Item(htext, font, sh).lead(1.25).track(0.04))) if (!g.ch.isBlank()) LH.add(g);
        boolean h2v = Pm.s("dir").equals("h2v");
        List<GL> A = h2v ? LH : LV, B = h2v ? LV : LH;
        double sA = h2v ? sh : sv, sB = h2v ? sv : sh;
        double tR = clamp(env.dur() * Pm.d("at"), 0.5, 1.5);
        int m = Math.min(A.size(), B.size());
        BB bb = null;
        double swirl = Pm.d("swirl");
        for (int i = 0; i < m; i++) {
            GL a = A.get(i), b = B.get(i);
            double q = E.inOutCubic((lt - tR - i * Math.min(0.05, 0.5 / m)) / 0.5);
            double ax = W / 2 + a.x + a.vx, ay = H / 2 + a.y + a.vy, bx = W / 2 + b.x + b.vx, by = H / 2 + b.y + b.vy;
            double mx = (ax + bx) / 2, my = (ay + by) / 2, dx = bx - ax, dy = by - ay, L = Math.hypot(dx, dy);
            if (L == 0) L = 1;
            double k = swirl * 0.35 * (i % 2 == 1 ? 1 : 0.6);
            double cx = mx - dy / L * L * k, cy = my + dx / L * L * k;
            double x = (1 - q) * (1 - q) * ax + 2 * (1 - q) * q * cx + q * q * bx, y = (1 - q) * (1 - q) * ay + 2 * (1 - q) * q * cy + q * q * by;
            double r0 = a.r90 ? 90 : 0, r1 = b.r90 ? 90 : 0;
            bb = ub(bb, env.mainDraw(it(a.ch, font, lerp(sA, sB, q), x, y).rot(lerp(r0, r1, q) + swirl * 180 * bellK(q) * 0.25).color(sc.fg()).mi(i * 0.4)));
        }
        return bb;
    }

    // ================================================================== 14 knPadGrid
    private static BB padGrid(Env env) {
        Cut c = env.cut; Params Pm = env.P(); double lt = env.lt; var sc = env.sc; String font = Pm.s("font");
        List<String> units = unitsOf(c, 6, Math.min(6, Math.max(2, (int) Math.ceil(gcount(c.text) / 4.0))));
        int n = units.size();
        int cols = n <= 3 ? n : n == 4 ? 2 : 3, rows = (int) Math.ceil(n / (double) cols);
        double gw = W * 0.86, gh = H * 0.72, g = U * 0.02;
        double cw = (gw - g * (cols - 1)) / cols, chh = Math.min((gh - g * (rows - 1)) / rows, cw * 0.75);
        double x0 = W / 2 - gw / 2, y0 = H / 2 - (chh * rows + g * (rows - 1)) / 2;
        double[] ts = onsets(env, n, 0.45, 0.34);
        double out = tout(env), lw = Math.max(1.5, U * 0.003);
        Color flashC = Pm.s("flash").equals("ink") ? sc.ink() : accOn(sc);
        BB bb = null;
        double[] sizes = new double[n];
        double mn = 1e9;
        for (int i = 0; i < n; i++) { sizes[i] = Math.min(fit(units.get(i), font, cw * 0.8, chh * 0.62, 0.02), chh * 0.5); mn = Math.min(mn, sizes[i]); }
        double sz = mn * 1.25;
        double rad = Pm.b("round") ? chh * 0.12 : 0;
        for (int i = 0; i < cols * rows; i++) {
            int r = i / cols, q = i % cols;
            double x = x0 + q * (cw + g), y = y0 + r * (chh + g);
            double e = E.outBack(clamp((lt - i * 0.04) / 0.3), 1.4) * out;
            if (e <= 0.01) continue;
            env.rrect(x + cw / 2 * (1 - e), y + chh / 2 * (1 - e), cw * e, chh * e, rad, null, 1, sc.sub(), lw);
            if (i >= n || lt < ts[i]) continue;
            double fl = Math.exp(-(lt - ts[i]) * 6), pf = 0.1 + 0.9 * fl;
            env.rrect(x + lw, y + lw, cw - lw * 2, chh - lw * 2, rad, flashC, pf * out, null, 0);
            Color tc = pf > 0.5 ? onCol(sc, flashC) : sc.fg();
            bb = ub(bb, env.mainDraw(it(units.get(i), font, Math.min(sizes[i], sz) * (1 + 0.12 * Math.exp(-(lt - ts[i]) * 12)), x + cw / 2, y + chh / 2).track(0.02).color(tc).mi(miAt(env, ts[i]))));
        }
        return bb;
    }
}
