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

/**
 * Print, editorial and paper-object layouts (magazine, contents, footnote, proofread, poster, dictionary, ransom note, newspaper,
 * records, tapes, spines, photos, stamps, postcards, calendars, lanterns, signs, curtains, slips, scrolls, labels and cards).
 * These are condensed re-creations of the originals: the same ideas and names, simpler drawing (see Credits).
 */
final class LyricPaper {
    private LyricPaper() {}

    private static final double W = Env.W, H = Env.H, U = Math.min(W, H);

    static void init() {}

    // ------------------------------------------------------------------ helpers
    static final class Card {
        final Color fill, text, acc, line;
        final boolean edge;
        Card(Color fill, Color text, Color acc, Color line, boolean edge) { this.fill = fill; this.text = text; this.acc = acc; this.line = line; this.edge = edge; }
    }

    static Color lightest(LyricEngine.Scheme sc) {
        Color best = sc.bg();
        for (Color c : new Color[]{sc.fg(), sc.ink(), sc.sub(), sc.accent(), sc.accent2(), sc.dim()}) if (c != null && LyricKit.lum(c) > LyricKit.lum(best)) best = c;
        return best;
    }

    static Color darkest(LyricEngine.Scheme sc) {
        Color best = sc.bg();
        for (Color c : new Color[]{sc.fg(), sc.ink(), sc.sub(), sc.accent(), sc.accent2(), sc.dim()}) if (c != null && LyricKit.lum(c) < LyricKit.lum(best)) best = c;
        return best;
    }

    static Card card(LyricEngine.Scheme sc) {
        Color fill = lightest(sc), text = onCol(sc, fill);
        Color acc = LyricKit.contrast(sc.accent(), fill) >= 2 ? sc.accent() : text;
        return new Card(fill, text, acc, LyricKit.mix(fill, text, 0.35), LyricKit.contrast(fill, sc.bg()) < 1.4);
    }

    static Color plateCol(LyricEngine.Scheme sc, Color against, Color... pref) {
        for (Color c : pref) if (c != null && LyricKit.contrast(c, against) >= 1.8) return c;
        return LyricKit.contrast(sc.fg(), against) >= LyricKit.contrast(sc.bg(), against) ? sc.fg() : sc.bg();
    }

    static void paper(Env env, double x, double y, double w, double h, double r, Color fill, double a, boolean edge) {
        if (a <= 0.01 || w <= 0 || h <= 0) return;
        double k = U * 0.012;
        env.rrect(x + k * 0.6, y + k, w, h, r, new Color(0, 0, 0, (int) (clamp(a * (LyricKit.lum(env.sc.bg()) > 0.5 ? 0.22 : 0.5)) * 255)), 1, null, 0);
        env.rrect(x, y, w, h, r, fill, a, edge ? LyricKit.mix(env.sc.fg(), fill, 0.35) : null, Math.max(1, U * 0.0016));
    }

    record Block(String text, double size, int lines) {}

    /** The best line breaking (1..maxLines) and size for a text that must fit the box. */
    static Block fitBlock(String text, String font, double aw, double ah, double lead, double track, int maxLines, boolean vertical) {
        String t = text.trim();
        int n = Math.max(1, gcount(t));
        Block best = null;
        double bs = -1;
        for (int L = 1; L <= Math.min(maxLines, n); L++) {
            String s = L == 1 ? t : brk(t, (int) Math.ceil(n / (double) L));
            int lines = s.split("\n", -1).length;
            if (lines > maxLines) continue;
            double size = LyricKit.fitSize(s, font, aw, ah, new Item("", "", 1).track(track).lead(lead).vertical(vertical));
            boolean lone = lines > 1 && n > 2 && java.util.Arrays.stream(s.split("\n")).anyMatch(l -> gcount(l) < 2);
            double score = size * (1 - 0.06 * (lines - 1)) * (lone ? 0.8 : 1);
            if (score > bs) { bs = score; best = new Block(s, size, lines); }
        }
        return best != null ? best : new Block(t, LyricKit.fitSize(t, font, aw, ah), 1);
    }

    static BB mainFit(Env env, String text, String font, double cx, double cy, double aw, double ah, Color col, double lead, double track, int maxLines, double maxSize) {
        Block b = fitBlock(text, font, aw, ah, lead, track, maxLines, false);
        return env.mainDraw(it(b.text, font, Math.min(b.size, maxSize), cx, cy).lead(lead).track(track).color(col));
    }

    static void label(Env env, String t, double x, double y, double size, double a, String align, Color c) { LyricTypo.label(env, t, x, y, size, a, align, c); }

