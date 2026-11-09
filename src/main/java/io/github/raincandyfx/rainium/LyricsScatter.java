package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Random;

/**
 * The Scatter mode: each word of the song appears on its own at the moment it is sung, in a spot
 * near your crosshair, turned to face you with a slight random tilt, and fades a second or two later.
 * A word's spot is the best of 28 jittered tries (in one of four columns across the view, alternately
 * a little above and below the middle): the one with the most clear space round it from the other
 * words up at the same time. It is then pulled in front of any wall and out of the ground (LyricsWorld).
 * Each word stays where it was put. Words are drawn from a small picture per line (an atlas, one row
 * a word), so no video is made in this mode.
 */
final class LyricsScatter {
    static final int MAX_WORDS = 24, SLOTS = 4, MAX_PIECES = 32;
    static final double APPEAR = 0.28, FADE = LyricsWords.FADE;
    private static final int ROW_W = LyricsWords.ROW_W, ROW_H = LyricsWords.ROW_H;
    private static final int BEHIND_ENTITIES = -100;
    private static final String[] NAMES = {"lyrics/words0", "lyrics/words1", "lyrics/words2", "lyrics/words3"};

    /** One word in the world. Times are song seconds. */
    static final class Piece {
        final int slot, row;
        final float u0, u1;          // the part of the atlas row that holds the word
        final double t, fadeAt;
        final double x, y, z;        // where it hangs
        final double ex, ey, ez;     // where your eye was, for Move with me
        final Vector3f right, up;    // how it faces
        final double halfW, halfH;   // blocks
        final LyricsWords.Spot spot; // how much of the view it covers, for the next words to keep clear of it
        final float drift;
        Piece(int slot, int row, float u0, float u1, double t, double fadeAt, double[] at, double[] eye, Vector3f right, Vector3f up,
              double halfW, double halfH, LyricsWords.Spot spot, float drift) {
            this.slot = slot; this.row = row; this.u0 = u0; this.u1 = u1; this.t = t; this.fadeAt = fadeAt;
            x = at[0]; y = at[1]; z = at[2]; ex = eye[0]; ey = eye[1]; ez = eye[2];
            this.right = right; this.up = up; this.halfW = halfW; this.halfH = halfH; this.spot = spot; this.drift = drift;
        }
    }

    private static final Random RNG = new Random();
    private static final ArrayList<Piece> pieces = new ArrayList<>();
    // the atlas of the line being sung: which words are in it and how wide each is
    private static int line = -2, slot = -1, placed;
    private static LyricsLrc.Line atlasLine;
    private static float[] ink0 = new float[0], ink1 = new float[0];
    private static final boolean[] slotHas = new boolean[SLOTS];
    private static final int[] rowsOf = new int[SLOTS];

    private LyricsScatter() {}

    static void reset() {
        pieces.clear();
        line = -2; slot = -1; placed = 0; atlasLine = null;
        java.util.Arrays.fill(slotHas, false);
    }

    // ------------------------------------------------------------------ pure parts (tested offline)

    // ------------------------------------------------------------------ every tick

