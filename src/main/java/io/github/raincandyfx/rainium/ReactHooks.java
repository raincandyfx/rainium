package io.github.raincandyfx.rainium;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Public doorway from the mixins into the (package-private) reaction sensors. Never throws. */
public final class ReactHooks {
    private ReactHooks() {}

    /** True when some switched-on graph needs anything at all (so a hook can skip its own work first). */
    public static boolean active() { return !ReactSensors.usage().isEmpty(); }

    public static void damage(int victimId, int causeId, int directId, String type) {
        try { ReactSensors.onDamage(victimId, causeId, directId, type); } catch (Throwable ignored) { }
    }

    public static void entityEvent(Entity e, byte id) {
        try { ReactSensors.onEntityEvent(e, id); } catch (Throwable ignored) { }
    }

    public static void swing(Entity e) {
        try { ReactSensors.onSwing(e); } catch (Throwable ignored) { }
    }

    public static void crit(int victimId) {
        try { ReactSensors.onCrit(victimId); } catch (Throwable ignored) { }
    }

    /** A particle packet: only its type and position matter (sweeping attacks). */
    public static void particlePacket(net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket p) {
        if (!ReactSensors.usage().has("sweep")) return;
        try {
            // @port particle begin  (26.3: the packet's accessors are read by name; other versions call them directly)
            Object opt = call(p, "particle", "getParticle");
            Object type = call(opt, "getType", "type");
            String id = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey((net.minecraft.core.particles.ParticleType<?>) type));
            double x = ((Number) call(p, "x", "getX")).doubleValue(), y = ((Number) call(p, "y", "getY")).doubleValue(), z = ((Number) call(p, "z", "getZ")).doubleValue();
            // @port particle end
            ReactSensors.onParticle(id, x, y, z);
        } catch (Throwable ignored) { }
    }

    /** The result of the first of these no-argument methods that exists. */
    private static Object call(Object o, String... names) throws ReflectiveOperationException {
        for (String n : names) {
            try { return o.getClass().getMethod(n).invoke(o); } catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(names[0]);
    }

    public static void levelEvent(int type, BlockPos pos) {
        try { ReactSensors.onLevelEvent(type, pos); } catch (Throwable ignored) { }
    }

    public static void pickUp(int playerId, int itemEntityId) {
        try { ReactSensors.onPickUp(playerId, itemEntityId); } catch (Throwable ignored) { }
    }

    public static void blockPlaced(String block) {
        try { if (ReactSensors.usage().has("block_placed")) ReactSensors.onBlockPlaced(block); } catch (Throwable ignored) { }
    }
}
