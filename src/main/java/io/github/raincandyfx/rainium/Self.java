package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who "me" is for Show on: Just me. The local player, and any player whose UUID is listed under
 * Settings > Visibility > Also me (for example yourself in a Flashback replay, where the local player
 * is the camera). In a replay with nobody listed, "me" is guessed (see {@link SelfPick}) and kept for
 * the rest of the replay.
 */
final class Self {
    private Self() {}

    private static Object level;   // the world the choice was made in
    private static UUID chosen;

    /** Whether the local player is a replay camera. */
    static boolean inReplay() {
        Minecraft mc = Minecraft.getInstance();
        try {
            return mc.player != null && SelfPick.isReplayCamera(mc.player.getName().getString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the entity with this id is "me". */
    static boolean is(int entityId, LayerConfig cfg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;   // no player (e.g. menus): treat as me
        if (listed(entityId, cfg)) return true;
        if (!inReplay()) return mc.player.getId() == entityId;
        if (!cfg.meUuids.isEmpty()) return false;   // in a replay, the list decides when there is one
        Player me = replayMe();
        return me != null && me.getId() == entityId;
    }

    private static boolean listed(int entityId, LayerConfig cfg) {
        if (cfg.meUuids.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        Entity e = mc.level.getEntity(entityId);
        return e != null && listed(e, cfg);
    }

    /**
     * Whether an entity is on the Also me list: by its entity UUID (what Flashback shows), or for a
     * player also by the account UUID in its profile, in case that's the one that was pasted.
     */
    static boolean listed(Entity e, LayerConfig cfg) {
        if (cfg.meUuids.contains(e.getUUID().toString())) return true;
        if (e instanceof Player p) {
            try {
                UUID id = p.getGameProfile().id();
                return id != null && cfg.meUuids.contains(id.toString());
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /**
     * The player the editor previews: the local player, unless it isn't "me" but a listed player is
     * loaded (a replay camera), or a replay's recorded player.
     */
    static Player previewPlayer(LayerConfig cfg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        if (!cfg.meUuids.isEmpty() && !listed(mc.player, cfg) && mc.level != null) {
            for (Player p : mc.level.players()) if (p != mc.player && listed(p, cfg)) return p;
        }
        if (!inReplay() || !cfg.meUuids.isEmpty()) return mc.player;
        Player me = replayMe();
        return me != null ? me : mc.player;
    }

    /** Players loaded right now, the local player first and then the nearest others, for picking who is "me". */
    static List<Player> nearbyPlayers(int max) {
        Minecraft mc = Minecraft.getInstance();
        List<Player> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        for (Player p : mc.level.players()) if (p != mc.player) out.add(p);
        out.sort((a, b) -> Double.compare(a.distanceToSqr(mc.player), b.distanceToSqr(mc.player)));
        if (!inReplay()) out.add(0, mc.player);   // in a replay the local player is only the camera
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static Player replayMe() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        if (level != mc.level) { level = mc.level; chosen = null; }   // a new replay or world: choose again
        List<Player> players = new ArrayList<>();
        for (Player p : mc.level.players()) if (p != mc.player) players.add(p);
        if (chosen != null) {
            for (Player p : players) if (chosen.equals(p.getUUID())) return p;
            return null;   // not loaded right now
        }
        List<SelfPick.Candidate> cands = new ArrayList<>();
        for (Player p : players) {
            int filled = 0;
            try {
                for (int i = 0; i < 36; i++) if (!p.getInventory().getItem(i).isEmpty()) filled++;
            } catch (Throwable ignored) { }
            cands.add(new SelfPick.Candidate(p, p.getName().getString(), filled, p.distanceToSqr(mc.player)));
        }
        String account;
        try { account = mc.getUser().getName(); } catch (Throwable t) { account = null; }
        SelfPick.Candidate c = SelfPick.pick(cands, account);
        if (c == null) return null;   // nobody loaded yet: try again next frame
        Player me = (Player) c.key();
        chosen = me.getUUID();
        return me;
    }
}