    /** Keeps the atlas of the line being sung (the next line's words come from it as they are sung). */
    static void update(LyricsLrc lrc, double pos, LayerConfig.LyricsSettings s) {
        int li = lrc.lineAt(pos + 0.1);
        if (li == line) return;
        boolean back = li < line;
        line = li;
        placed = 0;
        if (back) pieces.removeIf(p -> p.t > pos + 0.2);
        if (li < 0) { atlasLine = null; return; }
        LyricsLrc.Line ln = lrc.lines.get(li);
        atlasLine = ln;
        int n = Math.min(MAX_WORDS, ln.words().size());
        slot = (slot + 1) % SLOTS;
        final int mine = slot;
        pieces.removeIf(p -> p.slot == mine);   // the picture it used is about to be replaced
        BufferedImage img = new BufferedImage(ROW_W, ROW_H * Math.max(1, n), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        ink0 = new float[n]; ink1 = new float[n];
        int rgb = LyricsWords.colorOf(s.textColor);
        for (int i = 0; i < n; i++) {
            float[] ink = LyricsWords.drawWord(g, ln.words().get(i).text(), i, rgb);
            ink0[i] = ink[0]; ink1[i] = ink[1];
        }
        g.dispose();
        int H = img.getHeight();
        NativeImage ni = new NativeImage(ROW_W, H, false);
        int[] px = img.getRGB(0, 0, ROW_W, H, null, 0, ROW_W);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < ROW_W; x++) {
                int p = px[y * ROW_W + x];   // ARGB → ABGR
                ni.setPixelABGR(x, y, (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF));
            }
        Minecraft.getInstance().getTextureManager().register(Identifier.fromNamespaceAndPath("rainium", NAMES[mine]), new DynamicTexture(() -> "rainium lyrics words", ni));
        slotHas[mine] = true;
        rowsOf[mine] = n;
    }

    // ------------------------------------------------------------------ every frame

    static void submit(SubmitNodeCollector collector, LayerConfig.LyricsSettings s, LyricsLrc lrc, double pos) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 eyeV = cam.position();
        double[] eye = {eyeV.x, eyeV.y, eyeV.z};
        Vector3fc lv = cam.leftVector(), uv = cam.upVector();
        Vector3f camRight = new Vector3f(-lv.x(), -lv.y(), -lv.z());
        Vector3f camUp = new Vector3f(uv.x(), uv.y(), uv.z());
        Vector3f fwd = new Vector3f(lv.x(), lv.y(), lv.z()).cross(camUp).normalize();
        double vFov = mc.options.fov().get();
        double tanV = Math.tan(Math.toRadians(vFov) / 2);
        double aspect = Math.max(0.5, (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()));
        double tanH = tanV * aspect;
        double hFov = Math.toDegrees(2 * Math.atan(tanH));
        LyricsWorld.Solid solid = s.wallSafe ? Lyrics.solidWorld(mc) : null;

