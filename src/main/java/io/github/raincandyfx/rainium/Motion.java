package io.github.raincandyfx.rainium;

/** Named motion "feels" for the Settings tab; each scales the default physics. */
final class Motion {
    static final String[] NAMES = {"still", "calm", "normal", "lively", "bouncy"};
    static final int NORMAL = 2;
    // multipliers on the defaults: movement trail, turn lag, idle sway/bob, springiness, damping
    private static final float[][] K = {
        {0f, 0f, 0f, 1f, 1f},
        {0.5f, 0.5f, 0.5f, 1f, 1f},
        {1f, 1f, 1f, 1f, 1f},
        {1.6f, 1.5f, 1.6f, 1f, 0.8f},
        {2.2f, 2f, 2f, 0.8f, 0.45f},
    };

    private Motion() {}

    static void apply(LayerConfig.Physics p, int feel) {
        LayerConfig.Physics d = new LayerConfig.Physics();
        float[] k = K[feel];
        p.lag = r(d.lag * k[0], 0.001f); p.maxLag = r(d.maxLag * Math.max(1f, k[0]), 0.01f);
        p.yawLag = r(d.yawLag * k[1], 0.005f); p.maxYaw = r(d.maxYaw * Math.max(1f, k[1]), 0.5f);
        p.swayDeg = r(d.swayDeg * k[2], 0.05f); p.bob = r(d.bob * k[2], 0.001f);
        p.swayHz = d.swayHz; p.bobHz = d.bobHz;
        p.stiffness = r(d.stiffness * k[3], 0.1f); p.damping = r(d.damping * k[4], 0.01f);
    }

    /** Which feel the settings currently match, or -1 if they've been fine-tuned. */
    static int match(LayerConfig.Physics p) {
        for (int i = 0; i < NAMES.length; i++) {
            LayerConfig.Physics t = new LayerConfig.Physics();
            apply(t, i);
            if (eq(t.lag, p.lag) && eq(t.maxLag, p.maxLag) && eq(t.yawLag, p.yawLag) && eq(t.maxYaw, p.maxYaw)
                && eq(t.swayDeg, p.swayDeg) && eq(t.bob, p.bob) && eq(t.swayHz, p.swayHz) && eq(t.bobHz, p.bobHz)
                && eq(t.stiffness, p.stiffness) && eq(t.damping, p.damping)) return i;
        }
        return -1;
    }

    private static boolean eq(float a, float b) { return Math.abs(a - b) < 1e-4f; }
    private static float r(float v, float step) { return Math.round(v / step) * step; }
}
