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
 * Lyrics of whatever you're listening to, shown as a plane in the world.
 * Music video mode shows a JIZURA-style lyric video (JizuraNative, through LyricsVideo); Simple mode
 * shows the sung line (LyricsText). Each line gets a spot in the world and stays there while you move
 * and look around, like a sign in space. Either way the lyrics:
 *   - never cover an enemy: they sit to its left or right, with a buffer (LyricsPlacement),
 *   - always fit on the screen,
 *   - are small by default,
 *   - and never move the camera or change the world: only the lyrics are drawn.
 */
final class Lyrics {
    static final double DISTANCE = 3.5;   // blocks in front of the eye, when a line is placed
    static final String[] STYLES = {"noir", "crimson", "caution", "magenta", "paper", "hud", "mint", "specimen", "transit", "blueprint", "rouge", "mono"};

    enum Status { OFF, WAITING, NOTHING_PLAYING, LOOKING, SHOWING, NO_LYRICS, PLAIN_ONLY, INSTRUMENTAL, OFFLINE, FAILED }

    private static Status status = Status.OFF;
    private static NowPlaying.Track track;
    private static String trackKey;
    private static LyricsLrc lrc;
    private static CompletableFuture<LyricsLookup.Found> pending;
    private static String pendingKey;
    private static boolean wasOn;
    // where the lyrics are: a plane fixed in the world per line, and how it's nudged aside for an enemy (smoothed)
    private static int anchorLine = -2, anchorSize = -1;
    private static boolean anchorVideo;
    private static Vec3 anchorPos;
    private static final Vector3f planeRight = new Vector3f(1, 0, 0), planeNormal = new Vector3f(0, 0, 1);
    private static double planeW, planeH;                  // blocks
    private static double shiftShown, scaleShown = 1, alphaShown;
    private static long offViewNanos, lastFrameNanos;
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
        if (!s.video) LyricsVideo.clear();

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
        if (s.video) {
            String look = s.style + "|" + s.seed + "|" + s.motifs + "|" + s.newEffects;
            long seed = (s.seed * 1_000_003L + key.hashCode()) & 0x3FFFFFFFL;
            LyricsVideo.song(key + "|" + look, lrc, s.style, seed, s.motifs, s.newEffects, t.title());
            LyricsVideo.time(now, t.playing());
        } else {
            LyricsText.update(lrc, now);
        }
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

        double pos = track == null ? 0 : track.positionNow() - s.offset;
        int line = lrc.lineAt(pos + 0.1);   // the next line's spot is taken just as it begins
        long nowN = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0.016 : Math.min(0.1, (nowN - lastFrameNanos) / 1e9);
        lastFrameNanos = nowN;
        int size = Math.max(0, Math.min(2, s.size));

