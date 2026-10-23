package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wavefront OBJ -> Rainium mesh JSON. Same rules as the web converter and mesh_convert.py:
 * axis conversion into player-model space (+X left, +Y down, +Z behind, 1 unit = 1 block),
 * triangles as degenerate quads, quads kept, larger polygons fan-triangulated.
 */
final class ObjImport {
    // Display names for these are in the lang file (import.axes.*, import.split.*, ...).
    enum Axes { YUP_PZ, YUP_NZ, ZUP_NY, MC }          // Y up facing +Z / -Z, Z up facing -Y, already Minecraft space
    enum Origin { FEET, NECK }
    enum Grouping { OBJMAT, OBJECT, MATERIAL, SINGLE } // object + material, object, material, one group
    enum Normals { FILE, SMOOTH, FLAT }

    static final double[] UNIT_VALUES = {1.0, 0.01, 0.0625, 0.0254};
    // metres, centimetres, MC pixels, inches (names in the lang file: import.units.0-3)
    static final double[] FIT_VALUES = {0, 0.5, 1, 1.5, 2, 3, 4};

    static final class Options {
        Axes axes = Axes.YUP_PZ;
        Origin origin = Origin.FEET;
        int unitIndex = 0;
        double scale = 1.0;
        int fitIndex = 0;              // 0 = off
        double ox, oy, oz;
        Grouping grouping = Grouping.OBJMAT;
        Normals normals = Normals.FILE;
        boolean flipV = true;
        int precision = 5;
    }

    record Parsed(float[] p, float[] t, float[] n, List<Face> faces, int skipped) {}
    record Face(String obj, String grp, String mtl, int[][] refs) {}
    record Result(String json, int verts, int quads, double[] min, double[] max, List<String> groups, List<Warning> warnings) {}
    /** A problem worth telling the user about; key is "import.warn.&lt;key&gt;" in the lang file. */
    record Warning(String key, Object... args) {}

    // ---------------------------------------------------------------- parsing
    static Parsed parse(String text) {
        FloatList P = new FloatList(), T = new FloatList(), N = new FloatList();
        List<Face> faces = new ArrayList<>();
        String obj = "", grp = "", mtl = "";
        int skipped = 0;
        String[] lines = text.split("\r?\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            while (line.endsWith("\\") && li + 1 < lines.length) line = line.substring(0, line.length() - 1) + " " + lines[++li];
            String t = line.trim();
            if (t.isEmpty() || t.charAt(0) == '#') continue;
            String[] parts = t.split("\\s+");
            switch (parts[0]) {
                case "v" -> { P.add(num(parts, 1)); P.add(num(parts, 2)); P.add(num(parts, 3)); }
                case "vt" -> { T.add(num(parts, 1)); T.add(num(parts, 2)); }
                case "vn" -> { N.add(num(parts, 1)); N.add(num(parts, 2)); N.add(num(parts, 3)); }
                case "o" -> obj = join(parts);
                case "g" -> grp = join(parts);
                case "usemtl" -> mtl = join(parts);
                case "f" -> {
                    int[][] refs = new int[parts.length - 1][];
                    for (int k = 1; k < parts.length; k++) {
                        String[] c = parts[k].split("/", -1);
                        refs[k - 1] = new int[]{res(c, 0, P.size / 3), res(c, 1, T.size / 2), res(c, 2, N.size / 3)};
                    }
                    if (refs.length >= 3) faces.add(new Face(obj, grp, mtl, refs));
                }
                case "l", "p" -> skipped++;
                default -> { }
            }
        }
        return new Parsed(P.toArray(), T.toArray(), N.toArray(), faces, skipped);
    }

