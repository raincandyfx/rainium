package io.github.raincandyfx.rainium;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Folders and zips dropped into the rainium folder: finds the models and textures inside, matches each
 * model with its texture, and turns the lot into layers (in one folder) with a single click.
 * Zips are unpacked into imports/&lt;name&gt;/, which is also where converted meshes and any texture
 * that had to be extracted or converted are written; a dropped folder is left untouched.
 */
final class Bundles {
    private Bundles() {}

    /** A folder or zip that holds at least one model. rel is its path inside the rainium folder. */
    record Found(String name, String rel, boolean zip, int models, int textures) {}

    /** The result of adding one: the layers (already in their folder, if any) and what needs the user's eye. */
    record Added(String folder, List<LayerConfig.Layer> layers, int models, int textured, List<String> notes) {}

    private static final Set<String> RESERVED = Set.of("presets", "sets", "exports", "captures", "imports", "logs");
    private static final Set<String> MODEL_EXT = Set.of("obj", "gltf", "glb");
    private static final Set<String> KEEP_EXT = Set.of("obj", "mtl", "gltf", "glb", "bin", "png", "jpg", "jpeg", "bmp", "gif");
    private static final Set<String> PICTURE_EXT = Set.of("png", "jpg", "jpeg", "bmp", "gif", "tga", "webp");
    private static final int MAX_DEPTH = 6, MAX_FILES = 4000;
    private static final long MAX_UNPACKED = 400L << 20, MAX_ENTRY = 200L << 20;

    // ------------------------------------------------------------------ finding them
    private static final Map<String, int[]> zipCache = new ConcurrentHashMap<>();   // path@time@size -> {models, textures, hasSet}

