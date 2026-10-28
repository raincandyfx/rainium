package io.github.raincandyfx.rainium;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Who "me" is for Show on: Just me. Normally the local player. While watching a Flashback replay the
 * local player is the camera, so "me" is the recorded player instead (see {@link SelfPick}); the choice
 * is kept for the rest of the replay.
 */
final class Self {
    private Self() {}

    private static Object level;   // the world the choice was made in
    private static UUID chosen;

    /** Whether the local player is a replay camera. */
    static boolean inReplay() {
        Minecraft mc = Minecraft.getInstance();
        try {
            return mc.player != null && SelfPick.REPLAY_CAMERA.equals(mc.player.getName().getString());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether the entity with this id is "me". */
    static boolean is(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;   // no player (e.g. menus): treat as me
        if (!inReplay()) return mc.player.getId() == entityId;
        Player me = replayMe();
        return me != null && me.getId() == entityId;
    }

    /** The player the editor previews: the recorded player in a replay, else the local player. */
    static Player previewPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (!inReplay()) return mc.player;
        Player me = replayMe();
        return me != null ? me : mc.player;
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
