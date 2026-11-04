package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;

/**
 * Simple mode (and the fallback while Music video mode isn't available): the line being sung, with
 * each word filling in as it's sung, and the next line small under it. Drawn into a texture only
 * when something changes.
 */
final class LyricsText {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("rainium", "lyrics/text");
    static final int W = 1024, H = 256;

    private static String drawn = "";
    private static boolean has;

    private LyricsText() {}

    static boolean ready() { return has; }

    /** Redraws when the line or the sung words change. */
    static void update(LyricsLrc lrc, double t) {
        int li = lrc == null ? -1 : lrc.lineAt(t);
        LyricsLrc.Line line = li < 0 ? null : lrc.lines.get(li);
        if (line != null && t > line.end() + 0.4) line = null;
        int sung = 0;
        if (line != null) for (LyricsLrc.Word w : line.words()) if (t >= w.t()) sung++;
        LyricsLrc.Line next = li + 1 < (lrc == null ? 0 : lrc.lines.size()) ? lrc.lines.get(li + 1) : null;
        String key = (line == null ? "-" : li + ":" + sung) + "|" + (next == null ? "" : next.text());
        if (key.equals(drawn)) return;
        drawn = key;
        if (line == null && next == null) { has = false; return; }
        BufferedImage img = LyricsArt.draw(line, sung, next, W, H);
        NativeImage ni = new NativeImage(W, H, false);
        int[] px = img.getRGB(0, 0, W, H, null, 0, W);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = px[y * W + x];   // ARGB → ABGR
                ni.setPixelABGR(x, y, (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF));
            }
        Minecraft.getInstance().getTextureManager().register(TEXTURE, new DynamicTexture(() -> "rainium lyrics", ni));
        has = true;
    }

    static void reset() { drawn = ""; has = false; }
}
