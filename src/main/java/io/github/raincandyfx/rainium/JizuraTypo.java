package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.JizuraKit.BB;
import io.github.raincandyfx.rainium.JizuraKit.E;
import io.github.raincandyfx.rainium.JizuraKit.Env;
import io.github.raincandyfx.rainium.JizuraKit.GL;
import io.github.raincandyfx.rainium.JizuraKit.Item;
import io.github.raincandyfx.rainium.JizuraKit.Params;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import static io.github.raincandyfx.rainium.JizuraCompose.*;
import static io.github.raincandyfx.rainium.JizuraKit.clamp;
import static io.github.raincandyfx.rainium.JizuraLayouts.def;
import static io.github.raincandyfx.rainium.JizuraLayouts.fontPick;

/**
 * Typography layouts from JIZURA's "typo" pack: key glyph between small text, cropped giant type, cross setting,
 * hidden-by-band text and ruled lines (https://github.com/852wa/JIZURA, MIT, (c) 2026 hakoniwa). Landscape only.
 */
final class JizuraTypo {
    private JizuraTypo() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    private static boolean sp(String c) { return c.equals(" ") || c.equals("　"); }

    private static List<String> slots(String t) { return chars(t.trim().replaceAll("[\\s　]+", " ")); }

    private static double hair() { return Math.max(1, U * 0.0014); }

    private static double lsz() { return clamp(U * 0.018, 11, 22); }

    private static Color accentOn(JizuraNative.Scheme sc) { return JizuraKit.contrast(sc.accent(), sc.bg()) >= 1.8 ? sc.accent() : sc.fg(); }

    private static Color plate(JizuraNative.Scheme sc, Color... pref) {
        for (Color c : pref) if (c != null && JizuraKit.contrast(c, sc.bg()) >= 1.6) return c;
        return sc.fg();
    }

    private static boolean ok(String c) { return c != null && !sp(c) && !isBad(c); }

    /** Index of the key glyph: first kanji of the longest kanji run, else the first plain glyph. */
    private static int keyIndex(List<String> arr, String mode) {
        if (mode.equals("mid")) {
            int mid = (arr.size() - 1) / 2;
            for (int d = 0; d < arr.size(); d++) {
                if (mid + d < arr.size() && ok(arr.get(mid + d))) return mid + d;
                if (mid - d >= 0 && ok(arr.get(mid - d))) return mid - d;
            }
        }
        int best = -1, bl = 0;
        for (int i = 0; i < arr.size(); i++) {
            if (!JizuraKit.isKanji(arr.get(i))) continue;
            int j = i;
            while (j < arr.size() && JizuraKit.isKanji(arr.get(j))) j++;
            if (j - i > bl) { bl = j - i; best = i; }
            i = j;
        }
        if (best >= 0) return best;
        for (int i = 0; i < arr.size(); i++) if (ok(arr.get(i))) return i;
        return 0;
    }

    private static String join(List<String> a, int from, int to) { return String.join("", a.subList(Math.max(0, from), Math.min(a.size(), to))).trim(); }

    private static String mainLines(String text, int perL) {
        String t = text.trim();
        int n = gcount(t);
        if (n <= perL) return t;
        return brk(t, (int) Math.ceil(n / Math.ceil(n / (double) perL)));
    }

    private static void label(Env env, String text, double x, double y, double size, double a, String align, Color c) {
        if (a <= 0.01) return;
        env.draw(it(text, mono(env), size, x, y).track(0.12).align(align).color(c).alpha(a).noGhost());
    }

