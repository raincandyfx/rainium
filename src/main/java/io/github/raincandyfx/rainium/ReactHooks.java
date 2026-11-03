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

    public static void particle(String type, double x, double y, double z) {
        try { ReactSensors.onParticle(type, x, y, z); } catch (Throwable ignored) { }
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