        // 1. a spot in the world for each line: left and right of where you're looking, in turn. It stays there as you
        //    move and look around; it's only put somewhere new when you've turned away from it for a while or walked off.
        boolean place = anchorPos == null || line != anchorLine || size != anchorSize || video != anchorVideo;
        if (!place) {
            double dx = anchorPos.x - eye.x, dy = anchorPos.y - eye.y, dz = anchorPos.z - eye.z;
            double z = dx * fwd.x + dy * fwd.y + dz * fwd.z;
            double sx = (dx * right.x + dy * right.y + dz * right.z) / Math.max(z, 0.05) / tanH;
            double sy = (dx * up.x + dy * up.y + dz * up.z) / Math.max(z, 0.05) / tanV;
            boolean inView = z > 0.3 && Math.abs(sx) < 1.25 && Math.abs(sy) < 1.25;
            if (inView) offViewNanos = 0;
            else if (offViewNanos == 0) offViewNanos = nowN;
            else if (nowN - offViewNanos > 800_000_000L) place = true;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 1.2 || dist > 18) place = true;
        }
        if (place) {
            anchorLine = line; anchorSize = size; anchorVideo = video; offViewNanos = 0;
            double px = (line & 1) == 0 ? -0.5 : 0.5, py = 0.42;
            double zc = DISTANCE;
            anchorPos = new Vec3(
                eye.x + fwd.x * zc + right.x * px * tanH * zc + up.x * py * tanV * zc,
                eye.y + fwd.y * zc + right.y * px * tanH * zc + up.y * py * tanV * zc,
                eye.z + fwd.z * zc + right.z * px * tanH * zc + up.z * py * tanV * zc);
            double hx = anchorPos.x - eye.x, hz = anchorPos.z - eye.z, hl = Math.sqrt(hx * hx + hz * hz);
            if (hl < 1e-3) { hx = fwd.x; hz = fwd.z; hl = Math.max(1e-3, Math.sqrt(hx * hx + hz * hz)); }
            hx /= hl; hz /= hl;
            planeRight.set((float) -hz, 0f, (float) hx);      // faces you, upright, and fixed from now on
            planeNormal.set((float) -hx, 0f, (float) -hz);
            double texAspect = video ? (double) LyricsVideo.H / LyricsVideo.W : (double) LyricsText.H / LyricsText.W;
            planeW = LyricsPlacement.WIDTHS[size] * zc * tanH;
            planeH = planeW * texAspect;
            shiftShown = 0; scaleShown = 1; alphaShown = 0;
        }

        // 2. where that plane is on your screen right now, and whether an enemy is in the way
        double bob = Math.sin(nowN / 1.0e9 * 1.1) * 0.04;   // it floats a little
        double cxw = anchorPos.x - eye.x, cyw = anchorPos.y + bob - eye.y, czw = anchorPos.z - eye.z;
        double hw = planeW / 2, hh = planeH / 2;
        double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, depth = 0;
        boolean behind = false;
        for (int i = 0; i < 4; i++) {
            double sxn = (i & 1) == 0 ? -hw : hw, syn = (i & 2) == 0 ? -hh : hh;
            double wx = cxw + planeRight.x * sxn, wy = cyw + syn, wz = czw + planeRight.z * sxn;
            double z = wx * fwd.x + wy * fwd.y + wz * fwd.z;
            if (z < 0.1) { behind = true; break; }
            depth += z / 4;
            double sx = (wx * right.x + wy * right.y + wz * right.z) / z / tanH, sy = (wx * up.x + wy * up.y + wz * up.z) / z / tanV;
            x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy);
        }
        if (behind) return;
        LyricsPlacement.Rect base = new LyricsPlacement.Rect(x0, y0, x1, y1);
        List<LyricsPlacement.Rect> enemies = enemies(mc, eye, right, up, fwd, tanH, tanV);
        LyricsPlacement.Placed p = LyricsPlacement.anchored(base, enemies);
        double tScale = p.rect().w() / Math.max(1e-6, base.w());
        double tShift = (p.rect().cx() - base.cx()) * depth * tanH;   // blocks, along your right
        double tAlpha = p.alpha();

        // ease towards that, so dodging an enemy is a slide rather than a jump
        double k = 1 - Math.exp(-dt * 14);
        shiftShown += (tShift - shiftShown) * k;
        scaleShown += (tScale - scaleShown) * k;
        alphaShown += (tAlpha - alphaShown) * k;
        // never let the eased plane cover an enemy: snap if it would
        double ecx = base.cx() + shiftShown / (depth * tanH);
        LyricsPlacement.Rect eased = LyricsPlacement.Rect.centred(ecx, base.cy(), base.w() * scaleShown, base.h() * scaleShown);
        for (LyricsPlacement.Rect e : enemies) if (LyricsPlacement.guard(e).overlaps(eased)) { shiftShown = tShift; scaleShown = tScale; alphaShown = tAlpha; break; }
        if (alphaShown < 0.02) return;

        // 3. the plane itself, in the world (the picture is two layers: decorations a little behind the lyrics)
        float cx = (float) (cxw + right.x * shiftShown), cy = (float) (cyw + right.y * shiftShown), cz = (float) (czw + right.z * shiftShown);
        float qw = (float) (hw * scaleShown), qh = (float) (hh * scaleShown);
        Vector3f c = new Vector3f(cx, cy, cz);
        Vector3f back = new Vector3f(c).sub(new Vector3f(planeNormal).mul(0.12f * (float) scaleShown));
        Vector3f upW = new Vector3f(0, 1, 0);
        int alpha = (int) Math.round(Math.max(0, Math.min(1, alphaShown)) * 255);
        int color = (alpha << 24) | 0xFFFFFF;
        RenderType type = s.throughBlocks ? RenderTypes.textSeeThrough(tex) : RenderTypes.text(tex);
        final boolean twoLayers = video;
        final Vector3f r = new Vector3f(planeRight);
        collector.submitCustomGeometry(new PoseStack(), type, (pose, vc) -> {
            if (twoLayers) {
                quad(pose, vc, back, r, upW, qw, qh, 0f, 0.5f, color);
                quad(pose, vc, c, r, upW, qw, qh, 0.5f, 1f, color);
            } else quad(pose, vc, c, r, upW, qw, qh, 0f, 1f, color);
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
