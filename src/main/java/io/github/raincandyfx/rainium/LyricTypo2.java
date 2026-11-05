package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.GL;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.Params;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

import static io.github.raincandyfx.rainium.LyricCompose.*;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricLayouts.def;
import static io.github.raincandyfx.rainium.LyricLayouts.fontPick;
import static io.github.raincandyfx.rainium.LyricTypo.*;

/** The rest of the typography layouts: scale steps, justified lines, index table, split type, erosion, rulers, tracking, counts, margins, rotated blocks, squares. Landscape only. */
final class LyricTypo2 {
    private LyricTypo2() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    private static boolean latin(String t) { return hasLatin(t) && !t.matches(".*[\\u3040-\\u30ff\\u3400-\\u9fff\\uff00-\\uffef].*"); }

    private static List<String> groupLines(List<String> units, int L) {
        int tot = 0;
        int[] lens = new int[units.size()];
        for (int i = 0; i < lens.length; i++) { lens[i] = gcount(units.get(i)); tot += lens[i]; }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int acc = 0;
        for (int i = 0; i < units.size(); i++) {
            cur.append(units.get(i)); acc += lens[i];
            double target = tot * (out.size() + 1.0) / L;
            if (out.size() < L - 1 && acc >= target - lens[i] * 0.4 && i < units.size() - 1) { out.add(cur.toString().trim()); cur.setLength(0); }
        }
        if (!cur.toString().trim().isEmpty()) out.add(cur.toString().trim());
        return out;
    }

    private static double wOf(String t, String font, double size, double track) { return meas(t, font, size, track, 1.2).w(); }

