package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.Params;

import java.awt.Color;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static io.github.raincandyfx.rainium.LyricCompose.*;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricLayouts.def;
import static io.github.raincandyfx.rainium.LyricLayouts.fontPick;
import static io.github.raincandyfx.rainium.LyricPaper.*;
import static io.github.raincandyfx.rainium.LyricTypo.hair;
import static io.github.raincandyfx.rainium.LyricTypo.lsz;

/**
 * Playful, faux-3D and physical layouts: cubes, cylinders, flip cards, flags, ribbons, pendulums, piles, blocks, balloons, magnets,
 * tiles, bulbs, LED boards, billboards, bubbles, puzzles, kaleidoscopes, dominoes, bursts, walls, zippers, slices, masks and stencils
 * (condensed re-creations, see Credits).
 */
final class LyricPlay {
    private LyricPlay() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    private static Params ps(Params p, Object... kv) { for (int i = 0; i < kv.length; i += 2) p.with((String) kv[i], kv[i + 1]); return p; }

    static {
        def("cube", "立方体", n -> n >= 2 && n <= 16, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "dir", rng.pick(1.0, -1.0)), LyricPlay::cube);
        def("cylinder", "円筒", n -> n >= 3 && n <= 16, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif"), "dir", rng.pick(1.0, -1.0)), LyricPlay::cylinder);
        def("flipCards", "フリップ", n -> n >= 2 && n <= 10, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif")), LyricPlay::flipCards);
        def("accordion", "蛇腹", n -> n >= 2 && n <= 16, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "k", (double) rng.integer(4, 7)), LyricPlay::accordion);
        def("flag", "旗", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "amp", rng.range(0.05, 0.1)), LyricPlay::flag);
        def("ribbon", "リボン", n -> n <= 16, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif"), "amp", rng.range(0.08, 0.16)), LyricPlay::ribbon);
        def("pendulum", "振り子", n -> n >= 2 && n <= 10, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif")), LyricPlay::pendulum);
        def("pile", "山積み", n -> n >= 3 && n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "seed", (double) rng.integer(1, 9999)), LyricPlay::pile);
        def("blocks", "積み木", n -> n >= 2 && n <= 10, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "seed", (double) rng.integer(1, 9999)), LyricPlay::blocks);
        def("balloons", "風船", n -> n >= 2 && n <= 10, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "seed", (double) rng.integer(1, 9999)), LyricPlay::balloons);
        def("magnets", "マグネット", n -> n >= 2 && n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "seed", (double) rng.integer(1, 9999)), LyricPlay::magnets);
        def("tiles", "文字タイル", n -> n >= 2 && n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "serif", "display")), LyricPlay::tiles);
        def("bulbs", "電球看板", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay::bulbs);
        def("ledScroll", "LED電光掲示", n -> n <= 24, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "mono"), "speed", rng.range(120, 260)), LyricPlay::ledScroll);
        def("billboard", "看板", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay::billboard);
        def("crowdBubbles", "吹き出し群", n -> n >= 3 && n <= 24, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "body", "display"), "seed", (double) rng.integer(1, 9999)), LyricPlay::crowdBubbles);
        def("crossword", "クロスワード", n -> n >= 3 && n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "serif", "display")), LyricPlay2::crossword);
        def("wordSearch", "言葉探し", n -> n >= 2 && n <= 8, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "body"), "seed", (double) rng.integer(1, 9999)), LyricPlay2::wordSearch);
        def("puzzle", "パズル", n -> n >= 3 && n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::puzzle);
        def("shadowPlay", "影絵", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "dir", rng.range(0.4, 1.2)), LyricPlay2::shadowPlay);
        def("kaleido", "万華鏡", n -> n <= 10, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "n", (double) rng.pick(6, 8, 10)), LyricPlay2::kaleido);
        def("dominoes", "ドミノ", n -> n >= 3 && n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif")), LyricPlay2::dominoes);
        def("burst", "集中線", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "rays", (double) rng.integer(28, 56)), LyricPlay2::burst);
        def("fisheye", "魚眼", n -> n >= 3 && n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif")), LyricPlay2::fisheye);
        def("wall", "煉瓦壁", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::wall);
        def("origami", "折り紙", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif")), LyricPlay2::origami);
        def("zipper", "ジッパー", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::zipper);
        def("sliceStack", "スライス積み", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "k", (double) rng.integer(4, 7)), LyricPlay2::sliceStack);
        def("glitchGrid", "グリッチ格子", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::glitchGrid);
        def("mosaicTiles", "モザイク", n -> n >= 2 && n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display"), "seed", (double) rng.integer(1, 9999)), LyricPlay2::mosaicTiles);
        def("maskReveal", "マスク開示", n -> n <= 14, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display", "serif"), "shape", rng.pick("circle", "bars", "diamond")), LyricPlay2::maskReveal);
        def("contour", "等高線", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::contour);
        def("halftoneBig", "網点", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::halftoneBig);
        def("stencil", "ステンシル", n -> n <= 12, (rng, cut, st) -> ps(new Params(), "font", fontPick(rng, st, "display")), LyricPlay2::stencil);
    }

    private static double out(Env e) { return tout(e); }

    private static Color plate(Env env, Color... pref) { return plateCol(env.sc, env.sc.bg(), pref); }

    // ------------------------------------------------------------------ cube
    private static BB cube(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env);
        List<String> parts = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 2)));
        int k = parts.size();
        double w = Math.min(W * 0.5, H * 0.9), h = Math.min(H * 0.5, w * 0.6), cx = W / 2, cy = H / 2;
        double rot = (env.ltb * 0.9 + 0.2) * p.d("dir") * (Math.PI / 2) / Math.max(0.2, env.dur() / k);
        BB bb = null;
        Color[] pal = {plate(env, sc.accent()), plate(env, sc.ink(), sc.fg()), plate(env, sc.accent2(), sc.sub()), plate(env, sc.fg())};
        for (int i = 0; i < 4; i++) {
            double th = rot + i * Math.PI / 2, c = Math.cos(th);
            if (c <= 0.02) continue;
            double fw = w * c, fx = cx + Math.sin(th) * w * 0.5;
            Color fill = LyricKit.mix(pal[i], Color.BLACK, 0.45 * (1 - c));
            env.rect(fx - fw / 2, cy - h / 2, fw, h, fill, a);
            String t = parts.get(i % k);
            double size = Math.min(h * 0.6, LyricKit.fitSize(t, p.s("font"), w * 0.8, h * 0.7));
            BB r = env.mainDraw(it(t, p.s("font"), size, fx, cy).sx(c).color(onCol(sc, pal[i])).mi(i));
            bb = BB.union(bb, r);
        }
        return bb;
    }

    // ------------------------------------------------------------------ cylinder
    private static BB cylinder(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size();
        double R = Math.min(W * 0.34, k * U * 0.06), size = Math.min(U * 0.22, R * 2 * Math.PI / Math.max(4, k) * 0.9), cx = W / 2, cy = H / 2;
        double rot = env.ltb * 0.7 * p.d("dir");
        BB bb = null;
        env.line(new double[][]{{cx - R, cy - size * 0.7}, {cx + R, cy - size * 0.7}}, sc.sub(), hair() * 2, 0.5 * a);
        env.line(new double[][]{{cx - R, cy + size * 0.7}, {cx + R, cy + size * 0.7}}, sc.sub(), hair() * 2, 0.5 * a);
        for (int i = 0; i < k; i++) {
            double th = rot + i * 2 * Math.PI / Math.max(k, 6), c = Math.cos(th);
            if (c < -0.05) continue;
            double x = cx + Math.sin(th) * R, al = clamp(0.25 + 0.75 * c);
            bb = BB.union(bb, env.draw(it(us.get(i), p.s("font"), size, x, cy).sx(Math.max(0.12, c)).color(sc.fg()).alpha(al * a).noGhost()));
        }
        return bb != null ? bb : box(cx - R, cy - size, cx + R, cy + size);
    }

    // ------------------------------------------------------------------ flipCards
    private static BB flipCards(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 8);
        double cw = Math.min(W * 0.84 / k, U * 0.22), ch = cw * 1.3;
        Color back = plate(env, sc.accent(), sc.ink()), face = lightest(sc);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * cw * 1.05, f = E.inOutCubic(clamp((lt - 0.08 * i - 0.1) / 0.45)), c = Math.cos(f * Math.PI), sxw = Math.max(0.03, Math.abs(c));
            boolean front = f > 0.5;
            Color fill = front ? face : back;
            paper(env, x - cw * 0.46 * sxw, H / 2 - ch / 2, cw * 0.92 * sxw, ch, 4, fill, a, false);
            if (front) bb = BB.union(bb, env.draw(it(us.get(i), p.s("font"), cw * 0.7, x, H / 2).sx(sxw).color(onCol(sc, face)).alpha(a).noGhost()));
        }
        return bb != null ? bb : box(W * 0.1, H / 2 - ch / 2, W * 0.9, H / 2 + ch / 2);
    }

    // ------------------------------------------------------------------ accordion
    private static BB accordion(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        int k = (int) p.d("k");
        double w = W * 0.78, h = H * 0.46, x0 = (W - w) / 2, y0 = (H - h) / 2, open = E.outCubic(clamp(lt / 0.6)) * a, pw = w / k;
        Color c1 = plate(env, sc.accent(), sc.ink()), c2 = LyricKit.mix(c1, Color.BLACK, 0.25);
        Block b = fitBlock(env.cut.text, p.s("font"), w * 0.9, h * 0.8, 1.1, 0.03, 2, false);
        double size = Math.min(b.size(), U * 0.2);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double px = x0 + i * pw, pwd = pw * (0.35 + 0.65 * open), cxp = x0 + (i + 0.5) * pw, left = cxp - pwd / 2;
            var old = env.g.getClip();
            env.g.clip(new Rectangle2D.Double(left, y0, pwd, h));
            env.rect(left, y0, pwd, h, i % 2 == 0 ? c1 : c2, a);
            env.mainDraw(it(b.text(), p.s("font"), size, W / 2, H / 2).lead(1.1).track(0.03).color(onCol(sc, c1)));
            env.g.setClip(old);
        }
        return box(x0, y0, x0 + w, y0 + h);
    }

    // ------------------------------------------------------------------ flag
    private static BB flag(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env), t = env.ltb;
        Color cloth = plate(env, sc.accent(), sc.ink(), sc.fg());
        double w = W * 0.66, h = H * 0.42, x0 = (W - w) / 2, y0 = (H - h) / 2, amp = h * p.d("amp");
        env.rect(x0 - U * 0.015, y0 - h * 0.15, U * 0.012, h * 1.4, sc.sub(), a);
        int n = 24;
        for (int i = 0; i < n; i++) {
            double u0 = i / (double) n, u1 = (i + 1) / (double) n;
            double d0 = Math.sin(u0 * 7 - t * 4) * amp * u0, d1 = Math.sin(u1 * 7 - t * 4) * amp * u1;
            Color c = LyricKit.mix(cloth, Color.BLACK, 0.2 + 0.2 * Math.sin(u0 * 7 - t * 4));
            env.poly(new double[][]{{x0 + w * u0, y0 + d0}, {x0 + w * u1 + 1, y0 + d1}, {x0 + w * u1 + 1, y0 + h + d1}, {x0 + w * u0, y0 + h + d0}}, c, a);
        }
        List<String> us = charUnits(env.cut.text);
        double size = Math.min(h * 0.6, w * 0.84 / Math.max(1, us.size()) * 1.0);
        BB bb = null;
        for (int i = 0; i < us.size(); i++) {
            double u = (i + 0.5) / us.size() * 0.84 + 0.08, d = Math.sin(u * 7 - t * 4) * amp * u;
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, x0 + w * u, y0 + h / 2 + d).color(onCol(sc, cloth)).rot(Math.cos(u * 7 - t * 4) * 6 * u).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ ribbon
    private static BB ribbon(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env), t = env.ltb, lt = env.lt;
        Color band = plate(env, sc.accent(), sc.ink(), sc.fg());
        double h = H * 0.2, amp = H * p.d("amp"), x0 = -W * 0.05, x1 = W * 1.05, e = E.outExpo(clamp(lt / 0.6));
        int n = 40;
        for (int i = 0; i < n * e; i++) {
            double u0 = i / (double) n, u1 = (i + 1) / (double) n;
            double y0 = H / 2 + Math.sin(u0 * 5 + t * 2) * amp, y1 = H / 2 + Math.sin(u1 * 5 + t * 2) * amp;
            double fold = 0.6 + 0.4 * Math.cos(u0 * 5 + t * 2);
            env.poly(new double[][]{{x0 + (x1 - x0) * u0, y0 - h * fold / 2}, {x0 + (x1 - x0) * u1 + 1, y1 - h * fold / 2}, {x0 + (x1 - x0) * u1 + 1, y1 + h * fold / 2}, {x0 + (x1 - x0) * u0, y0 + h * fold / 2}}, LyricKit.mix(band, Color.BLACK, 0.3 * (1 - fold)), a);
        }
        List<String> us = charUnits(env.cut.text);
        double size = Math.min(h * 0.7, W * 0.8 / Math.max(1, us.size()));
        BB bb = null;
        for (int i = 0; i < us.size(); i++) {
            double u = 0.5 + (i - (us.size() - 1) / 2.0) * size / (x1 - x0) * 1.05, y = H / 2 + Math.sin(u * 5 + t * 2) * amp, slope = Math.cos(u * 5 + t * 2) * amp * 5 / (x1 - x0);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, x0 + (x1 - x0) * u, y).rot(Math.toDegrees(Math.atan(slope))).color(onCol(sc, band)).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ pendulum
    private static BB pendulum(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env), lt = env.lt;
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 9);
        double size = Math.min(U * 0.2, W * 0.8 / k), top = H * 0.08, len = H * 0.5;
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x0 = W / 2 + (i - (k - 1) / 2.0) * size * 1.08, damp = Math.exp(-lt * 0.5), ang = Math.sin(env.ltb * 3 + i * 0.35) * 0.35 * damp * clamp(lt * 2 + 0.1);
            double bx = x0 + Math.sin(ang) * len, by = top + Math.cos(ang) * len;
            env.line(new double[][]{{x0, top}, {bx, by}}, sc.sub(), hair() * 1.5, 0.6 * a);
            env.circle(bx, by - size * 0.55, size * 0.06, sc.accent(), null, 0, a);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, bx, by).rot(Math.toDegrees(ang)).color(sc.fg()).mi(i)));
        }
        env.rect(W / 2 - (k * size * 1.08) / 2 - size * 0.2, top - U * 0.01, k * size * 1.08 + size * 0.4, U * 0.012, sc.fg(), a);
        return bb;
    }

    // ------------------------------------------------------------------ pile
    private static BB pile(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size();
        long seed = (long) p.d("seed");
        double s = Math.min(U * 0.2, W * 0.8 / Math.max(4, k * 0.7));
        Card C = card(sc);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double drop = E.outBounce(clamp((lt - i * 0.07) / 0.5)), x = W / 2 + LyricKit.rs(seed, i, 1) * W * 0.16 + (i - (k - 1) / 2.0) * s * 0.35, floor = H * 0.8 - (i / 3) * s * 0.55, y = LyricKit.lerp(-s, floor, drop);
            Color fill = i % 3 == 0 ? plate(env, sc.accent()) : i % 3 == 1 ? C.fill : plate(env, sc.ink(), sc.fg());
            final int idx = i; final double fx = x, fy = y;
            final BB[] r = new BB[1];
            rotated(env, x, y, LyricKit.rs(seed, i, 2) * 18, () -> {
                paper(env, fx - s / 2, fy - s / 2, s, s, 3, fill, a, false);
                r[0] = env.mainDraw(it(us.get(idx), p.s("font"), s * 0.72, fx, fy).color(onCol(sc, fill)).mi(idx));
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ blocks
    private static BB blocks(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 10);
        long seed = (long) p.d("seed");
        double s = Math.min(U * 0.2, W * 0.8 / k);
        Color[] pal = {sc.accent(), plate(env, sc.accent2(), sc.sub()), plate(env, sc.ink(), sc.fg()), lightest(sc)};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * s * 1.02, y = H * 0.62 - s / 2 - (i % 2 == 1 ? s : 0) * E.outBounce(clamp((lt - i * 0.08) / 0.5)) - (1 - E.outBounce(clamp((lt - i * 0.08) / 0.5))) * 0;
            Color fill = pal[(int) (LyricKit.r(seed, i, 1) * pal.length)];
            double top = s * 0.18;
            env.poly(new double[][]{{x - s / 2, y - s / 2}, {x - s / 2 + top, y - s / 2 - top}, {x + s / 2 + top, y - s / 2 - top}, {x + s / 2, y - s / 2}}, LyricKit.mix(fill, Color.WHITE, 0.3), a);
            env.poly(new double[][]{{x + s / 2, y - s / 2}, {x + s / 2 + top, y - s / 2 - top}, {x + s / 2 + top, y + s / 2 - top}, {x + s / 2, y + s / 2}}, LyricKit.mix(fill, Color.BLACK, 0.3), a);
            env.rect(x - s / 2, y - s / 2, s, s, fill, a);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), s * 0.7, x, y).color(onCol(sc, fill)).mi(i)));
        }
        env.line(new double[][]{{W * 0.06, H * 0.62 + s * 0.02}, {W * 0.94, H * 0.62 + s * 0.02}}, sc.sub(), hair() * 3, 0.6 * a);
        return bb;
    }

    // ------------------------------------------------------------------ balloons
    private static BB balloons(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 10);
        long seed = (long) p.d("seed");
        double r0 = Math.min(U * 0.12, W * 0.4 / k);
        Color[] pal = {sc.accent(), plate(env, sc.accent2(), sc.sub()), plate(env, sc.fg()), plate(env, sc.ink())};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double rise = E.outCubic(clamp((lt - i * 0.06) / 0.9)), x = W / 2 + (i - (k - 1) / 2.0) * r0 * 2.1 + Math.sin(env.ltb * 1.2 + i) * r0 * 0.15, y = LyricKit.lerp(H * 1.1, H * 0.4 + LyricKit.rs(seed, i, 1) * H * 0.06, rise);
            Color fill = pal[i % pal.length];
            env.line(new double[][]{{x, y + r0 * 1.2}, {x + Math.sin(env.ltb * 2 + i) * r0 * 0.2, y + r0 * 2.6}, {x, y + r0 * 3.6}}, sc.sub(), hair() * 1.5, 0.6 * a);
            env.circle(x, y, r0, fill, null, 0, a);
            env.poly(new double[][]{{x, y + r0 * 1.05}, {x - r0 * 0.12, y + r0 * 1.25}, {x + r0 * 0.12, y + r0 * 1.25}}, fill, a);
            env.circle(x - r0 * 0.35, y - r0 * 0.4, r0 * 0.18, Color.WHITE, null, 0, 0.35 * a);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), r0 * 1.3, x, y).color(onCol(sc, fill)).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ magnets
    private static BB magnets(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size();
        long seed = (long) p.d("seed");
        double s = Math.min(U * 0.17, W * 0.8 / Math.max(4, k * 0.85));
        Color[] pal = {plate(env, sc.accent()), plate(env, sc.accent2(), sc.sub()), plate(env, sc.fg()), plate(env, sc.ink())};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outBack(clamp((lt - i * 0.06) / 0.35)), x = W / 2 + (i - (k - 1) / 2.0) * s * 0.92, y = H / 2 + LyricKit.rs(seed, i, 1) * s * 0.55;
            Color fill = pal[i % pal.length];
            final int idx = i; final double fx = x, fy = y;
            final BB[] r = new BB[1];
            rotated(env, x, y, LyricKit.rs(seed, i, 2) * 14, () -> {
                env.circle(fx + s * 0.04, fy + s * 0.06, s * 0.5 * clamp(e), new Color(0, 0, 0, 70), null, 0, a);
                env.circle(fx, fy, s * 0.5 * clamp(e), fill, null, 0, a);
                env.circle(fx - s * 0.18, fy - s * 0.2, s * 0.1, Color.WHITE, null, 0, 0.3 * a);
                r[0] = env.mainDraw(it(us.get(idx), p.s("font"), s * 0.66, fx, fy).color(onCol(sc, fill)).mi(idx));
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ tiles
    private static BB tiles(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 9);
        double s = Math.min(U * 0.2, W * 0.84 / k);
        Color wood = LyricKit.mix(lightest(sc), plate(env, sc.accent2(), sc.sub()), 0.3);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * s * 1.05, drop = E.outBounce(clamp((lt - i * 0.07) / 0.5)), y = LyricKit.lerp(-s, H / 2, drop);
            paper(env, x - s / 2, y - s / 2, s, s, s * 0.1, wood, a, false);
            env.rrect(x - s * 0.42, y - s * 0.42, s * 0.84, s * 0.84, s * 0.06, null, a, LyricKit.mix(wood, Color.BLACK, 0.3), hair() * 1.5);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), s * 0.62, x, y - s * 0.03).color(onCol(sc, wood)).mi(i)));
            label(env, String.valueOf(1 + (i * 3) % 7), x + s * 0.34, y + s * 0.34, s * 0.14, a, "right", onCol(sc, wood));
        }
        return bb;
    }

    // ------------------------------------------------------------------ bulbs
    private static BB bulbs(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env), lt = env.lt;
        double w = W * 0.78, h = H * 0.46, x0 = (W - w) / 2, y0 = (H - h) / 2;
        env.rrect(x0, y0, w, h, U * 0.02, LyricKit.mix(sc.bg(), Color.BLACK, 0.5), a, LyricKit.mix(sc.fg(), Color.BLACK, 0.4), U * 0.008);
        int nx = 14, ny = 6;
        double r = U * 0.012;
        for (int i = 0; i < 2 * (nx + ny); i++) {
            double x, y;
            if (i < nx) { x = x0 + w * i / nx; y = y0; }
            else if (i < nx + ny) { x = x0 + w; y = y0 + h * (i - nx) / ny; }
            else if (i < 2 * nx + ny) { x = x0 + w - w * (i - nx - ny) / nx; y = y0 + h; }
            else { x = x0; y = y0 + h - h * (i - 2 * nx - ny) / ny; }
            boolean on = ((i + (int) (env.ltb * 6)) % 3) != 0;
            env.circle(x, y, r * 2.2, on ? new Color(255, 230, 150, 60) : null, null, 0, a);
            env.circle(x, y, r, on ? new Color(0xFFE38A) : LyricKit.mix(sc.bg(), Color.WHITE, 0.2), null, 0, a);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, w * 0.82, h * 0.6, new Color(0xFFE9A8), 1.1, 0.05, 2, U * 0.2);
    }

    // ------------------------------------------------------------------ ledScroll
    private static BB ledScroll(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env), lt = env.lt;
        double w = W * 0.88, h = H * 0.3, x0 = (W - w) / 2, y0 = (H - h) / 2;
        env.rect(x0, y0, w, h, new Color(0x080808), a);
        String t = flat(env.cut.text);
        double size = h * 0.56, tw = meas(t, p.s("font"), size, 0.1, 1.2).w();
        double off = Math.max(0, lt - 0.15) * p.d("speed");
        double x = Math.max(x0 + w * 0.05, x0 + w - off), x2 = tw > w * 0.9 ? x0 + w - off : x0 + w / 2 - tw / 2;
        Color led = plate(env, sc.accent(), sc.fg());
        var old = env.g.getClip();
        env.g.clip(new Rectangle2D.Double(x0, y0, w, h));
        BB bb = env.draw(it(t, p.s("font"), size, tw > w * 0.9 ? x2 : (lt < 0.5 ? x0 + w - off * 2 : x0 + w / 2 - tw / 2), y0 + h / 2).align(tw > w * 0.9 ? "left" : "left").track(0.1).color(led).alpha(a).noGhost());
        env.g.setClip(old);
        int cols = (int) (w / (U * 0.012)), rows = (int) (h / (U * 0.012));
        for (int gy = 0; gy < rows; gy++) env.line(new double[][]{{x0, y0 + gy * h / rows}, {x0 + w, y0 + gy * h / rows}}, Color.BLACK, hair() * 2, 0.55 * a);
        for (int gx = 0; gx < cols; gx++) env.line(new double[][]{{x0 + gx * w / cols, y0}, {x0 + gx * w / cols, y0 + h}}, Color.BLACK, hair() * 2, 0.55 * a);
        return bb != null ? bb : box(x0, y0, x0 + w, y0 + h);
    }

    // ------------------------------------------------------------------ billboard
    private static BB billboard(Env env) {
        var sc = env.sc; Params p = env.P(); double a = out(env), lt = env.lt;
        Color board = plate(env, sc.accent(), sc.ink(), sc.fg());
        double w = W * 0.7, h = H * 0.4, x0 = (W - w) / 2, y0 = H * 0.16, rise = E.outCubic(clamp(lt / 0.5));
        env.rect(x0 + w * 0.2, y0 + h, U * 0.022, H * 0.4, sc.sub(), a);
        env.rect(x0 + w * 0.8 - U * 0.022, y0 + h, U * 0.022, H * 0.4, sc.sub(), a);
        paper(env, x0, y0 + (1 - rise) * -H * 0.3, w, h, 4, board, a, false);
        env.rect(x0 + w * 0.03, y0 + h * 0.06 + (1 - rise) * -H * 0.3, w * 0.94, h * 0.04, onCol(sc, board), a * 0.7);
        for (int i = 0; i < 5; i++) env.circle(x0 + w * (0.1 + i * 0.2), y0 - U * 0.03, U * 0.014, new Color(0xFFE38A), null, 0, a * (i % 2 == 0 ? 1 : 0.5));
        return mainFit(env, env.cut.text, p.s("font"), W / 2, y0 + h / 2 + (1 - rise) * -H * 0.3, w * 0.86, h * 0.66, onCol(sc, board), 1.05, 0.04, 2, U * 0.22);
    }

    // ------------------------------------------------------------------ crowdBubbles
    private static BB crowdBubbles(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = out(env);
        List<String> parts = splitK(env.cut.text, Math.min(5, Math.max(3, gcount(env.cut.text) / 2)));
        int k = parts.size();
        long seed = (long) p.d("seed");
        Card C = card(sc);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outBack(clamp((lt - i * 0.1) / 0.35)), bw = W * (0.2 + 0.1 * LyricKit.r(seed, i, 1)), bh = bw * 0.5;
            double x = W * (0.2 + 0.6 * ((i * 0.618 + 0.1) % 1)), y = H * (0.2 + 0.6 * ((i * 0.37 + 0.2) % 1));
            double s = clamp(e);
            Color fill = i % 2 == 0 ? C.fill : plate(env, sc.accent());
            env.rrect(x - bw / 2 * s, y - bh / 2 * s, bw * s, bh * s, bh * 0.4, fill, a, null, 0);
            env.poly(new double[][]{{x - bw * 0.15 * s, y + bh * 0.4 * s}, {x - bw * 0.3 * s, y + bh * 0.8 * s}, {x + bw * 0.05 * s, y + bh * 0.45 * s}}, fill, a);
            if (s > 0.5) bb = BB.union(bb, env.mainDraw(it(parts.get(i), p.s("font"), Math.min(bh * 0.5, LyricKit.fitSize(parts.get(i), p.s("font"), bw * 0.82, bh * 0.7)), x, y).color(onCol(sc, fill)).mi(i * 2)));
        }
        return bb;
    }
}
