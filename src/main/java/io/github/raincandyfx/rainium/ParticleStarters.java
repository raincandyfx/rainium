package io.github.raincandyfx.rainium;

/** Ready-made particle emitters offered in the Add tab; each is a normal particle layer to tweak. */
final class ParticleStarters {
    static final String[] NAMES = {"sparkles", "embers", "hearts", "trail", "aura", "blank"};

    private ParticleStarters() {}

    static LayerConfig.Layer make(String id, String label) {
        LayerConfig.Layer l = LayerConfig.newParticleLayer(label);
        LayerConfig.Particles p = l.particles;
        switch (id) {
            case "sparkles" -> {      // gentle glitter drifting up around the back
                l.texture = ParticleSim.vanillaId("glitter_7");
                p.shape = LayerConfig.Particles.Shape.SPHERE; p.area = 0.4f; p.rate = 10; p.life = 1.6f;
                p.size = 0.07f; p.endSize = 0.01f; p.speed = 0.15f; p.direction = LayerConfig.Particles.Direction.UP; p.spread = 60;
            }
            case "embers" -> {        // flames rising from the shoulders
                l.texture = ParticleSim.vanillaId("flame");
                p.shape = LayerConfig.Particles.Shape.BOX; p.area = 0.25f; p.rate = 14; p.life = 1.1f;
                p.size = 0.09f; p.endSize = 0.0f; p.speed = 0.5f; p.direction = LayerConfig.Particles.Direction.UP; p.spread = 20; p.gravity = -0.3f;
                l.offset = new float[]{0f, 0.1f, 0.1f};
            }
            case "hearts" -> {        // hearts that float up while you stand still
                l.texture = ParticleSim.vanillaId("heart");
                l.render = LayerConfig.Render.TRANSLUCENT;
                p.shape = LayerConfig.Particles.Shape.RING; p.area = 0.5f; p.rate = 2; p.life = 2.5f; p.when = LayerConfig.Particles.When.STILL;
                p.size = 0.12f; p.endSize = 0.12f; p.speed = 0.25f; p.direction = LayerConfig.Particles.Direction.UP; p.spread = 10;
                l.anchor = LayerConfig.Anchor.ROOT; l.offset = new float[]{0f, -0.3f, 0f};
            }
            case "trail" -> {         // sparks left behind as you move
                l.texture = ParticleSim.vanillaId("spark_4");
                p.shape = LayerConfig.Particles.Shape.SPHERE; p.area = 0.2f; p.rate = 30; p.life = 0.9f; p.when = LayerConfig.Particles.When.SPEED;
                p.size = 0.06f; p.endSize = 0.0f; p.speed = 0.05f; p.direction = LayerConfig.Particles.Direction.RANDOM; p.spread = 180; p.gravity = 0.4f;
                l.anchor = LayerConfig.Anchor.ROOT; l.offset = new float[]{0f, 0.75f, 0.1f};
            }
            case "aura" -> {          // slow soft glow swirling around you, moving with you
                l.texture = ParticleSim.vanillaId("effect_4");
                p.shape = LayerConfig.Particles.Shape.RING; p.area = 0.6f; p.rate = 12; p.life = 2.0f; p.follow = true;
                p.size = 0.1f; p.endSize = 0.04f; p.speed = 0.2f; p.direction = LayerConfig.Particles.Direction.UP; p.spread = 15; p.spin = 90;
                l.anchor = LayerConfig.Anchor.ROOT; l.offset = new float[]{0f, 1.4f, 0f};
                l.color = 0xB4FFFFFF;
            }
            default -> { }            // blank: the defaults from newParticleLayer
        }
        return l;
    }
}
