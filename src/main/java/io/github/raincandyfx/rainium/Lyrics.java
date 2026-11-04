package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Lyrics of whatever you're listening to, shown in the world in front of you.
 * Music video mode shows JIZURA's lyric video (LyricsVideo); Simple mode shows the sung line
 * (LyricsText). Either way the lyrics:
 *   - never cover an enemy: they sit to its left or right, with a buffer (LyricsPlacement),
 *   - always fit on the screen,
 *   - are small by default,
 *   - and never move the camera or change the world: only the lyrics are drawn.
 */
final class Lyrics {
    static final float DISTANCE = 3f;   // blocks in front of the eye
    static final String[] STYLES = {"noir", "crimson", "caution", "magenta", "paper", "hud", "mint", "specimen", "transit", "blueprint", "rouge", "mono"};

    enum Status { OFF, WAITING, NOTHING_PLAYING, LOOKING, SHOWING, NO_LYRICS, PLAIN_ONLY, INSTRUMENTAL, OFFLINE, FAILED }

    private static Status status = Status.OFF;
    private static NowPlaying.Track track;
    private static String trackKey;
    private static LyricsLrc lrc;
    private static CompletableFuture<LyricsLookup.Found> pending;
    private static String pendingKey;
    private static boolean wasOn;
    private static String lookKey = "";
    // where the lyrics are: an anchor direction in the world per line, and the rectangle shown (smoothed)
    private static int anchorLine = -2;
    private static Vector3f anchor;
    private static double[] shown;   // {cx, cy, w, h, alpha}
    private static long lastFrameNanos;
    // the one you're fighting
    private static int opponentId = -1;
    private static long foughtNanos;

    private Lyrics() {}

    static Status status() { return status; }
    static NowPlaying.Track track() { return track; }

    // ------------------------------------------------------------------ every tick
    static void tick(Minecraft mc) {
        LayerConfig.LyricsSettings s = RainiumClient.ASSETS.editable().lyrics;
        boolean on = s.enabled && mc.level != null && mc.player != null;
        if (!on) {
            if (wasOn) stop();
            return;
        }
        wasOn = true;
        if (!s.player.equals(NowPlaying.filter)) { NowPlaying.filter = s.player; NowPlaying.filterChanged(); }
        NowPlaying.want(true);
        if (s.demo && !NowPlaying.demo) NowPlaying.startDemo();
        if (!s.demo && NowPlaying.demo) NowPlaying.stopDemo();
        if (s.video) LyricsVideo.tick();
        else if (LyricsVideo.ready()) LyricsVideo.close();

        NowPlaying.Track t = NowPlaying.now();
        track = t;
        if (t == null) {
            status = NowPlaying.problem != null ? Status.FAILED : Status.NOTHING_PLAYING;
            if (trackKey != null) { trackKey = null; lrc = null; LyricsVideo.clear(); LyricsText.reset(); }
            return;
        }
        String key = t.key();
        if (!key.equals(trackKey)) {   // a new song
            trackKey = key;
            lrc = null;
            anchorLine = -2;
            LyricsVideo.clear();
            LyricsText.reset();
            if (s.chat && !t.player().equals("demo")) say(mc, Component.translatable("rainium.lyrics.now_playing", t.title(), t.artist()));
            if (t.player().equals("demo")) {
                lrc = LyricsLrc.parse(LyricsLrc.DEMO, LyricsLrc.DEMO_LENGTH);
                status = Status.SHOWING;
                pending = null;
            } else {
                status = Status.LOOKING;
                pendingKey = key;
                pending = LyricsLookup.find(RainiumClient.ASSETS.dir(), t.title(), t.artist(), t.album(), t.length(), s.online);
            }
        }
        if (pending != null && pending.isDone() && key.equals(pendingKey)) {
            LyricsLookup.Found f = pending.join();
            pending = null;
            switch (f.status()) {
                case FOUND -> {
                    lrc = LyricsLrc.parse(f.synced(), t.length());
                    status = lrc == null ? Status.NO_LYRICS : Status.SHOWING;
                }
                case PLAIN_ONLY -> status = Status.PLAIN_ONLY;
                case INSTRUMENTAL -> status = Status.INSTRUMENTAL;
                case OFFLINE -> status = Status.OFFLINE;
                case FAILED -> status = Status.FAILED;
                default -> status = Status.NO_LYRICS;
            }
        }
        if (lrc == null) return;
        double now = t.positionNow() - s.offset;
        if (s.video && LyricsVideo.ready()) {
            String look = s.style + "|" + s.seed + "|" + s.motifs + "|" + s.newEffects;
            if (!look.equals(lookKey)) { lookKey = look; LyricsVideo.clear(); }
            LyricsVideo.song(key + "|" + look, lrc.toLrc(), project(s, key));
            LyricsVideo.time(now, t.playing());
        } else {
            LyricsText.update(lrc, now);
        }
    }

