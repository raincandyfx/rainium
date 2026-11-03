package io.github.raincandyfx.rainium;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reactions: a small node graph on a layer (or a folder) that changes how it looks when things
 * happen to the player wearing it. Event nodes fire (hit, totem pop...), state nodes are on or off
 * (sneaking, low health...), logic nodes combine and shape them, and action nodes change the layer
 * (show, tint, scale...) with a fade in, an optional hold and a fade out, each with its own curve.
 *
 * Pure data and maths, no game classes: the game side (ReactSensors) supplies the events and states
 * of each wearer, and the renderer applies the {@link Mods} that {@link Runtime#eval} returns.
 */
final class React {
    private React() {}

    static final String FORMAT = "rainium-reactions-1";

    // =================================================================== the node types
    enum Cat { EVENT, STATE, LOGIC, ACTION }
    /** What a port carries: an event (a moment), a level (0..1, over time), or either (action inputs). */
    enum Port { EVENT, LEVEL, ANY }
    enum Kind { NUM, INT, CHOICE, COLOR, TEXT, CURVE, BOOL }

    /**
     * One setting of a node. `when` limits where it applies: "event"/"level" = only when the node's
     * input is wired from that kind of port; "key=value" = only while another setting has that value.
     */
    record Param(String key, Kind kind, Object def, double min, double max, double step, List<String> choices, String unit, String when) {
        boolean pick() { return kind == Kind.TEXT && choices != null; }
    }

    /** A node type. `group` sorts it in the add menu; `self` = only reported for you (the game doesn't tell clients this about other players). */
    record Type(String id, Cat cat, String group, Port[] in, Port out, List<Param> params, boolean self) {
        boolean isAction() { return cat == Cat.ACTION; }
    }

    static final Map<String, Type> TYPES = new LinkedHashMap<>();
    /** The groups of each category, in menu order. */
    static final Map<Cat, List<String>> GROUPS = new LinkedHashMap<>();
    static final List<String> CURVES = List.of("linear", "ease_in", "ease_out", "ease_in_out", "smooth", "sine",
        "back_out", "elastic_out", "bounce_out", "step", "custom");
    static final List<String> RETRIGGER = List.of("restart", "extend", "ignore");
    /** Kinds of damage the Hit and Hurt nodes can be limited to. */
    static final List<String> DAMAGE = List.of("any", "melee", "projectile", "explosion", "mace", "fall", "fire", "magic", "other");

    private static Param num(String k, double def, double min, double max, double step, String unit) { return new Param(k, Kind.NUM, def, min, max, step, null, unit, null); }
    private static Param num(String k, double def, double min, double max, double step, String unit, String when) { return new Param(k, Kind.NUM, def, min, max, step, null, unit, when); }
    private static Param integer(String k, int def, int min, int max) { return new Param(k, Kind.INT, (double) def, min, max, 1, null, "", null); }
    private static Param choice(String k, String def, String... opts) { return new Param(k, Kind.CHOICE, def, 0, 0, 0, List.of(opts), "", null); }
    private static Param text(String k, String def, String pick) { return new Param(k, Kind.TEXT, def, 0, 0, 0, pick == null ? null : List.of(pick), "", null); }
    private static Param color(String k, int def) { return new Param(k, Kind.COLOR, String.format("#%06X", def & 0xFFFFFF), 0, 0, 0, null, "", null); }
    private static Param curve(String k, String def, String when) { return new Param(k, Kind.CURVE, def, 0, 0, 0, CURVES, "", when); }
    private static Param damage() { return new Param("damage", Kind.CHOICE, "any", 0, 0, 0, DAMAGE, "", null); }

    private static final Port[] NONE = {}, EV = {Port.EVENT}, LV = {Port.LEVEL}, ANY = {Port.ANY};

    private static String group;   // the group the next types go in

    private static void type(String id, Cat cat, Port[] in, Port out, boolean self, Param... ps) {
        List<Param> all = new ArrayList<>(Arrays.asList(ps));
        if (cat == Cat.ACTION) {   // every action: how it comes in, how long it stays (after an event) and how it goes
            all.add(num("in", 0.15, 0, 10, 0.05, "s"));
            all.add(curve("in_curve", "ease_out", null));
            all.add(num("hold", 1.0, 0, 120, 0.25, "s", "event"));
            all.add(num("out", 0.4, 0, 10, 0.05, "s"));
            all.add(curve("out_curve", "ease_in_out", null));
            all.add(new Param("retrigger", Kind.CHOICE, "restart", 0, 0, 0, RETRIGGER, "", "event"));
        }
        TYPES.put(id, new Type(id, cat, group, in, out, List.copyOf(all), self));
        List<String> gs = GROUPS.computeIfAbsent(cat, k -> new ArrayList<>());
        if (!gs.contains(group)) gs.add(group);
    }

    private static void ev(String id, Param... ps) { type(id, Cat.EVENT, NONE, Port.EVENT, false, ps); }
    private static void evSelf(String id, Param... ps) { type(id, Cat.EVENT, NONE, Port.EVENT, true, ps); }
    private static void st(String id, Param... ps) { type(id, Cat.STATE, NONE, Port.LEVEL, false, ps); }
    private static void stSelf(String id, Param... ps) { type(id, Cat.STATE, NONE, Port.LEVEL, true, ps); }

    static {
        // ---- events (things that happen)
        group = "pvp";
        ev("totem");
        ev("totem_opponent");
        ev("double_pop", num("within", 10, 0.5, 120, 0.5, "s"));
        ev("mace_smash", num("min", 0, 0, 200, 1, "blocks"));
        ev("smashed");
        ev("pearl_thrown");
        ev("pearl_teleport");
        ev("opponent_pearled");
        ev("chorus");
        ev("wind_charge");
        ev("launched");
        ev("crystal_hit");
        ev("anchor_blast");
        ev("hit_by_projectile", choice("kind", "any", "any", "arrow", "trident", "other"));
        ev("shot_player", choice("kind", "any", "any", "arrow", "trident", "other"));
        ev("disable_shield");
        ev("opponent_armor_broke");
        ev("gapple", choice("kind", "any", "any", "golden", "enchanted"));
        ev("killed_by_player");
        ev("close_call", num("below", 20, 1, 100, 5, "%"));
        group = "combat";
        ev("hit", choice("target", "any", "any", "players", "mobs"), damage());
        ev("crit");
        ev("sweep");
        ev("kill", choice("target", "any", "any", "players", "mobs"));
        ev("kill_streak", integer("count", 3, 2, 100));
        ev("assist");
        ev("swing");
        group = "damage";
        ev("hurt", num("min", 0, 0, 40, 0.5, "hp"), damage());
        ev("heal", num("min", 0.5, 0, 40, 0.5, "hp"));
        ev("knockback", num("min", 4, 0, 60, 0.5, "blocks/s"));
        ev("death");
        ev("respawn");
        ev("shield_block");
        ev("shield_break");
        ev("armor_broke");
        ev("splashed");
        group = "movement";
        ev("jump");
        ev("land", num("min", 3, 0, 100, 0.5, "blocks"));
        ev("riptide");
        ev("firework");
        ev("enter_water");
        ev("leave_water");
        evSelf("dimension_change");
        group = "items";
        ev("shoot");
        evSelf("eat");
        evSelf("effect_gained", text("effect", "", "effect"));
        evSelf("effect_lost", text("effect", "", "effect"));
        evSelf("block_broken", text("block", "", "held"));
        evSelf("block_placed", text("block", "", "held"));
        ev("picked_up", text("item", "", "held"));
        ev("fishing_catch");
        evSelf("level_up");
        group = "world";
        ev("lightning", num("within", 16, 1, 256, 4, "blocks"));
        ev("chat", text("word", "gg", null));
        ev("interval", num("every", 5, 0.1, 600, 0.5, "s"));

        // ---- states (things that are true for a while)
        group = "health";
        st("health", choice("mode", "below", "below", "above", "missing"),
            new Param("percent", Kind.NUM, 30.0, 1, 100, 5, null, "%", "mode!=missing"));
        st("absorption");
        st("on_fire");
        group = "combat";
        st("in_combat", num("seconds", 10, 1, 120, 1, "s"));
        st("blocking");
        st("falling", num("blocks", 3, 0.5, 200, 0.5, "blocks"));
        group = "opponent";
        st("opponent_health", choice("mode", "below", "below", "above"), num("percent", 30, 1, 100, 5, "%"));
        st("opponent_near", num("within", 6, 1, 128, 1, "blocks"));
        st("opponent_holding", text("item", "mace", null));
        st("opponent_blocking");
        st("opponent_looking");
        group = "movement";
        st("sneaking");
        st("sprinting");
        st("moving", num("speed", 2, 0.1, 40, 0.5, "blocks/s"));
        st("gliding");
        st("swimming");
        st("in_water");
        st("in_lava");
        st("on_ground");
        st("airborne");
        st("riding");
        st("sleeping");
        st("in_cobweb");
        group = "items";
        st("holding", text("item", "totem_of_undying", "held"), choice("hand", "either", "either", "main", "off"));
        st("using", text("item", "", "using"));
        stSelf("effect", text("effect", "speed", "effect"));
        stSelf("hunger", num("below", 6, 1, 20, 1, ""));
        stSelf("low_durability", choice("slot", "any", "any", "held", "armor"), num("percent", 10, 1, 100, 5, "%"));
        stSelf("totems_left", integer("below", 2, 1, 64));
        stSelf("item_cooldown", text("item", "ender_pearl", "held"));
        stSelf("shield_cooldown");
        group = "world";
        st("night");
        st("raining");
        st("thunder");
        st("dimension", text("dimension", "the_nether", "dimension"));
        st("biome", text("biome", "plains", "biome"));
        st("game_mode", choice("mode", "creative", "survival", "creative", "spectator"));

        // ---- logic (combine and shape)
        group = "combine";
        type("and", Cat.LOGIC, new Port[]{Port.LEVEL, Port.LEVEL}, Port.LEVEL, false);
        type("or", Cat.LOGIC, new Port[]{Port.LEVEL, Port.LEVEL}, Port.LEVEL, false);
        type("not", Cat.LOGIC, LV, Port.LEVEL, false);
        type("either", Cat.LOGIC, new Port[]{Port.EVENT, Port.EVENT}, Port.EVENT, false);
        group = "convert";
        type("gate", Cat.LOGIC, new Port[]{Port.EVENT, Port.LEVEL}, Port.EVENT, false);
        type("hold", Cat.LOGIC, EV, Port.LEVEL, false, num("seconds", 3, 0.05, 600, 0.25, "s"));
        type("toggle", Cat.LOGIC, EV, Port.LEVEL, false);
        type("switch", Cat.LOGIC, new Port[]{Port.EVENT, Port.EVENT}, Port.LEVEL, false);
        type("starts", Cat.LOGIC, LV, Port.EVENT, false);
        type("stops", Cat.LOGIC, LV, Port.EVENT, false);
        group = "filter";
        type("chance", Cat.LOGIC, EV, Port.EVENT, false, num("percent", 50, 1, 100, 5, "%"));
        type("cooldown", Cat.LOGIC, EV, Port.EVENT, false, num("seconds", 2, 0.05, 600, 0.25, "s"));
        type("every", Cat.LOGIC, EV, Port.EVENT, false, integer("count", 3, 2, 100));
        type("combo", Cat.LOGIC, EV, Port.EVENT, false, integer("count", 3, 2, 50), num("within", 2, 0.1, 60, 0.25, "s"));
        type("delay", Cat.LOGIC, EV, Port.EVENT, false, num("seconds", 0.5, 0.05, 60, 0.05, "s"));

        // ---- actions (change this layer)
        group = "visibility";
        type("show", Cat.ACTION, ANY, null, false);
        type("hide", Cat.ACTION, ANY, null, false);
        type("opacity", Cat.ACTION, ANY, null, false, num("value", 0.3, 0, 1, 0.05, "×"));
        group = "colour";
        type("tint", Cat.ACTION, ANY, null, false, color("color", 0xFF3050), num("strength", 1, 0, 1, 0.05, "×"));
        type("glow", Cat.ACTION, ANY, null, false, num("strength", 1, 0, 1, 0.05, "×"));
        type("rainbow", Cat.ACTION, ANY, null, false, num("speed", 0.5, 0.05, 10, 0.05, "Hz"), num("strength", 0.8, 0, 1, 0.05, "×"));
        group = "motion";
        type("scale", Cat.ACTION, ANY, null, false, num("factor", 1.5, 0, 10, 0.05, "×"));
        type("move", Cat.ACTION, ANY, null, false, num("x", 0, -10, 10, 0.05, "blocks"), num("y", -0.25, -10, 10, 0.05, "blocks"), num("z", 0, -10, 10, 0.05, "blocks"));
        type("rotate", Cat.ACTION, ANY, null, false, num("x", 0, -360, 360, 5, "°"), num("y", 90, -360, 360, 5, "°"), num("z", 0, -360, 360, 5, "°"));
        type("spin", Cat.ACTION, ANY, null, false, num("speed", 180, -1440, 1440, 15, "°/s"));
        type("shake", Cat.ACTION, ANY, null, false, num("strength", 0.04, 0, 1, 0.01, "blocks"), num("speed", 14, 0.5, 40, 0.5, "Hz"));
        type("bob", Cat.ACTION, ANY, null, false, num("amount", 0.1, 0, 2, 0.01, "blocks"), num("speed", 1, 0.05, 10, 0.05, "Hz"));
        group = "effects";
        type("particles", Cat.ACTION, ANY, null, false, integer("burst", 20, 0, 500), num("rate", 3, 0, 20, 0.25, "×"));
    }

    static Type type(String id) { return TYPES.get(id); }

    static List<Type> ofCat(Cat c) {
        List<Type> out = new ArrayList<>();
        for (Type t : TYPES.values()) if (t.cat == c) out.add(t);
        return out;
    }

    // =================================================================== the graph
    static final class Node {
        int id;
        String type;
        float x, y;
        final Map<String, Object> p = new LinkedHashMap<>();   // settings, by Param key (missing = default)

        Type t() { return TYPES.get(type); }

        Param param(String key) {
            Type t = t();
            if (t != null) for (Param q : t.params) if (q.key.equals(key)) return q;
            return null;
        }

        Object get(String key) {
            Object v = p.get(key);
            if (v != null) return v;
            Param q = param(key);
            return q == null ? null : q.def;
        }

        double num(String key) { return get(key) instanceof Number n ? n.doubleValue() : 0; }
        String str(String key) { Object v = get(key); return v == null ? "" : String.valueOf(v); }
        int rgb(String key) { return LayerConfig.color(str(key)) & 0xFFFFFF; }

        void set(String key, Object v) {
            Param q = param(key);
            if (q != null && v instanceof Number n && (q.kind == Kind.NUM || q.kind == Kind.INT)) {
                double d = Math.max(q.min, Math.min(q.max, n.doubleValue()));
                if (q.kind == Kind.INT) d = Math.round(d);
                v = round4(d);
            }
            p.put(key, v);
        }
    }

    record Wire(int from, int to, int port) {}

    static final class Graph {
        boolean enabled = true;
        final List<Node> nodes = new ArrayList<>();
        final List<Wire> wires = new ArrayList<>();

        boolean isEmpty() { return nodes.isEmpty(); }

        Node node(int id) { for (Node n : nodes) if (n.id == id) return n; return null; }

        int nextId() { int m = 0; for (Node n : nodes) m = Math.max(m, n.id); return m + 1; }

        Wire input(int to, int port) { for (Wire w : wires) if (w.to == to && w.port == port) return w; return null; }

        /** What arrives at a node's input: EVENT or LEVEL, or null when nothing is wired there. */
        Port incoming(Node n, int port) {
            Wire w = input(n.id, port);
            if (w == null) return null;
            Node f = node(w.from);
            return f == null || f.t() == null ? null : f.t().out;
        }

        Node add(String type, float x, float y) {
            Type t = TYPES.get(type);
            if (t == null) return null;
            Node n = new Node();
            n.id = nextId();
            n.type = type;
            n.x = x; n.y = y;
            nodes.add(n);
            return n;
        }

        void remove(Node n) {
            nodes.remove(n);
            wires.removeIf(w -> w.from == n.id || w.to == n.id);
        }

        /** Why a wire from `from` to input `port` of `to` can't be made, or null if it can. */
        String whyNot(Node from, Node to, int port) {
            if (from == null || to == null || from == to) return "self";
            Type a = from.t(), b = to.t();
            if (a == null || b == null || a.out == null) return "no_output";
            if (port < 0 || port >= b.in.length) return "no_input";
            Port want = b.in[port];
            if (want != Port.ANY && want != a.out) return a.out == Port.EVENT ? "needs_level" : "needs_event";
            if (reaches(to.id, from.id)) return "loop";
            return null;
        }

        /** Connects, replacing whatever was wired into that input. Returns false (and changes nothing) if it can't. */
        boolean connect(Node from, Node to, int port) {
            if (whyNot(from, to, port) != null) return false;
            wires.removeIf(w -> w.to == to.id && w.port == port);
            wires.add(new Wire(from.id, to.id, port));
            return true;
        }

        void disconnect(int to, int port) { wires.removeIf(w -> w.to == to && w.port == port); }

        /** True if `target` can be reached by following wires downstream from `start`. */
        private boolean reaches(int start, int target) {
            ArrayList<Integer> todo = new ArrayList<>(List.of(start));
            Set<Integer> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                int c = todo.remove(todo.size() - 1);
                if (c == target) return true;
                if (!seen.add(c)) continue;
                for (Wire w : wires) if (w.from == c) todo.add(w.to);
            }
            return false;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!enabled) m.put("enabled", false);
            List<Object> ns = new ArrayList<>();
            for (Node n : nodes) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", (double) n.id);
                o.put("type", n.type);
                o.put("x", (double) Math.round(n.x));
                o.put("y", (double) Math.round(n.y));
                for (Map.Entry<String, Object> e : n.p.entrySet()) {
                    Param q = n.param(e.getKey());
                    if (q != null && sameValue(q.def, e.getValue())) continue;   // defaults stay implicit
                    o.put(e.getKey(), e.getValue() instanceof Number x ? (Object) x.doubleValue() : e.getValue());
                }
                ns.add(o);
            }
            m.put("nodes", ns);
            List<Object> ws = new ArrayList<>();
            for (Wire w : wires) ws.add(List.of((double) w.from, (double) w.to, (double) w.port));
            m.put("wires", ws);
            return m;
        }

        @SuppressWarnings("unchecked")
        static Graph fromJson(Object o) {
            Graph g = new Graph();
            if (!(o instanceof Map<?, ?> m0)) return g;
            Map<String, Object> m = (Map<String, Object>) m0;
            if (m.get("reactions") instanceof Map<?, ?> inner && m.get("nodes") == null) m = (Map<String, Object>) inner;   // a whole exported file
            if (m.get("enabled") instanceof Boolean b) g.enabled = b;
            if (m.get("nodes") instanceof List<?> ns) {
                for (Object x : ns) {
                    if (!(x instanceof Map<?, ?> nm0)) continue;
                    Map<String, Object> nm = (Map<String, Object>) nm0;
                    if (!(nm.get("type") instanceof String type) || !TYPES.containsKey(type)) continue;   // unknown (newer) node types are dropped
                    Node n = new Node();
                    n.type = type;
                    n.id = nm.get("id") instanceof Number id ? id.intValue() : g.nextId();
                    if (g.node(n.id) != null) n.id = g.nextId();
                    n.x = nm.get("x") instanceof Number v ? v.floatValue() : 0;
                    n.y = nm.get("y") instanceof Number v ? v.floatValue() : 0;
                    for (Param q : n.t().params) {
                        Object v = nm.get(q.key);
                        if (v == null) continue;
                        switch (q.kind) {
                            case NUM, INT -> { if (v instanceof Number num) n.set(q.key, num.doubleValue()); }
                            case BOOL -> { if (v instanceof Boolean b) n.p.put(q.key, b); }
                            case CHOICE -> { if (q.choices.contains(String.valueOf(v))) n.p.put(q.key, String.valueOf(v)); }
                            case CURVE -> { String c = String.valueOf(v); if (CURVES.contains(c) || c.startsWith("bezier:")) n.p.put(q.key, c); }
                            case COLOR -> n.p.put(q.key, String.format("#%06X", LayerConfig.color(v) & 0xFFFFFF));
                            case TEXT -> n.p.put(q.key, String.valueOf(v).trim());
                        }
                    }
                    g.nodes.add(n);
                }
            }
            if (m.get("wires") instanceof List<?> ws) {
                for (Object x : ws) {
                    int f, t, p;
                    if (x instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number a && l.get(1) instanceof Number b) {
                        f = a.intValue(); t = b.intValue(); p = l.size() > 2 && l.get(2) instanceof Number c ? c.intValue() : 0;
                    } else if (x instanceof Map<?, ?> wm && wm.get("from") instanceof Number a && wm.get("to") instanceof Number b) {
                        f = a.intValue(); t = b.intValue(); p = wm.get("port") instanceof Number c ? c.intValue() : 0;
                    } else continue;
                    g.connect(g.node(f), g.node(t), p);   // skips wires that don't fit
                }
            }
            return g;
        }

        Graph copy() { return fromJson(Json.parse(JsonWriter.write(toJson()))); }
    }

    /** A whole graph as a file of its own (for sharing or copying between layers). */
    static String exportText(Graph g, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        if (name != null) m.put("name", name);
        m.put("reactions", g.toJson());
        return JsonWriter.write(m);
    }

    /** Reads text made by {@link #exportText} (or a bare graph). Throws if it isn't one. */
    static Graph importText(String text) {
        Object o = Json.parse(text.trim());
        if (!(o instanceof Map<?, ?> m) || (m.get("reactions") == null && m.get("nodes") == null)) throw new IllegalArgumentException("not Rainium reactions");
        return Graph.fromJson(o);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Math.abs(x.doubleValue() - y.doubleValue()) < 1e-9;
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    // =================================================================== curves
    /** Eases t (0..1) along a named curve, or a custom "bezier:x1,y1,x2,y2" (like CSS cubic-bezier). */
    static double curve(String c, double t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        if (c == null) c = "linear";
        if (c.startsWith("bezier:")) { double[] b = bezier(c); return cubicBezier(b[0], b[1], b[2], b[3], t); }
        return switch (c) {
            case "ease_in" -> t * t * t;
            case "ease_out" -> 1 - Math.pow(1 - t, 3);
            case "ease_in_out" -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            case "smooth" -> t * t * (3 - 2 * t);
            case "sine" -> (1 - Math.cos(Math.PI * t)) / 2;
            case "back_out" -> { double c1 = 1.70158, c3 = c1 + 1; yield 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }
            case "elastic_out" -> Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1;
            case "bounce_out" -> bounce(t);
            case "step" -> 0;   // stays at the start until the very end
            default -> t;       // linear
        };
    }

    /** The default handles of a custom curve (an ease out). */
    static final String CUSTOM_DEFAULT = "bezier:0.2,0.6,0.35,1";

    static double[] bezier(String c) {
        double[] d = {0.25, 0.1, 0.25, 1};
        if (c == null || !c.startsWith("bezier:")) return d;
        String[] s = c.substring(7).split(",");
        for (int i = 0; i < 4 && i < s.length; i++) {
            try { d[i] = Double.parseDouble(s[i].trim()); } catch (NumberFormatException ignored) { }
        }
        d[0] = Math.max(0, Math.min(1, d[0]));
        d[2] = Math.max(0, Math.min(1, d[2]));
        d[1] = Math.max(-1, Math.min(2, d[1]));
        d[3] = Math.max(-1, Math.min(2, d[3]));
        return d;
    }

    static String bezierText(double x1, double y1, double x2, double y2) {
        return "bezier:" + fmt2(x1) + "," + fmt2(y1) + "," + fmt2(x2) + "," + fmt2(y2);
    }

    private static String fmt2(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static double cubicBezier(double x1, double y1, double x2, double y2, double x) {
        double t = x;   // solve x(t) = x by Newton, then bisection if that stalls
        for (int i = 0; i < 8; i++) {
            double cx = bez(x1, x2, t) - x, d = bezD(x1, x2, t);
            if (Math.abs(cx) < 1e-6) return bez(y1, y2, t);
            if (Math.abs(d) < 1e-6) break;
            t -= cx / d;
        }
        double lo = 0, hi = 1;
        t = x;
        for (int i = 0; i < 40; i++) {
            double cx = bez(x1, x2, t);
            if (Math.abs(cx - x) < 1e-6) break;
            if (cx < x) lo = t; else hi = t;
            t = (lo + hi) / 2;
        }
        return bez(y1, y2, t);
    }

    private static double bez(double a, double b, double t) { double u = 1 - t; return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t; }
    private static double bezD(double a, double b, double t) { double u = 1 - t; return 3 * u * u * a + 6 * u * t * (b - a) + 3 * t * t * (1 - b); }

    private static double bounce(double t) {
        double n1 = 7.5625, d1 = 2.75;
        if (t < 1 / d1) return n1 * t * t;
        if (t < 2 / d1) { t -= 1.5 / d1; return n1 * t * t + 0.75; }
        if (t < 2.5 / d1) { t -= 2.25 / d1; return n1 * t * t + 0.9375; }
        t -= 2.625 / d1;
        return n1 * t * t + 0.984375;
    }

    // =================================================================== what the renderer applies
    /** Changes to one layer this frame. Neutral values change nothing. */
    static final class Mods {
        float alpha = 1f;          // multiplies the layer's opacity (0 = not drawn)
        int tint = 0xFFFFFF;       // RGB the colour is pulled towards...
        float tintMix = 0f;        // ...by this much (0..1)
        float glow = 0f;           // 0..1 towards full brightness
        float scale = 1f;
        final float[] offset = new float[3];    // blocks, model space (x left, y down, z back)
        final float[] rotation = new float[3];  // degrees
        float spin = 0f;           // degrees around the upright axis
        float rate = 1f;           // particle rate multiplier
        int burst = 0;             // particles to add at once
        boolean any;               // something changed

        static final Mods NONE = new Mods();

        void addTint(int rgb, float k) {
            if (k <= 0) return;
            any = true;
            if (tintMix <= 0) { tint = rgb; tintMix = Math.min(1f, k); return; }
            float w = k / (tintMix + k);
            tint = mix(tint, rgb, w);
            tintMix = Math.min(1f, Math.max(tintMix, k));
        }

        /** Adds another set of changes (a folder's, on top of the layer's own). */
        void add(Mods o) {
            if (o == null || !o.any) return;
            any = true;
            alpha *= o.alpha;
            addTint(o.tint, o.tintMix);
            glow = Math.max(glow, o.glow);
            scale *= o.scale;
            for (int i = 0; i < 3; i++) { offset[i] += o.offset[i]; rotation[i] += o.rotation[i]; }
            spin += o.spin;
            rate *= o.rate;
            burst += o.burst;
        }

        /** The layer's colour (ARGB) with tint and opacity applied. */
        int color(int argb) {
            int rgb = argb & 0xFFFFFF;
            if (tintMix > 0) rgb = mix(rgb, tint, tintMix);
            if (glow > 0) rgb = mix(rgb, 0xFFFFFF, glow * 0.25f);   // a little lighter as well as lit
            int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
            return (a << 24) | rgb;
        }

        boolean hidden() { return alpha <= 0.004f; }
    }

    static int mix(int a, int b, float k) {
        k = Math.max(0f, Math.min(1f, k));
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return (r << 16) | (g << 8) | bl;
    }

    // =================================================================== running a graph
    /**
     * Something that happened to a wearer. amount: damage, healing, a fall height, a speed, a count or a
     * number of seconds (by kind); flags: 1 = the other entity is a player; tag: a kind of damage, an item,
     * block or effect id, a projectile kind, or a chat message.
     */
    record Ev(long seq, String kind, long nanos, float amount, int flags, String tag) {
        Ev(long seq, String kind, long nanos, float amount, int flags) { this(seq, kind, nanos, amount, flags, null); }
    }

    /** The player (or armor stand) a layer is drawn on, as the graph sees it. */
    interface Wearer {
        /** Newest event number; events after `seq` are added to `out` (oldest first). */
        long seq();
        void eventsAfter(long seq, List<Ev> out);
        /** A state node's value, 0..1. */
        float state(Node n);
        /** The local player (so "you only" nodes, and the editor's test switches, apply). */
        boolean self();
    }

    /** A wearer nothing happens to (armor stands; a world without a player). */
    static final Wearer NOBODY = new Wearer() {
        public long seq() { return 0; }
        public void eventsAfter(long seq, List<Ev> out) { }
        public float state(Node n) { return 0; }
        public boolean self() { return false; }
    };

    /** State nodes switched on by hand in the editor (key: object key + "#" + node id), for trying a graph out. */
    static final Set<String> testOn = java.util.Collections.synchronizedSet(new HashSet<>());

    /** Per-node memory between frames. */
    private static final class NState {
        String type;
        double t0, t1, from, to, cur, prev = Double.NaN, angle;
        int count;
        boolean on, active, up;
        final java.util.ArrayDeque<Double> q = new java.util.ArrayDeque<>();
    }

    /** A signal this frame: a level, and the moments (seconds) an event fired. */
    private static final class Sig {
        float level;
        double[] fires = EMPTY;
        int n;
        void fire(double t) {
            if (n == fires.length) fires = Arrays.copyOf(fires, Math.max(4, n * 2));
            fires[n++] = t;
        }
        static final double[] EMPTY = {};
    }

    /** One graph on one wearer. */
    static final class Runtime {
        final String key;   // the object ("l:folder/name" or "f:folder")
        Graph g;
        long lastSeq = Long.MIN_VALUE;
        double lastT = Double.NaN;
        long seenNanos;
        private final Map<Integer, NState> st = new HashMap<>();
        /** Per node: {level 0..1, seconds of the last time it fired (or -1)}, for the editor to show. */
        final Map<Integer, double[]> live = new HashMap<>();

        Runtime(String key, Graph g) { this.key = key; this.g = g; }

        private NState state(Node n) {
            NState s = st.get(n.id);
            if (s == null || !n.type.equals(s.type)) { s = new NState(); s.type = n.type; st.put(n.id, s); }
            return s;
        }

        /** Runs the graph at `nanos` and returns what it does to the layer. */
        Mods eval(Wearer w, long nanos) {
            seenNanos = nanos;
            Mods out = new Mods();
            if (g == null || !g.enabled || g.isEmpty()) return out;
            double t = nanos / 1e9;
            double dt = Double.isNaN(lastT) ? 0 : Math.max(0, Math.min(0.25, t - lastT));
            lastT = t;
            List<Ev> evs = new ArrayList<>();
            long seq = w.seq();
            if (lastSeq == Long.MIN_VALUE) lastSeq = seq;   // a graph that just appeared doesn't replay old events
            else if (seq > lastSeq) { w.eventsAfter(lastSeq, evs); lastSeq = seq; }
            if (st.size() > g.nodes.size() * 2 + 8) st.keySet().retainAll(g.nodes.stream().map(n -> n.id).toList());
            Map<Integer, Sig> memo = new HashMap<>();
            Set<Integer> busy = new HashSet<>();
            boolean anyShow = false;
            float show = 0f;
            for (Node n : g.nodes) {
                Type ty = n.t();
                if (ty == null || !ty.isAction()) continue;
                Wire wi = g.input(n.id, 0);
                if (wi == null) continue;
                Node src = g.node(wi.from);
                if (src == null) continue;
                Sig in = sig(src, w, evs, t, memo, busy);
                float lv = envelope(n, src.t().out == Port.EVENT, in, t, out);
                live.put(n.id, new double[]{lv, in.n > 0 ? t : liveFire(n.id)});
                if (n.type.equals("show")) { anyShow = true; show = Math.max(show, lv); continue; }
                apply(n, lv, t, dt, out);
            }
            if (anyShow) { out.alpha *= show; out.any = true; }
            return out;
        }

        private double liveFire(int id) { double[] l = live.get(id); return l == null ? -1 : l[1]; }

        private Sig sig(Node n, Wearer w, List<Ev> evs, double t, Map<Integer, Sig> memo, Set<Integer> busy) {
            Sig m = memo.get(n.id);
            if (m != null) return m;
            Sig s = new Sig();
            if (!busy.add(n.id)) return s;   // a loop (can't be made in the editor; a hand-edited file could)
            Type ty = n.t();
            if (ty != null) {
                Sig[] in = new Sig[ty.in.length];
                for (int p = 0; p < in.length; p++) {
                    Wire wi = g.input(n.id, p);
                    Node f = wi == null ? null : g.node(wi.from);
                    in[p] = f == null ? null : sig(f, w, evs, t, memo, busy);
                }
                switch (ty.cat) {
                    case EVENT -> event(n, w, evs, t, s);
                    case STATE -> s.level = testOn.contains(key + "#" + n.id) && w.self() ? 1f : (ty.self && !w.self() ? 0f : clamp01(w.state(n)));
                    case LOGIC -> logic(n, in, t, s);
                    case ACTION -> { }
                }
            }
            busy.remove(n.id);
            memo.put(n.id, s);
            if (ty != null && ty.cat != Cat.ACTION) live.put(n.id, new double[]{s.level, s.n > 0 ? t : liveFire(n.id)});
            return s;
        }

        private void event(Node n, Wearer w, List<Ev> evs, double t, Sig s) {
            Type ty = n.t();
            if (n.type.equals("interval")) {
                NState ns = state(n);
                double every = Math.max(0.05, n.num("every"));
                if (ns.t0 == 0 || ns.t0 > t + every) ns.t0 = t + every;
                for (int k = 0; t >= ns.t0 && k < 20; k++) { s.fire(ns.t0); ns.t0 += every; }
                if (t >= ns.t0) ns.t0 = t + every;
                return;
            }
            if (ty.self && !w.self()) return;
            for (Ev e : evs) if (e.kind.equals(n.type) && passes(n, e)) s.fire(e.nanos / 1e9);
        }

        private void logic(Node n, Sig[] in, double t, Sig s) {
            NState ns = state(n);
            switch (n.type) {
                case "and" -> s.level = Math.min(lv(in[0], 1), lv(in[1], 1)) * (in[0] == null && in[1] == null ? 0 : 1);
                case "or" -> s.level = Math.max(lv(in[0], 0), lv(in[1], 0));
                case "not" -> s.level = in[0] == null ? 0 : 1 - in[0].level;
                case "gate" -> { if (in[0] != null && (in[1] == null || in[1].level >= 0.5f)) for (int i = 0; i < in[0].n; i++) s.fire(in[0].fires[i]); }
                case "either" -> {
                    for (Sig x : in) if (x != null) for (int i = 0; i < x.n; i++) s.fire(x.fires[i]);
                    Arrays.sort(s.fires, 0, s.n);
                }
                case "hold" -> {
                    if (in[0] != null) for (int i = 0; i < in[0].n; i++) ns.t0 = Math.max(ns.t0, in[0].fires[i] + n.num("seconds"));
                    s.level = t < ns.t0 ? 1 : 0;
                }
                case "toggle" -> { if (in[0] != null) for (int i = 0; i < in[0].n; i++) ns.on = !ns.on; s.level = ns.on ? 1 : 0; }
                case "switch" -> {
                    double lastOn = Double.NEGATIVE_INFINITY, lastOff = Double.NEGATIVE_INFINITY;
                    if (in[0] != null && in[0].n > 0) lastOn = in[0].fires[in[0].n - 1];
                    if (in[1] != null && in[1].n > 0) lastOff = in[1].fires[in[1].n - 1];
                    if (lastOn > Double.NEGATIVE_INFINITY || lastOff > Double.NEGATIVE_INFINITY) ns.on = lastOn > lastOff;
                    s.level = ns.on ? 1 : 0;
                }
                case "starts", "stops" -> {
                    double v = in[0] == null ? 0 : in[0].level;
                    if (!Double.isNaN(ns.prev)) {
                        boolean rise = ns.prev < 0.5 && v >= 0.5, fall = ns.prev >= 0.5 && v < 0.5;
                        if (n.type.equals("starts") ? rise : fall) s.fire(t);
                    }
                    ns.prev = v;
                }
                case "chance" -> { if (in[0] != null) for (int i = 0; i < in[0].n; i++) if (Math.random() * 100 < n.num("percent")) s.fire(in[0].fires[i]); }
                case "cooldown" -> {
                    if (in[0] != null) for (int i = 0; i < in[0].n; i++) {
                        double f = in[0].fires[i];
                        if (ns.count == 0 || f - ns.t0 >= n.num("seconds")) { s.fire(f); ns.t0 = f; ns.count = 1; }
                    }
                }
                case "every" -> {
                    if (in[0] != null) for (int i = 0; i < in[0].n; i++) if (++ns.count >= (int) n.num("count")) { ns.count = 0; s.fire(in[0].fires[i]); }
                }
                case "combo" -> {
                    double within = n.num("within");
                    if (in[0] != null) for (int i = 0; i < in[0].n; i++) {
                        double f = in[0].fires[i];
                        ns.q.addLast(f);
                        while (!ns.q.isEmpty() && f - ns.q.peekFirst() > within) ns.q.pollFirst();
                        if (ns.q.size() >= (int) n.num("count")) { s.fire(f); ns.q.clear(); }
                    }
                }
                case "delay" -> {
                    if (in[0] != null) for (int i = 0; i < in[0].n; i++) if (ns.q.size() < 256) ns.q.addLast(in[0].fires[i] + n.num("seconds"));
                    for (Iterator<Double> it = ns.q.iterator(); it.hasNext(); ) { double due = it.next(); if (due <= t) { s.fire(due); it.remove(); } }
                }
                default -> { }
            }
        }

        private static float lv(Sig s, float missing) { return s == null ? missing : s.level; }

        /**
         * How strongly an action applies now (0..1). After an event: fade in, hold, fade out. While a
         * level: follows it, easing in as it rises and out as it falls.
         */
        private float envelope(Node n, boolean fromEvent, Sig in, double t, Mods out) {
            NState s = state(n);
            double fin = n.num("in"), fout = n.num("out");
            String cin = n.str("in_curve"), cout = n.str("out_curve");
            boolean bursts = n.type.equals("particles");
            if (fromEvent) {
                String re = n.str("retrigger");
                for (int i = 0; i < in.n; i++) {
                    double f = in.fires[i];
                    double now = levelAt(s, f, fin, fout, cin, cout);
                    boolean live = s.active && now > 0.0005;
                    if (live && re.equals("ignore")) continue;
                    if (live && re.equals("extend")) {
                        s.t1 = Math.max(s.t1, f + n.num("hold"));
                        if (f >= s.t0 + fin && now < 1) { s.from = now; s.t0 = f; s.t1 = Math.max(s.t1, f + fin + n.num("hold")); }
                    } else {
                        s.from = live ? now : 0;
                        s.t0 = f;
                        s.t1 = f + fin + n.num("hold");
                    }
                    s.active = true;
                    if (bursts) out.burst += (int) n.num("burst");
                }
                if (!s.active) return 0;
                float v = (float) levelAt(s, t, fin, fout, cin, cout);
                if (t > s.t1 + fout) s.active = false;
                return v;
            }
            // following a level
            double target = in.level;
            if (Double.isNaN(s.prev)) { s.cur = s.to = s.from = target; s.t0 = t; s.prev = target; }
            if (Math.abs(target - s.to) > 1e-4) {
                boolean up = target > s.cur;
                boolean sameWay = up == s.up && s.active && Math.abs(target - s.to) < 0.05;   // a level that drifts (health) keeps its transition
                if (!sameWay) { s.from = s.cur; s.t0 = t; s.up = up; s.active = true; }
                s.to = target;
            }
            if (s.active) {
                double dur = s.up ? fin : fout;
                double p = dur <= 0 ? 1 : (t - s.t0) / dur;
                double k = curve(s.up ? cin : cout, Math.min(1, p));
                s.cur = s.from + (s.to - s.from) * k;
                if (p >= 1) { s.cur = s.to; s.active = false; }
            }
            if (bursts && s.prev < 0.5 && s.cur >= 0.5) out.burst += (int) n.num("burst");
            s.prev = s.cur;
            return (float) clamp01(s.cur);
        }

        /** The after-an-event envelope at time x. */
        private static double levelAt(NState s, double x, double fin, double fout, String cin, String cout) {
            if (!s.active || x < s.t0) return s.active ? s.from : 0;
            if (x < s.t0 + fin) return s.from + (1 - s.from) * curve(cin, (x - s.t0) / fin);
            if (x <= s.t1) return 1;
            if (fout <= 0) return 0;
            double r = (x - s.t1) / fout;
            return r >= 1 ? 0 : 1 - curve(cout, r);
        }

        private void apply(Node n, float lv, double t, double dt, Mods o) {
            NState s = state(n);
            if (n.type.equals("spin")) {   // keeps turning while on, and stays where it got to
                s.angle = (s.angle + n.num("speed") * lv * dt) % 360.0;
                if (s.angle != 0) { o.spin += (float) s.angle; o.any = true; }
                return;
            }
            if (lv <= 0) return;
            o.any = true;
            switch (n.type) {
                case "hide" -> o.alpha *= 1 - lv;
                case "opacity" -> o.alpha *= 1 + ((float) n.num("value") - 1) * lv;
                case "tint" -> o.addTint(n.rgb("color"), (float) n.num("strength") * lv);
                case "glow" -> o.glow = Math.max(o.glow, (float) n.num("strength") * lv);
                case "rainbow" -> o.addTint(Hue.rotateArgb(0xFFFF4040, (float) ((t * n.num("speed") * 360.0) % 360.0)) & 0xFFFFFF, (float) n.num("strength") * lv);
                case "scale" -> o.scale *= 1 + ((float) n.num("factor") - 1) * lv;
                case "move" -> { o.offset[0] += (float) n.num("x") * lv; o.offset[1] += (float) n.num("y") * lv; o.offset[2] += (float) n.num("z") * lv; }
                case "rotate" -> { o.rotation[0] += (float) n.num("x") * lv; o.rotation[1] += (float) n.num("y") * lv; o.rotation[2] += (float) n.num("z") * lv; }
                case "shake" -> {
                    double a = n.num("strength") * lv, w = n.num("speed") * Math.PI * 2;
                    o.offset[0] += (float) (a * Math.sin(t * w + 0.3));
                    o.offset[1] += (float) (a * Math.sin(t * w * 1.13 + 1.7));
                    o.offset[2] += (float) (a * Math.sin(t * w * 0.87 + 3.1));
                }
                case "bob" -> o.offset[1] -= (float) (n.num("amount") * lv * (0.5 - 0.5 * Math.cos(t * n.num("speed") * Math.PI * 2)));
                case "particles" -> o.rate *= 1 + ((float) n.num("rate") - 1) * lv;
                default -> { }
            }
        }
    }

    /** Whether an event matches its node's settings (at least / within / which kind ...). */
    static boolean passes(Node n, Ev e) {
        String tag = e.tag == null ? "" : e.tag;
        switch (n.type) {
            case "hurt", "heal", "land", "mace_smash", "knockback" -> { if (e.amount + 1e-4 < n.num("min")) return false; }
            case "double_pop", "lightning" -> { if (e.amount > n.num("within") + 1e-4) return false; }
            case "close_call" -> { if (e.amount > n.num("below") + 1e-4) return false; }
            case "kill_streak" -> { int c = Math.max(1, (int) n.num("count")); if (e.amount < c || Math.round(e.amount) % c != 0) return false; }
            case "hit_by_projectile", "shot_player", "gapple" -> { String k = n.str("kind"); if (!k.equals("any") && !k.equals(tag)) return false; }
            case "effect_gained", "effect_lost", "block_broken", "block_placed", "picked_up" -> {
                String want = n.str(n.type.startsWith("effect") ? "effect" : n.type.startsWith("block") ? "block" : "item");
                if (!want.isBlank() && !idMatch(want, tag)) return false;
            }
            case "chat" -> { String word = n.str("word").trim().toLowerCase(Locale.ROOT); if (!word.isEmpty() && !tag.toLowerCase(Locale.ROOT).contains(word)) return false; }
            default -> { }
        }
        if (n.param("target") != null) {
            String target = n.str("target");
            if (target.equals("players") && (e.flags & 1) == 0) return false;
            if (target.equals("mobs") && (e.flags & 1) != 0) return false;
        }
        if (n.param("damage") != null) {
            String d = n.str("damage");
            if (!d.equals("any") && !d.equals(tag)) return false;
        }
        return true;
    }

    /** "totem_of_undying" matches "minecraft:totem_of_undying". */
    static boolean idMatch(String want, String id) {
        if (id == null) return false;
        String w = want.trim().toLowerCase(Locale.ROOT), i = id.toLowerCase(Locale.ROOT);
        if (w.indexOf(':') < 0) w = "minecraft:" + w;
        if (i.indexOf(':') < 0) i = "minecraft:" + i;
        return w.equals(i);
    }

    /** What the editor's try-it button fires for a node so that its own settings let it through: {amount, flags, tag}. */
    static Object[] testValues(Node n) {
        float amount = switch (n.type) {
            case "double_pop", "lightning", "close_call" -> 0f;
            case "kill_streak" -> (float) n.num("count");
            default -> 999f;
        };
        int flags = "mobs".equals(n.str("target")) ? 0 : 1;
        String tag = null;
        if (n.param("damage") != null && !n.str("damage").equals("any")) tag = n.str("damage");
        else if (n.param("kind") != null && !n.str("kind").equals("any")) tag = n.str("kind");
        else if (n.type.startsWith("effect_")) tag = n.str("effect");
        else if (n.type.startsWith("block_")) tag = n.str("block");
        else if (n.type.equals("picked_up")) tag = n.str("item");
        else if (n.type.equals("chat")) tag = n.str("word");
        return new Object[]{amount, flags, tag == null || tag.isBlank() ? null : tag};
    }

    /** Which of DAMAGE a damage type (its id) counts as. */
    static String damageKind(String id) {
        if (id == null) return "other";
        String t = id.substring(id.indexOf(':') + 1);
        return switch (t) {
            case "player_attack", "mob_attack", "mob_attack_no_aggro", "sting", "spear" -> "melee";
            case "arrow", "trident", "thrown", "mob_projectile", "fireball", "unattributed_fireball", "wither_skull", "wind_charge", "spit" -> "projectile";
            case "explosion", "player_explosion", "bad_respawn_point", "fireworks" -> "explosion";
            case "mace_smash" -> "mace";
            case "fall", "fly_into_wall", "stalagmite", "falling_block", "falling_anvil", "falling_stalactite", "ender_pearl" -> "fall";
            case "in_fire", "on_fire", "lava", "hot_floor", "campfire", "lightning_bolt" -> "fire";
            case "magic", "indirect_magic", "wither", "dragon_breath", "sonic_boom", "thorns" -> "magic";
            default -> "other";
        };
    }

    private static float clamp01(double v) { return (float) Math.max(0, Math.min(1, v)); }

    // =================================================================== what's in use
    /**
     * Which node types the switched-on graphs of a config use, and whether any reactive layer can be
     * drawn on players other than you. The sensors only run the checks this asks for.
     */
    record Usage(Set<String> types, boolean others) {
        static final Usage NONE = new Usage(Set.of(), false);
        boolean isEmpty() { return types.isEmpty(); }
        boolean has(String type) { return types.contains(type); }
        boolean any(String... ts) { for (String t : ts) if (types.contains(t)) return true; return false; }
    }

    static Usage usage(LayerConfig cfg) {
        if (cfg == null || !cfg.enabled) return Usage.NONE;
        Set<String> types = new HashSet<>();
        boolean others = false;
        for (LayerConfig.Layer l : cfg.layers) {
            if (!cfg.isActive(l)) continue;
            LayerConfig.Folder f = l.folder == null ? null : cfg.folders.get(l.folder);
            boolean any = false;
            for (Graph g : new Graph[]{l.reactions, f == null ? null : f.reactions}) {
                if (g == null || !g.enabled || g.isEmpty()) continue;
                for (Node n : g.nodes) types.add(n.type);
                any = true;
            }
            if (any) { LayerConfig.ShowOn on = cfg.playersFor(l); if (on != LayerConfig.ShowOn.SELF && on != LayerConfig.ShowOn.NOBODY) others = true; }
        }
        return types.isEmpty() ? Usage.NONE : new Usage(Set.copyOf(types), others);
    }

    // =================================================================== all running graphs
    private static final Map<String, Runtime> RUNS = new HashMap<>();
    private static long lastPrune;

    /**
     * What a graph does now to an object on a wearer. `wearerKey` names the wearer, `objKey` the
     * layer or folder. Runs one copy of the graph per wearer, so players don't share timers.
     */
    static Mods eval(String wearerKey, String objKey, Graph g, Wearer w, long nanos) {
        if (g == null || !g.enabled || g.isEmpty()) return Mods.NONE;
        Runtime r;
        synchronized (RUNS) {
            if (nanos - lastPrune > 5_000_000_000L) {
                RUNS.values().removeIf(x -> nanos - x.seenNanos > 20_000_000_000L);
                lastPrune = nanos;
            }
            r = RUNS.computeIfAbsent(wearerKey + "|" + objKey, k -> new Runtime(objKey, g));
        }
        r.g = g;   // the same graph after a save: keeps each node's timers (by node id)
        return r.eval(w, nanos);
    }

    /** The running copy of a graph on a wearer (for the editor to show what each node is doing), or null. */
    static Runtime running(String wearerKey, String objKey) {
        synchronized (RUNS) { return RUNS.get(wearerKey + "|" + objKey); }
    }

    static void forget(String objKeyPrefix) {
        synchronized (RUNS) { RUNS.keySet().removeIf(k -> k.substring(k.indexOf('|') + 1).startsWith(objKeyPrefix)); }
    }

    static String layerKey(LayerConfig.Layer l) { return "l:" + (l.folder == null ? "" : l.folder) + "/" + l.label; }
    static String folderKey(String folder) { return "f:" + folder; }
}
