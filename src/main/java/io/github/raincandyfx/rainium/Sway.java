package io.github.raincandyfx.rainium;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-player spring-damper that makes attached meshes trail behind movement and turning,
 * plus a gentle idle sway/bob. Output is in player-model space (+X = player's left,
 * +Y = down, +Z = behind), which is the space the layer renders in.
 */
final class Sway {
    record Pose(float x, float y, float z, float yawDeg) {
        static final Pose REST = new Pose(0, 0, 0, 0);
    }

    private static final class Body {
        double t = Double.NaN, lastSeen;
        double px, py, pz, bodyRot;
        double ox, oy, oz, vx, vy, vz;   // offset and its velocity
        double yaw, yawV;
        double phase;
    }

    private static final double MAX_STEP = 1.0 / 120.0;
    private static final double TELEPORT_DIST = 4.0;
    private final Map<Integer, Body> bodies = new HashMap<>();

    void clear() { bodies.clear(); }

    /**
     * @param timeSec  continuous time in seconds (ageInTicks / 20 including partial tick)
     * @param bodyRot  body yaw in degrees (Minecraft convention)
     */
    Pose sample(int id, double timeSec, double x, double y, double z, double bodyRot, LayerConfig.Physics p, double wallClock) {
        Body b = bodies.computeIfAbsent(id, k -> {
            Body nb = new Body();
            nb.phase = (k * 0.6180339887) % 1.0 * Math.PI * 2;
            return nb;
        });
        b.lastSeen = wallClock;
        double dt = timeSec - b.t;
        double dist = Math.sqrt(sq(x - b.px) + sq(y - b.py) + sq(z - b.pz));
        if (Double.isNaN(b.t) || dt < 0 || dt > 0.5 || dist > TELEPORT_DIST) {
            b.t = timeSec; b.px = x; b.py = y; b.pz = z; b.bodyRot = bodyRot;
            b.ox = b.oy = b.oz = b.vx = b.vy = b.vz = b.yaw = b.yawV = 0;
        } else if (dt > 1e-6) {
            // world velocity -> model space (inverse of the renderer's Y rotation and (-1,-1,1) flip)
            double wvx = (x - b.px) / dt, wvy = (y - b.py) / dt, wvz = (z - b.pz) / dt;
            double a = Math.toRadians(bodyRot - 180.0);
            double rx = wvx * Math.cos(a) + wvz * Math.sin(a);
            double rz = -wvx * Math.sin(a) + wvz * Math.cos(a);
            double mvx = -rx, mvy = -wvy, mvz = rz;
            // trail opposite to the direction of travel
            double tx = -mvx * p.lag, ty = -mvy * p.lag, tz = -mvz * p.lag;
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > p.maxLag) { double s = p.maxLag / tl; tx *= s; ty *= s; tz *= s; }
            double turnRate = wrapDeg(bodyRot - b.bodyRot) / dt;               // deg/s
            double ty2 = clamp(-turnRate * p.yawLag, -p.maxYaw, p.maxYaw);    // model-space yaw lags the turn

            double w = p.stiffness, zeta = p.damping;
            double remaining = dt;
            while (remaining > 1e-9) {
                double h = Math.min(MAX_STEP, remaining);
                b.vx += (w * w * (tx - b.ox) - 2 * zeta * w * b.vx) * h; b.ox += b.vx * h;
                b.vy += (w * w * (ty - b.oy) - 2 * zeta * w * b.vy) * h; b.oy += b.vy * h;
                b.vz += (w * w * (tz - b.oz) - 2 * zeta * w * b.vz) * h; b.oz += b.vz * h;
                b.yawV += (w * w * (ty2 - b.yaw) - 2 * zeta * w * b.yawV) * h; b.yaw += b.yawV * h;
                remaining -= h;
            }
            b.t = timeSec; b.px = x; b.py = y; b.pz = z; b.bodyRot = bodyRot;
        }
        double sway = p.swayDeg * Math.sin(2 * Math.PI * p.swayHz * timeSec + b.phase);
        double bob = p.bob * Math.sin(2 * Math.PI * p.bobHz * timeSec + b.phase * 1.7);
        if (bodies.size() > 64) prune(wallClock);
        return new Pose((float) b.ox, (float) (b.oy + bob), (float) b.oz, (float) (b.yaw + sway));
    }

    private void prune(double now) {
        for (Iterator<Body> it = bodies.values().iterator(); it.hasNext(); ) {
            if (now - it.next().lastSeen > 10.0) it.remove();
        }
    }

    private static double sq(double v) { return v * v; }
    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
    private static double wrapDeg(double d) {
        d %= 360.0;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }
}
