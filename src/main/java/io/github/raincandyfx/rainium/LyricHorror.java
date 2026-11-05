package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.BB;
import io.github.raincandyfx.rainium.LyricKit.E;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Params;

import java.awt.Color;
import java.awt.RadialGradientPaint;
import java.awt.GradientPaint;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.util.List;

import static io.github.raincandyfx.rainium.LyricCompose.*;
import static io.github.raincandyfx.rainium.LyricKit.clamp;
import static io.github.raincandyfx.rainium.LyricLayouts.def;
import static io.github.raincandyfx.rainium.LyricLayouts.fontPick;
import static io.github.raincandyfx.rainium.LyricPaper.*;
import static io.github.raincandyfx.rainium.LyricTypo.hair;

/** Uneasy, found-footage layouts: flashlight, door gap, wall writing, CCTV, spirit board, missing poster and more (condensed re-creations). */
final class LyricHorror {
    private LyricHorror() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);
    private static final Color BLOOD = new Color(0x9A1118), BONE = new Color(0xE6DFCF);

    static void init() {}

    private static void r(String key, String name, int lo, int hi, LyricLayouts.Render rd, String... roles) {
        def(key, name, n -> n >= lo && n <= hi, (rng, cut, st) -> {
            Params p = new Params();
            p.with("font", fontPick(rng, st, roles)).with("seed", (double) rng.integer(1, 9999)).with("dir", rng.pick(1.0, -1.0));
            return p;
        }, rd);
    }

    static {
        r("hrFlashlight", "懐中電灯", 0, 16, LyricHorror::flashlight, "serif", "display");
        r("hrDoorGap", "扉の隙間", 0, 14, LyricHorror::doorGap, "serif", "display");
        r("hrWallScrawl", "壁の書きなぐり", 0, 14, LyricHorror::wallScrawl, "display", "serif");
        r("hrCctv", "監視カメラ", 0, 16, LyricHorror::cctv, "mono", "display");
        r("hrOuija", "こっくりさん", 1, 10, LyricHorror::ouija, "serif", "display");
        r("hrMissing", "尋ね人", 0, 16, LyricHorror::missing, "serif", "display");
        r("hrWrongOne", "一字だけ違う", 2, 14, LyricHorror::wrongOne, "serif", "display");
        r("hrRisingDark", "闇が昇る", 0, 14, LyricHorror::risingDark, "serif", "display");
        r("hrRedacted", "黒塗り", 2, 16, LyricHorror::redacted, "mono", "serif");
        r("hrStaticTv", "砂嵐", 0, 14, LyricHorror::staticTv, "mono", "display");
        r("hrSpiritPhoto", "心霊写真", 0, 14, LyricHorror::spiritPhoto, "serif", "display");
        r("hrWrongShadow", "影が違う", 0, 12, LyricHorror::wrongShadow, "serif", "display");
    }

    private static double jit(Env e, int salt) { return LyricKit.r((long) (e.ltb * 11), salt, 5); }

    private static BB flashlight(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        double cx = W / 2 + Math.sin(env.ltb * 1.1) * W * 0.14, cy = H / 2 + Math.cos(env.ltb * 0.8) * H * 0.06, R = U * (0.3 + 0.03 * jit(env, 1));
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.76, H * 0.46, BONE, 1.15, 0.06, 2, U * 0.22);
        var old = env.g.getPaint();
        env.g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) R, new float[]{0f, 0.55f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 120), new Color(0, 0, 0, 245)}));
        env.g.fill(new Rectangle2D.Double(0, 0, W, H));
        env.g.setPaint(old);
        return bb;
    }

    private static BB doorGap(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), gap = W * 0.025 + W * 0.05 * E.inOutCubic(clamp((env.lt - 0.15) / 0.5)) * (1 - clamp((env.pOut - 0.4) * 2));
        env.rect(0, 0, W, H, new Color(0x050407), 0.93 * a);
        var old = env.g.getClip();
        env.g.clip(new Rectangle2D.Double(W / 2 - gap / 2, 0, gap, H));
        env.rect(W / 2 - gap / 2, 0, gap, H, new Color(0x3A2C22), a);
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.76, H * 0.46, BONE, 1.15, 0.06, 2, U * 0.26);
        env.g.setClip(old);
        env.rect(W / 2 - gap / 2 - U * 0.006, 0, U * 0.006, H, new Color(0x1A1410), a);
        env.rect(W / 2 + gap / 2, 0, U * 0.006, H, new Color(0x1A1410), a);
        return bb;
    }

    private static BB wallScrawl(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        String t = flat(env.cut.text);
        long seed = (long) p.d("seed");
        int n = (int) (24 * clamp(lt * 2));
        for (int i = 0; i < n; i++) {
            double x = W * (0.08 + 0.84 * LyricKit.r(seed, i, 1)), y = H * (0.1 + 0.8 * LyricKit.r(seed, i, 2)), sz = U * (0.04 + 0.05 * LyricKit.r(seed, i, 3));
            env.draw(it(t, p.s("font"), Math.min(sz, LyricKit.fitSize(t, p.s("font"), W * 0.4, sz)), x, y).rot((LyricKit.r(seed, i, 4) - 0.5) * 40).color(i % 4 == 0 ? BLOOD : BONE).alpha(a * (0.15 + 0.2 * LyricKit.r(seed, i, 5))).noGhost().noHalo());
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.78, H * 0.46, BONE, 1.15, 0.06, 2, U * 0.24);
    }

    private static BB cctv(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        env.rect(0, 0, W, H, new Color(0x0A100C), 0.55 * a);
        for (double y = 0; y < H; y += U * 0.012) env.rect(0, y, W, U * 0.004, Color.BLACK, 0.25 * a);
        double m = U * 0.05;
        for (int sx = 0; sx < 2; sx++) for (int sy = 0; sy < 2; sy++) {
            double x = sx == 0 ? m : W - m, y = sy == 0 ? m : H - m, d = U * 0.05;
            env.line(new double[][]{{x, y + (sy == 0 ? d : -d)}, {x, y}, {x + (sx == 0 ? d : -d), y}}, BONE, hair() * 3, 0.8 * a);
        }
        if ((int) (env.ltb * 2) % 2 == 0) env.circle(m * 1.6, m * 1.6, U * 0.012, BLOOD, null, 0, a);
        label(env, "REC  CAM " + lineNo(env), m * 2.2, m * 1.6, U * 0.022, a, "left", BONE);
        label(env, fmtTime(env.cut.start + env.ltb), W - m, H - m * 1.2, U * 0.022, a, "right", BONE);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H * 0.52, W * 0.72, H * 0.4, new Color(0xCFE8D2), 1.2, 0.08, 2, U * 0.14);
    }

    private static BB ouija(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = charUnits(env.cut.text);
        int k = Math.min(us.size(), 10);
        Color wood = new Color(0x7B5A3A);
        double w = W * 0.78, h = H * 0.56, x0 = (W - w) / 2, y0 = (H - h) / 2;
        env.rrect(x0, y0, w, h, U * 0.02, wood, a, new Color(0x3A2616), U * 0.01);
        label(env, "YES", x0 + w * 0.12, y0 + h * 0.18, U * 0.034, a, "center", new Color(0x2A1A0C));
        label(env, "NO", x0 + w * 0.88, y0 + h * 0.18, U * 0.034, a, "center", new Color(0x2A1A0C));
        label(env, "GOOD BYE", W / 2, y0 + h * 0.9, U * 0.03, a, "center", new Color(0x2A1A0C));
        double cur = Math.min(k - 1, lt * (k + 2) * 0.9), size = Math.min(U * 0.1, w * 0.8 / Math.max(5, k));
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * size * 1.1, y = y0 + h * 0.5 + Math.sin((i / (double) Math.max(1, k - 1) - 0.5) * 3) * -h * 0.1;
            double lit = clamp(1 - Math.abs(cur - i) * 0.8);
            bb = BB.union(bb, env.draw(it(us.get(i), p.s("font"), size, x, y).color(LyricKit.mix(new Color(0x2A1A0C), BONE, lit)).alpha(a).noGhost()));
        }
        double px = W / 2 + (cur - (k - 1) / 2.0) * size * 1.1, py = y0 + h * 0.5 + Math.sin((cur / Math.max(1, k - 1) - 0.5) * 3) * -h * 0.1 + h * 0.12;
        env.g.setColor(new Color(20, 12, 6, (int) (190 * a)));
        env.g.fill(new Ellipse2D.Double(px - size * 0.55, py - size * 0.4, size * 1.1, size * 0.8));
        env.g.setColor(new Color(BONE.getRed(), BONE.getGreen(), BONE.getBlue(), (int) (200 * a)));
        env.g.setStroke(new java.awt.BasicStroke((float) hair() * 2));
        env.g.draw(new Ellipse2D.Double(px - size * 0.2, py - size * 0.22, size * 0.4, size * 0.34));
        return bb;
    }

    private static BB missing(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = E.outCubic(clamp(env.lt / 0.4));
        double w = W * 0.5, h = H * 0.84, x0 = (W - w) / 2, y0 = (H - h) / 2 + (1 - e) * H * 0.2;
        env.rect(x0, y0, w, h, new Color(0xE9E2CF), a);
        env.rect(x0, y0, w, U * 0.01, new Color(0, 0, 0, 40), a);
        BB bb = null;
        final double fx = x0, fy = y0;
        label(env, "MISSING", W / 2, y0 + h * 0.1, U * 0.09, a, "center", new Color(0x151515));
        env.rect(x0 + w * 0.2, y0 + h * 0.18, w * 0.6, h * 0.34, new Color(0x6C675B), a);
        env.circle(W / 2, y0 + h * 0.3, h * 0.07, new Color(0x2B2A26), null, 0, a);
        env.rrect(W / 2 - h * 0.12, y0 + h * 0.37, h * 0.24, h * 0.15, h * 0.06, new Color(0x2B2A26), a, null, 0);
        bb = mainFit(env, env.cut.text, p.s("font"), W / 2, y0 + h * 0.68, w * 0.82, h * 0.2, new Color(0x151515), 1.2, 0.05, 3, U * 0.07);
        for (int i = 0; i < 6; i++) env.rect(x0 + w * (0.1 + i * 0.14), y0 + h * 0.9, w * 0.1, h * 0.08, new Color(0xE9E2CF), a);
        label(env, meta(env), W / 2, y0 + h * 0.84, U * 0.016, a, "center", new Color(0x45423A));
        return bb;
    }

    private static BB wrongOne(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size(), bad = Math.floorMod((int) p.d("seed"), k);
        double size = Math.min(U * 0.17, W * 0.84 / k);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            boolean b = i == bad && env.lt > 0.25;
            double x = W / 2 + (i - (k - 1) / 2.0) * size * 1.02;
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size * (b ? 1.35 : 1), x, H / 2 + (b ? size * 0.12 : 0)).sx(b ? -1 : 1).rot(b ? 8 : 0).color(b ? BLOOD : BONE).mi(i)));
        }
        return bb;
    }

    private static BB risingDark(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), rise = clamp(env.lt * 1.1);
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.78, H * 0.46, BONE, 1.15, 0.06, 2, U * 0.26);
        double top = LyricKit.lerp(H, H * 0.1, E.inOutSine(rise)), soft = H * 0.3;
        var old = env.g.getPaint();
        env.g.setPaint(new GradientPaint(0, (float) top, new Color(0, 0, 0, 0), 0, (float) (top + soft), new Color(2, 1, 3, (int) (245 * a))));
        env.g.fill(new Rectangle2D.Double(0, top, W, H - top));
        env.g.setPaint(old);
        return bb;
    }

    private static BB redacted(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> parts = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 2)));
        int k = parts.size(), keep = Math.floorMod((int) p.d("seed"), k);
        double w = W * 0.74, h = H * 0.62, x0 = (W - w) / 2, y0 = (H - h) / 2, lh = Math.min(U * 0.13, h * 0.8 / k);
        env.rect(x0, y0, w, h, new Color(0xE6DFC8), a);
        label(env, "CLASSIFIED  " + meta(env), x0 + w * 0.04, y0 + h * 0.07, U * 0.017, a, "left", new Color(0x4A4636));
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double y = H / 2 + (i - (k - 1) / 2.0) * lh * 1.15;
            String s = flat(parts.get(i));
            double sz = Math.min(lh, LyricKit.fitSize(s, p.s("font"), w * 0.86, lh));
            bb = BB.union(bb, env.draw(it(s, p.s("font"), sz, W / 2, y).color(new Color(0x1A1812)).alpha(a).noGhost()));
            if (i != keep) { double e = E.outExpo(clamp((env.lt - 0.2 - 0.12 * i) / 0.25)); env.rect(x0 + w * 0.05, y - sz * 0.55, w * 0.9 * e, sz * 1.1, new Color(0x0A0A0A), a); }
        }
        return bb;
    }

    private static BB staticTv(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), burst = Math.max(0, 1 - env.lt * 3);
        double w = W * 0.72, h = H * 0.62, x0 = (W - w) / 2, y0 = (H - h) / 2, cell = U * 0.014;
        env.rrect(x0 - cell, y0 - cell, w + 2 * cell, h + 2 * cell, U * 0.03, new Color(0x16140F), a, null, 0);
        env.rect(x0, y0, w, h, new Color(0x181818), a);
        long fr = (long) (env.ltb * 20);
        int cols = (int) (w / cell), rows = (int) (h / cell);
        double dens = 0.18 + 0.8 * burst;
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            double v = LyricKit.r(fr, r * 131 + c, 9);
            if (v < dens) env.rect(x0 + c * cell, y0 + r * cell, cell, cell, new Color(255, 255, 255, (int) (255 * (0.2 + 0.8 * v / dens))), 0.5 * a);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, w * 0.8, h * 0.5, Color.WHITE, 1.15, 0.08, 2, U * 0.2);
    }

    private static BB spiritPhoto(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = E.outCubic(clamp(env.lt / 0.4));
        double w = U * 0.66, h = U * 0.8, x0 = (W - w) / 2, y0 = (H - h) / 2 + (1 - e) * H * 0.1;
        Color border = new Color(0xE0D8C4), tone = new Color(0x6F6044);
        final BB[] r = new BB[1];
        rotated(env, W / 2, H / 2, -3, () -> {
            env.rect(x0, y0, w, h, border, a);
            env.rect(x0 + w * 0.06, y0 + w * 0.06, w * 0.88, h * 0.78, tone, a);
            double gx = W / 2 + Math.sin(env.ltb * 0.7) * w * 0.12, gy = y0 + h * 0.4;
            env.g.setPaint(new RadialGradientPaint((float) gx, (float) gy, (float) (w * 0.34), new float[]{0f, 1f}, new Color[]{new Color(255, 255, 255, (int) (70 * a)), new Color(255, 255, 255, 0)}));
            env.g.fill(new Rectangle2D.Double(x0, y0, w, h));
            env.g.setPaint(null);
            r[0] = mainFit(env, env.cut.text, p.s("font"), W / 2, y0 + h * 0.4, w * 0.74, h * 0.4, new Color(0xF4EEDC), 1.15, 0.06, 3, U * 0.1);
            label(env, meta(env), W / 2, y0 + h * 0.92, U * 0.016, a, "center", new Color(0x3A3426));
        });
        return r[0];
    }

    private static BB wrongShadow(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), t = env.ltb;
        String s = flat(env.cut.text);
        double size = Math.min(U * 0.26, LyricKit.fitSize(s, p.s("font"), W * 0.74, U * 0.26));
        double lag = clamp(env.lt * 1.2 - 0.2), dx = Math.sin(t * 0.6) * size * 0.4 * lag, dy = size * (0.35 + 0.25 * lag);
        env.draw(it(s, p.s("font"), size, W / 2 + dx, H / 2 + dy).sy(-1).color(Color.BLACK).alpha(0.75 * a * lag).noGhost().noHalo());
        return env.mainDraw(it(s, p.s("font"), size, W / 2, H / 2).color(BONE).mi(0));
    }
}
