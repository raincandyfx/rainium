#!/usr/bin/env python3
"""Convert OBJ, glTF (.gltf) or GLB models into a Rainium mesh JSON mesh.

No third-party packages needed (Python 3.8+).

    python mesh_convert.py model.glb wings.json
    python mesh_convert.py model.obj wings.json --scale 0.0625 --offset 0 0.2 0.15

Output is in Minecraft player-model space (+X = player's left, +Y = down, +Z = behind,
1 unit = 1 block) unless --keep-axes is given. Input is assumed to be Y-up with the
character facing +Z (glTF convention, and Blender's default OBJ/glTF export), which is
turned into model space by rotating 180 degrees about X: (x, y, z) -> (x, -y, -z).
If your model faces -Z instead, add --facing-neg-z.

Each OBJ object/group/material, or each glTF mesh primitive, becomes its own group, so
you can give parts different textures and render types in layers.json.
"""
import argparse, base64, json, math, os, struct, sys
from collections import OrderedDict


# ----------------------------------------------------------------------------- OBJ
def load_obj(path):
    P, T, N = [], [], []
    groups = OrderedDict()
    cur_obj, cur_mtl = None, None

    def group():
        name = "_".join(x for x in (cur_obj, cur_mtl) if x) or "main"
        return groups.setdefault(name, {"key": {}, "pos": [], "uv": [], "nrm": [], "tris": [], "has_uv": False, "has_n": False})

    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.split()
            if not parts or parts[0].startswith("#"):
                continue
            tag = parts[0]
            if tag == "v":
                P.append([float(x) for x in parts[1:4]])
            elif tag == "vt":
                uv = [float(x) for x in parts[1:3]] + [0.0] * (2 - len(parts[1:3]))
                T.append([uv[0], 1.0 - uv[1]])        # OBJ V is bottom-up; Minecraft is top-down
            elif tag == "vn":
                N.append([float(x) for x in parts[1:4]])
            elif tag in ("o", "g"):
                cur_obj = "_".join(parts[1:]) or None
            elif tag == "usemtl":
                cur_mtl = "_".join(parts[1:]) or None
            elif tag == "f":
                g = group()
                idx = []
                for ref in parts[1:]:
                    comps = ref.split("/")
                    def res(i, arr):
                        if len(comps) <= i or comps[i] == "":
                            return None
                        k = int(comps[i])
                        return k - 1 if k > 0 else len(arr) + k
                    key = (res(0, P), res(1, T), res(2, N))
                    if key not in g["key"]:
                        g["key"][key] = len(g["pos"]) // 3
                        g["pos"] += P[key[0]]
                        g["uv"] += T[key[1]] if key[1] is not None else [0.0, 0.0]
                        g["nrm"] += N[key[2]] if key[2] is not None else [0.0, 0.0, 0.0]
                        g["has_uv"] |= key[1] is not None
                        g["has_n"] |= key[2] is not None
                    idx.append(g["key"][key])
                for k in range(1, len(idx) - 1):
                    g["tris"] += [idx[0], idx[k], idx[k + 1]]
    return [(name, g["pos"], g["uv"] if g["has_uv"] else None, g["nrm"] if g["has_n"] else None, g["tris"])
            for name, g in groups.items() if g["tris"]]


