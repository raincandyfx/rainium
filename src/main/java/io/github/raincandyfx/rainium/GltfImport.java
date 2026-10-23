package io.github.raincandyfx.rainium;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * glTF 2.0 (.gltf / .glb) -> the same intermediate model the OBJ import uses, so every import
 * option (axes, units, size, splitting, normals) works the same. Same rules as mesh_convert.py:
 * node transforms are applied, only triangle primitives are read, each mesh primitive becomes its
 * own part (named after the node, plus the material when a mesh has several).
 *
 * UVs are stored the OBJ way up (v = 0 at the bottom) so the import's default "Flip texture
 * vertically" gives the right result for both formats. The first PNG base-colour texture is
 * returned so it can be saved next to the mesh.
 */
final class GltfImport {
    private GltfImport() {}

    record Loaded(ObjImport.Parsed parsed, byte[] png, int textures) {}

    private static final int JSON = 0x4E4F534A, BIN = 0x004E4942;

    @SuppressWarnings("unchecked")
    static Loaded load(byte[] data, Path baseDir) throws Exception {
        Map<String, Object> doc;
        byte[] glbBin = null;
        if (data.length >= 12 && data[0] == 'g' && data[1] == 'l' && data[2] == 'T' && data[3] == 'F') {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int off = 12;
            doc = null;
            while (off + 8 <= data.length) {
                int len = b.getInt(off), type = b.getInt(off + 4);
                if (off + 8 + len > data.length) throw new IllegalArgumentException("the .glb file is cut short");
                if (type == JSON) doc = (Map<String, Object>) Json.parse(new String(data, off + 8, len, StandardCharsets.UTF_8));
                else if (type == BIN) { glbBin = new byte[len]; System.arraycopy(data, off + 8, glbBin, 0, len); }
                off += 8 + len;
            }
            if (doc == null) throw new IllegalArgumentException("no JSON chunk in the .glb file");
        } else {
            doc = (Map<String, Object>) Json.parse(new String(data, StandardCharsets.UTF_8));
        }

        List<byte[]> buffers = new ArrayList<>();
        for (Object o : list(doc.get("buffers"))) {
            Map<String, Object> buf = (Map<String, Object>) o;
            buffers.add(resource(buf.get("uri"), glbBin, baseDir));
        }
        G g = new G(doc, buffers);

        FloatList P = new FloatList(), T = new FloatList(), N = new FloatList();
        List<ObjImport.Face> faces = new ArrayList<>();
        Map<String, Integer> usedNames = new HashMap<>();
        List<Object> nodes = list(doc.get("nodes"));
        List<Integer> roots = new ArrayList<>();
        List<Object> scenes = list(doc.get("scenes"));
        if (!scenes.isEmpty()) {
            int si = doc.get("scene") instanceof Number n ? n.intValue() : 0;
            for (Object r : list(((Map<String, Object>) scenes.get(Math.min(si, scenes.size() - 1))).get("nodes"))) roots.add(((Number) r).intValue());
        } else {
            boolean[] child = new boolean[nodes.size()];
            for (Object n : nodes) for (Object c : list(((Map<String, Object>) n).get("children"))) child[((Number) c).intValue()] = true;
            for (int i = 0; i < nodes.size(); i++) if (!child[i]) roots.add(i);
        }
        for (int r : roots) visit(g, nodes, r, identity(), P, T, N, faces, usedNames, 0);

        // first PNG base-colour texture (and how many textures the model uses)
        byte[] png = null;
        java.util.Set<Integer> texImages = new java.util.LinkedHashSet<>();
        for (Object m : list(doc.get("materials"))) {
            Map<String, Object> pbr = map(((Map<String, Object>) m).get("pbrMetallicRoughness"));
            Map<String, Object> tex = map(pbr.get("baseColorTexture"));
            if (tex.get("index") instanceof Number ti) {
                Map<String, Object> t = (Map<String, Object>) list(doc.get("textures")).get(ti.intValue());
                if (t.get("source") instanceof Number src) texImages.add(src.intValue());
            }
        }
        for (int img : texImages) {
            Map<String, Object> im = (Map<String, Object>) list(doc.get("images")).get(img);
            byte[] bytes;
            if (im.get("bufferView") instanceof Number bv) bytes = g.view(bv.intValue());
            else bytes = resource(im.get("uri"), null, baseDir);
            if (bytes != null && bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') { png = bytes; break; }
        }
        return new Loaded(new ObjImport.Parsed(P.toArray(), T.toArray(), N.toArray(), faces, 0), png, texImages.size());
    }

    @SuppressWarnings("unchecked")
    private static void visit(G g, List<Object> nodes, int ni, double[] parent, FloatList P, FloatList T, FloatList N,
                              List<ObjImport.Face> faces, Map<String, Integer> usedNames, int depth) {
        if (depth > 64 || ni < 0 || ni >= nodes.size()) return;
        Map<String, Object> node = (Map<String, Object>) nodes.get(ni);
        double[] M = mul(parent, nodeMatrix(node));
        if (node.get("mesh") instanceof Number mi) {
            Map<String, Object> mesh = (Map<String, Object>) list(g.doc.get("meshes")).get(mi.intValue());
            List<Object> prims = list(mesh.get("primitives"));
            double det = M[0] * (M[5] * M[10] - M[6] * M[9]) - M[1] * (M[4] * M[10] - M[6] * M[8]) + M[2] * (M[4] * M[9] - M[5] * M[8]);
            for (int pi = 0; pi < prims.size(); pi++) {
                Map<String, Object> prim = (Map<String, Object>) prims.get(pi);
                if (prim.get("mode") instanceof Number md && md.intValue() != 4) continue;   // triangles only
                Map<String, Object> at = map(prim.get("attributes"));
                if (!(at.get("POSITION") instanceof Number)) continue;
                float[][] pos = g.accessor(((Number) at.get("POSITION")).intValue());
                float[][] uv = at.get("TEXCOORD_0") instanceof Number u ? g.accessor(u.intValue()) : null;
                float[][] nrm = at.get("NORMAL") instanceof Number n ? g.accessor(n.intValue()) : null;
                int[] idx;
                if (prim.get("indices") instanceof Number ii) {
                    float[][] a = g.accessor(ii.intValue());
                    idx = new int[a.length];
                    for (int k = 0; k < a.length; k++) idx[k] = (int) a[k][0];
                } else {
                    idx = new int[pos.length];
                    for (int k = 0; k < idx.length; k++) idx[k] = k;
                }
                int pBase = P.size / 3, tBase = T.size / 2, nBase = N.size / 3;
                for (float[] p : pos) {
                    for (int r = 0; r < 3; r++) P.add((float) (M[r * 4] * p[0] + M[r * 4 + 1] * p[1] + M[r * 4 + 2] * p[2] + M[r * 4 + 3]));
                }
                if (uv != null) for (float[] t : uv) { T.add(t[0]); T.add(1f - t[1]); }
                if (nrm != null) for (float[] n : nrm) {
                    double x = M[0] * n[0] + M[1] * n[1] + M[2] * n[2], y = M[4] * n[0] + M[5] * n[1] + M[6] * n[2], z = M[8] * n[0] + M[9] * n[1] + M[10] * n[2];
                    double l = Math.sqrt(x * x + y * y + z * z);
                    if (l == 0) l = 1;
                    N.add((float) (x / l)); N.add((float) (y / l)); N.add((float) (z / l));
                }
                String name = str(node.get("name"), str(mesh.get("name"), "mesh"));
                String mat = "";
                if (prim.get("material") instanceof Number m) {
                    List<Object> mats = list(g.doc.get("materials"));
                    mat = m.intValue() < mats.size() ? str(((Map<String, Object>) mats.get(m.intValue())).get("name"), String.valueOf(m)) : String.valueOf(m);
                }
                if (prims.size() > 1 && mat.isEmpty()) mat = String.valueOf(pi);
                String key = name + "|" + mat;
                int used = usedNames.merge(key, 1, Integer::sum);
                String obj = used > 1 ? name + "_" + used : name;
                for (int k = 0; k + 2 < idx.length; k += 3) {
                    int a = idx[k], b = idx[k + 1], c = idx[k + 2];
                    if (det < 0) { int t = b; b = c; c = t; }   // mirrored node: keep front faces in front
                    int[][] refs = new int[3][];
                    int[] tri = {a, b, c};
                    for (int j = 0; j < 3; j++) {
                        int v = tri[j];
                        if (v < 0 || v >= pos.length) { refs = null; break; }
                        refs[j] = new int[]{pBase + v, uv != null ? tBase + v : -1, nrm != null ? nBase + v : -1};
                    }
                    if (refs != null) faces.add(new ObjImport.Face(obj, "", mat, refs));
                }
            }
        }
        for (Object c : list(node.get("children"))) visit(g, nodes, ((Number) c).intValue(), M, P, T, N, faces, usedNames, depth + 1);
    }

    // ---------------------------------------------------------------- buffers and accessors
    private static final class G {
        final Map<String, Object> doc;
        final List<byte[]> buffers;
        G(Map<String, Object> doc, List<byte[]> buffers) { this.doc = doc; this.buffers = buffers; }

        @SuppressWarnings("unchecked")
        byte[] view(int i) {
            Map<String, Object> bv = (Map<String, Object>) list(doc.get("bufferViews")).get(i);
            byte[] buf = buffers.get(((Number) bv.get("buffer")).intValue());
            int off = intOf(bv.get("byteOffset"), 0), len = intOf(bv.get("byteLength"), 0);
            byte[] out = new byte[len];
            System.arraycopy(buf, off, out, 0, len);
            return out;
        }

        @SuppressWarnings("unchecked")
        float[][] accessor(int i) {
            Map<String, Object> a = (Map<String, Object>) list(doc.get("accessors")).get(i);
            int comp = intOf(a.get("componentType"), 5126);
            int n = switch (String.valueOf(a.get("type"))) {
                case "SCALAR" -> 1; case "VEC2" -> 2; case "VEC3" -> 3; case "VEC4" -> 4;
                case "MAT2" -> 4; case "MAT3" -> 9; case "MAT4" -> 16;
                default -> throw new IllegalArgumentException("unknown accessor type " + a.get("type"));
            };
            int count = intOf(a.get("count"), 0);
            float[][] out = new float[count][n];
            if (!(a.get("bufferView") instanceof Number bvi)) return out;   // all zeros, per the spec
            Map<String, Object> bv = (Map<String, Object>) list(doc.get("bufferViews")).get(bvi.intValue());
            byte[] buf = buffers.get(((Number) bv.get("buffer")).intValue());
            if (buf == null) throw new IllegalArgumentException("a buffer is missing");
            int size = switch (comp) { case 5120, 5121 -> 1; case 5122, 5123 -> 2; case 5125, 5126 -> 4; default -> throw new IllegalArgumentException("unknown component type " + comp); };
            int start = intOf(bv.get("byteOffset"), 0) + intOf(a.get("byteOffset"), 0);
            int stride = intOf(bv.get("byteStride"), 0);
            if (stride == 0) stride = size * n;
            boolean normalized = Boolean.TRUE.equals(a.get("normalized"));
            ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < count; k++) {
                int o = start + k * stride;
                for (int c = 0; c < n; c++) {
                    int at = o + c * size;
                    float v = switch (comp) {
                        case 5126 -> b.getFloat(at);
                        case 5125 -> (float) Integer.toUnsignedLong(b.getInt(at));
                        case 5123 -> (float) (b.getShort(at) & 0xFFFF);
                        case 5122 -> (float) b.getShort(at);
                        case 5121 -> (float) (b.get(at) & 0xFF);
                        default -> (float) b.get(at);
                    };
                    if (normalized && comp != 5126) {
                        float max = switch (comp) { case 5121 -> 255f; case 5120 -> 127f; case 5123 -> 65535f; case 5122 -> 32767f; default -> 4294967295f; };
                        v = Math.max(v / max, -1f);
                    }
                    out[k][c] = v;
                }
            }
            return out;
        }
    }