    /** JIZURA's project for this song: the chosen style and parts, and a variation of its own. */
    static String project(LayerConfig.LyricsSettings s, String songKey) {
        long seed = (s.seed * 1_000_003L + songKey.hashCode()) & 0x3FFFFFFFL;
        java.util.Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("style", s.style);
        p.put("seed", seed);
        p.put("wa", s.motifs);
        p.put("extra", s.newEffects);
        p.put("typo", true);
        p.put("kinetic", true);
        p.put("horror", false);
        return JsonWriter.write(p);
    }

    private static void stop() {
        wasOn = false;
        status = Status.OFF;
        NowPlaying.want(false);
        NowPlaying.stopDemo();
        LyricsVideo.close();
        LyricsText.reset();
        trackKey = null; lrc = null; track = null; pending = null;
    }

    private static void say(Minecraft mc, Component msg) {
        if (mc.player != null) mc.player.sendSystemMessage(msg);   // shown only to you, never sent to the server
    }

    // ------------------------------------------------------------------ the one you're fighting (from the damage hook)
    static void noteDamage(int victimId, int causeId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || causeId < 0 || victimId == causeId) return;
        int me = mc.player.getId();
        if (causeId == me) { opponentId = victimId; foughtNanos = System.nanoTime(); }
        else if (victimId == me) { opponentId = causeId; foughtNanos = System.nanoTime(); }
    }

    // ------------------------------------------------------------------ drawing, from the level render hook
    static void submit(SubmitNodeCollector collector) {
        LayerConfig.LyricsSettings s = RainiumClient.ASSETS.editable().lyrics;
        if (!s.enabled || lrc == null || status != Status.SHOWING) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        boolean video = s.video && LyricsVideo.prepare();
        if (!video && !LyricsText.ready()) return;
        Identifier tex = video ? LyricsVideo.TEXTURE : LyricsText.TEXTURE;

        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 eye = cam.position();
        Vector3fc lv = cam.leftVector(), uv = cam.upVector();
        Vector3f right = new Vector3f(-lv.x(), -lv.y(), -lv.z());
        Vector3f up = new Vector3f(uv.x(), uv.y(), uv.z());
        Vector3f fwd = new Vector3f(lv.x(), lv.y(), lv.z()).cross(up).normalize();
        double fov = mc.options.fov().get();
        double tanV = Math.tan(Math.toRadians(fov) / 2);
        double aspect = Math.max(0.5, (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()));
        double tanH = tanV * aspect;

        // the size and where this line would like to be: a spot per line, alternating sides, fixed in the world
        double w = LyricsPlacement.WIDTHS[Math.max(0, Math.min(2, s.size))];
        double h = video ? w * 9 / 16 * aspect : w * ((double) LyricsText.H / LyricsText.W) * aspect;
        double pos = track == null ? 0 : track.positionNow() - s.offset;
        int line = lrc.lineAt(pos);
        if (line != anchorLine || anchor == null) {
            anchorLine = line;
            double px = (line & 1) == 0 ? -0.5 : 0.5, py = 0.42;
            anchor = new Vector3f(fwd).add(new Vector3f(right).mul((float) (px * tanH))).add(new Vector3f(up).mul((float) (py * tanV))).normalize();
        }
        double ax = 0, ay = 0.42;
        float az = anchor.dot(fwd);
        if (az > 0.05f) { ax = anchor.dot(right) / az / tanH; ay = anchor.dot(up) / az / tanV; }
        else ax = (line & 1) == 0 ? -0.5 : 0.5;

        List<LyricsPlacement.Rect> enemies = enemies(mc, eye, right, up, fwd, tanH, tanV);
        LyricsPlacement.Placed p = LyricsPlacement.place(ax, ay, w, h, enemies);

        // ease towards the new place, so dodging an enemy is a slide rather than a jump
        long nowN = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0.016 : Math.min(0.1, (nowN - lastFrameNanos) / 1e9);
        lastFrameNanos = nowN;
        double[] target = {p.rect().cx(), p.rect().cy(), p.rect().w(), p.rect().h(), p.alpha()};
        if (shown == null) shown = target.clone();
        double k = 1 - Math.exp(-dt * 14);
        for (int i = 0; i < 5; i++) shown[i] += (target[i] - shown[i]) * k;
        // never let the eased box cover an enemy: snap if it would
        LyricsPlacement.Rect eased = LyricsPlacement.Rect.centred(shown[0], shown[1], shown[2], shown[3]);
        for (LyricsPlacement.Rect e : enemies) if (LyricsPlacement.guard(e).overlaps(eased)) { shown = target.clone(); break; }
        if (shown[4] < 0.02) return;

        // from the screen box to a plane DISTANCE blocks in front of the eye, facing you
        float d = DISTANCE;
        float cx = (float) (shown[0] * d * tanH), cy = (float) (shown[1] * d * tanV);
        float hw = (float) (shown[2] / 2 * d * tanH), hh = (float) (shown[3] / 2 * d * tanV);
        Vector3f c = new Vector3f(fwd).mul(d).add(new Vector3f(right).mul(cx)).add(new Vector3f(up).mul(cy));
        int alpha = (int) Math.round(Math.max(0, Math.min(1, shown[4])) * 255);
        int color = (alpha << 24) | 0xFFFFFF;
        RenderType type = s.throughBlocks ? RenderTypes.textSeeThrough(tex) : RenderTypes.text(tex);
        final boolean twoLayers = video;
        collector.submitCustomGeometry(new PoseStack(), type, (pose, vc) -> {
            if (twoLayers) {   // JIZURA's back layer (left half), then its front layer (right half), on the same plane
                quad(pose, vc, c, right, up, hw, hh, 0f, 0.5f, color);
                quad(pose, vc, c, right, up, hw, hh, 0.5f, 1f, color);
            } else quad(pose, vc, c, right, up, hw, hh, 0f, 1f, color);
        });
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer vc, Vector3f c, Vector3f r, Vector3f u, float hw, float hh, float u0, float u1, int color) {
        int light = 0xF000F0;
        vertex(pose, vc, c, r, u, -hw, hh, u0, 0f, color, light);
        vertex(pose, vc, c, r, u, -hw, -hh, u0, 1f, color, light);
        vertex(pose, vc, c, r, u, hw, -hh, u1, 1f, color, light);
        vertex(pose, vc, c, r, u, hw, hh, u1, 0f, color, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vc, Vector3f c, Vector3f r, Vector3f u, float x, float y, float tu, float tv, int color, int light) {
        vc.addVertex(pose, c.x + r.x * x + u.x * y, c.y + r.y * x + u.y * y, c.z + r.z * x + u.z * y)
          .setColor(color).setUv(tu, tv).setLight(light);
    }

    /** Screen boxes of the enemies to keep clear: the one you're fighting, and the player or monster nearest your aim. */
    private static List<LyricsPlacement.Rect> enemies(Minecraft mc, Vec3 eye, Vector3f right, Vector3f up, Vector3f fwd, double tanH, double tanV) {
        List<LyricsPlacement.Rect> out = new ArrayList<>();
        Entity opp = opponentId >= 0 && System.nanoTime() - foughtNanos < 15_000_000_000L ? mc.level.getEntity(opponentId) : null;
        if (opp != null && opp.isAlive()) addBox(out, opp.getBoundingBox(), eye, right, up, fwd, tanH, tanV);
        Entity best = null;
        double bestCos = Math.cos(Math.toRadians(40));
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e == opp || !e.isAlive() || e.isSpectator()) continue;
            if (!(e instanceof Player) && !(e instanceof Enemy)) continue;
            if (!(e instanceof LivingEntity)) continue;
            AABB bb = e.getBoundingBox();
            double vx = (bb.minX + bb.maxX) / 2 - eye.x, vy = (bb.minY + bb.maxY) / 2 - eye.y, vz = (bb.minZ + bb.maxZ) / 2 - eye.z;
            double dist = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (dist < 0.5 || dist > 48) continue;
            double cos = (vx * fwd.x + vy * fwd.y + vz * fwd.z) / dist;
            if (cos > bestCos) { bestCos = cos; best = e; }
        }
        if (best != null) addBox(out, best.getBoundingBox(), eye, right, up, fwd, tanH, tanV);
        return out;
    }

    private static void addBox(List<LyricsPlacement.Rect> out, AABB bb, Vec3 eye, Vector3f r, Vector3f u, Vector3f f, double tanH, double tanV) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        int front = 0;
        for (int i = 0; i < 8; i++) {
            double px = ((i & 1) == 0 ? bb.minX : bb.maxX) - eye.x, py = ((i & 2) == 0 ? bb.minY : bb.maxY) - eye.y, pz = ((i & 4) == 0 ? bb.minZ : bb.maxZ) - eye.z;
            double z = px * f.x + py * f.y + pz * f.z;
            if (z > 0.05) front++;
            z = Math.max(z, 0.05);
            double sx = (px * r.x + py * r.y + pz * r.z) / z / tanH, sy = (px * u.x + py * u.y + pz * u.z) / z / tanV;
            x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy);
        }
        if (front == 0) return;   // behind you
        out.add(new LyricsPlacement.Rect(Math.max(-3, x0), Math.max(-3, y0), Math.min(3, x1), Math.min(3, y1)));
    }
}
