package io.github.raincandyfx.rainium;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;

import static io.github.raincandyfx.rainium.LyricKit.clamp;

/** Screen effects (quick hits on the whole picture) and the transitions into a cut. Both work on the finished layers (condensed re-creations). */
final class LyricFx {
    private LyricFx() {}

    private static double h(long a, int b) { double x = Math.sin(a * 12.9898 + b * 78.233) * 43758.5453; return x - Math.floor(x); }

    private static BufferedImage copy(BufferedImage s) {
        BufferedImage o = new BufferedImage(s.getWidth(), s.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(s, 0, 0, null);
        g.dispose();
        return o;
    }

    private static void clear(BufferedImage i) {
        Graphics2D g = i.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, i.getWidth(), i.getHeight());
        g.dispose();
    }

    private static Graphics2D gfx(BufferedImage i) {
        Graphics2D g = i.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    /** Redraws the layer through a transform (about the picture centre cx, cy). */
    private static void warp(BufferedImage img, AffineTransform t) {
        BufferedImage src = copy(img);
        clear(img);
        Graphics2D g = gfx(img);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, t, null);
        g.dispose();
    }

    private static AffineTransform about(double cx, double cy, double sx, double sy, double rot, double tx, double ty, double shx) {
        AffineTransform t = AffineTransform.getTranslateInstance(cx + tx, cy + ty);
        if (rot != 0) t.rotate(rot);
        if (shx != 0) t.shear(shx, 0);
        t.scale(sx, sy);
        t.translate(-cx, -cy);
        return t;
    }

    /** Shifts horizontal bands of the picture sideways: amp(row band index) in pixels. */
    private static void bands(BufferedImage img, int y0, int y1, int bandH, long seed, double amp, double prob) {
        BufferedImage src = copy(img);
        Graphics2D g = gfx(img);
        g.setComposite(AlphaComposite.Src);
        for (int y = y0, k = 0; y < y1; y += bandH, k++) {
            if (h(seed, k) > prob) continue;
            int dx = (int) ((h(seed, k + 100) - 0.5) * 2 * amp);
            g.setClip(0, y, img.getWidth(), Math.min(bandH, y1 - y));
            g.drawImage(src, dx, 0, null);
        }
        g.dispose();
    }

    private static void pixelate(BufferedImage img, int block) {
        int w = Math.max(1, img.getWidth() / block), hh = Math.max(1, img.getHeight() / block);
        BufferedImage small = new BufferedImage(w, hh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, hh, null);
        g.dispose();
        clear(img);
        Graphics2D g2 = img.createGraphics();
        g2.drawImage(small, 0, 0, img.getWidth(), img.getHeight(), null);
        g2.dispose();
    }

    private static void blur(BufferedImage img, int r) {
        int n = r * 2 + 1;
        float[] k = new float[n * n];
        java.util.Arrays.fill(k, 1f / (n * n));
        BufferedImage o = new ConvolveOp(new Kernel(n, n, k), ConvolveOp.EDGE_NO_OP, null).filter(copy(img), null);
        clear(img);
        Graphics2D g = img.createGraphics();
        g.drawImage(o, 0, 0, null);
        g.dispose();
    }

    private interface Px { int f(int argb, int x, int y); }

