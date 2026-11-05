package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Params;

import java.awt.Color;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static io.github.raincandyfx.rainium.LyricCompose.*;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricPaper.*;
import static io.github.raincandyfx.rainium.LyricTypo.hair;

/** Second half of the playful layouts (see {@link LyricPlay}). */
final class LyricPlay2 {
    private LyricPlay2() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    private static Color plate(Env env, Color... pref) { return plateCol(env.sc, env.sc.bg(), pref); }

    static BB crossword(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 9);
        double s = Math.min(U * 0.14, W * 0.84 / k), y0 = H / 2 - s / 2, x0 = W / 2 - k * s / 2;
        Color cell = lightest(sc), ink = onCol(sc, cell);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            if (i % 3 == 1) for (int j = -2; j <= 2; j++) if (j != 0) {
                double e = clamp((lt - 0.1 - 0.04 * i) / 0.3);
                env.rect(x0 + i * s, y0 + j * s, s * 0.96, s * 0.96, cell, a * 0.5 * e);
                env.rrect(x0 + i * s, y0 + j * s, s * 0.96, s * 0.96, 0, null, a * e, sc.sub(), hair() * 1.5);
            }
        }
        for (int i = 0; i < k; i++) {
            double e = E.outBack(clamp((lt - 0.05 * i) / 0.3));
            env.rect(x0 + i * s, y0, s * 0.96, s * 0.96, cell, a * clamp(e));
            env.rrect(x0 + i * s, y0, s * 0.96, s * 0.96, 0, null, a, sc.fg(), hair() * 2);
            label(env, String.valueOf(i + 1), x0 + i * s + s * 0.08, y0 + s * 0.2, s * 0.2, a, "left", ink);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), s * 0.66, x0 + i * s + s * 0.48, y0 + s * 0.52).color(ink).alpha(clamp(e)).mi(i)));
        }
        return bb;
    }

    static BB wordSearch(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 8), rows = 5, cols = Math.max(k + 2, 7);
        long seed = (long) p.d("seed");
        double s = Math.min(U * 0.13, W * 0.86 / cols), x0 = W / 2 - cols * s / 2, y0 = H / 2 - rows * s / 2;
        int row = 1 + (int) (LyricKit.r(seed, 1, 1) * (rows - 2)), col0 = 1;
        BB bb = null;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            boolean hit = r == row && c >= col0 && c < col0 + k;
            String ch = hit ? us.get(c - col0) : us.get((int) (LyricKit.r(seed, r * 31 + c, 2) * us.size()));
            double x = x0 + (c + 0.5) * s, y = y0 + (r + 0.5) * s;
            if (!hit) env.draw(it(ch, p.s("font"), s * 0.55, x, y).color(sc.sub()).alpha(0.55 * a).noGhost());
        }
        double e = E.outCubic(clamp((lt - 0.2) / 0.4));
        env.rrect(x0 + col0 * s + s * 0.04, y0 + row * s + s * 0.08, k * s * e - s * 0.08, s * 0.84, s * 0.42, plate(env, sc.accent()), 0.9 * a, null, 0);
        for (int c = 0; c < k; c++) bb = BB.union(bb, env.mainDraw(it(us.get(c), p.s("font"), s * 0.62, x0 + (col0 + c + 0.5) * s, y0 + (row + 0.5) * s).color(onCol(sc, plate(env, sc.accent()))).mi(c)));
        return bb;
    }

    static BB puzzle(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 8);
        double s = Math.min(U * 0.2, W * 0.86 / k);
        Color[] pal = {plate(env, sc.accent()), plate(env, sc.accent2(), sc.sub()), plate(env, sc.fg()), plate(env, sc.ink())};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outCubic(clamp((lt - 0.07 * i) / 0.5)), x = W / 2 + (i - (k - 1) / 2.0) * s * 0.98, y = H / 2 + (1 - e) * (i % 2 == 0 ? -1 : 1) * H * 0.4;
            Color fill = pal[i % pal.length];
            env.rect(x - s / 2, y - s / 2, s, s, fill, a);
            env.circle(x + s / 2, y, s * 0.13, fill, null, 0, a);
            env.circle(x, y - s / 2, s * 0.13, fill, null, 0, a);
            env.circle(x - s / 2, y, s * 0.13, sc.bg(), null, 0, a);
            env.circle(x, y + s / 2, s * 0.13, sc.bg(), null, 0, a);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), s * 0.62, x, y).color(onCol(sc, fill)).mi(i)));
        }
        return bb;
    }

    static BB shadowPlay(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        Color sheet = LyricKit.mix(lightest(sc), plate(env, sc.accent2(), sc.sub()), 0.25);
        env.rect(0, 0, W, H, sheet, 0.9 * a);
        double ang = p.d("dir"), len = U * 0.035 * (0.6 + 0.4 * Math.sin(env.ltb * 1.3));
        Color dark = new Color(0x15141A);
        BB bb = null;
        for (int i = 10; i >= 1; i--) {
            double f = i / 10.0;
            env.draw(it(flat(env.cut.text), p.s("font"), U * 0.22, W / 2 + Math.cos(ang) * len * f * 2, H / 2 + Math.sin(ang) * len * f * 2).color(LyricKit.mix(dark, sheet, 0.35 + f * 0.1)).alpha(a * 0.55).noGhost().noHalo());
        }
        bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.84, H * 0.5, dark, 1.1, 0.03, 2, U * 0.24);
        return bb;
    }

    static BB kaleido(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        int n = (int) p.d("n");
        String t = flat(env.cut.text);
        double size = Math.min(U * 0.12, LyricKit.fitSize(t, p.s("font"), W * 0.3, U * 0.14));
        double R = U * 0.22 + Math.sin(env.ltb) * U * 0.02, spin = env.ltb * 12;
        for (int i = 0; i < n; i++) {
            double th = i * 360.0 / n + spin, rad = Math.toRadians(th);
            env.draw(it(t, p.s("font"), size, W / 2 + Math.cos(rad) * R, H / 2 + Math.sin(rad) * R).rot(th + 90).color(i % 2 == 0 ? sc.accent() : sc.accent2()).alpha(0.42 * a).noGhost().noHalo());
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.4, H * 0.3, sc.fg(), 1.1, 0.03, 2, U * 0.14);
    }

    static BB dominoes(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 10);
        double s = Math.min(U * 0.2, W * 0.84 / k), y = H * 0.62;
        Color fill = lightest(sc);
        env.line(new double[][]{{W * 0.06, y + s * 0.02}, {W * 0.94, y + s * 0.02}}, sc.sub(), hair() * 3, 0.6 * a);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double fall = E.inCubic(clamp((lt - 0.1 - i * 0.07) / 0.3)), x = W / 2 + (i - (k - 1) / 2.0) * s * 1.05, ang = fall * 78;
            final int idx = i; final double fx = x; final BB[] r = new BB[1];
            rotated(env, x + s * 0.3, y, ang, () -> {
                double hh = s * 1.3, ww = s * 0.6;
                paper(env, fx - ww / 2 + s * 0.0, y - hh, ww, hh, 4, fill, a, false);
                env.rect(fx - ww / 2, y - hh * 0.5 - 1, ww, 2, sc.sub(), a * 0.6);
                r[0] = env.mainDraw(it(us.get(idx), p.s("font"), ww * 0.8, fx, y - hh * 0.75).color(onCol(sc, fill)).mi(idx));
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    static BB burst(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        int n = (int) p.d("rays");
        Color ray = plate(env, sc.accent(), sc.fg());
        double R = Math.hypot(W, H), inner = U * 0.2, grow = E.outExpo(clamp(lt / 0.3));
        for (int i = 0; i < n; i++) {
            double th = (i + 0.5 * Math.sin(i * 7.7)) * 2 * Math.PI / n, w = Math.PI / n * (0.35 + 0.35 * Math.abs(Math.sin(i * 3.1)));
            double r0 = inner * (1.0 + 0.4 * Math.sin(i * 1.7)) / grow;
            env.poly(new double[][]{{W / 2 + Math.cos(th - w) * R, H / 2 + Math.sin(th - w) * R}, {W / 2 + Math.cos(th + w) * R, H / 2 + Math.sin(th + w) * R}, {W / 2 + Math.cos(th) * r0, H / 2 + Math.sin(th) * r0}}, ray, 0.5 * a);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.7, H * 0.4, sc.fg(), 1.05, 0.03, 2, U * 0.26);
    }

    static BB fisheye(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size();
        double base = Math.min(U * 0.12, W * 0.9 / Math.max(5, k)), focus = (Math.sin(env.ltb * 0.9) * 0.5 + 0.5) * (k - 1);
        BB bb = null;
        double x = W / 2 - k * base * 0.75;
        for (int i = 0; i < k; i++) {
            double d = Math.abs(i - focus), sz = base * (0.5 + 1.8 * Math.exp(-d * d * 0.5));
            x += sz * 0.55;
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), sz, x, H / 2).color(LyricKit.mix(sc.sub(), sc.fg(), clamp(1 - d / 3))).mi(i)));
            x += sz * 0.55;
        }
        return bb;
    }

    static BB wall(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        Color brick = LyricKit.mix(plate(env, sc.accent2(), sc.sub()), Color.BLACK, 0.3), mortar = LyricKit.mix(brick, Color.WHITE, 0.4);
        double bh = U * 0.07, bw = bh * 2.2, e = clamp(lt / 0.4);
        env.rect(0, 0, W, H, mortar, 0.9 * a * e);
        int rows = (int) (H / bh) + 1;
        for (int r = 0; r < rows; r++) {
            double off = r % 2 == 0 ? 0 : -bw / 2;
            for (double x = off; x < W; x += bw) {
                double sh = 0.85 + 0.15 * Math.sin(r * 12.9 + x * 0.05);
                env.rect(x + 2, r * bh + 2, bw - 4, bh - 4, LyricKit.mix(brick, Color.BLACK, 1 - sh), a * e);
            }
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.8, H * 0.46, onCol(sc, brick), 1.1, 0.05, 2, U * 0.26);
    }

    static BB origami(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        Color sheet = plate(env, sc.accent(), sc.fg());
        double w = W * 0.66, h = H * 0.5, x0 = (W - w) / 2, y0 = (H - h) / 2, f = E.inOutCubic(clamp(lt / 0.5));
        env.rect(x0, y0, w, h, sheet, a);
        env.poly(new double[][]{{x0, y0}, {x0 + w * 0.5 * f, y0}, {x0, y0 + h * 0.5 * f}}, LyricKit.mix(sheet, Color.WHITE, 0.35), a);
        env.poly(new double[][]{{x0 + w, y0 + h}, {x0 + w - w * 0.5 * f, y0 + h}, {x0 + w, y0 + h - h * 0.5 * f}}, LyricKit.mix(sheet, Color.BLACK, 0.3), a);
        env.line(new double[][]{{x0 + w * 0.5 * f, y0}, {x0, y0 + h * 0.5 * f}}, Color.BLACK, hair() * 1.5, 0.3 * a);
        env.line(new double[][]{{x0 + w, y0 + h - h * 0.5 * f}, {x0 + w - w * 0.5 * f, y0 + h}}, Color.BLACK, hair() * 1.5, 0.3 * a);
        env.line(new double[][]{{x0, y0 + h / 2}, {x0 + w, y0 + h / 2}}, Color.BLACK, hair() * 1.2, 0.15 * a);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, w * 0.8, h * 0.62, onCol(sc, sheet), 1.1, 0.04, 2, U * 0.24);
    }

    static BB zipper(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        Color cloth = plate(env, sc.accent(), sc.ink(), sc.fg());
        double h = H * 0.34, y0 = (H - h) / 2, open = E.inOutCubic(clamp(lt / 0.6)), gap = h * 0.5 * open;
        env.rect(0, y0, W, h / 2 - gap / 2, cloth, a);
        env.rect(0, y0 + h / 2 + gap / 2, W, h / 2 - gap / 2, cloth, a);
        int n = 40;
        for (int i = 0; i < n; i++) {
            double x = W * (i + 0.5) / n, tooth = U * 0.012;
            env.rect(x - tooth / 2, y0 + h / 2 - gap / 2 - tooth * (i % 2 == 0 ? 1.4 : 0.6), tooth, tooth * 1.4, LyricKit.mix(sc.fg(), Color.BLACK, 0.2), a);
            env.rect(x - tooth / 2, y0 + h / 2 + gap / 2 + (i % 2 == 0 ? 0 : tooth * 0.8) - 0, tooth, tooth * 1.4, LyricKit.mix(sc.fg(), Color.BLACK, 0.2), a);
        }
        double pull = W * (0.08 + 0.84 * (1 - open));
        env.rrect(pull - U * 0.02, y0 + h / 2 - U * 0.03, U * 0.04, U * 0.06, U * 0.01, sc.fg(), a, null, 0);
        var old = env.g.getClip();
        BB bb = null;
        if (gap > 2) { env.g.clip(new Rectangle2D.Double(0, y0 + h / 2 - gap / 2, W, gap)); bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.8, Math.max(gap, h * 0.5), sc.fg(), 1.05, 0.04, 1, U * 0.24); env.g.setClip(old); }
        return bb != null ? bb : new BB(W * 0.1, y0, W * 0.9, y0 + h);
    }

    static BB sliceStack(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        int k = (int) p.d("k");
        double bh = U * 0.34 / k;
        String t = flat(env.cut.text);
        double size = Math.min(U * 0.34, LyricKit.fitSize(t, p.s("font"), W * 0.8, U * 0.34));
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outExpo(clamp((lt - 0.05 * i) / 0.5)), dx = (i % 2 == 0 ? -1 : 1) * W * 0.5 * (1 - e) + Math.sin(env.ltb * 1.5 + i) * U * 0.01, y = H / 2 - U * 0.17 + (i + 0.5) * bh;
            var old = env.g.getClip();
            env.g.clip(new Rectangle2D.Double(0, y - bh / 2, W, bh));
            bb = BB.union(bb, env.mainDraw(it(t, p.s("font"), size, W / 2 + dx, H / 2).color(i % 2 == 0 ? sc.fg() : plate(env, sc.accent())).mi(i)));
            env.g.setClip(old);
        }
        return bb;
    }

    static BB glitchGrid(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), t = env.ltb;
        String s = flat(env.cut.text);
        double size = Math.min(U * 0.3, LyricKit.fitSize(s, p.s("font"), W * 0.8, U * 0.3));
        int cols = 6, rows = 4;
        double cw = W / cols, ch = H / rows;
        BB bb = null;
        long fr = (long) (t * 8);
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            double rnd = LyricKit.r(fr, r * 9 + c, 3), dx = rnd > 0.78 ? (LyricKit.r(fr, r * 9 + c, 4) - 0.5) * cw * 0.6 : 0;
            var old = env.g.getClip();
            env.g.clip(new Rectangle2D.Double(c * cw, r * ch, cw, ch));
            bb = BB.union(bb, env.mainDraw(it(s, p.s("font"), size, W / 2 + dx, H / 2).color(rnd > 0.9 ? sc.accent() : sc.fg()).mi(r * cols + c)));
            env.g.setClip(old);
        }
        return bb;
    }

    static BB mosaicTiles(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        long seed = (long) p.d("seed");
        double s = U * 0.07;
        Color c1 = plate(env, sc.accent()), c2 = plate(env, sc.accent2(), sc.sub());
        int cols = (int) (W * 0.7 / s), rows = (int) (H * 0.4 / s);
        double x0 = W / 2 - cols * s / 2, y0 = H / 2 - rows * s / 2;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            double e = clamp((lt - (r + c) * 0.015) / 0.25), rnd = LyricKit.r(seed, r * 50 + c, 1);
            env.rect(x0 + c * s + 1, y0 + r * s + 1, (s - 2) * e, (s - 2) * e, LyricKit.mix(c1, c2, rnd), a * 0.85);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, cols * s * 0.9, rows * s * 0.8, sc.fg(), 1.1, 0.04, 2, U * 0.24);
    }

    static BB maskReveal(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt, e = E.outCubic(clamp(lt / 0.6));
        String shape = p.s("shape");
        var old = env.g.getClip();
        java.awt.Shape clip;
        double R = Math.hypot(W, H) * 0.5 * e;
        if (shape.equals("circle")) clip = new Ellipse2D.Double(W / 2 - R, H / 2 - R, R * 2, R * 2);
        else if (shape.equals("diamond")) { var path = new java.awt.geom.Path2D.Double(); path.moveTo(W / 2, H / 2 - R); path.lineTo(W / 2 + R, H / 2); path.lineTo(W / 2, H / 2 + R); path.lineTo(W / 2 - R, H / 2); path.closePath(); clip = path; }
        else { var area = new java.awt.geom.Area(); for (int i = 0; i < 8; i++) area.add(new java.awt.geom.Area(new Rectangle2D.Double(0, H * i / 8, W, H / 8 * e))); clip = area; }
        env.g.clip(clip);
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.84, H * 0.5, sc.fg(), 1.1, 0.04, 2, U * 0.28);
        env.g.setClip(old);
        return bb;
    }

    static BB contour(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        for (int i = 1; i <= 9; i++) {
            double rx = W * 0.07 * i * (0.9 + 0.1 * Math.sin(env.ltb + i)), ry = H * 0.065 * i * (0.9 + 0.1 * Math.cos(env.ltb * 0.8 + i)), f = clamp(lt * 3 - i * 0.1);
            env.g.setStroke(new java.awt.BasicStroke((float) hair()));
            env.circle(W / 2, H / 2, 0, null, null, 0, 0);
            env.line(ellipse(W / 2, H / 2, rx, ry, 64), sc.sub(), hair() * (i % 5 == 0 ? 2.5 : 1.2), (i % 5 == 0 ? 0.7 : 0.4) * a * f);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.5, H * 0.3, sc.fg(), 1.1, 0.04, 2, U * 0.2);
    }

    private static double[][] ellipse(double cx, double cy, double rx, double ry, int n) {
        double[][] pts = new double[n + 1][];
        for (int i = 0; i <= n; i++) pts[i] = new double[]{cx + Math.cos(i * 2 * Math.PI / n) * rx, cy + Math.sin(i * 2 * Math.PI / n) * ry};
        return pts;
    }

    static BB halftoneBig(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        double s = U * 0.05;
        int cols = (int) (W / s), rows = (int) (H / s);
        Color dot = plate(env, sc.accent(), sc.sub());
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            double x = (c + 0.5) * s, y = (r + 0.5) * s, d = Math.hypot((x - W / 2) / W, (y - H / 2) / H), rad = s * 0.5 * clamp(d * 1.6) * clamp(lt * 3);
            if (rad > 0.5) env.circle(x, y, rad, dot, null, 0, 0.55 * a);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.8, H * 0.46, sc.fg(), 1.1, 0.04, 2, U * 0.28);
    }

    static BB stencil(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        Color board = plate(env, sc.accent(), sc.ink(), sc.fg());
        double w = W * 0.8, h = H * 0.42, x0 = (W - w) / 2, y0 = (H - h) / 2;
        env.rect(x0, y0, w, h, board, a);
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, w * 0.86, h * 0.7, sc.bg(), 1.05, 0.06, 2, U * 0.26);
        for (int i = 1; i < 4; i++) env.rect(x0, y0 + h * i / 4 - 2, w, 4, board, a);
        for (int i = 1; i < 8; i++) env.rect(x0 + w * i / 8 - 2, y0, 4, h, board, a * 0.0);
        return bb;
    }
}
