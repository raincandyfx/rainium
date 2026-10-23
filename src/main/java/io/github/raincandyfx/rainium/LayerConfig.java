package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parsed contents of config/rainium/layers.json. Pure data; no Minecraft classes. */
final class LayerConfig {
    enum ShowOn { SELF, ALL, OTHERS }
    enum Anchor { BODY, ROOT, HEAD }
    enum Render { CUTOUT, CUTOUT_NO_CULL, TRANSLUCENT, TRANSLUCENT_CULL, EMISSIVE, TRANSLUCENT_EMISSIVE }

    static final class Layer {
        boolean enabled = true;
        String mesh;
        List<String> groups;          // null = all groups
        String texture;               // file in the config folder, or "namespace:path" resource id; null = white
        Render render = Render.CUTOUT_NO_CULL;
        int color = 0xFFFFFFFF;       // ARGB; alpha is the editor's Opacity
        float hue = 0;                // degrees, -180..180; shifts the texture's (and colour's) hue
        boolean fullbright = false;
        Anchor anchor = Anchor.BODY;
        float[] offset = {0, 0, 0};   // blocks (1 = 16 model pixels), model space
        float[] rotation = {0, 0, 0}; // degrees, applied X then Y then Z
        float[] scale = {1, 1, 1};
        boolean yUp = false;          // mesh authored Y-up/front=+Z (Blender/glTF); converted to model space
        boolean physics = true;
        boolean mirror = false;       // also draw a copy mirrored across X
        int order = -1;               // submit order; -1 = automatic (0 opaque, 1 translucent, 2 emissive)
        boolean hideWhenFlying = false;
        String label;
        String folder;                // null = not in a folder
        String preset;                // "presetId:part" when created from a preset (for Reset to preset)

        Map<String, Object> toJson() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", label);
            if (folder != null) m.put("folder", folder);
            if (preset != null) m.put("preset", preset);
            if (!enabled) m.put("enabled", false);
            m.put("mesh", mesh);
            m.put("groups", groups == null ? "*" : new ArrayList<Object>(groups));
            if (texture != null) m.put("texture", texture);
            m.put("render", render.name().toLowerCase(Locale.ROOT));
            if (color != 0xFFFFFFFF) m.put("color", String.format("#%08X", color));
            if (hue != 0) m.put("hue", (double) hue);
            if (fullbright) m.put("fullbright", true);
            m.put("anchor", anchor.name().toLowerCase(Locale.ROOT));
            m.put("offset", vec(offset));
            m.put("rotation", vec(rotation));
            m.put("scale", scale[0] == scale[1] && scale[1] == scale[2] ? (Object) (double) scale[0] : vec(scale));
            if (yUp) m.put("coords", "y_up");
            if (mirror) m.put("mirror", true);
            m.put("physics", physics);
            if (hideWhenFlying) m.put("hide_when_flying", true);
            if (order >= 0) m.put("order", order);
            return m;
        }