    static void small(Env env, String t, double x, double y, double size, double a, String align, Color c) {
        if (a <= 0.01) return;
        env.draw(it(t, body(env), size, x, y).align(align).color(c).alpha(a).noGhost());
    }

    static void greek(Env env, double x, double y, double w, double h, double lh, Color c, double a, long seed, double reveal) {
        int n = (int) Math.min(80, h / lh);
        double g = lh * 0.4;
        for (int r = 0; r < n; r++) {
            if (r >= n * reveal * 1.1) break;
            boolean endP = LyricKit.r(seed, r, 3) < 0.14;
            double len = endP ? w * (0.2 + 0.55 * LyricKit.r(seed, r, 4)) : w;
            env.dashed(new double[][]{{x, y + (r + 0.5) * lh}, {x + len, y + (r + 0.5) * lh}}, c, g * 0.72, a, (float) (g * 0.78), (float) (g * 0.24));
        }
    }

    static String src(Env env) { return flat(env.cut.lineText == null || env.cut.lineText.isBlank() ? env.cut.text : env.cut.lineText); }

    static String meta(Env env) { return "No." + lineNo(env) + "  /  " + fmtTime(env.cut.start); }

    static double ang(double deg) { return Math.toRadians(deg); }

    interface Draw { void run(); }

    /** Runs the drawing rotated about (cx, cy). */
    static void rotated(Env env, double cx, double cy, double deg, Draw d) {
        var keep = env.g.getTransform();
        env.g.translate(cx, cy); env.g.rotate(ang(deg)); env.g.translate(-cx, -cy);
        try { d.run(); } finally { env.g.setTransform(keep); }
    }

    static BB bbox(double x0, double y0, double x1, double y1) { return box(x0, y0, x1, y1); }

