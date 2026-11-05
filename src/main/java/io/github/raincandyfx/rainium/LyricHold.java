package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.GL;
import io.github.raincandyfx.rainium.LyricKit.State;

/** What a line does while it rests on screen: breathing, drifting, jittering, pulsing and so on (condensed re-creations). */
final class LyricHold {
    private LyricHold() {}

    private static double h(long step, int i, int salt) { double x = Math.sin(step * 12.9898 + i * 78.233 + salt * 37.719) * 43758.5453; return x - Math.floor(x); }

    static void apply(String key, State s, GL gl, double t, long step, double K, int word, double amt) {
        int i = gl.i;
        double A = amt;
        switch (key) {
            case "still" -> { }
            case "jitter" -> { s.dx += (h(step, i, 1) - 0.5) * 2.4 * K * A; s.dy += (h(step, i, 2) - 0.5) * 2.4 * K * A; }
            case "drift" -> { s.dx += Math.sin(t * 0.7 + i * 0.1) * 3 * K * A; s.dy += Math.cos(t * 0.5) * 2 * K * A; }
            case "breathe" -> { double k = 1 + 0.025 * A * Math.sin(t * 1.8); s.sx *= k; s.sy *= k; }
            case "wave" -> s.dy += Math.sin(t * 3 + i * 0.6) * 3.5 * K * A;
            case "glitchtick" -> { if (h(step / 5, 0, 3) < 0.14) { s.dx += (h(step, i, 4) - 0.5) * 20 * K * A; s.ghost += 6 * A; } }
            case "float" -> s.dy += Math.sin(t * 1.3) * 4.5 * K * A;
            case "sway" -> s.rot += Math.sin(t * 1.5) * 0.035 * A;
            case "pulse" -> { double k = 1 + 0.045 * A * Math.pow(Math.sin(t * 3), 2); s.sx *= k; s.sy *= k; }
            case "shimmer" -> s.alpha *= 1 - 0.18 * A * (0.5 + 0.5 * Math.sin(t * 7 + i));
            case "colorRun" -> { s.ghost += 3 * A * (0.5 + 0.5 * Math.sin(t * 4 - i * 0.5)); }
            case "rotateSlow" -> s.rot += Math.sin(t * 0.5) * 0.06 * A;
            case "trackBreathe" -> s.dx += ((i % 12) - 5.5) * Math.sin(t * 1.5) * 0.9 * K * A;
            case "skewWobble" -> { s.rot += Math.sin(t * 2.2 + i * 0.3) * 0.05 * A; s.sx *= 1 + 0.03 * Math.sin(t * 2.2) * A; }
            case "beatHop" -> s.dy -= Math.abs(Math.sin(t * 3.2)) * 5 * K * A;
            case "hWave" -> s.dx += Math.sin(t * 2 + i * 0.5) * 3.5 * K * A;
            case "heartbeat" -> { double ph = (t * 1.2) % 1, k = 1 + 0.07 * A * (Math.exp(-Math.pow((ph - 0.1) * 14, 2)) + 0.6 * Math.exp(-Math.pow((ph - 0.3) * 14, 2))); s.sx *= k; s.sy *= k; }
            case "orbitSmall" -> { s.dx += Math.cos(t * 1.5 + i * 0.4) * 2.5 * K * A; s.dy += Math.sin(t * 1.5 + i * 0.4) * 2.5 * K * A; }
            case "jelly" -> { double w = Math.sin(t * 6 + i * 0.2) * 0.05 * A; s.sx *= 1 + w; s.sy *= 1 - w; }
            case "scanBand" -> { double c = (t * 6) % 12, d = Math.abs((i % 12) - c); s.alpha *= 1 - 0.5 * A * Math.exp(-d * d * 0.5); }
            case "noiseDrift" -> { s.dx += (Math.sin(t * 1.3 + i * 2.1) + Math.sin(t * 2.9 + i)) * 1.6 * K * A; s.dy += (Math.sin(t * 1.7 + i * 1.3) + Math.sin(t * 3.1)) * 1.6 * K * A; }
            case "tilt" -> s.rot += 0.045 * A;
            case "zoomSlow" -> { double k = 1 + 0.012 * Math.min(t, 6) * A; s.sx *= k; s.sy *= k; }
            case "stretchPulse" -> { s.sx *= 1 + 0.07 * A * Math.sin(t * 3); }
            case "glitchJump" -> { if (h(step / 8, 0, 5) < 0.1) { s.dx += (h(step, i, 6) - 0.5) * 60 * K * A; s.dy += (h(step, i, 7) - 0.5) * 12 * K * A; } }
            case "echoTrail" -> s.ghost += (3 + 2 * Math.sin(t * 2)) * A;
            case "glowFlicker" -> s.ghost += (h(step, i, 8) * 5) * A;
            case "windGust" -> { double g = Math.sin(t * 0.9 + i * 0.15); s.dx += g * g * g * 7 * K * A; s.rot += g * g * g * 0.03 * A; }
            case "dangle" -> { s.rot += Math.sin(t * 2 + i * 0.3) * 0.05 * A; s.dy += Math.abs(Math.sin(t * 2 + i * 0.3)) * 2 * K * A; }
            case "eqBounce" -> s.sy *= 1 + 0.12 * A * Math.abs(Math.sin(t * 4 + i * 1.3));
            case "flashBox" -> { if ((int) (t * 3) % 6 == 0) s.alpha *= 1 - 0.35 * A; }
            case "glintSweep" -> { double c = ((t * 5) % 16) - 2, d = Math.abs((i % 12) - c); s.ghost += 9 * A * Math.exp(-d * d); }
            case "flipSwap" -> { double ph = (t * 0.45) % 1; if (ph < 0.12) s.sx *= Math.max(0.05, Math.abs(Math.cos(ph / 0.12 * Math.PI))); }
            case "shadowSway" -> s.ghost += (2 + 3 * Math.abs(Math.sin(t * 1.4))) * A;
            case "magnetJiggle" -> { s.dx += Math.sin(t * 11 + i * 3) * 1.4 * K * A; s.dy += Math.cos(t * 9 + i * 2) * 1.4 * K * A; }
            case "typeRattle" -> s.dy += (h(step, i, 9) - 0.5) * 3 * K * A;
            case "focusRack" -> s.ghost += (Math.sin(t * 1.2 + i * 0.2) * 0.5 + 0.5) * 7 * A;
            case "pluckString" -> s.dy += Math.sin(t * 22) * Math.exp(-((t * 0.7) % 1) * 4) * 5 * K * A;
            case "hrTwitch" -> { if (h(step / 4, i, 10) < 0.05) { s.dx += (h(step, i, 11) - 0.5) * 12 * K; s.rot += (h(step, i, 12) - 0.5) * 0.2; } }
            case "hrStare" -> { if (i % 5 == 2) { double k = 1.0 + 0.1 * Math.sin(t * 0.8) * A; s.sx *= k; s.sy *= k; } }
            case "hrLagOne" -> { if (i == 2 + (int) (t * 0.2) % 3) s.dx += Math.sin(t * 1.4) * 6 * K; }
            case "hrFlickerLight" -> s.alpha *= h(step, 0, 13) < 0.12 ? 0.15 : 1;
            case "knWordPulse" -> { double k = 1 + 0.05 * A * Math.pow(Math.sin(t * 3 + word * 1.2), 2); s.sx *= k; s.sy *= k; }
            case "knCounterRock" -> s.rot += Math.sin(t * 2.4) * 0.04 * A * ((word & 1) == 0 ? 1 : -1);
            case "knWordRide" -> s.dy += Math.sin(t * 2 + word * 1.1) * 4 * K * A;
            case "knTickShift" -> s.dx += (((int) (t * 3 + word)) % 3 - 1) * 2 * K * A;
            case "knBeatLean" -> s.rot += Math.abs(Math.sin(t * 3)) * 0.05 * A * ((word & 1) == 0 ? 1 : -1);
            case "knGapBreath" -> s.dx += (i % 12 - 5.5) * (0.5 + 0.5 * Math.sin(t * 1.4)) * 0.6 * K * A;
            case "tyKeyPulse" -> { if (i % 6 == 0) { double k = 1 + 0.06 * A * Math.sin(t * 3); s.sx *= k; s.sy *= k; } }
            case "tyReadCursor" -> { double c = (t * 4) % 14; s.alpha *= (i % 14) < c ? 1 : 0.55 + 0.45 * A * 0 + 0.0; }
            case "tyOutlineBlink" -> { if ((int) (t * 2) % 2 == 1) s.ghost += 5; }
            case "tyTrackStep" -> s.dx += ((i % 12) - 5.5) * (((int) (t * 1.5)) % 3) * 0.6 * K * A;
            default -> { }
        }
    }
}
