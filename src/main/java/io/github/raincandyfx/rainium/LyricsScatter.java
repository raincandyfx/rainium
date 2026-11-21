package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Scatter mode (by line): each line of the song appears in the world on its own, at a fresh spot
 * near your crosshair, in Minecraft's own font with a shadow, wrapped into short rows. It's typed out a
 * letter at a time in step with the singing, stays where it was put (turning away leaves it behind),
 * and when the next line starts it shrinks and fades where it is.
 *
 * A line's spot is the roomiest of 28 tries in one of four columns across the view (alternately a little
 * above and below the middle), clear of the line before; it's then pulled in front of any wall and out of
 * the ground (LyricsWorld). It faces you as you were when it appeared, upright. No video is made.
 */
final class LyricsScatter {
    private static final int LIGHT = 0xF000F0, MAX_PIECES = 6;
    private static final double APPEAR = 0.15, LINGER = 2.5;
    /** The colour when none is set: a warm off-white. */
    private static final int CREAM = 0xFFF2D8;

    /** One line in the world. Times are song seconds. */
    private static final class Piece {
        final LyricsLrc.Line line;
        final List<String> rows;
        final int width;                    // the widest row, in font pixels
        final double x, y, z;               // where it hangs
        final double ex, ey, ez;            // where your eye was, for Move with me
        final Quaternionf facing;
        final double scale;                 // blocks per font pixel
        final LyricsWords.Spot spot;
        final float drift;
        double fadeFrom = Double.NaN;       // when it started to go
        Piece(LyricsLrc.Line line, List<String> rows, int width, double[] at, double[] eye, Quaternionf facing, double scale, LyricsWords.Spot spot, float drift) {
            this.line = line; this.rows = rows; this.width = width;
            x = at[0]; y = at[1]; z = at[2]; ex = eye[0]; ey = eye[1]; ez = eye[2];
            this.facing = facing; this.scale = scale; this.spot = spot; this.drift = drift;
        }
        double goneAt() { return Double.isNaN(fadeFrom) ? Math.max(line.end(), line.t() + 1) + LINGER : fadeFrom; }
    }

    private static final Random RNG = new Random();
    private static final ArrayList<Piece> pieces = new ArrayList<>();
    private static int line = -2, placedCount;

    private LyricsScatter() {}

    static void reset() {
        pieces.clear();
        line = -2;
    }

    /** Every tick. Nothing to prepare: lines are placed when they start, in {@link #submit}. */
    static void update(LyricsLrc lrc, double pos, LayerConfig.LyricsSettings s) { }

    // ------------------------------------------------------------------ every frame

