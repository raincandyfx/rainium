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
import static io.github.raincandyfx.rainium.LyricLayouts.def;
import static io.github.raincandyfx.rainium.LyricLayouts.fontPick;
import static io.github.raincandyfx.rainium.LyricPaper.*;
import static io.github.raincandyfx.rainium.LyricTypo.hair;

/**
 * Kinetic and typographic layouts: spirals, drop caps, justified blocks, frames, bubbles, panels, film, tickets, bouncing and orbiting
 * glyphs, rain, tunnels, word clouds, slot reels, neon, tape and more (condensed re-creations, see Credits).
 */
final class LyricMotion {
    private LyricMotion() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    private static void r(String key, String name, int lo, int hi, LyricLayouts.Render rd, String... roles) {
        def(key, name, n -> n >= lo && n <= hi, (rng, cut, st) -> {
            Params p = new Params();
            p.with("font", fontPick(rng, st, roles)).with("seed", (double) rng.integer(1, 9999)).with("dir", rng.pick(1.0, -1.0));
            return p;
        }, rd);
    }

    private static Color plate(Env env, Color... pref) { return plateCol(env.sc, env.sc.bg(), pref); }

    private static List<String> units(Env env, int max) {
        List<String> us = charUnits(env.cut.text);
        return us.size() <= max ? us : us.subList(0, max);
    }

    static {
        r("spiral", "螺旋", 2, 16, LyricMotion::spiral, "display", "serif");
        r("dropCap", "大きな頭文字", 3, 24, LyricMotion::dropCap, "serif", "display");
        r("justified", "版面", 2, 22, LyricMotion::justified, "display", "serif");
        r("frameBox", "額縁", 0, 20, LyricMotion::frameBox, "serif", "display");
        r("bubble", "吹き出し", 0, 20, LyricMotion::bubble, "body", "display");
        r("perspective", "奥行き", 0, 14, LyricMotion::perspective, "display");
        r("panels", "コマ割り", 2, 18, LyricMotion::panels, "display");
        r("filmstrip", "フィルム", 1, 18, LyricMotion::filmstrip, "display", "mono");
        r("ticket", "チケット", 0, 18, LyricMotion::ticket, "display", "serif");
        r("bounceLine", "跳ねる", 2, 16, LyricMotion::bounceLine, "display");
        r("bubbles", "泡", 1, 12, LyricMotion::bubbles, "display", "body");
        r("circleWords", "同心円", 1, 14, LyricMotion::circleWords, "display", "serif");
        r("columnsBig", "大小縦組", 1, 10, LyricMotion::columnsBig, "serif", "display");
        r("credits", "エンドロール", 1, 16, LyricMotion::credits, "body", "serif");
        r("crossBands", "交差帯", 1, 14, LyricMotion::crossBands, "display");
        r("curtain", "幕", 1, 16, LyricMotion::curtain, "display", "serif");
        r("depthStack", "奥行き重ね", 1, 12, LyricMotion::depthStack, "display");
        r("dotMatrix", "ドット表示", 1, 12, LyricMotion::dotMatrix, "mono", "display");
        r("elastic", "ゴム", 2, 12, LyricMotion::elastic, "display");
        r("equalizer", "イコライザー", 1, 12, LyricMotion::equalizer, "display", "mono");
        r("flipBoard", "パタパタ", 1, 12, LyricMotion::flipBoard, "mono", "display");
        r("halfVertical", "縦横混植", 3, 14, LyricMotion::halfVertical, "serif", "display");
        r("hanging", "吊り下げ", 2, 12, LyricMotion::hanging, "display", "serif");
        r("kanjiFocus", "一字強調", 2, 16, LyricMotion::kanjiFocus, "serif", "display");
        r("keycaps", "キーキャップ", 1, 10, LyricMotion::keycaps, "mono", "display");
        r("neon", "ネオン", 1, 14, LyricMotion::neon, "display");
        r("orbit", "周回", 1, 14, LyricMotion::orbit, "display", "serif");
        r("rain", "文字の雨", 1, 16, LyricMotion::rain, "display", "mono");
        r("slotMachine", "スロット", 1, 10, LyricMotion::slotMachine, "display");
        r("splitHalves", "上下割り", 1, 14, LyricMotion::splitHalves, "display", "serif");
        r("stickerBomb", "ステッカー", 1, 12, LyricMotion::stickerBomb, "display");
        r("tape", "テープ", 1, 16, LyricMotion::tape, "display", "mono");
        r("tunnel", "トンネル", 1, 14, LyricMotion::tunnel, "display");
        r("tyLineFocus", "行中強調", 1, 16, LyricMotion::lineFocus, "display", "serif");
        r("typeSpecimen", "書体見本", 1, 8, LyricMotion::specimen, "serif", "display");
        r("wordCloud", "ワードクラウド", 1, 14, LyricMotion::wordCloud, "display", "body");
        r("zoomRepeat", "連続拡大", 1, 12, LyricMotion::zoomRepeat, "display");
    }

    private static double inE(Env e, double d, double delay) { return E.outExpo(clamp((e.lt - delay) / d)); }

