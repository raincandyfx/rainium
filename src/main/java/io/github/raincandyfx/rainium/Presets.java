package io.github.raincandyfx.rainium;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Ready-made layer sets, copied into config/rainium/presets/&lt;id&gt;/ the first time they're used.
 *
 *  - Gold halo: bundled with the mod as an example (assets/rainium/presets/halo).
 *  - Eyed wings: read from a compatible wing mod jar in the mods folder (its wing mesh already
 *    uses this mod's JSON format, so it is copied as-is). Not listed unless that mod is found.
 */
final class Presets {
    /** A preset the editor can offer (only ones that can actually be installed are listed). */
    record Info(String id, String name, String description, boolean available, String group) {
        Info(String id, String name, String description) { this(id, name, description, true, null); }
        Info(String id, String name, String description, boolean available) { this(id, name, description, available, null); }
    }

    /** Where to get the mod Eyed wings is read from (opened when its greyed-out card is clicked). */
    static final String WING_MOD_URL = "https://www.curseforge.com/minecraft/mc-mods/ronova-ruler-of-death";

    static final String WINGS = "wings", HALO = "halo";

    // Files read from a compatible wing mod, and the names they are saved under in presets/wings/.
    private static final String PACK_MESH = "assets/ronova/meshes/custom_wing_mesh.json";
    private static final String[][] PACK_FILES = {
        {PACK_MESH, "wings_mesh.json"},
        {"assets/ronova/textures/entity/custom_wing.png", "wings.png"},
        {"assets/ronova/textures/entity/custom_wing_glow.png", "wings_glow.png"},
        {"assets/ronova/textures/entity/custom_wing_eye.png", "wings_eye.png"},
        {"assets/ronova/textures/entity/custom_wing_aura.png", "wings_aura.png"},
    };

    private final Assets assets;
    private Path wingSource;         // cached
    private long wingCheckedAt;

    Presets(Assets assets) { this.assets = assets; }

    /** Shown in the editor's preset list (translated). */
    static String displayName(String id) { return Lang.t("preset." + id); }

    /** Name of the folder a preset is added in. This is saved in layers.json like any folder name the
     *  player types, so it stays the same whatever language the game is in (and works before the
     *  game's language has loaded, when presets are added on first launch). */
    private static String folderName(String id) { return id.equals(WINGS) ? "Eyed wings" : "Gold halo"; }

    List<Info> list() {
        List<Info> out = new ArrayList<>();
        out.add(new Info(HALO, displayName(HALO), Lang.t("preset.halo.description")));
        for (String id : bundledIds()) {
            String group = null;
            try { if (bundledSet(id).get("group") instanceof String gr && !gr.isBlank()) group = gr; } catch (IOException ignored) { }   // sets with the same "group" are shown side by side
            out.add(new Info(id, displayName(id), Lang.t("preset." + id + ".description"), true, group));
        }
        // last in the list; always shown, greyed out (with a link to the mod) until the mod it is read from is found
        boolean wings = wingSource() != null || Files.exists(assets.dir().resolve("presets/wings/wings_mesh.json"));
        out.add(new Info(WINGS, displayName(WINGS), Lang.t(wings ? "preset.wings.description" : "preset.wings.needs_mod"), wings));
        return out;
    }

    private final java.util.Map<String, Boolean> hasThumbCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when the sample model ships a pre-rendered picture (presets/&lt;id&gt;/thumb.png in the jar). */
    boolean hasThumb(String id) {
        return hasThumbCache.computeIfAbsent(id, k -> (bundledIds().contains(k) || k.equals(HALO) || k.equals(WINGS)) && Presets.class.getResource(RES + k + "/thumb.png") != null);
    }

    /** The shipped picture's PNG bytes (drawn as a real texture), or null. */
    byte[] thumbBytes(String id) {
        try (InputStream in = Presets.class.getResourceAsStream(RES + id + "/thumb.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** The shipped picture shrunk to Thumbs.BIG square ARGB pixels (the fallback when it can't be drawn as a texture), or null. */
    int[] thumbPixels(String id) {
        try {
            byte[] b = thumbBytes(id);
            if (b == null) return null;
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
            if (src == null) return null;
            java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(Thumbs.BIG, Thumbs.BIG, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g2 = im.createGraphics();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src.getScaledInstance(Thumbs.BIG, Thumbs.BIG, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
            g2.dispose();
            int[] px = new int[Thumbs.BIG * Thumbs.BIG];
            im.getRGB(0, 0, Thumbs.BIG, Thumbs.BIG, px, 0, Thumbs.BIG);
            return px;
        } catch (Exception e) {
            return null;
        }
    }

    /** The preset's model for a thumbnail, read without installing anything; null if unavailable. */
    java.util.Collection<Mesh.Group> previewGroups(String id) {
        try {
            String json = null;
            if (bundledIds().contains(id)) {
                for (Object o : (List<?>) bundledSet(id).get("layers")) {
                    if (o instanceof java.util.Map<?, ?> m && m.get("mesh") instanceof String f) {
                        try (InputStream in = Presets.class.getResourceAsStream(RES + id + "/" + f)) { if (in != null) json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                        break;
                    }
                }
            } else if (id.equals(HALO)) {
                try (InputStream in = Presets.class.getResourceAsStream(HALO_RES + "halo.json")) { if (in != null) json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
            } else if (id.equals(WINGS)) {
                Path copied = assets.dir().resolve("presets/wings/wings_mesh.json");
                if (Files.exists(copied)) json = Files.readString(copied);
                else {
                    Path jar = wingSource();
                    if (jar != null && !jar.toString().equals("<classpath>")) {
                        try (ZipFile zip = new ZipFile(jar.toFile())) {
                            ZipEntry e = zip.getEntry(PACK_MESH);
                            if (e != null) try (InputStream in = zip.getInputStream(e)) { json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                        }
                    } else if (jar != null) {
                        try (InputStream in = Presets.class.getResourceAsStream("/" + PACK_MESH)) { if (in != null) json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                    }
                }
            }
            if (json == null) return null;
            Mesh m = Mesh.fromJson(Json.parse(json));
            // the wings preset hides its aura by default; leave it out of the picture too
            java.util.List<Mesh.Group> gs = new java.util.ArrayList<>();
            for (Mesh.Group g : m.groups.values()) if (!g.name().startsWith("aura")) gs.add(g);
            return gs;
        } catch (Exception e) {
            return null;
        }
    }

    /** Copies the preset's files into presets/&lt;id&gt;/ if needed and returns new layers for it, in a fresh folder. */
    List<LayerConfig.Layer> install(String id) throws IOException {
        List<LayerConfig.Layer> layers = switch (id) {
            case WINGS -> wings();
            case HALO -> halo();
            default -> {
                if (!bundledIds().contains(id)) throw new IllegalArgumentException("unknown preset " + id);
                yield bundled(id, true);
            }
        };
        if (bundledIds().contains(id)) {
            String f = assets.editable().uniqueFolderName(String.valueOf(bundledSet(id).get("name")));
            for (LayerConfig.Layer l : layers) l.folder = f;
            return layers;
        }
        String folder = assets.editable().uniqueFolderName(folderName(id));
        for (LayerConfig.Layer l : layers) l.folder = folder;
        return layers;
    }

    /** The preset's original settings for a layer created from it, or null. */
    LayerConfig.Layer template(LayerConfig.Layer l) {
        if (l.preset == null) return null;
        String id = l.preset.substring(0, Math.max(0, l.preset.indexOf(':')));
        try {
            if (bundledIds().contains(id)) {
                for (LayerConfig.Layer t : bundled(id, false)) if (l.preset.equals(t.preset)) return t;
                return null;
            }
            for (LayerConfig.Layer t : id.equals(WINGS) ? wings() : id.equals(HALO) ? halo() : List.<LayerConfig.Layer>of()) {
                if (l.preset.equals(t.preset)) return t;
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ---------------------------------------------------------------- Eyed wings
    private List<LayerConfig.Layer> wings() throws IOException {
        Path dst = assets.dir().resolve("presets/wings");
        Files.createDirectories(dst);
        boolean have = true;
        for (String[] f : PACK_FILES) have &= Files.exists(dst.resolve(f[1]));
        if (!have) {
            Path jar = wingSource();
            if (jar == null) throw new IllegalStateException("no compatible wing mod found in the mods folder");
            if (jar.toString().equals("<classpath>")) {
                for (String[] f : PACK_FILES) {
                    try (InputStream in = Presets.class.getResourceAsStream("/" + f[0])) {
                        if (in == null) throw new IOException("the wing mod is missing some of its files");
                        Files.copy(in, dst.resolve(f[1]), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    for (String[] f : PACK_FILES) {
                        ZipEntry e = zip.getEntry(f[0]);
                        if (e == null) throw new IOException("the wing mod is missing some of its files");
                        try (InputStream in = zip.getInputStream(e)) {
                            Files.copy(in, dst.resolve(f[1]), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
        String mesh = "presets/wings/wings_mesh.json";
        List<LayerConfig.Layer> out = new ArrayList<>();
        out.add(layer("Wings", mesh, List.of("wing"), "presets/wings/wings.png", LayerConfig.Render.CUTOUT_NO_CULL, 0xFFFFFFFF, false, true));
        out.add(layer("Glow", mesh, List.of("wing_glow"), "presets/wings/wings_glow.png", LayerConfig.Render.EMISSIVE, 0xFFFFFFFF, true, true));
        out.add(layer("Eyes", mesh, List.of("eye_core"), "presets/wings/wings_eye.png", LayerConfig.Render.CUTOUT_NO_CULL, 0xFFFFFFFF, true, true));
        out.add(layer("Eye shine", mesh, List.of("eye_shell"), "presets/wings/wings_eye.png", LayerConfig.Render.TRANSLUCENT_CULL, 0x80FFFFFF, true, true));
        out.add(layer("Aura", mesh, List.of("aura"), "presets/wings/wings_aura.png", LayerConfig.Render.TRANSLUCENT_EMISSIVE, 0x8CFFFFFF, true, false));
        out.get(out.size() - 1).mirror = true;   // the aura mesh covers one wing; the mirrored copy covers the other
        for (LayerConfig.Layer l : out) {
            l.anchor = LayerConfig.Anchor.ROOT;   // the mesh is authored in model space
            l.preset = WINGS + ":" + l.groups.get(0);
        }
        return out;
    }

    /** A compatible wing mod: on the classpath if it's loaded, otherwise a jar (or .jar.disabled) in the mods folder. */
    Path wingSource() {
        long now = System.currentTimeMillis();
        if (now - wingCheckedAt < 5000) return wingSource;
        wingCheckedAt = now;
        wingSource = null;
        if (Presets.class.getResource("/" + PACK_MESH) != null) {
            wingSource = Path.of("<classpath>");
            return wingSource;
        }
        Path mods;
        try { mods = FabricLoader.getInstance().getGameDir().resolve("mods"); }
        catch (Throwable t) { return null; }
        if (!Files.isDirectory(mods)) return null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
            for (Path p : ds) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(n.endsWith(".jar") || n.endsWith(".jar.disabled"))) continue;
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    if (zip.getEntry(PACK_MESH) != null) { wingSource = p; break; }
                } catch (IOException ignored) { /* not a readable jar */ }
            }
        } catch (IOException ignored) { }
        return wingSource;
    }

    // ---------------------------------------------------------------- bundled sets
    // Sample models shipped inside the mod: assets/rainium/presets/<id>/set.json (the same format as a shared set) plus
    // its mesh and texture files, listed by id in assets/rainium/presets/index.txt. Gold halo is the older, hand-written one.
    private static final String RES = "/assets/rainium/presets/";
    private static List<String> bundledCache;

    static synchronized List<String> bundledIds() {
        if (bundledCache == null) {
            List<String> ids = new ArrayList<>();
            try (InputStream in = Presets.class.getResourceAsStream(RES + "index.txt")) {
                if (in != null) for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) if (!line.isBlank() && !line.startsWith("#")) ids.add(line.trim());
            } catch (IOException ignored) { }
            bundledCache = ids;
        }
        return bundledCache;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> bundledSet(String id) throws IOException {
        try (InputStream in = Presets.class.getResourceAsStream(RES + id + "/set.json")) {
            if (in == null) throw new IOException("the mod jar is missing the " + id + " preset");
            return (java.util.Map<String, Object>) Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** The set's layers, with their files in presets/&lt;id&gt;/ (copied out of the jar first when copy is true). */
    @SuppressWarnings("unchecked")
    private List<LayerConfig.Layer> bundled(String id, boolean copy) throws IOException {
        java.util.Map<String, Object> set = bundledSet(id);
        Path dst = assets.dir().resolve("presets/" + id);
        String sub = "presets/" + id;
        List<Object> ls = new ArrayList<>();
        int n = 0;
        for (Object o : (List<Object>) set.get("layers")) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>((java.util.Map<String, Object>) o);
            for (String key : new String[]{"mesh", "texture"}) {
                if (!(m.get(key) instanceof String f) || f.indexOf(':') >= 0) continue;   // a resource id like minecraft:... stays as it is
                if (copy) {
                    Files.createDirectories(dst);
                    copyFresh(RES + id + "/" + f, dst.resolve(f));
                }
                m.put(key, sub + "/" + f);
            }
            m.put("preset", id + ":" + (n++));
            ls.add(m);
        }
        java.util.Map<String, Object> wrapper = new java.util.LinkedHashMap<>();
        wrapper.put("layers", ls);
        return LayerConfig.fromJson(wrapper).layers;
    }

    /**
     * Copies a file out of the jar, replacing an older copy left by an earlier version (a stale mesh with
     * the new placement puts the model in the wrong place). Identical files are left alone.
     */
    private static void copyFresh(String resource, Path dest) throws IOException {
        byte[] fresh;
        try (InputStream in = Presets.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("the mod jar is missing " + resource.substring(resource.lastIndexOf('/') + 1));
            fresh = in.readAllBytes();
        }
        if (Files.isRegularFile(dest) && java.util.Arrays.equals(Files.readAllBytes(dest), fresh)) return;
        Files.write(dest, fresh);
    }

    /** Names of layers of this sample model that are switched off and on again right after adding it (see set.json "refresh"). */
    List<String> refreshLayers(String id) {
        List<String> out = new ArrayList<>();
        try {
            if (bundledIds().contains(id) && bundledSet(id).get("refresh") instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        } catch (IOException ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- Gold halo
    private static final String HALO_RES = "/assets/rainium/presets/halo/";

    private List<LayerConfig.Layer> halo() throws IOException {
        Path dst = assets.dir().resolve("presets/halo");
        Files.createDirectories(dst);
        for (String f : new String[]{"halo.json", "halo.png"}) copyFresh(HALO_RES + f, dst.resolve(f));
        String mesh = "presets/halo/halo.json", tex = "presets/halo/halo.png";
        // One layer per part of the model (rings / center / rungs)
        List<String> parts = new ArrayList<>(Mesh.fromJson(Json.parse(Files.readString(dst.resolve("halo.json")))).groups.keySet());
        List<LayerConfig.Layer> out = new ArrayList<>();
        for (String part : parts) {
            String nice = part.contains("_") ? part.substring(0, part.indexOf('_')) : part;   // "rings_Ornament_Gold" -> "rings"
            nice = nice.isEmpty() ? "Halo" : Character.toUpperCase(nice.charAt(0)) + nice.substring(1);
            LayerConfig.Layer l = layer(parts.size() == 1 ? "Halo" : nice, mesh, parts.size() == 1 ? null : List.of(part), tex,
                LayerConfig.Render.CUTOUT_NO_CULL, 0xFFFFFFFF, false, true);
            haloPlacement(l);
            l.fullbright = nice.equalsIgnoreCase("rungs");   // the rungs ignore world light, so they glow in the dark
            l.preset = HALO + ":" + part;
            out.add(l);
        }
        return out;
    }

    /** Fixed to the body (no lean), 225% size, pushed 0.1 back. */
    private static void haloPlacement(LayerConfig.Layer l) {
        l.anchor = LayerConfig.Anchor.ROOT;
        l.scale = new float[]{2.25f, 2.25f, 2.25f};
        l.offset = new float[]{0f, 0f, 0.1f};
        l.rotation = new float[3];
        l.physics = true;
        l.mirror = false;
    }

    private static LayerConfig.Layer layer(String name, String mesh, List<String> groups, String texture,
                                           LayerConfig.Render render, int color, boolean fullbright, boolean enabled) {
        LayerConfig.Layer l = LayerConfig.newLayer(mesh);
        l.label = name;
        l.groups = groups == null ? null : new ArrayList<>(groups);
        l.texture = texture;
        l.render = render;
        l.color = color;
        l.fullbright = fullbright;
        l.enabled = enabled;
        return l;
    }
}
