package io.github.raincandyfx.rainium;

/**
 * Keeps the lyrics out of the scenery: a spot behind a wall is pulled in front of it, and a spot
 * buried in the ground is lifted out. The block lookup is passed in, so the tests can run it.
 */
final class LyricsWorld {
    /** Whether the block containing a point is solid. */
    interface Solid { boolean at(double x, double y, double z); }

    private LyricsWorld() {}

    /** How far along eye→spot the first block is (0.2-block steps), or the whole distance when the way is clear. */
    static double clearDistance(Solid solid, double[] eye, double[] spot) {
        double dx = spot[0] - eye[0], dy = spot[1] - eye[1], dz = spot[2] - eye[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.01) return len;
        double sx = dx / len * 0.2, sy = dy / len * 0.2, sz = dz / len * 0.2;
        double x = eye[0], y = eye[1], z = eye[2], travelled = 0;
        while (travelled < len) {
            x += sx; y += sy; z += sz; travelled += 0.2;
            if (solid.at(x, y, z)) return Math.max(1.0, travelled - 0.4);
        }
        return len;
    }

    /** The spot pulled in front of the first wall between the eye and it (never closer than a block), as {x, y, z}. */
    static double[] inFront(Solid solid, double[] eye, double[] spot) {
        double len = Math.sqrt(sq(spot[0] - eye[0]) + sq(spot[1] - eye[1]) + sq(spot[2] - eye[2]));
        double d = clearDistance(solid, eye, spot);
        if (len < 0.01 || d >= len) return spot;
        double k = d / len;
        return new double[]{eye[0] + (spot[0] - eye[0]) * k, eye[1] + (spot[1] - eye[1]) * k, eye[2] + (spot[2] - eye[2]) * k};
    }

    /** The spot lifted up to three blocks out of the ground it is buried in. */
    static double[] aboveFloor(Solid solid, double[] spot) {
        double[] out = spot;
        for (int i = 0; i < 3; i++) {
            if (!solid.at(out[0], out[1], out[2])) return out;
            out = new double[]{out[0], out[1] + 1, out[2]};
        }
        return out;
    }

    /** How much of its distance a spot kept after being pulled in, for shrinking the lyrics to the size they would have had (at most 1). */
    static double keptScale(double[] eye, double[] wanted, double[] placed) {
        double a = Math.sqrt(sq(wanted[0] - eye[0]) + sq(wanted[1] - eye[1]) + sq(wanted[2] - eye[2]));
        double b = Math.sqrt(sq(placed[0] - eye[0]) + sq(placed[1] - eye[1]) + sq(placed[2] - eye[2]));
        return a < 0.01 ? 1 : Math.min(1.0, b / a * 1.1);
    }

    private static double sq(double v) { return v * v; }
}