    static {
        def("tyScaleSteps", "級数上げ", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("dir", rng.pick("up", "up", "down")).with("labels", rng.chance(0.8)).with("ratio", rng.range(2.0, 2.8)),
            LyricTypo2::scaleSteps);
        def("tyJustify", "幅揃え", n -> n >= 3 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("rules", rng.chance(0.7)).with("acc", (double) rng.integer(0, 3)).with("align", rng.pick("center", "left")),
            LyricTypo2::justify);
        def("tyIndexTable", "一覧表", n -> n >= 2 && n <= 9,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("head", rng.chance(0.8)),
            LyricTypo2::indexTable);
        def("tySplitType", "断ち割り", n -> n >= 1 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("off", rng.range(0.08, 0.15) * rng.pick(1.0, -1.0)).with("cutY", rng.pick(0.02, -0.06, 0.08)).with("cap", rng.chance(0.75)),
            LyricTypo2::splitType);
        def("tyErode", "削り反復", n -> n >= 3 && n <= 12,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("mode", rng.pick("grow", "grow", "erode")).with("idx", rng.chance(0.7)),
            LyricTypo2::erode);
        def("tyVRuler", "縦目盛り", n -> n >= 1 && n <= 12,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("side", rng.pick(1.0, -1.0)),
            LyricTypo2::vRuler);
        def("tyFullTrack", "全幅字送り", n -> n >= 3 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif", "body")).with("caps", rng.chance(0.8)).with("rule", rng.chance(0.75)),
            LyricTypo2::fullTrack);
        def("tyStatCount", "字数表示", n -> n >= 1 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("nf", fontPick(rng, st, "display", "mono")).with("acc", rng.chance(0.5)),
            LyricTypo2::statCount);
        def("tyMargin", "余白", n -> n >= 1 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "body", "display")).with("pos", rng.pick("bl", "bl", "tr", "br", "lc")).with("mark", rng.chance(0.7)),
            LyricTypo2::margin);
        def("tyRotBlock", "回転ブロック", n -> n >= 4 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("fb", fontPick(rng, st, "display", "serif")).with("rot", rng.pick(-90.0, -90.0, 90.0)).with("accent", rng.chance(0.5)).with("rule", rng.chance(0.8)),
            LyricTypo2::rotBlock);
        def("tySquare", "方形組", n -> n >= 3 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("order", rng.pick("yoko", "yoko", "tate")).with("frame", rng.chance(0.75)).with("acc", rng.chance(0.7)),
            LyricTypo2::square);
    }

    // ------------------------------------------------------------------ tyScaleSteps
    private static BB scaleSteps(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        String txt = env.cut.text.trim();
        List<String> units = new ArrayList<>();
        for (String w : env.cut.words) if (!w.trim().isEmpty()) units.add(w.trim());
        List<String> cs = new ArrayList<>();
        for (String c : slots(txt)) if (!sp(c)) cs.add(c);
        if (units.size() < 2 || units.size() > 7) {
            if (cs.size() <= 7) units = cs;
            else { int k = Math.min(5, (int) Math.ceil(cs.size() / 3.0)); units = new ArrayList<>(List.of(brk(txt, (int) Math.ceil(cs.size() / (double) k)).split("\n"))); }
        }
        int k = units.size();
        double[] f = new double[k];
        double fmax = 0;
        for (int i = 0; i < k; i++) { double t = k > 1 ? i / (double) (k - 1) : 1; f[i] = Math.pow(p.d("ratio"), p.s("dir").equals("up") ? t : 1 - t); fmax = Math.max(fmax, f[i]); }
        double tr = 0.02, out = tout(env), lw = hair(), ls = lsz();
        boolean lat = latin(txt) && k > 1 && k < cs.size();
        double gapK = 0.16, gm = lat ? 1.6 : 0.6, tot = 0;
        for (int i = 0; i < k; i++) tot += wOf(units.get(i), font, 100 * f[i], tr) + (i > 0 ? 100 * gapK * f[i] * gm : 0);
        double base = Math.min(W * 0.86 / tot * 100, H * 0.52 / fmax);
        double x = W / 2 - tot * base / 100 / 2, yb = H / 2 + base * fmax * 0.36;
        double xa = x;
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            double s = base * f[i];
            if (i > 0) x += s * gapK * gm;
            items.add(it(units.get(i), font, s, x, yb - s * 0.46).align("left").track(tr).color(sc.fg()).mi(i * 2));
            x += wOf(units.get(i), font, s, tr);
        }
        double e = E.outExpo(clamp((lt - 0.05) / 0.7)) * out;
        if (e > 0) env.line(new double[][]{{xa, yb + base * 0.08}, {LyricKit.lerp(xa, x, e), yb + base * 0.08}}, sc.sub(), lw, 0.6);
        BB bb = null;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            bb = BB.union(bb, env.mainDraw(item));
            if (p.b("labels")) {
                double a = E.outCubic(clamp((lt - 0.25 - i * 0.07) / 0.3)) * out;
                if (a > 0) label(env, Math.round(item.size / (U / 1080) * 0.75) + "pt", item.x, yb + ls * 1.3, ls * 0.85, a, "left", sc.sub());
            }
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyJustify
    private static BB justify(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        String txt = env.cut.text.trim();
        int n = gcount(txt), L = n <= 5 ? 2 : n <= 10 ? 3 : 4;
        List<String> units = new ArrayList<>();
        if (latin(txt)) { String[] ws = txt.split("\\s+"); for (int i = 0; i < ws.length; i++) units.add((i > 0 ? " " : "") + ws[i]); }
        else {
            for (String w : env.cut.words) if (!w.trim().isEmpty()) units.add(w);
            if (units.size() < 2) { units.clear(); units.addAll(chars(txt)); }
        }
        L = Math.min(L, units.size());
        List<String> lines = groupLines(units, Math.max(1, L));
        for (int i = 1; i < lines.size(); i++) {
            while (!lines.get(i).isEmpty() && isPunct(chars(lines.get(i)).get(0))) {
                String c = chars(lines.get(i)).get(0);
                lines.set(i - 1, lines.get(i - 1) + c); lines.set(i, lines.get(i).substring(c.length()).trim());
            }
        }
        lines.removeIf(String::isEmpty);
        if (lines.size() < 2 && !latin(txt)) lines = new ArrayList<>(List.of(brk(txt, (int) Math.ceil(n / 2.0)).split("\n")));
        double Wb = Math.min(W * 0.62, H * 1.15), tr = 0.01, gapK = 0.1;
        double[] sizes = new double[lines.size()];
        double tot = 0;
        for (int i = 0; i < sizes.length; i++) { sizes[i] = Math.min(LyricKit.fitSize(lines.get(i), font, Wb, 1e6, new Item(lines.get(i), font, 100).track(tr)), H * 0.36); tot += sizes[i] * (1 + gapK); }
        tot -= sizes[sizes.length - 1] * gapK;
        double k = Math.min(1, H * 0.84 / tot);
        tot *= k;
        for (int i = 0; i < sizes.length; i++) sizes[i] *= k;
        boolean left = p.s("align").equals("left");
        double x0 = W / 2 - Wb * k / 2, out = tout(env), lw = hair(), y = H / 2 - tot / 2;
        int accI = (int) p.d("acc") % lines.size();
        BB bb = null;
        for (int li = 0; li < lines.size(); li++) {
            double s = sizes[li];
            Item item = it(lines.get(li), font, s, left ? x0 : W / 2, y + s / 2).align(left ? "left" : "center").track(tr).color(li == accI && lines.size() > 2 ? accentOn(sc) : sc.fg()).mi(li * 3);
            bb = BB.union(bb, env.mainDraw(item));
            if (p.b("rules") && li < lines.size() - 1) {
                double e = E.outExpo(clamp((lt - 0.15 - li * 0.08) / 0.6)) * out, ry = y + s + s * gapK * k * 0.5;
                if (e > 0) env.line(new double[][]{{W / 2 - Wb * k / 2 * e, ry}, {W / 2 + Wb * k / 2 * e, ry}}, sc.sub(), lw, 0.55);
            }
            y += s * (1 + gapK);
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyIndexTable
    private static BB indexTable(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt;
        List<String> chars = chars(strip(env.cut.text));
        int n = chars.size();
        if (n == 0) return null;
        double rowH = Math.min(H * 0.78 / n, U * 0.2), gs = rowH * 0.86, Wt = Math.min(W * 0.56, H * 1.0);
        double x0 = W / 2 - Wt / 2, x1 = W / 2 + Wt / 2, xg = x0 + Wt * 0.24, top = H / 2 - n * rowH / 2;
        double out = tout(env), lw = hair(), fs = clamp(rowH * 0.3, 11, 34);
        double e0 = E.outExpo(clamp(lt / 0.6)) * out;
        if (e0 > 0) {
            env.line(new double[][]{{x0, top}, {LyricKit.lerp(x0, x1, e0), top}}, sc.fg(), lw * 2, 0.9);
            env.line(new double[][]{{x0, top + n * rowH}, {LyricKit.lerp(x0, x1, e0), top + n * rowH}}, sc.fg(), lw * 2, 0.9);
            if (p.b("head")) {
                label(env, "No.", x0, top - fs * 0.9, fs * 0.8, e0, "left", sc.sub());
                label(env, "CODE", x1, top - fs * 0.9, fs * 0.8, e0, "right", sc.sub());
            }
        }
        BB bb = null;
        for (int i = 0; i < n; i++) {
            double y = top + (i + 0.5) * rowH;
            bb = BB.union(bb, env.mainDraw(it(chars.get(i), p.s("font"), gs, xg, y).color(sc.fg()).mi(i)));
            double a = E.outCubic(clamp((lt - 0.12 - i * 0.06) / 0.3)) * out;
            if (a <= 0) continue;
            label(env, two(i + 1), x0, y, fs, a, "left", i == 0 ? accentOn(sc) : sc.sub());
            String info = String.format("U+%04X", chars.get(i).codePointAt(0));
            double iw = meas(info, mono(env), fs, 0.12, 1.2).w();
            label(env, info, x1, y, fs, a, "right", sc.sub());
            double la = xg + gs * 0.72, lb = x1 - iw - fs * 0.8;
            if (lb > la) env.dashed(new double[][]{{la, y + fs * 0.2}, {LyricKit.lerp(la, lb, a), y + fs * 0.2}}, sc.sub(), Math.max(2, lw * 2), a, (float) Math.max(2, lw * 2), (float) Math.max(5, fs * 0.5));
            if (i < n - 1) env.line(new double[][]{{x0, top + (i + 1) * rowH}, {x1, top + (i + 1) * rowH}}, sc.sub(), lw, 0.22 * a);
        }
        return bb;
    }

    // ------------------------------------------------------------------ tySplitType
    private static BB splitType(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        String txt = env.cut.text.trim(), mt = mainLines(txt, 11);
        int L = mt.split("\n", -1).length;
        double lead = 1.16, size = Math.min(fit(mt, font, W * 0.76, H * 0.56, 0.03, lead), H * 0.3);
        double e = E.outExpo(clamp((lt - 0.12) / 0.7)), d = size * p.d("off") * e, gy = size * 0.05 * e;
        double[] ys = new double[L];
        for (int li = 0; li < L; li++) ys[li] = H / 2 + (li - (L - 1) / 2.0) * size * lead + size * p.d("cutY");
        BB bb = null;
        for (int pass = 0; pass < 2; pass++) {
            boolean top = pass == 0;
            Path2D.Double clip = new Path2D.Double();
            for (int li = 0; li < L; li++) {
                double a = ys[li] - size * lead / 2, b = ys[li] + size * lead / 2;
                if (top) { double from = li > 0 ? a : -H; clip.append(new java.awt.geom.Rectangle2D.Double(-W, from, W * 3, ys[li] - from), false); }
                else clip.append(new java.awt.geom.Rectangle2D.Double(-W, ys[li], W * 3, (li < L - 1 ? b : H * 2) - ys[li]), false);
            }
            Item item = it(mt, font, size, W / 2 + (top ? d : -d), H / 2 + (top ? -gy : gy)).lead(lead).track(0.03).color(sc.fg()).mi(0);
            final BB[] r = new BB[1];
            LyricCompose2.clippedDo(env, clip, () -> r[0] = env.mainDraw(item));
            bb = BB.union(bb, r[0]);
        }
        double out = tout(env), lw = hair();
        if (e * out > 0.01) {
            for (int li = 0; li < L; li++) {
                double q = E.outExpo(clamp((lt - 0.2 - li * 0.08) / 0.6)) * out;
                env.line(new double[][]{{W * 0.04, ys[li]}, {LyricKit.lerp(W * 0.04, W * 0.96, q), ys[li]}}, accentOn(sc), Math.max(1.2, lw * 1.2), 0.9);
            }
            if (p.b("cap")) {
                double ls = lsz() * 0.85;
                String cap = flat(env.cut.lineText == null || env.cut.lineText.isBlank() ? txt : env.cut.lineText);
                label(env, cap.length() > 48 ? cap.substring(0, 48) : cap, W * 0.96, ys[L - 1] + ls * 0.9, ls, out * E.outCubic(clamp((lt - 0.4) / 0.3)), "right", sc.sub());
            }
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyErode
    private static BB erode(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        List<String> arr = slots(env.cut.text);
        int nS = arr.size(), nonSp = 0;
        for (String c : arr) if (!sp(c)) nonSp++;
        int R = Math.min(5, Math.max(2, nonSp));
        List<Integer> lens = new ArrayList<>();
        for (int r = 0; r < R; r++) { int l = Math.max(1, (int) Math.round(nS * (r + 1.0) / R)); if (!lens.contains(l)) lens.add(l); }
        boolean er = p.s("mode").equals("erode");
        if (er) java.util.Collections.reverse(lens);
        List<String> rows = new ArrayList<>();
        for (int l : lens) { String t = join(arr, 0, l); if (!t.isEmpty()) rows.add(t); }
        int Rn = rows.size(), mainR = er ? 0 : Rn - 1;
        String full = String.join("", arr);
        double leadK = 1.34, size = Math.min(Math.min(LyricKit.fitSize(full, font, W * 0.74, 1e6, new Item(full, font, 100).track(0.04)), H * 0.8 / (Rn * leadK)), H * 0.16);
        double fw = wOf(full, font, size, 0.04), x0 = W / 2 - fw / 2, out = tout(env);
        BB bb = null;
        for (int r = 0; r < Rn; r++) {
            String t = rows.get(r);
            double y = H / 2 + (r - (Rn - 1) / 2.0) * size * leadK, tIn = r * 0.11;
            Item item = it(t, font, size, x0, y).align("left").track(0.04);
            if (r == mainR) bb = env.mainDraw(item.color(sc.fg()).mi(r * 2.5));
            else {
                double dist = Math.abs(r - mainR) / (double) Math.max(1, Rn - 1);
                double a = clamp((lt - tIn) / 0.12) * out * (0.75 - 0.45 * dist);
                if (a > 0) env.draw(item.color(sc.sub()).alpha(a).noGhost());
            }
            if (p.b("idx")) {
                double a = clamp((lt - tIn) / 0.12) * out;
                if (a > 0) label(env, two(gcount(t)), x0 + fw + size * 0.5, y, Math.min(lsz(), size * 0.4), a * 0.85, "left", r == mainR ? accentOn(sc) : sc.sub());
            }
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyVRuler
    private static BB vRuler(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font"), side = null;
        double sd = p.d("side");
        String txt = env.cut.text.trim().replaceAll("[\\s　]+", "　");
        double tr = 0.14;
        double size = Math.min(Math.min(LyricKit.fitSize(txt, font, W * 0.3, H * 0.78, new Item(txt, font, 100).track(tr).vertical(true)), W * 0.2), H * 0.2);
        double cx = W / 2 - sd * size * 0.9;
        Item item = it(txt, font, size, cx, H / 2).vertical(true).track(tr).color(sc.fg());
        BB bb = env.mainDraw(item);
        List<GL> gl = new ArrayList<>();
        for (GL g : LyricKit.layoutText(item)) if (!sp(g.ch)) gl.add(g);
        if (gl.isEmpty()) return bb;
        double out = tout(env), lw = hair(), rx = cx + sd * size * 0.85;
        double y0 = H / 2 + gl.get(0).y - size * 0.7, y1 = H / 2 + gl.get(gl.size() - 1).y + size * 0.7;
        double e = E.outExpo(clamp(lt / 0.7)) * out;
        if (e <= 0) return bb;
        env.line(new double[][]{{rx, y0}, {rx, LyricKit.lerp(y0, y1, e)}}, sc.sub(), lw, 0.8);
        double fs = clamp(size * 0.2, 10, 24);
        int n = gl.size();
        for (int i = 0; i < n; i++) {
            double a = E.outCubic(clamp((lt - 0.1 - i * 0.05) / 0.25)) * out;
            if (a <= 0) continue;
            double gy = H / 2 + gl.get(i).y;
            env.line(new double[][]{{rx, gy}, {rx + sd * size * 0.3, gy}}, sc.sub(), lw, 0.9 * a);
            if (i < n - 1) { double ym = (gy + H / 2 + gl.get(i + 1).y) / 2; env.line(new double[][]{{rx, ym}, {rx + sd * size * 0.14, ym}}, sc.sub(), lw, 0.5 * a); }
            label(env, fmtTime(env.cut.start + env.dur() * i / n), rx + sd * size * 0.45, gy, fs, a, sd > 0 ? "left" : "right", sc.sub());
        }
        double u = clamp(lt / Math.max(0.3, env.dur() * 0.92));
        double py = LyricKit.lerp(H / 2 + gl.get(0).y, H / 2 + gl.get(n - 1).y, u), ts = fs * 0.7;
        env.poly(new double[][]{{rx - sd * 2, py}, {rx - sd * ts * 1.4, py - ts}, {rx - sd * ts * 1.4, py + ts}}, accentOn(sc), e);
        return bb;
    }

    // ------------------------------------------------------------------ tyFullTrack
    private static BB fullTrack(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt;
        List<String> s = slots(env.cut.text);
        int k = s.size();
        double m0 = W * 0.06, size = Math.min(H * 0.2, (W - m0 * 2) / k * 0.8), m = m0 + size * 0.5, span = W - m * 2, out = tout(env), lw = hair();
        BB bb = null;
        int gi = 0;
        for (int i = 0; i < k; i++) {
            if (sp(s.get(i))) continue;
            double u = k > 1 ? i / (double) (k - 1) : 0.5;
            bb = BB.union(bb, env.mainDraw(it(s.get(i), p.s("font"), size, m + span * u, H / 2).color(sc.fg()).mi(gi++)));
        }
        double e = E.outExpo(clamp((lt - 0.05) / 0.8)) * out;
        if (e > 0) {
            if (p.b("rule")) { double y = H / 2 + size * 0.68; env.line(new double[][]{{W / 2 - span / 2 * e, y}, {W / 2 + span / 2 * e, y}}, sc.sub(), lw, 0.6); }
            if (p.b("caps")) {
                double ls = lsz();
                String cap = flat(env.cut.lineText == null || env.cut.lineText.isBlank() ? env.cut.text : env.cut.lineText);
                if (cap.length() > 40) cap = cap.substring(0, 40);
                env.draw(it(cap, body(env), ls, m, H / 2 - size * 0.62 - ls).align("left").track(0.14).color(sc.sub()).alpha(e * 0.9).noGhost());
                label(env, "No." + lineNo(env) + "  " + fmtTime(env.cut.start), W - m, H / 2 - size * 0.62 - ls, ls, e * 0.9, "right", sc.sub());
            }
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyStatCount
    private static BB statCount(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt;
        String txt = env.cut.text.trim(), mt = mainLines(txt, 8);
        int nG = gcount(txt);
        double out = tout(env), lw = hair(), ls = lsz();
        boolean lat = txt.chars().allMatch(c -> c < 128);
        int cnt = (int) Math.round(nG * E.outCubic(clamp((lt - 0.1) / 0.9)));
        double e = E.outExpo(clamp((lt - 0.05) / 0.7)) * out;
        double size = Math.min(fit(mt, p.s("font"), W * 0.5, H * 0.54, 0.03, 1.2), H * 0.2);
        Item item = it(mt, p.s("font"), size, W * 0.08, H / 2).align("left").track(0.03).lead(1.2).color(sc.fg());
        double rx = W * 0.65;
        if (e > 0) env.line(new double[][]{{rx, H / 2 - H * 0.22 * e}, {rx, H / 2 + H * 0.22 * e}}, sc.sub(), lw, 0.7);
        double nx = rx + W * 0.04, ny = H / 2 - H * 0.05, ns = H * 0.26;
        BB bb = env.mainDraw(item);
        ns = Math.min(ns, (W * 0.93 - nx) / Math.max(0.3, meas("00", p.s("nf"), 1, 0, 1.2).w()));
        if (e > 0) {
            env.draw(it(two(cnt), p.s("nf"), ns, nx, ny).align("left").color(p.b("acc") ? accentOn(sc) : sc.fg()).alpha(e).noGhost());
            double y2 = ny + ns * 0.62;
            env.draw(it(lat ? "CHARACTERS" : "文字 / CHARACTERS", lat ? mono(env) : body(env), ls, nx, y2).align("left").track(0.12).color(sc.sub()).alpha(e).noGhost());
            label(env, "LINE " + lineNo(env) + "  ─  " + fmtTime(env.cut.start), nx, y2 + ls * 1.8, ls * 0.85, e * 0.8, "left", sc.sub());
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyMargin
    private static BB margin(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font");
        String txt = env.cut.text.trim(), mt = gcount(txt) > 14 ? brk(txt, 12) : txt;
        double size = Math.min(U * 0.068, fit(mt, font, W * 0.66, H * 0.22, 0.1, 1.4));
        String pos = p.s("pos");
        boolean right = pos.equals("tr") || pos.equals("br");
        double y = pos.equals("tr") ? H * 0.2 : pos.equals("lc") ? H * 0.5 : H * 0.8, x = right ? W * 0.92 : W * 0.08;
        Item item = it(mt, font, size, x, y).align(right ? "right" : "left").track(0.1).lead(1.4).color(sc.fg());
        BB bb = env.mainDraw(item);
        var m = meas(mt, font, size, 0.1, 1.4);
        double out = tout(env), lw = hair(), ls = lsz() * 0.9, e = E.outExpo(clamp((lt - 0.2) / 0.9)) * out;
        if (e > 0) {
            double tx0 = right ? x - m.w() : x, tx1 = right ? x : x + m.w(), ly = y - m.h() / 2 - ls * 1.4;
            label(env, two(Math.max(0, env.cut.line) + 1) + "  —  " + fmtTime(env.cut.start), x, ly, ls, e * 0.9, right ? "right" : "left", sc.sub());
            double a0 = right ? tx0 - size * 0.8 : tx1 + size * 0.8, a1 = right ? W * 0.08 : W * 0.92;
            if ((a1 - a0) * (right ? -1 : 1) > W * 0.05) {
                env.line(new double[][]{{a0, y}, {LyricKit.lerp(a0, a1, e), y}}, sc.sub(), lw, 0.6);
                if (p.b("mark")) { double q = size * 0.16; env.rect(LyricKit.lerp(a0, a1, e) - q / 2, y - q / 2, q, q, accentOn(sc), e); }
            }
        }
        return bb;
    }

    // ------------------------------------------------------------------ tyRotBlock
    private static BB rotBlock(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt; String font = p.s("font"), fb = p.s("fb");
        List<String> arr = slots(env.cut.text);
        int n = arr.size();
        int cutAt = Math.max(1, (int) Math.round(n * 0.36));
        if (env.cut.words.size() >= 2) { int l0 = chars(env.cut.words.get(0).trim()).size(); if (l0 >= 1 && l0 <= Math.ceil(n * 0.55)) cutAt = l0; }
        while (cutAt < n - 1 && (isSmallKana(arr.get(cutAt)) || isPunct(arr.get(cutAt)))) cutAt++;
        String A = join(arr, 0, cutAt), B = join(arr, cutAt, n);
        if (A.isEmpty()) A = arr.get(0);
        if (B.isEmpty()) B = arr.get(n - 1);
        boolean labelA = false;
        if (latin(env.cut.text)) {
            String t = env.cut.text.trim();
            int s = t.indexOf(' ');
            if (s > 0) { A = t.substring(0, s); B = t.substring(s + 1).trim(); }
            else { A = "LINE " + lineNo(env); B = t; labelA = true; }
        }
        double Hb = H * 0.62, wA100 = meas(A, font, 100, 0.02, 1.2).w();
        double sizeA = Math.min(Math.min(Hb / wA100 * 100, W * 0.24), labelA ? U * 0.06 : 1e9);
        double gap = sizeA * 0.28, Wb = Math.min(W * 0.84, Hb * 2.4) - sizeA - gap;
        String bestT = B;
        double bestS = -1;
        int nB = gcount(B);
        for (int Lc = 1; Lc <= 3 && Lc <= nB; Lc++) {
            String bt = Lc == 1 ? B : brk(B, (int) Math.ceil(nB / (double) Lc));
            if (Lc > 1 && latin(B)) {
                String[] ws = B.split("\\s+");
                if (ws.length < Lc) break;
                List<String> us = new ArrayList<>();
                for (int i = 0; i < ws.length; i++) us.add((i > 0 ? " " : "") + ws[i]);
                bt = String.join("\n", groupLines(us, Lc));
            }
            double s = Math.min(fit(bt, fb, Wb, Hb, 0.02, 1.08), Hb * 0.6);
            if (bestS < 0 || s > bestS * 1.05) { bestS = s; bestT = bt; }
        }
        var mB = meas(bestT, fb, bestS, 0.02, 1.08);
        double wA = wA100 * sizeA / 100, tot = sizeA + gap + mB.w();
        double xA = W / 2 - tot / 2 + sizeA / 2, xB = xA + sizeA / 2 + gap, out = tout(env), lw = hair(), bh = Math.max(wA, mB.h());
        if (p.b("rule")) {
            double e = E.outExpo(clamp((lt - 0.1) / 0.6)) * out, rx = xA + sizeA / 2 + gap / 2;
            if (e > 0) env.line(new double[][]{{rx, H / 2 - bh / 2 * e}, {rx, H / 2 + bh / 2 * e}}, sc.sub(), lw, 0.7);
        }
        Item itA = it(A, font, sizeA, xA, H / 2).rot(p.d("rot")).track(0.02).mi(0);
        if (labelA) env.draw(itA.color(sc.sub()).alpha(E.outCubic(clamp(lt / 0.4)) * out).noGhost());
        else env.mainDraw(itA.color(p.b("accent") ? accentOn(sc) : sc.fg()));
        env.mainDraw(it(bestT, fb, bestS, xB, H / 2).align("left").lead(1.08).track(0.02).color(sc.fg()).mi(3));
        return box(xA - sizeA / 2, H / 2 - bh / 2, xB + mB.w(), H / 2 + bh / 2);
    }

    // ------------------------------------------------------------------ tySquare
    private static BB square(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt;
        List<String> chars = chars(strip(env.cut.text));
        int n = chars.size();
        if (n == 0) return null;
        boolean tate = p.s("order").equals("tate");
        int a = (int) Math.ceil(Math.sqrt(n)), b = (int) Math.ceil(n / (double) a);
        int cols = tate ? b : a, rows = tate ? a : b;
        double cell = Math.min(Math.min(W * 0.78 / cols, H * 0.74 / rows), U * 0.3);
        double x0 = W / 2 - cols * cell / 2, y0 = H / 2 - rows * cell / 2;
        int ki = keyIndex(chars, "kanji");
        double out = tout(env), lw = hair(), ls = lsz();
        BB bb = null;
        for (int i = 0; i < cols * rows; i++) {
            int c = tate ? cols - 1 - i / rows : i % cols, r = tate ? i % rows : i / cols;
            double x = x0 + (c + 0.5) * cell, y = y0 + (r + 0.5) * cell;
            if (i < n) bb = BB.union(bb, env.mainDraw(it(chars.get(i), p.s("font"), cell * 0.9, x, y).vertical(tate).color(p.b("acc") && i == ki ? accentOn(sc) : sc.fg()).mi(i)));
            else {
                double q = E.outBack(clamp((lt - 0.2 - i * 0.03) / 0.3), 2) * out, s = cell * 0.08;
                if (q > 0) { env.line(new double[][]{{x - s * q, y}, {x + s * q, y}}, sc.sub(), lw, 0.8); env.line(new double[][]{{x, y - s * q}, {x, y + s * q}}, sc.sub(), lw, 0.8); }
            }
        }
        if (p.b("frame")) {
            double e = E.outExpo(clamp((lt - 0.05) / 0.7)) * out;
            if (e > 0) {
                double xa = x0, xb = x0 + cols * cell, g = cell * 0.12;
                env.line(new double[][]{{xa, y0 - g}, {LyricKit.lerp(xa, xb, e), y0 - g}}, sc.fg(), Math.max(2, lw * 2.4), 0.9);
                env.line(new double[][]{{xb, y0 + rows * cell + g}, {LyricKit.lerp(xb, xa, e), y0 + rows * cell + g}}, sc.sub(), lw, 0.8);
                label(env, cols + "×" + rows, xb, y0 + rows * cell + g + ls * 1.1, ls * 0.9, e, "right", sc.sub());
                label(env, tate ? "TATE" : "YOKO", xa, y0 + rows * cell + g + ls * 1.1, ls * 0.9, e * 0.8, "left", sc.sub());
            }
        }
        return bb;
    }
}