        // words whose moment has come
        if (atlasLine != null && slot >= 0 && slotHas[slot]) {
            double viewYaw = Math.toDegrees(Math.atan2(-fwd.x, fwd.z)), viewPitch = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, fwd.y))));
            int n = Math.min(MAX_WORDS, atlasLine.words().size());
            while (placed < n && pos >= atlasLine.words().get(placed).t() - 0.06) {
                LyricsLrc.Word w = atlasLine.words().get(placed);
                if (pos <= LyricsWords.fadeAt(w)) place(s, w, placed, eye, viewYaw, viewPitch, hFov, vFov, tanH, tanV, solid);
                placed++;
            }
        }
        while (pieces.size() > MAX_PIECES) pieces.remove(0);
        pieces.removeIf(p -> pos > p.fadeAt + FADE || pos < p.t - 1.5 || sq(p.x - eye[0]) + sq(p.y - eye[1]) + sq(p.z - eye[2]) > 4096);

        double opacity = Math.max(0, Math.min(1, s.opacityPct / 100.0));
        double bounce = Math.max(0, Math.min(1, s.floatPct / 100.0));
        long nanos = System.nanoTime();
        // furthest first, so a near word is drawn over a far one
        ArrayList<Piece> order = new ArrayList<>(pieces);
        order.sort((a, b) -> Double.compare(dist2(b, eye, s.followPlayer), dist2(a, eye, s.followPlayer)));
        for (int sl = 0; sl < SLOTS; sl++) {
            if (!slotHas[sl]) continue;
            final int mine = sl;
            ArrayList<float[]> quads = new ArrayList<>();   // cx, cy, cz, rx, ry, rz, ux, uy, uz, hw, hh, u0, u1, v0, v1, alpha
            for (Piece p : order) {
                if (p.slot != mine || pos < p.t) continue;
                float appear = (float) Math.max(0, Math.min(1, (pos - p.t) / APPEAR));
                float fade = pos < p.fadeAt ? 0f : (float) Math.max(0, Math.min(1, (pos - p.fadeAt) / FADE));
                double alpha = LyricsWords.easeOutCubic(appear) * (1 - fade) * opacity;
                if (alpha < 0.02) continue;
                double pop = (1 - 0.6 * bounce * (1 - LyricsWords.easeOutBack(appear, 2f))) * (1 - 0.15 * fade);
                double rise = -0.4 * bounce * (1 - LyricsWords.easeOutCubic(appear));
                double drift = Math.sin(nanos / 1.0e9 / 0.7 + p.drift) * 0.05 * bounce;
                double ox = 0, oy = 0, oz = 0;
                if (s.followPlayer) { ox = eye[0] - p.ex; oy = eye[1] - p.ey; oz = eye[2] - p.ez; }
                double cx = p.x + ox - eye[0], cy = p.y + oy - eye[1] + rise + drift, cz = p.z + oz - eye[2];
                if (cx * fwd.x + cy * fwd.y + cz * fwd.z < 0.1) continue;   // behind you
                float v0 = (float) p.row / rows(mine), v1 = (float) (p.row + 1) / rows(mine);
                quads.add(new float[]{(float) cx, (float) cy, (float) cz, p.right.x, p.right.y, p.right.z, p.up.x, p.up.y, p.up.z,
                    (float) (p.halfW * pop), (float) (p.halfH * pop), p.u0, p.u1, v0, v1, (float) alpha});
            }
            if (quads.isEmpty()) continue;
            Identifier tex = Identifier.fromNamespaceAndPath("rainium", NAMES[mine]);
            RenderType type = s.throughBlocks ? RenderTypes.textSeeThrough(tex) : RenderTypes.text(tex);
            collector.order(BEHIND_ENTITIES).submitCustomGeometry(new PoseStack(), type, (pose, vc) -> {
                for (float[] q : quads) quad(pose, vc, q);
            });
        }
    }

    private static int rows(int sl) { return Math.max(1, rowsOf[sl]); }

    private static double dist2(Piece p, double[] eye, boolean follow) {
        double ox = follow ? eye[0] - p.ex : 0, oy = follow ? eye[1] - p.ey : 0, oz = follow ? eye[2] - p.ez : 0;
        return sq(p.x + ox - eye[0]) + sq(p.y + oy - eye[1]) + sq(p.z + oz - eye[2]);
    }

    private static double sq(double v) { return v * v; }

    /** Puts word [index] of the line in the world: the best of 28 jittered tries in its column, then in front of walls and out of the ground. */
    private static void place(LayerConfig.LyricsSettings s, LyricsLrc.Word w, int index, double[] eye, double viewYaw, double viewPitch,
                              double hFov, double vFov, double tanH, double tanV, LyricsWorld.Solid solid) {
        double spanH = Math.min(hFov * 0.21, 18.0), spanV = Math.min(vFov * 0.17, 10.0);
        double[] base = LyricsWords.column(index, spanH, spanV);
        double near = Math.max(1.5, s.distance), far = near * 2;
        double sizeK = Math.max(1e-4, s.sizePct / 100.0);
        double halfAspect = Math.max(0.3, (ink1[index] - ink0[index]) * ROW_W / ROW_H) / 2;   // half the width over the height
        double until = LyricsWords.fadeAt(w);
        java.util.List<LyricsWords.Spot> spots = new ArrayList<>();
        for (Piece p : pieces) spots.add(p.spot);
        double bestScore = Double.NEGATIVE_INFINITY, bestYaw = viewYaw, bestPitch = viewPitch, bestDist = (near + far) / 2;
        for (int i = 0; i < 28; i++) {
            double spread = 3.0 + i * 0.6;
            double dh = Math.max(-spanH * 1.25, Math.min(spanH * 1.25, base[0] + RNG.nextGaussian() * spread));
            double dv = Math.max(-spanV * 1.25, Math.min(spanV * 1.25, base[1] + RNG.nextGaussian() * spread * 0.6));
            double dist = near + RNG.nextDouble() * (far - near);
            double[] size = LyricsWords.wordSize(sizeK, halfAspect, dist, tanH, tanV, s.keepOnScreen);
            double halfWDeg = Math.toDegrees(Math.atan(size[0] / dist)) + 2.0, halfHDeg = Math.toDegrees(Math.atan(size[1] / dist)) + 2.0;
            double g = LyricsWords.gap(viewYaw + dh, viewPitch + dv, halfWDeg, halfHDeg, spots, w.t(), until);
            double sc = LyricsWords.score(g, dh, dv, base[0], base[1]);
            if (sc > bestScore) { bestScore = sc; bestYaw = viewYaw + dh; bestPitch = viewPitch + dv; bestDist = dist; }
        }
        double[] d = LyricsWords.direction(bestPitch, bestYaw);
        double[] wanted = {eye[0] + d[0] * bestDist, eye[1] + d[1] * bestDist, eye[2] + d[2] * bestDist};
        double[] at = wanted;
        double kept = 1;
        if (solid != null) {
            at = LyricsWorld.aboveFloor(solid, LyricsWorld.inFront(solid, eye, wanted));
            kept = LyricsWorld.keptScale(eye, wanted, at);
        }
        double dist = Math.sqrt(sq(at[0] - eye[0]) + sq(at[1] - eye[1]) + sq(at[2] - eye[2]));
        double[] size = LyricsWords.wordSize(sizeK, halfAspect, bestDist, tanH, tanV, s.keepOnScreen);
        // the size it would have had at the chosen distance, shrunk in step with being pulled in
        double halfW = size[0] * kept, halfH = size[1] * kept;
        // turned to face you, a little off square so it does not look pasted on
        Vector3f n = new Vector3f((float) (eye[0] - at[0]), (float) (eye[1] - at[1]), (float) (eye[2] - at[2]));
        if (n.length() < 1e-3) return;
        n.normalize();
        Vector3f right = new Vector3f(n.z, 0f, -n.x);
        if (right.length() < 1e-3) right.set(1, 0, 0); else right.normalize();
        Vector3f up = new Vector3f(n).cross(right).normalize();
        float yaw = (float) Math.toRadians((RNG.nextDouble() - 0.5) * 50), roll = (float) Math.toRadians((RNG.nextDouble() - 0.5) * 16);
        right.rotateY(yaw); up.rotateY(yaw); n.rotateY(yaw);
        right.rotateAxis(roll, n.x, n.y, n.z); up.rotateAxis(roll, n.x, n.y, n.z);
        double halfWDeg = Math.toDegrees(Math.atan(halfW / Math.max(0.5, dist))) + 2.0, halfHDeg = Math.toDegrees(Math.atan(halfH / Math.max(0.5, dist))) + 2.0;
        pieces.add(new Piece(slot, index, ink0[index], ink1[index], w.t(), until, at, eye, right, up, halfW, halfH,
            new LyricsWords.Spot(w.t(), until, bestYaw, bestPitch, halfWDeg, halfHDeg), RNG.nextFloat() * 100f));
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer vc, float[] q) {
        int light = 0xF000F0;
        int color = ((int) Math.round(q[15] * 255) << 24) | 0xFFFFFF;
        float hw = q[9], hh = q[10];
        v(pose, vc, q, -hw, hh, q[11], q[13], color, light);
        v(pose, vc, q, -hw, -hh, q[11], q[14], color, light);
        v(pose, vc, q, hw, -hh, q[12], q[14], color, light);
        v(pose, vc, q, hw, hh, q[12], q[13], color, light);
    }

    private static void v(PoseStack.Pose pose, VertexConsumer vc, float[] q, float x, float y, float tu, float tv, int color, int light) {
        vc.addVertex(pose, q[0] + q[3] * x + q[6] * y, q[1] + q[4] * x + q[7] * y, q[2] + q[5] * x + q[8] * y)
          .setColor(color).setUv(tu, tv).setLight(light);
    }
}
