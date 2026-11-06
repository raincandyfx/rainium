package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.GL;
import io.github.raincandyfx.rainium.LyricEngine.Scheme;
import io.github.raincandyfx.rainium.LyricKit.Env;
import io.github.raincandyfx.rainium.LyricKit.Item;
import io.github.raincandyfx.rainium.LyricKit.State;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;

import static io.github.raincandyfx.rainium.LyricKit.clamp;

/** Looks for the letters themselves: outlines, shadows, glows, gradients, chrome, neon, karaoke, cut-out letters and so on (condensed re-creations). */
final class LyricTreat {
    private LyricTreat() {}

    private static double h(long a, int b, int c) { double x = Math.sin(a * 12.9898 + b * 78.233 + c * 37.719) * 43758.5453; return x - Math.floor(x); }

    private static void a(Graphics2D g, double al, double k) { g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) clamp(al * k))); }

    private static Shape move(Shape s, double dx, double dy) { return AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(s); }

    private static Shape xf(Shape s, AffineTransform t) { return t.createTransformedShape(s); }

    private static Color hue(float hh) { return Color.getHSBColor(hh - (float) Math.floor(hh), 0.65f, 1f); }

    private static void stroke(Graphics2D g, Shape s, Color c, double w) { g.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(c); g.draw(s); }

    /** Draws the glyph with the treatment; false when this treatment leaves the plain drawing alone. */
    static boolean paint(String key, Graphics2D g, Shape use, Item it, GL gl, State s, double al, Scheme sc, Env env) {
        if (key == null || key.equals("none")) return false;
        java.awt.geom.Rectangle2D b = use.getBounds2D();
        double sz = it.size, lw = Math.max(2, sz * 0.045), t = env.ltb, cx = b.getCenterX(), cy = b.getCenterY();
        Color fg = it.color, acc = sc.accent(), acc2 = sc.accent2(), bg = sc.bg();
        int i = gl.i;
        long step = env.step;
        switch (key) {
            case "hrInkBleed" -> { for (int k = 3; k >= 1; k--) { a(g, al, 0.12); stroke(g, use, fg, lw * k * 0.9); } a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "hrEroded" -> { g.setColor(fg); g.fill(use); var old = g.getClip(); g.clip(use); g.setColor(bg); for (int k = 0; k < 9; k++) g.fill(new Ellipse2D.Double(b.getX() + h(i, k, 1) * b.getWidth(), b.getY() + h(i, k, 2) * b.getHeight(), sz * 0.07, sz * 0.07)); g.setClip(old); }
            case "hrRedact" -> { g.setColor(fg); g.fill(use); if (i % 3 == 1) { a(g, al, 0.95); g.setColor(Color.BLACK); g.fill(new Rectangle2D.Double(b.getX() - 2, cy - sz * 0.14, b.getWidth() + 4, sz * 0.28)); } }
            case "hrDoubleExp" -> { a(g, al, 0.4); g.setColor(acc2); g.fill(move(use, sz * 0.04, -sz * 0.03)); a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "knWordScale" -> { double k = 0.8 + 0.4 * ((i / 3) % 2); g.setColor(fg); g.fill(xf(use, scaleAbout(cx, cy, k, k))); }
            case "knWordPlate" -> { if ((i / 3) % 2 == 0) { g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - sz * 0.06, cy - sz * 0.5, b.getWidth() + sz * 0.12, sz)); g.setColor(bg); } else g.setColor(fg); g.fill(use); }
            case "outline" -> stroke(g, use, fg, lw);
            case "outlineFill" -> { g.setColor(acc2); g.fill(use); stroke(g, use, fg, lw * 1.2); }
            case "doubleOutline" -> { stroke(g, use, acc, lw * 3); stroke(g, use, bg, lw * 1.2); g.setColor(fg); g.fill(use); }
            case "extrude" -> { g.setColor(LyricKit.mix(fg, Color.BLACK, 0.55)); for (int k = 10; k >= 1; k--) g.fill(move(use, k * sz * 0.012, k * sz * 0.012)); g.setColor(fg); g.fill(use); }
            case "longShadow" -> { g.setColor(LyricKit.mix(bg, acc, 0.5)); for (int k = 28; k >= 1; k--) g.fill(move(use, k * sz * 0.012, k * sz * 0.012)); g.setColor(fg); g.fill(use); }
            case "hardShadow" -> { g.setColor(acc); g.fill(move(use, sz * 0.05, sz * 0.05)); g.setColor(fg); g.fill(use); }
            case "softShadow" -> { for (int k = 6; k >= 1; k--) { a(g, al, 0.08); g.setColor(Color.BLACK); g.fill(move(use, sz * 0.012 * k, sz * 0.02 * k)); } a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "glow" -> { for (int k = 5; k >= 1; k--) { a(g, al, 0.1); stroke(g, use, acc, lw * k * 1.2); } a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "marker" -> { a(g, al, 0.55); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - 2, cy + sz * 0.02, b.getWidth() + 4, sz * 0.3)); a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "highlight" -> { a(g, al, 0.9); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - sz * 0.04, cy - sz * 0.46, b.getWidth() + sz * 0.08, sz * 0.92)); a(g, al, 1); g.setColor(LyricCompose.onCol(sc, acc)); g.fill(use); }
            case "underline" -> { g.setColor(fg); g.fill(use); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - 2, b.getMaxY() + sz * 0.05, b.getWidth() + 4, Math.max(2, sz * 0.05))); }
            case "strike" -> { g.setColor(fg); g.fill(use); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - sz * 0.05, cy - sz * 0.02, b.getWidth() + sz * 0.1, Math.max(2, sz * 0.05))); }
            case "boxed" -> { g.setColor(fg); g.fill(use); stroke(g, new Rectangle2D.Double(b.getX() - sz * 0.1, b.getY() - sz * 0.1, b.getWidth() + sz * 0.2, b.getHeight() + sz * 0.2), acc, Math.max(2, sz * 0.03)); }
            case "gradientV" -> { g.setPaint(new GradientPaint(0, (float) b.getY(), fg, 0, (float) b.getMaxY(), acc)); g.fill(use); }
            case "splitColor" -> { var old = g.getClip(); g.clip(use); g.setColor(fg); g.fill(new Rectangle2D.Double(b.getX() - 1, b.getY() - 1, b.getWidth() + 2, b.getHeight() / 2 + 1)); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - 1, cy, b.getWidth() + 2, b.getHeight() / 2 + 1)); g.setClip(old); }
            case "halftone" -> { var old = g.getClip(); g.clip(use); g.setColor(fg); double d = Math.max(3, sz * 0.07); for (double y = b.getY(); y < b.getMaxY(); y += d) for (double x = b.getX() + ((int) (y / d) % 2) * d / 2; x < b.getMaxX(); x += d) { double r = d * 0.5 * (0.35 + 0.65 * (y - b.getY()) / Math.max(1, b.getHeight())); g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r)); } g.setClip(old); }
            case "stripes" -> { var old = g.getClip(); g.clip(use); g.setColor(fg); g.fill(b); g.setColor(acc); double d = Math.max(4, sz * 0.1); for (double y = b.getY(); y < b.getMaxY(); y += d * 2) g.fill(new Rectangle2D.Double(b.getX(), y, b.getWidth(), d)); g.setClip(old); }
            case "hatch" -> { var old = g.getClip(); g.clip(use); g.setStroke(new BasicStroke((float) Math.max(1.5, sz * 0.02))); g.setColor(fg); double d = Math.max(4, sz * 0.06); for (double x = b.getX() - b.getHeight(); x < b.getMaxX(); x += d) g.draw(new Line2D.Double(x, b.getMaxY(), x + b.getHeight(), b.getY())); g.setClip(old); stroke(g, use, fg, lw * 0.6); }
            case "dotted" -> { g.setStroke(new BasicStroke((float) lw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{0.1f, (float) (lw * 2.2)}, 0)); g.setColor(fg); g.draw(use); }
            case "alternate" -> { g.setColor(i % 2 == 0 ? fg : acc); g.fill(use); }
            case "italic" -> { g.setColor(fg); g.fill(xf(use, shearAbout(cx, b.getMaxY(), -0.22, 0))); }
            case "wide" -> { g.setColor(fg); g.fill(xf(use, scaleAbout(cx, cy, 1.35, 1))); }
            case "tall" -> { g.setColor(fg); g.fill(xf(use, scaleAbout(cx, cy, 1, 1.35))); }
            case "echoOutline" -> { for (int k = 3; k >= 1; k--) { a(g, al, 0.7 / k); stroke(g, move(use, 0, 0), k % 2 == 0 ? acc : acc2, lw * (1 + k * 1.6)); } a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "emphasisDots" -> { g.setColor(fg); g.fill(use); g.setColor(acc); g.fill(new Ellipse2D.Double(cx - sz * 0.05, b.getY() - sz * 0.16, sz * 0.1, sz * 0.1)); }
            case "neonOutline" -> { for (int k = 4; k >= 1; k--) { a(g, al, 0.13); stroke(g, use, acc, lw * k * 1.6); } a(g, al, 1); stroke(g, use, LyricKit.mix(acc, Color.WHITE, 0.8), lw * 0.7); }
            case "chrome" -> { var c1 = LyricKit.mix(fg, Color.WHITE, 0.8); g.setPaint(new java.awt.LinearGradientPaint(0, (float) b.getY(), 0, (float) b.getMaxY(), new float[]{0f, 0.48f, 0.52f, 1f}, new Color[]{c1, LyricKit.mix(fg, Color.BLACK, 0.2), LyricKit.mix(acc2, Color.BLACK, 0.45), LyricKit.mix(fg, Color.WHITE, 0.55)})); g.fill(use); stroke(g, use, Color.BLACK, lw * 0.35); }
            case "rainbow" -> { g.setColor(hue((float) (i * 0.09 + t * 0.2))); g.fill(use); }
            case "glitchSplit" -> { double o = sz * 0.035; a(g, al, 0.8); g.setColor(sc.ghostA()); g.fill(move(use, -o, 0)); g.setColor(sc.ghostB()); g.fill(move(use, o, o * 0.3)); a(g, al, 1); g.setColor(fg); g.fill(use); }
            case "shadowStack" -> { for (int k = 4; k >= 1; k--) { g.setColor(LyricKit.mix(acc, bg, k * 0.18)); g.fill(move(use, k * sz * 0.04, k * sz * 0.04)); } g.setColor(fg); g.fill(use); }
            case "stencilGap" -> { g.setColor(fg); g.fill(use); var old = g.getClip(); g.clip(use); g.setColor(bg); g.fill(new Rectangle2D.Double(b.getX() - 1, cy - sz * 0.025, b.getWidth() + 2, sz * 0.05)); g.setClip(old); }
            case "waterline" -> { g.setColor(fg); g.fill(use); var old = g.getClip(); g.clip(use); a(g, al, 0.55); g.setColor(acc2); double wy = cy + sz * 0.05 + Math.sin(t * 3 + i) * sz * 0.03; g.fill(new Rectangle2D.Double(b.getX() - 1, wy, b.getWidth() + 2, b.getMaxY() - wy + 2)); g.setClip(old); }
            case "karaoke" -> { double prog = clamp((env.lt - env.cut.inDur * 0.5) / Math.max(0.5, env.dur() - env.cut.inDur - env.cut.outDur)); double fillX = b.getX() + b.getWidth() * clamp(prog * 12 - i + 1); g.setColor(sc.sub()); g.fill(use); var old = g.getClip(); g.clip(use); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - 1, b.getY() - 1, Math.max(0, fillX - b.getX() + 1), b.getHeight() + 2)); g.setClip(old); }
            case "sizeWave" -> { double k = 1 + 0.25 * Math.sin(t * 3 + i * 0.9); g.setColor(fg); g.fill(xf(use, scaleAbout(cx, b.getMaxY(), k, k))); }
            case "rotateAlt" -> { g.setColor(fg); g.fill(xf(use, AffineTransform.getRotateInstance((i % 2 == 0 ? 1 : -1) * 0.12, cx, cy))); }
            case "baselineShift" -> { g.setColor(fg); g.fill(move(use, 0, (i % 2 == 0 ? -1 : 1) * sz * 0.1)); }
            case "fauxBold" -> { g.setColor(fg); g.fill(use); stroke(g, use, fg, sz * 0.03); }
            case "circled" -> { g.setColor(fg); g.fill(use); stroke(g, new Ellipse2D.Double(cx - sz * 0.5, cy - sz * 0.5, sz, sz), acc, Math.max(2, sz * 0.025)); }
            case "bracketsQuote" -> { g.setColor(fg); g.fill(use); if (i == 0) { g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - sz * 0.22, b.getY(), sz * 0.05, sz * 0.4)); } }
            case "reflection" -> { g.setColor(fg); g.fill(use); a(g, al, 0.3); g.setColor(fg); g.fill(xf(use, new AffineTransform(1, 0, 0, -0.7, 0, b.getMaxY() * 1.7 + sz * 0.1))); }
            case "inline" -> { g.setColor(fg); g.fill(use); stroke(g, use, bg, lw * 0.7); }
            case "sticker" -> { a(g, al, 0.35); g.setColor(Color.BLACK); g.fill(move(use, sz * 0.02, sz * 0.03)); stroke(g, move(use, sz * 0.02, sz * 0.03), Color.BLACK, lw * 2.6); a(g, al, 1); stroke(g, use, Color.WHITE, lw * 2.4); g.setColor(fg); g.fill(use); }
            case "gradientSweep" -> { double x = b.getX() + ((t * 0.5 + i * 0.05) % 1) * b.getWidth() * 3 - b.getWidth(); g.setPaint(new GradientPaint((float) x, 0, fg, (float) (x + b.getWidth() * 2), 0, acc)); g.fill(use); }
            case "kerningWide" -> { g.setColor(fg); g.fill(move(use, (i % 12 - 5.5) * sz * 0.08, 0)); }
            case "monoGrid" -> { g.setColor(fg); g.fill(use); stroke(g, new Rectangle2D.Double(cx - sz * 0.55, cy - sz * 0.55, sz * 1.1, sz * 1.1), acc, Math.max(1.5, sz * 0.015)); }
            case "outlineOffset" -> { stroke(g, move(use, sz * 0.04, sz * 0.04), acc, lw); g.setColor(fg); g.fill(use); }
            case "toneShadow" -> { var old = g.getClip(); Shape sh = move(use, sz * 0.05, sz * 0.05); g.clip(sh); g.setColor(acc); double d = Math.max(3, sz * 0.05); for (double y = b.getY(); y < b.getMaxY() + sz * 0.1; y += d) for (double x = b.getX() + ((int) (y / d) % 2) * d / 2; x < b.getMaxX() + sz * 0.1; x += d) g.fill(new Ellipse2D.Double(x, y, d * 0.5, d * 0.5)); g.setClip(old); g.setColor(fg); g.fill(use); }
            case "fadeChars" -> { a(g, al, clamp(1 - 0.5 * ((i % 12) / 11.0) * (0.5 + 0.5 * Math.sin(t)))); g.setColor(fg); g.fill(use); }
            case "cutShift" -> { var old = g.getClip(); g.clip(new Rectangle2D.Double(b.getX() - sz, b.getY() - sz, b.getWidth() + 2 * sz, cy - b.getY() + sz)); g.setColor(fg); g.fill(move(use, -sz * 0.06, 0)); g.setClip(old); g.clip(new Rectangle2D.Double(b.getX() - sz, cy, b.getWidth() + 2 * sz, b.getMaxY() - cy + sz)); g.fill(move(use, sz * 0.06, 0)); g.setClip(old); }
            case "focusPull" -> { double f = Math.abs(Math.sin(t * 0.8 + i * 0.3)); for (int k = 3; k >= 1; k--) { a(g, al, 0.1 * f); stroke(g, use, fg, lw * k); } a(g, al, 1 - 0.3 * f); g.setColor(fg); g.fill(use); }
            case "spotChar" -> { g.setColor(i == (int) (t * 3) % 8 ? acc : fg); g.fill(use); }
            case "ransom" -> { Color[] pal = {Color.WHITE, acc, acc2, LyricKit.mix(bg, Color.WHITE, 0.7), Color.BLACK}; Color p = pal[(int) (h(i, 3, 1) * pal.length)]; double rot = (h(i, 4, 1) - 0.5) * 0.3; var old = g.getTransform(); g.rotate(rot, cx, cy); g.setColor(p); g.fill(new Rectangle2D.Double(cx - sz * 0.5, cy - sz * 0.55, sz, sz * 1.1)); g.setTransform(old); g.setColor(LyricCompose.onCol(sc, p)); g.fill(AffineTransform.getRotateInstance(rot, cx, cy).createTransformedShape(use)); }
            case "tyHollowKey" -> { if (i == 0) stroke(g, use, fg, lw); else { g.setColor(fg); g.fill(use); } }
            case "tyHeadRules" -> { g.setColor(fg); g.fill(use); g.setColor(acc); g.fill(new Rectangle2D.Double(b.getX() - 2, b.getY() - sz * 0.12, b.getWidth() + 4, Math.max(2, sz * 0.025))); g.fill(new Rectangle2D.Double(b.getX() - 2, b.getMaxY() + sz * 0.1, b.getWidth() + 4, Math.max(2, sz * 0.025))); }
            case "tyHeadBig" -> { double k = i == 0 ? 1.5 : 1; g.setColor(i == 0 ? acc : fg); g.fill(xf(use, scaleAbout(cx, b.getMaxY(), k, k))); }
            case "tyIndexSup" -> { g.setColor(fg); g.fill(use); g.setColor(acc); g.setStroke(new BasicStroke(1f)); g.fill(new Rectangle2D.Double(b.getMaxX() - sz * 0.05, b.getY() - sz * 0.14, sz * 0.12, sz * 0.12)); }
            default -> { return false; }
        }
        return true;
    }

    private static AffineTransform scaleAbout(double cx, double cy, double sx, double sy) { AffineTransform t = AffineTransform.getTranslateInstance(cx, cy); t.scale(sx, sy); t.translate(-cx, -cy); return t; }

    private static AffineTransform shearAbout(double cx, double cy, double shx, double shy) { AffineTransform t = AffineTransform.getTranslateInstance(cx, cy); t.shear(shx, shy); t.translate(-cx, -cy); return t; }
}
