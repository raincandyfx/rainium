package io.github.raincandyfx.rainium;

import java.util.Random;

/** The particle simulation itself, with no game classes (so it can be tested on its own). Drawing is in ParticleFx. */
final class ParticleSim {
    private ParticleSim() {}

    static final int MAX_PER_EMITTER = 400;

    /** Vanilla particle textures offered in the editor (ids under minecraft:textures/particle/). */
    static final String[] VANILLA = {
        "glitter_7", "spark_4", "flame", "soul_fire_flame", "heart", "note", "critical_hit",
        "enchanted_hit", "bubble", "generic_3", "effect_4", "cherry_3",
    };

    static String vanillaId(String name) { return "minecraft:textures/particle/" + name + ".png"; }

    /** The vanilla particle name of a texture id, or null if it isn't one. */
    static String vanillaName(String texture) {
        if (texture == null || !texture.startsWith("minecraft:textures/particle/")) return null;
        return texture.substring("minecraft:textures/particle/".length()).replaceAll("\\.png$", "");
    }

    // ------------------------------------------------------------------ simulation (pure, testable)

    static final class Emitter {
        final float[] px = new float[MAX_PER_EMITTER], py = new float[MAX_PER_EMITTER], pz = new float[MAX_PER_EMITTER];
        final float[] vx = new float[MAX_PER_EMITTER], vy = new float[MAX_PER_EMITTER], vz = new float[MAX_PER_EMITTER];
        final float[] age = new float[MAX_PER_EMITTER], life = new float[MAX_PER_EMITTER], rot = new float[MAX_PER_EMITTER];
        int count;
        double carry;                       // fractional particles not yet spawned
        long lastNanos;
        double lastX = Double.NaN, lastY, lastZ;
        float speed;                        // smoothed movement speed of the entity (blocks/s)
        final Random rnd = new Random();

        /** Advances by dt seconds. Positions are in whatever space the caller uses (world, or emitter-local when following). */
        void step(LayerConfig.Particles s, float dt, float[] origin, float[][] axes, boolean worldUpIsMinusY, int budget) {
            // age, move, remove dead
            float drag = (float) Math.exp(-s.drag * dt);
            float g = s.gravity * dt * (worldUpIsMinusY ? 1f : -1f);   // positive gravity pulls "down"
            for (int i = 0; i < count; ) {
                age[i] += dt;
                if (age[i] >= life[i]) { remove(i); continue; }
                vy[i] += g;
                vx[i] *= drag; vy[i] *= drag; vz[i] *= drag;
                px[i] += vx[i] * dt; py[i] += vy[i] * dt; pz[i] += vz[i] * dt;
                rot[i] += s.spin * dt;
                i++;
            }
            // spawn
            float rate = switch (s.when) {
                case ALWAYS -> s.rate;
                case MOVING -> speed > 0.4f ? s.rate : 0f;
                case STILL -> speed <= 0.4f ? s.rate : 0f;
                case SPEED -> s.rate * Math.min(4f, speed / 4.3f);   // full rate at walking speed
            };
            carry += rate * dt;
            int n = (int) carry;
            carry -= n;
            n = Math.min(n, Math.min(budget, MAX_PER_EMITTER - count));
            for (int k = 0; k < n; k++) spawn(s, origin, axes, worldUpIsMinusY);
        }

        /** axes: the emitter's local x, y, z directions in the simulation space (y points down, z backwards). */
        private void spawn(LayerConfig.Particles s, float[] o, float[][] axes, boolean worldUpIsMinusY) {
            float lx = 0, ly = 0, lz = 0;
            switch (s.shape) {
                case POINT -> { }
                case SPHERE -> { float[] d = randomUnit(); float r = s.area * (float) Math.cbrt(rnd.nextFloat()); lx = d[0] * r; ly = d[1] * r; lz = d[2] * r; }
                case BOX -> { lx = (rnd.nextFloat() * 2 - 1) * s.area; ly = (rnd.nextFloat() * 2 - 1) * s.area; lz = (rnd.nextFloat() * 2 - 1) * s.area; }
                case RING -> { double a = rnd.nextDouble() * Math.PI * 2; lx = (float) Math.cos(a) * s.area; lz = (float) Math.sin(a) * s.area; }
            }
            float x = o[0] + axes[0][0] * lx + axes[1][0] * ly + axes[2][0] * lz;
            float y = o[1] + axes[0][1] * lx + axes[1][1] * ly + axes[2][1] * lz;
            float z = o[2] + axes[0][2] * lx + axes[1][2] * ly + axes[2][2] * lz;
            float[] dir = switch (s.direction) {
                case UP -> new float[]{0, worldUpIsMinusY ? -1 : 1, 0};
                case DOWN -> new float[]{0, worldUpIsMinusY ? 1 : -1, 0};
                case BACK -> norm(axes[2][0], axes[2][1], axes[2][2]);
                case FORWARD -> norm(-axes[2][0], -axes[2][1], -axes[2][2]);
                case OUT -> {
                    float dx = x - o[0], dy = y - o[1], dz = z - o[2];
                    yield dx * dx + dy * dy + dz * dz < 1e-8f ? randomUnit() : norm(dx, dy, dz);
                }
                case RANDOM -> randomUnit();
            };
            dir = cone(dir, s.spread);
            int i = count++;
            px[i] = x; py[i] = y; pz[i] = z;
            vx[i] = dir[0] * s.speed; vy[i] = dir[1] * s.speed; vz[i] = dir[2] * s.speed;
            age[i] = 0;
            life[i] = s.life * (0.75f + rnd.nextFloat() * 0.5f);
            rot[i] = rnd.nextFloat() * 360f;
        }

        private void remove(int i) {
            int last = --count;
            px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last];
            vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
            age[i] = age[last]; life[i] = life[last]; rot[i] = rot[last];
        }

        private float[] randomUnit() {
            double z = rnd.nextDouble() * 2 - 1, a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - z * z);
            return new float[]{(float) (r * Math.cos(a)), (float) (r * Math.sin(a)), (float) z};
        }

        /** A random direction within `deg` degrees of d. */
        private float[] cone(float[] d, float deg) {
            if (deg <= 0) return d;
            double maxA = Math.toRadians(Math.min(180, deg));
            double cosA = 1 - rnd.nextDouble() * (1 - Math.cos(maxA)), sinA = Math.sqrt(Math.max(0, 1 - cosA * cosA)), phi = rnd.nextDouble() * Math.PI * 2;
            // any two axes perpendicular to d
            float[] a = Math.abs(d[0]) < 0.9f ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] u = norm(d[1] * a[2] - d[2] * a[1], d[2] * a[0] - d[0] * a[2], d[0] * a[1] - d[1] * a[0]);
            float[] v = {d[1] * u[2] - d[2] * u[1], d[2] * u[0] - d[0] * u[2], d[0] * u[1] - d[1] * u[0]};
            double c = Math.cos(phi) * sinA, sn = Math.sin(phi) * sinA;
            return new float[]{(float) (d[0] * cosA + u[0] * c + v[0] * sn), (float) (d[1] * cosA + u[1] * c + v[1] * sn), (float) (d[2] * cosA + u[2] * c + v[2] * sn)};
        }

        private static float[] norm(float x, float y, float z) {
            float l = (float) Math.sqrt(x * x + y * y + z * z);
            return l < 1e-8f ? new float[]{0, 0, 0} : new float[]{x / l, y / l, z / l};
        }
    }

}
