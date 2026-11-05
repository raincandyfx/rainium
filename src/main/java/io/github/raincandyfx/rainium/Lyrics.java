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
 * Music video mode shows a lyric video (LyricEngine, through LyricsVideo); Simple mode
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
    private static int anchorLine = -2;
    private static double anchorSize = -1, spotAlpha = 1;
    private static boolean placedFollow;
    private static boolean anchorVideo;
    private static Vec3 anchorPos;
    private static Vec3 lastEye;
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
            String look = s.style + "|" + s.seed + "|" + s.motifs + "|" + s.newEffects + "|" + s.look().key();
            long seed = (s.seed * 1_000_003L + key.hashCode()) & 0x3FFFFFFFL;
            LyricsVideo.song(key + "|" + look, lrc, s.style, seed, s.motifs, s.newEffects, t.title(), s.look());
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
    private static final int BEHIND_ENTITIES = -100;
    private static double followSide = 1, followX = 0.5, followY = 0.42, followDepth = DISTANCE;
    private static double enemyDepth;

    /** True while an effect (face / follow player) still runs: pct of the line (100 = the whole time), and/or secs seconds (0 = no limit). */
    static boolean active(double since, double lineLen, double pct, double secs) {
        if (secs > 0 && since >= secs) return false;
        return pct >= 100 || since < Math.max(0, pct) / 100.0 * lineLen;
    }

    /** Which side a Fixed line goes to: alternating, or always one. */
    private static int sideOf(LayerConfig.LyricsSettings s, int line) { return s.sideMode == 1 ? -1 : s.sideMode == 2 ? 1 : (line & 1) == 0 ? -1 : 1; }

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
        double sizeK = Math.max(1e-4, s.sizePct / 100.0);
        double widthN = LyricsPlacement.WIDTHS[1] * sizeK;   // on-screen width, 2 = the whole screen
        double texAspect = video ? (double) LyricEngine.H / LyricEngine.W : (double) LyricsText.H / LyricsText.W;
        double limit = s.keepOnScreen ? 1 : 1e6;
        double k = 1 - Math.exp(-dt * 14);
        boolean follow = s.placement == 1;
        List<LyricsPlacement.Rect> enemies = s.avoidEnemies || follow ? enemies(mc, eye, right, up, fwd, tanH, tanV) : List.of();

        double cxw, cyw, czw, hw, hh;
        Vector3f faceRight = new Vector3f(), faceNormal = new Vector3f();
        if (follow) {
            // beside the opponent, the same angular size whatever the distance; the side changes only when a new line begins
            double hn = widthN * texAspect * tanH / tanV;
            boolean fresh = line != anchorLine || video != anchorVideo;
            if (fresh) {
                anchorLine = line; anchorVideo = video;
                LyricsPlacement.Rect e = enemies.isEmpty() ? null : enemies.get(0);
                if (s.sideMode != 0) followSide = s.sideMode == 1 ? -1 : 1;
                else if (e != null) followSide = e.cx() > 0 ? -1 : 1;   // the roomier side
                else followSide = (line & 1) == 0 ? -1 : 1;
            }
            double tx = followX, ty = followY, td = followDepth;
            if (!enemies.isEmpty()) {
                LyricsPlacement.Rect g = LyricsPlacement.guard(enemies.get(0));
                tx = followSide < 0 ? g.x0() - widthN / 2 - s.gapPct / 100.0 : g.x1() + widthN / 2 + s.gapPct / 100.0;
                ty = enemies.get(0).cy();
                td = Math.max(0.5, enemyDepth);
            } else if (fresh) {
                tx = followSide * s.sidePct / 100.0; ty = s.heightPct / 100.0; td = s.distance;
            }
            if (s.keepOnScreen) {
                tx = LyricsPlacement.clamp(tx, -1 + LyricsPlacement.MARGIN + widthN / 2, 1 - LyricsPlacement.MARGIN - widthN / 2);
                ty = LyricsPlacement.clamp(ty, -1 + LyricsPlacement.MARGIN + hn / 2, 1 - LyricsPlacement.MARGIN - hn / 2);
            }
            if (fresh && anchorPos == null) { followX = tx; followY = ty; followDepth = td; }
            followX += (tx - followX) * k; followY += (ty - followY) * k; followDepth += (td - followDepth) * k;
            if (anchorPos == null) anchorPos = new Vec3(eye.x, eye.y, eye.z);   // only marks that a spot exists
            double zc = followDepth;
            cxw = fwd.x * zc + right.x * followX * tanH * zc + up.x * followY * tanV * zc;
            cyw = fwd.y * zc + right.y * followX * tanH * zc + up.y * followY * tanV * zc;
            czw = fwd.z * zc + right.z * followX * tanH * zc + up.z * followY * tanV * zc;
            double hl = Math.sqrt(cxw * cxw + czw * czw);
            double hx = hl < 1e-3 ? fwd.x : cxw / hl, hz = hl < 1e-3 ? fwd.z : czw / hl;
            double hn2 = Math.max(1e-3, Math.sqrt(hx * hx + hz * hz));
            hx /= hn2; hz /= hn2;
            faceRight.set((float) -hz, 0f, (float) hx);
            faceNormal.set((float) -hx, 0f, (float) -hz);
            hw = widthN * tanH * zc / 2;
            hh = hw * texAspect;
        } else {
            // a spot in the world for each line, by the rules, and then it stays exactly there
            boolean place = anchorPos == null || line != anchorLine || video != anchorVideo || sizeK != anchorSize || placedFollow;
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
                anchorLine = line; anchorSize = sizeK; anchorVideo = video; offViewNanos = 0; placedFollow = false;
                double px = sideOf(s, line) * s.sidePct / 100.0, py = s.heightPct / 100.0;
                double zc = Math.max(0.3, s.distance);
                Vec3 at = new Vec3(
                    eye.x + fwd.x * zc + right.x * px * tanH * zc + up.x * py * tanV * zc,
                    eye.y + fwd.y * zc + right.y * px * tanH * zc + up.y * py * tanV * zc,
                    eye.z + fwd.z * zc + right.z * px * tanH * zc + up.z * py * tanV * zc);
                double hx = at.x - eye.x, hz = at.z - eye.z, hl = Math.sqrt(hx * hx + hz * hz);
                if (hl < 1e-3) { hx = fwd.x; hz = fwd.z; hl = Math.max(1e-3, Math.sqrt(hx * hx + hz * hz)); }
                hx /= hl; hz /= hl;
                planeRight.set((float) -hz, 0f, (float) hx);      // faces you, upright, and fixed from now on
                planeNormal.set((float) -hx, 0f, (float) -hz);
                planeW = widthN * zc * tanH;
                planeH = planeW * texAspect;
                // the rules, applied once: fit the screen, slide clear of an enemy; the result is the spot and shape it keeps
                double ccx = at.x - eye.x, ccy = at.y - eye.y, ccz = at.z - eye.z;
                double x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9, depth = 0;
                for (int i = 0; i < 4; i++) {
                    double sxn = (i & 1) == 0 ? -planeW / 2 : planeW / 2, syn = (i & 2) == 0 ? -planeH / 2 : planeH / 2;
                    double wx = ccx + planeRight.x * sxn, wy = ccy + syn, wz = ccz + planeRight.z * sxn;
                    double z = Math.max(0.1, wx * fwd.x + wy * fwd.y + wz * fwd.z);
                    depth += z / 4;
                    double sx = (wx * right.x + wy * right.y + wz * right.z) / z / tanH, sy = (wx * up.x + wy * up.y + wz * up.z) / z / tanV;
                    x0 = Math.min(x0, sx); x1 = Math.max(x1, sx); y0 = Math.min(y0, sy); y1 = Math.max(y1, sy);
                }
                LyricsPlacement.Rect base = new LyricsPlacement.Rect(x0, y0, x1, y1);
                LyricsPlacement.Placed p = LyricsPlacement.anchored(base, enemies, limit);
                double sc = p.rect().w() / Math.max(1e-6, base.w());
                double shift = (p.rect().cx() - base.cx()) * depth * tanH;   // blocks, along your right
                anchorPos = new Vec3(at.x + right.x * shift, at.y + right.y * shift, at.z + right.z * shift);
                planeW *= sc; planeH *= sc;
                spotAlpha = p.alpha() > 0 ? 1 : 0;
                alphaShown = 0;
            }
            if (place) lastEye = eye;
            // face player / follow player: each works for a share of the line and/or a number of seconds, then the panel stays as it is
            LyricsLrc.Line ln = line >= 0 && line < lrc.lines.size() ? lrc.lines.get(line) : null;
            double since = ln == null ? 0 : pos - ln.t(), lineLen = ln == null ? 3 : Math.max(0.2, ln.end() - ln.t());
            if (s.followPlayer && active(since, lineLen, s.followPct, s.followSecs) && lastEye != null)
                anchorPos = new Vec3(anchorPos.x + eye.x - lastEye.x, anchorPos.y + eye.y - lastEye.y, anchorPos.z + eye.z - lastEye.z);
            lastEye = eye;
            if (s.facePlayer && active(since, lineLen, s.facePct, s.faceSecs)) {
                double hx = eye.x - anchorPos.x, hz = eye.z - anchorPos.z, hl = Math.sqrt(hx * hx + hz * hz);
                if (hl > 1e-3) {
                    hx /= hl; hz /= hl;   // from the panel towards you
                    planeRight.set((float) hz, 0f, (float) -hx);
                    planeNormal.set((float) hx, 0f, (float) hz);
                }
            }
            double bob = Math.sin(nowN / 1.0e9 * 1.1) * 0.04 * s.floatPct / 100.0;   // it floats a little
            cxw = anchorPos.x - eye.x; cyw = anchorPos.y + bob - eye.y; czw = anchorPos.z - eye.z;
            faceRight.set(planeRight.x, planeRight.y, planeRight.z); faceNormal.set(planeNormal.x, planeNormal.y, planeNormal.z);
            hw = planeW / 2; hh = planeH / 2;
        }
        if (follow) placedFollow = true;
        alphaShown += ((follow ? 1 : spotAlpha) - alphaShown) * k;
        alphaShown = Math.min(alphaShown, 1);
        double opacity = Math.max(0, Math.min(1, s.opacityPct / 100.0));
        if (alphaShown * opacity < 0.02) return;
        if (follow) cyw += Math.sin(nowN / 1.0e9 * 1.1) * 0.04 * s.floatPct / 100.0;
        if (cxw * fwd.x + cyw * fwd.y + czw * fwd.z < 0.1) return;   // behind you

        // the plane itself, in the world (the picture is two layers: decorations a little behind the lyrics)
        float qw = (float) hw, qh = (float) hh;
        Vector3f c = new Vector3f((float) cxw, (float) cyw, (float) czw);
        Vector3f back = new Vector3f(c).sub(new Vector3f(faceNormal).mul(0.12f * (float) (hw / Math.max(1e-6, widthN * tanH * DISTANCE / 2))));
        Vector3f upW = new Vector3f(0, 1, 0);
        int alpha = (int) Math.round(alphaShown * opacity * 255);
        int color = (alpha << 24) | 0xFFFFFF;
        RenderType type = s.throughBlocks ? RenderTypes.textSeeThrough(tex) : RenderTypes.text(tex);
        final boolean twoLayers = video;
        double roll = Math.toRadians(s.rollDeg), cr = Math.cos(roll), sr = Math.sin(roll);
        final Vector3f r = new Vector3f(faceRight).mul((float) cr).add(new Vector3f(upW).mul((float) sr));   // tilted about the way it faces
        final Vector3f uw = new Vector3f(upW).mul((float) cr).sub(new Vector3f(faceRight).mul((float) sr));
        // the video's picture has room around it for effects to spill into: the quad is that much bigger
        final float vw = video ? (float) (qw * LyricsVideo.SCALE_X) : qw, vh = video ? (float) (qh * LyricsVideo.SCALE_Y) : qh;
        // an earlier order than the entities (order 0): they are drawn over the lyrics, however near the lyrics are
        collector.order(BEHIND_ENTITIES).submitCustomGeometry(new PoseStack(), type, (pose, vc) -> {
            if (twoLayers) {
                quad(pose, vc, back, r, uw, vw, vh, 0f, 0.5f, color);
                quad(pose, vc, c, r, uw, vw, vh, 0.5f, 1f, color);
            } else quad(pose, vc, c, r, uw, qw, qh, 0f, 1f, color);
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
        if (out.isEmpty()) { double mx = (bb.minX + bb.maxX) / 2 - eye.x, my = (bb.minY + bb.maxY) / 2 - eye.y, mz = (bb.minZ + bb.maxZ) / 2 - eye.z; enemyDepth = mx * f.x + my * f.y + mz * f.z; }
        out.add(new LyricsPlacement.Rect(Math.max(-3, x0), Math.max(-3, y0), Math.min(3, x1), Math.min(3, y1)));
    }
}