    private static byte[] resource(Object uri, byte[] glbBin, Path baseDir) throws Exception {
        if (uri == null) return glbBin;
        String u = String.valueOf(uri);
        if (u.startsWith("data:")) return Base64.getDecoder().decode(u.substring(u.indexOf(',') + 1));
        if (baseDir == null) throw new IllegalArgumentException("the model refers to another file: " + u);
        Path p = baseDir.resolve(java.net.URLDecoder.decode(u, StandardCharsets.UTF_8)).normalize();
        if (!p.startsWith(baseDir)) throw new IllegalArgumentException("the model refers to a file outside its folder");
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("missing file next to the model: " + u);
        return Files.readAllBytes(p);
    }

    // ---------------------------------------------------------------- matrices (row-major 4x4)
    private static double[] identity() { return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}; }

    private static double[] nodeMatrix(Map<String, Object> node) {
        List<Object> mm = list(node.get("matrix"));
        if (mm.size() == 16) {   // glTF stores column-major
            double[] r = new double[16];
            for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) r[rr * 4 + c] = d(mm.get(c * 4 + rr));
            return r;
        }
        double[] t = vec(node.get("translation"), 3, 0), q = vec(node.get("rotation"), 4, 0), s = vec(node.get("scale"), 3, 1);
        if (list(node.get("rotation")).size() != 4) q = new double[]{0, 0, 0, 1};
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double[][] R = {
            {1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)},
            {2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)},
            {2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)}};
        return new double[]{
            R[0][0] * s[0], R[0][1] * s[1], R[0][2] * s[2], t[0],
            R[1][0] * s[0], R[1][1] * s[1], R[1][2] * s[2], t[1],
            R[2][0] * s[0], R[2][1] * s[1], R[2][2] * s[2], t[2],
            0, 0, 0, 1};
    }

    private static double[] mul(double[] A, double[] B) {
        double[] r = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
            double v = 0;
            for (int k = 0; k < 4; k++) v += A[i * 4 + k] * B[k * 4 + j];
            r[i * 4 + j] = v;
        }
        return r;
    }

    // ---------------------------------------------------------------- small helpers
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }
    private static String str(Object o, String def) { return o instanceof String s && !s.isBlank() ? s : def; }
    private static int intOf(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }
    private static double d(Object o) { return o instanceof Number n ? n.doubleValue() : 0; }
    private static double[] vec(Object o, int n, double def) {
        List<Object> l = list(o);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = i < l.size() ? d(l.get(i)) : def;
        return r;
    }

    private static final class FloatList {
        float[] a = new float[256];
        int size;
        void add(float v) { if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2); a[size++] = v; }
        float[] toArray() { return java.util.Arrays.copyOf(a, size); }
    }
}
