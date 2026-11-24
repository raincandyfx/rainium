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
import java.util.ArrayList;
import java.util.List;

/** The arithmetic behind the Scatter mode (LyricsScatter, and Scatter by whole line in Lyrics), kept free of the game so the tests can run it. */
final class LyricsWords {
    static final double FADE = 0.6;
    // the Words style: one row of the word picture per word, and how long a word stays
    static final int ROW_W = 768, ROW_H = 96;
    static final double MIN_LIFE = 1.2, MAX_LIFE = 3.5, TAIL = 0.7;

    /** Scatter's colours: a warm peach-cream (as in Lyrics+), white, your text colour, a slow rainbow, a soft colour per line, or a gradient. */
    static final int COLOR_PEACH = 0, COLOR_WHITE = 1, COLOR_CUSTOM = 2, COLOR_RAINBOW = 3, COLOR_PER_LINE = 4, COLOR_GRADIENT = 5;
    static final String[] COLOR_KEYS = {"peach", "white", "custom", "rainbow", "per_line", "gradient"};
    static final int PEACH = 0xFFE4C4;

    /** Gradients: ready-made pairs of colours, or your own two (the last). */
    static final String[] GRADIENT_KEYS = {"sunset", "ocean", "aurora", "candy", "fire", "gold", "custom"};
    static final int[][] GRADIENTS = {
        {0xFFC371, 0xFF5F8D},   // sunset: apricot to pink
        {0x8FE3FF, 0x4A7BFF},   // ocean: sky to deep blue
        {0x7DF5B5, 0xB98CFF},   // aurora: green to violet
        {0xFFB8DE, 0xA9E4FF},   // candy: pink to baby blue
        {0xFFE45C, 0xFF5340},   // fire: yellow to red
        {0xFFF3C4, 0xE0A43A},   // gold: cream to gold
    };
    /** Which way a gradient runs: across each row, down the rows, along the whole line, or a shimmer that sweeps along. */
    static final int DIR_ACROSS = 0, DIR_DOWN = 1, DIR_LINE = 2, DIR_SHIMMER = 3;
    static final String[] DIR_KEYS = {"across", "down", "line", "shimmer"};

    /** Scatter's fonts: Minecraft's own, its smooth Unifont, or one bundled with Rainium (assets/rainium/font/scatter_*.json). */
    static final String[] FONT_KEYS = {"minecraft", "unifont", "clean", "serif", "mono", "script", "tall", "retro"};

    /** The font id for a Scatter font (null for Minecraft's default). */
    static String fontId(int font) {
        int f = Math.floorMod(font, FONT_KEYS.length);
        return f == 0 ? null : f == 1 ? "minecraft:uniform" : "rainium:scatter_" + FONT_KEYS[f];
    }

    /** The bundled font file for a Scatter font, for the Words style's pictures (null for the two built into the game). */
    static String fontFile(int font) {
        int f = Math.floorMod(font, FONT_KEYS.length);
        return f < 2 ? null : "/assets/rainium/font/scatter_" + FONT_KEYS[f] + ".ttf";
    }

    /** The two ends of the chosen gradient (custom: your two colours, with pleasant ones when unset). */
    static int[] gradientEnds(int preset, String a, String b) {
        int p = Math.floorMod(preset, GRADIENT_KEYS.length);
        if (p < GRADIENTS.length) return GRADIENTS[p];
        return new int[]{colorOf(a, GRADIENTS[0][0]), colorOf(b, GRADIENTS[0][1])};
    }