    static {
        def("tyKeySplit", "大字挟み", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("fs", fontPick(rng, st, "serif", "body", "display"))
                .with("mode", rng.pick("kanji", "kanji", "mid")).with("big", rng.pick("fill", "fill", "accent", "outline")).with("rule", rng.chance(0.75)),
            JizuraTypo::keySplit);
        def("tyCropGiant", "見切れ大文字", n -> n >= 1 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("fm", fontPick(rng, st, "display", "serif")).with("edge", rng.pick("a", "b"))
                .with("style", rng.pick("dim", "dim", "outline")).with("dir", rng.pick(1.0, -1.0)),
            JizuraTypo::cropGiant);
        def("tyCross", "十字組", n -> n >= 3 && n <= 11,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("mode", rng.pick("kanji", "mid", "mid")).with("rules", rng.chance(0.8))
                .with("key", rng.pick("accent", "accent", "fg")),
            JizuraTypo::cross);
        def("tyBandHide", "帯隠れ", n -> n >= 1 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("band", rng.pick("ink", "accent", "fg")).with("pos", rng.pick("low", "low", "high"))
                .with("speed", rng.range(40, 90)).with("dir", rng.pick(1.0, -1.0)).with("k", rng.range(0.3, 0.38)),
            JizuraTypo::bandHide);
        def("tyBaseline", "罫線組", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display", "body")).with("nl", rng.pick(2.0, 3.0, 3.0)).with("key", rng.chance(0.8)).with("right", rng.chance(0.5)),
            JizuraTypo::baseline);
    }

    // ------------------------------------------------------------------ tyKeySplit
    private static BB keySplit(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font"), fs = p.s("fs");
        String txt = env.cut.text;
        List<String> arr = slots(txt);
        int ki = keyIndex(arr, p.s("mode"));
        String key = arr.isEmpty() ? "?" : arr.get(ki), before = join(arr, 0, ki), after = join(arr, ki + 1, arr.size());
        if (hasLatin(txt) && !txt.matches(".*[\\u3040-\\u30ff\\u3400-\\u9fff].*")) {
            String[] ws = txt.trim().split("\\s+");
            int wi = 0;
            for (int i = 0; i < ws.length; i++) if (ws[i].length() > ws[wi].length()) wi = i;
            key = ws[wi];
            before = String.join(" ", java.util.Arrays.copyOfRange(ws, 0, wi));
            after = String.join(" ", java.util.Arrays.copyOfRange(ws, wi + 1, ws.length));
        }
        double bigS = Math.min(H * 0.64, W * 0.34);
        bigS = Math.min(bigS, JizuraKit.fitSize(key, font, W * 0.5, 1e6));
        double bw = meas(key, font, bigS, 0, 1.2).w();
        double gap = bigS * 0.12;
        String bt = before.isEmpty() ? "" : brk(before, 5), at = after.isEmpty() ? "" : brk(after, 5);
        double side = (W * 0.9 - bw - gap * 2) / Math.max(1, (bt.isEmpty() ? 0 : 1) + (at.isEmpty() ? 0 : 1));
        double ss = bigS * 0.3;
        if (!bt.isEmpty()) ss = Math.min(ss, fit(bt, fs, side, bigS * 0.62, 0.04, 1.2));
        if (!at.isEmpty()) ss = Math.min(ss, fit(at, fs, side, bigS * 0.62, 0.04, 1.2));
        var mB = bt.isEmpty() ? null : meas(bt, fs, ss, 0.04, 1.2);
        var mA = at.isEmpty() ? null : meas(at, fs, ss, 0.04, 1.2);
        double tot = (mB != null ? mB.w() + gap : 0) + bw + (mA != null ? mA.w() + gap : 0);
        double bx = W / 2 - tot / 2 + (mB != null ? mB.w() + gap : 0) + bw / 2, by = H / 2;
        double out = tout(env), lw = hair();
        Item itB = mB == null ? null : it(bt, fs, ss, bx - bw / 2 - gap, by - bigS * 0.46 + mB.h() / 2).align("right").track(0.04).lead(1.2).color(sc.fg()).mi(0);
        Item itA = mA == null ? null : it(at, fs, ss, bx + bw / 2 + gap, by + bigS * 0.46 - mA.h() / 2).align("left").track(0.04).lead(1.2).color(sc.fg()).mi(4);
        if (p.b("rule")) {
            double e = E.outCubic(clamp((lt - 0.2) / 0.6)) * out;
            if (e > 0 && itB != null) {
                double y = itB.y + mB.h() / 2 + ss * 0.35, xa = itB.x - mB.w(), xb = bx + bw * 0.5;
                env.line(new double[][]{{xa, y}, {JizuraKit.lerp(xa, xb, e), y}}, sc.sub(), lw, 0.7);
            }
            if (e > 0 && itA != null) {
                double y = itA.y - mA.h() / 2 - ss * 0.35, xb = itA.x + mA.w(), xa = bx - bw * 0.5;
                env.line(new double[][]{{xb, y}, {JizuraKit.lerp(xb, xa, e), y}}, sc.sub(), lw, 0.7);
            }
        }
        Item big = it(key, font, bigS, bx, by).color(p.s("big").equals("accent") ? accentOn(sc) : sc.fg()).mi(2);
        if (p.s("big").equals("outline")) big.outline(Math.max(1.5, bigS * 0.014), sc.fg());
        BB bb = env.mainDraw(big);
        if (itB != null) bb = BB.union(bb, env.mainDraw(itB));
        if (itA != null) bb = BB.union(bb, env.mainDraw(itA));
        int nG = 0, kiG = 0;
        for (int i = 0; i < arr.size(); i++) if (!sp(arr.get(i))) { nG++; if (i < ki) kiG++; }
        label(env, two(kiG + 1) + " / " + two(nG), bx - bw / 2, by + bigS * 0.56, lsz() * 0.9, E.outCubic(clamp((lt - 0.3) / 0.3)) * out, "left", sc.sub());
        return bb;
    }

