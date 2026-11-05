package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricEngine.Scheme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import static io.github.raincandyfx.rainium.LyricKit.clamp;

/** Small decorations drawn around a cut on the back layer: marks, stamps, ornaments, weather and interface bits (condensed re-creations). */
final class LyricDecor {
    private LyricDecor() {}

    /** The ones the engine draws itself, so they aren't drawn twice. */
    static final java.util.Set<String> HANDLED = java.util.Set.of("rings", "slash", "arrows", "barcode", "counter", "hud");

    private static double h(long a, int b) { double x = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453; return x - Math.floor(x); }

    private static Color c(Color x, double a) { return new Color(x.getRed(), x.getGreen(), x.getBlue(), (int) Math.round(255 * clamp(a))); }

    private static void ln(Graphics2D g, double x0, double y0, double x1, double y1) { g.draw(new Line2D.Double(x0, y0, x1, y1)); }

    private static void txt(Graphics2D g, String s, double x, double y, double size, boolean bold) {
        g.setFont(new Font(Font.MONOSPACED, bold ? Font.BOLD : Font.PLAIN, (int) size));
        g.drawString(s, (float) x, (float) y);
    }

    private static void star(Graphics2D g, double x, double y, double r, int n, boolean fill) {
        Path2D p = new Path2D.Double();
        for (int i = 0; i < n * 2; i++) {
            double an = -Math.PI / 2 + i * Math.PI / n, rr = i % 2 == 0 ? r : r * 0.45;
            if (i == 0) p.moveTo(x + Math.cos(an) * rr, y + Math.sin(an) * rr); else p.lineTo(x + Math.cos(an) * rr, y + Math.sin(an) * rr);
        }
        p.closePath();
        if (fill) g.fill(p); else g.draw(p);
    }