    /** A colour [t] (0..1) of the way from a to b. */
    static int mix(int a, int b, double t) {
        double k = Math.max(0, Math.min(1, t));
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * k);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * k);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Where along the gradient a character sits (0..1): character [col] of [rowLen] in row [row] of [rows],
     * or character [inLine] of [lineLen] counting the whole line; the shimmer moves with time.
     */
    static double gradientAt(int dir, int col, int rowLen, int row, int rows, int inLine, int lineLen, double seconds) {
        return switch (dir) {
            case DIR_DOWN -> rows <= 1 ? 0 : (double) row / (rows - 1);
            case DIR_LINE -> lineLen <= 1 ? 0 : (double) inLine / (lineLen - 1);
            case DIR_SHIMMER -> {
                double x = (lineLen <= 1 ? 0 : (double) inLine / lineLen) * 0.6 - seconds * 0.45;
                double f = x - Math.floor(x);
                yield f < 0.5 ? f * 2 : 2 - f * 2;   // there and back
            }
            default -> rowLen <= 1 ? 0 : (double) col / (rowLen - 1);
        };
    }

    /** Where a line is up and how much of the view it covers (angles in degrees, times in song seconds). */
    record Spot(double t, double fadeAt, double yawDeg, double pitchDeg, double halfWDeg, double halfHDeg) {}

    private LyricsWords() {}

    /** The centre, in degrees from the middle of the view, of the column for line [index]: four across, alternately above and below the middle. */
    static double[] column(int index, double spanH, double spanV) {
        return new double[]{-spanH + (index % 4) * (2 * spanH / 3), (index % 2 == 0 ? 0.5 : -0.3) * spanV};
    }

    /** Degrees of clear view between a spot and the nearest line up at the same time (6 when none). */
    static double gap(double yaw, double pitch, double halfW, double halfH, List<Spot> others, double from, double until) {
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

    static float easeOutCubic(float t) { return 1f - (1f - t) * (1f - t) * (1f - t); }

    static float easeOutBack(float t, float s) {
        float u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    /** When a word goes (Words style): its own time and held-until, at least [MIN_LIFE] seconds, at most [MAX_LIFE]. */
    static double fadeAt(LyricsLrc.Word w) {
        return Math.min(w.t() + MAX_LIFE, Math.max(w.t() + MIN_LIFE, w.end() + TAIL));
    }

    /** The colour (0xRRGGBB) a Scatter line or word is drawn in, at this moment (seconds) for the rainbow. */
    static int tint(int mode, String custom, int lineNo, double seconds) {
        return switch (mode) {
            case COLOR_WHITE -> 0xFFFFFF;
            case COLOR_CUSTOM -> colorOf(custom, PEACH);
            case COLOR_RAINBOW -> Color.HSBtoRGB((float) ((seconds * 0.08) % 1.0), 0.42f, 1f) & 0xFFFFFF;
            case COLOR_PER_LINE -> Color.HSBtoRGB((float) ((Math.max(0, lineNo) * 0.618034) % 1.0), 0.38f, 1f) & 0xFFFFFF;
            default -> PEACH;
        };
    }

    /** One row of the Words style's picture: the word with a dark edge, centred. Returns the ink's left and right edge as 0..1 across the row. */
    static float[] drawWord(Graphics2D g, String word, int row, int rgb) { return drawWord(g, word, row, rgb, null); }

    private static final Font[] AWT_FONTS = new Font[FONT_KEYS.length];

    /** The Scatter font as a Java font at 64 px for the Words style's pictures (bold sans for the game's own two). */
    static Font awtFont(int font) {
        int f = Math.floorMod(font, FONT_KEYS.length);
        if (AWT_FONTS[f] != null) return AWT_FONTS[f];
        Font out = new Font(Font.SANS_SERIF, Font.BOLD, 64);
        String file = fontFile(f);
        if (file != null) {
            try (java.io.InputStream in = LyricsWords.class.getResourceAsStream(file)) {
                if (in != null) out = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(64f);
            } catch (Exception ignored) { }
        }
        AWT_FONTS[f] = out;
        return out;
    }

    static float[] drawWord(Graphics2D g, String word, int row, int rgb, Font base) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        FontRenderContext frc = g.getFontRenderContext();
        Font f = base != null ? base : new Font(Font.SANS_SERIF, Font.BOLD, 64);
        double w = f.getStringBounds(word, frc).getWidth();
        double room = ROW_W - 28;
        if (w > room) f = f.deriveFont((float) (f.getSize2D() * room / w));
        TextLayout tl = new TextLayout(word, f, frc);
        double tw = tl.getBounds().getWidth(), x = (ROW_W - tw) / 2 - tl.getBounds().getX(), y = row * ROW_H + ROW_H * 0.70;
        Shape sh = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
        g.setColor(new Color(0, 0, 0, 170));
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(sh);
        g.setColor(new Color(rgb));
        g.fill(sh);
        double pad = 8;
        return new float[]{(float) Math.max(0, (ROW_W - tw) / 2 - pad) / ROW_W, (float) Math.min(ROW_W, (ROW_W + tw) / 2 + pad) / ROW_W};
    }

    /** Half width and half height in blocks of a word at a distance (Words style): a set share of the view's height, narrowed to fit the view. */
    static double[] wordSize(double sizeK, double halfAspect, double dist, double tanH, double tanV, boolean keepOnScreen) {
        double halfH = 0.075 * sizeK * tanV * dist, halfW = halfH * 2 * halfAspect;
        double limit = 0.76 * tanH * dist;
        if (keepOnScreen && halfW > limit) { halfH *= limit / halfW; halfW = limit; }
        return new double[]{halfW, halfH};
    }

    /** The unit vector a view direction points in (Minecraft's yaw and pitch, in degrees). */
    static double[] direction(double pitchDeg, double yawDeg) {
        double p = Math.toRadians(pitchDeg), y = Math.toRadians(yawDeg);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    static int colorOf(String hex, int fallback) {
        if (hex != null && hex.matches("#?[0-9a-fA-F]{6}")) return Integer.parseInt(hex.replace("#", ""), 16);
        return fallback;
    }

    /** A line's text with runs of spaces made single, as it's typed and wrapped. */
    static String clean(String text) { return text == null ? "" : text.trim().replaceAll("\\s+", " "); }

    /**
     * The line cut into rows of at most [max] characters, at spaces (a longer word gets a row of its own).
     * Joining the rows with single spaces gives the cleaned line back, so typed characters map onto rows.
     */
    static List<String> wrap(String text, int max) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : clean(text).split(" ")) {
            if (word.isEmpty()) continue;
            if (row.length() > 0 && row.length() + 1 + word.length() > max) { rows.add(row.toString()); row.setLength(0); }
            if (row.length() > 0) row.append(' ');
            row.append(word);
        }
        if (row.length() > 0) rows.add(row.toString());
        return rows;
    }

    /**
     * How many characters of the (cleaned) line are typed at song time [pos]. Each word is typed from
     * its own time, a letter at a time over a short span (quicker for short notes), so the text keeps
     * up with the singing; without word times the line is typed evenly over its first part.
     */
    static int typed(LyricsLrc.Line line, double pos) {
        String text = clean(line.text());
        int n = text.length();
        if (n == 0 || pos < line.t()) return 0;
        if (line.words().isEmpty()) {
            double span = Math.max(0.3, Math.min(line.end() - line.t(), 0.06 * n + 0.3) * 0.8);
            return (int) Math.min(n, Math.ceil((pos - line.t()) / span * n));
        }
        int cursor = 0, shown = 0;
        for (LyricsLrc.Word w : line.words()) {
            String wt = clean(w.text());
            if (wt.isEmpty()) continue;
            int at = text.indexOf(wt, cursor);
            if (at < 0) at = Math.min(n, cursor);
            int end = Math.min(n, at + wt.length());
            if (pos < w.t()) return shown;
            double span = Math.max(0.04, Math.min(Math.max(0.05, w.end() - w.t()), 0.055 * wt.length() + 0.08));
            double f = Math.min(1, (pos - w.t()) / span);
            shown = at + (int) Math.ceil(f * (end - at));
            if (f < 1) return shown;
            cursor = end;
        }
        return Math.min(n, shown == cursor ? n : shown);
    }

    /** The visible part of each row when [typed] characters of the joined rows are typed. */
    static List<String> typedRows(List<String> rows, int typed) {
        List<String> out = new ArrayList<>(rows.size());
        int used = 0;
        for (String r : rows) {
            int k = Math.max(0, Math.min(r.length(), typed - used));
            out.add(r.substring(0, k));
            used += r.length() + 1;   // the space the row break stands for
        }
        return out;
    }
}
