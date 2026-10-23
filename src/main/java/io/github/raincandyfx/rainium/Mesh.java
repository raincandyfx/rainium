package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A mesh made of named groups. Every group is normalised to the same internal form:
 * interleaved vertices (x, y, z, u, v, nx, ny, nz) and a quad index list. Triangles are
 * stored as degenerate quads (last index repeated), because Minecraft's entity render
 * types draw quads.
 *
 * Accepted input shapes (see README for examples):
 *   1. Interleaved:    { "groupName": { "v": [...8 floats per vertex], "q": [quad indices] }, ... }
 *   2. Wrapped:        { "groups": { "groupName": {...}, ... } }
 *   3. Single group:   { "v": [...], "q": [...] }   or   { "positions": [...], ... }
 * Per group, either interleaved "v" (with optional "layout"/"stride") or separate
 * "positions" / "uvs" / "normals" arrays (flat or nested), plus one of
 * "q"/"quads", "t"/"tris"/"triangles", "indices" (+ "mode"), or "faces" (nested polygons).
 */
final class Mesh {
    static final int STRIDE = 8;

    record Group(String name, float[] v, int[] q) {
        int vertexCount() { return v.length / STRIDE; }
        int quadCount() { return q.length / 4; }
    }

    final Map<String, Group> groups;

    private Mesh(Map<String, Group> groups) { this.groups = groups; }