    static void submit(SubmitNodeCollector collector, LayerConfig.LyricsSettings s, LyricsLrc lrc, double pos) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 eyeV = cam.position();
        double[] eye = {eyeV.x, eyeV.y, eyeV.z};
        Vector3fc lv = cam.leftVector(), uv = cam.upVector();
        Vector3f fwd = new Vector3f(lv.x(), lv.y(), lv.z()).cross(new Vector3f(uv.x(), uv.y(), uv.z())).normalize();
        double vFov = mc.options.fov().get();
        double tanV = Math.tan(Math.toRadians(vFov) / 2);
        double aspect = Math.max(0.5, (double) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight()));
        double tanH = tanV * aspect;

        // a new line: the one before starts to go, and the new one is placed near where you look now
        int li = lrc.lineAt(pos + 0.05);
        if (li != line) {
            if (li < line) pieces.clear();   // went back in the song
            for (Piece p : pieces) if (Double.isNaN(p.fadeFrom)) p.fadeFrom = pos;
            line = li;
            if (li >= 0 && li < lrc.lines.size() && !LyricsWords.clean(lrc.lines.get(li).text()).isEmpty())
                place(mc, s, lrc.lines.get(li), eye, fwd, tanH, tanV);
        }
        while (pieces.size() > MAX_PIECES) pieces.remove(0);
        pieces.removeIf(p -> pos > p.goneAt() + LyricsWords.FADE || pos < p.line.t() - 1.5
            || sq(p.x - eye[0]) + sq(p.y - eye[1]) + sq(p.z - eye[2]) > 4096);

        double opacity = Math.max(0, Math.min(1, s.opacityPct / 100.0));
        double bob = Math.max(0, Math.min(2, s.floatPct / 100.0));
        int rgb = LyricsWords.colorOf(s.textColor, CREAM);
        Font.DisplayMode mode = s.throughBlocks ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        long nanos = System.nanoTime();
        for (Piece p : pieces) {
            float in = (float) Math.max(0, Math.min(1, (pos - p.line.t()) / APPEAR));
            float out = pos < p.goneAt() ? 0f : (float) Math.max(0, Math.min(1, (pos - p.goneAt()) / LyricsWords.FADE));
            double alpha = LyricsWords.easeOutCubic(in) * (1 - out) * opacity;
            int a = (int) Math.round(alpha * 255);
            if (a < 6) continue;   // the font draws nearly-clear text as solid, so very faint text is skipped
            double ox = 0, oy = 0, oz = 0;
            if (s.followPlayer) { ox = eye[0] - p.ex; oy = eye[1] - p.ey; oz = eye[2] - p.ez; }
            double drift = Math.sin(nanos / 1.0e9 / 0.9 + p.drift) * 0.03 * bob;
            double cx = p.x + ox - eye[0], cy = p.y + oy - eye[1] + drift, cz = p.z + oz - eye[2];
            if (cx * fwd.x + cy * fwd.y + cz * fwd.z < 0.1) continue;   // behind you
            double k = p.scale * (1 - 0.35 * LyricsWords.easeOutCubic(out));   // shrinks as it goes
            List<String> shown = LyricsWords.typedRows(p.rows, LyricsWords.typed(p.line, pos));
            PoseStack ps = new PoseStack();
            ps.translate(cx, cy, cz);
            ps.mulPose(p.facing);
            ps.scale((float) k, (float) -k, (float) k);
            int color = (a << 24) | rgb;
            float top = -p.rows.size() * 10 / 2f;
            for (int i = 0; i < p.rows.size(); i++) {
                String part = shown.get(i);
                if (part.isEmpty()) continue;
                float left = -mc.font.width(p.rows.get(i)) / 2f;   // where the whole row will sit, so typing doesn't shift it
                collector.submitText(ps, left, top + i * 10, Component.literal(part).getVisualOrderText(), true, mode, LIGHT, color, 0, 0);
            }
        }
    }

    /** Puts a line in the world: the roomiest of 28 tries in its column, then in front of walls and out of the ground, facing you. */
    private static void place(Minecraft mc, LayerConfig.LyricsSettings s, LyricsLrc.Line ln, double[] eye, Vector3f fwd, double tanH, double tanV) {
        List<String> rows = LyricsWords.wrap(ln.text(), LyricsWords.ROW_CHARS);
        if (rows.isEmpty()) return;
        int width = 1;
        for (String r : rows) width = Math.max(width, mc.font.width(r));
        double hFov = Math.toDegrees(2 * Math.atan(tanH)), vFov = Math.toDegrees(2 * Math.atan(tanV));
        double viewYaw = Math.toDegrees(Math.atan2(-fwd.x, fwd.z)), viewPitch = Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, fwd.y))));
        double spanH = Math.min(hFov * 0.15, 14.0), spanV = Math.min(vFov * 0.12, 8.0);
        double[] base = LyricsWords.column(placedCount++, spanH, spanV);
        double near = Math.max(1.5, s.distance), far = near * 1.6;
        double sizeK = Math.max(1e-4, s.sizePct / 100.0);
        // a row is about 4% of the view's height, whatever the distance
        double rowShare = 0.042 * sizeK * 2 * tanV;
        double halfHDeg = Math.toDegrees(Math.atan(rowShare * rows.size() / 2)) + 1.5;
        double halfWDeg = Math.toDegrees(Math.atan(rowShare * width / 10.0 / 2)) + 1.5;
        List<LyricsWords.Spot> before = new ArrayList<>();
        for (Piece p : pieces) before.add(p.spot);
        double until = Math.max(ln.end(), ln.t() + 1) + LINGER;
        double bestScore = Double.NEGATIVE_INFINITY, bestYaw = viewYaw, bestPitch = viewPitch, bestDist = (near + far) / 2;
        for (int i = 0; i < 28; i++) {
            double spread = 2.0 + i * 0.4;
            double dh = Math.max(-spanH * 1.25, Math.min(spanH * 1.25, base[0] + RNG.nextGaussian() * spread));
            double dv = Math.max(-spanV * 1.25, Math.min(spanV * 1.25, base[1] + RNG.nextGaussian() * spread * 0.6));
            double g = LyricsWords.gap(viewYaw + dh, viewPitch + dv, halfWDeg, halfHDeg, before, ln.t(), until);
            double sc = LyricsWords.score(g, dh, dv, base[0], base[1]);
            if (sc > bestScore) { bestScore = sc; bestYaw = viewYaw + dh; bestPitch = viewPitch + dv; bestDist = near + RNG.nextDouble() * (far - near); }
        }
        double[] d = LyricsWords.direction(bestPitch, bestYaw);
        double[] wanted = {eye[0] + d[0] * bestDist, eye[1] + d[1] * bestDist, eye[2] + d[2] * bestDist};
        double[] at = wanted;
        double kept = 1;
        if (s.wallSafe) {
            LyricsWorld.Solid solid = Lyrics.solidWorld(mc);
            at = LyricsWorld.aboveFloor(solid, LyricsWorld.inFront(solid, eye, wanted));
            kept = LyricsWorld.keptScale(eye, wanted, at);
        }
        double scale = rowShare * bestDist / 10.0 * kept;   // blocks per font pixel (a row is 10 pixels)
        if (s.keepOnScreen) {   // a long row never wider than most of the view
            double limit = 1.5 * tanH * bestDist * kept;
            if (width * scale > limit) scale = limit / width;
        }
        // upright, its front towards your eye
        Vector3f n = new Vector3f((float) (eye[0] - at[0]), (float) (eye[1] - at[1]), (float) (eye[2] - at[2]));
        if (n.length() < 1e-3) return;
        n.normalize();
        Vector3f right = new Vector3f(n.z, 0f, -n.x);
        if (right.length() < 1e-3) right.set(1, 0, 0); else right.normalize();
        Vector3f up = new Vector3f(n).cross(right).normalize();
        Quaternionf facing = new Quaternionf().setFromNormalized(new Matrix3f(right, up, n));
        pieces.add(new Piece(ln, rows, width, at, eye, facing, scale,
            new LyricsWords.Spot(ln.t(), until, bestYaw, bestPitch, halfWDeg, halfHDeg), RNG.nextFloat() * 100f));
    }

    private static double sq(double v) { return v * v; }
}
