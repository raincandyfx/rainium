package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Watches what happens to players (and the entities they fight) and keeps, for each one, a short
 * list of events (hit, hurt, totem pop...) and what's needed for states (in combat, moving...).
 * Everything here is what a client is told anyway: health, equipment, damage, entity events,
 * entities appearing and disappearing, particles and chat.
 *
 * Nothing is watched that no switched-on graph uses ({@link #usage}): each check below starts with
 * "is this used?", and with no reactions at all, nothing runs and nothing is kept.
 * Runs on the game thread: {@link #tick} after each client tick, the on* hooks from the network
 * handler, mixins and Fabric events (see ReactHooks).
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
    /** Everyone who damaged an entity recently (for assists): victim id -> attacker id -> nanos. */
    private static final Map<Integer, Map<Integer, Long>> attackers = new HashMap<>();
    /** Thrown pearls and fishing bobbers still in flight: entity id -> owner id. */
    private static final Map<Integer, Integer> pearls = new HashMap<>(), bobbers = new HashMap<>();
    /** Pearls that just landed: {owner id, x, y, z, nanos}. */
    private static final List<double[]> landed = new ArrayList<>();
    /** Bobbers just reeled in: owner id -> nanos. */
    private static final Map<Integer, Long> reeled = new HashMap<>();
    private static ClientLevel lastLevel;

    /** What the switched-on graphs use (worked out again when the config changes). */
    private static React.Usage usage = React.Usage.NONE;
    private static LayerConfig usageFor;

    static React.Usage usage() { return usage; }

    // which groups of checks are needed
    private static boolean needHealth() { return usage.any("hurt", "heal", "death", "respawn", "close_call", "killed_by_player", "kill_streak", "knockback", "gapple"); }
    private static boolean needMotion() { return usage.any("jump", "land", "moving", "falling", "knockback", "launched", "chorus", "mace_smash", "enter_water", "leave_water", "riptide"); }
    private static boolean needUse() { return usage.any("shoot", "gapple", "chorus"); }
    private static boolean needDamage() {
        return usage.any("hit", "crit", "kill", "totem_opponent", "in_combat", "mace_smash", "smashed", "crystal_hit", "anchor_blast", "hit_by_projectile",
            "shot_player", "assist", "killed_by_player", "kill_streak", "disable_shield", "opponent_armor_broke", "opponent_pearled", "hurt",
            "opponent_health", "opponent_near", "opponent_holding", "opponent_blocking", "opponent_looking", "sweep");
    }
    private static boolean needAttackers() { return usage.any("kill", "crit", "kill_streak", "assist", "killed_by_player", "disable_shield", "sweep"); }
    private static boolean needEntities() { return usage.any("pearl_thrown", "pearl_teleport", "opponent_pearled", "wind_charge", "firework", "lightning", "fishing_catch"); }

    /** One watched player. */
    static final class Track implements React.Wearer {
        final UUID uuid;
        int id;
        final ArrayDeque<React.Ev> evs = new ArrayDeque<>();
        float health = -1, absorb;
        boolean dead, onGround = true, using, inWater, riptide;
        int xpLevel = -1;
        long lastSwing, lastPop, lastLaunch, chorusUntil, kbUntil, damageNanos, totemNanos;
        float kbMax;
        String useItem = "", damageKind = "other";
        int useTicks, streak;
        double airTopY, x, y, z, speed;
        boolean hasPos;
        long foughtNanos;      // last time it hit something or was hit
        int opponentId = -1;   // who that was
        long seenNanos;
        Set<String> effects;   // yours, for gained / lost
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

        /** The one it's fighting (in the last 15 s), or null. */
        Entity opponent() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || opponentId < 0 || System.nanoTime() - foughtNanos > OPPONENT_SECONDS * 1e9) return null;
            return mc.level.getEntity(opponentId);
        }
    }

    private static void emit(Track t, String kind, float amount, int flags) { emit(t, kind, amount, flags, null); }

    private static void emit(Track t, String kind, float amount, int flags, String tag) {
        if (t == null || !usage.has(kind)) return;
        t.evs.addLast(new React.Ev(++seq, kind, System.nanoTime(), amount, flags, tag));
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

    private static Track trackOf(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null || entityId < 0 ? null : track(mc.level.getEntity(entityId));
    }

    private static Track selfTrack() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : track(mc.player);
    }

    /** Players fighting this entity (it's their opponent), except itself. */
    private static List<Track> fighting(Entity e) {
        List<Track> out = new ArrayList<>();
        long now = System.nanoTime();
        for (Track t : tracks.values())
            if (t.opponentId == e.getId() && now - t.foughtNanos <= OPPONENT_SECONDS * 1e9 && t.id != e.getId()) out.add(t);
        return out;
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

    /** Fires an event on yourself by hand (the editor's try-it button). */
    static void test(String kind, float amount, int flags, String tag) {
        emit(selfTrack(), kind, amount, flags, tag);
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
            if (!tracks.isEmpty() || !lastAttacker.isEmpty() || lastLevel != null) clearAll();
            if (level == null) lastLevel = null;
            return;
        }
        if (level != lastLevel) {   // new world or dimension: entity ids start over
            boolean moved = lastLevel != null;
            idToUuid.clear(); lastAttacker.clear(); attackers.clear(); pearls.clear(); bobbers.clear(); landed.clear(); reeled.clear();
            lastLevel = level;
            if (moved) emit(selfTrack(), "dimension_change", 0, 0, currentDimension());
        }
        long now = System.nanoTime();
        boolean health = needHealth(), motion = needMotion(), use = needUse();
        boolean levels = usage.has("level_up"), effects = usage.any("effect_gained", "effect_lost"), riptide = usage.has("riptide");
        if (!needAttackers()) { lastAttacker.clear(); attackers.clear(); }
        if (!needEntities()) { pearls.clear(); bobbers.clear(); landed.clear(); reeled.clear(); }
        for (Player p : level.players()) {
            Track t = track(p);
            if (t == null) continue;
            t.seenNanos = now;
            if (health) health(p, t, now); else t.health = -1;   // starts afresh if it's used again later
            if (motion) motion(p, t, now); else { t.hasPos = false; t.speed = 0; }
            if (use) use(p, t, now); else { t.using = false; t.useTicks = 0; }
            if (riptide) { boolean r = p.isAutoSpinAttack(); if (r && !t.riptide) emit(t, "riptide", 0, 0); t.riptide = r; }
            if (levels && t.self) {
                if (t.xpLevel >= 0 && p.experienceLevel > t.xpLevel) emit(t, "level_up", p.experienceLevel, 0);
                t.xpLevel = p.experienceLevel;
            } else t.xpLevel = -1;
            if (effects && t.self) effects(p, t); else t.effects = null;
        }
        if (usage.any("kill", "kill_streak", "assist", "killed_by_player")) deaths(level, now);
        if (!landed.isEmpty()) pearlLandings(level, now);
        if (!reeled.isEmpty()) reeled.values().removeIf(at -> now - at > 3_000_000_000L);

        // forget players not seen for a while
        tracks.values().removeIf(t -> now - t.seenNanos > 60_000_000_000L);
        idToUuid.values().removeIf(u -> !tracks.containsKey(u));
    }

    private static void clearAll() {
        tracks.clear(); idToUuid.clear(); lastAttacker.clear(); attackers.clear(); pearls.clear(); bobbers.clear(); landed.clear(); reeled.clear();
        lastLevel = null;
    }

    /** Hurt, healed, death, respawn, close calls, golden apples seen as absorption appearing. */
    private static void health(Player p, Track t, long now) {
        float hp = p.getHealth(), ab = p.getAbsorptionAmount();
        boolean dead = p.isDeadOrDying() || hp <= 0;
        if (t.health >= 0) {
            float before = t.health + t.absorb, after = hp + ab;
            if (dead && !t.dead) {
                emit(t, "death", before, 0);
                long[] k = lastAttacker.get(p.getId());
                Minecraft mc = Minecraft.getInstance();
                if (k != null && now - k[1] <= 5_000_000_000L && mc.level != null && mc.level.getEntity((int) k[0]) instanceof Player) emit(t, "killed_by_player", 0, 1);
                t.streak = 0;
            } else if (!dead && t.dead) emit(t, "respawn", 0, 0);
            else if (!dead && after < before - 0.01f) {
                String kind = now - t.damageNanos < 400_000_000L ? t.damageKind : "other";
                emit(t, "hurt", before - after, 0, kind);
                emit(t, "close_call", hp / Math.max(1f, p.getMaxHealth()) * 100f, 0);
                t.kbUntil = now + 300_000_000L; t.kbMax = 0;
            } else if (!dead && hp > t.health + 0.01f) emit(t, "heal", hp - t.health, 0);
            // someone else's golden apple: absorption appears (not from a totem; your own comes from eating)
            if (!t.self && !dead && t.absorb <= 0 && ab > 0 && now - t.totemNanos > 1_500_000_000L) emit(t, "gapple", ab, 0, ab >= 12 ? "enchanted" : "golden");
        }
        t.health = hp; t.absorb = ab; t.dead = dead;
    }

    /** Jump, land, speed, knockback, launches, chorus teleports, water. */
    private static void motion(Player p, Track t, long now) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        boolean ground = p.onGround(), water = p.isInWater();
        if (t.hasPos) {
            double dx = x - t.x, dz = z - t.z, dy = y - t.y;
            double h = Math.sqrt(dx * dx + dz * dz), v = h * 20, all = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean teleport = all > 3;
            if (teleport) v = 0;
            t.speed += (v - t.speed) * 0.35;
            if (t.onGround && !ground && dy > 0.05 && dy < 0.9 && !p.isPassenger() && !water) emit(t, "jump", 0, 0);
            if (!t.onGround && ground && !water) emit(t, "land", (float) Math.max(0, t.airTopY - y), 0);
            if (dy > 0.9 && !teleport && !p.isFallFlying() && !p.isPassenger() && !p.isAutoSpinAttack() && now - t.lastLaunch > 1_000_000_000L) {
                t.lastLaunch = now;
                emit(t, "launched", (float) (dy * 20), 0);
            }
            if (now < t.kbUntil) t.kbMax = (float) Math.max(t.kbMax, v);
            else if (t.kbUntil != 0) { if (t.kbMax > 0.5f) emit(t, "knockback", t.kbMax, 0); t.kbUntil = 0; }
            if (teleport && now < t.chorusUntil) { emit(t, "chorus", (float) all, 0); t.chorusUntil = 0; }
            if (water && !t.inWater) emit(t, "enter_water", 0, 0);
            if (!water && t.inWater) emit(t, "leave_water", 0, 0);
        }
        if (ground || water || p.isPassenger() || p.isFallFlying()) t.airTopY = y; else t.airTopY = Math.max(t.airTopY, y);
        t.x = x; t.y = y; t.z = z; t.hasPos = true; t.onGround = ground; t.inWater = water;
    }

    /** What a player is using: shooting a bow or trident, chorus fruit about to teleport. */
    private static void use(Player p, Track t, long now) {
        boolean using = p.isUsingItem();
        if (using) { t.useItem = itemId(p.getUseItem()); t.useTicks++; }
        else {
            if (t.using && t.useTicks >= 3 && (t.useItem.endsWith(":bow") || t.useItem.endsWith(":trident"))) emit(t, "shoot", 0, 0, t.useItem);
            if (t.using && t.useItem.endsWith(":chorus_fruit")) t.chorusUntil = now + 1_500_000_000L;
            t.useTicks = 0;
        }
        t.using = using;
    }

    /** Your effects starting and ending. */
    private static void effects(Player p, Track t) {
        Set<String> now = new HashSet<>();
        for (MobEffectInstance m : p.getActiveEffects()) now.add(m.getEffect().getRegisteredName());
        if (t.effects != null) {
            for (String e : now) if (!t.effects.contains(e)) emit(t, "effect_gained", 0, 0, e);
            for (String e : t.effects) if (!now.contains(e)) emit(t, "effect_lost", 0, 0, e);
        }
        t.effects = now;
    }

    /** Kills, kill streaks and assists: something that someone hurt recently has died. */
    private static void deaths(ClientLevel level, long now) {
        for (Iterator<Map.Entry<Integer, long[]>> it = lastAttacker.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, long[]> e = it.next();
            long[] a = e.getValue();
            if (now - a[1] > 10_000_000_000L) { it.remove(); attackers.remove(e.getKey()); continue; }
            Entity victim = level.getEntity(e.getKey());
            if (victim == null) continue;
            if (victim instanceof LivingEntity le && (le.isDeadOrDying() || le.getHealth() <= 0)) {
                it.remove();
                int flags = victim instanceof Player ? 1 : 0;
                Track killer = a[1] >= now - 5_000_000_000L ? trackOf((int) a[0]) : null;
                if (killer != null) {
                    emit(killer, "kill", 0, flags);
                    killer.streak++;
                    emit(killer, "kill_streak", killer.streak, flags);
                }
                Map<Integer, Long> all = attackers.remove(e.getKey());
                if (all != null) for (Map.Entry<Integer, Long> h : all.entrySet())
                    if (h.getKey() != a[0] && now - h.getValue() <= 10_000_000_000L) emit(trackOf(h.getKey()), "assist", 0, flags);
            }
        }
    }

    /** A pearl that just landed: did its owner arrive there? */
    private static void pearlLandings(ClientLevel level, long now) {
        for (Iterator<double[]> it = landed.iterator(); it.hasNext(); ) {
            double[] l = it.next();
            if (now - (long) l[4] > 700_000_000L) { it.remove(); continue; }
            Entity owner = level.getEntity((int) l[0]);
            if (owner == null) continue;
            Vec3 p = owner.position();
            double dx = p.x - l[1], dy = p.y - l[2], dz = p.z - l[3];
            if (dx * dx + dy * dy + dz * dz <= 2.5 * 2.5) {
                it.remove();
                emit(track(owner), "pearl_teleport", 0, 0);
                if (usage.has("opponent_pearled")) for (Track t : fighting(owner)) emit(t, "opponent_pearled", 0, owner instanceof Player ? 1 : 0);
            }
        }
    }

    // ------------------------------------------------------------------ from the network handler and the game
    /** Something damaged an entity: `causeId` (or -1) is who's responsible, `directId` what hit it, `type` the damage type id. */
    static void onDamage(int victimId, int causeId, int directId, String type) {
        if (!needDamage()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity victim = mc.level.getEntity(victimId);
        if (victim == null) return;
        Entity cause = causeId < 0 ? null : mc.level.getEntity(causeId);
        Entity direct = directId < 0 ? null : mc.level.getEntity(directId);
        long now = System.nanoTime();
        String kind = React.damageKind(type);
        String t = type == null ? "" : type.substring(type.indexOf(':') + 1);
        String projectile = t.equals("arrow") ? "arrow" : t.equals("trident") ? "trident" : "other";
        int victimFlag = victim instanceof Player ? 1 : 0;
        Track v = track(victim);
        if (v != null) {
            v.damageKind = kind; v.damageNanos = now;
            if (t.equals("mace_smash")) emit(v, "smashed", 0, cause instanceof Player ? 1 : 0);
            if (t.equals("bad_respawn_point")) emit(v, "anchor_blast", 0, 0);
            if (kind.equals("explosion") && direct != null && entityId(direct).endsWith(":end_crystal")) emit(v, "crystal_hit", 0, 0);
            if (kind.equals("projectile")) emit(v, "hit_by_projectile", 0, cause instanceof Player ? 1 : 0, projectile);
        }
        if (cause != null && cause != victim) {
            if (needAttackers()) {
                lastAttacker.put(victimId, new long[]{causeId, now});
                attackers.computeIfAbsent(victimId, k -> new HashMap<>()).put(causeId, now);
            }
            Track a = track(cause);
            if (a != null) {
                emit(a, "hit", 0, victimFlag, kind);
                if (t.equals("mace_smash")) emit(a, "mace_smash", a.hasPos ? (float) Math.max(0, a.airTopY - a.y) : 0, victimFlag);
                if (kind.equals("projectile") && victim instanceof Player) emit(a, "shot_player", 0, 1, projectile);
                a.foughtNanos = now; a.opponentId = victimId;
            }
            if (v != null) { v.foughtNanos = now; v.opponentId = causeId; }
        }
    }

    /** A critical hit on an entity: the hit came from whoever just damaged it. */
    static void onCrit(int victimId) {
        if (!usage.has("crit")) return;
        long[] a = lastAttacker.get(victimId);
        Minecraft mc = Minecraft.getInstance();
        if (a == null || mc.level == null || System.nanoTime() - a[1] > 500_000_000L) return;
        emit(trackOf((int) a[0]), "crit", 0, mc.level.getEntity(victimId) instanceof Player ? 1 : 0);
    }

    /** An entity starts swinging an arm (from the game's own swing, so it works for you and for others). */
    static void onSwing(Entity e) {
        if (!usage.any("swing", "sweep") || !e.level().isClientSide()) return;
        Track t = track(e);
        if (t == null) return;
        long now = System.nanoTime();
        if (now - t.lastSwing < 50_000_000L) return;   // the same swing reported twice
        t.lastSwing = now;
        emit(t, "swing", 0, 0);
    }

    /** An entity event (35 totem, 29 shield block, 30 shield broken, 9 finished eating, 47-52 and 65 something broke). */
    static void onEntityEvent(Entity e, byte id) {
        if (e == null || usage.isEmpty()) return;
        switch (id) {
            case 35 -> {
                Track t = track(e);
                if (t != null) {
                    long now = System.nanoTime();
                    t.totemNanos = now;
                    emit(t, "totem", 0, 0);
                    if (t.lastPop != 0) emit(t, "double_pop", (now - t.lastPop) / 1e9f, 0);
                    t.lastPop = now;
                }
                if (usage.has("totem_opponent")) for (Track f : fighting(e)) emit(f, "totem_opponent", 0, e instanceof Player ? 1 : 0);
            }
            case 29 -> emit(track(e), "shield_block", 0, 0);
            case 30 -> {
                emit(track(e), "shield_break", 0, 0);
                long[] a = lastAttacker.get(e.getId());
                if (a != null && System.nanoTime() - a[1] < 1_000_000_000L) emit(trackOf((int) a[0]), "disable_shield", 0, e instanceof Player ? 1 : 0);
            }
            case 9 -> {
                Track t = track(e);
                if (t == null) return;
                emit(t, "eat", 0, 0, t.useItem);
                if (t.useItem.endsWith(":enchanted_golden_apple")) emit(t, "gapple", 0, 0, "enchanted");
                else if (t.useItem.endsWith(":golden_apple")) emit(t, "gapple", 0, 0, "golden");
            }
            case 49, 50, 51, 52, 65 -> {   // a piece of armour broke
                emit(track(e), "armor_broke", 0, 0);
                if (usage.has("opponent_armor_broke")) for (Track f : fighting(e)) emit(f, "opponent_armor_broke", 0, e instanceof Player ? 1 : 0);
            }
            default -> { }
        }
    }

    /** An entity arrived in the world: pearls, wind charges, fireworks, lightning, fishing bobbers. */
    static void onEntityLoad(Entity e) {
        if (!needEntities()) return;
        String type = entityId(e);
        Entity owner = e instanceof Projectile pr ? pr.getOwner() : null;
        switch (type.substring(type.indexOf(':') + 1)) {
            case "ender_pearl" -> { if (owner != null) { pearls.put(e.getId(), owner.getId()); emit(track(owner), "pearl_thrown", 0, 0); } }
            case "wind_charge" -> emit(track(owner), "wind_charge", 0, 0);
            case "fishing_bobber" -> { if (owner != null) bobbers.put(e.getId(), owner.getId()); }
            case "firework_rocket" -> {   // boosting: the rocket appears at a gliding player
                Track best = null;
                double bd = 9;
                Minecraft mc = Minecraft.getInstance();
                for (Track t : tracks.values()) {
                    Entity p = mc.level == null ? null : mc.level.getEntity(t.id);
                    if (!(p instanceof LivingEntity le) || !le.isFallFlying()) continue;
                    double d = p.position().distanceToSqr(e.position());
                    if (d < bd) { bd = d; best = t; }
                }
                emit(best, "firework", 0, 0);
            }
            case "lightning_bolt" -> {
                Minecraft mc = Minecraft.getInstance();
                for (Track t : tracks.values()) {
                    Entity p = mc.level == null ? null : mc.level.getEntity(t.id);
                    if (p != null) emit(t, "lightning", (float) Math.sqrt(p.position().distanceToSqr(e.position())), 0);
                }
            }
            default -> { }
        }
    }

    /** An entity left the world: a pearl landing, a bobber reeled in. */
    static void onEntityUnload(Entity e) {
        if (!needEntities()) return;
        Integer owner = pearls.remove(e.getId());
        if (owner != null && usage.any("pearl_teleport", "opponent_pearled")) {
            Vec3 p = e.position();
            landed.add(new double[]{owner, p.x, p.y, p.z, System.nanoTime()});
        }
        Integer fisher = bobbers.remove(e.getId());
        if (fisher != null) reeled.put(fisher, System.nanoTime());
    }

    /** A particle effect from the server: sweeping attacks. */
    static void onParticle(String type, double x, double y, double z) {
        if (!usage.has("sweep") || type == null || !type.endsWith(":sweep_attack")) return;
        Track best = null;
        double bd = 4 * 4;
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        for (Track t : tracks.values()) {
            if (now - t.lastSwing > 400_000_000L) continue;
            Entity p = mc.level == null ? null : mc.level.getEntity(t.id);
            if (p == null) continue;
            double d = p.position().distanceToSqr(new Vec3(x, y, z));
            if (d < bd) { bd = d; best = t; }
        }
        emit(best, "sweep", 0, 0);
    }

    /** A world event: 2002 and 2007 are splash potions breaking. */
    static void onLevelEvent(int type, BlockPos pos) {
        if (!usage.has("splashed") || (type != 2002 && type != 2007) || pos == null) return;
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        for (Track t : tracks.values()) {
            Entity p = mc.level == null ? null : mc.level.getEntity(t.id);
            if (p != null && p.position().distanceToSqr(at) <= 4.5 * 4.5) emit(t, "splashed", 0, 0);
        }
    }

    /** A player picked up an item entity. */
    static void onPickUp(int playerId, int itemEntityId) {
        if (!usage.any("picked_up", "fishing_catch")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Track t = trackOf(playerId);
        if (t == null) return;
        Entity it = mc.level.getEntity(itemEntityId);
        String item = it instanceof ItemEntity ie ? itemId(ie.getItem()) : "";
        emit(t, "picked_up", 0, 0, item);
        Long r = reeled.remove(playerId);
        if (r != null && System.nanoTime() - r < 3_000_000_000L) emit(t, "fishing_catch", 0, 0, item);
    }

    static void onBlockBroken(String block) { emit(selfTrack(), "block_broken", 0, 0, block); }

    static void onBlockPlaced(String block) { emit(selfTrack(), "block_placed", 0, 0, block); }

    /** A chat message from a player (by account id). */
    static void onChat(UUID sender, String text) {
        if (!usage.has("chat") || sender == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Player p : mc.level.players()) if (sender.equals(p.getUUID())) { emit(track(p), "chat", 0, 0, text); return; }
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
            case "falling": return t != null && t.hasPos && !e.onGround() && t.airTopY - e.getY() >= n.num("blocks") ? 1 : 0;
            case "opponent_health": {
                if (!(opponent(t) instanceof LivingEntity o)) return 0;
                float pct = o.getHealth() / Math.max(1f, o.getMaxHealth()) * 100;
                return n.str("mode").equals("above") ? (pct > n.num("percent") ? 1 : 0) : (pct < n.num("percent") ? 1 : 0);
            }
            case "opponent_near": { Entity o = opponent(t); return o != null && o.position().distanceToSqr(e.position()) <= n.num("within") * n.num("within") ? 1 : 0; }
            case "opponent_holding": {
                if (!(opponent(t) instanceof LivingEntity o)) return 0;
                String want = id(n.str("item"));
                return itemId(o.getMainHandItem()).equals(want) || itemId(o.getOffhandItem()).equals(want) ? 1 : 0;
            }
            case "opponent_blocking": return opponent(t) instanceof LivingEntity o && o.isBlocking() ? 1 : 0;
            case "opponent_looking": {
                Entity o = opponent(t);
                if (o == null) return 0;
                Vec3 look = o.getViewVector(1f), to = e.getEyePosition().subtract(o.getEyePosition());
                double len = to.length();
                return len > 1e-4 && look.dot(to) / len > 0.92 ? 1 : 0;
            }
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
            case "sleeping": return e.isSleeping() ? 1 : 0;
            case "in_cobweb": {
                if (mc.level == null) return 0;
                BlockPos p = e.blockPosition();
                return blockId(p).endsWith(":cobweb") || blockId(p.above()).endsWith(":cobweb") ? 1 : 0;
            }
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
            case "low_durability": {
                String slot = n.str("slot");
                double limit = n.num("percent") / 100.0;
                List<ItemStack> stacks = new ArrayList<>();
                if (!slot.equals("armor")) { stacks.add(e.getMainHandItem()); stacks.add(e.getOffhandItem()); }
                if (!slot.equals("held")) for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) stacks.add(e.getItemBySlot(s));
                for (ItemStack s : stacks) {
                    if (s == null || s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) continue;
                    if (1.0 - (double) s.getDamageValue() / s.getMaxDamage() < limit) return 1;
                }
                return 0;
            }
            case "totems_left": return e instanceof Player p && count(p, "minecraft:totem_of_undying") < n.num("below") ? 1 : 0;
            case "item_cooldown": return e instanceof Player p && onCooldown(p, id(n.str("item"))) ? 1 : 0;
            case "shield_cooldown": return e instanceof Player p && onCooldown(p, "minecraft:shield") ? 1 : 0;
            case "night": return mc.level != null && mc.level.isDarkOutside() ? 1 : 0;
            case "raining": return mc.level != null && mc.level.isRaining() ? 1 : 0;
            case "thunder": return mc.level != null && mc.level.isThundering() ? 1 : 0;
            case "dimension": return mc.level != null && currentDimension().equals(id(n.str("dimension"))) ? 1 : 0;
            case "biome": return mc.level != null && biomeAt(e).equals(id(n.str("biome"))) ? 1 : 0;
            case "game_mode": {
                if (!(e instanceof Player p)) return 0;
                return switch (n.str("mode")) {
                    case "creative" -> p.isCreative() ? 1 : 0;
                    case "spectator" -> p.isSpectator() ? 1 : 0;
                    default -> !p.isCreative() && !p.isSpectator() ? 1 : 0;
                };
            }
            default: return 0;
        }
    }

    private static Entity opponent(Track t) { return t == null ? null : t.opponent(); }

    /** How many of an item are in a player's inventory. */
    private static int count(Player p, String item) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) { ItemStack s = inv.getItem(i); if (!s.isEmpty() && itemId(s).equals(item)) n += s.getCount(); }
        return n;
    }

    /** Whether an item (by id) is cooling down for a player (looked up through a stack of it in the inventory). */
    private static boolean onCooldown(Player p, String item) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && itemId(s).equals(item)) return p.getCooldowns().isOnCooldown(s);
        }
        return false;
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

    static String entityId(Entity e) { return e == null ? "" : String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())); }

    static String blockId(BlockPos p) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "" : String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()));
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
