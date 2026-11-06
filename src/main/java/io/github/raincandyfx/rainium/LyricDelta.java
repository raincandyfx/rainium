package io.github.raincandyfx.rainium;

/**
 * What changed between two pictures of the lyric video. The two layers sit side by side in one texture (2w x h, ABGR);
 * only the changed span of each row is passed on, so most frames cost a small part of a full upload.
 */
final class LyricDelta {
    final int[] x0, x1;          // per row: the changed span [x0, x1); x0 < 0 when the row is unchanged
    final int[] data;            // the spans' pixels, one after the other
    final int rows;

    private LyricDelta(int[] x0, int[] x1, int[] data, int rows) { this.x0 = x0; this.x1 = x1; this.data = data; this.rows = rows; }

    /**
     * Compares the layers (ARGB) with the picture last handed over (ABGR, updated here) and returns what differs.
     * With full set, every row is sent whole (the first picture, or after one that never reached the screen).
     */
    static LyricDelta diff(int[] back, int[] front, int w, int h, int[] last, boolean full) {
        int[] x0 = new int[h], x1 = new int[h];
        int[] buf = new int[Math.max(16, w * 2 * h / 4)];
        int n = 0, rows = 0;
        for (int y = 0; y < h; y++) {
            int lo = Integer.MAX_VALUE, hi = -1, row = y * w * 2;
            for (int layer = 0; layer < 2; layer++) {
                int[] src = layer == 0 ? back : front;
                int o = row + layer * w, si = y * w;
                for (int x = 0; x < w; x++) {
                    int p = src[si + x];   // ARGB -> ABGR
                    int q = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
                    if (q != last[o + x] || full) {
                        last[o + x] = q;
                        int xx = layer * w + x;
                        if (xx < lo) lo = xx;
                        if (xx > hi) hi = xx;
                    }
                }
            }
            if (hi < 0) { x0[y] = -1; continue; }
            if (full) { lo = 0; hi = w * 2 - 1; }
            int len = hi - lo + 1;
            if (n + len > buf.length) buf = java.util.Arrays.copyOf(buf, Math.max(buf.length * 2, n + len));
            System.arraycopy(last, row + lo, buf, n, len);
            n += len;
            x0[y] = lo; x1[y] = hi + 1; rows++;
        }
        return new LyricDelta(x0, x1, n == buf.length ? buf : java.util.Arrays.copyOf(buf, n), rows);
    }

    /** Writes the changed spans into a picture of the same layout (what the texture upload does pixel by pixel). */
    void applyTo(int[] picture, int w) {
        int n = 0;
        for (int y = 0; y < x0.length; y++) {
            if (x0[y] < 0) continue;
            for (int x = x0[y]; x < x1[y]; x++) picture[y * w * 2 + x] = data[n++];
        }
    }
}