    /** Draws one decoration around a text box centred at cx, cy of size bw x bh in a W x H picture; al is its overall fade. */
    static void paint(String key, Graphics2D g, Scheme sc, double ts, double lt, long step, double al, double cx, double cy, double bw, double bh, int W, int H, long seed) {
        Color A = sc.accent(), B = sc.accent2(), F = sc.fg(), D = sc.dim();
        double L = cx - bw / 2, R = cx + bw / 2, T = cy - bh / 2, Bt = cy + bh / 2;
        double k = clamp(lt / 0.4);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (key) {
            case "grid" -> { g.setColor(c(D, 0.35 * al)); for (int x = 0; x <= W; x += 50) ln(g, x, 0, x, H); for (int y = 0; y <= H; y += 50) ln(g, 0, y, W, y); }
            case "plusGrid" -> { g.setColor(c(D, 0.5 * al)); for (int x = 25; x < W; x += 50) for (int y = 25; y < H; y += 50) { ln(g, x - 3, y, x + 3, y); ln(g, x, y - 3, x, y + 3); } }
            case "guides" -> { g.setColor(c(A, 0.5 * al)); g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 4f}, 0)); ln(g, 0, cy, W, cy); ln(g, cx, 0, cx, H); ln(g, L, 0, L, H); ln(g, R, 0, R, H); }
            case "stripes", "verticalStrip", "bars" -> { g.setColor(c(A, 0.25 * al)); for (int i = 0; i < 6; i++) g.fill(key.equals("bars") ? new Rectangle2D.Double(0, T - 18 + i * 8, W * k, 3) : new Rectangle2D.Double(W - 30 - i * 8, 0, 4, H)); }
            case "blobs", "lightLeak", "halftonePatch" -> { for (int i = 0; i < 3; i++) { double x = W * (0.2 + 0.3 * i) + Math.sin(ts * 0.5 + i) * 30, y = H * (0.3 + 0.2 * (i % 2)), r = 110; g.setPaint(new RadialGradientPaint((float) x, (float) y, (float) r, new float[]{0f, 1f}, new Color[]{c(i % 2 == 0 ? A : B, 0.28 * al), c(A, 0)})); g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r)); } if (key.equals("halftonePatch")) { g.setColor(c(F, 0.3 * al)); for (int y = 0; y < 90; y += 9) for (int x = 0; x < 90; x += 9) g.fill(new Ellipse2D.Double(W - 100 + x, H - 100 + y, 2 + x / 30.0, 2 + x / 30.0)); } }
            case "shapes", "memphis", "polkaPatch", "stripeCircle", "halfCircles" -> { for (int i = 0; i < 6; i++) { double x = h(seed, i) * W, y = h(seed, i + 9) * H, r = 8 + h(seed, i + 20) * 16; g.setColor(c(i % 2 == 0 ? A : B, 0.6 * al)); switch ((int) (h(seed, i + 30) * 4)) { case 0 -> g.draw(new Ellipse2D.Double(x, y, r, r)); case 1 -> g.draw(new Rectangle2D.Double(x, y, r, r)); case 2 -> ln(g, x, y, x + r, y + r); default -> star(g, x, y, r / 2, 3, false); } } }
            case "brackets", "bracketsJP", "decoCorners", "cropMarks" -> { double m = key.equals("cropMarks") ? 30 : 14, len = 18 * k; g.setColor(c(A, 0.85 * al)); g.setStroke(new BasicStroke(2f)); double[][] pts = {{L - m, T - m, 1, 1}, {R + m, T - m, -1, 1}, {L - m, Bt + m, 1, -1}, {R + m, Bt + m, -1, -1}}; for (double[] p : pts) { ln(g, p[0], p[1], p[0] + p[2] * len, p[1]); ln(g, p[0], p[1], p[0], p[1] + p[3] * len); } }
            case "dots", "orbitDots" -> { for (int i = 0; i < 5; i++) { double an = ts * (key.equals("orbitDots") ? 0.8 : 0) + i * Math.PI * 2 / 5, r = Math.max(bw, bh) * 0.5 + 24; g.setColor(c(i % 2 == 0 ? A : B, 0.8 * al)); g.fill(new Ellipse2D.Double(cx + Math.cos(an) * r * 1.4 - 3, cy + Math.sin(an) * r * 0.6 - 3, 6, 6)); } }
            case "sparks", "twinkle", "starField", "heartsStars", "confetti", "petals", "momiji", "dandelion", "fireflies", "bubbles", "smoke", "musicNotes", "risingParticles", "snow", "bokeh", "cloudPuffs", "spectrumRing", "hanabi" -> {
                int n = key.equals("confetti") ? 26 : 14;
                for (int i = 0; i < n; i++) {
                    double sp = 8 + h(seed, i) * 25, x = (h(seed, i + 40) * W + (key.equals("petals") || key.equals("momiji") || key.equals("snow") ? ts * sp * 0.6 : Math.sin(ts + i) * 10)) % W, y;
                    boolean up = key.equals("bubbles") || key.equals("smoke") || key.equals("risingParticles") || key.equals("fireflies") || key.equals("musicNotes") || key.equals("dandelion");
                    y = up ? H - ((h(seed, i + 80) * H + ts * sp) % H) : (h(seed, i + 80) * H + ts * sp) % H;
                    double r = 2 + h(seed, i + 120) * (key.equals("bokeh") ? 14 : 4);
                    g.setColor(c(i % 3 == 0 ? B : i % 3 == 1 ? A : F, (key.equals("bokeh") ? 0.2 : 0.7) * al));
                    switch (key) {
                        case "heartsStars", "twinkle", "starField", "sparks" -> star(g, x, y, r * (0.6 + 0.5 * Math.sin(ts * 4 + i)), 4, true);
                        case "bubbles" -> g.draw(new Ellipse2D.Double(x, y, r * 2, r * 2));
                        case "musicNotes" -> { g.fill(new Ellipse2D.Double(x, y, r * 2, r * 1.6)); ln(g, x + r * 2, y + r * 0.8, x + r * 2, y - r * 3); }
                        case "petals", "momiji" -> g.fill(new Ellipse2D.Double(x, y, r * 2.2, r));
                        case "confetti" -> g.fill(new Rectangle2D.Double(x, y, r * 1.4, r * 0.7));
                        default -> g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
                    }
                }
            }
            case "arrows", "loopArrows" -> { g.setColor(c(B, 0.85 * al)); for (int i = 0; i < 3; i++) { double x = R + 20 + i * 12 + (ts * 20) % 12; ln(g, x, cy - 8, x + 8, cy); ln(g, x, cy + 8, x + 8, cy); } }
            case "leaders", "dimension", "rulerEdge", "timecodeBar" -> { g.setColor(c(A, 0.8 * al)); if (key.equals("dimension")) { ln(g, L, Bt + 20, R, Bt + 20); ln(g, L, Bt + 14, L, Bt + 26); ln(g, R, Bt + 14, R, Bt + 26); txt(g, String.valueOf((int) bw), cx - 10, Bt + 34, 9, false); } else if (key.equals("rulerEdge")) { for (int x = 0; x < W; x += 10) ln(g, x, H, x, H - (x % 50 == 0 ? 12 : 6)); } else if (key.equals("timecodeBar")) { g.draw(new Rectangle2D.Double(20, H - 30, W - 40, 6)); g.fill(new Rectangle2D.Double(20, H - 30, (W - 40) * ((ts * 0.05) % 1), 6)); } else { ln(g, L - 30, cy, L - 6, cy); ln(g, L - 30, cy, L - 40, cy - 14); txt(g, "NOTE", L - 70, cy - 16, 9, false); } }
            case "waveform", "waveLine", "volumeBars", "eqBars", "dataColumns" -> { g.setColor(c(A, 0.7 * al)); int n = 36; for (int i = 0; i < n; i++) { double v = Math.abs(Math.sin(ts * 4 + i * 0.5) * Math.sin(ts * 1.3 + i * 0.2)); double x = W * 0.1 + i * W * 0.8 / n; if (key.equals("waveLine")) { if (i > 0) ln(g, x - W * 0.8 / n, Bt + 40 + Math.sin(ts * 3 + (i - 1) * 0.5) * 10, x, Bt + 40 + Math.sin(ts * 3 + i * 0.5) * 10); } else ln(g, x, Bt + 40 - v * 18, x, Bt + 40 + v * 18); } }
            case "barcode" -> { g.setColor(c(F, 0.7 * al)); double x = L; for (int i = 0; i < 28; i++) { double w2 = 1 + h(seed, i) * 3; g.fill(new Rectangle2D.Double(x, Bt + 16, w2, 16)); x += w2 + 1.5; } }
            case "counter", "indexNum", "likeCounter", "tally" -> { g.setColor(c(A, 0.9 * al)); txt(g, key.equals("tally") ? "||||" : String.format("%03d", (int) (seed % 900) + (int) ts), R + 12, T + 10, 12, true); }
            case "crosshair", "reticle", "radar", "progressRing", "sonarArcs", "atomOrbit", "beatRing", "rings", "concentricSquares", "spinner" -> { double r = Math.max(30, Math.hypot(bw, bh) * 0.4); g.setColor(c(A, 0.6 * al)); if (key.equals("concentricSquares")) { for (int i = 1; i <= 3; i++) g.draw(new Rectangle2D.Double(cx - r * i / 2, cy - r * i / 2 * 0.6, r * i, r * i * 0.6)); } else if (key.equals("progressRing") || key.equals("spinner")) { g.setStroke(new BasicStroke(3f)); g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, ts * 200, key.equals("spinner") ? 270 : 360 * ((ts * 0.3) % 1), Arc2D.OPEN)); } else if (key.equals("beatRing")) { double ph = (ts * 2) % 1; g.setColor(c(A, (1 - ph) * 0.7 * al)); g.draw(new Ellipse2D.Double(cx - r * (0.6 + ph), cy - r * (0.6 + ph), 2 * r * (0.6 + ph), 2 * r * (0.6 + ph))); } else if (key.equals("atomOrbit")) { for (int i = 0; i < 3; i++) { var old = g.getTransform(); g.rotate(i * Math.PI / 3 + ts * 0.5, cx, cy); g.draw(new Ellipse2D.Double(cx - r, cy - r * 0.35, 2 * r, r * 0.7)); g.setTransform(old); } } else { g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)); ln(g, cx - r - 8, cy, cx + r + 8, cy); ln(g, cx, cy - r - 8, cx, cy + r + 8); if (key.equals("radar")) { g.setColor(c(A, 0.3 * al)); g.fill(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, -ts * 120, 40, Arc2D.PIE)); } if (key.equals("sonarArcs")) for (int i = 1; i < 4; i++) g.draw(new Ellipse2D.Double(cx - r - i * 10, cy - r - i * 10, 2 * (r + i * 10), 2 * (r + i * 10))); } }
            case "slash", "lineBurst", "speedCorner", "starburst", "sunRays" -> { g.setColor(c(A, 0.7 * al)); int n = key.equals("slash") ? 2 : 14; for (int i = 0; i < n; i++) { if (key.equals("slash")) { ln(g, L - 40, Bt + 14 + i * 8, L - 40 + (bw + 80) * k, Bt + 14 + i * 8 - 14); continue; } double an = i * Math.PI * 2 / n; ln(g, cx + Math.cos(an) * (Math.max(bw, bh) * 0.55 + 10), cy + Math.sin(an) * (bh * 0.8 + 10), cx + Math.cos(an) * (Math.max(bw, bh) * 0.55 + 28 * k), cy + Math.sin(an) * (bh * 0.8 + 28 * k)); } }
            case "glitchRects" -> { for (int i = 0; i < 5; i++) { if (h(step, i) > 0.5) continue; g.setColor(c(i % 2 == 0 ? A : B, 0.55 * al)); g.fill(new Rectangle2D.Double(cx + (h(step, i + 10) - 0.5) * bw * 1.4, cy + (h(step, i + 20) - 0.5) * bh * 1.4, 10 + h(step, i + 30) * 50, 3 + h(step, i + 40) * 8)); } }
            case "triangleSpin", "constellation" -> { g.setColor(c(A, 0.6 * al)); if (key.equals("triangleSpin")) { var old = g.getTransform(); g.rotate(ts * 0.6, R + 40, T - 10); Path2D p = new Path2D.Double(); p.moveTo(R + 40, T - 30); p.lineTo(R + 58, T + 2); p.lineTo(R + 22, T + 2); p.closePath(); g.draw(p); g.setTransform(old); } else { double px = 0, py = 0; for (int i = 0; i < 7; i++) { double x = h(seed, i) * W, y = h(seed, i + 9) * H; g.fill(new Ellipse2D.Double(x - 2, y - 2, 4, 4)); if (i > 0) { g.setColor(c(A, 0.25 * al)); ln(g, px, py, x, y); g.setColor(c(A, 0.6 * al)); } px = x; py = y; } } }
            case "checkerStrip" -> { for (int i = 0; i < 20; i++) { g.setColor(c(i % 2 == 0 ? F : A, 0.5 * al)); g.fill(new Rectangle2D.Double(i * 10, H - 10, 10, 10)); } }
            case "dateStamp", "seal", "kamon", "qrBlock", "watermarkKanji", "romajiLine", "colophon", "tyColophon", "headingTape", "glyphLock", "indexTabs", "swatches" -> { g.setColor(c(B, 0.75 * al)); if (key.equals("qrBlock")) { for (int j = 0; j < 9; j++) for (int i = 0; i < 9; i++) if (h(seed, i * 9 + j) > 0.5) g.fill(new Rectangle2D.Double(W - 60 + i * 4, H - 60 + j * 4, 4, 4)); } else if (key.equals("seal") || key.equals("kamon")) { g.setStroke(new BasicStroke(2f)); g.draw(new Ellipse2D.Double(W - 70, H - 70, 44, 44)); txt(g, "印", W - 62, H - 40, 22, true); } else if (key.equals("swatches")) { Color[] cs = {sc.bg(), A, B, F, D}; for (int i = 0; i < 5; i++) { g.setColor(cs[i]); g.fill(new Rectangle2D.Double(20 + i * 14, H - 28, 12, 12)); } } else if (key.equals("watermarkKanji")) { g.setColor(c(A, 0.1 * al)); txt(g, "歌", W * 0.62, H * 0.9, 260, true); } else { txt(g, key.equals("dateStamp") ? "2026.11.05" : key.equals("romajiLine") ? "RAINIUM / LYRICS" : "VOL. " + (seed % 9), key.equals("romajiLine") ? L : W - 120, key.equals("dateStamp") ? T - 12 : H - 22, 11, true); } }
            case "scribbleUnder", "scribbleCircle", "crossOut", "highlightMark", "brushStroke", "tapePieces", "punchHoles", "staple", "paperClip", "registration", "ruledLines" -> { g.setColor(c(A, 0.75 * al)); g.setStroke(new BasicStroke(key.equals("brushStroke") ? 8f : 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); Path2D p = new Path2D.Double(); switch (key) {
                case "scribbleUnder" -> { p.moveTo(L, Bt + 8); for (double x = L; x < L + bw * k; x += 12) p.lineTo(x, Bt + 8 + ((int) (x / 12) % 2 == 0 ? 0 : 6)); g.draw(p); }
                case "scribbleCircle" -> g.draw(new Ellipse2D.Double(L - 20, T - 18, bw + 40, bh + 36));
                case "crossOut" -> { ln(g, L, T, R, Bt); ln(g, L, Bt, R, T); }
                case "highlightMark" -> { g.setColor(c(A, 0.3 * al)); g.fill(new Rectangle2D.Double(L - 6, cy, bw * k + 12, bh * 0.4)); }
                case "brushStroke" -> { g.setColor(c(A, 0.3 * al)); ln(g, L - 30, Bt + 6, L - 30 + (bw + 60) * k, Bt - 4); }
                case "tapePieces" -> { for (int i = 0; i < 2; i++) { var old = g.getTransform(); g.rotate(i == 0 ? -0.5 : 0.5, i == 0 ? L : R, T); g.setColor(c(F, 0.35 * al)); g.fill(new Rectangle2D.Double((i == 0 ? L : R) - 16, T - 6, 32, 12)); g.setTransform(old); } }
                case "punchHoles" -> { g.setColor(c(D, 0.6 * al)); for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Double(14, H * (0.25 + 0.25 * i), 9, 9)); }
                case "staple" -> { g.setColor(c(F, 0.6 * al)); ln(g, 20, 20, 44, 28); }
                case "paperClip" -> { g.draw(new Rectangle2D.Double(W - 40, 8, 10, 40)); g.draw(new Rectangle2D.Double(W - 36, 14, 10, 40)); }
                case "registration" -> { for (double[] q : new double[][]{{14, 14}, {W - 14, 14}, {14, H - 14}, {W - 14, H - 14}}) { g.draw(new Ellipse2D.Double(q[0] - 5, q[1] - 5, 10, 10)); ln(g, q[0] - 9, q[1], q[0] + 9, q[1]); ln(g, q[0], q[1] - 9, q[0], q[1] + 9); } }
                default -> { g.setColor(c(D, 0.3 * al)); for (int y = 40; y < H; y += 24) ln(g, 0, y, W, y); }
            } }
            case "seigaiha", "asanoha", "hexGrid", "namiGashira", "kasumi", "shimenawa", "sensu", "chochin", "tsukiKumo" -> { g.setColor(c(A, 0.3 * al)); if (key.equals("tsukiKumo")) { g.fill(new Ellipse2D.Double(W * 0.8, 18, 38, 38)); g.setColor(c(sc.bg(), 0.9 * al)); g.fill(new Ellipse2D.Double(W * 0.8 + 10, 14, 36, 36)); } else if (key.equals("chochin")) { g.setColor(c(B, 0.8 * al)); g.fill(new Ellipse2D.Double(W - 60, 10 + Math.sin(ts) * 2, 30, 40)); ln(g, W - 45, 0, W - 45, 10); } else if (key.equals("kasumi")) { g.setColor(c(F, 0.1 * al)); for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Double(((ts * 6 + i * 270) % (W + 200)) - 100, H * (0.65 + 0.1 * i), 240, 24)); } else if (key.equals("sensu")) { for (int i = 0; i < 7; i++) { double an = Math.PI * (1.15 + i * 0.11); ln(g, 40, H - 20, 40 + Math.cos(an) * 60, H - 20 + Math.sin(an) * 60); } } else { double s = 22; for (double x = 0; x < W; x += s) g.draw(new Arc2D.Double(x, H - s * 1.2, s, s, 0, 180, Arc2D.OPEN)); } }
            case "vines", "rainRipples", "lightLeak2", "cursorClick", "windowChrome", "progressBar", "toggleSwitch", "notifBell", "mediaControls" -> { g.setColor(c(A, 0.75 * al)); switch (key) {
                case "windowChrome" -> { g.draw(new Rectangle2D.Double(L - 24, T - 28, bw + 48, bh + 56)); g.fill(new Ellipse2D.Double(L - 18, T - 22, 6, 6)); g.fill(new Ellipse2D.Double(L - 8, T - 22, 6, 6)); }
                case "progressBar" -> { g.draw(new Rectangle2D.Double(L, Bt + 18, bw, 5)); g.fill(new Rectangle2D.Double(L, Bt + 18, bw * ((ts * 0.2) % 1), 5)); }
                case "toggleSwitch" -> { g.draw(new Rectangle2D.Double(W - 60, 16, 32, 16)); g.fill(new Ellipse2D.Double(W - 42 + (step % 24 < 12 ? 0 : -16), 18, 12, 12)); }
                case "mediaControls" -> { double y = H - 28; Path2D p = new Path2D.Double(); p.moveTo(cx - 6, y); p.lineTo(cx + 8, y + 8); p.lineTo(cx - 6, y + 16); p.closePath(); g.fill(p); }
                case "cursorClick" -> { double x = R + 8 + Math.sin(ts * 3) * 4, y = Bt + 6; Path2D p = new Path2D.Double(); p.moveTo(x, y); p.lineTo(x + 10, y + 24); p.lineTo(x + 14, y + 14); p.lineTo(x + 24, y + 10); p.closePath(); g.fill(p); }
                case "notifBell" -> { g.draw(new Arc2D.Double(W - 40, 14, 20, 24, 0, 180, Arc2D.OPEN)); g.fill(new Ellipse2D.Double(W - 33, 38, 6, 5)); }
                default -> { for (int i = 0; i < 3; i++) { Path2D p = new Path2D.Double(); p.moveTo(10 + i * 6, H); for (int y = 0; y < 120; y += 10) p.lineTo(10 + i * 6 + Math.sin(y * 0.15 + i) * 6, H - y); g.draw(p); } }
            } }
            case "circuit", "ruledLines2", "zigzagRibbon", "dandelion2" -> { g.setColor(c(A, 0.5 * al)); Path2D p = new Path2D.Double(); p.moveTo(0, T); double x = 0, y = T; while (x < W * k) { x += 24; p.lineTo(x, y); y += (h(seed, (int) x) > 0.5 ? 14 : -14); p.lineTo(x, y); } g.draw(p); }
            case "moonPhases" -> { for (int i = 0; i < 5; i++) { g.setColor(c(F, 0.5 * al)); g.fill(new Ellipse2D.Double(20 + i * 18, 20, 12, 12)); g.setColor(c(sc.bg(), 0.9 * al)); g.fill(new Ellipse2D.Double(20 + i * 18 + (i - 2) * 3, 20, 12, 12)); } }
            case "spectrum", "tyTypeScale", "tyBigPunct", "tyRunningHead", "tyGlyphBody", "tyTextRule" -> { g.setColor(c(F, 0.6 * al)); if (key.equals("tyBigPunct")) { g.setColor(c(A, 0.2 * al)); txt(g, "“", L - 30, T + 50, 120, true); } else if (key.equals("tyTextRule")) { ln(g, L, T - 14, R, T - 14); ln(g, L, Bt + 14, R, Bt + 14); } else if (key.equals("tyRunningHead")) { txt(g, "RAINIUM — LYRICS", L, 22, 10, false); ln(g, L, 28, R, 28); } else if (key.equals("tyTypeScale")) { for (int i = 0; i < 5; i++) txt(g, "Aa", 16 + i * 26, H - 16, 8 + i * 4, false); } else txt(g, "Aa Bb Cc 123", L, Bt + 24, 9, false); }
            case "hrScratches", "hrCracks", "hrDrips", "hrSigil", "hrWatchEye", "hrStaticPatch", "hrDustBeam" -> { g.setColor(c(A, 0.6 * al)); switch (key) {
                case "hrScratches" -> { for (int i = 0; i < 6; i++) { double x = h(seed, i) * W, y = h(seed, i + 9) * H; ln(g, x, y, x + 40, y + 20 + h(seed, i + 3) * 30); } }
                case "hrCracks" -> { Path2D p = new Path2D.Double(); double x = cx, y = 0; p.moveTo(x, y); while (y < H * 0.8 * k) { x += (h(seed, (int) y) - 0.5) * 30; y += 20; p.lineTo(x, y); } g.draw(p); }
                case "hrDrips" -> { g.setStroke(new BasicStroke(3f)); for (int i = 0; i < 6; i++) { double x = L + i * bw / 5, len = (14 + h(seed, i) * 40) * (0.4 + 0.6 * clamp(lt / 1.5)); ln(g, x, T - 6, x, T - 6 + len); } }
                case "hrSigil" -> { double r = 36; g.draw(new Ellipse2D.Double(W - 80 - r, 70 - r, 2 * r, 2 * r)); star(g, W - 80, 70, r, 5, false); }
                case "hrWatchEye" -> { double ex = W * 0.85, ey = H * 0.18; g.draw(new Arc2D.Double(ex - 30, ey - 14, 60, 28, 0, 180, Arc2D.OPEN)); g.draw(new Arc2D.Double(ex - 30, ey - 14, 60, 28, 180, 180, Arc2D.OPEN)); g.fill(new Ellipse2D.Double(ex - 6 + Math.sin(ts) * 8, ey - 6, 12, 12)); }
                case "hrStaticPatch" -> { for (int i = 0; i < 60; i++) { g.setColor(c(F, h(step, i) * 0.4 * al)); g.fill(new Rectangle2D.Double(W * 0.7 + h(step, i + 60) * 90, H * 0.6 + h(step, i + 120) * 70, 4, 2)); } }
                default -> { g.setPaint(new java.awt.GradientPaint((float) (W * 0.7), 0, c(F, 0.2 * al), (float) (W * 0.4), H, c(F, 0))); Path2D p = new Path2D.Double(); p.moveTo(W * 0.65, 0); p.lineTo(W * 0.75, 0); p.lineTo(W * 0.45, H); p.lineTo(W * 0.3, H); p.closePath(); g.fill(p); }
            } }
            case "knSpeedTrail", "knWordTicks" -> { g.setColor(c(A, 0.7 * al)); for (int i = 0; i < 8; i++) { double y = T + i * bh / 8; ln(g, L - 20 - h(seed, i) * 50, y, L - 8, y); } }
            case "dataColumns2" -> { }
            default -> { }
        }
    }
}
