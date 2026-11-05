package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.JizuraKit.BB;
import io.github.raincandyfx.rainium.JizuraKit.E;
import io.github.raincandyfx.rainium.JizuraKit.Env;
import io.github.raincandyfx.rainium.JizuraKit.Item;
import io.github.raincandyfx.rainium.JizuraKit.Params;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.raincandyfx.rainium.JizuraCompose.*;
import static io.github.raincandyfx.rainium.JizuraKit.DEG;
import static io.github.raincandyfx.rainium.JizuraKit.clamp;
import static io.github.raincandyfx.rainium.JizuraLayouts.def;
import static io.github.raincandyfx.rainium.JizuraLayouts.fontPick;

/**
 * More compositional layouts from JIZURA's "layoutsA" pack: split screen, mirror, sideways type, edge text,
 * seal, manuscript paper, quotation marks, dimension lines, search bar, chat and notification
 * (https://github.com/852wa/JIZURA, MIT, (c) 2026 hakoniwa).
 */
final class JizuraCompose2 {
    private JizuraCompose2() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    // ------------------------------------------------------------------ helpers
    static Shape pathOf(double[][] pts) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < pts.length; i++) { if (i == 0) p.moveTo(pts[i][0], pts[i][1]); else p.lineTo(pts[i][0], pts[i][1]); }
        p.closePath();
        return p;
    }

    /** Runs the drawing with everything outside the shape cut away. */
    static BB clipped(Env env, Shape shape, java.util.function.Supplier<BB> draw) {
        Shape old = env.g.getClip();
        env.g.clip(shape);
        try { return draw.get(); } finally { env.g.setClip(old); }
    }

    static void clippedDo(Env env, Shape shape, Runnable draw) { clipped(env, shape, () -> { draw.run(); return null; }); }

    static void dash(Env env, double x0, double y0, double x1, double y1, double d, double g, Color c, double lw, double a) {
        env.dashed(new double[][]{{x0, y0}, {x1, y1}}, c, lw, a, (float) d, (float) g);
    }

    static double[][] closeLoop(double[][] p) {
        double[][] o = new double[p.length + 2][];
        System.arraycopy(p, 0, o, 0, p.length);
        o[p.length] = p[0]; o[p.length + 1] = p[1];
        return o;
    }

    static {
        def("splitScreen", "二分割", n -> n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("split", rng.pick("v", "diag", "v", "h")).with("plate", rng.pick("fg", "accent", "fg"))
                .with("side", rng.pick(1.0, -1.0)).with("tilt", rng.range(12, 22)),
            JizuraCompose2::splitScreen);
        def("mirror", "鏡像", n -> n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("strength", rng.range(0.34, 0.5)).with("squash", rng.pick(1.0, 0.7, 0.85))
                .with("ripple", rng.chance(0.5)).with("ticks", rng.chance(0.6)),
            JizuraCompose2::mirror);
        def("sideways", "縦倒し", n -> n >= 3 && n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "display", "serif")).with("side", rng.pick("left", "right")).with("copy", rng.pick("stack", "number")).with("rule", rng.chance(0.75)),
            JizuraCompose2::sideways);
        def("edgeFrame", "外周", n -> n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("edgeFont", rng.pick("body", "mono")).with("sep", rng.pick("　／　", "　・　", "　—　"))
                .with("speed", rng.range(0.6, 1.2) * rng.pick(1.0, -1.0)).with("corner", rng.pick("square", "cross")).with("inner", rng.chance(0.6)),
            JizuraCompose2::edgeFrame);
        def("hanko", "落款", n -> n >= 1 && n <= 10,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif")).with("sealFont", fontPick(rng, st, "serif", "display")).with("vert", rng.chance(0.6))
                .with("seal", rng.pick("haku", "shu", "haku")).with("rot", rng.range(-7, 7)),
            JizuraCompose2::hanko);
        def("genkou", "原稿用紙", n -> n <= 22,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif")).with("lineC", rng.pick("accent", "sub")).with("indent", rng.chance(0.5)).with("pad", rng.integer(2, 4)),
            JizuraCompose2::genkou);
        def("quote", "引用", n -> n <= 20,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("markFont", fontPick(rng, st, "serif"))
                .with("marks", hasLatin(cut.text) ? "latin" : rng.pick("kagi", "double", "kagi")).with("markC", rng.pick("accent", "sub")).with("attrib", rng.chance(0.75)),
            JizuraCompose2::quote);
        def("ruler", "寸法線", n -> n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif", "body")).with("dims", rng.pick("topRight", "bottomLeft")).with("ticks", rng.chance(0.7))
                .with("guides", rng.chance(0.8)).with("unit", rng.pick("px", "pt", "px")),
            JizuraCompose2::ruler);
        def("searchBar", "検索窓", n -> n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "body", "display")).with("shape", rng.pick("pill", "rect")).with("fill", rng.pick("outline", "filled"))
                .with("sugg", rng.integer(3, 4)).with("pos", rng.pick("center", "upper")),
            JizuraCompose2::searchBar);
        def("chat", "チャット", n -> n <= 22,
            (rng, cut, st) -> {
                int n = gcount(cut.text), nw = Math.max(1, cut.words.size());
                List<String> msgs = n <= 4 ? List.of(cut.text.trim()) : splitK(cut.text, (int) clamp(nw, 2, n > 12 ? 4 : 3));
                return new Params().with("msgs", String.join("\u0001", msgs)).with("font", fontPick(rng, st, "body", "display")).with("side0", rng.pick(1.0, -1.0)).with("alt", rng.chance(0.6));
            },
            JizuraCompose2::chat);
        def("notification", "通知", n -> n <= 24,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "body", "display")).with("pos", rng.pick("banner", "lock", "center")).with("stack", rng.chance(0.5)).with("app", rng.pick("MUSIC", "LYRICS", "MESSAGE")),
            JizuraCompose2::notification);
    }

    // ================================================================== splitScreen
    private static BB splitScreen(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        Color tOff = JizuraKit.contrast(sc.fg(), sc.bg()) > 2 ? sc.fg() : onCol(sc, sc.bg());
        Color plateC = p.s("plate").equals("accent") ? sc.accent() : sc.fg();
        if (onCol(sc, plateC).equals(tOff) || JizuraKit.contrast(plateC, sc.bg()) < 1.6) plateC = sc.fg();
        Color tOn = onCol(sc, plateC);
        double e = tin(env, 0, 0.5) * (1 - E.inExpo(env.pOut)), sd = p.d("side") == 0 ? 1 : p.d("side");
        double[][] plate, other;
        if (p.s("split").equals("h")) {
            double by = H / 2 + sd * (1 - e) * H * 0.55;
            double[][] lower = {{-W, by}, {2 * W, by}, {2 * W, 2 * H}, {-W, 2 * H}}, upper = {{-W, -H}, {2 * W, -H}, {2 * W, by}, {-W, by}};
            plate = sd > 0 ? lower : upper; other = sd > 0 ? upper : lower;
        } else {
            double dx = p.s("split").equals("diag") ? Math.tan(p.d("tilt") * DEG) * H / 2 : 0, sh = sd * (1 - e) * (W * 0.55 + Math.abs(dx));
            java.util.function.DoubleUnaryOperator bx = y -> W / 2 + dx - 2 * dx * (y / H) + sh;
            double[] top = {bx.applyAsDouble(-H), -H}, bot = {bx.applyAsDouble(2 * H), 2 * H};
            double[][] right = {top, {3 * W, -H}, {3 * W, 2 * H}, bot}, left = {{-2 * W, -H}, top, bot, {-2 * W, 2 * H}};
            plate = sd > 0 ? right : left; other = sd > 0 ? left : right;
        }
        if (e > 0) env.poly(plate, plateC, 1);
        String text = brk(env.cut.text.trim(), 10);
        double size = Math.min(fit(text, font, W * 0.86, H * (p.s("split").equals("h") ? 0.36 : 0.42), 0.02, 1.08), U * 0.3);
        var m = meas(text, font, size, 0.02, 1.08);
        double cy = p.s("split").equals("h") ? H / 2 + (m.h() > size * 1.5 ? 0 : size * 0.04) : H / 2;
        double la = tin(env, 0.25, 0.4, E::outCubic) * tout(env), ls = smallSize(env) * 0.9;
        boolean h = p.s("split").equals("h");
        final Color fOff = tOff, fOn = tOn;
        BB[] bb = new BB[1];
        clippedDo(env, pathOf(other), () -> {
            bb[0] = env.mainDraw(it(text, font, size, W / 2, cy).track(0.02).lead(1.08).color(fOff));
            if (la > 0) env.draw(it("No." + lineNo(env), mono(env), ls, sd > 0 ? W * 0.05 : W * 0.95, sd > 0 || !h ? H * 0.07 : H * 0.93).track(0.15).align(sd > 0 ? "left" : "right").color(sc.sub()).alpha(la).noGhost());
        });
        if (e > 0) clippedDo(env, pathOf(plate), () -> {
            bb[0] = BB.union(bb[0], env.mainDraw(it(text, font, size, W / 2, cy).track(0.02).lead(1.08).color(fOn)));
            if (la > 0) env.draw(it(fmtTime(env.cut.start), mono(env), ls, sd > 0 ? W * 0.95 : W * 0.05, sd > 0 || !h ? H * 0.93 : H * 0.07).track(0.15).align(sd > 0 ? "right" : "left").color(fOn).alpha(la).noGhost());
        });
        return bb[0] != null ? bb[0] : box(W / 2 - m.w() / 2, cy - m.h() / 2, W / 2 + m.w() / 2, cy + m.h() / 2);
    }

    // ================================================================== mirror
    private static BB mirror(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String text = brk(env.cut.text.trim(), 12);
        double size = Math.min(fit(text, font, W * 0.84, H * 0.3, 0.04, 1.15), U * 0.22);
        var m = meas(text, font, size, 0.04, 1.15);
        double squash = p.d("squash"), RH = m.h() * squash + size * 0.1, cy = H / 2 - RH * 0.42, hz = cy + m.h() / 2 + size * 0.08, out = tout(env);
        double e = tin(env, 0, 0.7, E::inOutCubic) * out;
        if (e > 0) {
            double hw = W * 0.44 * e;
            env.line(new double[][]{{W / 2 - hw, hz}, {W / 2 + hw, hz}}, sc.sub(), Math.max(1.2, U * 0.0016), 0.85);
            if (p.b("ticks")) for (int k = -8; k <= 8; k++) {
                double x = W / 2 + k * W * 0.05;
                if (Math.abs(x - W / 2) > hw) continue;
                env.line(new double[][]{{x, hz - U * 0.006}, {x, hz + U * 0.006 * (k % 4 == 0 ? 2 : 1)}}, sc.sub(), 1.2, 0.6);
            }
            env.circle(W / 2 - hw, hz, U * 0.005, sc.accent(), null, 0, 1);
            env.circle(W / 2 + hw, hz, U * 0.005, sc.accent(), null, 0, 1);
        }
        int K = 9;
        for (int k = 0; k < K; k++) {
            double y0 = hz + RH * k / K, y1 = hz + RH * (k + 1) / K, a = p.d("strength") * Math.pow(1 - (k + 0.5) / K, 1.5);
            double dx = p.b("ripple") ? Math.sin(env.ltb * 2.4 + k * 0.9) * size * 0.035 * (k + 1) / K : 0;
            Shape keepClip = env.g.getClip();
            AffineTransform keep = env.g.getTransform();
            env.g.clip(new Rectangle2D.Double(-W, y0, W * 3, y1 - y0 + 0.6));
            env.g.translate(dx, hz); env.g.scale(1, -squash); env.g.translate(0, -hz);
            env.mainDraw(it(text, font, size, W / 2, cy).track(0.04).lead(1.15).color(sc.fg()).alpha(a).noGhost().mi(0));
            env.g.setTransform(keep); env.g.setClip(keepClip);
        }
        BB bb = env.mainDraw(it(text, font, size, W / 2, cy).track(0.04).lead(1.15).color(sc.fg()).mi(0));
        return bb != null ? bb : box(W / 2 - m.w() / 2, cy - m.h() / 2, W / 2 + m.w() / 2, cy + m.h() / 2);
    }

    // ================================================================== sideways
    private static BB sideways(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String t0 = env.cut.text.trim();
        int n = gcount(t0);
        String text = n > 6 ? brk(t0, (int) Math.ceil(n / 2.0)) : t0;
        double L = H * 0.88, T = W * 0.3;
        double size = Math.min(fit(text, font, L, T, 0.02, 1.02), U * 0.3);
        var m = meas(text, font, size, 0.02, 1.02);
        boolean left = p.s("side").equals("left");
        double mx = W * 0.05, cx = left ? mx + m.h() / 2 : W - mx - m.h() / 2, rot = left ? -90 : 90, out = tout(env);
        double rx = left ? cx + m.h() / 2 + W * 0.035 : cx - m.h() / 2 - W * 0.035;
        if (p.b("rule")) {
            double e = tin(env, 0.05, 0.6, E::inOutCubic) * out;
            env.line(new double[][]{{rx, H * 0.06}, {rx, H * 0.06 + H * 0.88 * e}}, sc.accent(), Math.max(2, U * 0.003), 1);
        }
        double ax0 = left ? rx + W * 0.04 : W * 0.07, ax1 = left ? W * 0.93 : rx - W * 0.04, aw = ax1 - ax0, ca = tin(env, 0.2, 0.45, E::outCubic) * out;
        if (ca > 0.01 && aw > U * 0.2) {
            double ls = smallSize(env);
            String copy = env.cut.lineText == null || env.cut.lineText.isBlank() ? env.cut.text : env.cut.lineText;
            double cs = Math.min(U * 0.05, fit(brk(copy, Math.max(4, (int) Math.floor(aw / (U * 0.05)))), body(env), aw, H * 0.3, 0, 1.5));
            String ct = brk(copy, Math.max(4, (int) Math.floor(aw / (cs * 1.02))));
            var cm = meas(ct, body(env), cs, 0, 1.5);
            double sl = (1 - ca) * U * 0.03;
            if (p.s("copy").equals("number")) {
                double ns = Math.min(aw * 0.5, H * 0.3);
                env.draw(it(lineNo(env), font, ns, ax0 - ns * 0.04, H * 0.1 + ns * 0.45 + sl).align("left").outline(Math.max(1.5, ns * 0.012), sc.sub()).alpha(ca).noGhost());
            } else {
                env.draw(it("No." + lineNo(env), mono(env), ls, ax0, H * 0.1 + sl).track(0.15).align("left").color(sc.accent()).alpha(ca).noGhost());
                env.draw(it(fmtTime(env.cut.start), mono(env), ls, ax0, H * 0.1 + ls * 1.6 + sl).track(0.15).align("left").color(sc.sub()).alpha(ca).noGhost());
            }
            double by = H * 0.9 - cm.h() / 2;
            env.draw(it(ct, body(env), cs, ax0, by - sl).lead(1.5).align("left").color(sc.fg()).alpha(ca * 0.9).noGhost());
        }
        AffineTransform keep = env.g.getTransform();
        env.g.translate(cx, H / 2); env.g.rotate(rot * DEG);
        BB bb = env.mainDraw(it(text, font, size, 0, 0).track(0.02).lead(1.02).color(sc.fg()));
        env.g.setTransform(keep);
        return bb != null ? box(cx - m.h() / 2, H / 2 - m.w() / 2, cx + m.h() / 2, H / 2 + m.w() / 2) : null;
    }

    // ================================================================== edgeFrame
    private static BB edgeFrame(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        double d = U * 0.045, es = U * 0.024, band = es * 1.7, out = tout(env);
        String ef = p.s("edgeFont").equals("mono") ? mono(env) : body(env);
        String unit = flat(env.cut.lineText == null || env.cut.lineText.isBlank() ? env.cut.text : env.cut.lineText) + p.s("sep");
        double per = Math.max(1, meas(unit, ef, es, 0.08, 1.3).w());
        double[][] edges = {{d + band, d, W - 2 * d - 2 * band, 0}, {W - d, d + band, H - 2 * d - 2 * band, 90}, {W - d - band, H - d, W - 2 * d - 2 * band, 180}, {d, H - d - band, H - 2 * d - 2 * band, 270}};
        for (int k = 0; k < 4; k++) {
            double[] ed = edges[k];
            double e = tin(env, k * 0.07, 0.5, E::inOutCubic) * out;
            if (e <= 0.001) continue;
            int reps = (int) Math.min(60, Math.ceil(ed[2] / per) + 2);
            double off = ((env.ltb * p.d("speed") * U * 0.07) % per + per) % per;
            AffineTransform keep = env.g.getTransform();
            env.g.translate(ed[0], ed[1]); env.g.rotate(ed[3] * DEG);
            Item row = it(unit.repeat(reps), ef, es, -per + off, 0).track(0.08).align("left").color(sc.sub()).alpha(0.85).noGhost().noHalo();
            row.clip = new double[]{0, ed[2] * e};
            env.draw(row);
            env.g.setTransform(keep);
        }
        double cq = E.outBack(clamp((env.lt - 0.1) / 0.3), 2) * out;
        if (cq > 0) for (double[] c : new double[][]{{d, d}, {W - d, d}, {W - d, H - d}, {d, H - d}}) {
            if (p.s("corner").equals("square")) { double q = es * 0.55 * cq; env.rect(c[0] - q / 2, c[1] - q / 2, q, q, sc.accent(), 1); }
            else {
                double q = es * 0.6 * cq, lw = Math.max(1.5, es * 0.08);
                env.line(new double[][]{{c[0] - q, c[1]}, {c[0] + q, c[1]}}, sc.accent(), lw, 1);
                env.line(new double[][]{{c[0], c[1] - q}, {c[0], c[1] + q}}, sc.accent(), lw, 1);
            }
        }
        if (p.b("inner")) {
            double e = tin(env, 0.15, 0.8, E::inOutCubic) * out, g = d + band * 0.95;
            env.polyPartial(new double[][]{{g, g}, {W - g, g}, {W - g, H - g}, {g, H - g}, {g, g}}, e, sc.sub(), 1.2, 0.45);
        }
        String text = brk(env.cut.text.trim(), 11);
        double size = Math.min(fit(text, font, W * 0.72, H * 0.5, 0.04, 1.15), U * 0.22);
        BB bb = env.mainDraw(it(text, font, size, W / 2, H / 2).track(0.04).lead(1.15).color(sc.fg()));
        return bb != null ? bb : box(W * 0.2, H * 0.3, W * 0.8, H * 0.7);
    }

    // ================================================================== hanko
    private static List<String> sealGlyphs(String text) {
        List<String> g = new ArrayList<>(), kan = new ArrayList<>();
        for (String c : chars(strip(text))) if (!isPunct(c) && !isSmallKana(c)) { g.add(c); if (JizuraKit.isKanji(c)) kan.add(c); }
        if (kan.size() >= 2 || (kan.size() == 1 && g.size() > 4)) return kan.subList(0, Math.min(4, kan.size()));
        if (g.size() <= 4) return g.isEmpty() ? chars(strip(text)).subList(0, Math.min(1, chars(strip(text)).size())) : g;
        return g.subList(0, 2);
    }

    private static BB hanko(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String text = strip(env.cut.text);
        int n = gcount(text);
        double out = tout(env), size, sx, sy, S;
        Item item;
        BB bbox;
        if (p.b("vert")) {
            int cols = n > 6 ? 2 : 1;
            String vt = cols > 1 ? brk(text, (int) Math.ceil(n / 2.0)) : text;
            int per = 1;
            for (String l : vt.split("\n")) per = Math.max(per, gcount(l));
            size = Math.min(Math.min(H * 0.7 / Math.max(per, 2.5), U * 0.2), W * 0.3 / cols);
            S = size * (n <= 2 ? 1.35 : 1.5);
            double lead = 1.35, bw = size * (1 + (cols - 1) * lead), colH = per * size;
            double x = W / 2 + S * 0.45 + (cols - 1) * size * lead / 2, top = H / 2 - colH / 2 - S * 0.25;
            item = it(vt, font, size, x, top).vertical(true).align("left").lead(lead).color(sc.fg());
            bbox = box(x - bw / 2, top, x + bw / 2, top + colH);
            String[] ls = vt.split("\n");
            int lastLen = gcount(ls[ls.length - 1]);
            sx = x - bw / 2 - S * 0.72; sy = Math.max(top + lastLen * size, top + S * 0.5) + S * 0.1;
        } else {
            size = Math.min(fit(text, font, W * 0.6, H * 0.3, 0, 1.3), U * 0.17);
            S = size * 1.7;
            var m = meas(text, font, size, 0, 1.3);
            double x = W / 2 - S * 0.5, y = H / 2;
            item = it(text, font, size, x, y).color(sc.fg());
            bbox = box(x - m.w() / 2, y - m.h() / 2, x + m.w() / 2, y + m.h() / 2);
            sx = x + m.w() / 2 + S * 0.75; sy = y + size * 0.3;
        }
        BB bb = env.mainDraw(item);
        double t0 = env.cut.inDur * 0.7 + 0.08, x = (env.lt - t0) / 0.2;
        if (x > 0 && out > 0) {
            double sc2 = JizuraKit.lerp(1.55, 1, E.outBack(clamp(x), 1.4)), a = clamp(x * 3) * out;
            double shake = x > 1 && x < 1.8 ? JizuraKit.rs(env.step, 91, 0) * S * 0.015 * (1.8 - x) : 0;
            AffineTransform keep = env.g.getTransform();
            env.g.translate(sx + shake, sy); env.g.rotate(p.d("rot") * DEG); env.g.scale(sc2, sc2);
            int M = 11;
            long seed = env.cut.seed;
            double[][] pts = new double[M * 4][];
            for (int i = 0; i < M; i++) pts[i] = new double[]{-S / 2 + S * i / M, -S / 2 + JizuraKit.rs(seed, i, 1) * S * 0.014};
            for (int i = 0; i < M; i++) pts[M + i] = new double[]{S / 2 + JizuraKit.rs(seed, i, 2) * S * 0.014, -S / 2 + S * i / M};
            for (int i = 0; i < M; i++) pts[2 * M + i] = new double[]{S / 2 - S * i / M, S / 2 + JizuraKit.rs(seed, i, 3) * S * 0.014};
            for (int i = 0; i < M; i++) pts[3 * M + i] = new double[]{-S / 2 + JizuraKit.rs(seed, i, 4) * S * 0.014, S / 2 - S * i / M};
            List<String> G = sealGlyphs(text);
            int gn = G.size(), cols = gn >= 3 ? 2 : 1, rows = (int) Math.ceil(gn / (double) cols);
            boolean haku = p.s("seal").equals("haku");
            double inner = S * (haku ? 0.8 : 0.72), cw = inner / cols, chh = inner / rows, gs = Math.min(cw, chh) * 0.92, gsx = clamp(cw / chh, 0.8, 1.7);
            Color gcol = haku ? sc.bg() : sc.accent();
            if (haku) env.poly(pts, sc.accent(), a);
            else env.line(closeLoop(pts), sc.accent(), S * 0.07, a);
            for (int i = 0; i < gn; i++) {
                int col = cols - 1 - i / rows, row = i % rows;
                double gx = -inner / 2 + cw * (col + 0.5), gy = -inner / 2 + chh * (row + 0.5);
                env.draw(it(G.get(i), p.s("sealFont"), gs, gx, gy).sx(gs * gsx > cw ? cw / gs : gsx).color(gcol).alpha(a).noGhost());
            }
            if (haku) for (int i = 0; i < 12; i++)
                env.circle(JizuraKit.rs(seed, i, 5) * S * 0.46, JizuraKit.rs(seed, i, 6) * S * 0.46, S * JizuraKit.rr(0.004, 0.012, seed, i, 7), sc.bg(), null, 0, a * 0.9);
            env.g.setTransform(keep);
        }
        return bb != null ? bbox : null;
    }

    // ================================================================== genkou
    private static BB genkou(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        List<String> cs = chars(strip(env.cut.text));
        int n = cs.size(), ind = p.b("indent") ? 1 : 0, maxR = 10;
        int used = Math.max(1, (int) Math.ceil((n + ind) / (double) maxR));
        int R = Math.max(7, (int) Math.ceil((n + ind) / (double) used) + (used == 1 && n + ind < maxR ? 1 : 0));
        double gapK = 0.3, cell = Math.min(H * 0.78 / R, W * 0.9 / ((used + 2) * (1 + gapK))), colW = cell * (1 + gapK);
        int C = Math.max(used + 2, Math.min((int) Math.floor(W * 0.92 / colW), used + p.i("pad") * 2));
        double gw = C * colW - cell * gapK, gh = R * cell, gx0 = W / 2 - gw / 2, gy0 = H / 2 - gh / 2;
        int cStart = (int) Math.floor((C - used) / 2.0);
        java.util.function.IntToDoubleFunction colX = c -> gx0 + gw - cell - c * colW;
        Color lc = p.s("lineC").equals("accent") ? sc.accent() : sc.sub();
        double out = tout(env), lw = Math.max(1.5, cell * 0.02);
        for (int c = 0; c < C; c++) {
            double e = tin(env, c * 0.035, 0.45, E::inOutCubic) * out;
            if (e <= 0) continue;
            double x = colX.applyAsDouble(c), dist = Math.abs(c - (cStart + (used - 1) / 2.0)) / Math.max(1, C / 2.0), a = 0.85 * (1 - dist * 0.6);
            env.line(new double[][]{{x, gy0}, {x, gy0 + gh * e}}, lc, lw, a);
            env.line(new double[][]{{x + cell, gy0}, {x + cell, gy0 + gh * e}}, lc, lw, a);
            for (int r = 0; r <= R; r++) {
                double y = gy0 + r * cell;
                if (y > gy0 + gh * e + 0.5) break;
                env.line(new double[][]{{x, y}, {x + cell, y}}, lc, lw, a * (r == 0 || r == R ? 1 : 0.8));
            }
        }
        double fa = tin(env, 0.3, 0.4, E::outCubic) * out;
        if (fa > 0) {
            double fs = Math.max(11, cell * 0.14);
            env.draw(it("(" + R + "×" + C + ")", mono(env), fs, gx0, gy0 + gh + fs * 1.4).align("left").color(lc).alpha(fa * 0.8).noGhost());
            env.draw(it("No." + lineNo(env), mono(env), fs, gx0 + gw, gy0 + gh + fs * 1.4).align("right").color(lc).alpha(fa * 0.8).noGhost());
        }
        BB bb = null;
        for (int i = 0; i < n; i++) {
            String ch = cs.get(i);
            int k = i + ind, c = cStart + k / R, r = k % R;
            double x = colX.applyAsDouble(c) + cell / 2, y = gy0 + (r + 0.5) * cell, rot = 0;
            if (isSmallKana(ch)) { x += cell * 0.1; y -= cell * 0.1; }
            if ("、。，．".contains(ch)) { x += cell * 0.28; y -= cell * 0.28; }
            if ("ー〜～…―—-(（)）[「」『』【】〔〕]".contains(ch) || ch.matches("[A-Za-z0-9]")) rot = 90;
            bb = BB.union(bb, env.mainDraw(it(ch, font, cell * 0.8, x, y).rot(rot).color(sc.fg()).mi(i)));
        }
        return bb != null ? bb : box(colX.applyAsDouble(cStart + used - 1), gy0, colX.applyAsDouble(cStart) + cell, gy0 + gh);
    }

    // ================================================================== quote
    private static BB quote(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"), mf = p.s("markFont");
        String text = brk(env.cut.text.trim(), 11);
        double size = Math.min(fit(text, font, W * 0.62, H * 0.4, 0.04, 1.25), U * 0.16);
        var m = meas(text, font, size, 0.04, 1.25);
        double cy = H / 2 - (p.b("attrib") ? size * 0.25 : 0), bx0 = W / 2 - m.w() / 2, bx1 = W / 2 + m.w() / 2, by0 = cy - m.h() / 2, by1 = cy + m.h() / 2, out = tout(env);
        double e = E.outExpo(clamp((env.lt - 0.02) / 0.5)) * (1 - E.inCubic(env.pOut));
        Color mc = p.s("markC").equals("accent") ? sc.accent() : sc.sub();
        boolean latin = p.s("marks").equals("latin");
        String o1 = latin ? "“" : p.s("marks").equals("double") ? "『" : "「", c1 = latin ? "”" : p.s("marks").equals("double") ? "』" : "」";
        // glyph ink boxes in ems (relative to the centre): the quotation marks sit high, the brackets span most of the height
        double[] ia = latin ? new double[]{-0.22, 0.22, -0.40, -0.08} : new double[]{-0.40, 0.40, -0.42, 0.32};
        double[] ib = latin ? new double[]{-0.22, 0.22, -0.40, -0.08} : new double[]{-0.40, 0.40, -0.32, 0.42};
        double gap = size * 0.2, inkW = Math.max(0.05, Math.max(ia[1] - ia[0], ib[1] - ib[0]));
        double ms = Math.min(Math.min(Math.max(size * (latin ? 3.2 : 4), m.h() * 1.8), U * 0.5), (W * 0.94 - m.w() - gap * 2) / (2 * inkW));
        double far = U * 0.35 * (1 - e);
        if (e > 0.001) {
            double al = clamp(e * 1.5);
            double ax = bx0 - gap - ia[1] * ms - far, ay = by0 - size * 0.1 - ia[2] * ms - far * 0.6;
            double bxx = bx1 + gap - ib[0] * ms + far, byy = (latin ? by1 - size * 0.95 - ib[2] * ms : by1 + size * 0.1 - ib[3] * ms) + far * 0.6;
            env.draw(it(o1, mf, ms, ax, ay).color(mc).alpha(al).noGhost());
            env.draw(it(c1, mf, ms, bxx, byy).color(mc).alpha(al).noGhost());
        }
        if (p.b("attrib")) {
            double a = tin(env, 0.35, 0.4, E::outCubic) * out, ls = smallSize(env);
            String at = "No." + lineNo(env);
            double aw = Math.min(W * 0.4, meas("— " + at, body(env), ls, 0.12, 1.3).w());
            env.line(new double[][]{{W / 2 - aw / 2 - ls * 2, by1 + size * 0.95}, {W / 2 - aw / 2 - ls * 0.8, by1 + size * 0.95}}, mc, 1.5, a);
            env.draw(it(at, body(env), ls, W / 2, by1 + size * 0.95).track(0.12).color(sc.sub()).alpha(a).noGhost());
        }
        BB bb = env.mainDraw(it(text, font, size, W / 2, cy).track(0.04).lead(1.25).color(sc.fg()));
        return bb != null ? bb : box(bx0, by0, bx1, by1);
    }

    // ================================================================== ruler
    private static void arrowHead(Env env, double x, double y, double ang, double s, Color col, double a) {
        double c = Math.cos(ang), sn = Math.sin(ang);
        env.poly(new double[][]{{x, y}, {x - c * s + sn * s * 0.35, y - sn * s - c * s * 0.35}, {x - c * s - sn * s * 0.35, y - sn * s + c * s * 0.35}}, col, a);
    }

    private static BB ruler(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String text = brk(env.cut.text.trim(), 12);
        double size = Math.min(fit(text, font, W * 0.66, H * 0.34, 0.04, 1.15), U * 0.19);
        var m = meas(text, font, size, 0.04, 1.15);
        double cx = W / 2, cy = H / 2, x0 = cx - m.w() / 2, x1 = cx + m.w() / 2, y0 = cy - m.h() / 2, y1 = cy + m.h() / 2, out = tout(env);
        double e = tin(env, 0.08, 0.75, E::inOutCubic) * out, lw = Math.max(1.2, U * 0.0016), ls = smallSize(env) * 0.9, ah = ls * 0.7;
        boolean top = p.s("dims").equals("topRight");
        if (e > 0) {
            if (p.b("guides")) {
                double gx = W * 0.47 * e, gy = H * 0.47 * e;
                dash(env, x0, cy - gy, x0, cy + gy, 8, 8, sc.sub(), 1, 0.35);
                dash(env, x1, cy - gy, x1, cy + gy, 8, 8, sc.sub(), 1, 0.35);
                dash(env, cx - gx, y0, cx + gx, y0, 8, 8, sc.sub(), 1, 0.35);
                dash(env, cx - gx, y1, cx + gx, y1, 8, 8, sc.sub(), 1, 0.35);
            }
            double dy = top ? y0 - size * 0.55 : y1 + size * 0.55, ext = top ? -1 : 1, hx = m.w() / 2 * e;
            env.line(new double[][]{{x0, top ? y0 - size * 0.08 : y1 + size * 0.08}, {x0, dy + ext * ls * 0.6}}, sc.sub(), lw, 0.8);
            env.line(new double[][]{{x1, top ? y0 - size * 0.08 : y1 + size * 0.08}, {x1, dy + ext * ls * 0.6}}, sc.sub(), lw, 0.8);
            env.line(new double[][]{{cx - hx, dy}, {cx + hx, dy}}, sc.sub(), lw, 1);
            arrowHead(env, cx - hx, dy, Math.PI, ah, sc.sub(), 1); arrowHead(env, cx + hx, dy, 0, ah, sc.sub(), 1);
            env.draw(it(Math.round(m.w() * e) + " " + p.s("unit"), mono(env), ls, cx, dy + ext * ls * 0.95).track(0.08).color(sc.accent()).alpha(clamp(e * 2 - 0.3)).noGhost());
            double dx = top ? x1 + size * 0.5 : x0 - size * 0.5, ex = top ? 1 : -1, vy = m.h() / 2 * e;
            env.line(new double[][]{{top ? x1 + size * 0.08 : x0 - size * 0.08, y0}, {dx + ex * ls * 0.6, y0}}, sc.sub(), lw, 0.8);
            env.line(new double[][]{{top ? x1 + size * 0.08 : x0 - size * 0.08, y1}, {dx + ex * ls * 0.6, y1}}, sc.sub(), lw, 0.8);
            env.line(new double[][]{{dx, cy - vy}, {dx, cy + vy}}, sc.sub(), lw, 1);
            arrowHead(env, dx, cy - vy, -Math.PI / 2, ah, sc.sub(), 1); arrowHead(env, dx, cy + vy, Math.PI / 2, ah, sc.sub(), 1);
            env.draw(it(Math.round(m.h() * e) + " " + p.s("unit"), mono(env), ls, dx + ex * ls * 1.1, cy).track(0.08).rot(top ? 90 : -90).color(sc.accent()).alpha(clamp(e * 2 - 0.3)).noGhost());
            if (p.b("ticks")) {
                double ty = top ? y1 + size * 0.28 : y0 - size * 0.28, sg = top ? 1 : -1, a = clamp(e * 1.6 - 0.4);
                var lay = JizuraKit.layoutText(new Item(text, font, size).track(0.04).lead(1.15));
                int li = top ? lay.get(lay.size() - 1).li : 0;
                double lx0 = 1e9, lx1 = -1e9;
                List<Double> xs = new ArrayList<>();
                for (var gl : lay) if (gl.li == li && !gl.ch.isBlank()) { lx0 = Math.min(lx0, cx + gl.x - gl.w / 2); lx1 = Math.max(lx1, cx + gl.x + gl.w / 2); xs.add(cx + gl.x - gl.w / 2); }
                if (!xs.isEmpty()) {
                    env.line(new double[][]{{lx0, ty}, {lx0 + (lx1 - lx0) * e, ty}}, sc.sub(), lw, 0.7 * a);
                    for (double gx : xs) if (gx <= lx0 + (lx1 - lx0) * e + 0.5) env.line(new double[][]{{gx, ty}, {gx, ty + sg * ls * 0.5}}, sc.sub(), lw, a);
                    env.line(new double[][]{{lx1, ty}, {lx1, ty + sg * ls * 0.5}}, sc.sub(), lw, a);
                    env.draw(it("n=" + gcount(env.cut.text), mono(env), ls, top ? lx0 : lx1, ty + sg * ls * 1.4).track(0.08).align(top ? "left" : "right").color(sc.sub()).alpha(a).noGhost());
                }
            }
        }
        BB bb = env.mainDraw(it(text, font, size, cx, cy).track(0.04).lead(1.15).color(sc.fg()));
        return bb != null ? bb : box(x0, y0, x1, y1);
    }

    // ================================================================== searchBar
    private static void magnifier(Env env, double x, double y, double r, Color col, double lw, double a) {
        env.circle(x - r * 0.15, y - r * 0.15, r * 0.62, null, col, lw, a);
        env.line(new double[][]{{x + r * 0.3, y + r * 0.3}, {x + r * 0.8, y + r * 0.8}}, col, lw * 1.2, a);
    }

    private static BB searchBar(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String text = env.cut.text.trim();
        double bw = W * 0.7, ts = Math.min(fit(text, font, bw - U * 0.22, U * 0.11, 0.02, 1.3), U * 0.095), bh = ts * 2.1;
        double r = p.s("shape").equals("pill") ? bh / 2 : bh * 0.16, by = H * (p.s("pos").equals("upper") ? 0.28 : 0.38), out = tout(env);
        double e = tin(env, 0, 0.45) * out;
        boolean filled = p.s("fill").equals("filled");
        Color plate = sc.ink(), tc = filled ? onCol(sc, plate) : sc.fg();
        double w = bw * (0.3 + 0.7 * e), bx0 = W / 2 - w / 2;
        if (e > 0) {
            if (filled) env.rrect(bx0, by - bh / 2, w, bh, r, plate, e, null, 0);
            else env.rrect(bx0, by - bh / 2, w, bh, r, null, e, sc.fg(), Math.max(2, ts * 0.05));
            magnifier(env, bx0 + bh * 0.5, by, ts * 0.55, tc, Math.max(2, ts * 0.06), e);
            double xr = bx0 + w - bh * 0.45, q = ts * 0.2;
            env.line(new double[][]{{xr - q, by - q}, {xr + q, by + q}}, tc, Math.max(1.5, ts * 0.04), 0.6 * e);
            env.line(new double[][]{{xr - q, by + q}, {xr + q, by - q}}, tc, Math.max(1.5, ts * 0.04), 0.6 * e);
        }
        double tx = W / 2 - bw / 2 + bh * 0.95;
        BB bb = env.mainDraw(it(text, font, ts, tx, by).align("left").track(0.02).color(tc));
        double typed = env.cut.inDur;
        if (env.lt > typed && out > 0.5 && env.step % 2 == 0) {
            double tw = meas(text, font, ts, 0.02, 1.3).w();
            env.rect(tx + tw + ts * 0.12, by - ts * 0.55, Math.max(2, ts * 0.06), ts * 1.1, sc.accent(), 1);
        }
        String line = env.cut.lineText != null && !strip(env.cut.lineText).equals(strip(text)) ? env.cut.lineText : text + " meaning";
        List<String> rows = List.of(text + " lyrics", line, text + " mv", text + " cover").subList(0, p.i("sugg"));
        double rs = ts * 0.52, rh = rs * 2.4, py = by + bh / 2 + rs * 0.8, t0 = Math.max(0.3, typed), pa = tin(env, t0, 0.3, E::outCubic) * out;
        if (pa > 0) {
            double ph = rows.size() * rh + rs * 0.6;
            env.rrect(W / 2 - bw / 2, py, bw, ph * pa, bh * 0.16, filled ? plate : null, 0.9 * pa, filled ? null : sc.sub(), 1.2);
            for (int i = 0; i < rows.size(); i++) {
                double a = clamp((env.lt - t0 - 0.05 - i * 0.07) / 0.2) * out;
                if (a <= 0 || (i + 1) * rh > ph * pa) continue;
                double y = py + rs * 0.3 + (i + 0.5) * rh;
                String rt = rows.get(i);
                magnifier(env, W / 2 - bw / 2 + bh * 0.5, y, rs * 0.7, filled ? onCol(sc, plate) : sc.sub(), 1.5, a * 0.7);
                double xt = W / 2 - bw / 2 + bh * 0.95;
                String pre = rt.startsWith(text) ? text : "", rest = rt.substring(pre.length());
                double pw = pre.isEmpty() ? 0 : meas(pre, font, rs, 0.02, 1.3).w();
                if (!pre.isEmpty()) env.draw(it(pre, font, rs, xt, y).track(0.02).align("left").color(filled ? onCol(sc, plate) : sc.fg()).alpha(a).noGhost());
                env.draw(it(rest, body(env), rs, xt + pw, y).track(0.02).align("left").color(filled ? onCol(sc, plate) : sc.sub()).alpha(a * (filled ? 0.65 : 1)).noGhost());
            }
        }
        return bb != null ? bb : box(W / 2 - bw / 2, by - bh / 2, W / 2 + bw / 2, by + bh / 2);
    }

    // ================================================================== chat
    private static BB chat(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String[] msgs = p.s("msgs").isEmpty() ? new String[]{env.cut.text.trim()} : p.s("msgs").split("\u0001");
        double colW = W * (W / H < 1.5 ? 0.66 : 0.52), maxW = colW * 0.8;
        Color sentC = sc.accent(), recvC = !sc.ink().equals(sc.accent()) ? sc.ink() : sc.sub();
        double side0 = p.d("side0") == 0 ? 1 : p.d("side0");
        List<Object[]> list = new ArrayList<>();   // {text or null for typing, side}
        if (msgs.length == 1) list.add(new Object[]{null, -side0});
        for (int i = 0; i < msgs.length; i++) list.add(new Object[]{msgs[i], p.b("alt") ? (i % 2 == 1 ? -side0 : side0) : side0});
        int nL = list.size();
        double fs = Math.min(U * 0.088, H * 0.62 / (nL * 2.2));
        int longest = 1;
        for (Object[] q : list) longest = Math.max(longest, q[0] == null ? 1 : gcount((String) q[0]));
        fs = Math.max(Math.min(fs, (maxW - fs * 1.4) / (longest * 1.02)), fs * 0.62);
        String[] lines = new String[nL];
        for (int i = 0; i < nL; i++) lines[i] = list.get(i)[0] == null ? "" : brk((String) list.get(i)[0], Math.max(3, (int) Math.floor(maxW / (fs * 1.02))));
        for (String l : lines) if (!l.isEmpty()) fs = Math.min(fs, fit(l, font, maxW - fs * 1.4, H * 0.3, 0, 1.25));
        double pad = fs * 0.62, sp = fs * 0.45;
        double[][] dims = new double[nL][];
        for (int i = 0; i < nL; i++) {
            if (list.get(i)[0] == null) dims[i] = new double[]{fs * 3, fs * 1.9};
            else { var m = meas(lines[i], font, fs, 0, 1.25); dims[i] = new double[]{m.w() + pad * 2, m.h() + pad * 1.45}; }
        }
        double gap = Math.min(0.3, Math.max(0.12, env.dur() * 0.45 / nL));
        boolean typing = list.get(0)[0] == null;
        java.util.function.IntToDoubleFunction tAt = i -> typing ? (i == 0 ? 0 : 0.35 + (i - 1) * gap) : i * gap;
        double stag = Math.max(0.01, env.cut.stagger), bottom = H * 0.8, out = tout(env);
        BB bb = null;
        double[] lift = new double[nL];
        for (int i = 0; i < nL; i++) { double y = 0; for (int j = i + 1; j < nL; j++) y += (dims[j][1] + sp) * E.outExpo(clamp((env.lt - tAt.applyAsDouble(j)) / 0.28)); lift[i] = y; }
        for (int i = 0; i < nL; i++) {
            double lt = env.lt - tAt.applyAsDouble(i);
            if (lt < 0) continue;
            double pop = E.outBack(clamp(lt / 0.25), 1.6) * out, bw = dims[i][0], bh = dims[i][1];
            boolean right = (double) list.get(i)[1] > 0;
            double x0 = right ? W / 2 + colW / 2 - bw : W / 2 - colW / 2, y1 = bottom - lift[i], y0 = y1 - bh;
            Color col = right ? sentC : recvC, tc = onCol(sc, col);
            double px = right ? x0 + bw : x0, py = y1;
            AffineTransform keep = env.g.getTransform();
            double ps = Math.max(0.001, pop);
            env.g.translate(px, py); env.g.scale(ps, ps); env.g.translate(-px, -py);
            env.rrect(x0, y0, bw, bh, Math.min(bh / 2, fs * 0.9), col, 1, null, 0);
            env.poly(right ? new double[][]{{x0 + bw - fs * 0.5, y1 - fs * 0.3}, {x0 + bw + fs * 0.28, y1 + fs * 0.05}, {x0 + bw - fs * 0.1, y1 - fs * 0.8}}
                : new double[][]{{x0 + fs * 0.5, y1 - fs * 0.3}, {x0 - fs * 0.28, y1 + fs * 0.05}, {x0 + fs * 0.1, y1 - fs * 0.8}}, col, 1);
            if (list.get(i)[0] == null) {
                for (int k = 0; k < 3; k++) { double ph = Math.sin(env.ltb * 9 - k * 0.9) * 0.5 + 0.5; env.circle(x0 + bw / 2 + (k - 1) * fs * 0.62, y0 + bh / 2 - ph * fs * 0.12, fs * 0.16, tc, null, 0, 0.45 + ph * 0.5); }
            } else bb = BB.union(bb, env.mainDraw(it(lines[i], font, fs, x0 + bw / 2, y0 + bh / 2).lead(1.25).color(tc).mi(tAt.applyAsDouble(i) / stag)));
            env.g.setTransform(keep);
            if (i == nL - 1 && pop > 0.9) {
                double ms = Math.max(11, fs * 0.36);
                env.draw(it((right ? "Read " : "") + fmtTime(env.cut.start).substring(0, 5), mono(env), ms, right ? x0 + bw : x0, y1 + ms * 1.4).align(right ? "right" : "left").color(sc.sub()).alpha(out).noGhost());
            }
        }
        return bb != null ? bb : box(W / 2 - colW / 2, bottom - H * 0.3, W / 2 + colW / 2, bottom);
    }

    // ================================================================== notification
    private static void noteIcon(Env env, double x, double y, double s, Color col, double a) {
        env.circle(x - s * 0.18, y + s * 0.2, s * 0.17, col, null, 0, a);
        env.rect(x - s * 0.03, y - s * 0.32, s * 0.07, s * 0.52, col, a);
        env.poly(new double[][]{{x + s * 0.04, y - s * 0.32}, {x + s * 0.3, y - s * 0.18}, {x + s * 0.04, y - s * 0.12}}, col, a);
    }

    private static BB notification(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font");
        String text = env.cut.text.trim();
        double cw = W * (W / H < 1.5 ? 0.66 : 0.54), pd = cw * 0.05, hs = Math.max(12, cw * 0.032);
        Color plate = sc.ink(), tc = onCol(sc, plate);
        String mt = text;
        double ms = Math.min(fit(mt, font, cw - pd * 2, U * 0.11, 0.02, 1.3), U * 0.095);
        if (ms < U * 0.07 && gcount(text) > 5) { mt = brk(text, (int) Math.ceil(gcount(text) / 2.0)); ms = Math.min(fit(mt, font, cw - pd * 2, U * 0.2, 0.02, 1.3), U * 0.085); }
        var mm = meas(mt, font, ms, 0.02, 1.3);
        double headH = hs * 2.2, titleH = hs * 1.9, ch = pd + headH + titleH + mm.h() + pd * 1.1, out = tout(env), clockY = H * 0.22;
        if (p.s("pos").equals("lock")) {
            double a = tin(env, 0, 0.5, E::outCubic) * out, cs = Math.min(U * 0.2, H * 0.2);
            double st0 = Math.max(0, env.cut.start);
            int hh = (int) (Math.floor(st0 / 60) % 24), mi = (int) Math.floor(st0) % 60;
            env.draw(it(two(hh == 0 ? 12 : hh) + ":" + two(mi), font, cs, W / 2, clockY).track(0.02).color(sc.sub()).alpha(a * 0.8).noGhost());
            env.draw(it("LINE " + lineNo(env) + "  ·  " + fmtTime(env.cut.start), mono(env), hs, W / 2, clockY - cs * 0.62).track(0.2).color(sc.sub()).alpha(a * 0.8).noGhost());
        }
        double target = p.s("pos").equals("banner") ? H * 0.06 : p.s("pos").equals("lock") ? clockY + Math.min(U * 0.2, H * 0.2) * 0.75 : H / 2 - ch / 2;
        double eIn = E.outBack(clamp(env.lt / 0.45), 1.1), eOut = E.inCubic(env.pOut);
        double y0 = target - (1 - eIn) * (target + ch + 30) - eOut * (target + ch + 30), x0 = W / 2 - cw / 2;
        if (p.b("stack")) { double sw = cw * 0.92; env.rrect(W / 2 - sw / 2, y0 + ch - pd * 0.4, sw, pd * 1.4, pd * 0.7, plate, 0.45, null, 0); }
        env.rrect(x0, y0, cw, ch, pd * 0.9, plate, 0.96, null, 0);
        double iy = y0 + pd + headH / 2 - hs * 0.2, isz = hs * 1.7;
        env.rrect(x0 + pd, iy - isz / 2, isz, isz, isz * 0.24, sc.accent(), 1, null, 0);
        noteIcon(env, x0 + pd + isz / 2, iy, isz * 0.8, onCol(sc, sc.accent()), 1);
        env.draw(it(p.s("app"), mono(env), hs, x0 + pd + isz + hs * 0.7, iy).track(0.15).align("left").color(tc).alpha(0.6).noGhost());
        env.draw(it("now", mono(env), hs, x0 + cw - pd, iy).track(0.1).align("right").color(tc).alpha(0.5).noGhost());
        double ty = y0 + pd + headH + titleH / 2;
        env.draw(it("No." + lineNo(env), body(env), hs * 1.15, x0 + pd, ty).track(0.06).align("left").color(tc).alpha(0.9).noGhost());
        double my = ty + titleH / 2 + mm.h() / 2;
        BB bb = env.mainDraw(it(mt, font, ms, x0 + pd, my).align("left").track(0.02).lead(1.3).color(tc));
        return bb != null ? bb : box(x0 + pd, my - mm.h() / 2, x0 + pd + mm.w(), my + mm.h() / 2);
    }
}
