package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.List;

/** The arithmetic behind the Scatter mode (LyricsScatter, and Scatter by whole line in Lyrics), kept free of the game so the tests can run it. */
final class LyricsWords {
    static final double FADE = 0.6;
    /** The most characters in one row of a typed line; longer lines wrap at spaces. */
    static final int ROW_CHARS = 18;

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
