package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.Params;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static io.github.raincandyfx.rainium.LyricCompose.*;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricLayouts.def;
import static io.github.raincandyfx.rainium.LyricLayouts.fontPick;
import static io.github.raincandyfx.rainium.LyricTypo.hair;
import static io.github.raincandyfx.rainium.LyricTypo.lsz;
import static io.github.raincandyfx.rainium.LyricPaper.*;

/** Paper objects, part two: records, tapes, spines, photos, stamps, postcards, calendars, lanterns, signs, curtains, slips, scrolls, labels and cards (condensed). */
final class LyricPaper2 {
    private LyricPaper2() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    static {
        def("vinyl", "レコード", n -> n <= 16, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("spin", rng.pick(1.0, -1.0)), LyricPaper2::vinyl);
        def("cassette", "カセット", n -> n <= 16, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")).with("side", rng.pick("A", "B")), LyricPaper2::cassette);
        def("bookSpine", "背表紙", n -> n >= 2 && n <= 14, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("seed", (double) rng.integer(1, 9999)), LyricPaper2::bookSpine);
        def("polaroid", "ポラロイド", n -> n <= 14, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "body", "serif")).with("tilt", rng.range(-6, 6)), LyricPaper2::polaroid);
        def("stampSheet", "切手シート", n -> n >= 2 && n <= 12, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")), LyricPaper2::stampSheet);
        def("postcard", "絵葉書", n -> n <= 18, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "body")).with("tilt", rng.range(-5, 5)), LyricPaper2::postcard);
        def("letterPaper", "便箋", n -> n <= 20, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "body")), LyricPaper2::letterPaper);
        def("calendar", "カレンダー", n -> n <= 14, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("day", (double) rng.integer(3, 27)).with("mon", (double) rng.integer(1, 12)), LyricPaper2::calendar);
        def("chochin", "提灯", n -> n >= 2 && n <= 8, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")), LyricPaper2::chochin);
        def("routeMap", "路線図", n -> n >= 2 && n <= 16, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")), LyricPaper2::routeMap);
        def("stationSign", "駅名標", n -> n <= 10, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")), LyricPaper2::stationSign);
        def("noren", "暖簾", n -> n >= 2 && n <= 8, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")), LyricPaper2::noren);
        def("tanzaku", "短冊", n -> n >= 2 && n <= 18, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")), LyricPaper2::tanzaku);
        def("omikuji", "おみくじ", n -> n >= 2 && n <= 14, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("luck", rng.pick("大吉", "中吉", "小吉", "吉", "末吉")), LyricPaper2::omikuji);
        def("kakejiku", "掛け軸", n -> n >= 2 && n <= 12, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")), LyricPaper2::kakejiku);
        def("shoji", "障子", n -> n <= 12, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("cols", (double) rng.integer(3, 5)), LyricPaper2::shoji);
        def("clapper", "カチンコ", n -> n <= 14, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")), LyricPaper2::clapper);
        def("warningLabel", "警告ラベル", n -> n <= 16, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")), LyricPaper2::warningLabel);
        def("priceTag", "値札", n -> n <= 12, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")).with("tilt", rng.range(-12, 12)), LyricPaper2::priceTag);
        def("nameTag", "名札", n -> n <= 14, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")).with("tilt", rng.range(-5, 5)), LyricPaper2::nameTag);
        def("stickyNotes", "付箋", n -> n >= 3 && n <= 20, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "body", "display")).with("seed", (double) rng.integer(1, 9999)), LyricPaper2::stickyNotes);
        def("karuta", "かるた", n -> n >= 3 && n <= 12, (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("seed", (double) rng.integer(1, 9999)), LyricPaper2::karuta);
    }

    private static double out(Env e) { return tout(e); }

    // ------------------------------------------------------------------ vinyl
    private static BB vinyl(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env), lt = env.lt;
        double R = Math.min(H * 0.44, W * 0.3), cx = W * 0.4, cy = H / 2, ang = env.ltb * 1.8 * p.d("spin");
        Color disc = LyricKit.mix(sc.bg(), Color.BLACK, 0.8);
        env.circle(cx, cy, R, disc, LyricKit.mix(disc, sc.fg(), 0.3), Math.max(1, U * 0.002), a);
        for (int i = 1; i < 6; i++) env.circle(cx, cy, R * (0.45 + i * 0.09), null, LyricKit.mix(disc, sc.fg(), 0.18), 1, a);
        double lr = R * 0.34;
        env.circle(cx, cy, lr, sc.accent(), null, 0, a);
        Color lc = onCol(sc, sc.accent());
        rotated(env, cx, cy, Math.toDegrees(ang), () -> env.line(new double[][]{{cx - lr * 0.8, cy}, {cx + lr * 0.8, cy}}, lc, 2, a * 0.5));
        env.circle(cx, cy, R * 0.035, sc.bg(), null, 0, a);
        label(env, "SIDE A  No." + lineNo(env), cx, cy + lr * 0.55, lsz() * 0.9, a, "center", lc);
        double tx = cx + R + W * 0.04, aw = W * 0.96 - tx;
        return mainFit(env, env.cut.text, p.s("font"), tx + aw / 2, cy, aw, H * 0.5, sc.fg(), 1.15, 0.03, 3, U * 0.16);
    }

    // ------------------------------------------------------------------ cassette
    private static BB cassette(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env);
        Card C = card(sc);
        double w = Math.min(W * 0.74, H * 1.35), h = w * 0.64, x0 = (W - w) / 2, y0 = (H - h) / 2;
        Color shell = LyricKit.mix(darkest(sc), sc.bg(), 0.3);
        paper(env, x0, y0, w, h, U * 0.015, shell, a, false);
        env.rrect(x0 + w * 0.06, y0 + h * 0.08, w * 0.88, h * 0.5, U * 0.008, C.fill, a, null, 0);
        env.rect(x0 + w * 0.06, y0 + h * 0.08, w * 0.88, h * 0.07, sc.accent(), a);
        label(env, "SIDE " + p.s("side") + "  /  No." + lineNo(env), x0 + w * 0.09, y0 + h * 0.115, lsz() * 0.9, a, "left", onCol(sc, sc.accent()));
        BB bb = mainFit(env, env.cut.text, p.s("font"), x0 + w / 2, y0 + h * 0.36, w * 0.8, h * 0.28, C.text, 1.1, 0.03, 2, U * 0.12);
        double ang = env.ltb * 3;
        for (int s = -1; s <= 1; s += 2) {
            double rx = x0 + w / 2 + s * w * 0.2, ry = y0 + h * 0.74, rr = h * 0.12;
            env.circle(rx, ry, rr, C.fill, null, 0, a);
            for (int k = 0; k < 6; k++) { double q = ang * s + k * Math.PI / 3; env.line(new double[][]{{rx + Math.cos(q) * rr * 0.4, ry + Math.sin(q) * rr * 0.4}, {rx + Math.cos(q) * rr * 0.9, ry + Math.sin(q) * rr * 0.9}}, shell, 2, a); }
        }
        return bb;
    }

    // ------------------------------------------------------------------ bookSpine
    private static BB bookSpine(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, out = out(env);
        List<String> chunks = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 3)));
        int k = chunks.size();
        long seed = (long) p.d("seed");
        double bw = Math.min(W * 0.16, H * 0.2), h = H * 0.7, x = W / 2 - k * bw / 2, y0 = H * 0.15;
        Color[] pal = {sc.accent(), sc.ink(), sc.fg(), sc.accent2() != null ? sc.accent2() : sc.sub()};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double a = tin(env, i * 0.1, 0.45, E::outBack) * out, hh = h * (0.92 + 0.08 * LyricKit.r(seed, i, 1));
            Color fill = plateCol(sc, sc.bg(), pal[(int) (LyricKit.r(seed, i, 2) * pal.length)]);
            double bx = x + i * bw, by = y0 + h - hh * clamp(a);
            paper(env, bx, by, bw * 0.96, hh, 3, fill, clamp(a), false);
            env.rect(bx + bw * 0.06, by + hh * 0.06, bw * 0.84, hh * 0.015, onCol(sc, fill), clamp(a) * 0.7);
            double size = Math.min(bw * 0.6, LyricKit.fitSize(chunks.get(i), p.s("font"), bw * 0.7, hh * 0.7, new Item("", "", 1).vertical(true)));
            bb = BB.union(bb, env.mainDraw(it(chunks.get(i).replaceAll("\\s+", ""), p.s("font"), size, bx + bw * 0.48, by + hh * 0.18).vertical(true).align("left").color(onCol(sc, fill)).mi(i * 2)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ polaroid
    private static BB polaroid(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env);
        Card C = card(sc);
        double w = Math.min(W * 0.5, H * 0.72), h = w * 1.18, cx = W / 2, cy = H / 2, tilt = p.d("tilt") + Math.sin(env.ltb * 1.2) * 0.8;
        final BB[] r = new BB[1];
        rotated(env, cx, cy, tilt, () -> {
            paper(env, cx - w / 2, cy - h / 2, w, h, 4, C.fill, a, C.edge);
            Color img = plateCol(sc, C.fill, sc.ink(), sc.accent(), sc.fg());
            double ix = cx - w * 0.44, iy = cy - h / 2 + w * 0.06, iw = w * 0.88, ih = w * 0.88;
            env.rect(ix, iy, iw, ih, img, a);
            env.circle(ix + iw * 0.7, iy + ih * 0.3, ih * 0.13, plateCol(sc, img, sc.accent2(), C.fill), null, 0, a);
            r[0] = mainFit(env, env.cut.text, p.s("font"), cx, iy + ih / 2 + ih * 0.12, iw * 0.88, ih * 0.5, onCol(sc, img), 1.15, 0.03, 3, U * 0.12);
            small(env, "No." + lineNo(env) + "  " + fmtTime(env.cut.start), cx, cy + h / 2 - w * 0.07, lsz(), a * 0.8, "center", C.text);
        });
        return r[0];
    }

    // ------------------------------------------------------------------ stampSheet
    private static BB stampSheet(Env env) {
        var sc = env.sc; Params p = env.P(); double out = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size(), cols = Math.min(k, 5), rows = (int) Math.ceil(k / (double) cols);
        double s = Math.min(Math.min(W * 0.8 / cols, H * 0.7 / rows), U * 0.26);
        Card C = card(sc);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            int r = i / cols, c = i % cols, inRow = Math.min(cols, k - r * cols);
            double x = W / 2 + (c - (inRow - 1) / 2.0) * s, y = H / 2 + (r - (rows - 1) / 2.0) * s * 1.1, a = tin(env, i * 0.07, 0.4, E::outBack) * out;
            double q = s * 0.46 * clamp(a);
            paper(env, x - q, y - q * 1.1, q * 2, q * 2.2, 1, C.fill, clamp(a), false);
            Color inner = plateCol(sc, C.fill, i % 2 == 0 ? sc.accent() : sc.ink(), sc.fg());
            env.rect(x - q * 0.82, y - q * 0.92, q * 1.64, q * 1.84, inner, clamp(a));
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), q * 1.3, x, y - q * 0.05).color(onCol(sc, inner)).mi(i)));
            label(env, String.valueOf(10 * (i + 1)), x, y + q * 0.84, q * 0.2, a, "center", onCol(sc, inner));
        }
        return bb;
    }

    // ------------------------------------------------------------------ postcard
    private static BB postcard(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env), lw = hair();
        Card C = card(sc);
        double w = Math.min(W * 0.78, H * 1.45), h = w * 0.66, cx = W / 2, cy = H / 2;
        final BB[] r = new BB[1];
        rotated(env, cx, cy, p.d("tilt"), () -> {
            paper(env, cx - w / 2, cy - h / 2, w, h, 5, C.fill, a, C.edge);
            env.line(new double[][]{{cx + w * 0.02, cy - h * 0.4}, {cx + w * 0.02, cy + h * 0.4}}, C.line, lw * 2, a * 0.6);
            double sx = cx + w * 0.36, sy = cy - h * 0.3, ss = w * 0.1;
            env.rect(sx - ss / 2, sy - ss * 0.6, ss, ss * 1.2, C.acc, a);
            env.rect(sx - ss * 0.38, sy - ss * 0.46, ss * 0.76, ss * 0.92, C.fill, a * 0.8);
            env.circle(sx - ss * 0.6, sy + ss * 0.9, ss * 0.5, null, C.line, lw * 2, a * 0.6);
            for (int i = 0; i < 3; i++) env.line(new double[][]{{cx + w * 0.1, cy + h * (0.05 + i * 0.12)}, {cx + w * 0.44, cy + h * (0.05 + i * 0.12)}}, C.line, lw * 1.5, a * 0.8);
            r[0] = mainFit(env, env.cut.text, p.s("font"), cx - w * 0.23, cy - h * 0.05, w * 0.42, h * 0.5, C.text, 1.25, 0.03, 4, U * 0.1);
            small(env, "— No." + lineNo(env), cx - w * 0.04, cy + h * 0.36, lsz(), a * 0.8, "right", C.text);
        });
        return r[0];
    }

    // ------------------------------------------------------------------ letterPaper
    private static BB letterPaper(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env), lt = env.lt, lw = hair();
        Card C = card(sc);
        double w = Math.min(W * 0.58, H * 0.8), h = H * 0.9, x0 = (W - w) / 2, y0 = (H - h) / 2;
        paper(env, x0, y0, w, h, 3, C.fill, a, C.edge);
        int rows = 12;
        double top = y0 + h * 0.12, rh = h * 0.76 / rows;
        for (int i = 0; i <= rows; i++) env.line(new double[][]{{x0 + w * 0.08, top + i * rh}, {x0 + w * 0.92 * E.outExpo(clamp((lt - i * 0.02) / 0.5)) + w * 0.08 * (1 - E.outExpo(clamp((lt - i * 0.02) / 0.5))), top + i * rh}}, LyricKit.mix(C.fill, C.acc, 0.5), lw, a * 0.6);
        Block b = fitBlock(env.cut.text, p.s("font"), w * 0.76, rh * 5, 1.0, 0.04, 3, false);
        double size = Math.min(b.size(), rh * 0.8);
        String[] ls = b.text().split("\n");
        BB bb = null;
        for (int i = 0; i < ls.length; i++) bb = BB.union(bb, env.mainDraw(it(ls[i], p.s("font"), size, x0 + w * 0.12, top + (2 + i * 1.6) * rh - rh * 0.2).align("left").track(0.04).color(C.text).mi(i * 3)));
        small(env, src(env).equals(env.cut.text.trim()) ? "拝啓" : "拝啓  " + brk(src(env), 12).split("\n")[0], x0 + w * 0.12, top + rh * 0.4, lsz(), a * 0.6, "left", C.text);
        small(env, "No." + lineNo(env) + "  敬具", x0 + w * 0.9, top + 10.5 * rh, lsz(), a * 0.7, "right", C.text);
        env.circle(x0 + w * 0.82, top + 11.2 * rh, rh * 0.45, C.acc, null, 0, a * 0.8);
        return bb;
    }

    // ------------------------------------------------------------------ calendar
    private static BB calendar(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env), lt = env.lt;
        Card C = card(sc);
        double w = Math.min(W * 0.46, H * 0.74), h = H * 0.84, x0 = W * 0.1, y0 = (H - h) / 2;
        paper(env, x0, y0, w, h, 6, C.fill, a, C.edge);
        env.rect(x0, y0, w, h * 0.16, C.acc, a);
        label(env, "MON " + two((int) p.d("mon")), x0 + w / 2, y0 + h * 0.08, lsz() * 1.4, a, "center", onCol(sc, C.acc));
        double gx = x0 + w * 0.06, gy = y0 + h * 0.22, cw = w * 0.88 / 7, ch = h * 0.7 / 5;
        int day = (int) p.d("day");
        for (int i = 0; i < 35; i++) {
            int dn = i + 1;
            if (dn > 31) break;
            double x = gx + (i % 7 + 0.5) * cw, y = gy + (i / 7 + 0.5) * ch, e = E.outExpo(clamp((lt - i * 0.008) / 0.3));
            label(env, String.valueOf(dn), x, y, ch * 0.34, a * e, "center", dn == day ? onCol(sc, C.acc) : LyricKit.mix(C.fill, C.text, 0.7));
        }
        double dx = gx + (day - 1) % 7 * cw + cw / 2, dy = gy + (day - 1) / 7 * ch + ch / 2;
        env.circle(dx, dy, Math.min(cw, ch) * 0.5 * E.outBack(clamp((lt - 0.3) / 0.35)), C.acc, null, 0, a);
        label(env, String.valueOf(day), dx, dy, ch * 0.34, a, "center", onCol(sc, C.acc));
        return mainFit(env, env.cut.text, p.s("font"), W * 0.72, H / 2, W * 0.4, H * 0.5, sc.fg(), 1.15, 0.03, 3, U * 0.16);
    }

    // ------------------------------------------------------------------ chochin
    private static BB chochin(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.4, E::outCubic) * out(env), sway = Math.sin(env.ltb * 1.5) * 1.6;
        Color red = LyricKit.contrast(sc.accent(), sc.bg()) >= 1.6 ? sc.accent() : lightest(sc);
        double w = Math.min(W * 0.3, H * 0.4), h = H * 0.62, cx = W / 2, cy = H / 2 + U * 0.03;
        final BB[] r = new BB[1];
        rotated(env, cx, cy - h / 2 - U * 0.1, sway, () -> {
            env.line(new double[][]{{cx, cy - h / 2 - U * 0.1}, {cx, cy - h / 2}}, sc.fg(), Math.max(2, U * 0.004), a);
            env.rect(cx - w * 0.3, cy - h / 2, w * 0.6, h * 0.06, darkest(sc), a);
            env.rect(cx - w * 0.3, cy + h / 2 - h * 0.06, w * 0.6, h * 0.06, darkest(sc), a);
            env.circle(cx, cy, 1, null, null, 0, 0);
            env.rrect(cx - w / 2, cy - h / 2 + h * 0.05, w, h * 0.9, w * 0.5, red, a, null, 0);
            for (int i = 1; i < 7; i++) {
                double yy = cy - h / 2 + h * 0.05 + h * 0.9 * i / 7.0, k = Math.sin(Math.PI * i / 7.0), ww = w * 0.5 * k;
                env.line(new double[][]{{cx - ww, yy}, {cx + ww, yy}}, LyricKit.mix(red, darkest(sc), 0.4), Math.max(1, U * 0.002), a * 0.8);
            }
            String t = strip(env.cut.text);
            double size = Math.min(w * 0.5, LyricKit.fitSize(t, p.s("font"), w * 0.6, h * 0.6, new Item("", "", 1).vertical(true)));
            r[0] = env.mainDraw(it(t, p.s("font"), size, cx, cy - h * 0.22).vertical(true).align("left").color(onCol(sc, red)));
        });
        return r[0];
    }

    // ------------------------------------------------------------------ routeMap
    private static BB routeMap(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, out = out(env);
        List<String> st = splitK(env.cut.text, Math.min(5, Math.max(2, gcount(env.cut.text) / 2)));
        int k = st.size();
        double x0 = W * 0.12, x1 = W * 0.88, y = H / 2, lw = Math.max(4, U * 0.012), e = E.outExpo(clamp(lt / 0.8)) * out;
        env.line(new double[][]{{x0, y}, {x0 + (x1 - x0) * e, y}}, sc.accent(), lw, 1);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = LyricKit.lerp(x0, x1, k > 1 ? i / (double) (k - 1) : 0.5), a = tin(env, 0.15 + i * 0.12, 0.35, E::outBack) * out;
            if (a <= 0.01) continue;
            env.circle(x, y, U * 0.022 * clamp(a), sc.bg(), sc.fg(), Math.max(2, lw * 0.5), 1);
            double size = Math.min(U * 0.07, LyricKit.fitSize(st.get(i), p.s("font"), (x1 - x0) / k * 0.9, H * 0.2));
            double ty = i % 2 == 0 ? y - U * 0.1 : y + U * 0.12;
            bb = BB.union(bb, env.mainDraw(it(st.get(i), p.s("font"), size, x, ty).color(sc.fg()).mi(i * 2)));
            label(env, "S" + two(i + 1), x, i % 2 == 0 ? y - U * 0.045 : y + U * 0.05, lsz() * 0.8, a, "center", sc.sub());
        }
        return bb;
    }

    // ------------------------------------------------------------------ stationSign
    private static BB stationSign(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env);
        Card C = card(sc);
        double w = W * 0.82, h = H * 0.42, x0 = (W - w) / 2, y0 = (H - h) / 2;
        paper(env, x0, y0, w, h, 4, C.fill, a, C.edge);
        env.rect(x0, y0 + h * 0.8, w, h * 0.2, C.acc, a);
        label(env, "← PREV", x0 + w * 0.04, y0 + h * 0.9, lsz(), a, "left", onCol(sc, C.acc));
        label(env, "NEXT →", x0 + w * 0.96, y0 + h * 0.9, lsz(), a, "right", onCol(sc, C.acc));
        env.circle(x0 + w * 0.08, y0 + h * 0.32, h * 0.14, null, C.acc, Math.max(3, h * 0.03), a);
        label(env, "01", x0 + w * 0.08, y0 + h * 0.32, h * 0.12, a, "center", C.acc);
        return mainFit(env, env.cut.text, p.s("font"), x0 + w * 0.56, y0 + h * 0.4, w * 0.78, h * 0.62, C.text, 1.1, 0.12, 2, U * 0.2);
    }

    // ------------------------------------------------------------------ noren
    private static BB noren(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, out = out(env);
        Color cloth = plateCol(sc, sc.bg(), sc.ink(), sc.accent(), sc.fg());
        double top = H * 0.1, w = W * 0.7, h = H * 0.55, x0 = (W - w) / 2, a = E.outCubic(clamp(lt / 0.4)) * out;
        env.rect(x0 - U * 0.02, top - U * 0.02, w + U * 0.04, U * 0.03, darkest(sc), a);
        double part = E.outCubic(clamp((lt - 0.15) / 0.5)) * 0.04 * w;
        int strips = 4;
        double sw = w / strips;
        for (int i = 0; i < strips; i++) {
            double dx = (i < strips / 2 ? -1 : 1) * part * (1 - Math.abs(i - (strips - 1) / 2.0) / strips);
            env.rect(x0 + i * sw + dx, top, sw * 0.97, h * Math.sin(1.4 + i * 0.1) / Math.sin(1.4) * 0.5 + h * 0.5, cloth, a);
        }
        Color tc = onCol(sc, cloth);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, top + h * 0.38, w * 0.6, h * 0.5, tc, 1.1, 0.1, 2, U * 0.2);
    }

    // ------------------------------------------------------------------ tanzaku
    private static BB tanzaku(Env env) {
        var sc = env.sc; Params p = env.P(); double out = out(env);
        List<String> parts = splitK(env.cut.text, Math.min(3, Math.max(1, gcount(env.cut.text) / 5)));
        int k = parts.size();
        double w = Math.min(W * 0.18, U * 0.2), h = H * 0.74, gap = w * 0.5, x0 = W / 2 + (k - 1) * (w + gap) / 2, y0 = H * 0.15;
        Color[] pal = {lightest(sc), sc.accent(), sc.accent2() != null ? sc.accent2() : sc.sub()};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double a = tin(env, i * 0.12, 0.45, E::outCubic) * out, x = x0 - i * (w + gap), sway = Math.sin(env.ltb * 1.3 + i) * 1.5, yy = y0 + (1 - a) * -H * 0.1;
            Color fill = pal[i % pal.length];
            final BB[] r = new BB[1];
            final int idx = i;
            rotated(env, x, yy, sway, () -> {
                env.line(new double[][]{{x, yy - U * 0.05}, {x, yy}}, sc.fg(), 2, a);
                paper(env, x - w / 2, yy, w, h, 2, fill, clamp(a), false);
                env.line(new double[][]{{x - w * 0.38, yy + h * 0.06}, {x + w * 0.38, yy + h * 0.06}}, onCol(sc, fill), 1.5, a * 0.5);
                String t = strip(parts.get(idx));
                double size = Math.min(w * 0.55, LyricKit.fitSize(t, p.s("font"), w * 0.7, h * 0.78, new Item("", "", 1).vertical(true).track(0.06)));
                r[0] = env.mainDraw(it(t, p.s("font"), size, x, yy + h * 0.12).vertical(true).align("left").track(0.06).color(onCol(sc, fill)).mi(idx * 2));
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ omikuji
    private static BB omikuji(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env), lt = env.lt;
        Card C = card(sc);
        double w = Math.min(W * 0.34, H * 0.46), h = H * 0.86, cx = W / 2, y0 = (H - h) / 2, e = E.outCubic(clamp(lt / 0.6));
        double hh = h * e;
        paper(env, cx - w / 2, y0, w, hh, 2, C.fill, a, C.edge);
        env.line(new double[][]{{cx - w * 0.4, y0 + w * 0.12}, {cx + w * 0.4, y0 + w * 0.12}}, C.acc, 2, a);
        env.draw(it(p.s("luck"), p.s("font"), w * 0.5, cx, y0 + w * 0.5).color(C.acc).alpha(a).noGhost());
        double ty = y0 + w * 0.9;
        BB bb = null;
        String t = strip(env.cut.text);
        if (hh > w * 1.2) {
            double size = Math.min(w * 0.4, LyricKit.fitSize(t, p.s("font"), w * 0.7, h - w * 1.3, new Item("", "", 1).vertical(true).track(0.05)));
            bb = env.mainDraw(it(t, p.s("font"), size, cx, ty).vertical(true).align("left").track(0.05).color(C.text));
        }
        small(env, "第" + lineNo(env) + "番", cx, y0 + hh - w * 0.12, lsz(), a * 0.8, "center", C.text);
        return bb != null ? bb : box(cx - w / 2, y0, cx + w / 2, y0 + hh);
    }

    // ------------------------------------------------------------------ kakejiku
    private static BB kakejiku(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outCubic) * out(env), lt = env.lt;
        Card C = card(sc);
        Color mount = plateCol(sc, sc.bg(), sc.ink(), sc.accent(), sc.fg());
        double w = Math.min(W * 0.3, H * 0.4), h = H * 0.9, cx = W / 2, y0 = (H - h) / 2, e = E.outCubic(clamp(lt / 0.7)), hh = h * e;
        env.rect(cx - w / 2, y0, w, hh, mount, a);
        env.rect(cx - w * 0.36, y0 + h * 0.07, w * 0.72, Math.max(0, hh - h * 0.14), C.fill, a);
        env.rrect(cx - w * 0.6, y0 - h * 0.012, w * 1.2, h * 0.024, h * 0.012, darkest(sc), a, null, 0);
        env.rrect(cx - w * 0.6, y0 + hh - h * 0.012, w * 1.2, h * 0.024, h * 0.012, darkest(sc), a, null, 0);
        String t = strip(env.cut.text);
        double size = Math.min(w * 0.45, LyricKit.fitSize(t, p.s("font"), w * 0.6, h * 0.7, new Item("", "", 1).vertical(true).track(0.05)));
        BB bb = e > 0.7 ? env.mainDraw(it(t, p.s("font"), size, cx, y0 + h * 0.13).vertical(true).align("left").track(0.05).color(C.text)) : null;
        env.circle(cx + w * 0.22, y0 + hh - h * 0.14, w * 0.06, C.acc, null, 0, a * 0.9);
        return bb != null ? bb : box(cx - w / 2, y0, cx + w / 2, y0 + hh);
    }

    // ------------------------------------------------------------------ shoji
    private static BB shoji(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, out = out(env), a = tin(env, 0, 0.3, E::outCubic) * out;
        Card C = card(sc);
        double w = W * 0.78, h = H * 0.86, x0 = (W - w) / 2, y0 = (H - h) / 2;
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, w * 0.62, h * 0.5, sc.fg(), 1.15, 0.05, 3, U * 0.2);
        int cols = (int) p.d("cols"), rows = 6;
        double slide = E.inOutCubic(clamp((lt - 0.1) / 0.6)) * out;
        Color wood = LyricKit.mix(darkest(sc), sc.bg(), 0.2);
        double pw = w / 2;
        for (int side = 0; side < 2; side++) {
            double px = x0 + side * pw + (side == 0 ? -1 : 1) * pw * 0.85 * slide;
            if (Math.abs(slide) > 0.97) continue;
            env.rect(px, y0, pw, h, C.fill, a * 0.92);
            for (int i = 0; i <= cols; i++) env.line(new double[][]{{px + pw * i / cols, y0}, {px + pw * i / cols, y0 + h}}, wood, Math.max(2, U * 0.005), a);
            for (int j = 0; j <= rows; j++) env.line(new double[][]{{px, y0 + h * j / rows}, {px + pw, y0 + h * j / rows}}, wood, Math.max(2, U * 0.005), a);
        }
        return bb;
    }

    // ------------------------------------------------------------------ clapper
    private static BB clapper(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = tin(env, 0, 0.25, E::outCubic) * out(env);
        Color dark = darkest(sc), light = lightest(sc);
        double w = Math.min(W * 0.72, H * 1.3), h = w * 0.62, x0 = (W - w) / 2, y0 = (H - h) / 2 + h * 0.06, bh = h * 0.16;
        env.rect(x0, y0 + bh, w, h - bh, dark, a);
        for (int r = 1; r < 4; r++) env.line(new double[][]{{x0, y0 + bh + (h - bh) * r / 4}, {x0 + w, y0 + bh + (h - bh) * r / 4}}, light, hair() * 1.5, a * 0.6);
        double clap = lt < 0.4 ? 1 - E.outBack(clamp(lt / 0.35)) : 0, ang = -26 * clap;
        Color stripe = light;
        rotated(env, x0, y0 + bh, ang, () -> {
            env.rect(x0, y0, w, bh * 0.9, dark, a);
            for (int i = 0; i < 8; i++) env.poly(new double[][]{{x0 + w * i / 8, y0}, {x0 + w * (i + 0.5) / 8, y0}, {x0 + w * (i + 0.5) / 8 - bh * 0.4, y0 + bh * 0.9}, {x0 + w * i / 8 - bh * 0.4, y0 + bh * 0.9}}, stripe, a);
        });
        for (int i = 0; i < 8; i++) env.poly(new double[][]{{x0 + w * i / 8, y0 + bh}, {x0 + w * (i + 0.5) / 8, y0 + bh}, {x0 + w * (i + 0.5) / 8 - bh * 0.4, y0 + bh * 1.9}, {x0 + w * i / 8 - bh * 0.4, y0 + bh * 1.9}}, stripe, a);
        label(env, "SCENE " + lineNo(env) + "   TAKE " + (1 + (int) (env.cut.seed % 9)), x0 + w * 0.04, y0 + bh * 2.7, lsz() * 1.1, a, "left", light);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, y0 + h * 0.62, w * 0.88, h * 0.42, light, 1.1, 0.03, 2, U * 0.14);
    }

    // ------------------------------------------------------------------ warningLabel
    private static BB warningLabel(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.25, E::outCubic) * out(env), lt = env.lt;
        Color yellow = LyricKit.contrast(sc.accent(), sc.bg()) >= 1.8 ? sc.accent() : lightest(sc), black = onCol(sc, yellow);
        double w = W * 0.82, h = H * 0.5, x0 = (W - w) / 2, y0 = (H - h) / 2, bar = h * 0.14;
        env.rect(x0, y0, w, h, yellow, a);
        double off = env.ltb * 20 % (bar * 2);
        for (int i = -2; i < w / bar + 2; i++) {
            double sx = x0 + i * bar * 2 + off;
            if (sx + bar * 2 < x0 || sx > x0 + w) continue;
            double a0 = Math.max(sx, x0), a1 = Math.min(sx + bar, x0 + w);
            env.poly(new double[][]{{a0, y0}, {a1, y0}, {a1, y0 + bar}, {a0, y0 + bar}}, black, a);
            env.poly(new double[][]{{a0, y0 + h - bar}, {a1, y0 + h - bar}, {a1, y0 + h}, {a0, y0 + h}}, black, a);
        }
        label(env, "⚠ WARNING / 警告  No." + lineNo(env), x0 + w * 0.04, y0 + bar * 1.8, lsz() * 1.1, a, "left", black);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, y0 + h * 0.58, w * 0.86, h * 0.5, black, 1.1, 0.04, 2, U * 0.18);
    }

    // ------------------------------------------------------------------ priceTag
    private static BB priceTag(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.35, E::outBack) * out(env);
        Card C = card(sc);
        double w = Math.min(W * 0.6, H * 1.0), h = w * 0.5, cx = W / 2, cy = H / 2, sway = p.d("tilt") + Math.sin(env.ltb * 1.6) * 2;
        final BB[] r = new BB[1];
        rotated(env, cx - w * 0.44, cy, sway * clamp(a), () -> {
            double nx = w * 0.14;
            double[][] tag = {{cx - w / 2 + nx, cy - h / 2}, {cx + w / 2, cy - h / 2}, {cx + w / 2, cy + h / 2}, {cx - w / 2 + nx, cy + h / 2}, {cx - w / 2, cy}};
            env.poly(offs(tag, U * 0.008, U * 0.01), new Color(0, 0, 0, 70), clamp(a));
            env.poly(tag, C.fill, clamp(a));
            env.circle(cx - w * 0.4, cy, h * 0.07, sc.bg(), C.line, 2, clamp(a));
            env.line(new double[][]{{cx - w * 0.4, cy}, {cx - w * 0.62, cy - h * 0.1}}, C.line, 2, clamp(a));
            r[0] = mainFit(env, env.cut.text, p.s("font"), cx + w * 0.02, cy - h * 0.08, w * 0.7, h * 0.5, C.text, 1.1, 0.03, 2, U * 0.12);
            label(env, "¥" + (int) (980 + env.cut.seed % 9000) + "  No." + lineNo(env), cx + w * 0.42, cy + h * 0.36, lsz(), a, "right", C.acc);
        });
        return r[0];
    }

    // ------------------------------------------------------------------ nameTag
    private static BB nameTag(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tin(env, 0, 0.3, E::outBack) * out(env);
        Card C = card(sc);
        Color head = plateCol(sc, C.fill, sc.accent(), sc.ink(), sc.fg());
        double w = Math.min(W * 0.6, H * 1.0), h = w * 0.66, cx = W / 2, cy = H / 2;
        final BB[] r = new BB[1];
        rotated(env, cx, cy, p.d("tilt"), () -> {
            paper(env, cx - w / 2, cy - h / 2, w, h, w * 0.04, C.fill, clamp(a), C.edge);
            env.rrect(cx - w / 2, cy - h / 2, w, h * 0.32, w * 0.04, head, clamp(a), null, 0);
            env.rect(cx - w / 2, cy - h / 2 + h * 0.2, w, h * 0.12, head, clamp(a));
            env.draw(it("HELLO", body(env), h * 0.13, cx, cy - h * 0.38).color(onCol(sc, head)).alpha(clamp(a)).noGhost());
            env.draw(it("my name is", body(env), h * 0.08, cx, cy - h * 0.22).color(onCol(sc, head)).alpha(clamp(a)).noGhost());
            r[0] = mainFit(env, env.cut.text, p.s("font"), cx, cy + h * 0.16, w * 0.84, h * 0.46, C.text, 1.1, 0.03, 2, U * 0.14);
        });
        return r[0];
    }

    // ------------------------------------------------------------------ stickyNotes
    private static BB stickyNotes(Env env) {
        var sc = env.sc; Params p = env.P(); double out = out(env);
        List<String> parts = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 3)));
        int k = parts.size();
        long seed = (long) p.d("seed");
        Color[] pal = {new Color(0xFFE66B), new Color(0xFF9EB5), new Color(0x8FE3B0), new Color(0x9ED0FF)};
        double s = Math.min(W * 0.3, H * 0.4);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double a = tin(env, i * 0.12, 0.4, E::outBack) * out;
            double x = W / 2 + (i - (k - 1) / 2.0) * s * 0.95 + LyricKit.rs(seed, i, 1) * s * 0.06, y = H / 2 + LyricKit.rs(seed, i, 2) * s * 0.18 - (1 - clamp(a)) * s * 0.4, rot = LyricKit.rs(seed, i, 3) * 6;
            Color fill = pal[(int) (LyricKit.r(seed, i, 4) * pal.length)];
            final int idx = i;
            final BB[] r = new BB[1];
            rotated(env, x, y, rot, () -> {
                paper(env, x - s / 2, y - s / 2, s, s, 2, fill, clamp(a), false);
                env.rect(x - s / 2, y - s / 2, s, s * 0.1, LyricKit.mix(fill, Color.WHITE, 0.3), clamp(a));
                r[0] = mainFit(env, parts.get(idx), p.s("font"), x, y + s * 0.05, s * 0.82, s * 0.62, new Color(0x222222), 1.15, 0.03, 3, U * 0.1);
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ karuta
    private static BB karuta(Env env) {
        var sc = env.sc; Params p = env.P(); double out = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 8);
        long seed = (long) p.d("seed");
        Card C = card(sc);
        double cw = Math.min(W * 0.84 / k, U * 0.2), ch = cw * 1.45;
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double a = tin(env, i * 0.07, 0.35, E::outBack) * out, x = W / 2 + (i - (k - 1) / 2.0) * cw * 1.03, y = H / 2 + LyricKit.rs(seed, i, 1) * ch * 0.06, rot = LyricKit.rs(seed, i, 2) * 4;
            final int idx = i;
            final BB[] r = new BB[1];
            rotated(env, x, y, rot, () -> {
                paper(env, x - cw / 2, y - ch / 2, cw * 0.96, ch, 3, C.fill, clamp(a), C.edge);
                env.rect(x - cw * 0.42, y - ch * 0.46, cw * 0.84, ch * 0.08, C.acc, clamp(a));
                r[0] = env.mainDraw(it(us.get(idx), p.s("font"), cw * 0.72, x, y + ch * 0.04).color(C.text).mi(idx));
                label(env, two(idx + 1), x, y + ch * 0.4, cw * 0.14, a, "center", C.acc);
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }
}
