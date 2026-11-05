package io.github.raincandyfx.rainium;

import io.github.raincandyfx.rainium.LyricKit.E;

import java.awt.geom.AffineTransform;

import static io.github.raincandyfx.rainium.LyricKit.clamp;

/** Camera moves over the whole picture while a line is up: pushes, pans, rolls, shakes and snaps (condensed re-creations). */
final class LyricCam {
    private LyricCam() {}

    private static double h(long step, int salt) { double x = Math.sin(step * 12.9898 + salt * 78.233) * 43758.5453; return x - Math.floor(x); }

    /** The move at lt seconds into a cut of dur seconds, as a transform about the centre of a w x h picture. */
    static AffineTransform at(String key, double lt, double dur, long step, double w, double h) {
        double u = clamp(lt / Math.max(0.2, dur)), tx = 0, ty = 0, sc = 1, rot = 0, shx = 0, sx = 1;
        double d = Math.min(w, h);
        switch (key) {
            case "push", "dollyIn" -> sc = 1 + 0.07 * u;
            case "orbitDrift" -> { tx = Math.sin(u * 3) * 0.02 * w; ty = Math.cos(u * 3) * 0.02 * h; rot = Math.sin(u * 3) * 0.02; }
            case "barrelRoll" -> rot = E.inOutCubic(u) * Math.PI * 2;
            case "pendulumSway" -> rot = Math.sin(lt * 2) * 0.045;
            case "focusIn" -> sc = 1 + 0.2 * (1 - E.outCubic(clamp(lt / 0.6)));
            case "rackFocus" -> sc = 1 + 0.02 * Math.sin(lt * 2);
            case "earthquake" -> { double k = Math.exp(-lt * 0.8); tx = (h(step, 1) - 0.5) * 0.03 * w * k; ty = (h(step, 2) - 0.5) * 0.03 * h * k; rot = (h(step, 3) - 0.5) * 0.02 * k; }
            case "floatNoise" -> { tx = (Math.sin(lt * 0.9) + Math.sin(lt * 1.7)) * 0.01 * w; ty = (Math.sin(lt * 1.1) + Math.cos(lt * 1.9)) * 0.01 * h; rot = Math.sin(lt * 0.6) * 0.01; }
            case "vertigo" -> { sc = 1 + 0.25 * E.inOutCubic(u); shx = -0.06 * u; }
            case "tiltDown" -> ty = -0.05 * h * (1 - E.outCubic(u));
            case "tiltUp" -> ty = 0.05 * h * (1 - E.outCubic(u));
            case "spiralIn" -> { double k = 1 - E.outCubic(clamp(lt / 0.8)); rot = 0.25 * k; sc = 1 + 0.35 * k; }
            case "snapPan" -> tx = -0.3 * w * (1 - E.outExpo(clamp(lt / 0.3)));
            case "jelly" -> { double k = Math.exp(-lt * 3) * Math.sin(lt * 14); sc = 1 + 0.04 * k; sx = 1 - 0.04 * k; }
            case "hrNervous" -> { tx = (h(step, 4) - 0.5) * 0.012 * w; ty = (h(step, 5) - 0.5) * 0.012 * h; rot = (h(step, 6) - 0.5) * 0.008; }
            case "hrDutchSnap" -> rot = lt > dur * 0.5 ? 0.09 : 0;
            case "knReadPan" -> tx = -(u - 0.5) * 0.08 * w;
            case "knTiltKick" -> rot = 0.12 * Math.exp(-lt * 6) * Math.cos(lt * 12);
            case "knCardFlip" -> sx = Math.max(0.04, Math.abs(Math.cos(E.outCubic(clamp(lt / 0.5)) * Math.PI)));
            case "knShearKick" -> shx = 0.25 * Math.exp(-lt * 7) * Math.cos(lt * 14);
            case "knJumpCut" -> sc = lt > dur * 0.5 ? 1.12 : 1;
            case "knRushIn" -> sc = 0.82 + 0.18 * E.outExpo(clamp(lt / 0.4));
            case "pullOut" -> sc = 1.15 - 0.15 * E.outCubic(u);
            case "panL" -> tx = -0.05 * w * u;
            case "panR" -> tx = 0.05 * w * u;
            case "dutch" -> rot = 0.07;
            case "handheld" -> { tx = (Math.sin(lt * 2.3) + Math.sin(lt * 5.1)) * 0.004 * w; ty = (Math.sin(lt * 1.9) + Math.cos(lt * 4.3)) * 0.004 * h; rot = Math.sin(lt * 1.3) * 0.004; }
            case "beatPunch" -> sc = 1 + 0.05 * Math.exp(-((lt * 2) % 1) * 7);
            case "whipIn" -> { tx = -0.7 * w * (1 - E.outExpo(clamp(lt / 0.25))); }
            case "crashZoom" -> sc = 1 + 0.5 * (1 - E.outExpo(clamp(lt / 0.3)));
            case "bounce" -> ty = -Math.abs(Math.sin(lt * 4)) * 0.012 * h * Math.exp(-lt * 0.6);
            case "roll" -> rot = Math.sin(u * Math.PI) * 0.06;
            case "driftDiag" -> { tx = -0.03 * w * u; ty = -0.03 * h * u; sc = 1.05; }
            case "shakeHard" -> { tx = (h(step, 7) - 0.5) * 0.04 * w; ty = (h(step, 8) - 0.5) * 0.04 * h; rot = (h(step, 9) - 0.5) * 0.03; }
            case "stepZoom" -> sc = 1 + 0.04 * Math.floor(lt * 2);
            default -> { }
        }
        AffineTransform t = new AffineTransform();
        t.translate(w / 2 + tx, h / 2 + ty);
        if (rot != 0) t.rotate(rot);
        if (shx != 0) t.shear(shx, 0);
        t.scale(sc * sx, sc);
        t.translate(-w / 2, -h / 2);
        return t;
    }
}
