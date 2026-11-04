package io.github.raincandyfx.rainium;

import java.util.List;

/**
 * Where the lyrics go on screen, in normalised screen coordinates (x and y from -1 to 1, y up).
 * Three rules:
 *   1. never over an enemy: if the lyrics would cover one (plus a buffer), they move to its left
 *      or right, whichever fits better, shrinking if they must; never above or below it;
 *   2. always fully on screen (inside a margin), shrinking if the screen is too small;
 *   3. small by default (the caller picks the size).
 * Pure, so the tests can run it.
 */
final class LyricsPlacement {
    record Rect(double x0, double y0, double x1, double y1) {
        double w() { return x1 - x0; }
        double h() { return y1 - y0; }
        double cx() { return (x0 + x1) / 2; }
        double cy() { return (y0 + y1) / 2; }
        boolean overlaps(Rect o) { return x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1; }
        Rect grow(double dx, double dy) { return new Rect(x0 - dx, y0 - dy, x1 + dx, y1 + dy); }
        static Rect centred(double cx, double cy, double w, double h) { return new Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); }
    }

    /** The placed lyrics: where, and how visible (0 when there's no room at all). */
    record Placed(Rect rect, double alpha) {}

    /** Width on screen (normalised, 2 = the whole width) for Small, Medium, Large: small by default. */
    static final double[] WIDTHS = {0.46, 0.62, 0.86};
    static final double MARGIN = 0.04;      // kept clear at the screen's edges
    static final double BUFFER = 0.06;      // kept clear around an enemy
    static final double MIN_SCALE = 0.45;   // smallest the lyrics shrink to beside an enemy

    private LyricsPlacement() {}

    /** An enemy's screen box with its buffer: a fixed part plus a quarter of its width. */
    static Rect guard(Rect enemy) {
        return enemy.grow(BUFFER + enemy.w() * 0.25, BUFFER * 0.5);
    }

    /**
     * Places lyrics of size w×h that would like to be centred at (cx, cy), clear of the guarded
     * enemy boxes.
     */
    static Placed place(double cx, double cy, double w, double h, List<Rect> enemies) {
        double lo = -1 + MARGIN, hi = 1 - MARGIN;
        // 2. fit the screen: shrink to fit, then keep inside
        double s = Math.min(1, Math.min((hi - lo) / w, (hi - lo) / h));
        w *= s; h *= s;
        cx = clamp(cx, lo + w / 2, hi - w / 2);
        cy = clamp(cy, lo + h / 2, hi - h / 2);
        Rect r = Rect.centred(cx, cy, w, h);
        double fullW = w, fullH = h;
        // 1. clear of enemies: sideways only
        for (int pass = 0; pass < 4; pass++) {
            Rect hit = null;
            for (Rect e : enemies) { Rect g = guard(e); if (g.overlaps(r)) { hit = g; break; } }
            if (hit == null) return new Placed(r, 1);
            double roomL = hit.x0 - lo, roomR = hi - hit.x1;
            boolean preferLeft = r.cx() < hit.cx();
            double room = preferLeft ? roomL : roomR;
            if (room < r.w()) {                                // the preferred side is too narrow: try the other
                double other = preferLeft ? roomR : roomL;
                if (other >= r.w() || other > room) { preferLeft = !preferLeft; room = other; }
            }
            double k = Math.min(1, room / r.w());
            if (k * r.w() < fullW * MIN_SCALE || room <= 0) {   // no side has room: fade out rather than cover the enemy
                double kk = Math.max(0, room) / fullW;
                if (kk < 0.2) return new Placed(r, 0);
                k = kk * fullW / r.w();
            }
            double nw = r.w() * k, nh = r.h() * k;
            double ncx = preferLeft ? hit.x0 - nw / 2 : hit.x1 + nw / 2;
            double ncy = clamp(r.cy(), lo + nh / 2, hi - nh / 2);
            r = Rect.centred(ncx, ncy, nw, nh);
        }
        for (Rect e : enemies) if (guard(e).overlaps(r)) return new Placed(r, 0);
        return new Placed(r, 1);
    }

    static double clamp(double v, double a, double b) { return a > b ? (a + b) / 2 : Math.max(a, Math.min(b, v)); }
}