    static List<Found> scan(Path dir) {
        List<Found> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.startsWith(".")) continue;
                if (Files.isDirectory(p)) {
                    if (RESERVED.contains(n.toLowerCase(Locale.ROOT))) continue;
                    int[] c = count(p);
                    if (c[0] > 0) out.add(new Found(n, n, false, c[0], c[1]));
                } else if (n.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    int[] c = countZip(p);
                    if (c != null && c[2] == 0 && c[0] > 0) out.add(new Found(n.substring(0, n.length() - 4), n, true, c[0], c[1]));
                }
            }
        } catch (IOException ignored) { }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private static int[] count(Path root) {
        int[] c = new int[2];
        int[] seen = {0};
        walk(root, 0, seen, p -> {
            String e = ext(p.getFileName().toString());
            if (MODEL_EXT.contains(e)) c[0]++;
            else if (PICTURE_EXT.contains(e)) c[1]++;
        });
        return c;
    }

    private static int[] countZip(Path p) {
        String key;
        try { key = p + "@" + Files.getLastModifiedTime(p).toMillis() + "@" + Files.size(p); } catch (IOException e) { return null; }
        int[] hit = zipCache.get(key);
        if (hit != null) return hit;
        int[] c = new int[3];
        try (ZipFile z = new ZipFile(p.toFile())) {
            var en = z.entries();
            int n = 0;
            while (en.hasMoreElements() && n++ < 20000) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName().toLowerCase(Locale.ROOT);
                if (name.equals("set.json")) c[2] = 1;
                if (name.contains("__macosx/")) continue;
                String x = ext(name);
                if (MODEL_EXT.contains(x)) c[0]++;
                else if (PICTURE_EXT.contains(x)) c[1]++;
            }
        } catch (Exception e) {
            return null;
        }
        zipCache.put(key, c);
        return c;
    }

    private static void walk(Path folder, int depth, int[] seen, java.util.function.Consumer<Path> each) {
        if (depth > MAX_DEPTH) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                if (seen[0]++ > MAX_FILES) return;
                String n = p.getFileName().toString();
                if (n.startsWith(".") || n.equalsIgnoreCase("__MACOSX")) continue;
                if (Files.isDirectory(p)) walk(p, depth + 1, seen, each);
                else each.accept(p);
            }
        } catch (IOException ignored) { }
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    // ------------------------------------------------------------------ adding one
    /** Unpacks (zips), converts every model, matches textures, and returns the layers. Runs off the main thread: touches only files. */
    static Added add(Path dir, Found f, String folderName) throws IOException {
        List<String> notes = new ArrayList<>();
        String outRel = "imports/" + Assets.sanitizeFile(f.name());
        Path out = dir.resolve(outRel);
        Files.createDirectories(out);
        Path root;
        String rootRel;
        if (f.zip()) {
            Path target = out.resolve("files");
            unpack(dir.resolve(f.rel()), target);
            root = target;
            rootRel = outRel + "/files";
        } else {
            root = dir.resolve(f.rel());
            rootRel = f.rel();
        }

        List<Path> models = new ArrayList<>();
        List<Path> pictures = new ArrayList<>();
        Map<String, Path> byName = new HashMap<>();   // lower-case file name -> path, for textures named by a model file
        walk(root, 0, new int[]{0}, p -> {
            String e = ext(p.getFileName().toString());
            if (MODEL_EXT.contains(e)) models.add(p);
            else if (PICTURE_EXT.contains(e)) { pictures.add(p); byName.putIfAbsent(p.getFileName().toString().toLowerCase(Locale.ROOT), p); }
        });
        models.sort((a, b) -> a.toString().compareToIgnoreCase(b.toString()));
        if (models.isEmpty()) throw new IOException("no models found");

        // every picture as a PNG the game can read (JPG and similar are converted; the rest are reported once)
        Map<Path, String> pngRel = new HashMap<>();   // original file -> path of a PNG, relative to the rainium folder
        Set<String> unreadable = new LinkedHashSet<>();
        for (Path p : pictures) {
            String name = p.getFileName().toString(), e = ext(name);
            if (e.equals("png")) { pngRel.put(p, rel(dir, p)); continue; }
            Path png = out.resolve("converted").resolve(Assets.sanitizeFile(stem(name)) + "_" + Integer.toHexString(rel(root, p).hashCode()) + ".png");
            if (convertToPng(p, png)) pngRel.put(p, rel(dir, png));
            else unreadable.add(name);
        }
        List<String> pngList = new ArrayList<>(new LinkedHashSet<>(pngRel.values()));

        List<LayerConfig.Layer> layers = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        int textured = 0;
        for (Path model : models) {
            String modelName = model.getFileName().toString();
            String base = stem(modelName);
            try {
                ObjImport.Parsed parsed;
                byte[] embedded = null;
                Map<String, String> materialTex = new HashMap<>();   // material name -> picture file
                String lower = modelName.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".gltf") || lower.endsWith(".glb")) {
                    GltfImport.Loaded ld = GltfImport.load(Files.readAllBytes(model), model.getParent());
                    parsed = ld.parsed();
                    embedded = ld.png();
                    if (ld.textures() > 1) notes.add(Lang.t("bundle.note.extra_textures", modelName, ld.textures() - 1));
                } else {
                    String text = Files.readString(model, StandardCharsets.UTF_8);
                    parsed = ObjImport.parse(text);
                    materialTextures(model, text, byName, materialTex);
                }

                // the model's own texture: the one its material file names, else a PNG inside the model, else the best name match
                ObjImport.Options opt = new ObjImport.Options();
                Map<String, String> distinct = new HashMap<>();   // material -> PNG path
                for (Map.Entry<String, String> e : materialTex.entrySet()) {
                    Path pic = byName.get(e.getValue().toLowerCase(Locale.ROOT));
                    if (pic != null && pngRel.containsKey(pic)) distinct.put(e.getKey(), pngRel.get(pic));
                }
                if (new HashSet<>(distinct.values()).size() > 1) opt.grouping = ObjImport.Grouping.MATERIAL;   // one part per material, each with its own texture

                ObjImport.Result res = ObjImport.convert(parsed, opt);
                if (res.verts() == 0) { notes.add(Lang.t("bundle.note.no_faces", modelName)); continue; }
                double ext = Math.max(res.max()[0] - res.min()[0], Math.max(res.max()[1] - res.min()[1], res.max()[2] - res.min()[2]));
                if (ext > 6 || ext < 0.2) {   // clearly not in blocks: fit it to two blocks across (about a player's height)
                    opt.fitIndex = 4;
                    res = ObjImport.convert(parsed, opt);
                    notes.add(Lang.t("bundle.note.resized", modelName));
                }

                String meshFile = unique(usedNames, Assets.sanitizeFile(base)) + ".json";
                Files.writeString(out.resolve(meshFile), res.json(), StandardCharsets.UTF_8);
                String meshRel = outRel + "/" + meshFile;

                String modelTex = null;
                if (embedded != null) {
                    Path ep = out.resolve("embedded").resolve(Assets.sanitizeFile(base) + ".png");
                    Files.createDirectories(ep.getParent());
                    Files.write(ep, embedded);
                    modelTex = rel(dir, ep);
                } else if (distinct.size() == 1 || new HashSet<>(distinct.values()).size() == 1) {
                    modelTex = distinct.values().iterator().next();
                }
                if (modelTex == null && distinct.isEmpty()) modelTex = pickTexture(rel(dir, model), pngList, null);

                List<String> parts = res.groups();
                boolean perPart = parts.size() > 1;
                boolean anyTex = false;
                for (String part : parts) {
                    LayerConfig.Layer l = LayerConfig.newLayer(meshRel);
                    l.groups = new ArrayList<>(List.of(part));
                    l.label = models.size() > 1 && perPart ? base + " · " + part : models.size() > 1 ? base : part;
                    String tex = distinct.get(part);
                    if (tex == null && modelTex != null) tex = modelTex;
                    l.texture = tex;
                    if (tex != null) anyTex = true;
                    layers.add(l);
                }
                if (!perPart) {   // a one-part model: the whole model, no part filter
                    layers.get(layers.size() - 1).groups = null;
                }
                if (anyTex) textured++;
                else notes.add(Lang.t("bundle.note.no_texture", modelName));
            } catch (Exception e) {
                notes.add(Lang.t("bundle.note.failed", modelName, String.valueOf(e.getMessage())));
            }
        }
        if (layers.isEmpty()) throw new IOException(notes.isEmpty() ? "nothing could be imported" : notes.get(0));
        for (String u : unreadable) notes.add(Lang.t("bundle.note.texture_format", u));
        String folder = layers.size() > 1 ? folderName : null;
        if (folder != null) for (LayerConfig.Layer l : layers) l.folder = folder;
        else layers.get(0).label = folderName;
        return new Added(folder, layers, models.size(), textured, notes);
    }

    private static String unique(Set<String> used, String base) {
        String n = base;
        for (int i = 2; !used.add(n.toLowerCase(Locale.ROOT)); i++) n = base + "_" + i;
        return n;
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ unpacking
    private static void unpack(Path zip, Path target) throws IOException {
        Files.createDirectories(target);
        Path base = target.toAbsolutePath().normalize();
        long total = 0;
        int files = 0;
        try (ZipFile z = new ZipFile(zip.toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory() || name.startsWith("/") || name.contains(":") || name.toLowerCase(Locale.ROOT).contains("__macosx/")) continue;
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (fileName.startsWith(".") || !KEEP_EXT.contains(ext(fileName))) continue;
                if (++files > 3000) throw new IOException("too many files in the zip");
                Path dest = base.resolve(name).normalize();
                if (!dest.startsWith(base)) continue;   // a path that climbs out of the folder: never written
                Files.createDirectories(dest.getParent());
                long wrote = 0;
                try (InputStream in = z.getInputStream(e); OutputStream o = Files.newOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        wrote += r;
                        total += r;
                        if (wrote > MAX_ENTRY || total > MAX_UNPACKED) throw new IOException("the zip is too large to unpack");
                        o.write(buf, 0, r);
                    }
                }
            }
        }
    }

    private static boolean convertToPng(Path src, Path dest) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(src.toFile());
            if (img == null) return false;
            Files.createDirectories(dest.getParent());
            return javax.imageio.ImageIO.write(img, "png", dest.toFile());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ matching textures
    /** Material name -> texture file name, read from the .mtl file(s) an OBJ names (map_Kd lines). */
    private static void materialTextures(Path obj, String text, Map<String, Path> unused, Map<String, String> out) {
        List<String> mtlNames = new ArrayList<>();
        for (String line : text.split("\\R", -1)) {
            String t = line.trim();
            if (t.length() > 7 && t.regionMatches(true, 0, "mtllib", 0, 6) && Character.isWhitespace(t.charAt(6))) mtlNames.add(t.substring(7).trim());
        }
        for (String m : mtlNames) {
            Path mtl = obj.getParent().resolve(m.replace('\\', '/')).normalize();
            if (!Files.isRegularFile(mtl)) mtl = obj.getParent().resolve(m.substring(m.replace('\\', '/').lastIndexOf('/') + 1));
            if (!Files.isRegularFile(mtl)) continue;
            String cur = null;
            try {
                for (String line : Files.readString(mtl, StandardCharsets.UTF_8).split("\\R", -1)) {
                    String t = line.trim();
                    String low = t.toLowerCase(Locale.ROOT);
                    if (low.startsWith("newmtl ")) cur = t.substring(7).trim();
                    else if (cur != null && low.startsWith("map_kd ")) {
                        String file = textureName(t.substring(7).trim());
                        if (!file.isEmpty()) out.putIfAbsent(cur, file.substring(file.replace('\\', '/').lastIndexOf('/') + 1));
                    }
                }
            } catch (IOException ignored) { }
        }
    }

    /** A map_Kd value without its options (-s 1 1 1, -o 0 0, -bm 1, ...). */
    private static String textureName(String v) {
        String[] t = v.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (t[i].startsWith("-") && t[i].length() > 1 && !Character.isDigit(t[i].charAt(1))) {
                while (i + 1 < t.length && t[i + 1].matches("-?[0-9.]+")) i++;
                continue;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(t[i]);
        }
        return sb.toString();
    }

    private static final Set<String> COLOUR_WORDS = Set.of("diffuse", "albedo", "basecolor", "base", "color", "colour", "col", "tex", "texture", "diff", "skin", "map", "uv", "main", "atlas", "d", "c");
    private static final Set<String> OTHER_MAPS = Set.of("normal", "normals", "nrm", "norm", "bump", "height", "disp", "displacement", "spec", "specular",
        "gloss", "glossiness", "rough", "roughness", "metal", "metallic", "metalness", "ao", "occlusion", "orm", "mask", "opacity", "alpha",
        "emissive", "emission", "emit", "glow", "lightmap", "env", "cube");

    private static List<String> tokens(String file) {
        String s = stem(file.substring(file.lastIndexOf('/') + 1)).replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String t : s.split("[^a-z0-9]+")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static String core(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) if (!COLOUR_WORDS.contains(t) && !OTHER_MAPS.contains(t)) sb.append(t);
        return sb.toString();
    }

    private static boolean isOtherMap(List<String> tokens) {
        for (String t : tokens) if (OTHER_MAPS.contains(t)) return true;
        return false;
    }

    private static String dirOf(String p) {
        int s = p.lastIndexOf('/');
        return s < 0 ? "" : p.substring(0, s);
    }

    /**
     * The texture that belongs to a model: names are compared ("dragon.obj" and "dragon_diffuse.png"), a
     * texture next to the model counts for more, and maps that aren't colour (normal, specular, glow...)
     * are never chosen. Null when nothing fits or several fit equally well.
     */
    static String pickTexture(String modelRel, List<String> pngRels, String unused) {
        List<String> mt = tokens(modelRel);
        String m = core(mt), md = dirOf(modelRel);
        String best = null;
        double bestScore = 0;
        boolean tie = false;
        List<String> colour = new ArrayList<>();
        for (String png : pngRels) {
            List<String> pt = tokens(png);
            if (isOtherMap(pt)) continue;
            colour.add(png);
            String n = core(pt);
            double s = 0;
            if (!m.isEmpty() && !n.isEmpty()) {
                if (m.equals(n)) s += 100;
                else if ((n.length() >= 3 && m.contains(n)) || (m.length() >= 3 && n.contains(m))) s += 60;
                else {
                    Set<String> a = new HashSet<>(mt), b = new HashSet<>(pt), both = new HashSet<>(a);
                    both.retainAll(b);
                    a.addAll(b);
                    if (!a.isEmpty()) s += 40.0 * both.size() / a.size();
                }
            }
            String pd = dirOf(png);
            if (pd.equals(md)) s += 25;
            else if (dirOf(pd).equals(dirOf(md)) || pd.startsWith(md) || md.startsWith(pd)) s += 5;
            for (String t : pt) if (COLOUR_WORDS.contains(t) && t.length() > 2) { s += 5; break; }
            if (s > bestScore + 1e-9) { best = png; bestScore = s; tie = false; }
            else if (Math.abs(s - bestScore) < 1e-9 && best != null) tie = true;
        }
        if (best == null) return null;
        if (bestScore >= 55) return best;           // a name that matches
        if (colour.size() == 1) return colour.get(0);   // the only colour texture there is
        return tie ? null : (bestScore >= 25 ? best : null);   // otherwise only if one is clearly nearest
    }
}
