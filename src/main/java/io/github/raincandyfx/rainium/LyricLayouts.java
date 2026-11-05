package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.Params;
import io.github.raincandyfx.rainium.LyricKit.Rng;
import io.github.raincandyfx.rainium.LyricEngine.Cut;
import io.github.raincandyfx.rainium.LyricEngine.Style;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;

import static io.github.raincandyfx.rainium.LyricKit.DEG;
import static io.github.raincandyfx.rainium.LyricKit.TAU;
import static io.github.raincandyfx.rainium.LyricKit.a;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricKit.fitSize;
import static io.github.raincandyfx.rainium.LyricKit.glyphCount;
import static io.github.raincandyfx.rainium.LyricKit.lerp;
import static io.github.raincandyfx.rainium.LyricKit.lum;
import static io.github.raincandyfx.rainium.LyricKit.measure;
import static io.github.raincandyfx.rainium.LyricKit.mix;
import static io.github.raincandyfx.rainium.LyricKit.r;
import static io.github.raincandyfx.rainium.LyricKit.rr;
import static io.github.raincandyfx.rainium.LyricKit.rs;
import static io.github.raincandyfx.rainium.LyricKit.splitLines;

/**
 * The core layouts, ported from its source (see Credits)
 * to Java: center, mixed, vcols, marquee, tile, scatter, ring, wave, huge, labels, condensed, gloss,
 * type, diag, circle, stack and pill. Each has a plan (choices made once per cut) and a render
 * (what's drawn at a moment), written against the same kind of drawing calls (LyricKit.Env).
 */
final class LyricLayouts {
    private LyricLayouts() {}

    interface Plan { Params plan(Rng rng, Cut cut, Style st); }
    interface Render { BB render(Env env); }
    record Def(String name, IntPredicate fits, Plan plan, Render render) {}

    static final Map<String, Def> ALL = new LinkedHashMap<>();
    private static final double W = Env.W, H = Env.H;

    static List<String> fontsOf(Style st, String... roles) {
        List<String> out = new ArrayList<>();
        for (String r : roles) out.addAll(st.fonts.getOrDefault(r, List.of()));
        return out.isEmpty() ? st.fonts.getOrDefault("display", List.of("gothic_black")) : out;
    }

    private static String body(Env e) { return e.st.fonts.getOrDefault("body", List.of("gothic_med")).get(0); }
    private static String mono(Env e) { List<String> m = e.st.fonts.getOrDefault("mono", List.of()); return m.isEmpty() ? "mono" : m.get(0); }
    private static String serif(Env e) { return e.st.fonts.getOrDefault("serif", List.of("mincho")).get(0); }
    /** Japanese text runs together; English keeps single spaces so words stay readable. */
    private static String plain(String s) { return s.matches("(?s).*[A-Za-z].*") ? s.trim().replaceAll("\\s+", " ") : s.replaceAll("\\s+", ""); }
    private static String strip(String s) { return s.replaceAll("\\s+", ""); }
    private static String two(int n) { return String.format(Locale.ROOT, "%02d", n); }
    private static String fmtTime(double t) { int cs = (int) Math.max(0, t * 100); return String.format(Locale.ROOT, "%02d:%02d.%02d", cs / 6000, (cs / 100) % 60, cs % 100); }
    private static BB box(double x0, double y0, double x1, double y1) { return new BB(x0, y0, x1, y1); }

    static void def(String key, String name, IntPredicate fits, Plan plan, Render render) { ALL.put(key, new Def(name, fits, plan, render)); }

    static String fontPick(Rng rng, Style st, String... roles) { return rng.pick(fontsOf(st, roles)); }