        private static List<Object> vec(float[] v) { return List.of((double) v[0], (double) v[1], (double) v[2]); }
    }

    static final class Physics {
        float stiffness = 9f;      // spring angular frequency (rad/s)
        float damping = 0.75f;     // damping ratio (1 = critically damped)
        float lag = 0.035f;        // offset per (block/second) of movement
        float maxLag = 0.18f;      // blocks
        float yawLag = 0.06f;      // degrees of sway per (degree/second) of body turn
        float maxYaw = 14f;        // degrees
        float swayDeg = 1.5f;      // idle sway amplitude
        float swayHz = 0.25f;
        float bob = 0.012f;        // idle bob amplitude (blocks)
        float bobHz = 0.5f;
    }

    /** On/off and collapsed state of a folder of layers. */
    static final class Folder {
        boolean enabled = true;
        boolean collapsed = false;
        float motion = 1f;            // multiplier on movement sway for this folder's parts (0 = still)
    }

    /** Motion strength for a layer: its folder's setting, or 1. */
    float motionOf(Layer l) {
        if (l.folder == null) return 1f;
        Folder f = folders.get(l.folder);
        return f == null ? 1f : f.motion;
    }

    /** Copy look and placement (not the model, part, texture, name or folder) from one layer to another. */
    static void copyStyle(Layer from, Layer to) {
        to.render = from.render;
        to.color = from.color;
        to.hue = from.hue;
        to.fullbright = from.fullbright;
        to.anchor = from.anchor;
        to.offset = from.offset.clone();
        to.rotation = from.rotation.clone();
        to.scale = from.scale.clone();
        to.yUp = from.yUp;
        to.physics = from.physics;
        to.mirror = from.mirror;
        to.hideWhenFlying = from.hideWhenFlying;
        to.order = from.order;
    }

    /** One row group in the layer list: a folder with its members, or a single loose layer. */
    record Entry(String folder, List<Layer> members) {
        boolean isFolder() { return folder != null; }
    }

    boolean enabled = true;
    ShowOn showOn = ShowOn.SELF;
    Privacy.Mode privacyMode = Privacy.Mode.INGAME;   // what replaces the account name in shown paths
    String privacyName = "";
    boolean autoUpdate = true;                         // check GitHub for updates once per launch
    boolean devUpdates = false;                        // also install dev builds (x.y.1+ pre-releases)
    final Physics physics = new Physics();
    final List<Layer> layers = new ArrayList<>();
    final Map<String, Folder> folders = new java.util.LinkedHashMap<>();

    Folder folder(String name) { return folders.computeIfAbsent(name, k -> new Folder()); }

    /** A layer is drawn when it and its folder (if any) are switched on. */
    boolean isActive(Layer l) {
        if (!l.enabled) return false;
        if (l.folder == null) return true;
        Folder f = folders.get(l.folder);
        return f == null || f.enabled;
    }

    /** Groups layers for display: a folder appears where its first member is, with all its members. */
    List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        java.util.Map<String, Entry> byFolder = new java.util.HashMap<>();
        for (Layer l : layers) {
            if (l.folder == null) { out.add(new Entry(null, new ArrayList<>(List.of(l)))); continue; }
            Entry e = byFolder.get(l.folder);
            if (e == null) { e = new Entry(l.folder, new ArrayList<>()); byFolder.put(l.folder, e); out.add(e); }
            e.members().add(l);
        }
        return out;
    }

    /** Rewrites the layer order from display entries (keeps each folder's members together). */
    void setOrder(List<Entry> entries) {
        layers.clear();
        for (Entry e : entries) layers.addAll(e.members());
    }

    /** Move a layer one step up/down: within its folder if it has one, otherwise among top-level rows. */
    boolean moveLayer(Layer l, int dir) {
        List<Entry> es = entries();
        if (l.folder != null) {
            for (Entry e : es) {
                if (!l.folder.equals(e.folder())) continue;
                int i = e.members().indexOf(l), j = i + dir;
                if (j < 0 || j >= e.members().size()) return false;
                java.util.Collections.swap(e.members(), i, j);
                setOrder(es);
                return true;
            }
            return false;
        }
        for (int i = 0; i < es.size(); i++) {
            if (!es.get(i).isFolder() && es.get(i).members().get(0) == l) return swapEntries(es, i, i + dir);
        }
        return false;
    }

    /** Move a whole folder one step up/down among top-level rows. */
    boolean moveFolder(String name, int dir) {
        List<Entry> es = entries();
        for (int i = 0; i < es.size(); i++) if (name.equals(es.get(i).folder())) return swapEntries(es, i, i + dir);
        return false;
    }

    private boolean swapEntries(List<Entry> es, int i, int j) {
        if (j < 0 || j >= es.size()) return false;
        java.util.Collections.swap(es, i, j);
        setOrder(es);
        return true;
    }

    List<Layer> members(String folder) {
        List<Layer> out = new ArrayList<>();
        for (Layer l : layers) if (folder.equals(l.folder)) out.add(l);
        return out;
    }

    /** Folder names in display order. */
    List<String> folderNames() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries()) if (e.isFolder()) out.add(e.folder());
        return out;
    }

    /** "Name", or "Name 2", "Name 3"... if a folder with that name already has layers. */
    String uniqueFolderName(String base) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Layer l : layers) if (l.folder != null) used.add(l.folder);
        if (!used.contains(base)) return base;
        for (int n = 2; ; n++) if (!used.contains(base + " " + n)) return base + " " + n;
    }

    /** Drop folder states that no layer uses any more. */
    void pruneFolders() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (Layer l : layers) if (l.folder != null) used.add(l.folder);
        folders.keySet().retainAll(used);
        for (String u : used) folder(u);
    }

    @SuppressWarnings("unchecked")
    static LayerConfig fromJson(Object root) {
        if (!(root instanceof Map)) throw new IllegalArgumentException("layers.json must be a JSON object");
        Map<String, Object> m = (Map<String, Object>) root;
        LayerConfig c = new LayerConfig();
        c.enabled = bool(m, "enabled", true);
        c.showOn = enumOf(ShowOn.class, m.get("show_on"), ShowOn.SELF, "show_on");
        if (m.get("updates") instanceof Map<?, ?> um) {
            if (um.get("automatic") instanceof Boolean b) c.autoUpdate = b;
            if (um.get("dev_builds") instanceof Boolean b) c.devUpdates = b;
        }
        if (m.get("privacy") instanceof Map) {
            Map<String, Object> pm = (Map<String, Object>) m.get("privacy");
            Object mode = pm.get("name_in_paths");
            if (mode != null) {
                String ms = String.valueOf(mode).trim().toUpperCase(Locale.ROOT).replace("-", "_");
                c.privacyMode = ms.equals("CUSTOM") ? Privacy.Mode.CUSTOM : ms.equals("HIDDEN") ? Privacy.Mode.HIDDEN : Privacy.Mode.INGAME;
            }
            if (pm.get("custom_name") instanceof String cn) c.privacyName = cn;
        }
        if (m.get("physics") instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) m.get("physics");
            Physics ph = c.physics;
            ph.stiffness = f(p, "stiffness", ph.stiffness);
            ph.damping = f(p, "damping", ph.damping);
            ph.lag = f(p, "lag", ph.lag);
            ph.maxLag = f(p, "max_lag", ph.maxLag);
            ph.yawLag = f(p, "yaw_lag", ph.yawLag);
            ph.maxYaw = f(p, "max_yaw", ph.maxYaw);
            ph.swayDeg = f(p, "sway_deg", ph.swayDeg);
            ph.swayHz = f(p, "sway_hz", ph.swayHz);
            ph.bob = f(p, "bob", ph.bob);
            ph.bobHz = f(p, "bob_hz", ph.bobHz);
        }
        if (m.get("folders") instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("folders")).entrySet()) {
                Folder f = c.folder(e.getKey());
                if (e.getValue() instanceof Map) {
                    Map<String, Object> fm = (Map<String, Object>) e.getValue();
                    f.enabled = bool(fm, "enabled", true);
                    f.collapsed = bool(fm, "collapsed", false);
                    f.motion = Math.max(0f, Math.min(5f, f(fm, "motion", 1f)));
                } else if (e.getValue() instanceof Boolean b) {
                    f.enabled = b;
                }
            }
        }
        Object ls = m.get("layers");
        if (!(ls instanceof List)) throw new IllegalArgumentException("\"layers\" must be an array");
        int n = 0;
        for (Object o : (List<Object>) ls) {
            n++;
            if (!(o instanceof Map)) throw new IllegalArgumentException("layer #" + n + " must be an object");
            Map<String, Object> lm = (Map<String, Object>) o;
            try {
                c.layers.add(layer(lm, n));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("layer #" + n + ": " + e.getMessage(), e);
            }
        }
        c.pruneFolders();
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Layer layer(Map<String, Object> m, int n) {
        Layer l = new Layer();
        l.enabled = bool(m, "enabled", true);
        Object mesh = m.get("mesh");
        if (!(mesh instanceof String) || ((String) mesh).isBlank()) throw new IllegalArgumentException("\"mesh\" (file name) is required");
        l.mesh = (String) mesh;
        Object g = m.get("groups");
        if (g == null) g = m.get("group");
        if (g instanceof String s && !s.equals("*")) l.groups = List.of(s);
        else if (g instanceof List) {
            l.groups = new ArrayList<>();
            for (Object x : (List<Object>) g) l.groups.add(String.valueOf(x));
            if (l.groups.contains("*")) l.groups = null;
        }
        l.texture = m.get("texture") instanceof String s && !s.isBlank() ? s : null;
        l.render = enumOf(Render.class, m.get("render"), Render.CUTOUT_NO_CULL, "render");
        l.color = color(m.get("color"));
        l.hue = Hue.normalize(f(m, "hue", 0f));
        l.fullbright = bool(m, "fullbright", false);
        l.anchor = enumOf(Anchor.class, m.get("anchor"), Anchor.BODY, "anchor");
        l.offset = vec3(m.get("offset"), 0f, "offset");
        l.rotation = vec3(m.get("rotation"), 0f, "rotation");
        l.scale = vec3(m.get("scale"), 1f, "scale");
        Object coords = m.get("coords");
        if (coords != null) {
            String cs = String.valueOf(coords).toLowerCase(Locale.ROOT);
            if (cs.equals("y_up") || cs.equals("yup") || cs.equals("blender") || cs.equals("gltf")) l.yUp = true;
            else if (!cs.equals("minecraft") && !cs.equals("model")) throw new IllegalArgumentException("coords must be \"minecraft\" or \"y_up\"");
        }
        l.physics = bool(m, "physics", true);
        l.mirror = bool(m, "mirror", false);
        l.hideWhenFlying = bool(m, "hide_when_flying", false);
        if (m.get("order") instanceof Number x) l.order = x.intValue();
        l.label = m.get("name") instanceof String s ? s : "layer #" + n;
        l.folder = m.get("folder") instanceof String s && !s.isBlank() ? s : null;
        l.preset = m.get("preset") instanceof String s && !s.isBlank() ? s : null;
        return l;
    }

    static int color(Object o) {
        if (o == null) return 0xFFFFFFFF;
        if (o instanceof Number x) {
            long v = x.longValue();
            return (v & 0xFF000000L) == 0 ? (int) (v | 0xFF000000L) : (int) v;
        }
        if (o instanceof List<?> l && (l.size() == 3 || l.size() == 4)) {
            float[] c = new float[4];
            c[3] = 1f;
            for (int k = 0; k < l.size(); k++) c[k] = ((Number) l.get(k)).floatValue();
            boolean unit = c[0] <= 1 && c[1] <= 1 && c[2] <= 1 && c[3] <= 1;
            int[] b = new int[4];
            for (int k = 0; k < 4; k++) b[k] = Math.max(0, Math.min(255, Math.round(unit ? c[k] * 255f : c[k])));
            return (b[3] << 24) | (b[0] << 16) | (b[1] << 8) | b[2];
        }
        String s = String.valueOf(o).trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        long v = Long.parseLong(s, 16);
        if (s.length() <= 6) v |= 0xFF000000L;
        return (int) v;
    }

    private static float[] vec3(Object o, float def, String what) {
        if (o == null) return new float[]{def, def, def};
        if (o instanceof Number x) { float v = x.floatValue(); return new float[]{v, v, v}; }
        if (o instanceof List<?> l && l.size() == 3) {
            return new float[]{((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue()};
        }
        throw new IllegalArgumentException("\"" + what + "\" must be a number or [x, y, z]");
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m.get(k);
        return o instanceof Boolean b ? b : def;
    }

    private static float f(Map<String, Object> m, String k, float def) {
        Object o = m.get(k);
        return o instanceof Number x ? x.floatValue() : def;
    }

    private static <E extends Enum<E>> E enumOf(Class<E> cls, Object o, E def, String what) {
        if (o == null) return def;
        String s = String.valueOf(o).trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return Enum.valueOf(cls, s);
        } catch (IllegalArgumentException e) {
            StringBuilder ok = new StringBuilder();
            for (E v : cls.getEnumConstants()) ok.append(ok.length() > 0 ? ", " : "").append(v.name().toLowerCase(Locale.ROOT));
            throw new IllegalArgumentException("\"" + what + "\" must be one of: " + ok);
        }
    }

    // ------------------------------------------------------------------ writing
    Map<String, Object> toJson() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("show_on", showOn.name().toLowerCase(Locale.ROOT));
        Map<String, Object> pv = new java.util.LinkedHashMap<>();
        pv.put("name_in_paths", privacyMode.name().toLowerCase(Locale.ROOT));
        if (privacyName != null && !privacyName.isBlank()) pv.put("custom_name", privacyName);
        m.put("privacy", pv);
        Map<String, Object> up = new java.util.LinkedHashMap<>();
        up.put("automatic", autoUpdate);
        up.put("dev_builds", devUpdates);
        m.put("updates", up);
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("stiffness", physics.stiffness); p.put("damping", physics.damping);
        p.put("lag", physics.lag); p.put("max_lag", physics.maxLag);
        p.put("yaw_lag", physics.yawLag); p.put("max_yaw", physics.maxYaw);
        p.put("sway_deg", physics.swayDeg); p.put("sway_hz", physics.swayHz);
        p.put("bob", physics.bob); p.put("bob_hz", physics.bobHz);
        m.put("physics", p);
        pruneFolders();
        if (!folders.isEmpty()) {
            Map<String, Object> fs = new java.util.LinkedHashMap<>();
            for (String name : folderNames()) {
                Folder f = folders.get(name);
                Map<String, Object> fm = new java.util.LinkedHashMap<>();
                fm.put("enabled", f.enabled);
                if (f.collapsed) fm.put("collapsed", true);
                if (f.motion != 1f) fm.put("motion", (double) f.motion);
                fs.put(name, fm);
            }
            m.put("folders", fs);
        }
        List<Object> ls = new ArrayList<>();
        for (Layer l : layers) ls.add(l.toJson());
        m.put("layers", ls);
        return m;
    }

    Layer copyLayer(Layer l) {
        @SuppressWarnings("unchecked")
        Layer c = layer((Map<String, Object>) Json.parse(JsonWriter.write(l.toJson())), 0);
        return c;
    }

    static Layer newLayer(String mesh) {
        Layer l = new Layer();
        l.mesh = mesh;
        l.label = mesh.replaceAll("\\.json$", "");
        return l;
    }
}