    // ------------------------------------------------------------------ layouts
    static {
        def("magazine", "見開き", n -> n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif", "serif")).with("kicker", rng.pick("FEATURE", "ESSAY", "INTERVIEW", "COLUMN", "STORY")).with("folio", (double) (2 * rng.integer(6, 90))).with("seed", (double) rng.integer(1, 9999)),
            LyricPaper::magazine);
        def("headlineDeck", "見出しとリード", n -> n <= 22,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "display", "serif")).with("kicker", rng.pick("FEATURE", "COVER STORY", "ESSAY", "REPORT")).with("dbl", rng.chance(0.5)).with("bottom", rng.chance(0.4)),
            LyricPaper::headlineDeck);
        def("contents", "目次", n -> n >= 2 && n <= 20,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("page0", (double) rng.integer(3, 40)).with("step", (double) rng.integer(6, 22)),
            LyricPaper::contents);
        def("footnote", "脚注", n -> n >= 2 && n <= 22,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display", "serif")).with("marks", rng.pick("num", "kome", "star")).with("left", rng.chance(0.6)),
            LyricPaper::footnote);
        def("proofread", "校正", n -> n >= 2 && n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("seed", (double) rng.integer(1, 9999)),
            LyricPaper::proofread);
        def("numbered", "番号付き", n -> n >= 2 && n <= 22,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("nf", fontPick(rng, st, "display", "mono")),
            LyricPaper::numbered);
        def("poster", "ポスター", n -> n <= 16,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display")).with("frame", rng.chance(0.7)).with("plate", rng.pick("accent", "fg", "ink")),
            LyricPaper::poster);
        def("swissGrid", "スイス組", n -> n <= 18,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "body")).with("col", (double) rng.integer(1, 3)),
            LyricPaper::swissGrid);
        def("dictionary", "辞書", n -> n >= 1 && n <= 12,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "serif", "display")).with("pos", rng.pick("n.", "v.", "adj.", "adv.", "int.")),
            LyricPaper::dictionary);
        def("ema", "絵馬", n -> n >= 2 && n <= 14,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("tilt", rng.range(-5, 5)),
            LyricPaper::ema);
        def("ransom", "切り抜き文", n -> n >= 2 && n <= 14,
            (rng, cut, st) -> new Params().with("seed", (double) rng.integer(1, 9999)),
            LyricPaper::ransom);
        def("newspaper", "新聞", n -> n <= 24,
            (rng, cut, st) -> new Params().with("font", fontPick(rng, st, "display", "serif")).with("seed", (double) rng.integer(1, 9999)),
            LyricPaper::newspaper);
        P2.init();
    }

    // ------------------------------------------------------------------ 1 magazine
    private static BB magazine(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, out = tout(env);
        Card C = card(sc);
        double ph = Math.min(H * 0.84, W * 0.44 / 0.72), pw = ph * 0.72, cx = W / 2, cy = H / 2;
        double aA = tin(env, 0, 0.28, E::outCubic) * out, uf = E.inOutCubic(clamp((lt - 0.06) / 0.5));
        double ax0 = cx - pw, ay0 = cy - ph / 2, ls = clamp(pw * 0.03, 11, 26), mx = pw * 0.1, my = ph * 0.075, lw = Math.max(1, U * 0.0014);
        paper(env, ax0, ay0, pw * (1 + uf), ph, 0, C.fill, aA, C.edge);
        env.rect(cx, ay0, pw * uf, ph, C.fill, aA);
        env.line(new double[][]{{cx, ay0}, {cx, ay0 + ph}}, C.line, lw * 2, aA * 0.5 * uf);
        if (uf > 0.3) {
            double ca = aA * clamp((uf - 0.3) / 0.5), bx = cx + mx * uf, by = ay0 + my, iw = pw * uf - mx * 2;
            Color imgC = plateCol(sc, C.fill, sc.accent(), sc.ink(), sc.fg());
            if (iw > 4) {
                env.rect(bx, by, iw, ph * 0.38, imgC, ca);
                env.circle(bx + iw * 0.62, by + ph * 0.2, Math.min(iw, ph * 0.38) * 0.28, plateCol(sc, imgC, sc.accent2(), C.fill), null, 0, ca);
                double fs = clamp(pw * 0.022, 9, 20);
                greek(env, bx, by + ph * 0.38 + fs * 2, iw, ph * 0.5, fs * 1.75, C.text, ca * 0.5, (long) p.d("seed"), uf);
                label(env, two((int) p.d("folio") + 1), bx + iw, ay0 + ph - my * 0.45, ls * 0.85, ca * 0.8, "right", C.text);
            }
        }
        double fa = tin(env, 0.12, 0.4, E::outCubic) * out, ax = ax0 + mx, ay = ay0 + my, aw = pw - mx * 2, ah = ph - my * 2;
        env.rect(ax, ay + ls * 0.1, ls * 0.8, ls * 0.8, C.acc, fa);
        label(env, p.s("kicker") + "  No." + lineNo(env), ax + ls * 1.4, ay + ls * 0.5, ls, fa, "left", C.text);
        Block b = fitBlock(env.cut.text, p.s("font"), aw, ah * 0.52, 1.1, 0.01, 4, false);
        double size = Math.min(b.size, U * 0.15);
        var m = meas(b.text, p.s("font"), size, 0.01, 1.1);
        double hy = ay + ls * 2.4 + m.h() / 2;
        BB bb = env.mainDraw(it(b.text, p.s("font"), size, ax, hy).align("left").lead(1.1).track(0.01).color(C.text));
        double ry = hy + m.h() / 2 + size * 0.35, re = tin(env, 0.15, 0.6, E::inOutCubic) * out;
        env.line(new double[][]{{ax, ry}, {ax + aw * re, ry}}, C.text, Math.max(2, lw * 2), 0.9 * aA);
        double ds = clamp(pw * 0.034, 11, 30);
        String deck = brk(altCopy(env), Math.max(4, (int) (aw / (ds * 1.05))));
        String[] dl = deck.split("\n");
        env.draw(it(String.join("\n", java.util.Arrays.copyOf(dl, Math.min(3, dl.length))), body(env), ds, ax, ry + ds * 1.2 + (Math.min(3, dl.length) - 1) * ds * 0.78).align("left").lead(1.55).color(C.text).alpha(fa * 0.75).noGhost());
        label(env, two((int) p.d("folio")), ax0 + mx * 0.5, ay0 + ph - my * 0.45, ls * 0.85, fa * 0.8, "left", C.text);
        return bb != null ? bb : box(ax, hy - m.h() / 2, ax + m.w(), hy + m.h() / 2);
    }

    // ------------------------------------------------------------------ 2 headlineDeck
    private static BB headlineDeck(Env env) {
        var sc = env.sc; Params p = env.P(); double out = tout(env);
        double mx = W * 0.075, aw = W - mx * 2, ls = smallSize(env) * 1.1, ds = clamp(U * 0.032, 16, 44), lw = Math.max(2, U * 0.003);
        Block b = fitBlock(env.cut.text, p.s("font"), aw, H * 0.46, 1.02, -0.01, 3, false);
        double size = Math.min(b.size, U * 0.24);
        var m = meas(b.text, p.s("font"), size, -0.01, 1.02);
        String dc = altCopy(env);
        String[] dlines = brk(dc, Math.max(5, (int) (aw * 0.62 / (ds * (hasLatin(dc) ? 0.55 : 1.02))))).split("\n");
        int nd = Math.min(4, dlines.length);
        double deckH = nd * ds * 1.6, kickH = ls * 2.2, top, hy, ry, dy;
        if (p.b("bottom")) {
            double tot = deckH + ls * 1.6 + m.h() + kickH;
            top = Math.max(H * 0.08, H * 0.9 - tot); dy = top; ry = top + deckH + ls * 0.6; hy = ry + ls + kickH + m.h() / 2;
        } else {
            double tot = kickH + m.h() + size * 0.3 + ls + deckH;
            top = Math.max(H * 0.07, (H - tot) / 2 - H * 0.03); hy = top + kickH + m.h() / 2; ry = hy + m.h() / 2 + size * 0.22; dy = ry + ls * 1.2;
        }
        double ka = tin(env, 0.05, 0.4) * out, ky = hy - m.h() / 2 - ls * 1.1;
        String kt = p.s("kicker") + "  " + lineNo(env);
        var km = meas(kt, mono(env), ls, 0.2, 1.2);
        if (ka > 0.01) {
            env.rect(mx, ky - ls * 0.8, (km.w() + ls * 1.2) * ka, ls * 1.6, sc.accent(), out);
            env.draw(it(kt, mono(env), ls, mx + ls * 0.6, ky).align("left").track(0.2).color(onCol(sc, sc.accent())).alpha(clamp(ka * 2 - 1)).noGhost());
        }
        BB bb = env.mainDraw(it(b.text, p.s("font"), size, mx, hy).align("left").lead(1.02).track(-0.01).color(sc.fg()));
        double re = tin(env, 0.1, 0.7, E::inOutCubic) * out;
        env.line(new double[][]{{mx, ry}, {mx + aw * re, ry}}, sc.fg(), lw, 1);
        if (p.b("dbl")) env.line(new double[][]{{mx, ry + lw * 2.5}, {mx + aw * re, ry + lw * 2.5}}, sc.fg(), lw * 0.4, 0.8);
        for (int i = 0; i < nd; i++) {
            double a = tin(env, env.cut.inDur * 0.6 + 0.08 * i, 0.4, E::outCubic) * out;
            if (a > 0.01) env.draw(it(dlines[i], body(env), ds, mx, dy + ds * 0.8 + i * ds * 1.6 + (1 - a) * ds * 0.5).align("left").color(i == 0 ? sc.fg() : sc.sub()).alpha(a).noGhost());
        }
        return bb != null ? bb : box(mx, hy - m.h() / 2, mx + m.w(), hy + m.h() / 2);
    }

    // ------------------------------------------------------------------ 3 contents
    private static BB contents(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double out = tout(env);
        int n = gcount(env.cut.text), k = n <= 4 ? 1 : n <= 9 ? 2 : n <= 14 ? 3 : 4;
        List<String> chunks = splitK(env.cut.text, k);
        k = chunks.size();
        double ls = smallSize(env), lw = Math.max(1.2, U * 0.0016);
        int cur = Math.min(k - 1, (int) Math.floor(clamp(env.lt / Math.max(0.3, env.dur() * 0.92)) * k));
        double bw = W * 0.66, bx = (W - bw) / 2, numW = ls * 3.4, pgW = ls * 5, tw = bw - numW - pgW - ls * 2;
        double rowH0 = Math.min(H * 0.66 / k, U * 0.26), size = rowH0 * 0.64;
        for (String c : chunks) size = Math.min(size, LyricKit.fitSize(c, font, tw * 0.92, rowH0 * 0.7, new Item("", "", 1).track(0.03)));
        double rowH = Math.max(size * 1.62, ls * 2.4), blockH = k * rowH, y0 = H / 2 - blockH / 2 + ls * 1.8;
        double ha = tin(env, 0, 0.4, E::outCubic) * out, hy = y0 - ls * 2.8;
        env.draw(it("目次", body(env), ls * 2.1, bx, hy - ls * 0.2).align("left").track(0.5).color(sc.fg()).alpha(ha).noGhost());
        label(env, "CONTENTS", bx + bw, hy, ls * 0.85, ha, "right", sc.sub());
        double le = tin(env, 0.05, 0.6, E::inOutCubic) * out;
        env.line(new double[][]{{bx, hy + ls * 1.4}, {bx + bw * le, hy + ls * 1.4}}, sc.fg(), lw * 1.6, 1);
        env.line(new double[][]{{bx + bw, y0 + blockH + ls * 0.2}, {bx + bw - bw * le, y0 + blockH + ls * 0.2}}, sc.fg(), lw, 0.7);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double y = y0 + (i + 0.5) * rowH, a = tin(env, 0.1 + i * 0.1, 0.5, E::outCubic) * out;
            boolean isCur = i == cur;
            Color c = isCur ? sc.accent() : sc.sub();
            label(env, two(i + 1), bx, y, ls * 1.2, a, "left", c);
            bb = BB.union(bb, env.mainDraw(it(chunks.get(i), font, size, bx + numW, y).align("left").track(0.03).color(sc.fg()).mi(i * 3)));
            double tx1 = bx + numW + meas(chunks.get(i), font, size, 0.03, 1.2).w() + ls * 0.8, lx1 = bx + bw - pgW;
            if (lx1 > tx1) env.dashed(new double[][]{{tx1, y + size * 0.3}, {LyricKit.lerp(tx1, lx1, a), y + size * 0.3}}, c, Math.max(1.2, ls * 0.16), a, 0.6f, (float) (ls * 0.5));
            label(env, String.format("%03d", (int) p.d("page0") + i * (int) p.d("step")), bx + bw, y, ls * 1.4, a, "right", isCur ? sc.accent() : sc.fg());
            if (isCur) env.rect(bx - ls * 1.1, y - size * 0.45, ls * 0.35, size * 0.9, sc.accent(), clamp(a * 2 - 0.4));
        }
        return bb != null ? bb : box(bx, y0, bx + bw, y0 + blockH);
    }

    // ------------------------------------------------------------------ 4 footnote
    private static BB footnote(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double out = tout(env);
        int n = gcount(env.cut.text), k = n <= 3 ? 1 : n <= 8 ? 2 : 3;
        List<String> chunks = splitK(env.cut.text, k);
        k = chunks.size();
        boolean lat = hasLatin(env.cut.text);
        String t0 = String.join(lat ? " " : "", chunks);
        double mx = W * 0.09, aw = W - mx * 2, ls = smallSize(env), notesH = k * ls * 1.95 + ls * 2.2, areaTop = H * 0.12, areaBot = H - notesH - H * 0.1;
        Block b = fitBlock(t0, font, aw * 0.88, (areaBot - areaTop) * 0.8, 1.25, 0.03, 3, false);
        double size = Math.min(b.size, U * 0.17);
        boolean left = p.b("left");
        BB bb = env.mainDraw(it(b.text, font, size, left ? mx : W / 2, (areaTop + areaBot) / 2).align(left ? "left" : "center").lead(1.25).track(0.03).color(sc.fg()));
        double ny = H - notesH - H * 0.02 + ls;
        double re = tin(env, 0.1, 0.6, E::inOutCubic) * out;
        env.line(new double[][]{{mx, ny - ls * 0.8}, {mx + aw * 0.3 * re, ny - ls * 0.8}}, sc.sub(), Math.max(1, U * 0.0016), 0.8);
        for (int i = 0; i < k; i++) {
            String mark = p.s("marks").equals("num") ? String.valueOf(i + 1) : p.s("marks").equals("kome") ? "※" + (i + 1) : "*" + (i + 1);
            double a = tin(env, 0.2 + i * 0.12, 0.45, E::outCubic) * out;
            env.draw(it(mark, mono(env), ls, mx, ny + i * ls * 1.95).align("left").color(sc.accent()).alpha(a).noGhost());
            String note = (i == 0 ? "" : "") + chunks.get(i) + (lat ? " — " : "、") + "No." + lineNo(env) + "." + (i + 1);
            small(env, note, mx + ls * 2.4, ny + i * ls * 1.95, ls, a * 0.8, "left", sc.sub());
        }
        return bb;
    }

    // ------------------------------------------------------------------ 5 proofread
    private static BB proofread(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double lt = env.lt, out = tout(env);
        String text = env.cut.text.trim();
        Block b = fitBlock(text, font, W * 0.74, H * 0.46, 1.4, 0.04, 3, false);
        double size = Math.min(b.size, U * 0.16);
        Item item = it(b.text, font, size, W / 2, H / 2).lead(1.4).track(0.04).color(sc.fg());
        BB bb = env.mainDraw(item);
        Color red = LyricKit.contrast(sc.accent(), sc.bg()) >= 1.8 ? sc.accent() : sc.sub();
        var gl = LyricKit.layoutText(item);
        long seed = (long) p.d("seed");
        double lw = Math.max(1.5, size * 0.03);
        int marks = Math.min(4, gl.size());
        for (int k = 0; k < marks; k++) {
            var g = gl.get((int) (LyricKit.r(seed, k, 1) * gl.size()));
            double e = E.outCubic(clamp((lt - 0.25 - k * 0.14) / 0.3)) * out;
            if (e <= 0) continue;
            double gx = W / 2 + g.x, gy = H / 2 + g.y;
            switch (k % 3) {
                case 0 -> env.line(new double[][]{{gx - g.w * 0.5, gy}, {gx - g.w * 0.5 + g.w * e, gy - size * 0.05}}, red, lw, 0.95);
                case 1 -> { env.line(new double[][]{{gx - g.w * 0.3, gy + size * 0.62}, {gx, gy + size * 0.42 * (0.5 + 0.5 * e)}, {gx + g.w * 0.3, gy + size * 0.62}}, red, lw, 0.95); small(env, "ママ", gx, gy + size * 0.95, size * 0.2, e, "center", red); }
                default -> env.circle(gx, gy, size * 0.62 * e, null, red, lw, 0.9);
            }
        }
        label(env, "校正  No." + lineNo(env), W * 0.08, H * 0.1, lsz(), tin(env, 0.1, 0.4) * out, "left", red);
        return bb;
    }

    // ------------------------------------------------------------------ 6 numbered
    private static BB numbered(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double out = tout(env);
        int n = gcount(env.cut.text), k = n <= 4 ? 2 : n <= 9 ? 3 : 4;
        List<String> chunks = splitK(env.cut.text, k);
        k = chunks.size();
        double rowH = Math.min(H * 0.74 / k, U * 0.24), size = rowH * 0.62;
        for (String c : chunks) size = Math.min(size, LyricKit.fitSize(c, font, W * 0.56, rowH * 0.8, new Item("", "", 1).track(0.03)));
        double x0 = W * 0.17, y0 = H / 2 - k * rowH / 2, lw = Math.max(1.2, U * 0.0016);
        BB bb = null;
        for (int i = 0; i < k; i++) {
            double y = y0 + (i + 0.5) * rowH, a = tin(env, 0.08 + i * 0.1, 0.5, E::outCubic) * out;
            env.draw(it(String.valueOf(i + 1), p.s("nf"), rowH * 0.8, x0 - W * 0.02, y).align("right").outline(Math.max(1.5, rowH * 0.012), i == k - 1 ? sc.accent() : sc.sub()).alpha(a).noGhost());
            bb = BB.union(bb, env.mainDraw(it(chunks.get(i), font, size, x0 + W * 0.03, y).align("left").track(0.03).color(sc.fg()).mi(i * 3)));
            env.line(new double[][]{{x0 - W * 0.12, y0 + (i + 1) * rowH}, {LyricKit.lerp(x0 - W * 0.12, W * 0.88, a), y0 + (i + 1) * rowH}}, sc.sub(), lw, 0.4);
        }
        return bb;
    }

    // ------------------------------------------------------------------ 7 poster
    private static BB poster(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double lt = env.lt, out = tout(env);
        double m = U * 0.05, lw = Math.max(2, U * 0.004), e = E.outExpo(clamp(lt / 0.6)) * out;
        Color plate = plateCol(sc, sc.bg(), p.s("plate").equals("accent") ? sc.accent() : p.s("plate").equals("ink") ? sc.ink() : sc.fg(), sc.fg());
        if (p.b("frame") && e > 0) env.line(new double[][]{{m, m}, {W - m, m}, {W - m, H - m}, {m, H - m}, {m, m}}, sc.fg(), lw, e);
        env.rect(m, H - m - U * 0.09, (W - m * 2) * e, U * 0.09, plate, 1);
        label(env, "No." + lineNo(env) + "   " + fmtTime(env.cut.start) + "   " + gcount(env.cut.text) + (hasLatin(env.cut.text) ? " CHARS" : "字"), m + U * 0.02, H - m - U * 0.045, lsz(), e, "left", onCol(sc, plate));
        BB bb = mainFit(env, env.cut.text, font, W / 2, H * 0.44, W * 0.8, H * 0.58, sc.fg(), 1.1, 0.02, 3, U * 0.3);
        label(env, p.b("frame") ? "PRESENTS" : "LIVE", W / 2, m + U * 0.05, lsz() * 1.1, e, "center", sc.sub());
        return bb;
    }

    // ------------------------------------------------------------------ 8 swissGrid
    private static BB swissGrid(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double lt = env.lt, out = tout(env), lw = hair();
        int cols = 6;
        double m = W * 0.06, cw = (W - m * 2) / cols, e = E.outExpo(clamp(lt / 0.7)) * out;
        for (int i = 0; i <= cols; i++) env.line(new double[][]{{m + i * cw, H * 0.08}, {m + i * cw, H * 0.08 + H * 0.84 * e}}, sc.sub(), lw, 0.25);
        int c0 = (int) p.d("col") == 1 ? 0 : (int) p.d("col") == 2 ? 1 : 2;
        BB bb = null;
        Block b = fitBlock(env.cut.text, font, cw * (cols - c0 - 0.4), H * 0.5, 1.0, -0.01, 3, false);
        double size = Math.min(b.size, U * 0.22);
        bb = env.mainDraw(it(b.text, font, size, m + c0 * cw, H * 0.5).align("left").lead(1.0).track(-0.01).color(sc.fg()));
        label(env, lineNo(env), m, H * 0.12, lsz() * 1.8, e, "left", sc.accent());
        label(env, fmtTime(env.cut.start), m + cw * 5, H * 0.92, lsz(), e, "left", sc.sub());
        env.rect(m, H * 0.08, cw * 0.8 * e, U * 0.012, sc.accent(), 1);
        return bb;
    }

    // ------------------------------------------------------------------ 9 dictionary
    private static BB dictionary(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double lt = env.lt, out = tout(env);
        double x0 = W * 0.16, y0 = H * 0.3, ls = lsz() * 1.2;
        Block b = fitBlock(env.cut.text, font, W * 0.68, H * 0.34, 1.1, 0.02, 2, false);
        double size = Math.min(b.size, U * 0.2);
        BB bb = env.mainDraw(it(b.text, font, size, x0, y0 + size * 0.2).align("left").lead(1.1).track(0.02).color(sc.fg()));
        var m = meas(b.text, font, size, 0.02, 1.1);
        double a = tin(env, 0.2, 0.4, E::outCubic) * out, y = y0 + size * 0.2 + m.h() / 2 + ls * 1.4;
        env.draw(it("[" + p.s("pos") + "]", mono(env), ls, x0, y).align("left").color(sc.accent()).alpha(a).noGhost());
        env.line(new double[][]{{x0, y + ls}, {x0 + (W * 0.68) * E.outExpo(clamp((lt - 0.2) / 0.6)) * out, y + ls}}, sc.fg(), Math.max(1.5, U * 0.002), 0.9);
        String[] defs = {"1. " + src(env), "2. " + altCopy(env), "3. No." + lineNo(env) + " / " + fmtTime(env.cut.start)};
        for (int i = 0; i < 3; i++) small(env, defs[i], x0, y + ls * 2.4 + i * ls * 1.6, ls * 0.95, tin(env, 0.3 + i * 0.1, 0.4, E::outCubic) * out * 0.8, "left", i == 0 ? sc.fg() : sc.sub());
        return bb;
    }

    // ------------------------------------------------------------------ 10 ema
    private static BB ema(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double lt = env.lt, out = tout(env);
        Color wood = LyricKit.mix(lightest(sc), sc.accent2() != null ? sc.accent2() : sc.accent(), 0.25), ink = onCol(sc, wood);
        double w = Math.min(W * 0.62, H * 1.0), h = w * 0.7, cx = W / 2, cy = H / 2 + U * 0.03;
        double a = tin(env, 0, 0.35, E::outCubic) * out, sway = Math.sin(env.ltb * 1.4) * 1.2 + p.d("tilt");
        final BB[] r = new BB[1];
        rotated(env, cx, cy - h / 2 - U * 0.08, sway * a, () -> {
            double[][] pent = {{cx - w / 2, cy - h * 0.28}, {cx, cy - h / 2}, {cx + w / 2, cy - h * 0.28}, {cx + w * 0.46, cy + h / 2}, {cx - w * 0.46, cy + h / 2}};
            env.poly(offs(pent, U * 0.008, U * 0.012), new Color(0, 0, 0, 70), a);
            env.poly(pent, wood, a);
            env.circle(cx, cy - h * 0.43, U * 0.012, ink, null, 0, a * 0.7);
            env.line(new double[][]{{cx - U * 0.05, cy - h / 2 - U * 0.09}, {cx, cy - h * 0.43}, {cx + U * 0.05, cy - h / 2 - U * 0.09}}, sc.accent(), Math.max(2, U * 0.004), a);
            r[0] = mainFit(env, env.cut.text, font, cx, cy + h * 0.06, w * 0.7, h * 0.5, ink, 1.2, 0.04, 3, U * 0.16);
            small(env, "願 No." + lineNo(env), cx, cy + h * 0.4, lsz(), a * 0.75, "center", ink);
        });
        return r[0];
    }

    static double[][] offs(double[][] pts, double dx, double dy) {
        double[][] o = new double[pts.length][];
        for (int i = 0; i < pts.length; i++) o[i] = new double[]{pts[i][0] + dx, pts[i][1] + dy};
        return o;
    }

    // ------------------------------------------------------------------ 11 ransom
    private static BB ransom(Env env) {
        var sc = env.sc; Params p = env.P(); double lt = env.lt, out = tout(env);
        List<String> us = charUnits(env.cut.text);
        int k = us.size();
        if (k == 0) return null;
        long seed = (long) p.d("seed");
        double cell = Math.min(W * 0.84 / Math.min(k, 8), U * 0.24), rowsN = Math.ceil(k / 8.0);
        int per = (int) Math.ceil(k / rowsN);
        Color[] pal = {lightest(sc), sc.accent(), sc.fg(), sc.accent2() != null ? sc.accent2() : sc.sub(), sc.ink()};
        BB bb = null;
        for (int i = 0; i < k; i++) {
            int row = i / per, col = i % per, inRow = Math.min(per, k - row * per);
            double x = W / 2 + (col - (inRow - 1) / 2.0) * cell * 0.95 + LyricKit.rs(seed, i, 1) * cell * 0.06;
            double y = H / 2 + (row - (rowsN - 1) / 2.0) * cell * 1.1 + LyricKit.rs(seed, i, 2) * cell * 0.08;
            double a = E.outBack(clamp((lt - i * 0.06) / 0.28)) * out;
            if (a <= 0.01) continue;
            Color fill = pal[(int) (LyricKit.r(seed, i, 3) * pal.length)];
            double rot = LyricKit.rs(seed, i, 4) * 9, s = cell * (0.78 + 0.18 * LyricKit.r(seed, i, 5));
            final double fx = x, fy = y;
            final int idx = i;
            final BB[] rr = new BB[1];
            rotated(env, x, y, rot, () -> {
                paper(env, fx - s / 2, fy - s / 2, s, s * 1.05, 2, fill, clamp(a), false);
                rr[0] = env.mainDraw(it(us.get(idx), "mincho", s * 0.7, fx, fy).color(onCol(sc, fill)).mi(idx));
            });
            bb = BB.union(bb, rr[0]);
        }
        return bb;
    }

    // ------------------------------------------------------------------ 12 newspaper
    private static BB newspaper(Env env) {
        var sc = env.sc; Params p = env.P(); String font = p.s("font"); double lt = env.lt, out = tout(env);
        Card C = card(sc);
        double pw = W * 0.7, ph = H * 0.9, x0 = (W - pw) / 2, y0 = (H - ph) / 2, a = tin(env, 0, 0.3, E::outCubic) * out, lw = hair();
        paper(env, x0, y0, pw, ph, 3, C.fill, a, C.edge);
        double m = pw * 0.05, ls = lsz();
        env.draw(it("THE DAILY LYRIC", font, ls * 2.2, x0 + pw / 2, y0 + ph * 0.07).color(C.text).alpha(a).noGhost());
        env.line(new double[][]{{x0 + m, y0 + ph * 0.115}, {x0 + pw - m, y0 + ph * 0.115}}, C.text, lw * 3, a);
        env.line(new double[][]{{x0 + m, y0 + ph * 0.125}, {x0 + pw - m, y0 + ph * 0.125}}, C.text, lw, a);
        BB bb = mainFit(env, env.cut.text, font, x0 + pw / 2, y0 + ph * 0.3, pw - m * 2, ph * 0.26, C.text, 1.05, 0.0, 3, U * 0.13);
        double fs = ls * 0.8, top = y0 + ph * 0.5, cw = (pw - m * 2 - m) / 2;
        greek(env, x0 + m, top, cw, ph * 0.42, fs * 1.6, C.text, a * 0.5, (long) p.d("seed"), clamp(lt / 0.8));
        greek(env, x0 + m * 2 + cw, top, cw, ph * 0.42, fs * 1.6, C.text, a * 0.5, (long) p.d("seed") + 5, clamp(lt / 0.8));
        env.line(new double[][]{{x0 + pw / 2, top}, {x0 + pw / 2, top + ph * 0.42}}, C.line, lw, a * 0.5);
        label(env, fmtTime(env.cut.start) + "   No." + lineNo(env), x0 + m, y0 + ph * 0.15, ls * 0.8, a, "left", C.text);
        return bb;
    }

    /** Second batch of paper-object layouts. */
    static final class P2 {
        static void init() { LyricPaper2.init(); }
    }
}