    private static float num(String[] p, int i) {
        if (i >= p.length) return 0f;
        try { return Float.parseFloat(p[i]); } catch (NumberFormatException e) { return 0f; }
    }
    private static String join(String[] parts) {
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < parts.length; i++) { if (i > 1) b.append('_'); b.append(parts[i]); }
        return b.toString();
    }
    private static int res(String[] c, int i, int len) {
        if (i >= c.length || c[i].isEmpty()) return -1;
        try {
            int n = Integer.parseInt(c[i]);
            return n > 0 ? n - 1 : len + n;
        } catch (NumberFormatException e) { return -1; }
    }

    // ---------------------------------------------------------------- conversion
    private static double[] axes(Axes a, double x, double y, double z) {
        return switch (a) {
            case YUP_PZ -> new double[]{x, -y, -z};
            case YUP_NZ -> new double[]{-x, -y, z};
            case ZUP_NY -> new double[]{x, -z, y};
            case MC -> new double[]{x, y, z};
        };
    }

    private static String groupKey(Face f, Grouping mode) {
        String o = !f.obj().isEmpty() ? f.obj() : f.grp();
        String k = switch (mode) {
            case SINGLE -> "main";
            case OBJECT -> o.isEmpty() ? "main" : o;
            case MATERIAL -> f.mtl().isEmpty() ? "main" : f.mtl();
            case OBJMAT -> {
                String j = o.isEmpty() ? f.mtl() : (f.mtl().isEmpty() ? o : o + "_" + f.mtl());
                yield j.isEmpty() ? "main" : j;
            }
        };
        return k.replaceAll("[^\\w.-]+", "_");
    }

    private static final class G {
        final Map<String, Integer> map = new LinkedHashMap<>();
        final DoubleList pos = new DoubleList(), uv = new DoubleList(), nrm = new DoubleList();
        final List<Boolean> hasN = new ArrayList<>();
        final IntList q = new IntList();
    }

    static Result convert(Parsed m, Options o) {
        int nv = m.p().length / 3;
        double[] P = new double[m.p().length];
        for (int i = 0; i < nv; i++) {
            double[] a = axes(o.axes, m.p()[i*3], m.p()[i*3+1], m.p()[i*3+2]);
            P[i*3] = a[0]; P[i*3+1] = a[1]; P[i*3+2] = a[2];
        }
        double scale = o.scale * UNIT_VALUES[o.unitIndex];
        boolean[] used = new boolean[nv];
        for (Face f : m.faces()) for (int[] r : f.refs()) if (r[0] >= 0 && r[0] < nv) used[r[0]] = true;
        double fit = FIT_VALUES[o.fitIndex];
        if (fit > 0) {
            double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int i = 0; i < nv; i++) if (used[i]) for (int a = 0; a < 3; a++) { mn[a] = Math.min(mn[a], P[i*3+a]); mx[a] = Math.max(mx[a], P[i*3+a]); }
            double ext = Math.max(mx[0] - mn[0], Math.max(mx[1] - mn[1], mx[2] - mn[2]));
            if (ext > 0 && ext < Double.MAX_VALUE) scale = fit / ext;
        }
        double[] off = {o.ox, o.oy + (o.origin == Origin.FEET ? 1.5 : 0), o.oz};

        Map<String, G> groups = new LinkedHashMap<>();
        int badRefs = 0, missingUv = 0;
        int nt = m.t().length / 2, nn = m.n().length / 3;
        for (Face f : m.faces()) {
            String key = groupKey(f, o.grouping);
            G g = groups.computeIfAbsent(key, k -> new G());
            int[] idx = new int[f.refs().length];
            boolean ok = true;
            for (int k = 0; k < f.refs().length; k++) {
                int[] r = f.refs()[k];
                if (r[0] < 0 || r[0] >= nv) { ok = false; break; }
                String id = r[0] + "/" + r[1] + "/" + (o.normals == Normals.FILE ? r[2] : -1);
                Integer vi = g.map.get(id);
                if (vi == null) {
                    vi = g.pos.size / 3;
                    g.map.put(id, vi);
                    g.pos.add(P[r[0]*3] * scale + off[0]); g.pos.add(P[r[0]*3+1] * scale + off[1]); g.pos.add(P[r[0]*3+2] * scale + off[2]);
                    if (r[1] >= 0 && r[1] < nt) { g.uv.add(m.t()[r[1]*2]); g.uv.add(o.flipV ? 1 - m.t()[r[1]*2+1] : m.t()[r[1]*2+1]); }
                    else { g.uv.add(0); g.uv.add(0); missingUv++; }
                    if (o.normals == Normals.FILE && r[2] >= 0 && r[2] < nn) {
                        double[] n = axes(o.axes, m.n()[r[2]*3], m.n()[r[2]*3+1], m.n()[r[2]*3+2]);
                        double l = Math.sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2]);
                        if (l == 0) l = 1;
                        g.nrm.add(n[0] / l); g.nrm.add(n[1] / l); g.nrm.add(n[2] / l); g.hasN.add(true);
                    } else { g.nrm.add(0); g.nrm.add(0); g.nrm.add(0); g.hasN.add(false); }
                }
                idx[k] = vi;
            }
            if (!ok) { badRefs++; continue; }
            if (idx.length == 3) { g.q.add(idx[0]); g.q.add(idx[1]); g.q.add(idx[2]); g.q.add(idx[2]); }
            else if (idx.length == 4) { for (int x : idx) g.q.add(x); }
            else for (int j = 1; j + 1 < idx.length; j++) { g.q.add(idx[0]); g.q.add(idx[j]); g.q.add(idx[j+1]); g.q.add(idx[j+1]); }
        }

        if (o.normals == Normals.FLAT) for (G g : groups.values()) flatten(g);
        for (G g : groups.values()) smoothMissing(g);

        double k = Math.pow(10, o.precision);
        StringBuilder sb = new StringBuilder("{");
        int totalV = 0, totalQ = 0;
        double[] bmn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, bmx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        List<String> names = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, G> e : groups.entrySet()) {
            G g = e.getValue();
            int n = g.pos.size / 3;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "")).append("\":{\"v\":[");
            for (int i = 0; i < n; i++) {
                for (int a = 0; a < 3; a++) { double v = g.pos.get(i*3+a); bmn[a] = Math.min(bmn[a], v); bmx[a] = Math.max(bmx[a], v); }
                if (i > 0) sb.append(',');
                num(sb, g.pos.get(i*3), k); sb.append(','); num(sb, g.pos.get(i*3+1), k); sb.append(','); num(sb, g.pos.get(i*3+2), k); sb.append(',');
                num(sb, g.uv.get(i*2), k); sb.append(','); num(sb, g.uv.get(i*2+1), k); sb.append(',');
                num(sb, g.nrm.get(i*3), k); sb.append(','); num(sb, g.nrm.get(i*3+1), k); sb.append(','); num(sb, g.nrm.get(i*3+2), k);
            }
            sb.append("],\"q\":[");
            for (int i = 0; i < g.q.size; i++) { if (i > 0) sb.append(','); sb.append(g.q.get(i)); }
            sb.append("]}");
            totalV += n;
            totalQ += g.q.size / 4;
            names.add(e.getKey());
        }
        sb.append('}');

        List<Warning> warn = new ArrayList<>();
        if (m.faces().isEmpty()) warn.add(new Warning("no_faces"));
        double maxSide = 0;
        if (totalV > 0) maxSide = Math.max(bmx[0] - bmn[0], Math.max(bmx[1] - bmn[1], bmx[2] - bmn[2]));
        if (maxSide > 8) warn.add(new Warning("too_big", String.format(Locale.ROOT, "%.1f", maxSide)));
        if (totalV > 0 && maxSide < 0.08) warn.add(new Warning("too_small", String.format(Locale.ROOT, "%.3f", maxSide)));
        if (totalV > 0 && missingUv == totalV) warn.add(new Warning("no_uvs"));
        if (badRefs > 0) warn.add(new Warning("bad_faces", badRefs));
        if (totalQ > 40000) warn.add(new Warning("heavy", totalQ));
        if (totalV == 0) { bmn = new double[3]; bmx = new double[3]; }
        return new Result(sb.toString(), totalV, totalQ, bmn, bmx, names, warn);
    }

    private static void num(StringBuilder sb, double v, double k) {
        double r = Math.round(v * k) / k;
        if (r == 0) { sb.append('0'); return; }
        if (r == Math.rint(r) && Math.abs(r) < 1e15) { sb.append((long) r); return; }
        String s = new java.math.BigDecimal(r).setScale((int) Math.round(Math.log10(k)), java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        sb.append(s);
    }

    private static double[] faceNormal(DoubleList pos, int a, int b, int c, boolean raw) {
        double ux = pos.get(b*3) - pos.get(a*3), uy = pos.get(b*3+1) - pos.get(a*3+1), uz = pos.get(b*3+2) - pos.get(a*3+2);
        double vx = pos.get(c*3) - pos.get(a*3), vy = pos.get(c*3+1) - pos.get(a*3+1), vz = pos.get(c*3+2) - pos.get(a*3+2);
        double[] n = {uy*vz - uz*vy, uz*vx - ux*vz, ux*vy - uy*vx};
        if (raw) return n;
        double l = Math.sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2]);
        if (l == 0) l = 1;
        return new double[]{n[0] / l, n[1] / l, n[2] / l};
    }

    private static void flatten(G g) {
        DoubleList pos = new DoubleList(), uv = new DoubleList(), nrm = new DoubleList();
        IntList q = new IntList();
        for (int i = 0; i < g.q.size; i += 4) {
            int[] ids = {g.q.get(i), g.q.get(i+1), g.q.get(i+2), g.q.get(i+3)};
            double[] n = faceNormal(g.pos, ids[0], ids[1], ids[2], false);
            int base = pos.size / 3;
            int uniq = ids[3] == ids[2] ? 3 : 4;
            for (int j = 0; j < uniq; j++) {
                int v = ids[j];
                pos.add(g.pos.get(v*3)); pos.add(g.pos.get(v*3+1)); pos.add(g.pos.get(v*3+2));
                uv.add(g.uv.get(v*2)); uv.add(g.uv.get(v*2+1));
                nrm.add(n[0]); nrm.add(n[1]); nrm.add(n[2]);
            }
            q.add(base); q.add(base + 1); q.add(base + 2); q.add(base + (uniq == 4 ? 3 : 2));
        }
        g.pos.replace(pos); g.uv.replace(uv); g.nrm.replace(nrm); g.q.replace(q);
        g.hasN.clear();
        for (int i = 0; i < pos.size / 3; i++) g.hasN.add(true);
    }

    private static void smoothMissing(G g) {
        if (!g.hasN.contains(false)) return;
        double[] acc = new double[g.pos.size];
        for (int i = 0; i < g.q.size; i += 4) {
            double[] n = faceNormal(g.pos, g.q.get(i), g.q.get(i+1), g.q.get(i+2), true);
            int uniq = g.q.get(i+3) == g.q.get(i+2) ? 3 : 4;
            for (int j = 0; j < uniq; j++) { int v = g.q.get(i+j); acc[v*3] += n[0]; acc[v*3+1] += n[1]; acc[v*3+2] += n[2]; }
        }
        for (int v = 0; v < g.hasN.size(); v++) if (!g.hasN.get(v)) {
            double l = Math.sqrt(acc[v*3]*acc[v*3] + acc[v*3+1]*acc[v*3+1] + acc[v*3+2]*acc[v*3+2]);
            if (l > 1e-12) { g.nrm.set(v*3, acc[v*3] / l); g.nrm.set(v*3+1, acc[v*3+1] / l); g.nrm.set(v*3+2, acc[v*3+2] / l); }
            else g.nrm.set(v*3+1, -1);
        }
    }

    // ---------------------------------------------------------------- tiny primitive lists
    private static final class FloatList {
        float[] a = new float[1024]; int size;
        void add(float v) { if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2); a[size++] = v; }
        float[] toArray() { return java.util.Arrays.copyOf(a, size); }
    }
    private static final class DoubleList {
        double[] a = new double[256]; int size;
        void add(double v) { if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2); a[size++] = v; }
        double get(int i) { return a[i]; }
        void set(int i, double v) { a[i] = v; }
        void replace(DoubleList o) { a = o.a; size = o.size; }
    }
    private static final class IntList {
        int[] a = new int[256]; int size;
        void add(int v) { if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2); a[size++] = v; }
        int get(int i) { return a[i]; }
        void replace(IntList o) { a = o.a; size = o.size; }
    }
}
