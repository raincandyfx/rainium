package io.github.raincandyfx.rainium;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;

/** The arithmetic and drawing behind the Scatter mode (LyricsScatter), kept free of the game so the tests can run it. */
final class LyricsWords {
    static final int ROW_W = 768, ROW_H = 96;
    static final double FADE = 0.55, MIN_LIFE = 1.2, MAX_LIFE = 3.5, TAIL = 0.7;

    /** Where a word is up and how much of the view it covers (angles in degrees, times in song seconds). */
    record Spot(double t, double fadeAt, double yawDeg, double pitchDeg, double halfWDeg, double halfHDeg) {}

    private LyricsWords() {}

    /** The centre, in degrees from the middle of the view, of the column for word [index]: four across, alternately above and below the middle. */
    static double[] column(int index, double spanH, double spanV) {
        return new double[]{-spanH + (index % 4) * (2 * spanH / 3), (index % 2 == 0 ? 0.5 : -0.3) * spanV};
    }

    /** Degrees of clear view between a spot and the nearest word up at the same time (6 when none). */
    static double gap(double yaw, double pitch, double halfW, double halfH, java.util.List<Spot> others, double from, double until) {
        double gap = 6.0;
        for (Spot p : others) {
            if (p.fadeAt() + FADE < from || p.t() > until) continue;
            double dy = Math.abs(p.yawDeg() - yaw);
            if (dy > 180) dy = 360 - dy;
            double dp = Math.abs(p.pitchDeg() - pitch);
            gap = Math.min(gap, Math.max(dy - (halfW + p.halfWDeg()), dp - (halfH + p.halfHDeg())));
        }
        return gap;
    }

    /** Candidate quality: roomy is good (up to 6°), far from its column is a little bad. */
    static double score(double gap, double dh, double dv, double baseH, double baseV) {
        return Math.min(gap, 6.0) * 10.0 - Math.hypot(dh - baseH, dv - baseV) * 0.3;
    }

    /** When a word goes: its own time and held-until, at least [MIN_LIFE] seconds, at most [MAX_LIFE]. */
    static double fadeAt(LyricsLrc.Word w) {
        return Math.min(w.t() + MAX_LIFE, Math.max(w.t() + MIN_LIFE, w.end() + TAIL));
    }

    static float easeOutCubic(float t) { return 1f - (1f - t) * (1f - t) * (1f - t); }

    static float easeOutBack(float t, float s) {
        float u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    /** The unit vector a view direction points in (Minecraft's yaw and pitch, in degrees). */
    static double[] direction(double pitchDeg, double yawDeg) {
        double p = Math.toRadians(pitchDeg), y = Math.toRadians(yawDeg);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /** One row of the atlas: the word with a dark edge, centred. Returns the ink's left and right edge as 0..1 across the row. */
    static float[] drawWord(Graphics2D g, String word, int row, int rgb) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        FontRenderContext frc = g.getFontRenderContext();
        Font f = new Font(Font.SANS_SERIF, Font.BOLD, 64);
        double w = f.getStringBounds(word, frc).getWidth();
        double room = ROW_W - 28;
        if (w > room) f = f.deriveFont((float) (f.getSize2D() * room / w));
        TextLayout tl = new TextLayout(word, f, frc);
        double tw = tl.getBounds().getWidth(), x = (ROW_W - tw) / 2 - tl.getBounds().getX(), y = row * ROW_H + ROW_H * 0.70;
        Shape s = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
        g.setColor(new Color(0, 0, 0, 170));
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(s);
        g.setColor(new Color(rgb));
        g.fill(s);
        double pad = 8;
        return new float[]{(float) Math.max(0, (ROW_W - tw) / 2 - pad) / ROW_W, (float) Math.min(ROW_W, (ROW_W + tw) / 2 + pad) / ROW_W};
    }

    static int colorOf(String hex) {
        if (hex != null && hex.matches("#?[0-9a-fA-F]{6}")) return Integer.parseInt(hex.replace("#", ""), 16);
        return 0xFFFFFF;
    }

    /** Half width and half height in blocks of a word at a distance: a set share of the view's height, narrowed to fit the view. */
    static double[] wordSize(double sizeK, double halfAspect, double dist, double tanH, double tanV, boolean keepOnScreen) {
        double halfH = 0.075 * sizeK * tanV * dist, halfW = halfH * 2 * halfAspect;
        double limit = 0.76 * tanH * dist;   // 76% of the width of the view
        if (keepOnScreen && halfW > limit) { halfH *= limit / halfW; halfW = limit; }
        return new double[]{halfW, halfH};
    }
}
