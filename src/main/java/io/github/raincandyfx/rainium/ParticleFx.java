package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Particle emitters: each particle layer on each entity gets its own small simulation, advanced
 * whenever that entity is drawn, and drawn as camera-facing squares with the layer's texture.
 * Particles live in world space (so they trail behind you) unless the layer follows you, in which
 * case they live in the emitter's own space and move with it.
 */
final class ParticleFx {
    private ParticleFx() {}

    private static final int MAX_TOTAL = 4000;
    static final int MAX_PER_EMITTER = ParticleSim.MAX_PER_EMITTER;
    static final String[] VANILLA = ParticleSim.VANILLA;
    static String vanillaId(String name) { return ParticleSim.vanillaId(name); }
    static String vanillaName(String texture) { return ParticleSim.vanillaName(texture); }

    // ------------------------------------------------------------------ in the game

    private static final Map<String, ParticleSim.Emitter> emitters = new HashMap<>();
    private static long lastPrune;

    private static int totalParticles() {
        int t = 0;
        for (ParticleSim.Emitter e : emitters.values()) t += e.count;
        return t;
    }

    /**
     * Steps and draws one emitter. `pose` is the layer's pose (emitter at its origin, model axes:
     * +x left, +y down, +z back). In the world the particles are drawn relative to the camera; in the
     * editor's preview they're drawn in the preview's own space and always follow the player.
     */
    static void submit(String key, LayerConfig.Layer l, PoseStack layerPose, boolean preview, double ex, double ey, double ez,
                       SubmitNodeCollector collector, int order, RenderType type, int color, int light, int overlay) {
        PoseStack.Pose pose = layerPose.last();
        LayerConfig.Particles s = l.particles;
        long now = System.nanoTime();
        synchronized (emitters) {
            if (now - lastPrune > 5_000_000_000L) {   // forget emitters that haven't been drawn for a while
                for (Iterator<ParticleSim.Emitter> it = emitters.values().iterator(); it.hasNext(); ) if (now - it.next().lastNanos > 10_000_000_000L) it.remove();
                lastPrune = now;
            }
        }
        ParticleSim.Emitter e;
        synchronized (emitters) { e = emitters.computeIfAbsent(key, k -> new ParticleSim.Emitter()); }
        float dt = e.lastNanos == 0 ? 0f : Math.min(0.1f, (now - e.lastNanos) / 1e9f);
        e.lastNanos = now;

        // movement speed of the entity, smoothed
        if (dt > 0 && !Double.isNaN(e.lastX)) {
            double dx = ex - e.lastX, dy = ey - e.lastY, dz = ez - e.lastZ;
            float v = (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) / dt);
            if (v > 50) v = 0;   // teleport
            e.speed += (v - e.speed) * Math.min(1f, dt * 8f);
        }
        e.lastX = ex; e.lastY = ey; e.lastZ = ez;

        Matrix4f m = pose.pose();
        boolean local = s.follow || preview;
        float[] origin;
        float[][] axes;
        Vec3 cam = null;
        if (local) {
            origin = new float[]{0, 0, 0};
            axes = new float[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        } else {
            cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
            Vector3f o = m.transformPosition(new Vector3f(0, 0, 0));
            origin = new float[]{(float) (o.x() + cam.x), (float) (o.y() + cam.y), (float) (o.z() + cam.z)};
            axes = new float[][]{dirOf(m, 1, 0, 0), dirOf(m, 0, 1, 0), dirOf(m, 0, 0, 1)};
        }
        // local space has +y down; world space has +y up
        int budget;
        synchronized (emitters) { budget = Math.max(0, MAX_TOTAL - totalParticles()); }
        if (dt > 0) e.step(s, dt, origin, axes, local, budget);
        if (e.count == 0) return;

        // camera-facing directions, in the space the particles are drawn in
        Vector3f right, up;
        if (local) {
            Matrix4f inv = new Matrix4f(m).invert();
            if (preview) {   // the preview faces the screen: screen right and up, taken back into layer space
                right = norm(inv.transformDirection(new Vector3f(1, 0, 0)));
                up = norm(inv.transformDirection(new Vector3f(0, 1, 0)));
            } else {         // world camera axes, taken into layer space
                Camera c = Minecraft.getInstance().gameRenderer.mainCamera();
                Vector3fc lv = c.leftVector(), uv = c.upVector();
                right = norm(inv.transformDirection(new Vector3f(-lv.x(), -lv.y(), -lv.z())));
                up = norm(inv.transformDirection(new Vector3f(uv.x(), uv.y(), uv.z())));
            }
        } else {
            Camera c = Minecraft.getInstance().gameRenderer.mainCamera();
            Vector3fc lv = c.leftVector(), uv = c.upVector();
            right = new Vector3f(-lv.x(), -lv.y(), -lv.z());
            up = new Vector3f(uv.x(), uv.y(), uv.z());
        }

        // snapshot for drawing (the collector draws later)
        int n = e.count;
        float[] data = new float[n * 6];
        float scaleFix = local ? 1f / Math.max(1e-4f, scaleOf(m)) : 1f;   // keep sizes in blocks when drawing in layer space
        for (int i = 0; i < n; i++) {
            float t = e.age[i] / e.life[i];
            data[i * 6] = local ? e.px[i] : (float) (e.px[i] - cam.x);
            data[i * 6 + 1] = local ? e.py[i] : (float) (e.py[i] - cam.y);
            data[i * 6 + 2] = local ? e.pz[i] : (float) (e.pz[i] - cam.z);
            data[i * 6 + 3] = (s.size + (s.endSize - s.size) * t) * 0.5f * scaleFix;
            data[i * 6 + 4] = s.fade ? 1f - t : 1f;
            data[i * 6 + 5] = e.rot[i];
        }
        boolean additive = l.render == LayerConfig.Render.EMISSIVE;
        PoseStack ps = local ? layerPose : new PoseStack();   // world particles are already camera-relative
        SubmitNodeCollector.CustomGeometryRenderer geo = (p, vc) -> {
            for (int i = 0; i < n; i++) quad(p, vc, data, i, right, up, color, light, overlay, additive);
        };
        OrderedSubmitNodeCollector target = order == 0 ? collector : collector.order(order);
        target.submitCustomGeometry(ps, type, geo);
    }