    // ------------------------------------------------------------------ tyCropGiant
    private static BB cropGiant(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt;
        String txt = env.cut.text.trim();
        double u = clamp(lt / Math.max(0.5, env.dur()));
        double inG = E.outCubic(clamp(lt / 0.8)), outG = E.inCubic(env.pOut);
        boolean low = p.s("edge").equals("a");
        double dir = p.d("dir"), G = H * 0.66;
        Item gi = it(txt, p.s("font"), G, W / 2 + (0.5 - u) * W * 0.14 * dir,
            (low ? H + G * 0.03 : -G * 0.03) + (low ? 1 : -1) * ((1 - inG) + outG) * G * 0.45).noGhost();
        if (p.s("style").equals("outline")) gi.outline(Math.max(1.4, G * 0.006), sc.sub()).alpha(0.65);
        else gi.color(JizuraKit.mix(sc.bg(), sc.fg(), 0.16));
        env.draw(gi);
        String mt = mainLines(txt, 10);
        String fm = p.s("fm");
        double size = Math.min(fit(mt, fm, W * 0.74, H * 0.34, 0.03, 1.18), H * 0.19);
        Item main = it(mt, fm, size, W * 0.08, low ? H * 0.38 : H * 0.62).align("left").track(0.03).lead(1.18).color(sc.fg());
        BB bb = env.mainDraw(main);
        var m = meas(mt, fm, size, 0.03, 1.18);
        double la = E.outCubic(clamp((lt - 0.2) / 0.35)) * tout(env), ls = lsz();
        if (la > 0) {
            double ty = main.y - m.h() / 2 - ls * 1.6, x0 = main.x;
            label(env, "No." + two(Math.max(0, env.cut.line) + 1), x0, ty, ls, la, "left", accentOn(sc));
            double len = Math.min(m.w(), W * 0.3) * E.outExpo(clamp((lt - 0.25) / 0.5));
            env.line(new double[][]{{x0 + ls * 4, ty}, {x0 + ls * 4 + len, ty}}, sc.sub(), hair(), 0.7 * la);
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyCross
    private static BB cross(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        List<String> arr = slots(env.cut.text);
        if (arr.isEmpty()) return null;
        int ki = keyIndex(arr, p.s("mode"));
        double tr = 0.08;
        String A = join(arr, 0, ki), K = arr.get(ki), B = join(arr, ki + 1, arr.size());
        java.util.function.Function<String, Double> w100 = s -> s.isEmpty() ? 0.0 : JizuraKit.measure(it(s, font, 100, 0, 0).track(tr)).w();
        java.util.function.Function<String, Double> h100 = s -> s.isEmpty() ? 0.0 : JizuraKit.measure(it(s, font, 100, 0, 0).track(tr).vertical(true)).h();
        double kw = w100.apply(K), g100 = 100 * tr + 6;
        double Lx = (A.isEmpty() ? 0 : w100.apply(A) + g100) + kw / 2, Rx = (B.isEmpty() ? 0 : w100.apply(B) + g100) + kw / 2;
        double Ty = (A.isEmpty() ? 0 : h100.apply(A) + g100) + 50, By = (B.isEmpty() ? 0 : h100.apply(B) + g100) + 50;
        double size = Math.min(Math.min(W * 0.86 / ((Lx + Rx) / 100), H * 0.86 / ((Ty + By) / 100)), U * 0.2);
        double k = size / 100, cx = W / 2 - (Rx - Lx) * k / 2, cy = H / 2 - (By - Ty) * k / 2, out = tout(env), lw = hair();
        if (p.b("rules")) {
            double e = E.outExpo(clamp((lt - 0.1) / 0.7)) * out;
            if (e > 0) {
                env.line(new double[][]{{cx - W * e, cy}, {cx + W * e, cy}}, sc.sub(), lw, 0.28);
                env.line(new double[][]{{cx, cy - H * e}, {cx, cy + H * e}}, sc.sub(), lw, 0.28);
                env.circle(cx, cy, size * 0.78 * (0.6 + 0.4 * e), null, sc.sub(), lw, 0.5 * e);
            }
        }
        double g = size * tr + 6 * k;
        BB bb = null;
        if (!A.isEmpty()) bb = env.mainDraw(it(A, font, size, cx - kw * k / 2 - g, cy).align("right").track(tr).color(sc.fg()).mi(0));
        BB kb = env.mainDraw(it(K, font, size, cx, cy).track(tr).color(p.s("key").equals("accent") ? accentOn(sc) : sc.fg()).mi(1));
        bb = BB.union(bb, kb);
        if (!B.isEmpty()) bb = BB.union(bb, env.mainDraw(it(B, font, size, cx + kw * k / 2 + g, cy).align("left").track(tr).color(sc.fg()).mi(2)));
        if (!A.isEmpty()) { double h = h100.apply(A) * k; bb = BB.union(bb, env.mainDraw(it(A, font, size, cx, cy - size / 2 - g - h / 2).track(tr).vertical(true).color(sc.fg()).mi(3))); }
        if (!B.isEmpty()) { double h = h100.apply(B) * k; bb = BB.union(bb, env.mainDraw(it(B, font, size, cx, cy + size / 2 + g + h / 2).track(tr).vertical(true).color(sc.fg()).mi(4))); }
        label(env, two(ki + 1) + "×" + two(ki + 1), cx + size * 0.62, cy - size * 0.62, lsz() * 0.9, E.outCubic(clamp((lt - 0.35) / 0.3)) * out, "left", sc.sub());
        return bb;
    }

    // ------------------------------------------------------------------ tyBandHide
    private static BB bandHide(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        String txt = env.cut.text.trim();
        String mt = mainLines(txt, 10);
        int L = mt.split("\n", -1).length;
        double lead = 1.32;
        double size = Math.min(fit(mt, font, W * 0.8, H * 0.66, 0.02, lead), H * 0.3);
        BB bb = env.mainDraw(it(mt, font, size, W / 2, H / 2).lead(lead).track(0.02).color(sc.fg()));
        String bs = p.s("band");
        Color bandC = bs.equals("accent") ? plate(sc, sc.accent(), sc.ink(), sc.fg()) : bs.equals("ink") ? plate(sc, sc.ink(), sc.accent(), sc.fg()) : plate(sc, sc.fg(), sc.accent());
        Color tc = onCol(sc, bandC);
        String unit = flat(env.cut.lineText == null || env.cut.lineText.isBlank() ? txt : env.cut.lineText) + "　／　";
        double out = 1 - E.inExpo(env.pOut), k = p.d("k"), dir = p.d("dir"), speed = p.d("speed");
        for (int li = 0; li < L; li++) {
            double e = E.outExpo(clamp((lt - 0.12 - li * 0.08) / 0.5)) * out;
            if (e <= 0) continue;
            double yl = H / 2 + (li - (L - 1) / 2.0) * size * lead, h = size * (k + 0.08);
            double y0 = p.s("pos").equals("low") ? yl + size * (0.5 - k) : yl - size * 0.58;
            double d = (li % 2 == 1 ? -1 : 1) * dir, bw = (W + 20) * e, x0 = d > 0 ? -10 : W + 10 - bw;
            env.rect(x0, y0, bw, h, bandC, 1);
            double fs = h * 0.44, per = meas(unit, body(env), fs, 0.12, 1.3).w();
            if (per > 1) {
                double off = ((env.ltb * speed * d) % per + per) % per;
                Item run = it(unit.repeat((int) Math.min(40, Math.ceil(W * 2 / per) + 2)), body(env), fs, -per + off, y0 + h / 2).align("left").track(0.12).color(tc).noGhost();
                Shape_clip(env, x0, y0, bw, h, () -> env.draw(run));
            }
        }
        return bb;
    }

    private static void Shape_clip(Env env, double x, double y, double w, double h, Runnable r) {
        JizuraCompose2.clippedDo(env, new Rectangle2D.Double(x, y, w, h), r);
    }

    // ------------------------------------------------------------------ tyBaseline
    private static BB baseline(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        String txt = env.cut.text.trim();
        int nG = gcount(txt), nl = (int) p.d("nl");
        int per = Math.max(4, (int) Math.ceil(nG / (double) nl));
        String joined = nG <= 4 ? txt : brk(txt, per);
        List<String> lines = new ArrayList<>(List.of(joined.split("\n", -1)));
        if (hasLatin(txt) && !txt.matches(".*[\\u3040-\\u30ff\\u3400-\\u9fff].*")) {
            String[] ws = txt.split("\\s+");
            int k = Math.min(ws.length, nl);
            lines = new ArrayList<>();
            int idx = 0;
            for (int i = 0; i < k; i++) {
                int cnt = (ws.length - idx) / (k - i);
                lines.add(String.join(" ", java.util.Arrays.copyOfRange(ws, idx, idx + Math.max(1, cnt))));
                idx += Math.max(1, cnt);
            }
        }
        if (lines.size() > 6) lines = lines.subList(0, 6);
        int L = lines.size();
        double leadK = 1.62, size = Math.min(H * 0.7 / (L * leadK), H * 0.16);
        for (String l : lines) size = Math.min(size, JizuraKit.fitSize(l, font, W * 0.74, 1e6, new Item(l, font, 100).track(0.04)));
        double x0 = W * 0.14, mx = W * 0.06, out = tout(env), lw = hair(), ls = lsz();
        List<String> all = chars(String.join("", lines));
        int ki = keyIndex(all, "kanji"), kEnd = ki;
        while (kEnd + 1 < all.size() && JizuraKit.isKanji(all.get(kEnd + 1)) && JizuraKit.isKanji(all.get(ki))) kEnd++;
        int acc = 0;
        BB bb = null;
        for (int li = 0; li < L; li++) {
            String ln = lines.get(li);
            double y = H / 2 + (li - (L - 1) / 2.0) * size * leadK, yb = y + size * 0.6;
            double e = E.outExpo(clamp((lt - li * 0.1) / 0.6)) * out;
            if (e > 0) {
                env.line(new double[][]{{mx, yb}, {JizuraKit.lerp(mx, W - mx, e), yb}}, sc.sub(), lw, 0.55);
                label(env, two(li + 1), mx, y + size * 0.2, ls * 0.9, e, "left", sc.sub());
                if (p.b("right")) label(env, String.valueOf(gcount(ln)), W - mx, y + size * 0.2, ls * 0.9, e * 0.8, "right", sc.sub());
            }
            Item item = it(ln, font, size, x0, y).align("left").track(0.04).color(sc.fg()).mi(li * 4);
            bb = BB.union(bb, env.mainDraw(item));
            int n = chars(ln).size();
            if (p.b("key") && ki >= acc && ki < acc + n && e > 0) {
                GL[] gp = JizuraKit.layoutText(item).stream().filter(g -> !sp(g.ch)).toArray(GL[]::new);
                int i0 = 0;
                List<String> lc = chars(ln);
                for (int i = 0; i < ki - acc; i++) if (!sp(lc.get(i))) i0++;
                int i1 = Math.min(gp.length - 1, i0 + Math.min(kEnd, acc + n - 1) - ki);
                if (i0 < gp.length) {
                    double a0 = x0 + gp[i0].x - gp[i0].w / 2, a1 = x0 + gp[i1].x + gp[i1].w / 2;
                    double q = E.outExpo(clamp((lt - 0.35 - li * 0.1) / 0.45)) * out;
                    env.rect(a0, yb - Math.max(2, size * 0.035), (a1 - a0) * q, Math.max(3, size * 0.07), accentOn(sc), 1);
                }
            }
            acc += n;
        }
        return bb;
    }
}
