package io.github.raincandyfx.rainium;

import net.minecraft.world.entity.Entity;

/** Public doorway from the network-handler mixin into the (package-private) reaction sensors. Never throws. */
public final class ReactHooks {
    private ReactHooks() {}

    public static void damage(int victimId, int causeId) {
        try { ReactSensors.onDamage(victimId, causeId); } catch (Throwable ignored) { }
    }

    public static void entityEvent(Entity e, byte id) {
        try { ReactSensors.onEntityEvent(e, id); } catch (Throwable ignored) { }
    }

    public static void crit(int victimId) {
        try { ReactSensors.onCrit(victimId); } catch (Throwable ignored) { }
    }
}
