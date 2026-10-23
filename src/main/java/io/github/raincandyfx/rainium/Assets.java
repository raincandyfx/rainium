package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Owns everything in config/rainium: the layer config (editable from the in-game editor),
 * parsed meshes, textures registered from PNG files, and an optional import-preview layer.
 * Watches the files and reloads when any of them changes on disk.
 */
final class Assets {
    static final Logger LOG = LoggerFactory.getLogger("rainium");
    private static final String[] DEFAULT_FILES = {"layers.json", "README.txt", "mesh_convert.py"};
    private static final String NS = "rainium";

    /** One renderable layer with its mesh groups and texture resolved. */
    record Resolved(LayerConfig.Layer cfg, Mesh.Group[] groups, Identifier texture) {}

    /** Immutable snapshot used by the renderer. */
    record State(LayerConfig config, List<Resolved> layers, boolean anyPhysics) {}

    private Path dir;
    private volatile State state;
    private volatile Resolved preview;
    private LayerConfig editable;          // what the editor shows and changes
    private String lastError;
    private final Map<Path, Long> stamps = new HashMap<>();
    private final Map<String, Mesh> meshCache = new HashMap<>();
    private final Map<String, Long> meshStamp = new HashMap<>();
    private final List<Runnable> pendingTextureUploads = new ArrayList<>();
    private long nextCheckNs;
    private Identifier white;
    final Presets presets = new Presets(this);