# ---------------------------------------------------------------------------- glTF
COMP = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def load_gltf(path):
    with open(path, "rb") as f:
        data = f.read()
    glb_bin = None
    if data[:4] == b"glTF":
        off = 12
        doc = None
        while off < len(data):
            clen, ctype = struct.unpack_from("<II", data, off)
            chunk = data[off + 8: off + 8 + clen]
            if ctype == 0x4E4F534A:
                doc = json.loads(chunk.decode("utf-8"))
            elif ctype == 0x004E4942:
                glb_bin = chunk
            off += 8 + clen
    else:
        doc = json.loads(data.decode("utf-8"))
    base = os.path.dirname(os.path.abspath(path))
    buffers = []
    for b in doc.get("buffers", []):
        uri = b.get("uri")
        if uri is None:
            buffers.append(glb_bin)
        elif uri.startswith("data:"):
            buffers.append(base64.b64decode(uri.split(",", 1)[1]))
        else:
            with open(os.path.join(base, uri), "rb") as f:
                buffers.append(f.read())

    def accessor(i):
        a = doc["accessors"][i]
        fmt, size = COMP[a["componentType"]]
        n = NCOMP[a["type"]]
        count = a["count"]
        if "bufferView" not in a:
            return [[0.0] * n for _ in range(count)]
        bv = doc["bufferViews"][a["bufferView"]]
        buf = buffers[bv["buffer"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride") or size * n
        out = []
        for k in range(count):
            vals = list(struct.unpack_from("<" + fmt * n, buf, start + k * stride))
            if a.get("normalized") and fmt != "f":
                mx = float((1 << (size * 8 - (0 if fmt.isupper() else 1))) - 1)
                vals = [max(v / mx, -1.0) for v in vals]
            out.append(vals)
        return out

    def node_matrix(node):
        if "matrix" in node:
            m = node["matrix"]
            return [[m[c * 4 + r] for c in range(4)] for r in range(4)]
        t = node.get("translation", [0, 0, 0])
        x, y, z, w = node.get("rotation", [0, 0, 0, 1])
        s = node.get("scale", [1, 1, 1])
        R = [[1 - 2 * (y*y + z*z), 2 * (x*y - z*w), 2 * (x*z + y*w)],
             [2 * (x*y + z*w), 1 - 2 * (x*x + z*z), 2 * (y*z - x*w)],
             [2 * (x*z - y*w), 2 * (y*z + x*w), 1 - 2 * (x*x + y*y)]]
        return [[R[r][0] * s[0], R[r][1] * s[1], R[r][2] * s[2], t[r]] for r in range(3)] + [[0, 0, 0, 1]]

    def mul(A, B):
        return [[sum(A[r][k] * B[k][c] for k in range(4)) for c in range(4)] for r in range(4)]

    I = [[1 if r == c else 0 for c in range(4)] for r in range(4)]
    results = []
    used_names = {}

    def visit(ni, parent):
        node = doc["nodes"][ni]
        M = mul(parent, node_matrix(node))
        if "mesh" in node:
            mesh = doc["meshes"][node["mesh"]]
            for pi, prim in enumerate(mesh["primitives"]):
                if prim.get("mode", 4) != 4:
                    continue
                at = prim["attributes"]
                pos = accessor(at["POSITION"])
                uv = accessor(at["TEXCOORD_0"]) if "TEXCOORD_0" in at else None
                nrm = accessor(at["NORMAL"]) if "NORMAL" in at else None
                idx = [v[0] for v in accessor(prim["indices"])] if "indices" in prim else list(range(len(pos)))
                P, Nn = [], []
                for p in pos:
                    P += [sum(M[r][c] * (p + [1])[c] for c in range(4)) for r in range(3)]
                if nrm:
                    for n in nrm:
                        v = [sum(M[r][c] * n[c] for c in range(3)) for r in range(3)]
                        l = math.sqrt(sum(x * x for x in v)) or 1.0
                        Nn += [x / l for x in v]
                det = (M[0][0] * (M[1][1]*M[2][2] - M[1][2]*M[2][1]) - M[0][1] * (M[1][0]*M[2][2] - M[1][2]*M[2][0])
                       + M[0][2] * (M[1][0]*M[2][1] - M[1][1]*M[2][0]))
                if det < 0:   # mirrored node: keep front faces front
                    for k in range(0, len(idx) - 2, 3):
                        idx[k + 1], idx[k + 2] = idx[k + 2], idx[k + 1]
                name = node.get("name") or mesh.get("name") or "mesh"
                if len(mesh["primitives"]) > 1:
                    mat = prim.get("material")
                    name += "_" + (doc.get("materials", [{}] * (mat + 1))[mat].get("name", str(mat)) if mat is not None else str(pi))
                n_used = used_names.get(name, 0)
                used_names[name] = n_used + 1
                if n_used:
                    name = f"{name}_{n_used + 1}"
                results.append((name, P, [c for t in uv for c in t] if uv else None, Nn if nrm else None, idx))
        for c in node.get("children", []):
            visit(c, M)

    scene = doc.get("scenes", [{}])[doc.get("scene", 0)] if doc.get("scenes") else None
    roots = scene["nodes"] if scene else [i for i in range(len(doc.get("nodes", [])))
                                           if not any(i in n.get("children", []) for n in doc["nodes"])]
    for r in roots:
        visit(r, I)
    return results


# ------------------------------------------------------------------------- output
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help=".obj, .gltf or .glb")
    ap.add_argument("output", help="output .json")
    ap.add_argument("--scale", type=float, default=1.0, help="multiply positions (e.g. 0.0625 = 1 unit -> 1 pixel)")
    ap.add_argument("--offset", type=float, nargs=3, default=[0, 0, 0], metavar=("X", "Y", "Z"),
                    help="add after conversion, in model space blocks (+Y down, +Z behind)")
    ap.add_argument("--keep-axes", action="store_true", help="do not convert axes (input is already model space)")
    ap.add_argument("--facing-neg-z", action="store_true", help="input character faces -Z instead of +Z")
    ap.add_argument("--merge", action="store_true", help="put everything into one group called 'main'")
    ap.add_argument("--fit", type=float, metavar="SIZE", help="uniformly scale so the largest dimension is SIZE blocks")
    ap.add_argument("--precision", type=int, default=5, help="decimal places (default 5)")
    a = ap.parse_args()

    ext = os.path.splitext(a.input)[1].lower()
    if ext == ".obj":
        parts = load_obj(a.input)
    elif ext in (".gltf", ".glb"):
        parts = load_gltf(a.input)
    else:
        sys.exit("unsupported input type: " + ext + " (use .obj, .gltf or .glb)")
    if not parts:
        sys.exit("no triangle geometry found in " + a.input)

    def axes(x, y, z):
        if a.keep_axes:
            return x, y, z
        if a.facing_neg_z:
            return -x, -y, z      # rotate 180 about X, then 180 about Y
        return x, -y, -z

    scale = a.scale
    if a.fit:
        allp = [axes(*p[i:i + 3]) for _, p, *_ in parts for i in range(0, len(p), 3)]
        ext3 = [max(v[k] for v in allp) - min(v[k] for v in allp) for k in range(3)]
        scale = a.fit / (max(ext3) or 1.0)

    out = OrderedDict()
    total_v = total_q = 0
    for name, P, UV, Nn, tris in parts:
        v = []
        n = len(P) // 3
        for k in range(n):
            x, y, z = axes(*P[k * 3:k * 3 + 3])
            nx, ny, nz = axes(*Nn[k * 3:k * 3 + 3]) if Nn else (0.0, 0.0, 0.0)
            u, w = (UV[k * 2], UV[k * 2 + 1]) if UV else (0.0, 0.0)
            v += [x * scale + a.offset[0], y * scale + a.offset[1], z * scale + a.offset[2], u, w, nx, ny, nz]
        q = []
        for k in range(0, len(tris) - 2, 3):
            q += [tris[k], tris[k + 1], tris[k + 2], tris[k + 2]]
        r = a.precision
        v = [round(x, r) if abs(x) > 10 ** -r else 0 for x in v]
        if not Nn:  # let the mod compute normals: use separate-array form without normals
            g = {"positions": [v[i:i + 3] for i in range(0, len(v), 8)],
                 "uvs": [v[i + 3:i + 5] for i in range(0, len(v), 8)],
                 "quads": q}
        else:
            g = {"v": v, "q": q}
        key = "main" if a.merge else name.replace(" ", "_")
        if a.merge and key in out:
            prev = out[key]
            if "v" in prev and "v" in g:
                base = len(prev["v"]) // 8
                prev["v"] += g["v"]
                prev["q"] += [i + base for i in g["q"]]
            else:
                sys.exit("--merge needs all parts to have normals (or none of them)")
        else:
            out[key] = g
        total_v += n
        total_q += len(q) // 4
    with open(a.output, "w") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {a.output}: {len(out)} group(s) [{', '.join(out)}], {total_v} vertices, {total_q} quads, scale {scale:g}")


if __name__ == "__main__":
    main()
