package io.github.raincandyfx.rainium;

import java.util.List;

/**
 * Which player is "me" while watching a replay (Flashback's camera is a separate player named
 * "Replay Viewer"). In order: the player with your account name; else the only player whose
 * inventory is known (a replay keeps the recorded player's whole hotbar, but only what others
 * hold in their hands); else the player nearest the camera, since a replay starts at the
 * recorded player. No game classes here, so it can be tested on its own.
 */
final class SelfPick {
    private SelfPick() {}

    /** The camera's own player name in Flashback replays (older notes called it "Replay Player"). */
    static final String REPLAY_CAMERA = "Replay Viewer";

    static boolean isReplayCamera(String name) { return REPLAY_CAMERA.equals(name) || "Replay Player".equals(name); }

    /** A player in the replay: name, how many inventory slots hold something, distance² to the camera. */
    record Candidate(Object key, String name, int filledSlots, double distanceSq) {}

    /** The candidate that is "me", or null if there are none. */
    static Candidate pick(List<Candidate> players, String accountName) {
        if (players.isEmpty()) return null;
        if (accountName != null) {
            for (Candidate c : players) if (accountName.equalsIgnoreCase(c.name())) return c;
        }
        Candidate withInventory = null;
        int count = 0;
        for (Candidate c : players) {
            if (c.filledSlots() >= 2) { withInventory = c; count++; }   // others show at most the item in their hand
        }
        if (count == 1) return withInventory;
        Candidate nearest = null;
        for (Candidate c : players) if (nearest == null || c.distanceSq() < nearest.distanceSq()) nearest = c;
        return nearest;
    }
}