    void init() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("rainium");
        boolean fresh = false;
        try {
            // Before the rename the folder was config/meshlayers; carry an existing setup over.
            Path old = dir.resolveSibling("meshlayers");
            if (!Files.exists(dir) && Files.isRegularFile(old.resolve("layers.json"))) {
                Files.move(old, dir);
                LOG.info("[rainium] moved the old config/meshlayers folder to config/rainium");
            }
            if (!Files.exists(dir.resolve("layers.json"))) {
                Files.createDirectories(dir);
                for (String f : DEFAULT_FILES) copyDefault(f);
                fresh = true;
                LOG.info("[rainium] created example config in {}", Privacy.redactForLog(dir.toString()));
            }
        } catch (IOException e) {
            LOG.error(Privacy.redactForLog("[rainium] could not create config folder " + dir + ": " + e));
        }
        reload();
        if (fresh) useStandardPresets();
    }

    private void copyDefault(String name) throws IOException {
        Path target = dir.resolve(name);
        if (Files.exists(target)) return;
        try (InputStream in = Assets.class.getResourceAsStream("/assets/rainium/defaults/" + name)) {
            if (in != null) Files.copy(in, target);
        }
    }

    Path dir() { return dir; }
    /** The current config problem, with personal paths redacted for display. */
    String lastError() { return lastError == null ? null : Privacy.redact(lastError); }
    Resolved preview() { return preview; }

    /** The config the editor works on. Never null after init (falls back to an empty config). */
    LayerConfig editable() {
        if (editable == null) editable = new LayerConfig();
        return editable;
    }

    /** Called from the render thread every frame. Cheap: file checks run once per second. */
    State current() {
        long now = System.nanoTime();
        if (now >= nextCheckNs) {
            nextCheckNs = now + 1_000_000_000L;
            if (changed()) reload();
        }
        if (!pendingTextureUploads.isEmpty()) {
            List<Runnable> jobs = new ArrayList<>(pendingTextureUploads);
            pendingTextureUploads.clear();
            for (Runnable r : jobs) {
                try { r.run(); } catch (Throwable t) { LOG.error(Privacy.redactForLog("[rainium] texture upload failed: " + t)); }
            }
        }
        return state;
    }

    private boolean changed() {
        for (Map.Entry<Path, Long> e : stamps.entrySet()) {
            if (stamp(e.getKey()) != e.getValue()) return true;
        }
        return false;
    }

    private static long stamp(Path p) {
        try { return Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() ^ (Files.size(p) << 20) : -1L; }
        catch (IOException e) { return -2L; }
    }

    synchronized void reload() {
        Path cfgPath = dir.resolve("layers.json");
        Map<Path, Long> newStamps = new HashMap<>();
        newStamps.put(cfgPath, stamp(cfgPath));
        try {
            LayerConfig cfg;
            try {
                cfg = LayerConfig.fromJson(Json.parse(Files.readString(cfgPath, StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("layers.json: " + e.getMessage(), e);
            }
            editable = cfg;
            Privacy.mode = cfg.privacyMode;
            Privacy.customName = cfg.privacyName;
            apply(cfg, newStamps);
            lastError = null;
        } catch (Exception e) {
            // keep the previous working state, but watch the files so a fix reloads automatically
            lastError = e.getMessage();
            LOG.error(Privacy.redactForLog("[rainium] config error, keeping previous layers: " + e.getMessage()));
        }
        stamps.clear();
        stamps.putAll(newStamps);
    }

    /** Resolve meshes and textures for every enabled layer and publish a new render state. */
    private void apply(LayerConfig cfg, Map<Path, Long> newStamps) throws IOException {
        List<Resolved> out = new ArrayList<>();
        Map<String, Identifier> texIds = new LinkedHashMap<>();
        boolean anyPhysics = false;
        for (LayerConfig.Layer l : cfg.layers) {
            if (!cfg.isActive(l)) continue;
            if (l.isParticles()) {
                out.add(new Resolved(l, new Mesh.Group[0], texture(l, texIds, newStamps)));
                anyPhysics |= l.physics;
                continue;
            }
            Path meshPath = dir.resolve(l.mesh).normalize();
            newStamps.put(meshPath, stamp(meshPath));
            Mesh mesh = mesh(l.mesh);
            List<Mesh.Group> gs = new ArrayList<>();
            if (l.groups == null) gs.addAll(mesh.groups.values());
            else for (String g : l.groups) {
                Mesh.Group mg = mesh.groups.get(g);
                if (mg == null) throw new IllegalArgumentException(l.label + ": group '" + g + "' not in " + l.mesh + " (has: " + String.join(", ", mesh.groups.keySet()) + ")");
                gs.add(mg);
            }
            Identifier tex = texture(l, texIds, newStamps);
            out.add(new Resolved(l, gs.toArray(new Mesh.Group[0]), tex));
            anyPhysics |= l.physics;
        }
        state = new State(cfg, List.copyOf(out), anyPhysics);
        LOG.info("[rainium] {} layer(s) active", out.size());
    }

    /** Load (or fetch from cache) a mesh file from the folder. Throws with a readable message on failure. */
    /** Parts of a model file for a thumbnail: mesh JSON, OBJ, glTF or GLB (imported with default options). */
    java.util.Collection<Mesh.Group> previewGroups(String file) {
        try {
            String lower = file.toLowerCase(Locale.ROOT);
            Path p = dir.resolve(file).normalize();
            if (lower.endsWith(".json")) return mesh(file).groups.values();
            ObjImport.Parsed parsed = lower.endsWith(".obj") ? ObjImport.parse(Files.readString(p))
                : GltfImport.load(Files.readAllBytes(p), p.getParent()).parsed();
            return Mesh.fromJson(Json.parse(ObjImport.convert(parsed, new ObjImport.Options()).json())).groups.values();
        } catch (Exception e) {
            return null;
        }
    }

    synchronized Mesh mesh(String file) throws IOException {
        Path meshPath = dir.resolve(file).normalize();
        long ms = stamp(meshPath);
        if (ms < 0) throw new IllegalArgumentException("mesh file not found: " + file);
        Mesh mesh = meshCache.get(file);
        if (mesh == null || meshStamp.getOrDefault(file, 0L) != ms) {
            long t0 = System.nanoTime();
            try {
                mesh = Mesh.fromJson(Json.parse(Files.readString(meshPath, StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
            }
            meshCache.put(file, mesh);
            meshStamp.put(file, ms);
            LOG.info("[rainium] loaded {} in {} ms: {}", file, (System.nanoTime() - t0) / 1_000_000, mesh.describe());
        }
        return mesh;
    }

    /**
     * First run: if any presets can be built from files already on this
     * machine, start with those (otherwise the config starts empty).
     */
    private void useStandardPresets() {
        List<LayerConfig.Layer> layers = new ArrayList<>();
        List<String> hiddenFolders = new ArrayList<>();
        for (Presets.Info p : presets.list()) {
            try {
                List<LayerConfig.Layer> got = presets.install(p.id());
                layers.addAll(got);
                // The bundled halo is installed so it's ready to use, but starts switched off.
                if (p.id().equals(Presets.HALO) && !got.isEmpty()) hiddenFolders.add(got.get(0).folder);
            }
            catch (Exception e) { LOG.warn(Privacy.redactForLog("[rainium] preset " + p.id() + " unavailable: " + e.getMessage())); }
        }
        if (layers.isEmpty()) return;
        editable().layers.clear();
        editable().layers.addAll(layers);
        for (String f : hiddenFolders) editable().folders.computeIfAbsent(f, k -> new LayerConfig.Folder()).enabled = false;
        try {
            Files.writeString(dir.resolve("layers.json"), JsonWriter.write(editable().toJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error(Privacy.redactForLog("[rainium] could not write layers.json: " + e.getMessage()));
        }
        reload();
        LOG.info("[rainium] started with presets: {} layer(s)", layers.size());
    }

    /** Group names of a mesh file, or an empty list if it can't be read. */
    List<String> groupsOf(String file) {
        try { return new ArrayList<>(mesh(file).groups.keySet()); }
        catch (Exception e) { return List.of(); }
    }

    /** Write the editable config to layers.json and apply it. The first save keeps a backup of the hand-written file. */
    /** Replace layers.json with a previous version (undo/redo) and apply it. */
    synchronized void restore(String json) {
        try {
            Files.writeString(dir.resolve("layers.json"), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            lastError = "could not write layers.json: " + e.getMessage();
            return;
        }
        reload();
    }

    String snapshot() { return JsonWriter.write(editable().toJson()); }

    // ---------------------------------------------------------------- sharing folders as zips
    /** Writes exports/&lt;folder&gt;.zip with the folder's layers and every mesh/texture they use. Returns the zip path. */
    synchronized Path exportFolder(String folder) throws IOException {
        List<LayerConfig.Layer> parts = editable().members(folder);
        if (parts.isEmpty()) throw new IOException("folder is empty");
        Path outDir = dir.resolve("exports");
        Files.createDirectories(outDir);
        Path zipPath = outDir.resolve(sanitizeFile(folder) + ".zip");
        Map<String, String> files = new LinkedHashMap<>();   // source path in config folder -> name in zip
        List<Object> layerJson = new ArrayList<>();
        for (LayerConfig.Layer l : parts) {
            Map<String, Object> m = l.toJson();
            m.remove("folder");
            m.remove("show");   // who it shows on is the sharer's own choice (and may list names)
            if (l.mesh != null) m.put("mesh", zipName(files, l.mesh));
            if (l.texture != null && l.texture.indexOf(':') < 0) m.put("texture", zipName(files, l.texture));
            layerJson.add(m);
        }
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", folder);
        set.put("format", "rainium-set-1");
        LayerConfig.Folder f = editable().folders.get(folder);
        if (f != null && f.motion != 1f) set.put("motion", (double) f.motion);
        set.put("layers", layerJson);
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new java.util.zip.ZipEntry("set.json"));
            zos.write(JsonWriter.write(set).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (Map.Entry<String, String> e : files.entrySet()) {
                Path src = dir.resolve(e.getKey()).normalize();
                if (!Files.isRegularFile(src)) continue;
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getValue()));
                Files.copy(src, zos);
                zos.closeEntry();
            }
        }
        return zipPath;
    }

    private static String zipName(Map<String, String> files, String path) {
        String existing = files.get(path);
        if (existing != null) return existing;
        String base = sanitizeFile(path.substring(path.lastIndexOf('/') + 1));
        String name = base;
        for (int n = 2; files.containsValue(name); n++) {
            int dot = base.lastIndexOf('.');
            name = dot > 0 ? base.substring(0, dot) + "_" + n + base.substring(dot) : base + "_" + n;
        }
        files.put(path, name);
        return name;
    }

    /** Zips in the folder (and exports/) that contain a set.json. */
    List<String> setZips() {
        List<String> out = new ArrayList<>();
        for (String folder : new String[]{"", "exports/"}) {
            Path d = folder.isEmpty() ? dir : dir.resolve(folder);
            if (!Files.isDirectory(d)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.zip")) {
                for (Path p : ds) {
                    try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(p.toFile())) {
                        if (z.getEntry("set.json") != null) out.add(folder + p.getFileName());
                    } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Unpacks a set zip into sets/&lt;name&gt;/ and returns its layers (already pointing at the unpacked files), in a new folder. */
    @SuppressWarnings("unchecked")
    synchronized List<LayerConfig.Layer> importSet(String zipRel) throws IOException {
        Path zipPath = dir.resolve(zipRel).normalize();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zipPath.toFile())) {
            java.util.zip.ZipEntry setEntry = z.getEntry("set.json");
            if (setEntry == null) throw new IOException("not a Rainium set (no set.json)");
            Map<String, Object> set;
            try (InputStream in = z.getInputStream(setEntry)) {
                Object o = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!(o instanceof Map)) throw new IOException("set.json is not an object");
                set = (Map<String, Object>) o;
            }
            String name = set.get("name") instanceof String sname && !sname.isBlank() ? sname
                : zipPath.getFileName().toString().replaceAll("(?i)\\.zip$", "");
            String folderName = editable().uniqueFolderName(name);
            String sub = "sets/" + sanitizeFile(folderName);
            Path target = dir.resolve(sub);
            Files.createDirectories(target);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.equals("set.json") || n.contains("/") || n.contains("\\") || n.startsWith(".")) continue;   // flat files only
                try (InputStream in = z.getInputStream(e)) {
                    Files.copy(in, target.resolve(n), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            List<Object> ls = new ArrayList<>();
            if (set.get("layers") instanceof List<?> l0) {
                for (Object o : l0) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
                    if (m.get("mesh") instanceof String ms) m.put("mesh", sub + "/" + sanitizeFile(ms));
                    if (m.get("texture") instanceof String ts && ts.indexOf(':') < 0) m.put("texture", sub + "/" + sanitizeFile(ts));
                    m.put("folder", folderName);
                    m.remove("show");   // sets exported by 0.3.7 may carry the sharer's choice
                    ls.add(m);
                }
            }
            if (ls.isEmpty()) throw new IOException("the set has no layers");
            wrapper.put("layers", ls);
            LayerConfig parsed = LayerConfig.fromJson(wrapper);
            if (set.get("motion") instanceof Number mo) editable().folder(folderName).motion = mo.floatValue();
            return parsed.layers;
        }
    }

    synchronized void save() {
        Path cfgPath = dir.resolve("layers.json");
        try {
            Path bak = dir.resolve("layers.json.bak");
            if (Files.exists(cfgPath) && !Files.exists(bak)) Files.copy(cfgPath, bak, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(cfgPath, JsonWriter.write(editable().toJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            lastError = "could not save layers.json: " + e.getMessage();
            return;
        }
        reload();
    }

    /** Write a converted mesh into the folder. Returns the file name used. */
    synchronized String writeMesh(String baseName, String json) throws IOException {
        String name = sanitizeFile(baseName) + ".json";
        if (name.equalsIgnoreCase("layers.json")) name = "layers_mesh.json";
        Files.writeString(dir.resolve(name), json, StandardCharsets.UTF_8);
        meshCache.remove(name);
        return name;
    }

    /** Files with the given extension (lower-case, no dot) in the folder and its presets/ subfolders, sorted. */
    List<String> files(String ext) {
        List<String> out = new ArrayList<>();
        collect(dir, "", ext, 0, out);
        out.sort((a, b) -> {
            boolean pa = a.contains("/"), pb = b.contains("/");
            return pa != pb ? (pa ? 1 : -1) : a.compareToIgnoreCase(b);
        });
        return out;
    }

    private void collect(Path folder, String prefix, String ext, int depth, List<String> out) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (depth < 2 && (depth > 0 || n.equals("presets") || n.equals("sets"))) collect(p, prefix + n + "/", ext, depth + 1, out);
                    continue;
                }
                if (!n.toLowerCase(Locale.ROOT).endsWith("." + ext)) continue;
                if (depth == 0 && n.equals("layers.json")) continue;
                out.add(prefix + n);
            }
        } catch (IOException ignored) { }
    }

    String readFile(String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    /** Show a mesh on the player that isn't in the config yet (import preview). */
    void setPreview(Mesh mesh, LayerConfig.Layer cfg) {
        preview = new Resolved(cfg, mesh.groups.values().toArray(new Mesh.Group[0]), whiteTexture());
    }

    void clearPreview() { preview = null; }

    private Identifier texture(LayerConfig.Layer l, Map<String, Identifier> done, Map<Path, Long> newStamps) {
        String t = l.texture;
        if (t == null) return whiteTexture();
        // Hue-shifted copies are separate textures. Each distinct hue of a file gets a numbered slot,
        // so re-registering on reload replaces the old copy instead of piling up new ones.
        int deg = Math.round(Hue.normalize(l.hue));
        String key = deg == 0 ? t : t + "#" + deg;
        if (done.containsKey(key)) return done.get(key);
        // "namespace:path" is a game texture. Check this before treating it as a file name: on
        // Windows a ':' in a path is an error, not just a file that doesn't exist.
        if (LayerConfig.isResourceId(t)) {
            Identifier rid = Identifier.parse(t);   // game textures can't be recoloured; hue then only affects the colour
            done.put(key, rid);
            return rid;
        }
        Path file;
        try { file = dir.resolve(t).normalize(); }
        catch (java.nio.file.InvalidPathException e) { throw new IllegalArgumentException(l.label + ": not a valid texture name: " + t); }
        Identifier id;
        if (Files.isRegularFile(file)) {
            newStamps.put(file, stamp(file));
            int slot = 0;
            for (String k : done.keySet()) if (k.startsWith(t + "#")) slot++;
            id = Identifier.fromNamespaceAndPath(NS, "dynamic/" + sanitize(t) + (deg == 0 ? "" : "/hue" + slot));
            final Identifier fid = id;
            final byte[] bytes;
            try { bytes = Files.readAllBytes(file); }
            catch (IOException e) { throw new IllegalArgumentException("cannot read texture " + t + ": " + e.getMessage()); }
            pendingTextureUploads.add(() -> {
                try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
                    NativeImage img = NativeImage.read(in);
                    fixGlowMask(img);
                    if (deg != 0) shiftHue(img, deg);
                    Minecraft.getInstance().getTextureManager().register(fid, new DynamicTexture(() -> "rainium " + t, img));
                } catch (IOException e) {
                    LOG.error(Privacy.redactForLog("[rainium] texture " + t + " is not a readable PNG: " + e.getMessage()));
                }
            });
        } else {
            throw new IllegalArgumentException(l.label + ": texture file not found: " + t);
        }
        done.put(key, id);
        return id;
    }

    /** Glow textures made for additive blending often have no alpha at all (every pixel ~transparent),
     *  so alpha-blended render styles would discard the whole thing. Give such textures an alpha
     *  equal to their brightness: black stays invisible, bright parts show. Other textures are untouched. */
    private static void fixGlowMask(NativeImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getPixelsABGR();
        if (!Hue.alphaFromBrightness(px)) return;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) img.setPixelABGR(x, y, px[y * w + x]);
    }

    private static void shiftHue(NativeImage img, float deg) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getPixelsABGR();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                if ((p >>> 24) != 0) img.setPixelABGR(x, y, Hue.rotateAbgr(p, deg));
            }
        }
    }

    private Identifier whiteTexture() {
        if (white == null) {
            white = Identifier.fromNamespaceAndPath(NS, "dynamic/white");
            final Identifier id = white;
            pendingTextureUploads.add(() -> {
                NativeImage img = new NativeImage(1, 1, false);
                img.setPixelABGR(0, 0, 0xFFFFFFFF);
                Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "rainium white", img));
            });
        }
        return white;
    }

    static String sanitize(String s) {
        String r = s.toLowerCase(Locale.ROOT).replace('\\', '/');
        StringBuilder b = new StringBuilder();
        for (char c : r.toCharArray()) b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '/' || c == '.' || c == '_' || c == '-' ? c : '_');
        return b.toString();
    }

    static String sanitizeFile(String s) {
        String r = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        return r.isEmpty() ? "mesh" : r;
    }

    /** Open the folder in the system file manager (JDK only; works on Windows, macOS and Linux desktops). */
    void openFolder() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[]{"explorer", dir.toString()}
            : os.contains("mac") ? new String[]{"open", dir.toString()}
            : new String[]{"xdg-open", dir.toString()};
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            lastError = "could not open the folder " + dir;
        }
    }
}