    private static void pixels(BufferedImage img, Px f) {
        int w = img.getWidth(), hh = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < hh; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            boolean any = false;
            for (int x = 0; x < w; x++) { int v = row[x]; if ((v >>> 24) != 0) { row[x] = f.f(v, x, y); any = true; } }
            if (any) img.setRGB(0, y, w, 1, row, 0, w);
        }
    }

    private static int rgb(int a, int r, int g, int b) { return (a << 24) | (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b)); }

    private static void split(BufferedImage img, double dx, double dy) {
        BufferedImage src = copy(img);
        clear(img);
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        BufferedImage r = tint(src, 0xFFFF2040), b = tint(src, 0xFF2090FF);
        g.drawImage(r, (int) -dx, (int) -dy, null);
        g.drawImage(b, (int) dx, (int) dy, null);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(src, 0, 0, null);
        g.dispose();
    }

    private static BufferedImage tint(BufferedImage s, int argb) {
        BufferedImage o = copy(s);
        pixels(o, (v, x, y) -> rgb((v >>> 24) * 7 / 10, (argb >> 16) & 255, (argb >> 8) & 255, argb & 255));
        return o;
    }

    private static void overlay(BufferedImage img, Color c, double a, int x, int y, int w, int hh) {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(a)));
        g.setColor(c);
        g.fillRect(x, y, w, hh);
        g.dispose();
    }

    private static void maskTo(BufferedImage img, Shape keep) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        BufferedImage m = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = gfx(m);
        mg.setColor(Color.WHITE);
        mg.fill(keep);
        mg.dispose();
        g.setComposite(AlphaComposite.DstIn);
        g.drawImage(m, 0, 0, null);
        g.dispose();
    }

    /** Redraws a layer through a transform. */
    static void transform(BufferedImage img, AffineTransform t) { warp(img, t); }

    /** A screen effect on both layers. p runs 0..1 over the effect's short window. px, py: where the picture starts in the layer; w, hh: its size. */
    static void apply(String key, BufferedImage front, BufferedImage back, double p, long step, int px, int py, int w, int hh, Color a, Color b) {
        if (key == null || key.isEmpty()) return;
        double e = 1 - p, cx = px + w / 2.0, cy = py + hh / 2.0;
        long sd = step;
        for (BufferedImage img : new BufferedImage[]{front, back}) {
            switch (key) {
                case "slice", "sliceShift", "shatter", "tileShift", "splitSlide", "interlace" -> bands(img, py, py + hh, key.equals("interlace") ? 3 : Math.max(6, hh / 12), sd, w * 0.08 * e, key.equals("interlace") ? 0.5 : 0.7);
                case "block", "macroBlock", "mosaic", "gridRepeat", "pixelDrift" -> pixelate(img, key.equals("mosaic") ? 10 : 6 + (int) (e * 12));
                case "invert", "negativeRing" -> pixels(img, (v, x, y) -> { if (key.equals("negativeRing") && Math.hypot(x - cx, y - cy) > hh * (0.1 + 0.9 * p)) return v; return rgb(v >>> 24, 255 - ((v >> 16) & 255), 255 - ((v >> 8) & 255), 255 - (v & 255)); });
                case "bandInvert" -> { int y0 = py + (int) (p * hh); pixels(img, (v, x, y) -> (y > y0 && y < y0 + hh / 6) ? rgb(v >>> 24, 255 - ((v >> 16) & 255), 255 - ((v >> 8) & 255), 255 - (v & 255)) : v); }
                case "zoom", "zoomPunch", "crashZoom", "bulge", "zoomStutter", "heartbeat" -> warp(img, about(cx, cy, 1 + 0.18 * e * (key.equals("zoomStutter") ? (step % 2) : 1), 1 + 0.18 * e * (key.equals("zoomStutter") ? (step % 2) : 1), 0, 0, 0, 0));
                case "squash" -> warp(img, about(cx, cy, 1 + 0.12 * e, 1 - 0.12 * e, 0, 0, 0, 0));
                case "rotateSnap" -> warp(img, about(cx, cy, 1, 1, 0.12 * e * (step % 2 == 0 ? 1 : -1), 0, 0, 0));
                case "perspectiveTilt" -> warp(img, about(cx, cy, 1, 1, 0, 0, 0, 0.18 * e));
                case "shake", "earthquake" -> warp(img, about(cx, cy, 1, 1, (h(sd, 1) - 0.5) * 0.04 * e, (h(sd, 2) - 0.5) * w * 0.05 * e, (h(sd, 3) - 0.5) * hh * 0.05 * e, 0));
                case "whipBlur", "smear", "echoFrames", "vhsRoll" -> { BufferedImage s = copy(img); Graphics2D g = gfx(img); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f * (float) e)); int n = 4; for (int k = 1; k <= n; k++) g.drawImage(s, (int) (k * w * 0.02 * e * (key.equals("vhsRoll") ? 0 : 1)), (int) (key.equals("vhsRoll") ? k * hh * 0.03 * e : 0), null); g.dispose(); }
                case "chroma", "rgbSplit", "radialChroma" -> split(img, w * 0.012 * e + 1, key.equals("radialChroma") ? hh * 0.006 * e : 0);
                case "halftone", "ditherBit", "posterize", "duotone", "hueShift" -> pixels(img, (v, x, y) -> {
                    int r = (v >> 16) & 255, gg = (v >> 8) & 255, bb = v & 255;
                    switch (key) {
                        case "posterize" -> { return rgb(v >>> 24, r / 64 * 64, gg / 64 * 64, bb / 64 * 64); }
                        case "ditherBit" -> { int lum = (r + gg + bb) / 3; boolean on = lum > ((x & 1) * 2 + (y & 1) * 3) * 40 + 20; return on ? rgb(v >>> 24, 255, 255, 255) : rgb(v >>> 24, 0, 0, 0); }
                        case "duotone" -> { int lum = (r + gg + bb) / 3; return rgb(v >>> 24, (a.getRed() * lum + b.getRed() * (255 - lum)) / 255, (a.getGreen() * lum + b.getGreen() * (255 - lum)) / 255, (a.getBlue() * lum + b.getBlue() * (255 - lum)) / 255); }
                        case "hueShift" -> { return rgb(v >>> 24, gg, bb, r); }
                        default -> { return ((x / 4 + y / 4) & 1) == 0 ? v : rgb(v >>> 24, r / 2, gg / 2, bb / 2); }
                    }
                });
                case "edgeDetect", "defocus", "focusLines" -> { if (key.equals("defocus")) blur(img, 1 + (int) (e * 3)); else { BufferedImage s = copy(img); Graphics2D g = gfx(img); g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)); for (int dx = -1; dx <= 1; dx += 2) g.drawImage(s, dx, 0, null); g.dispose(); } }
                case "flash", "whiteFrame", "bloomFlash", "strobe", "filmBurn", "lightSweep" -> overlay(img, key.equals("filmBurn") ? a : Color.WHITE, (key.equals("strobe") ? (step % 2 == 0 ? 0.7 : 0) : 0.8 * e), key.equals("lightSweep") ? (int) (px + p * w * 1.2 - w * 0.1) : 0, 0, key.equals("lightSweep") ? w / 8 : img.getWidth(), img.getHeight());
                case "blackFrame", "crtOff" -> overlay(img, Color.BLACK, key.equals("crtOff") ? 0.9 * p : 0.9 * e, 0, key.equals("crtOff") ? (int) (py + hh * 0.5 * p) : 0, img.getWidth(), key.equals("crtOff") ? (int) (hh * (1 - p)) : img.getHeight());
                case "tvStatic", "trackingNoise", "dustScratches", "filmAdvance", "scanBar", "snapshot", "colorBars", "mirrorFlash", "hrSubliminal", "hrSignalLoss", "hrPassingShadow" -> {
                    Graphics2D g = gfx(img);
                    switch (key) {
                        case "tvStatic", "hrSignalLoss" -> { for (int i = 0; i < 400; i++) { g.setColor(new Color(255, 255, 255, (int) (h(sd, i) * 200 * e))); g.fillRect(px + (int) (h(sd, i + 400) * w), py + (int) (h(sd, i + 800) * hh), 3, 1); } }
                        case "trackingNoise" -> { for (int i = 0; i < 6; i++) { g.setColor(new Color(255, 255, 255, (int) (120 * e))); g.fillRect(px, py + (int) (h(sd, i) * hh), w, 2); } }
                        case "dustScratches" -> { g.setStroke(new BasicStroke(1f)); for (int i = 0; i < 6; i++) { g.setColor(new Color(255, 255, 255, (int) (140 * e))); double x = px + h(sd, i) * w; g.draw(new Line2D.Double(x, py, x + 2, py + hh)); } }
                        case "filmAdvance" -> { g.setColor(new Color(0, 0, 0, 200)); g.fillRect(px, (int) (py + (p - 0.2) * hh * 1.4), w, (int) (hh * 0.08)); }
                        case "scanBar" -> { g.setColor(new Color(255, 255, 255, 90)); g.fillRect(px, (int) (py + p * hh), w, hh / 14); }
                        case "snapshot" -> { g.setColor(new Color(255, 255, 255, (int) (230 * e))); g.setStroke(new BasicStroke(10f)); g.draw(new Rectangle2D.Double(px + 5, py + 5, w - 10, hh - 10)); }
                        case "colorBars" -> { Color[] cs = {Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE}; for (int i = 0; i < 7; i++) { g.setColor(new Color(cs[i].getRed(), cs[i].getGreen(), cs[i].getBlue(), (int) (110 * e))); g.fillRect(px + i * w / 7, py, w / 7 + 1, hh / 4); } }
                        case "mirrorFlash" -> { g.dispose(); warp(img, about(cx, cy, step % 2 == 0 ? -1 : 1, 1, 0, 0, 0, 0)); continue; }
                        case "hrSubliminal" -> { g.setColor(new Color(0, 0, 0, (int) (220 * (step % 3 == 0 ? 1 : 0)))); g.fillRect(px, py, w, hh); }
                        default -> { g.setColor(new Color(0, 0, 0, (int) (160 * e))); g.fill(new Ellipse2D.Double(px + (p - 0.3) * w * 1.5, py - hh * 0.2, w * 0.4, hh * 1.4)); }
                    }
                    g.dispose();
                }
                case "ripple", "waveWarp", "loopScroll" -> { BufferedImage s = copy(img); clear(img); Graphics2D g = gfx(img); g.setComposite(AlphaComposite.Src); for (int y = py; y < py + hh; y += 3) { int dx = key.equals("loopScroll") ? (int) (p * w) : (int) (Math.sin(y * 0.08 + p * 8) * 8 * e); g.setClip(0, y, img.getWidth(), 3); g.drawImage(s, dx, 0, null); if (key.equals("loopScroll")) g.drawImage(s, dx - w, 0, null); } g.dispose(); }
                case "kaleido" -> { BufferedImage s = copy(img); Graphics2D g = gfx(img); g.setComposite(AlphaComposite.SrcOver); g.setClip(0, 0, (int) cx, img.getHeight()); g.translate(cx * 2, 0); g.scale(-1, 1); g.drawImage(s, 0, 0, null); g.dispose(); }
                case "pixelSort" -> bands(img, py, py + hh, 2, sd, w * 0.1 * e, 0.4);
                case "lightRays", "anamorphic", "starGlint", "speedLines" -> { Graphics2D g = gfx(img); g.setColor(new Color(255, 255, 255, (int) (110 * e))); if (key.equals("anamorphic")) g.fillRect(px, (int) cy - 2, w, 4); else if (key.equals("starGlint")) { g.setStroke(new BasicStroke(2f)); g.draw(new Line2D.Double(cx - 80 * e, cy, cx + 80 * e, cy)); g.draw(new Line2D.Double(cx, cy - 80 * e, cx, cy + 80 * e)); } else for (int i = 0; i < 18; i++) { double an = i * Math.PI / 9 + h(sd, i) * 0.2; g.setStroke(new BasicStroke(1.5f)); g.draw(new Line2D.Double(cx + Math.cos(an) * w * 0.25, cy + Math.sin(an) * hh * 0.25, cx + Math.cos(an) * w, cy + Math.sin(an) * w)); } g.dispose(); }
                case "panelWipe", "irisTrans", "doors", "blindsTrans" -> { transition(switch (key) { case "panelWipe" -> "wipe"; case "irisTrans" -> "irisOpen"; case "doors" -> "doorsOpen"; default -> "blinds"; }, img, p, step, px, py, w, hh); }
                default -> { }
            }
        }
    }

    /** The transition into a cut: u runs 0..1 as the new cut is revealed (1 = fully there). Works by cutting away what's not revealed yet. */
    static void transition(String key, BufferedImage img, double u, long step, int px, int py, int w, int hh) {
        if (key == null || key.isEmpty() || u >= 1) return;
        double cx = px + w / 2.0, cy = py + hh / 2.0, e = 1 - u;
        int W = img.getWidth(), H = img.getHeight();
        switch (key) {
            case "wipe", "tyRuleWipe", "cover", "uncover" -> maskTo(img, new Rectangle2D.Double(px - 4, 0, (w + 8) * u + 4, H));
            case "diagonalWipe" -> { Path2D q = new Path2D.Double(); double s = (w + hh) * u; q.moveTo(px - 4, py - 4); q.lineTo(px + s, py - 4); q.lineTo(px + s - hh - 8, py + hh + 4); q.lineTo(px - 4, py + hh + 4); q.closePath(); maskTo(img, q); }
            case "clockWipe" -> { Path2D q = new Path2D.Double(); q.moveTo(cx, cy); for (int i = 0; i <= 40; i++) { double an = -Math.PI / 2 + i / 40.0 * u * Math.PI * 2; q.lineTo(cx + Math.cos(an) * w, cy + Math.sin(an) * w); } q.closePath(); maskTo(img, q); }
            case "irisOpen", "inkBlob" -> { double r = Math.hypot(w, hh) * 0.55 * u; Area a = new Area(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)); if (key.equals("inkBlob")) for (int i = 0; i < 7; i++) { double an = i * 0.9, d = r * 0.6 * (0.5 + h(i, 1)); a.add(new Area(new Ellipse2D.Double(cx + Math.cos(an) * d - r * 0.4, cy + Math.sin(an) * d - r * 0.4, r * 0.8, r * 0.8))); } maskTo(img, a); }
            case "pushSlide", "whipPan", "knStripSlam", "knCornerSwing" -> warp(img, AffineTransform.getTranslateInstance(-w * e * (key.equals("whipPan") ? 1.4 : 1) * (key.equals("knCornerSwing") ? 0.5 : 1), key.equals("knStripSlam") ? -hh * e * 0.5 : 0));
            case "zoomThrough" -> { warp(img, about(cx, cy, 1 + 2.5 * e * e, 1 + 2.5 * e * e, 0, 0, 0, 0)); }
            case "doorsOpen", "tyGridCells" -> { Area a = new Area(new Rectangle2D.Double(px, py, w, hh)); a.subtract(new Area(new Rectangle2D.Double(cx - w / 2.0 * e, py, w * e, hh))); maskTo(img, a); }
            case "blinds" -> { Area a = new Area(); int n = 10; for (int i = 0; i < n; i++) a.add(new Area(new Rectangle2D.Double(px, py + i * hh / (double) n, w, hh / (double) n * u))); maskTo(img, a); }
            case "checker", "blockDissolve", "shatterTiles", "hrStaticCut", "knStutterCut" -> { Area a = new Area(); int n = 12, m = 7; for (int j = 0; j < m; j++) for (int i = 0; i < n; i++) { double th = key.equals("checker") ? ((i + j) % 2 == 0 ? 0.0 : 0.5) + 0.0 : h(i * 31 + j, 5); if (u > th * (key.equals("checker") ? 1 : 0.9) + (key.equals("checker") ? 0 : 0.0) + (key.equals("knStutterCut") ? (step % 2) * 0.15 : 0)) a.add(new Area(new Rectangle2D.Double(px + i * w / (double) n, py + j * hh / (double) m, w / (double) n + 1, hh / (double) m + 1))); } maskTo(img, a); }
            case "spinOut" -> warp(img, about(cx, cy, u, u, e * 1.5, 0, 0, 0));
            case "sliceShift" -> bands(img, py, py + hh, hh / 10, step, w * 0.3 * e, 1.0);
            case "cubeTurn" -> warp(img, about(cx, cy, Math.max(0.03, u), 1, 0, 0, 0, 0));
            case "flashCross" -> { overlay(img, Color.WHITE, 0.9 * e, 0, 0, W, H); }
            case "pixelate" -> { pixelate(img, 2 + (int) (e * 18)); }
            case "hrBlink" -> { if (step % 3 != 0 && e > 0.3) { maskTo(img, new Rectangle2D.Double(0, 0, 0, 0)); } }
            default -> { }
        }
    }
}
