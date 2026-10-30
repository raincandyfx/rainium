package io.github.raincandyfx.rainium;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Small preview pictures: each model is drawn in software (seen from behind and a little to the side,
 * where most cosmetics sit) into a small ARGB image. The Add tab shows shaded silhouettes; the layer
 * list shows each layer on its own with its texture, part, hue, tint and placement, and each folder
 * its enabled layers together. Pictures are made on a background thread and cached by key.
 */
final class Thumbs {
    static final int SIZE = 22;

    private static final Map<String, int[]> done = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> pending = new ConcurrentHashMap<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rainium-thumbnails");
        t.setDaemon(true);
        return t;
    });

    private Thumbs() {}

    /** The picture for key, or null while it's being made (or if the model can't be read). */
    static int[] get(String key, Supplier<Collection<Mesh.Group>> groups) { return get(key, SIZE, groups); }

    /** Same, n×n pixels: a shaded silhouette. */
    static int[] get(String key, int n, Supplier<Collection<Mesh.Group>> groups) {
        return make(key + "#" + n, () -> {
            Collection<Mesh.Group> gs = groups.get();
            return gs == null ? null : render(gs, n, 0xFFE8DBED);
        });
    }

    private static int[] make(String key, Supplier<int[]> job) {
        int[] px = done.get(key);
        if (px != null || pending.putIfAbsent(key, true) != null) return px;
        worker.execute(() -> {
            try {
                int[] out = job.get();
                if (out != null) done.put(key, out);
            } catch (Throwable ignored) {
                // no picture for unreadable models
            }
        });
        return null;
    }

    /** Cache key for a file in the config folder: name plus last-modified time. */
    static String key(Path file) {
        try { return file + "@" + Files.getLastModifiedTime(file).toMillis(); }
        catch (Exception e) { return file.toString(); }
    }

    /** Draws the groups into an n×n ARGB picture: transparent background, model in `base` colour, lit from the upper left. */
    static int[] render(Collection<Mesh.Group> groups, int n, int base) {
        // view: from behind, turned 30° (model +Z is behind the player)
        double yaw = Math.toRadians(150), cy = Math.cos(yaw), sy = Math.sin(yaw);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Mesh.Group g : groups) {
            float[] v = g.v();
            for (int i = 0; i < v.length; i += Mesh.STRIDE) {
                double x = v[i] * cy + v[i + 2] * sy, y = v[i + 1];
                minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        int[] out = new int[n * n];
        if (minX > maxX) return out;
        double span = Math.max(maxX - minX, maxY - minY);
        if (span <= 0) span = 1;
        double s = (n - 2) / span, ox = (n - (maxX - minX) * s) / 2 - minX * s, oy = (n - (maxY - minY) * s) / 2 - minY * s;
        float[] depth = new float[n * n];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        double lx = -0.4, ly = -0.7, lz = -0.6, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        for (Mesh.Group g : groups) {
            float[] v = g.v();
            int[] q = g.q();
            for (int k = 0; k + 3 < q.length; k += 4) {
                tri(v, q[k], q[k + 1], q[k + 2], cy, sy, s, ox, oy, n, out, depth, lx, ly, lz, br, bg, bb);
                if (q[k + 3] != q[k + 2]) tri(v, q[k], q[k + 2], q[k + 3], cy, sy, s, ox, oy, n, out, depth, lx, ly, lz, br, bg, bb);
            }
        }
        return out;
    }

    private static void tri(float[] v, int a, int b, int c, double cy, double sy, double s, double ox, double oy, int n,
                            int[] out, float[] depth, double lx, double ly, double lz, int br, int bg, int bb) {
        int A = a * Mesh.STRIDE, B = b * Mesh.STRIDE, C = c * Mesh.STRIDE;
        double[] X = new double[3], Y = new double[3], Z = new double[3];
        int[] idx = {A, B, C};
        for (int i = 0; i < 3; i++) {
            int o = idx[i];
            X[i] = (v[o] * cy + v[o + 2] * sy) * s + ox;
            Y[i] = v[o + 1] * s + oy;
            Z[i] = -v[o] * sy + v[o + 2] * cy;   // larger = further from the viewer
        }
        // flat shading from the face normal (two-sided)
        double ux = v[B] - v[A], uy = v[B + 1] - v[A + 1], uz = v[B + 2] - v[A + 2];
        double wx = v[C] - v[A], wy = v[C + 1] - v[A + 1], wz = v[C + 2] - v[A + 2];
        double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl == 0) return;
        double vx = nx * cy + nz * sy, vz = -nx * sy + nz * cy;   // normal in view space
        double lit = Math.abs(vx * lx + ny * ly + vz * lz) / nl;
        double shade = 0.45 + 0.55 * lit;
        int col = 0xFF000000 | ((int) (br * shade) << 16) | ((int) (bg * shade) << 8) | (int) (bb * shade);

        int x0 = Math.max(0, (int) Math.floor(Math.min(X[0], Math.min(X[1], X[2])))), x1 = Math.min(n - 1, (int) Math.ceil(Math.max(X[0], Math.max(X[1], X[2]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(Y[0], Math.min(Y[1], Y[2])))), y1 = Math.min(n - 1, (int) Math.ceil(Math.max(Y[0], Math.max(Y[1], Y[2]))));
        double area = (X[1] - X[0]) * (Y[2] - Y[0]) - (X[2] - X[0]) * (Y[1] - Y[0]);
        if (Math.abs(area) < 1e-9) {   // edge-on: still mark the pixels it passes through
            for (int i = 0; i < 3; i++) {
                int px = (int) X[i], py = (int) Y[i];
                if (px >= 0 && py >= 0 && px < n && py < n && Z[i] < depth[py * n + px]) { depth[py * n + px] = (float) Z[i]; out[py * n + px] = col; }
            }
            return;
        }
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                double cx = px + 0.5, cyy = py + 0.5;
                double w0 = ((X[1] - cx) * (Y[2] - cyy) - (X[2] - cx) * (Y[1] - cyy)) / area;
                double w1 = ((X[2] - cx) * (Y[0] - cyy) - (X[0] - cx) * (Y[2] - cyy)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -0.02 || w1 < -0.02 || w2 < -0.02) continue;
                float z = (float) (w0 * Z[0] + w1 * Z[1] + w2 * Z[2]);
                int i = py * n + px;
                if (z < depth[i]) { depth[i] = z; out[i] = col; }
            }
        }
    }
}