    private static void quad(PoseStack.Pose p, VertexConsumer vc, float[] d, int i, Vector3f right, Vector3f up,
                             int color, int light, int overlay, boolean additive) {
        float x = d[i * 6], y = d[i * 6 + 1], z = d[i * 6 + 2], h = d[i * 6 + 3], fade = d[i * 6 + 4];
        double a = Math.toRadians(d[i * 6 + 5]);
        float ca = (float) Math.cos(a) * h, sa = (float) Math.sin(a) * h;
        // rotated corner offsets: r' = right*cos + up*sin, u' = up*cos - right*sin
        float rx = right.x() * ca + up.x() * sa, ry = right.y() * ca + up.y() * sa, rz = right.z() * ca + up.z() * sa;
        float ux = up.x() * ca - right.x() * sa, uy = up.y() * ca - right.y() * sa, uz = up.z() * ca - right.z() * sa;
        int c;
        int alpha = (int) (((color >>> 24) & 0xFF) * fade);
        if (additive) {   // additive glow ignores alpha: fade the colour instead
            int r = (int) (((color >> 16) & 0xFF) * fade), g = (int) (((color >> 8) & 0xFF) * fade), b = (int) ((color & 0xFF) * fade);
            c = (alpha << 24) | (r << 16) | (g << 8) | b;
        } else {
            c = (alpha << 24) | (color & 0xFFFFFF);
        }
        float nx = right.y() * up.z() - right.z() * up.y(), ny = right.z() * up.x() - right.x() * up.z(), nz = right.x() * up.y() - right.y() * up.x();
        vertex(p, vc, x - rx - ux, y - ry - uy, z - rz - uz, 0, 1, c, light, overlay, nx, ny, nz);
        vertex(p, vc, x + rx - ux, y + ry - uy, z + rz - uz, 1, 1, c, light, overlay, nx, ny, nz);
        vertex(p, vc, x + rx + ux, y + ry + uy, z + rz + uz, 1, 0, c, light, overlay, nx, ny, nz);
        vertex(p, vc, x - rx + ux, y - ry + uy, z - rz + uz, 0, 0, c, light, overlay, nx, ny, nz);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer vc, float x, float y, float z, float u, float v, int c, int light, int overlay,
                               float nx, float ny, float nz) {
        vc.addVertex(p, x, y, z).setColor(c).setUv(u, v).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
    }

    private static float[] dirOf(Matrix4f m, float x, float y, float z) {
        Vector3f d = norm(m.transformDirection(new Vector3f(x, y, z)));
        return new float[]{d.x(), d.y(), d.z()};
    }

    private static Vector3f norm(Vector3f v) {
        float l = v.length();
        return l < 1e-8f ? v : v.div(l);
    }

    private static float scaleOf(Matrix4f m) {
        return m.transformDirection(new Vector3f(1, 0, 0)).length();
    }
}