    static {
        // ------------------------------------------------ center
        def("center", "中央", n -> true,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, rng.chance(0.7) ? new String[]{"display"} : new String[]{"serif"}))
                .with("sx", rng.pick(1.0, 1.0, 1.0, 1.25, 1.45, 0.78)).with("track", rng.range(0.02, 0.14)).with("sub", rng.chance(0.45))
                .with("under", rng.chance(0.3)).with("accent", rng.chance(0.18)).with("ox", rng.range(-0.05, 0.05)).with("oy", rng.range(-0.06, 0.06)),
            env -> {
                Params P = env.P(); 
                String text = splitLines(env.cut.text, 11);
                double size = Math.min(fitSize(text, P.s("font"), W * 0.84, H * 0.5, new Item("", "", 1).sx(P.d("sx")).track(P.d("track")).lead(1.2)), H * 0.33);
                BB bb = env.mainDraw(new Item(text, P.s("font"), size).at(W / 2 + P.d("ox") * W, H / 2 + P.d("oy") * H).sx(P.d("sx")).track(P.d("track")).lead(1.2).color(P.b("accent") ? env.sc.accent() : env.sc.fg()));
                if (bb != null && P.b("sub") && !env.cut.lineText.equals(env.cut.text)) {
                    env.draw(new Item(env.cut.lineText, body(env), clamp(H * 0.026, 16, 34)).at(W / 2 + P.d("ox") * W, bb.y1() + H * 0.07).track(0.22).color(env.sc.sub()).alpha(E.outCubic(env.pIn)).noGhost());
                }
                if (bb != null && P.b("under")) {
                    double e = E.outExpo(env.pIn * 1.2 - 0.2), o = E.inCubic(env.pOut);
                    if (e > 0 && o < 1) env.line(new double[][]{{lerp(bb.x0(), bb.x1(), o), bb.y1() + size * 0.14}, {lerp(bb.x0(), bb.x1(), e), bb.y1() + size * 0.14}}, env.sc.accent(), Math.max(2, size * 0.03), 1);
                }
                return bb;
            });

        // ------------------------------------------------ mixed
        def("mixed", "大小ミックス", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("fontBig", fontPick(rng, st, "display", "serif")).with("fontSmall", fontPick(rng, st, "serif", "body"))
                .with("mode", rng.pick("line", "stair", "line", "wave")).with("rotAmp", rng.range(2, 10)).with("smallK", rng.range(0.42, 0.6)).with("accentIdx", rng.integer(0, 20)),
            env -> {
                Params P = env.P();
                List<String> chars = new ArrayList<>();
                plain(env.cut.text).codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
                int n = chars.size(), rows = n > 9 ? 2 : 1, perRow = (int) Math.ceil(n / (double) rows);
                long s = env.cut.seed;
                double[] ks = new double[n], ws = new double[n];
                String[] fonts = new String[n];
                for (int i = 0; i < n; i++) {
                    String ch = chars.get(i);
                    boolean kanji = ch.matches("[㐀-鿿豈-﫿々〆ヶ]"), kata = ch.matches("[゠-ヿㇰ-ㇿｦ-ﾟ]"), latin = ch.matches("[A-Za-z0-9]"), punct = ch.matches("[、。，．,.!?！？…‥・「」『』（）()【】〈〉《》〔〕［］\\[\\]'\"“”‘’ー〜～:：;；\\-—―]");
                    double k = kanji ? 1 : kata ? 0.88 : latin ? 0.8 : punct ? 0.42 : P.d("smallK") + r(s, i, 3) * 0.14;
                    if ("ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ".contains(ch)) k *= 0.8;
                    fonts[i] = (kanji || kata) ? P.s("fontBig") : (r(s, i, 4) < 0.55 ? P.s("fontSmall") : P.s("fontBig"));
                    ks[i] = k;
                    ws[i] = LyricKit.adv(fonts[i], ch) * k * 0.96;
                }
                BB all = null;
                for (int rI = 0; rI < rows; rI++) {
                    int from = rI * perRow, to = Math.min(n, (rI + 1) * perRow);
                    double sumW = 0;
                    for (int i = from; i < to; i++) sumW += ws[i];
                    double base = Math.min(W * 0.86 / sumW, H * (rows > 1 ? 0.3 : 0.4));
                    double x = W / 2 - sumW * base / 2;
                    double baseline = H / 2 + base * 0.38 + (rI - (rows - 1) / 2.0) * base * 1.05;
                    for (int i = from; i < to; i++) {
                        int j = i - from;
                        double size = ks[i] * base;
                        double y = baseline - size / 2 + rs(s, i, 5) * base * 0.06;
                        if (P.s("mode").equals("stair")) y += (j - (to - from - 1) / 2.0) * base * 0.12;
                        if (P.s("mode").equals("wave")) y += Math.sin(j * 1.1) * base * 0.1;
                        String ch = chars.get(i);
                        boolean kanji = ch.matches("[㐀-鿿豈-﫿々〆ヶ]");
                        Color col = (i == P.i("accentIdx") % n && !kanji) ? env.sc.accent() : env.sc.fg();
                        all = BB.union(all, env.mainDraw(new Item(ch, fonts[i], size).at(x + ws[i] * base / 2, y).rot(rs(s, i, 6) * P.d("rotAmp")).color(col).mi(i)));
                        x += ws[i] * base;
                    }
                }
                return all;
            });

        // ------------------------------------------------ vcols
        def("vcols", "縦書き", n -> n <= 18,
            (rng, cut, st) -> {
                int n = glyphCount(cut.text);
                String variant = n <= 5 ? rng.pick("repeat", "repeat", "split") : n <= 9 ? rng.pick("split", "repeat") : "split";
                return new Params().with("variant", variant).with("cols", n <= 4 ? rng.pick(3, 5, 5) : 3).with("font", fontPick(rng, st, "serif", "serif", "display"))
                    .with("side", rng.pick("same", "outline", "dim")).with("perCol", rng.integer(3, 6));
            },
            env -> {
                Params P = env.P(); String text = strip(env.cut.text);
                int n = glyphCount(text);
                if (P.s("variant").equals("repeat") && n <= 9) {
                    int cols = P.i("cols");
                    double size = Math.min(H * 0.8 / (n * 1.04), W * 0.86 / (cols * 1.75));
                    BB bb = null;
                    double mid = (cols - 1) / 2.0;
                    for (int i = 0; i < cols; i++) {
                        boolean side = i != Math.round(mid);
                        Item it = new Item(text, P.s("font"), size).at(W / 2 + (i - mid) * size * 1.75, H / 2).vertical(true).track(0.04).color(env.sc.fg()).mi(Math.abs(i - mid) * 2);
                        if (side && P.s("side").equals("outline")) { it.fill = false; it.stroke = Math.max(1.2, size * 0.012); }
                        if (side && P.s("side").equals("dim")) it.alpha = 0.38;
                        BB rb = env.mainDraw(it);
                        if (!side) bb = rb;
                    }
                    return bb;
                }
                int per = Math.max(2, Math.min(P.i("perCol") + 1, (int) Math.ceil(n / Math.ceil(n / 7.0))));
                List<String> arr = new ArrayList<>();
                text.codePoints().forEach(cp -> arr.add(new String(Character.toChars(cp))));
                List<String> colsArr = new ArrayList<>();
                for (int i = 0; i < arr.size(); i += per) colsArr.add(String.join("", arr.subList(i, Math.min(arr.size(), i + per))));
                String t2 = String.join("\n", colsArr);
                double size = Math.min(H * 0.78 / (per * 1.03), W * 0.8 / (colsArr.size() * 1.4));
                double colH = per * size * 1.03;
                return env.mainDraw(new Item(t2, P.s("font"), size).at(W / 2, H / 2 - colH / 2).vertical(true).lead(1.4).align("left").track(0.03).color(env.sc.fg()));
            });

        // ------------------------------------------------ marquee
        def("marquee", "流れる帯", n -> n <= 12,
            (rng, cut, st) -> new Params().with("rows", rng.pick(2, 4, 4, 2)).with("rowStyle", rng.pick("outline", "dim", "box")).with("speed", rng.range(0.5, 1.2))
                .with("font", fontPick(rng, st, "display")).with("sx", rng.pick(1.25, 1.45, 1.6)),
            env -> {
                Params P = env.P(); String text = env.cut.text;
                double size = Math.min(fitSize(text, P.s("font"), W * 0.84, H * 0.3, new Item("", "", 1).sx(P.d("sx")).track(0.05)), H * 0.3);
                double rs = size * 0.42, lb = env.ltb;
                String unit = text + "　";
                double period = measure(new Item(unit, P.s("font"), rs).sx(P.d("sx")).track(0.05)).w() + rs * 0.05;
                int reps = (int) Math.ceil((W * 2.4) / period) + 1;
                double[] ys = P.i("rows") == 2 ? new double[]{-1, 1} : new double[]{-2, -1, 1, 2};
                for (int rI = 0; rI < ys.length; rI++) {
                    double k = ys[rI];
                    double y = H / 2 + Math.signum(k) * (size * 0.5 + rs * 0.95) + (Math.abs(k) - 1) * Math.signum(k) * rs * 1.25;
                    double al = clamp((lb - Math.abs(k) * 0.05) / 0.12);
                    if (al <= 0) continue;
                    int dir = rI % 2 == 1 ? 1 : -1;
                    double off = (((lb * P.d("speed") * W * 0.22 * dir + rI * period * 0.37) % period) + period) % period - period / 2;
                    Item row = new Item(unit.repeat(reps), P.s("font"), rs).at(W / 2 + off, y).sx(P.d("sx")).track(0.05).noGhost().alpha(al);
                    if (P.s("rowStyle").equals("outline")) { row.outline(Math.max(1.2, rs * 0.02), env.sc.fg()); row.alpha = al * 0.9; }
                    else if (P.s("rowStyle").equals("dim")) { row.color = env.sc.sub(); row.alpha = al * 0.35; }
                    else { env.rect(-10, y - rs * 0.62, W + 20, rs * 1.24, env.sc.ink(), al); row.color = env.sc.bg(); }
                    env.draw(row);
                }
                return env.mainDraw(new Item(text, P.s("font"), size).at(W / 2, H / 2).sx(P.d("sx")).track(0.05).color(env.sc.fg()));
            });

        // ------------------------------------------------ tile
        def("tile", "敷き詰め", n -> n <= 12,
            (rng, cut, st) -> new Params().with("unit", rng.pick("chunk", "line", "chunk")).with("knock", rng.pick("stroke", "box")).with("flicker", rng.chance(0.6))
                .with("font", fontPick(rng, st, "display")).with("tileFont", fontPick(rng, st, "serif", "body", "display")).with("rowsN", rng.pick(12, 14, 16, 18)),
            env -> {
                Params P = env.P(); String text = env.cut.text; double lb = env.ltb;
                String unitText = (P.s("unit").equals("line") ? env.cut.lineText : text) + "　";
                int rowsN = P.i("rowsN");
                double rowH = H / rowsN, ts = rowH * 0.72;
                double period = measure(new Item(unitText, P.s("tileFont"), ts).track(0.02)).w();
                int reps = (int) Math.ceil(W * 1.6 / period) + 2;
                String run = unitText.repeat(reps);
                for (int rI = 0; rI <= rowsN; rI++) {
                    double ap = r(env.cut.seed, rI, 91) * env.cut.inDur * 1.6;
                    if (lb < ap) continue;
                    if (P.b("flicker") && r(env.cut.seed, env.step, rI, 92) < 0.16) continue;
                    int dir = rI % 2 == 1 ? 1 : -1;
                    double off = ((((rI % 2) * period * 0.5 + lb * 26 * dir) % period) + period) % period;
                    env.draw(new Item(run, P.s("tileFont"), ts).track(0.02).align("left").at(-period + off - period * 0.5, (rI + 0.5) * rowH).color(env.sc.sub()).alpha(0.42 * clamp((lb - ap) / 0.1)).noGhost());
                }
                String mt = text;
                double size = Math.min(fitSize(mt, P.s("font"), W * 0.8, H * 0.34, new Item("", "", 1).track(0.04).lead(1.15)), H * 0.3);
                Item it = new Item(mt, P.s("font"), size).at(W / 2, H / 2).track(0.04).lead(1.15).color(env.sc.fg());
                if (P.s("knock").equals("box")) {
                    LyricKit.Measure m = measure(it);
                    double e = E.outExpo(env.pIn * 1.4);
                    env.rect(W / 2 - (m.w() / 2 + size * 0.35) * e, H / 2 - m.h() / 2 - size * 0.28, (m.w() + size * 0.7) * e, m.h() + size * 0.56, env.sc.bg(), 1);
                } else {
                    Item k = it.copy(); k.fill = false; k.stroke = size * 0.16; k.strokeColor = env.sc.bg(); k.ghost = false;
                    env.mainDraw(k);
                }
                return env.mainDraw(it);
            });

        // ------------------------------------------------ scatter
        def("scatter", "散らし", n -> n >= 2 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("fontB", fontPick(rng, st, "serif", "display")).with("extras", rng.chance(0.65)),
            env -> {
                Params P = env.P(); long s = env.cut.seed;
                List<String> chars = new ArrayList<>();
                strip(env.cut.text).codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
                int n = chars.size();
                if (P.b("extras")) {
                    for (int k = 0; k < 9; k++) {
                        double ap = r(s, k, 81) * env.dur() * 0.5;
                        if (env.ltb < ap) continue;
                        boolean top = r(s, k, 82) < 0.5;
                        env.draw(new Item(env.cut.text, body(env), rr(H * 0.022, H * 0.045, s, k, 83)).at(rr(W * 0.08, W * 0.92, s, k, 84), top ? rr(H * 0.08, H * 0.26, s, k, 85) : rr(H * 0.74, H * 0.92, s, k, 85)).rot(rs(s, k, 86) * 18).color(env.sc.sub()).alpha(0.75).noGhost());
                    }
                }
                double base = Math.min(H * 0.3, W * 0.9 / n * 1.15);
                BB bb = null;
                for (int i = 0; i < n; i++) {
                    String ch = chars.get(i);
                    double x = W * (0.1 + 0.8 * (i + 0.5) / n) + rs(s, i, 71) * W * 0.035;
                    double y = H / 2 + rs(s, i, 72) * H * 0.18;
                    double k = 0.62 + r(s, i, 73) * 0.85 * (ch.matches("[㐀-鿿豈-﫿々〆ヶ]") ? 1 : 0.7);
                    bb = BB.union(bb, env.mainDraw(new Item(ch, i % 3 == 1 ? P.s("fontB") : P.s("font"), base * k).at(x, y).rot(rs(s, i, 74) * 24).color(r(s, i, 75) < 0.15 ? env.sc.accent() : env.sc.fg()).mi(i)));
                }
                return bb;
            });

        // ------------------------------------------------ ring
        def("ring", "円環", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("orient", rng.pick("tangent", "tangent", "upright")).with("center", rng.pick("word", "disc", "word", "none"))
                .with("speed", rng.range(4, 12) * rng.pick(1, -1)).with("R", rng.range(0.28, 0.35)).with("font", fontPick(rng, st, "display", "serif")).with("fontC", fontPick(rng, st, "display", "serif")),
            env -> {
                Params P = env.P(); String text = plain(env.cut.text);
                double R = Math.min(H * P.d("R"), W * 0.4), cx = W / 2, cy = H / 2, lb = env.ltb;
                int n = glyphCount(text);
                List<String> unit = new ArrayList<>();
                (text + "・").codePoints().forEach(cp -> unit.add(new String(Character.toChars(cp))));
                double sizeRing = Math.min(H * 0.07, TAU * R / ((n + 1) * 1.25));
                int cnt = Math.max(unit.size(), Math.min(44, (int) Math.floor(TAU * R / (sizeRing * 1.2))));
                env.circle(cx, cy, R * 0.86, null, env.sc.sub(), 1.2 * 2.4, 0.55);
                env.circle(cx, cy, R * 1.15, null, env.sc.sub(), 1.2 * 2.4, 0.35);
                BB bb = null;
                if (P.s("center").equals("disc")) {
                    double e = E.outBack(clamp(env.lt / (env.cut.inDur * 0.9)), 1.6) * (1 - E.inCubic(env.pOut));
                    env.circle(cx, cy, R * 0.72 * e, env.sc.accent(), null, 0, 1);
                    double size = fitSize(text, P.s("fontC"), R * 1.15, R * 0.8);
                    bb = env.mainDraw(new Item(text, P.s("fontC"), Math.min(size, H * 0.2)).at(cx, cy).color(env.sc.bg()).mi(0));
                } else if (P.s("center").equals("word")) {
                    double size = fitSize(text, P.s("fontC"), R * 1.3, R * 0.85);
                    bb = env.mainDraw(new Item(text, P.s("fontC"), Math.min(size, H * 0.22)).at(cx, cy).color(env.sc.fg()).mi(0));
                }
                for (int i = 0; i < cnt; i++) {
                    String ch = unit.get(i % unit.size());
                    double ang = (double) i / cnt * 360 + lb * P.d("speed") - 90, rad = ang * DEG;
                    Item it = new Item(ch, P.s("font"), sizeRing).at(cx + Math.cos(rad) * R, cy + Math.sin(rad) * R).rot(P.s("orient").equals("tangent") ? ang + 90 : 0).color(ch.equals("・") ? env.sc.accent() : env.sc.fg()).mi(i * 0.25);
                    it.noHold = true;
                    BB b = env.mainDraw(it);
                    if (bb == null) bb = BB.union(bb, b);
                }
                return bb != null ? bb : box(cx - R, cy - R, cx + R, cy + R);
            });

        // ------------------------------------------------ wave
        def("wave", "波の軌跡", n -> n >= 2 && n <= 16,
            (rng, cut, st) -> new Params().with("amp", rng.range(0.08, 0.17)).with("freq", rng.range(0.8, 1.6)).with("trail", rng.pick(5, 7, 9)).with("font", fontPick(rng, st, "display"))
                .with("travel", rng.range(0.25, 0.5) * rng.pick(1, -1)),
            env -> {
                Params P = env.P(); String text = plain(env.cut.text);
                List<String> chars = new ArrayList<>();
                text.codePoints().forEach(cp -> chars.add(new String(Character.toChars(cp))));
                int n = chars.size();
                double size = Math.min(H * 0.2, W * 0.72 / n);
                double du = size * 1.05 / W, lb = env.ltb, u0 = 0.5 - (env.lt / env.dur() - 0.5) * P.d("travel");
                java.util.function.DoubleFunction<double[]> path = u -> new double[]{W * u, H / 2 + H * P.d("amp") * Math.sin(TAU * P.d("freq") * u + lb * 1.3)};
                java.util.function.DoubleUnaryOperator angAt = u -> { double[] p = path.apply(u - 0.002), q = path.apply(u + 0.002); return Math.atan2(q[1] - p[1], q[0] - p[0]) / DEG; };
                BB bb = null;
                int trail = P.i("trail");
                for (int k = trail; k >= 1; k--) {
                    for (int i = 0; i < n; i++) {
                        double u = u0 + (i - (n - 1) / 2.0) * du + k * du * 0.2 * Math.signum(P.d("travel"));
                        double[] p = path.apply(u);
                        env.draw(new Item(chars.get(i), P.s("font"), size * (1 - k * 0.035)).at(p[0], p[1]).rot(angAt.applyAsDouble(u)).color(env.sc.sub()).alpha(0.5 * (1 - k / (trail + 1.0)) * E.outCubic(env.pIn)).noGhost());
                    }
                }
                for (int i = 0; i < n; i++) {
                    double u = u0 + (i - (n - 1) / 2.0) * du;
                    double[] p = path.apply(u);
                    bb = BB.union(bb, env.mainDraw(new Item(chars.get(i), P.s("font"), size).at(p[0], p[1]).rot(angAt.applyAsDouble(u)).color(env.sc.fg()).mi(i * 0.5)));
                }
                return bb;
            });

        // ------------------------------------------------ huge
        def("huge", "画面突き抜け", n -> n <= 8,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("dir", rng.pick(1, -1)).with("label", rng.chance(0.8)),
            env -> {
                Params P = env.P(); String text0 = plain(env.cut.text);
                int n = glyphCount(text0);
                String text = n >= 5 ? splitLines(text0, (int) Math.ceil(n / 2.0)) : text0;
                int lines = text.split("\n").length;
                double size = lines > 1 ? Math.min(H * 0.56, W * 1.2 / (Math.ceil(n / 2.0) * 0.98)) : Math.min(H * 0.98, W * 1.3 / (n * 0.96));
                double u = env.lt / env.dur();
                BB bb = env.mainDraw(new Item(text, P.s("font"), size).at(W / 2 + (0.5 - u) * W * 0.16 * P.d("dir"), H / 2 + H * 0.02).lead(0.98).track(-0.02).color(env.sc.fg()));
                if (P.b("label")) {
                    double ls = clamp(H * 0.028, 16, 30);
                    double al = E.outCubic(clamp((env.lt - env.cut.inDur * 0.5) / 0.2)) * (1 - env.pOut);
                    LyricKit.Measure lt = measure(new Item(env.cut.text, body(env), ls).track(0.12));
                    env.rect(W * 0.05, H * 0.86 - ls, lt.w() + ls * 1.4, ls * 2, env.sc.ink(), al);
                    env.draw(new Item(env.cut.text, body(env), ls).track(0.12).align("left").at(W * 0.05 + ls * 0.7, H * 0.86).color(env.sc.bg()).alpha(al).noGhost());
                }
                return bb;
            });

        // ------------------------------------------------ labels
        def("labels", "ラベル貼り", n -> n >= 1 && n <= 16,
            (rng, cut, st) -> new Params().with("variant", rng.pick("radial", "rows", "scatter")).with("unit", glyphCount(cut.text) <= 6 ? "char" : rng.pick("char", "word"))
                .with("center", rng.pick("orb", "word", "none")).with("font", fontPick(rng, st, "display", "body")).with("fontC", fontPick(rng, st, "display")),
            env -> {
                Params P = env.P(); long s = env.cut.seed; double lb = env.ltb;
                String text = plain(env.cut.text);
                List<String> units = new ArrayList<>();
                if (P.s("unit").equals("char")) text.codePoints().forEach(cp -> { String c = new String(Character.toChars(cp)); if (!c.isBlank() && !c.matches("[、。，．,.!?！？…‥・「」『』（）()【】〈〉《》〔〕［］\\[\\]'\"“”‘’ー〜～:：;；\\-—―]")) units.add(c); });
                else if (!env.cut.words.isEmpty()) units.addAll(env.cut.words);
                else units.add(text);
                if (units.isEmpty()) units.add(text);
                double out = 1 - E.inCubic(env.pOut);
                java.util.function.Consumer<double[]> noop = x -> { };
                class Label {
                    void draw(String u, double x, double y, double rot, double fs, double q) {
                        if (q <= 0) return;
                        LyricKit.Measure m = measure(new Item(u, P.s("font"), fs).track(0.04));
                        double w = m.w() + fs * 0.7, h = fs * 1.36;
                        AffineTransform keep = env.g.getTransform();
                        env.g.translate(x, y); env.g.rotate(rot * DEG); env.g.scale(q, q);
                        env.rect(-w / 2, -h / 2, w, h, env.sc.ink(), 1);
                        env.draw(new Item(u, P.s("font"), fs).track(0.04).at(0, 0).color(env.sc.bg()).noGhost());
                        env.g.setTransform(keep);
                    }
                }
                Label lab = new Label();
                BB bb = null;
                if (P.s("variant").equals("radial")) {
                    int m = Math.max(units.size(), 10);
                    double R = Math.min(H * 0.3, W * 0.36), fs = Math.min(H * 0.062, W * 0.052);
                    if (P.s("center").equals("orb")) { double e = E.outBack(clamp(env.lt / 0.35), 1.4) * out; env.circle(W / 2, H / 2, R * 0.52 * e, env.sc.accent(), null, 0, 1); }
                    for (int i = 0; i < m; i++) {
                        double ang = (double) i / m * 360 + lb * 7 - 90;
                        double q = E.outBack(clamp((env.lt - i * 0.025) / 0.22), 2) * out;
                        lab.draw(units.get(i % units.size()), W / 2 + Math.cos(ang * DEG) * R, H / 2 + Math.sin(ang * DEG) * R, ang, fs, q);
                    }
                    if (P.s("center").equals("word")) bb = env.mainDraw(new Item(text, P.s("fontC"), Math.min(fitSize(text, P.s("fontC"), R * 1.1, R * 0.7), H * 0.18)).at(W / 2, H / 2).color(env.sc.fg()));
                    return bb != null ? bb : box(W / 2 - R, H / 2 - R, W / 2 + R, H / 2 + R);
                }
                if (P.s("variant").equals("rows")) {
                    int k = units.size();
                    double fs = Math.min(H * 0.1, H * 0.7 / (k * 1.5));
                    for (int i = 0; i < k; i++) {
                        double q = E.outBack(clamp((env.lt - i * 0.05) / 0.22), 2) * out;
                        lab.draw(units.get(i), W / 2 + rs(s, i, 5) * W * 0.12, H / 2 + (i - (k - 1) / 2.0) * fs * 1.55, rs(s, i, 6) * 4, fs, q);
                    }
                    return box(W * 0.3, H / 2 - k * fs * 0.8, W * 0.7, H / 2 + k * fs * 0.8);
                }
                double fs = H * 0.085;
                for (int i = 0; i < units.size(); i++) {
                    double q = E.outBack(clamp((env.lt - i * 0.05) / 0.22), 2) * out;
                    lab.draw(units.get(i), W * (0.15 + 0.7 * ((i + 0.5) / units.size())) + rs(s, i, 7) * W * 0.04, H / 2 + rs(s, i, 8) * H * 0.25, rs(s, i, 9) * 22, fs * (0.8 + r(s, i, 10) * 0.5), q);
                }
                return box(W * 0.15, H * 0.3, W * 0.85, H * 0.7);
            });

        // ------------------------------------------------ condensed
        def("condensed", "縦長圧縮", n -> n <= 10,
            (rng, cut, st) -> {
                int n = glyphCount(cut.text);
                return new Params().with("count", n <= 4 ? rng.pick(3, 2, 1) : n <= 7 ? rng.pick(2, 1) : 1).with("sx", rng.range(0.42, 0.58)).with("sy", rng.range(1.1, 1.3)).with("font", fontPick(rng, st, "display", "body"));
            },
            env -> {
                Params P = env.P(); String text = plain(env.cut.text);
                int count = P.i("count");
                double slot = W * 0.92 / count;
                double size = Math.min(fitSize(text, P.s("font"), slot * 0.94, H * 0.8, new Item("", "", 1).sx(P.d("sx")).sy(P.d("sy")).track(0.04)), H * 0.62);
                BB bb = null;
                int[] order = {1, 0, 2, 3};
                for (int i = 0; i < count; i++)
                    bb = BB.union(bb, env.mainDraw(new Item(text, P.s("font"), size).sx(P.d("sx")).sy(P.d("sy")).track(0.04).at(W / 2 + (i - (count - 1) / 2.0) * slot, H / 2).color(env.sc.fg()).mi(count > 1 ? order[i] * 2 : 0)));
                return bb;
            });

        // ------------------------------------------------ gloss
        def("gloss", "注釈", n -> n <= 12,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("side", rng.pick("right", "left")).with("bgText", rng.chance(0.6)).with("vertNote", rng.chance(0.45)),
            env -> {
                Params P = env.P(); String text = env.cut.text; double lb = env.ltb;
                if (P.b("bgText")) {
                    for (int rI = 0; rI < 3; rI++) {
                        double bs = H * 0.3;
                        env.draw(new Item(plain(text).repeat(6), P.s("font"), bs).at(W / 2 + ((lb * 20 * (rI % 2 == 1 ? 1 : -1)) % (bs * 2)), H * (0.18 + rI * 0.32)).color(env.sc.dim()).noGhost());
                    }
                }
                boolean right = P.s("side").equals("right");
                double size = Math.min(fitSize(text, P.s("font"), W * 0.5, H * 0.3, new Item("", "", 1).track(0.03)), H * 0.24);
                BB bb = env.mainDraw(new Item(text, P.s("font"), size).at(right ? W * 0.4 : W * 0.6, H * 0.54).track(0.03).color(env.sc.fg()));
                if (bb == null) return null;
                double e = E.outExpo(clamp((env.lt - env.cut.inDur * 0.4) / 0.45)) * (1 - E.inCubic(env.pOut));
                if (e <= 0) return bb;
                double ax = right ? bb.x1() + size * 0.1 : bb.x0() - size * 0.1, ay = bb.y0() + size * 0.2;
                double nx = right ? Math.min(W * 0.9, bb.x1() + W * 0.1) : Math.max(W * 0.1, bb.x0() - W * 0.1), ny = Math.max(H * 0.12, bb.y0() - H * 0.12);
                double mx = lerp(ax, nx, 0.45);
                env.polyPartial(new double[][]{{ax, ay}, {mx, ay}, {nx, ny}}, e, env.sc.sub(), 1.3 * 2.4, 1);
                env.circle(ax, ay, 4 * 2.4, env.sc.accent(), null, 0, e);
                String note = env.cut.note != null ? env.cut.note : env.cut.lineText;
                double ns = clamp(H * 0.024, 14, 26);
                String al = right ? "left" : "right";
                env.draw(new Item("【" + plain(text) + "】", serif(env), ns * 1.2).align(al).at(nx, ny - ns * 1.2).color(env.sc.fg()).alpha(e).noGhost());
                if (P.b("vertNote")) env.draw(new Item(env.cut.lineText, serif(env), ns).vertical(true).align("left").at(nx + (right ? ns : -ns), ny + ns * 0.8).color(env.sc.sub()).alpha(e).noGhost());
                else env.draw(new Item(note, body(env), ns).align(al).at(nx, ny + ns * 0.4).track(0.08).color(env.sc.sub()).alpha(e).noGhost());
                env.draw(new Item("No." + two(env.cut.line + 1), mono(env), ns * 0.8).align(al).at(nx, ny + ns * 2).color(env.sc.accent()).alpha(e).noGhost());
                return bb;
            });

        // ------------------------------------------------ type
        def("type", "タイプ", n -> n <= 28,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "body", "serif", "mono")).with("align", rng.pick("left", "center")).with("prompt", rng.chance(0.6)),
            env -> {
                Params P = env.P();
                String text = splitLines(env.cut.text, 14);
                double size = Math.min(H * 0.11, fitSize(text, P.s("font"), W * 0.74, H * 0.36, new Item("", "", 1).track(0.06).lead(1.35)));
                boolean left = P.s("align").equals("left");
                double x = left ? W * 0.13 : W / 2;
                if (P.b("prompt")) env.draw(new Item(">", mono(env), size * 0.8).at((left ? x : x - measure(new Item(text, P.s("font"), size).track(0.06)).w() / 2) - size * 0.9, H / 2 - (text.split("\n").length - 1) * size * 0.67).color(env.sc.accent()).noGhost());
                Item it = new Item(text, P.s("font"), size).at(x, H / 2).align(left ? "left" : "center").track(0.06).lead(1.35).color(env.sc.fg());
                if (env.cut.enter == LyricEngine.Enter.POP || env.cut.enter == LyricEngine.Enter.RISE) it.enter = "TYPE";
                BB bb = env.mainDraw(it);
                double ms = clamp(H * 0.02, 12, 20);
                env.draw(new Item("LINE " + two(env.cut.line + 1) + " ─ " + fmtTime(env.t), mono(env), ms).align("left").at(W * 0.13, H * 0.8).color(env.sc.sub()).alpha(0.8).noGhost());
                return bb;
            });

        // ------------------------------------------------ diag
        def("diag", "斜め帯", n -> n <= 14,
            (rng, cut, st) -> new Params().with("ang", rng.range(10, 22) * rng.pick(1, -1)).with("band", rng.pick("accent", "ink")).with("second", rng.chance(0.7)).with("font", fontPick(rng, st, "display")),
            env -> {
                Params P = env.P(); String text = env.cut.text; double lb = env.ltb;
                Color bandCol = P.s("band").equals("accent") ? env.sc.accent() : env.sc.ink();
                Color txtCol = lum(bandCol) > 0.5 ? (lum(env.sc.bg()) < 0.5 ? env.sc.bg() : new Color(0x111111)) : (lum(env.sc.fg()) > 0.5 ? env.sc.fg() : Color.WHITE);
                double size = Math.min(fitSize(text, P.s("font"), W * 0.72, H * 0.24, new Item("", "", 1).track(0.05)), H * 0.2);
                double bh = size * 1.6;
                double e = E.outExpo(clamp(env.lt / (env.cut.inDur * 0.8))) * (1 - E.inExpo(env.pOut));
                AffineTransform keep = env.g.getTransform();
                env.g.translate(W / 2, H / 2); env.g.rotate(-P.d("ang") * DEG);
                env.rect(-W * 1.2, -bh / 2 * e, W * 2.4, bh * e, bandCol, 1);
                if (P.b("second")) {
                    double y2 = bh * 0.95, h2 = bh * 0.32;
                    env.rect(-W * 1.2, y2 - h2 / 2, W * 2.4 * e, h2, env.sc.fg(), 0.9);
                    String unit = env.cut.lineText + "　／　";
                    double period = measure(new Item(unit, body(env), h2 * 0.55).track(0.1)).w();
                    env.draw(new Item(unit.repeat((int) Math.ceil(W * 3 / period)), body(env), h2 * 0.55).track(0.1).at(-((lb * 120) % period), y2).color(env.sc.bg()).noGhost().alpha(e));
                }
                env.g.setTransform(keep);
                return env.mainDraw(new Item(text, P.s("font"), size).at(W / 2, H / 2).rot(-P.d("ang")).track(0.05).color(txtCol));
            });

        // ------------------------------------------------ circle
        def("circle", "円窓", n -> n <= 10,
            (rng, cut, st) -> new Params().with("variant", rng.pick("disc", "eclipse", "ring")).with("vertical", glyphCount(cut.text) <= 4 && rng.chance(0.5))
                .with("font", fontPick(rng, st, "display", "serif")).with("off", rng.range(-0.12, 0.12)),
            env -> {
                Params P = env.P(); String text = plain(env.cut.text);
                double cx = W / 2 + P.d("off") * W, cy = H / 2;
                double out = 1 - E.inCubic(env.pOut);
                if (P.s("variant").equals("eclipse")) {
                    double size = Math.min(fitSize(text, P.s("font"), W * 0.82, H * 0.46, new Item("", "", 1).track(0.02)), H * 0.36);
                    BB bb = env.mainDraw(new Item(splitLines(text, 6), P.s("font"), size).at(W / 2, H / 2).lead(1.05).color(env.sc.fg()));
                    double R = H * 0.19, u = env.lt / env.dur();
                    double ex = W / 2 + lerp(-0.08, 0.08, u) * W, ey = H / 2 + H * 0.12;
                    env.circle(ex, ey, R * 1.01 * out, null, env.sc.fg(), 3 * 2.4, 0.9);
                    env.circle(ex, ey, R * out, mix(env.sc.bg(), Color.BLACK, 0.35), null, 0, 1);
                    return bb;
                }
                double R = Math.min(H * 0.3, W * 0.4);
                double e = E.outBack(clamp(env.lt / (env.cut.inDur * 0.9)), 1.5) * out;
                if (P.s("variant").equals("disc")) env.circle(cx, cy, R * e, env.sc.accent(), null, 0, 1);
                else env.arc(cx, cy, R, -90, -90 + 360 * E.outExpo(clamp(env.lt / (env.cut.inDur * 1.3))) * out, env.sc.fg(), 3 * 2.4, 1);
                boolean vert = P.b("vertical");
                double size = vert ? Math.min(fitSize(text, P.s("font"), R * 1.1, R * 1.35, new Item("", "", 1).vertical(true)), R * 0.9) : Math.min(fitSize(text, P.s("font"), R * 1.45, R * 0.9), R * 0.8);
                return env.mainDraw(new Item(text, P.s("font"), size).at(cx, cy).vertical(vert).color(P.s("variant").equals("disc") ? env.sc.bg() : env.sc.fg()));
            });

        // ------------------------------------------------ stack
        def("stack", "残像スタック", n -> n <= 12,
            (rng, cut, st) -> new Params().with("copies", rng.pick(3, 4, 5)).with("dir", rng.pick(1, -1)).with("style", rng.pick("fade", "outline", "fade")).with("font", fontPick(rng, st, "display", "serif"))
                .with("gap", rng.range(0.82, 1.02)).with("xs", rng.range(-0.04, 0.04)),
            env -> {
                Params P = env.P(); String text = env.cut.text;
                double size = Math.min(fitSize(text, P.s("font"), W * 0.8, H * 0.22, new Item("", "", 1).track(0.03)), H * 0.19);
                double step = size * P.d("gap");
                int n = P.i("copies"), dir = P.i("dir");
                double y0 = H / 2 - dir * (n - 1) * step / 2;
                BB bb = null;
                for (int k = n - 1; k >= 0; k--) {
                    Item it = new Item(text, P.s("font"), size).at(W / 2 + P.d("xs") * W * k, y0 + dir * k * step).track(0.03).color(env.sc.fg()).mi(k * 1.2);
                    if (k > 0) {
                        if (P.s("style").equals("outline")) { it.fill = false; it.stroke = Math.max(1.2, size * 0.014); it.alpha = 0.85; }
                        else it.alpha = 0.6 * Math.pow(0.58, k - 1);
                    }
                    BB rb = env.mainDraw(it);
                    if (k == 0) bb = rb;
                }
                return bb;
            });

        // ------------------------------------------------ pill
        def("pill", "カプセル", n -> n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")).with("smalls", rng.chance(0.75)),
            env -> {
                Params P = env.P(); String text = env.cut.text;
                double size = Math.min(fitSize(text, P.s("font"), W * 0.62, H * 0.2, new Item("", "", 1).track(0.04)), H * 0.17);
                LyricKit.Measure m = measure(new Item(text, P.s("font"), size).track(0.04));
                double w = m.w() + size * 1.3, h = size * 1.6;
                double e = E.outExpo(clamp(env.lt / (env.cut.inDur * 0.9))) * (1 - E.inExpo(env.pOut));
                double ww = Math.max(h, w * e);
                Color fillC = env.sc.accent();
                env.rrect(W / 2 - ww / 2, H / 2 - h / 2, ww, h, h / 2, fillC, 1, null, 0);
                Color tc = lum(fillC) > 0.55 ? new Color(0x111111) : Color.WHITE;
                java.awt.Shape keep = env.g.getClip();
                env.g.clipRect((int) (W / 2 - ww / 2), 0, (int) Math.ceil(ww), (int) H);
                Item it = new Item(text, P.s("font"), size).at(W / 2, H / 2).track(0.04).color(tc);
                it.halo = false;
                BB bb = env.mainDraw(it);
                env.g.setClip(keep);
                if (P.b("smalls")) {
                    String[] labs = {"LYRIC", "No." + two(env.cut.line + 1), fmtTime(env.cut.start)};
                    double fs = clamp(H * 0.022, 13, 24);
                    for (int i = 0; i < 3; i++) {
                        double q = E.outBack(clamp((env.lt - 0.15 - i * 0.06) / 0.25), 2) * (1 - env.pOut);
                        if (q <= 0) continue;
                        LyricKit.Measure mm = measure(new Item(labs[i], body(env), fs).track(0.1));
                        double px = W / 2 + (i == 0 ? -w * 0.3 : i == 1 ? w * 0.42 : w * 0.1), py = H / 2 + (i == 1 ? -h * 0.95 : h * 0.95);
                        env.rrect(px - (mm.w() / 2 + fs * 0.8) * q, py - fs * 0.85, (mm.w() + fs * 1.6) * q, fs * 1.7, fs * 0.85, null, 1, env.sc.fg(), 1.3 * 2.4);
                        env.draw(new Item(labs[i], body(env), fs).track(0.1).at(px, py).color(env.sc.fg()).alpha(q).noGhost());
                    }
                }
                return bb;
            });

        LyricKinetic.init();
        LyricCompose.init();
        LyricCompose2.init(); LyricTypo.init(); LyricTypo2.init();
    }
}
