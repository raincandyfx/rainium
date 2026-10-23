package io.github.raincandyfx.rainium;

/** Hue rotation of colours, keeping saturation, brightness and alpha. Pure Java, no game classes. */
final class Hue {
    private Hue() {}

    /** Wraps an angle into -180..180 degrees. */
    static float normalize(float deg) {
        float d = ((deg % 360f) + 360f) % 360f;
        return d > 180f ? d - 360f : d;
    }

    /** Rotates the hue of an ARGB colour by deg degrees. Greys (white, black) stay the same. */
    static int rotateArgb(int argb, float deg) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int rgb = rotateRgb(r, g, b, deg);
        return (a << 24) | rgb;
    }

    /** Same for the ABGR pixel layout used by NativeImage. */
    static int rotateAbgr(int abgr, float deg) {
        int a = abgr >>> 24, b = (abgr >> 16) & 0xFF, g = (abgr >> 8) & 0xFF, r = abgr & 0xFF;
        int rgb = rotateRgb(r, g, b, deg);
        return (a << 24) | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    /** Returns 0xRRGGBB. */
    private static int rotateRgb(int r, int g, int b, float deg) {
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max == min) return (r << 16) | (g << 8) | b;   // grey: no hue to rotate
        float c = max - min;
        float h;
        if (max == r) h = ((g - b) / c) % 6f;
        else if (max == g) h = (b - r) / c + 2f;
        else h = (r - g) / c + 4f;
        h = h * 60f + deg;
        h = ((h % 360f) + 360f) % 360f / 60f;
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float r1, g1, b1;
        switch ((int) h) {
            case 0 -> { r1 = c; g1 = x; b1 = 0; }
            case 1 -> { r1 = x; g1 = c; b1 = 0; }
            case 2 -> { r1 = 0; g1 = c; b1 = x; }
            case 3 -> { r1 = 0; g1 = x; b1 = c; }
            case 4 -> { r1 = x; g1 = 0; b1 = c; }
            default -> { r1 = c; g1 = 0; b1 = x; }
        }
        int ro = Math.round(r1 + min), go = Math.round(g1 + min), bo = Math.round(b1 + min);
        return (clamp(ro) << 16) | (clamp(go) << 8) | clamp(bo);
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(255, v); }

    /**
     * If no pixel is meaningfully opaque (alpha &lt; 8) but some have colour, the image is an additive
     * glow mask: sets each pixel's alpha to its brightest channel, in place. Returns whether it did.
     * Works on ABGR or ARGB pixels alike.
     */
    static boolean alphaFromBrightness(int[] px) {
        boolean anyColour = false;
        for (int p : px) {
            if ((p >>> 24) >= 8) return false;
            if ((p & 0xFFFFFF) != 0) anyColour = true;
        }
        if (!anyColour) return false;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = Math.max((p >> 16) & 0xFF, Math.max((p >> 8) & 0xFF, p & 0xFF));
            px[i] = (a << 24) | (p & 0xFFFFFF);
        }
        return true;
    }
}
