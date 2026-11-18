package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Outfits and cosmetics worn by other players on a Rainium server, ready to draw. Each is a zip
 * (an outfit from Rainium, or a set zip the server installed), kept on disk by its hash in
 * cache/servers/ so it's downloaded once, and turned into a render state like your own layers:
 * meshes parsed and pictures made into textures. Zips come from other people, so everything in
 * them is size-checked and only Rainium's own formats are read (mesh JSON and pictures).
 */
final class RemoteOutfits {
    private RemoteOutfits() {}

    private static final int MAX_ENTRIES = 512, MAX_ENTRY = 16 << 20, MAX_TOTAL = 64 << 20, MAX_SIDE = 4096, MAX_LAYERS = 64;
    private static final long CACHE_LIMIT = 256L << 20;

    /** One outfit ready to draw, and the textures it registered. */
    record Loaded(Assets.State state, List<Identifier> textures) {}

    private static final Map<String, Loaded> loaded = new HashMap<>();
    private static int version;   // goes up whenever the set of loaded outfits changes
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> failed = new HashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rainium-outfits");
        t.setDaemon(true);
        return t;
    });

    private static Path cacheDir() { return RainiumClient.ASSETS.dir().resolve("cache").resolve("servers"); }
    private static Path file(String hash) { return cacheDir().resolve(hash + ".zip"); }

    /** The outfit with this hash, or null while it's still being fetched or loaded (it is then started). */
    static Assets.State state(String hash) {
        Loaded l = loaded.get(hash);
        if (l != null) return l.state();
        if (!loading.contains(hash) && Files.isRegularFile(file(hash)) && !recentlyFailed(hash)) loadFromDisk(hash);
        return null;
    }

    /** Whether the outfit is here already (loaded, loading, or on disk): no need to download it. */
    static boolean have(String hash) {
        return loaded.containsKey(hash) || loading.contains(hash) || Files.isRegularFile(file(hash));
    }

    /** A freshly downloaded outfit: kept on disk and loaded. */
    static void store(String hash, byte[] data) {
        loading.add(hash);
        WORKER.execute(() -> {
            try {
                Files.createDirectories(cacheDir());
                Path tmp = cacheDir().resolve(hash + ".part");
                Files.write(tmp, data);
                Files.move(tmp, file(hash), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Assets.LOG.warn("[rainium] could not cache a downloaded outfit: " + e.getMessage());
            }
            decode(hash, data);
        });
    }

    private static void loadFromDisk(String hash) {
        loading.add(hash);
        WORKER.execute(() -> {
            try {
                byte[] data = Files.readAllBytes(file(hash));
                if (!NetProtocol.hash(data).equals(hash)) { Files.deleteIfExists(file(hash)); throw new IOException("damaged cache file"); }
                Files.setLastModifiedTime(file(hash), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                decode(hash, data);
            } catch (IOException e) {
                fail(hash, e.getMessage());
            }
        });
    }

    private static boolean recentlyFailed(String hash) {
        Long t = failed.get(hash);
        return t != null && System.currentTimeMillis() - t < 60_000;
    }

    private static void fail(String hash, String why) {
        Minecraft.getInstance().execute(() -> {
            loading.remove(hash);
            failed.put(hash, System.currentTimeMillis());
        });
        Assets.LOG.warn("[rainium] an outfit from the server could not be used: " + why);
    }

    // ------------------------------------------------------------------ reading a zip

    /** Off the render thread: reads the zip and parses its layers and meshes, then hands the pictures to the render thread. */
    private static void decode(String hash, byte[] data) {
        try {
            Map<String, byte[]> entries = unzip(data);
            LayerConfig cfg = config(entries);
            Map<String, Mesh> meshes = new HashMap<>();
            List<LayerConfig.Layer> keep = new ArrayList<>();
            Map<LayerConfig.Layer, Mesh.Group[]> groups = new LinkedHashMap<>();
            for (LayerConfig.Layer l : cfg.layers) {
                if (keep.size() >= MAX_LAYERS) break;
                if (!cfg.isActive(l)) continue;
                try {
                    if (l.isParticles()) { groups.put(l, new Mesh.Group[0]); keep.add(l); continue; }
                    if (l.mesh == null) continue;
                    Mesh mesh = meshes.get(l.mesh);
                    if (mesh == null) {
                        byte[] mb = entries.get(key(l.mesh));
                        if (mb == null) continue;
                        mesh = Mesh.fromJson(Json.parse(new String(mb, StandardCharsets.UTF_8)));
                        meshes.put(l.mesh, mesh);
                    }
                    List<Mesh.Group> gs = new ArrayList<>();
                    if (l.groups == null) gs.addAll(mesh.groups.values());
                    else for (String g : l.groups) { Mesh.Group mg = mesh.groups.get(g); if (mg != null) gs.add(mg); }
                    if (gs.isEmpty()) continue;
                    groups.put(l, gs.toArray(new Mesh.Group[0]));
                    keep.add(l);
                } catch (RuntimeException e) {
                    // one broken layer doesn't stop the rest
                }
            }
            // pictures, read here (still off the render thread)
            Map<String, NativeImage> pictures = new HashMap<>();
            for (LayerConfig.Layer l : keep) {
                if (l.texture == null || LayerConfig.isResourceId(l.texture)) continue;
                int deg = Math.round(Hue.normalize(l.hue));
                String k = l.texture + "#" + deg;
                if (pictures.containsKey(k)) continue;
                byte[] png = entries.get(key(l.texture));
                NativeImage img = png == null ? null : picture(png);
                if (img != null) {
                    Assets.fixGlowMask(img);
                    if (deg != 0) Assets.shiftHue(img, deg);
                }
                pictures.put(k, img);
            }
            Minecraft.getInstance().execute(() -> finish(hash, cfg, keep, groups, pictures));
        } catch (Exception e) {
            fail(hash, e.getMessage());
        }
    }

    /** On the render thread: registers the textures and publishes the outfit. */
    private static void finish(String hash, LayerConfig cfg, List<LayerConfig.Layer> keep, Map<LayerConfig.Layer, Mesh.Group[]> groups, Map<String, NativeImage> pictures) {
        loading.remove(hash);
        if (!ServerLink.wanted(hash)) {   // nobody wears it any more
            for (NativeImage img : pictures.values()) if (img != null) img.close();
            return;
        }
        release(hash);
        Map<String, Identifier> ids = new HashMap<>();
        List<Identifier> textures = new ArrayList<>();
        int n = 0;
        for (Map.Entry<String, NativeImage> e : pictures.entrySet()) {
            if (e.getValue() == null) continue;
            Identifier id = Identifier.fromNamespaceAndPath("rainium", "remote/" + hash.substring(0, 16) + "/" + n++);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "rainium outfit", e.getValue()));
            ids.put(e.getKey(), id);
            textures.add(id);
        }
        Identifier white = white();
        List<Assets.Resolved> out = new ArrayList<>();
        boolean anyPhysics = false;
        for (LayerConfig.Layer l : keep) {
            Identifier tex;
            if (l.texture == null) tex = white;
            else if (LayerConfig.isResourceId(l.texture)) tex = Identifier.parse(l.texture);
            else tex = ids.getOrDefault(l.texture + "#" + Math.round(Hue.normalize(l.hue)), white);
            out.add(new Assets.Resolved(l, groups.get(l), tex));
            anyPhysics |= l.physics;
        }
        loaded.put(hash, new Loaded(new Assets.State(cfg, List.copyOf(out), anyPhysics), textures));
        version++;
    }

    private static Identifier whiteId;
    private static Identifier white() {
        if (whiteId == null) {
            whiteId = Identifier.fromNamespaceAndPath("rainium", "remote/white");
            NativeImage img = new NativeImage(1, 1, false);
            img.setPixelABGR(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager().register(whiteId, new DynamicTexture(() -> "rainium white", img));
        }
        return whiteId;
    }

    /** The zip's layers: an outfit's config, or a set zip's layers in one folder. */
    @SuppressWarnings("unchecked")
    private static LayerConfig config(Map<String, byte[]> entries) {
        byte[] outfit = entries.get("outfit.json");
        if (outfit != null) {
            Object root = Json.parse(new String(outfit, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> m) || !(m.get("config") instanceof Map<?, ?> c)) throw new IllegalArgumentException("outfit.json has no config");
            return LayerConfig.fromJson(c);
        }
        byte[] set = entries.get("set.json");
        if (set == null) throw new IllegalArgumentException("no outfit.json or set.json");
        Object root = Json.parse(new String(set, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> m) || !(m.get("layers") instanceof List<?> ls)) throw new IllegalArgumentException("set.json has no layers");
        String name = m.get("name") instanceof String s && !s.isBlank() ? s : "set";
        List<Object> layers = new ArrayList<>();
        for (Object o : ls) {
            if (!(o instanceof Map<?, ?> lm)) continue;
            Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) lm);
            copy.remove("show");
            copy.put("folder", name);
            layers.add(copy);
        }
        Map<String, Object> folder = new LinkedHashMap<>();
        folder.put("enabled", true);
        if (m.get("motion") instanceof Number n) folder.put("motion", n.doubleValue());
        if (m.get("reactions") != null) folder.put("reactions", m.get("reactions"));
        Map<String, Object> folders = new LinkedHashMap<>();
        folders.put(name, folder);
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("enabled", true);
        conf.put("folders", folders);
        conf.put("layers", layers);
        return LayerConfig.fromJson(conf);
    }

    /** The files in a zip, by lower-case name, within the size limits. */
    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (++count > MAX_ENTRIES) throw new IOException("too many files");
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) > 0) {
                    bo.write(buf, 0, r);
                    total += r;
                    if (bo.size() > MAX_ENTRY || total > MAX_TOTAL) throw new IOException("too big when unpacked");
                }
                out.put(key(e.getName()), bo.toByteArray());
            }
        }
        return out;
    }

    private static String key(String name) {
        String k = name.replace('\\', '/');
        while (k.startsWith("./") || k.startsWith("/")) k = k.substring(k.startsWith("./") ? 2 : 1);
        return k.toLowerCase(Locale.ROOT);
    }

    /** A PNG no bigger than 4096 on a side (checked before it's decoded), or null. */
    private static NativeImage picture(byte[] png) {
        if (png.length < 24 || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
        int w = ((png[16] & 255) << 24) | ((png[17] & 255) << 16) | ((png[18] & 255) << 8) | (png[19] & 255);
        int h = ((png[20] & 255) << 24) | ((png[21] & 255) << 16) | ((png[22] & 255) << 8) | (png[23] & 255);
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return null;
        try (InputStream in = new ByteArrayInputStream(png)) {
            return NativeImage.read(in);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ letting go

    private static void release(String hash) {
        Loaded l = loaded.remove(hash);
        if (l == null) return;
        version++;
        for (Identifier id : l.textures()) Minecraft.getInstance().getTextureManager().release(id);
    }

    static int version() { return version; }

    /** Your own reactions' needs, plus what the loaded outfits' reactions need (they're worn by others). */
    static React.Usage withRemote(React.Usage mine) {
        if (loaded.isEmpty()) return mine;
        Set<String> types = new HashSet<>(mine.types());
        for (Loaded l : loaded.values()) types.addAll(React.usage(l.state().config()).types());
        return types.isEmpty() ? React.Usage.NONE : new React.Usage(Set.copyOf(types), true);
    }

    /** Frees outfits nobody on the server wears any more. */
    static void tidy() {
        for (String h : new ArrayList<>(loaded.keySet())) if (!ServerLink.wanted(h)) release(h);
    }

    /** Left the server: frees everything, and trims the disk cache. */
    static void clear() {
        for (String h : new ArrayList<>(loaded.keySet())) release(h);
        failed.clear();
        WORKER.execute(RemoteOutfits::trimCache);
    }

    private static void trimCache() {
        try {
            if (!Files.isDirectory(cacheDir())) return;
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir())) { for (Path f : ds) files.add(f); }
            Map<Path, Long> times = new HashMap<>();
            long total = 0;
            for (Path f : files) { total += Files.size(f); times.put(f, Files.getLastModifiedTime(f).toMillis()); }
            files.sort((a, b) -> Long.compare(times.get(a), times.get(b)));
            for (Path f : files) {
                if (total <= CACHE_LIMIT && !f.getFileName().toString().endsWith(".part")) continue;
                total -= Files.size(f);
                Files.deleteIfExists(f);
            }
        } catch (IOException ignored) { }
    }
}