    // ------------------------------------------------------------------ spiral
    private static BB spiral(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.8, 0);
        List<String> us = units(env, 24);
        int k = us.size();
        double size = Math.min(U * 0.14, W * 0.5 / Math.sqrt(k + 2)), spin = env.ltb * 0.5 * p.d("dir");
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double t = (i + 1) / (double) k, th = t * 5.2 + spin, rad = (0.1 + t * 0.9) * U * 0.34 * e;
            double x = W / 2 + Math.cos(th) * rad * 1.5, y = H / 2 + Math.sin(th) * rad;
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size * (0.5 + t * 0.8), x, y).rot(Math.toDegrees(th) + 90).color(LyricKit.mix(sc.sub(), sc.fg(), t)).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ dropCap
    private static BB dropCap(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.5, 0);
        List<String> us = charUnits(env.cut.text);
        String first = us.get(0), rest = String.join("", us.subList(1, us.size()));
        double big = U * 0.5, x0 = W * 0.12;
        env.rect(x0 - U * 0.02, H / 2 - big * 0.48, U * 0.01, big * 0.96, plate(env, sc.accent()), a * e);
        BB bb = env.mainDraw(it(first, p.s("font"), big, x0 + big * 0.5, H / 2).color(plate(env, sc.accent(), sc.fg())).sx(1).mi(0));
        Block b = fitBlock(rest, p.s("font"), W * 0.78 - big, big * 0.9, 1.5, 0.05, 6, false);
        bb = BB.union(bb, env.mainDraw(it(b.text(), p.s("font"), Math.min(b.size(), U * 0.1), x0 + big + (W * 0.78 - big) / 2, H / 2).lead(1.5).track(0.05).color(sc.fg()).mi(3)));
        return bb;
    }

    // ------------------------------------------------------------------ justified
    private static BB justified(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> ls = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 3)));
        int k = ls.size();
        double w = W * 0.8, lh = Math.min(U * 0.22, H * 0.66 / k);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            String t = flat(ls.get(i));
            double size = lh * 0.82, tw = meas(t, p.s("font"), size, 0, 1.2).w(), sx = clamp(w / Math.max(1, tw), 0.35, 2.4);
            double y = H / 2 + (i - (k - 1) / 2.0) * lh, e = inE(env, 0.5, 0.06 * i);
            bb = BB.union(bb, env.mainDraw(it(t, p.s("font"), size, W / 2, y).sx(sx * (0.6 + 0.4 * e)).color(i % 2 == 0 ? sc.fg() : plate(env, sc.accent())).mi(i * 3)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ frameBox
    private static BB frameBox(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.5, 0);
        double w = W * 0.7, h = H * 0.5, x0 = (W - w) / 2, y0 = (H - h) / 2;
        Color gold = plate(env, sc.accent(), sc.fg());
        for (int i = 0; i < 3; i++) {
            double m = i * U * 0.018;
            env.rrect(x0 + m, y0 + m, (w - 2 * m) * e, (h - 2 * m) * e, 0, null, a, i == 1 ? sc.sub() : gold, U * (i == 1 ? 0.004 : 0.01));
        }
        double c = U * 0.05;
        for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2)
            env.circle(W / 2 + sx * (w / 2 - c * 0.4), H / 2 + sy * (h / 2 - c * 0.4), c * 0.22, gold, null, 0, a * e);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, w * 0.76, h * 0.62, sc.fg(), 1.15, 0.05, 3, U * 0.2);
    }

    // ------------------------------------------------------------------ bubble
    private static BB bubble(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = E.outBack(clamp(env.lt / 0.35));
        Card C = card(sc);
        double w = W * 0.62 * clamp(e), h = H * 0.42 * clamp(e);
        env.rrect(W / 2 - w / 2, H * 0.44 - h / 2, w, h, h * 0.4, C.fill, a, C.line, hair() * 3);
        env.poly(new double[][]{{W * 0.38, H * 0.44 + h * 0.4}, {W * 0.3, H * 0.44 + h * 0.8}, {W * 0.46, H * 0.44 + h * 0.46}}, C.fill, a);
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H * 0.44, w * 0.8, h * 0.66, onCol(sc, C.fill), 1.2, 0.04, 3, U * 0.18);
    }

    // ------------------------------------------------------------------ perspective
    private static BB perspective(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> ls = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 2)));
        int k = ls.size();
        double vy = H * 0.28;
        for (int i = 0; i <= 8; i++) env.line(new double[][]{{W / 2, vy}, {W * (i / 8.0), H}}, sc.sub(), hair() * 1.5, 0.35 * a);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double t = (i + 1.0) / (k + 0.6), depth = clamp(t - env.ltb * 0.06 % 1.0 * 0, 0, 1), y = LyricKit.lerp(vy, H * 0.88, t * t), sz = U * (0.04 + 0.22 * t * t);
            String s = flat(ls.get(i));
            bb = BB.union(bb, env.mainDraw(it(s, p.s("font"), sz, W / 2, y).color(LyricKit.mix(sc.sub(), sc.fg(), t)).alpha(clamp(0.3 + t)).sy(0.8).mi(i * 3)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ panels
    private static BB panels(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> parts = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 2)));
        int k = parts.size();
        double gx = W * 0.07, gy = H * 0.12, tw = W - 2 * gx, th = H - 2 * gy, gap = U * 0.02;
        BB bb = null;
        Color[] pal = {plate(env, sc.accent()), lightest(sc), plate(env, sc.ink(), sc.fg()), plate(env, sc.accent2(), sc.sub())};
        for (int i = 0; i < k; i++) {
            int cols = k <= 2 ? k : 2, rows = (k + cols - 1) / cols, c = i % cols, r = i / cols;
            double pw = (tw - gap * (cols - 1)) / cols, ph = (th - gap * (rows - 1)) / rows, e = E.outExpo(clamp((lt - i * 0.1) / 0.3));
            double x = gx + c * (pw + gap), y = gy + r * (ph + gap);
            Color f = pal[i % pal.length];
            env.rect(x, y, pw * e, ph, f, a);
            if (e > 0.6) bb = BB.union(bb, env.mainDraw(it(parts.get(i), p.s("font"), Math.min(ph * 0.5, LyricKit.fitSize(parts.get(i), p.s("font"), pw * 0.8, ph * 0.6)), x + pw / 2, y + ph / 2).color(onCol(sc, f)).mi(i * 2)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ filmstrip
    private static BB filmstrip(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), t = env.ltb;
        List<String> us = units(env, 12);
        int k = us.size();
        double fh = H * 0.34, fw = fh * 1.15, y0 = H / 2 - fh / 2, off = (t * W * 0.04) % fw;
        env.rect(0, y0 - fh * 0.14, W, fh * 1.28, new Color(0x16120E), 0.95 * a);
        for (double x = -fw + off % (fh * 0.2); x < W; x += fh * 0.2) {
            env.rrect(x, y0 - fh * 0.1, fh * 0.1, fh * 0.06, 2, sc.bg(), a, null, 0);
            env.rrect(x, y0 + fh * 1.04, fh * 0.1, fh * 0.06, 2, sc.bg(), a, null, 0);
        }
        BB bb = null;
        double total = k * fw;
        for (int i = 0; i < k; i++) {
            double x = W / 2 - total / 2 + i * fw + fw / 2;
            Color f = LyricKit.mix(lightest(sc), plate(env, sc.accent2(), sc.sub()), i % 2 * 0.25);
            env.rect(x - fw * 0.46, y0, fw * 0.92, fh, f, a);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), fw * 0.62, x, y0 + fh / 2).color(onCol(sc, f)).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ ticket
    private static BB ticket(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.5, 0);
        Color paperC = plate(env, sc.accent(), sc.fg());
        double w = W * 0.78, h = H * 0.38, x0 = (W - w) / 2 + (1 - e) * W * 0.3, y0 = (H - h) / 2, stub = w * 0.24;
        env.rect(x0, y0, w, h, paperC, a);
        env.circle(x0, y0 + h / 2, h * 0.12, sc.bg(), null, 0, a);
        env.circle(x0 + w, y0 + h / 2, h * 0.12, sc.bg(), null, 0, a);
        env.circle(x0 + w - stub, y0, h * 0.07, sc.bg(), null, 0, a);
        env.circle(x0 + w - stub, y0 + h, h * 0.07, sc.bg(), null, 0, a);
        for (int i = 0; i < 12; i++) env.rect(x0 + w - stub - 1, y0 + h * 0.1 + i * h * 0.075, 2, h * 0.04, onCol(sc, paperC), a * 0.6);
        label(env, "ADMIT ONE", x0 + w - stub / 2, y0 + h * 0.2, U * 0.024, a, "center", onCol(sc, paperC));
        label(env, meta(env), x0 + w - stub / 2, y0 + h * 0.8, U * 0.018, a, "center", onCol(sc, paperC));
        return mainFit(env, env.cut.text, p.s("font"), x0 + (w - stub) / 2, y0 + h / 2, (w - stub) * 0.82, h * 0.7, onCol(sc, paperC), 1.1, 0.04, 2, U * 0.2);
    }

    // ------------------------------------------------------------------ bounceLine
    private static BB bounceLine(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> us = units(env, 16);
        int k = us.size();
        double size = Math.min(U * 0.2, W * 0.84 / k);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double ph = env.ltb * 5 - i * 0.45, hop = Math.abs(Math.sin(ph)) * size * 0.8 * Math.exp(-Math.max(0, env.lt) * 0.35), sq = 1 - 0.25 * Math.max(0, 1 - Math.abs(Math.sin(ph)) * 5);
            double x = W / 2 + (i - (k - 1) / 2.0) * size * 1.0;
            env.circle(x, H * 0.66, size * 0.3, new Color(0, 0, 0, 60), null, 0, a * (1 - hop / size));
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, x, H * 0.6 - hop).sy(sq).color(i % 3 == 0 ? plate(env, sc.accent()) : sc.fg()).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ bubbles
    private static BB bubbles(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> parts = splitK(env.cut.text, Math.min(6, Math.max(1, gcount(env.cut.text))));
        int k = parts.size();
        long seed = (long) p.d("seed");
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double r0 = U * (0.11 + 0.05 * LyricKit.r(seed, i, 1)), rise = clamp((lt - i * 0.06) / 1.2), x = W * (0.18 + 0.64 * (i + 0.5) / k) + Math.sin(env.ltb * 1.4 + i * 2) * r0 * 0.4;
            double y = LyricKit.lerp(H + r0, H * (0.38 + 0.18 * ((i * 0.37) % 1)), E.outCubic(rise));
            env.circle(x, y, r0, new Color(255, 255, 255, 28), null, 0, a);
            env.g.setColor(new Color(255, 255, 255, (int) (110 * a)));
            env.g.setStroke(new java.awt.BasicStroke((float) hair() * 2));
            env.g.draw(new Ellipse2D.Double(x - r0, y - r0, r0 * 2, r0 * 2));
            env.circle(x - r0 * 0.4, y - r0 * 0.45, r0 * 0.14, Color.WHITE, null, 0, 0.45 * a);
            bb = BB.union(bb, env.mainDraw(it(parts.get(i), p.s("font"), Math.min(r0 * 1.1, LyricKit.fitSize(parts.get(i), p.s("font"), r0 * 1.5, r0 * 1.2)), x, y).color(sc.fg()).mi(i * 2)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ circleWords
    private static BB circleWords(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.7, 0);
        List<String> us = units(env, 28);
        int k = us.size(), rings = k > 12 ? 2 : 1;
        BB bb = null;
        int idx = 0;
        for (int rg = 0; rg < rings; rg++) {
            int cnt = rg == 0 ? (rings == 1 ? k : Math.min(k, 10)) : k - Math.min(k, 10);
            double R = U * (rg == 0 ? 0.3 : 0.19) * e, size = Math.min(U * 0.13, 2 * Math.PI * R / Math.max(6, cnt) * 0.85), spin = env.ltb * 0.4 * (rg == 0 ? 1 : -1) * p.d("dir");
            env.g.setColor(new Color(sc.sub().getRed(), sc.sub().getGreen(), sc.sub().getBlue(), (int) (90 * a)));
            env.g.setStroke(new java.awt.BasicStroke((float) hair()));
            env.g.draw(new Ellipse2D.Double(W / 2 - R, H / 2 - R, R * 2, R * 2));
            for (int i = 0; i < cnt && idx < k; i++, idx++) {
                double th = spin + i * 2 * Math.PI / Math.max(6, cnt) - Math.PI / 2;
                bb = BB.union(bb, env.mainDraw(it(us.get(idx), p.s("font"), size, W / 2 + Math.cos(th) * R, H / 2 + Math.sin(th) * R).rot(Math.toDegrees(th) + 90).color(rg == 0 ? sc.fg() : plate(env, sc.accent())).mi(idx)));
            }
        }
        return bb;
    }

    // ------------------------------------------------------------------ columnsBig
    private static BB columnsBig(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> parts = splitK(env.cut.text, Math.min(4, Math.max(2, gcount(env.cut.text) / 2)));
        int k = parts.size();
        double x = W * 0.84;
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double size = Math.min(U * (i == 0 ? 0.26 : 0.13), H * 0.78 / Math.max(1, gcount(parts.get(i)))), cw = size * 1.25;
            x -= cw / 2;
            double e = inE(env, 0.5, 0.1 * i);
            bb = BB.union(bb, env.mainDraw(it(parts.get(i), p.s("font"), size, x, H / 2 + (1 - e) * H * 0.2).vertical(true).color(i == 0 ? plate(env, sc.accent(), sc.fg()) : sc.fg()).mi(i * 3)));
            x -= cw / 2 + U * 0.03;
        }
        return bb;
    }

    // ------------------------------------------------------------------ credits
    private static BB credits(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> parts = splitK(env.cut.text, Math.min(6, Math.max(2, gcount(env.cut.text) / 2)));
        int k = parts.size();
        double size = U * 0.07, gap = size * 2.6, scroll = LyricKit.lerp(H * 0.2, -H * 0.2, clamp(env.lt)), y = H * 0.85 - scroll * 0 + (0.5 - env.lt) * gap * 0.6;
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double yy = H / 2 + (i - (k - 1) / 2.0) * gap + (0.5 - clamp(env.lt)) * gap * 0.6;
            double fade = clamp(1 - Math.abs(yy - H / 2) / (H * 0.5));
            label(env, "ROLE " + two(i + 1), W / 2, yy - size * 0.95, size * 0.32, a * fade, "center", sc.sub());
            bb = BB.union(bb, env.draw(it(parts.get(i), p.s("font"), size, W / 2, yy).color(sc.fg()).alpha(fade * a).noGhost()));
        }
        return bb != null ? bb : box(W * 0.3, H * 0.3, W * 0.7, H * 0.7);
    }

    // ------------------------------------------------------------------ crossBands
    private static BB crossBands(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.5, 0);
        Color b1 = plate(env, sc.accent()), b2 = plate(env, sc.ink(), sc.fg());
        double bw = U * 0.2;
        for (int i = 0; i < 2; i++) {
            Color c = i == 0 ? b1 : b2;
            double ang = (i == 0 ? -14 : 14);
            final int idx = i;
            rotated(env, W / 2, H / 2, ang, () -> env.rect(-W * 0.1 + W * 1.2 * (1 - e) * (idx == 0 ? -1 : 1), H / 2 - bw / 2, W * 1.2, bw, c, a * 0.92));
        }
        List<String> parts = splitK(env.cut.text, 2);
        BB bb = null;
        for (int i = 0; i < parts.size(); i++) {
            final int idx = i; final BB[] r = new BB[1];
            Color c = i == 0 ? b1 : b2;
            rotated(env, W / 2, H / 2, i == 0 ? -14 : 14, () -> r[0] = env.mainDraw(it(parts.get(idx), p.s("font"), Math.min(bw * 0.7, LyricKit.fitSize(parts.get(idx), p.s("font"), W * 0.7, bw * 0.7)), W / 2, H / 2).color(onCol(sc, c)).mi(idx * 3)));
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ curtain
    private static BB curtain(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), open = E.inOutCubic(clamp((env.lt - 0.1) / 0.5)) * (1 - E.inCubic(clamp((env.pOut - 0.5) * 2)));
        Color cloth = plate(env, sc.accent(), sc.ink(), sc.fg());
        BB bb = mainFit(env, env.cut.text, p.s("font"), W / 2, H / 2, W * 0.76, H * 0.46, sc.fg(), 1.1, 0.05, 2, U * 0.26);
        double half = W / 2 * (1 - open);
        for (int s = -1; s <= 1; s += 2) {
            double x = s < 0 ? 0 : W - half;
            env.rect(x, 0, half, H, cloth, a);
            for (int i = 0; i < 8; i++) env.rect(x + half * (i + 0.5) / 8, 0, Math.max(1, half * 0.05), H, LyricKit.mix(cloth, Color.BLACK, 0.3), a * 0.6);
        }
        return bb;
    }

    // ------------------------------------------------------------------ depthStack
    private static BB depthStack(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.6, 0);
        String t = flat(env.cut.text);
        double size = Math.min(U * 0.3, LyricKit.fitSize(t, p.s("font"), W * 0.7, U * 0.3));
        for (int i = 7; i >= 1; i--) {
            double f = i / 7.0, off = f * U * 0.05 * e;
            env.draw(it(t, p.s("font"), size * (1 - f * 0.14), W / 2 + off * 0.8, H / 2 - off).color(LyricKit.mix(sc.bg(), plate(env, sc.accent()), 1 - f * 0.7)).alpha(a * (1 - f * 0.5)).noGhost().noHalo());
        }
        return env.mainDraw(it(t, p.s("font"), size, W / 2, H / 2).color(sc.fg()).mi(0));
    }

    // ------------------------------------------------------------------ dotMatrix
    private static BB dotMatrix(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        double s = U * 0.028;
        int cols = (int) (W * 0.84 / s), rows = (int) (H * 0.4 / s);
        double x0 = W / 2 - cols * s / 2, y0 = H / 2 - rows * s / 2;
        Color on = plate(env, sc.accent(), sc.fg());
        java.awt.image.BufferedImage mask = new java.awt.image.BufferedImage(cols, rows, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D mg = mask.createGraphics();
        mg.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        mg.setColor(Color.WHITE);
        String t = flat(env.cut.text);
        java.awt.Font f = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, Math.max(4, (int) (rows * 0.8)));
        mg.setFont(f);
        java.awt.FontMetrics fm = mg.getFontMetrics();
        double scaleX = Math.min(1.0, cols * 0.94 / Math.max(1, fm.stringWidth(t)));
        mg.translate(cols / 2.0 - fm.stringWidth(t) * scaleX / 2, rows * 0.5 + fm.getAscent() * 0.34);
        mg.scale(scaleX, 1);
        mg.drawString(t, 0, 0);
        mg.dispose();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            boolean lit = (mask.getRGB(c, r) >>> 24) > 100 && c < cols * clamp(lt * 3);
            env.circle(x0 + (c + 0.5) * s, y0 + (r + 0.5) * s, s * 0.36, lit ? on : LyricKit.mix(sc.bg(), Color.WHITE, 0.12), null, 0, a);
        }
        return box(x0, y0, x0 + cols * s, y0 + rows * s);
    }

    // ------------------------------------------------------------------ elastic
    private static BB elastic(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> us = units(env, 12);
        int k = us.size();
        double size = Math.min(U * 0.22, W * 0.8 / k), t = env.lt;
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double ph = Math.max(0, t - 0.05 * i), wob = Math.exp(-ph * 3.5) * Math.sin(ph * 22), sx = 1 + wob * 0.6, sy = 1 - wob * 0.45;
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, W / 2 + (i - (k - 1) / 2.0) * size * 1.02, H / 2).sx(sx).sy(sy).color(i % 2 == 0 ? sc.fg() : plate(env, sc.accent())).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ equalizer
    private static BB equalizer(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        int bars = 22;
        double bw = W * 0.8 / bars, base = H * 0.72;
        Color c = plate(env, sc.accent(), sc.fg());
        for (int i = 0; i < bars; i++) {
            double h = U * (0.06 + 0.22 * (0.5 + 0.5 * Math.sin(env.ltb * 6 + i * 0.7) * Math.cos(env.ltb * 3.3 + i * 1.3)));
            env.rect(W * 0.1 + i * bw + bw * 0.12, base - h, bw * 0.76, h, LyricKit.mix(c, sc.bg(), 0.3 + 0.4 * (i % 2)), a * 0.9);
        }
        return mainFit(env, env.cut.text, p.s("font"), W / 2, H * 0.38, W * 0.8, H * 0.34, sc.fg(), 1.1, 0.05, 2, U * 0.22);
    }

    // ------------------------------------------------------------------ flipBoard
    private static BB flipBoard(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = units(env, 12);
        int k = us.size();
        double cw = Math.min(U * 0.17, W * 0.88 / k), ch = cw * 1.35;
        Color tile = LyricKit.mix(sc.bg(), Color.BLACK, 0.55);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * cw * 1.06, f = clamp((lt - 0.05 * i - 0.1) / 0.45), flap = Math.abs(Math.cos(f * Math.PI)) * (f < 1 ? 1 : 0);
            env.rrect(x - cw / 2, H / 2 - ch / 2, cw, ch, cw * 0.06, tile, a, null, 0);
            String shown = f > 0.5 ? us.get(i) : String.valueOf((char) ('A' + (i * 7 + (int) (lt * 20)) % 26));
            bb = BB.union(bb, env.draw(it(shown, p.s("font"), cw * 0.78, x, H / 2).sy(f < 1 ? Math.max(0.1, 1 - flap * 0.8) : 1).color(onCol(sc, tile)).alpha(a).noGhost()));
            env.rect(x - cw / 2, H / 2 - 1, cw, 2, Color.BLACK, a * 0.8);
        }
        return bb;
    }

    // ------------------------------------------------------------------ halfVertical
    private static BB halfVertical(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int cut = Math.max(1, us.size() / 2);
        String v = String.join("", us.subList(0, cut)), h = String.join("", us.subList(cut, us.size()));
        double vs = Math.min(U * 0.14, H * 0.76 / Math.max(1, v.length()));
        BB bb = env.mainDraw(it(v, p.s("font"), vs, W * 0.76, H / 2).vertical(true).color(plate(env, sc.accent(), sc.fg())).mi(0));
        bb = BB.union(bb, mainFit(env, h, p.s("font"), W * 0.38, H / 2, W * 0.58, H * 0.5, sc.fg(), 1.2, 0.05, 3, U * 0.18));
        return bb;
    }

    // ------------------------------------------------------------------ hanging
    private static BB hanging(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = units(env, 12);
        int k = us.size();
        long seed = (long) p.d("seed");
        double size = Math.min(U * 0.19, W * 0.84 / k);
        env.rect(W * 0.06, H * 0.08, W * 0.88, U * 0.01, sc.fg(), a);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x0 = W / 2 + (i - (k - 1) / 2.0) * size * 1.05, len = H * (0.22 + 0.2 * LyricKit.r(seed, i, 1)) * inE(env, 0.5, 0.05 * i), ang = Math.sin(env.ltb * 2.2 + i * 0.8) * 0.08 * Math.exp(-lt * 0.2);
            double bx = x0 + Math.sin(ang) * len, by = H * 0.08 + Math.cos(ang) * len;
            env.line(new double[][]{{x0, H * 0.08}, {bx, by}}, sc.sub(), hair() * 1.5, 0.7 * a);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, bx, by + size * 0.6).rot(Math.toDegrees(ang)).color(sc.fg()).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ kanjiFocus
    private static BB kanjiFocus(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size(), key = Math.min(k - 1, Math.abs(Math.floorMod((long) p.d("seed"), k)));
        double small = Math.min(U * 0.1, W * 0.8 / (k + 3)), big = U * 0.44;
        double total = (k - 1) * small + big, x = W / 2 - total / 2;
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double sz = i == key ? big : small;
            x += sz / 2;
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), sz, x, H / 2 + (i == key ? 0 : small * 0.2)).color(i == key ? plate(env, sc.accent(), sc.fg()) : sc.sub()).mi(i == key ? 0 : i + 2)));
            x += sz / 2 + small * 0.05;
        }
        return bb;
    }

    // ------------------------------------------------------------------ keycaps
    private static BB keycaps(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = units(env, 10);
        int k = us.size();
        double s = Math.min(U * 0.18, W * 0.86 / k);
        Color cap = LyricKit.mix(lightest(sc), plate(env, sc.accent2(), sc.sub()), 0.15);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * s * 1.08, press = Math.max(0, Math.sin(clamp((lt - 0.08 * i - 0.1) / 0.3) * Math.PI)) * s * 0.06;
            env.rrect(x - s / 2, H / 2 - s / 2 + s * 0.08, s, s, s * 0.14, LyricKit.mix(cap, Color.BLACK, 0.45), a, null, 0);
            env.rrect(x - s / 2 + s * 0.04, H / 2 - s / 2 + press, s * 0.92, s * 0.9, s * 0.12, cap, a, LyricKit.mix(cap, Color.BLACK, 0.25), hair() * 1.5);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), s * 0.56, x, H / 2 - s * 0.04 + press).color(onCol(sc, cap)).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ neon
    private static BB neon(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        String t = flat(env.cut.text);
        double size = Math.min(U * 0.26, LyricKit.fitSize(t, p.s("font"), W * 0.8, U * 0.26));
        Color glow = plate(env, sc.accent(), sc.fg());
        double flick = (env.ltb * 14 % 1 > 0.94 && env.lt < 0.9) ? 0.45 : 1;
        for (int i = 4; i >= 1; i--) env.draw(it(t, p.s("font"), size, W / 2, H / 2).outline(U * 0.006 * i * 2, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 40)).alpha(a * flick).noGhost().noHalo());
        env.draw(it(t, p.s("font"), size, W / 2, H / 2).outline(U * 0.006, glow).alpha(a * flick).noGhost().noHalo());
        return env.draw(it(t, p.s("font"), size, W / 2, H / 2).color(LyricKit.mix(glow, Color.WHITE, 0.75)).alpha(0.8 * a * flick).noGhost().noHalo());
    }

    // ------------------------------------------------------------------ orbit
    private static BB orbit(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.7, 0);
        List<String> us = units(env, 14);
        int k = us.size();
        double size = Math.min(U * 0.14, W * 0.5 / Math.sqrt(k + 1)), rx = W * 0.32 * e, ry = H * 0.22 * e, spin = env.ltb * 0.7 * p.d("dir");
        env.g.setColor(new Color(sc.sub().getRed(), sc.sub().getGreen(), sc.sub().getBlue(), (int) (80 * a)));
        env.g.setStroke(new java.awt.BasicStroke((float) hair()));
        env.g.draw(new Ellipse2D.Double(W / 2 - rx, H / 2 - ry, rx * 2, ry * 2));
        env.circle(W / 2, H / 2, size * 0.22, plate(env, sc.accent()), null, 0, a);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double th = spin + i * 2 * Math.PI / k, depth = 0.65 + 0.35 * Math.sin(th);
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size * depth, W / 2 + Math.cos(th) * rx, H / 2 + Math.sin(th) * ry).color(LyricKit.mix(sc.sub(), sc.fg(), depth)).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ rain
    private static BB rain(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = units(env, 16);
        int k = us.size();
        long seed = (long) p.d("seed");
        double size = Math.min(U * 0.16, W * 0.84 / k);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * size * 1.0, fall = E.outBounce(clamp((lt - 0.2 * LyricKit.r(seed, i, 1) - 0.03 * i) / 0.6)), y = LyricKit.lerp(-size, H / 2, fall);
            for (int t = 1; t <= 4; t++) if (fall < 1) env.draw(it(us.get(i), p.s("font"), size, x, y - t * size * 0.7).color(sc.accent()).alpha(a * 0.18 / t).noGhost().noHalo());
            bb = BB.union(bb, env.mainDraw(it(us.get(i), p.s("font"), size, x, y).color(sc.fg()).mi(i)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ slotMachine
    private static BB slotMachine(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = units(env, 10);
        int k = us.size();
        double cw = Math.min(U * 0.2, W * 0.86 / k), ch = cw * 1.4;
        env.rrect(W / 2 - k * cw * 0.54, H / 2 - ch * 0.64, k * cw * 1.08, ch * 1.28, cw * 0.1, LyricKit.mix(sc.bg(), Color.BLACK, 0.5), a, plate(env, sc.accent(), sc.fg()), U * 0.008);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double x = W / 2 + (i - (k - 1) / 2.0) * cw * 1.02, land = clamp((lt - 0.08 * i - 0.12) / 0.5), off = (1 - E.outCubic(land)) * ch * 3, x0 = x - cw * 0.46;
            var old = env.g.getClip();
            env.g.clip(new Rectangle2D.Double(x0, H / 2 - ch / 2, cw * 0.92, ch));
            env.rect(x0, H / 2 - ch / 2, cw * 0.92, ch, lightest(sc), a);
            for (int j = -2; j <= 2; j++) {
                String s = j == 0 ? us.get(i) : us.get((i + j * 3 + 100 * k) % k);
                double y = H / 2 + j * ch * 0.95 - off % (ch * 0.95 * 5) * (land < 1 ? 1 : 0);
                if (land >= 1 && j != 0) continue;
                bb = BB.union(bb, env.draw(it(s, p.s("font"), cw * 0.66, x, y).color(onCol(sc, lightest(sc))).alpha(a).noGhost()));
            }
            env.g.setClip(old);
        }
        return bb != null ? bb : box(W * 0.1, H * 0.3, W * 0.9, H * 0.7);
    }

    // ------------------------------------------------------------------ splitHalves
    private static BB splitHalves(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), e = inE(env, 0.5, 0);
        String t = flat(env.cut.text);
        double size = Math.min(U * 0.4, LyricKit.fitSize(t, p.s("font"), W * 0.82, U * 0.4)), off = (1 - e) * W * 0.4, gap = U * 0.012;
        BB bb = null;
        for (int h = 0; h < 2; h++) {
            var old = env.g.getClip();
            env.g.clip(h == 0 ? new Rectangle2D.Double(0, 0, W, H / 2 - gap) : new Rectangle2D.Double(0, H / 2 + gap, W, H / 2));
            bb = BB.union(bb, env.mainDraw(it(t, p.s("font"), size, W / 2 + (h == 0 ? -off : off), H / 2).color(h == 0 ? sc.fg() : plate(env, sc.accent())).mi(h * 3)));
            env.g.setClip(old);
        }
        env.rect(W * 0.06, H / 2 - hair(), W * 0.88, hair() * 2, sc.sub(), a * 0.6);
        return bb;
    }

    // ------------------------------------------------------------------ stickerBomb
    private static BB stickerBomb(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> parts = splitK(env.cut.text, Math.min(5, Math.max(1, gcount(env.cut.text) / 2)));
        int k = parts.size();
        long seed = (long) p.d("seed");
        Color[] pal = {plate(env, sc.accent()), lightest(sc), plate(env, sc.accent2(), sc.sub()), plate(env, sc.ink(), sc.fg())};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outBack(clamp((lt - 0.09 * i) / 0.3)), x = W * (0.2 + 0.6 * (i + 0.5) / k), y = H / 2 + LyricKit.rs(seed, i, 1) * H * 0.14, ang = LyricKit.rs(seed, i, 2) * 14;
            Color f = pal[i % pal.length];
            double sz = Math.min(U * 0.14, LyricKit.fitSize(parts.get(i), p.s("font"), W * 0.2, U * 0.14)), sw = meas(parts.get(i), p.s("font"), sz, 0, 1).w() + sz * 0.8;
            final int idx = i; final double fx = x, fy = y, fs = clamp(e); final BB[] r = new BB[1]; final Color ff = f;
            rotated(env, x, y, ang, () -> {
                env.rrect(fx - sw / 2 * fs - U * 0.008, fy - sz * 0.7 * fs - U * 0.008, sw * fs + U * 0.016, sz * 1.4 * fs + U * 0.016, sz * 0.4, Color.WHITE, a, null, 0);
                env.rrect(fx - sw / 2 * fs, fy - sz * 0.7 * fs, sw * fs, sz * 1.4 * fs, sz * 0.35, ff, a, null, 0);
                if (fs > 0.6) r[0] = env.mainDraw(it(parts.get(idx), p.s("font"), sz, fx, fy).color(onCol(sc, ff)).mi(idx * 2));
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ tape
    private static BB tape(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> parts = splitK(env.cut.text, Math.min(3, Math.max(1, gcount(env.cut.text) / 3)));
        int k = parts.size();
        double bh = U * 0.15;
        Color tp = plate(env, sc.accent(), sc.fg());
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outExpo(clamp((lt - 0.12 * i) / 0.4)), y = H / 2 + (i - (k - 1) / 2.0) * bh * 1.35, ang = (i % 2 == 0 ? -2.5 : 2.5);
            final int idx = i; final BB[] r = new BB[1]; final double fy = y;
            rotated(env, W / 2, y, ang, () -> {
                env.rect(W * 0.08 + W * 0.84 * (1 - e) * (idx % 2 == 0 ? -1 : 1) * 0.5, fy - bh / 2, W * 0.84 * e, bh, tp, a * 0.94);
                for (double x = W * 0.08; x < W * 0.92; x += U * 0.012) env.rect(x, fy - bh / 2, 1, bh * 0.08, LyricKit.mix(tp, Color.BLACK, 0.2), a * 0.4);
                if (e > 0.5) r[0] = env.mainDraw(it(parts.get(idx), p.s("font"), Math.min(bh * 0.72, LyricKit.fitSize(parts.get(idx), p.s("font"), W * 0.74, bh * 0.72)), W / 2, fy).color(onCol(sc, tp)).mi(idx * 3));
            });
            bb = BB.union(bb, r[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ tunnel
    private static BB tunnel(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        String t = flat(env.cut.text);
        double base = Math.min(U * 0.2, LyricKit.fitSize(t, p.s("font"), W * 0.5, U * 0.2));
        BB bb = null;
        for (int i = 7; i >= 0; i--) {
            double z = (i + env.ltb * 0.9) % 8 / 8.0, sz = base * (0.1 + z * z * 2.8), al = clamp(1 - z) * clamp(z * 6) * a;
            if (i == 0) bb = env.mainDraw(it(t, p.s("font"), base, W / 2, H / 2).color(sc.fg()).mi(0));
            else env.draw(it(t, p.s("font"), sz, W / 2, H / 2).outline(U * 0.003, LyricKit.mix(sc.sub(), plate(env, sc.accent()), z)).alpha(al).noGhost().noHalo());
        }
        return bb;
    }

    // ------------------------------------------------------------------ lineFocus
    private static BB lineFocus(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        List<String> ls = splitK(env.cut.text, Math.min(5, Math.max(2, gcount(env.cut.text) / 2)));
        int k = ls.size(), key = Math.floorMod((int) p.d("seed"), k);
        double lh = Math.min(U * 0.14, H * 0.7 / (k + 0.8));
        BB bb = null;
        for (int i = 0; i < k; i++) {
            boolean f = i == key;
            double sz = lh * (f ? 1.5 : 0.7), y = H / 2 + (i - (k - 1) / 2.0) * lh * 1.1;
            bb = BB.union(bb, env.mainDraw(it(flat(ls.get(i)), p.s("font"), Math.min(sz, LyricKit.fitSize(flat(ls.get(i)), p.s("font"), W * 0.8, sz)), W / 2, y).color(f ? plate(env, sc.accent(), sc.fg()) : sc.sub()).alpha(f ? 1 : 0.7).mi(i * 3)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ specimen
    private static BB specimen(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> us = charUnits(env.cut.text);
        String first = us.get(0);
        double e = inE(env, 0.5, 0);
        env.rect(W * 0.06, H * 0.12, W * 0.88, hair() * 2, sc.fg(), a * 0.8);
        env.rect(W * 0.06, H * 0.88, W * 0.88, hair() * 2, sc.fg(), a * 0.8);
        BB bb = env.mainDraw(it(first, p.s("font"), U * 0.5, W * 0.24, H / 2).color(sc.fg()).mi(0));
        double sz = U * 0.12;
        for (int i = 0; i < 4; i++) {
            double s = sz * Math.pow(0.7, i);
            bb = BB.union(bb, env.draw(it(flat(env.cut.text), p.s("font"), Math.min(s, LyricKit.fitSize(flat(env.cut.text), p.s("font"), W * 0.42, s)), W * 0.74, H * (0.26 + 0.14 * i)).align("center").color(i == 0 ? plate(env, sc.accent(), sc.fg()) : sc.sub()).alpha(a * clamp((lt - 0.06 * i) / 0.3)).noGhost()));
        }
        label(env, "Aa  " + p.s("font").toUpperCase(), W * 0.07, H * 0.1, U * 0.02, a * e, "left", sc.sub());
        label(env, meta(env), W * 0.93, H * 0.92, U * 0.018, a * e, "right", sc.sub());
        return bb;
    }

    // ------------------------------------------------------------------ wordCloud
    private static BB wordCloud(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env), lt = env.lt;
        List<String> parts = splitK(env.cut.text, Math.min(8, Math.max(2, gcount(env.cut.text) / 2)));
        int k = parts.size();
        long seed = (long) p.d("seed");
        Color[] cols = {sc.fg(), plate(env, sc.accent()), sc.sub(), plate(env, sc.accent2(), sc.sub())};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double e = E.outBack(clamp((lt - 0.07 * i) / 0.35)), ang = i * 2.399 + LyricKit.r(seed, i, 1), rad = U * 0.04 + (i / (double) k) * U * 0.34;
            double x = W / 2 + Math.cos(ang) * rad * 1.9, y = H / 2 + Math.sin(ang) * rad * 0.9, sz = U * (i == 0 ? 0.2 : 0.07 + 0.07 * LyricKit.r(seed, i, 2));
            if (i == 0) { x = W / 2; y = H / 2; }
            sz = Math.min(sz, LyricKit.fitSize(parts.get(i), p.s("font"), W * 0.3, sz));
            bb = BB.union(bb, env.mainDraw(it(parts.get(i), p.s("font"), sz * clamp(e), x, y).rot(i == 0 ? 0 : (LyricKit.r(seed, i, 3) < 0.3 ? 90 : 0)).color(cols[i % cols.length]).mi(i * 2)));
        }
        return bb;
    }

    // ------------------------------------------------------------------ zoomRepeat
    private static BB zoomRepeat(Env env) {
        var sc = env.sc; Params p = env.P(); double a = tout(env);
        String t = flat(env.cut.text);
        double base = Math.min(U * 0.28, LyricKit.fitSize(t, p.s("font"), W * 0.78, U * 0.28));
        BB bb = null;
        int reps = 4;
        for (int i = reps - 1; i >= 0; i--) {
            double ph = clamp(env.lt * 1.4 - i * 0.18), sz = base * (0.4 + 1.6 * E.outExpo(ph)) ;
            if (i == 0) bb = env.mainDraw(it(t, p.s("font"), base, W / 2, H / 2).color(sc.fg()).mi(0));
            else env.draw(it(t, p.s("font"), sz, W / 2, H / 2).outline(U * 0.004, i % 2 == 0 ? plate(env, sc.accent()) : sc.sub()).alpha(a * (1 - ph) * 0.8).noGhost().noHalo());
        }
        return bb;
    }
}
