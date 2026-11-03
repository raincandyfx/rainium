package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Watches what happens to players (and the entities they fight) and keeps, for each one, a short
 * list of events (hit, hurt, totem pop...) and what's needed for states (in combat, moving...).
 * Everything here is what a client is told anyway: health, equipment, damage and entity events.
 * Runs on the game thread: {@link #tick} after each client tick, the on* hooks from the network handler.
 */
final class ReactSensors {
    private ReactSensors() {}

    /** Seconds an opponent counts as "last fought". */
    static final double OPPONENT_SECONDS = 15;

    private static long seq;
    private static final Map<UUID, Track> tracks = new HashMap<>();
    private static final Map<Integer, UUID> idToUuid = new HashMap<>();
    /** Who last damaged an entity (any living entity, by id): {attacker id, nanos}. */
    private static final Map<Integer, long[]> lastAttacker = new HashMap<>();
    private static ClientLevel lastLevel;

    /** What the switched-on graphs use (worked out again when the config changes). Checks nobody uses don't run. */
    private static React.Usage usage = React.Usage.NONE;
    private static LayerConfig usageFor;

    static React.Usage usage() { return usage; }

    /** One watched player. */
    static final class Track implements React.Wearer {
        final UUID uuid;
        int id;
        final ArrayDeque<React.Ev> evs = new ArrayDeque<>();
        float health = -1, absorb;
        boolean dead, onGround = true, using;
        int xpLevel = -1;
        long lastSwing;
        String useItem = "";
        int useTicks;
        double airTopY, x, y, z, speed;
        boolean hasPos;
        long foughtNanos;      // last time it hit something or was hit
        int opponentId = -1;   // who that was
        long seenNanos;
        final boolean self;

        Track(UUID uuid, boolean self) { this.uuid = uuid; this.self = self; }

        public long seq() { return evs.isEmpty() ? 0 : evs.peekLast().seq(); }
        public void eventsAfter(long s, List<React.Ev> out) { for (React.Ev e : evs) if (e.seq() > s) out.add(e); }
        public boolean self() { return self; }
        public float state(React.Node n) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return 0;
            Entity e = mc.level.getEntity(id);
            return e instanceof LivingEntity le ? ReactSensors.state(n, le, this) : 0;
        }
    }

    private static void emit(Track t, String kind, float amount, int flags) {
        if (t == null) return;
        t.evs.addLast(new React.Ev(++seq, kind, System.nanoTime(), amount, flags));
        while (t.evs.size() > 64) t.evs.pollFirst();
    }

    private static Track track(Entity e) {
        if (!(e instanceof Player p) || usage.isEmpty()) return null;
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && p.getUUID().equals(mc.player.getUUID());
        if (!self && !usage.others() && !Self.is(p.getId(), usageFor)) return null;   // only your own layers react: don't watch anyone else
        Track t = tracks.computeIfAbsent(p.getUUID(), u -> new Track(u, self));
        t.id = p.getId();
        idToUuid.put(p.getId(), p.getUUID());
        return t;
    }

    /** The wearer for an entity being drawn (by its id), or nobody. */
    static React.Wearer wearer(int entityId) {
        UUID u = idToUuid.get(entityId);
        Track t = u == null ? null : tracks.get(u);
        return t == null ? React.NOBODY : t;
    }

    /** A stable name for the wearer (survives respawning, which gives the player a new id). */
    static String key(int entityId) {
        UUID u = idToUuid.get(entityId);
        return u == null ? "e" + entityId : u.toString();
    }

    /** The local player's wearer key (the editor shows what its graphs are doing there). */
    static String selfKey() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "none" : mc.player.getUUID().toString();
    }

    /** Fires an event on yourself by hand (the editor's ▶ test button). */
    static void test(String kind, float amount, int flags) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        emit(track(mc.player), kind, amount, flags);
    }

    // ------------------------------------------------------------------ every tick
    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        Assets.State st = RainiumClient.ASSETS.current();
        LayerConfig cfg = st == null ? null : st.config();
        if (cfg != usageFor) {
            usageFor = cfg;
            React.Usage before = usage;
            usage = React.usage(cfg);
            if (before.others() && !usage.others()) tracks.values().removeIf(t -> !t.self);
        }
        if (level == null || usage.isEmpty()) {   // nothing reacts: watch nothing, remember nothing
            if (!tracks.isEmpty() || !lastAttacker.isEmpty() || lastLevel != null) { tracks.clear(); idToUuid.clear(); lastAttacker.clear(); lastLevel = null; }
            return;
        }
        if (level != lastLevel) { idToUuid.clear(); lastAttacker.clear(); lastLevel = level; }   // new world or dimension: ids start over
        long now = System.nanoTime();
        boolean health = usage.any("hurt", "heal", "death", "respawn");
        boolean motion = usage.any("jump", "land", "moving");
        boolean shoot = usage.has("shoot"), levels = usage.has("level_up");
        if (!usage.any("kill", "crit")) lastAttacker.clear();
        for (Player p : level.players()) {
            Track t = track(p);
            if (t == null) continue;
            t.seenNanos = now;
            if (health) health(p, t); else t.health = -1;   // starts afresh if it's used again later
            if (motion) motion(p, t); else { t.hasPos = false; t.speed = 0; }
            if (shoot) shoot(p, t); else { t.using = false; t.useTicks = 0; }
            if (levels && t.self) {
                if (t.xpLevel >= 0 && p.experienceLevel > t.xpLevel) emit(t, "level_up", p.experienceLevel, 0);
                t.xpLevel = p.experienceLevel;
            } else t.xpLevel = -1;
        }
        if (usage.has("kill")) kills(level, now);

        // forget players not seen for a while
        tracks.values().removeIf(t -> now - t.seenNanos > 60_000_000_000L);
        idToUuid.values().removeIf(u -> !tracks.containsKey(u));
    }

    private static void health(Player p, Track t) {
        float hp = p.getHealth(), ab = p.getAbsorptionAmount();
        boolean dead = p.isDeadOrDying() || hp <= 0;
        if (t.health >= 0) {
            float before = t.health + t.absorb, after = hp + ab;
            if (dead && !t.dead) emit(t, "death", before, 0);
            else if (!dead && t.dead) emit(t, "respawn", 0, 0);
            else if (!dead && after < before - 0.01f) emit(t, "hurt", before - after, 0);
            else if (!dead && hp > t.health + 0.01f) emit(t, "heal", hp - t.health, 0);
        }
        t.health = hp; t.absorb = ab; t.dead = dead;
    }

    /** Jump, land and speed. */
    private static void motion(Player p, Track t) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        boolean ground = p.onGround();
        if (t.hasPos) {
            double dx = x - t.x, dz = z - t.z, dy = y - t.y;
            double v = Math.sqrt(dx * dx + dz * dz) * 20;
            if (v > 60) v = 0;   // teleport
            t.speed += (v - t.speed) * 0.35;
            if (t.onGround && !ground && dy > 0.05 && !p.isPassenger() && !p.isInWater()) emit(t, "jump", 0, 0);
            if (!t.onGround && ground && !p.isInWater()) emit(t, "land", (float) Math.max(0, t.airTopY - y), 0);
        }
        if (ground || p.isInWater() || p.isPassenger()) t.airTopY = y; else t.airTopY = Math.max(t.airTopY, y);
        t.x = x; t.y = y; t.z = z; t.hasPos = true; t.onGround = ground;
    }

    private static void shoot(Player p, Track t) {
        // letting go of a drawn bow or trident
        boolean using = p.isUsingItem();
        if (using) { t.useItem = itemId(p.getUseItem()); t.useTicks++; }
        else {
            if (t.using && t.useTicks >= 3 && (t.useItem.endsWith(":bow") || t.useItem.endsWith(":trident"))) emit(t, "shoot", 0, 0);
            t.useTicks = 0;
        }
        t.using = using;
    }

    /** Kills: something that someone hurt recently has died. */
    private static void kills(ClientLevel level, long now) {
        for (Iterator<Map.Entry<Integer, long[]>> it = lastAttacker.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, long[]> e = it.next();
            long[] a = e.getValue();
            if (now - a[1] > 10_000_000_000L) { it.remove(); continue; }
            Entity victim = level.getEntity(e.getKey());
            if (victim == null) continue;
            if (victim instanceof LivingEntity le && (le.isDeadOrDying() || le.getHealth() <= 0)) {
                it.remove();
                if (now - a[1] <= 5_000_000_000L) emit(track(level.getEntity((int) a[0])), "kill", 0, victim instanceof Player ? 1 : 0);
            }
        }
    }

    // ------------------------------------------------------------------ from the network handler
    /** Something damaged an entity: `causeId` (or -1) is who's responsible. */
    static void onDamage(int victimId, int causeId) {
        if (causeId < 0 || !usage.any("hit", "crit", "kill", "totem_opponent", "in_combat")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity victim = mc.level.getEntity(victimId);
        Entity cause = causeId < 0 ? null : mc.level.getEntity(causeId);
        long now = System.nanoTime();
        if (cause != null && cause != victim) {
            if (usage.any("kill", "crit")) lastAttacker.put(victimId, new long[]{causeId, now});
            Track a = track(cause);
            if (a != null) {
                if (usage.has("hit")) emit(a, "hit", 0, victim instanceof Player ? 1 : 0);
                a.foughtNanos = now; a.opponentId = victimId;
            }
            Track v = track(victim);
            if (v != null) { v.foughtNanos = now; v.opponentId = causeId; }
        }
    }

    /** An entity starts swinging an arm (from the game's own swing, so it works for you and for others). */
    static void onSwing(Entity e) {
        if (!usage.has("swing") || !e.level().isClientSide()) return;
        Track t = track(e);
        if (t == null) return;
        long now = System.nanoTime();
        if (now - t.lastSwing < 50_000_000L) return;   // the same swing reported twice
        t.lastSwing = now;
        emit(t, "swing", 0, 0);
    }

    /** A critical hit on an entity: the hit came from whoever just damaged it. */
    static void onCrit(int victimId) {
        if (!usage.has("crit")) return;
        long[] a = lastAttacker.get(victimId);
        Minecraft mc = Minecraft.getInstance();
        if (a == null || mc.level == null || System.nanoTime() - a[1] > 500_000_000L) return;
        emit(track(mc.level.getEntity((int) a[0])), "crit", 0, mc.level.getEntity(victimId) instanceof Player ? 1 : 0);
    }

    /** An entity event (35 totem, 29 shield block, 30 shield broken, 9 finished eating/drinking). */
    static void onEntityEvent(Entity e, byte id) {
        if (e == null || usage.isEmpty()) return;
        switch (id) {
            case 35 -> {
                if (usage.has("totem")) emit(track(e), "totem", 0, 0);
                if (!usage.has("totem_opponent")) return;
                long now = System.nanoTime();
                for (Track t : tracks.values())   // and for whoever was fighting it
                    if (t.opponentId == e.getId() && now - t.foughtNanos <= OPPONENT_SECONDS * 1e9 && t.id != e.getId())
                        emit(t, "totem_opponent", 0, e instanceof Player ? 1 : 0);
            }
            case 29 -> { if (usage.has("shield_block")) emit(track(e), "shield_block", 0, 0); }
            case 30 -> { if (usage.has("shield_break")) emit(track(e), "shield_break", 0, 0); }
            case 9 -> { if (usage.has("eat")) emit(track(e), "eat", 0, 0); }
            default -> { }
        }
    }

    // ------------------------------------------------------------------ states
    static float state(React.Node n, LivingEntity e, Track t) {
        Minecraft mc = Minecraft.getInstance();
        switch (n.type) {
            case "health": {
                float max = Math.max(1f, e.getMaxHealth()), frac = e.getHealth() / max;
                return switch (n.str("mode")) {
                    case "above" -> frac * 100 > n.num("percent") ? 1 : 0;
                    case "missing" -> 1 - frac;
                    default -> frac * 100 < n.num("percent") ? 1 : 0;
                };
            }
            case "absorption": return e.getAbsorptionAmount() > 0 ? 1 : 0;
            case "in_combat": return t != null && t.foughtNanos != 0 && System.nanoTime() - t.foughtNanos <= n.num("seconds") * 1e9 ? 1 : 0;
            case "on_fire": return e.isOnFire() ? 1 : 0;
            case "blocking": return e.isBlocking() ? 1 : 0;
            case "sneaking": return e.isShiftKeyDown() || e.isCrouching() ? 1 : 0;
            case "sprinting": return e.isSprinting() ? 1 : 0;
            case "moving": return t != null && t.speed > n.num("speed") ? 1 : 0;
            case "gliding": return e.isFallFlying() ? 1 : 0;
            case "swimming": return e.isSwimming() ? 1 : 0;
            case "in_water": return e.isInWater() ? 1 : 0;
            case "in_lava": return e.isInLava() ? 1 : 0;
            case "on_ground": return e.onGround() ? 1 : 0;
            case "airborne": return !e.onGround() && !e.isPassenger() && !e.isInWater() && !e.isFallFlying() ? 1 : 0;
            case "riding": return e.isPassenger() ? 1 : 0;
            case "holding": {
                String want = id(n.str("item"));
                String h = n.str("hand");
                boolean main = !h.equals("off") && itemId(e.getMainHandItem()).equals(want);
                boolean off = !h.equals("main") && itemId(e.getOffhandItem()).equals(want);
                return main || off ? 1 : 0;
            }
            case "using": {
                if (!e.isUsingItem()) return 0;
                String want = n.str("item");
                return want.isBlank() || itemId(e.getUseItem()).equals(id(want)) ? 1 : 0;
            }
            case "effect": {
                String want = id(n.str("effect"));
                for (MobEffectInstance m : e.getActiveEffects()) if (want.equals(m.getEffect().getRegisteredName())) return 1;
                return 0;
            }
            case "hunger": return e instanceof Player p && p.getFoodData().getFoodLevel() < n.num("below") ? 1 : 0;
            case "night": return mc.level != null && mc.level.isDarkOutside() ? 1 : 0;
            case "raining": return mc.level != null && mc.level.isRaining() ? 1 : 0;
            case "thunder": return mc.level != null && mc.level.isThundering() ? 1 : 0;
            case "dimension": return mc.level != null && currentDimension().equals(id(n.str("dimension"))) ? 1 : 0;
            case "biome": return mc.level != null && biomeAt(e).equals(id(n.str("biome"))) ? 1 : 0;
            default: return 0;
        }
    }

    /** "minecraft:" in front of an id that has no namespace. */
    static String id(String s) {
        s = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return s.isEmpty() || s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    /** The id without "minecraft:" (how the editor shows it). */
    static String shortId(String s) { return s != null && s.startsWith("minecraft:") ? s.substring(10) : s; }

    static String itemId(ItemStack s) {
        if (s == null || s.isEmpty()) return "";
        return String.valueOf(BuiltInRegistries.ITEM.getKey(s.getItem()));
    }

    static String currentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : String.valueOf(mc.level.dimension().identifier());
    }

    static String biomeAt(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : mc.level.getBiome(e.blockPosition()).getRegisteredName();
    }

    // ------------------------------------------------------------------ for the editor's "use current" buttons
    /** What you're holding (main hand, or the off hand if that's empty), without "minecraft:". */
    static String heldItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        String m = itemId(mc.player.getMainHandItem());
        if (m.isEmpty()) m = itemId(mc.player.getOffhandItem());
        return m.isEmpty() ? null : shortId(m);
    }

    /** The effects you have now, without "minecraft:". */
    static List<String> myEffects() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.player != null) for (MobEffectInstance m : mc.player.getActiveEffects()) out.add(shortId(m.getEffect().getRegisteredName()));
        return out;
    }

    static String myDimension() { String d = currentDimension(); return d.isEmpty() ? null : shortId(d); }

    static String myBiome() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null || mc.level == null ? null : shortId(biomeAt(mc.player));
    }
}