    String describe() {
        StringBuilder b = new StringBuilder();
        for (Group g : groups.values()) {
            if (b.length() > 0) b.append(", ");
            b.append(g.name()).append(" (").append(g.vertexCount()).append(" verts, ").append(g.quadCount()).append(" quads)");
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    static Mesh fromJson(Object root) {
        if (!(root instanceof Map)) throw new IllegalArgumentException("mesh file must be a JSON object");
        Map<String, Object> obj = (Map<String, Object>) root;
        Map<String, Group> out = new LinkedHashMap<>();
        if (looksLikeGroup(obj)) {
            out.put("main", group("main", obj));
        } else {
            Map<String, Object> src = obj.get("groups") instanceof Map ? (Map<String, Object>) obj.get("groups") : obj;
            for (Map.Entry<String, Object> e : src.entrySet()) {
                if (e.getValue() instanceof Map && looksLikeGroup((Map<String, Object>) e.getValue())) {
                    out.put(e.getKey(), group(e.getKey(), (Map<String, Object>) e.getValue()));
                }
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no mesh groups found (expected objects with \"v\" or \"positions\")");
        return new Mesh(out);
    }

    private static boolean looksLikeGroup(Map<String, Object> m) {
        return m.get("v") instanceof List || m.get("positions") instanceof List
            || m.get("position") instanceof List || m.get("vertices") instanceof List;
    }

    private static Group group(String name, Map<String, Object> m) {
        try {
            return buildGroup(name, m);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("group '" + name + "': " + e.getMessage(), e);
        }
    }

    private static Group buildGroup(String name, Map<String, Object> m) {
        float[] pos, uv = null, nrm = null;
        if (m.get("v") instanceof List) {
            List<String> layout = layout(m.get("layout"));
            int stride = 0;
            for (String a : layout) stride += attrSize(a);
            if (m.get("stride") != null) stride = Math.max(stride, (int) num(m.get("stride")));
            float[] raw = flat(m.get("v"));
            if (raw.length % stride != 0) throw new IllegalArgumentException("\"v\" length " + raw.length + " is not a multiple of stride " + stride);
            int n = raw.length / stride;
            pos = new float[n * 3];
            boolean hasUv = layout.contains("uv"), hasN = layout.contains("normal");
            if (hasUv) uv = new float[n * 2];
            if (hasN) nrm = new float[n * 3];
            for (int k = 0; k < n; k++) {
                int o = k * stride;
                for (String a : layout) {
                    switch (a) {
                        case "pos" -> { pos[k*3] = raw[o]; pos[k*3+1] = raw[o+1]; pos[k*3+2] = raw[o+2]; }
                        case "uv" -> { uv[k*2] = raw[o]; uv[k*2+1] = raw[o+1]; }
                        case "normal" -> { nrm[k*3] = raw[o]; nrm[k*3+1] = raw[o+1]; nrm[k*3+2] = raw[o+2]; }
                        default -> { }
                    }
                    o += attrSize(a);
                }
            }
        } else {
            pos = flat(first(m, "positions", "position", "vertices"));
            if (pos.length % 3 != 0) throw new IllegalArgumentException("positions length " + pos.length + " is not a multiple of 3");
            Object u = first(m, "uvs", "uv", "texcoords", "texcoord");
            if (u != null) uv = flat(u);
            Object nn = first(m, "normals", "normal");
            if (nn != null) nrm = flat(nn);
        }
        int n = pos.length / 3;
        if (uv != null && uv.length != n * 2) throw new IllegalArgumentException("uv count " + uv.length / 2 + " does not match vertex count " + n);
        if (nrm != null && nrm.length != n * 3) throw new IllegalArgumentException("normal count " + nrm.length / 3 + " does not match vertex count " + n);
        if (Boolean.TRUE.equals(m.get("flip_v")) && uv != null) for (int k = 1; k < uv.length; k += 2) uv[k] = 1f - uv[k];

        int[] quads = indices(m, n);
        for (int idx : quads) if (idx < 0 || idx >= n) throw new IllegalArgumentException("index " + idx + " out of range (vertex count " + n + ")");
        if (nrm == null) nrm = computeNormals(pos, quads);

        float[] v = new float[n * STRIDE];
        for (int k = 0; k < n; k++) {
            int o = k * STRIDE;
            v[o] = pos[k*3]; v[o+1] = pos[k*3+1]; v[o+2] = pos[k*3+2];
            if (uv != null) { v[o+3] = uv[k*2]; v[o+4] = uv[k*2+1]; }
            v[o+5] = nrm[k*3]; v[o+6] = nrm[k*3+1]; v[o+7] = nrm[k*3+2];
        }
        return new Group(name, v, quads);
    }

    @SuppressWarnings("unchecked")
    private static int[] indices(Map<String, Object> m, int n) {
        Object q = first(m, "q", "quads");
        if (q != null) return quadsFrom(flatInt(q), 4);
        Object t = first(m, "t", "tris", "triangles");
        if (t != null) return quadsFrom(flatInt(t), 3);
        Object f = m.get("faces");
        if (f instanceof List<?> fl && !fl.isEmpty() && fl.get(0) instanceof List) return polygons((List<Object>) f);
        Object idx = first(m, "indices", "faces");
        String mode = String.valueOf(m.getOrDefault("mode", "triangles")).toLowerCase();
        int per = mode.startsWith("quad") ? 4 : 3;
        if (idx != null) {
            if (idx instanceof List<?> il && !il.isEmpty() && il.get(0) instanceof List) return polygons((List<Object>) idx);
            return quadsFrom(flatInt(idx), per);
        }
        int[] seq = new int[n];
        for (int k = 0; k < n; k++) seq[k] = k;
        return quadsFrom(seq, per);
    }

    private static int[] quadsFrom(int[] idx, int per) {
        if (idx.length % per != 0) throw new IllegalArgumentException("index count " + idx.length + " is not a multiple of " + per);
        if (per == 4) return idx;
        int[] out = new int[idx.length / 3 * 4];
        for (int k = 0, o = 0; k < idx.length; k += 3, o += 4) {
            out[o] = idx[k]; out[o+1] = idx[k+1]; out[o+2] = idx[k+2]; out[o+3] = idx[k+2];
        }
        return out;
    }

    /** Nested polygons: triangles and quads kept, larger polygons fan-triangulated. */
    private static int[] polygons(List<Object> faces) {
        List<Integer> out = new ArrayList<>();
        for (Object f : faces) {
            int[] p = flatInt(f);
            if (p.length == 3) { out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(p[2]); }
            else if (p.length == 4) { for (int x : p) out.add(x); }
            else if (p.length > 4) {
                for (int k = 1; k + 1 < p.length; k++) { out.add(p[0]); out.add(p[k]); out.add(p[k+1]); out.add(p[k+1]); }
            }
        }
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) r[k] = out.get(k);
        return r;
    }

    private static float[] computeNormals(float[] pos, int[] quads) {
        float[] n = new float[pos.length];
        for (int k = 0; k < quads.length; k += 4) {
            int a = quads[k], b = quads[k+1], c = quads[k+2];
            float ux = pos[b*3] - pos[a*3], uy = pos[b*3+1] - pos[a*3+1], uz = pos[b*3+2] - pos[a*3+2];
            float vx = pos[c*3] - pos[a*3], vy = pos[c*3+1] - pos[a*3+1], vz = pos[c*3+2] - pos[a*3+2];
            float nx = uy*vz - uz*vy, ny = uz*vx - ux*vz, nz = ux*vy - uy*vx;
            for (int j = 0; j < 4; j++) {
                int t = quads[k+j];
                if (j == 3 && t == c) break;
                n[t*3] += nx; n[t*3+1] += ny; n[t*3+2] += nz;
            }
        }
        for (int k = 0; k < n.length; k += 3) {
            float l = (float) Math.sqrt(n[k]*n[k] + n[k+1]*n[k+1] + n[k+2]*n[k+2]);
            if (l > 1e-8f) { n[k] /= l; n[k+1] /= l; n[k+2] /= l; } else { n[k+1] = -1f; }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private static List<String> layout(Object o) {
        List<String> r = new ArrayList<>();
        if (o == null) { r.add("pos"); r.add("uv"); r.add("normal"); return r; }
        Iterable<Object> parts = o instanceof List ? (List<Object>) o : List.of((Object[]) String.valueOf(o).split("[,\\s]+"));
        for (Object p : parts) {
            String a = String.valueOf(p).trim().toLowerCase();
            if (a.isEmpty()) continue;
            if (a.equals("position") || a.equals("xyz") || a.equals("p")) a = "pos";
            if (a.equals("texcoord") || a.equals("st")) a = "uv";
            if (a.equals("n") || a.equals("nrm")) a = "normal";
            r.add(a);
        }
        if (!r.contains("pos")) throw new IllegalArgumentException("layout must contain \"pos\"");
        return r;
    }

    private static int attrSize(String a) {
        return switch (a) {
            case "pos", "normal" -> 3;
            case "uv" -> 2;
            case "color", "rgba" -> 4;
            default -> {
                if (a.startsWith("pad")) {
                    String d = a.substring(3).replaceAll("[^0-9]", "");
                    yield d.isEmpty() ? 1 : Integer.parseInt(d);
                }
                throw new IllegalArgumentException("unknown layout attribute '" + a + "' (use pos, uv, normal, color, padN)");
            }
        };
    }

    private static Object first(Map<String, Object> m, String... keys) {
        for (String k : keys) if (m.get(k) != null) return m.get(k);
        return null;
    }

    private static double num(Object o) {
        if (o instanceof Number x) return x.doubleValue();
        throw new IllegalArgumentException("expected a number, got " + o);
    }

    private static float[] flat(Object o) {
        List<Double> acc = new ArrayList<>();
        flatten(o, acc);
        float[] r = new float[acc.size()];
        for (int k = 0; k < r.length; k++) r[k] = acc.get(k).floatValue();
        return r;
    }

    private static int[] flatInt(Object o) {
        List<Double> acc = new ArrayList<>();
        flatten(o, acc);
        int[] r = new int[acc.size()];
        for (int k = 0; k < r.length; k++) r[k] = (int) Math.round(acc.get(k));
        return r;
    }

    private static void flatten(Object o, List<Double> acc) {
        if (o instanceof List<?> l) { for (Object x : l) flatten(x, acc); }
        else if (o instanceof Number x) acc.add(x.doubleValue());
        else throw new IllegalArgumentException("expected numbers, got " + o);
    }
}
