package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Music video mode: the lyric video (LyricEngine) drawn on a background thread about twelve times a
 * second and shown as a texture on planes in the world. Nothing else is needed: it's plain Java2D.
 * The picture is two layers side by side (decorations on the left half, the lyrics on the right).
 */
final class LyricsVideo {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("rainium", "lyrics/video");
    static final int W = LyricEngine.CW, H = LyricEngine.CH;   // one layer (the picture and room around it); the texture is twice as wide
    static final double SCALE_X = (double) LyricEngine.CW / LyricEngine.W, SCALE_Y = (double) LyricEngine.CH / LyricEngine.H;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rainium-lyrics-video");
        t.setDaemon(true);
        return t;
    });

    private static volatile LyricEngine engine;
    private static String songKey;
    private static volatile int[] frame;                  // a finished picture waiting to be uploaded
    private static long requested = Long.MIN_VALUE;
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static double anchorT;
    private static long anchorNanos;
    private static boolean playing;
    private static DynamicTexture texture;
    private static boolean has;
    private static BufferedImage back, front;             // used only by the drawing thread

    private LyricsVideo() {}

    /** Music video mode needs nothing installed. */
    static boolean ready() { return true; }

    /** A new song (or a new look): the layout of every line is worked out on the drawing thread. */
    static void song(String key, LyricsLrc lrc, String style, long seed, boolean motifs, boolean extra, String title, LyricEngine.Look look) {
        if (key.equals(songKey)) return;
        songKey = key;
        has = false;
        frame = null;
        requested = Long.MIN_VALUE;
        engine = null;
        final String k = key;
        POOL.execute(() -> {
            LyricEngine e = new LyricEngine(style, seed, motifs, extra, title, look);
            e.song(lrc);
            if (k.equals(songKey)) engine = e;
        });
    }

    static void clear() {
        songKey = null;
        engine = null;
        frame = null;
        has = false;
    }

    /** The song position; the picture keeps time from it between calls. */
    static void time(double t, boolean isPlaying) {
        anchorT = t;
        anchorNanos = System.nanoTime();
        playing = isPlaying;
    }

    private static double now() { return anchorT + (playing ? (System.nanoTime() - anchorNanos) / 1e9 : 0); }

    /** Called every frame from the render thread: asks for the next picture and uploads a finished one. False when there's nothing to show yet. */
    static boolean prepare() {
        LyricEngine e = engine;
        if (e == null || songKey == null) return false;
        double t = now();
        long step = (long) Math.floor(t / e.stepSec + 1e-6);
        if (step != requested && busy.compareAndSet(false, true)) {
            requested = step;
            POOL.execute(() -> {
                try { frame = render(e, t); }
                catch (Throwable ex) { Assets.LOG.warn("[rainium] lyrics video: " + ex); }
                finally { busy.set(false); }
            });
        }
        int[] px = frame;
        if (px != null) {
            frame = null;
            upload(px);
        }
        return has;
    }

    /** On the drawing thread: the two layers, packed side by side as ABGR pixels. */
    private static int[] render(LyricEngine e, double t) {
        if (back == null) {
            back = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            front = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        }
        e.draw(t, back, front);
        int[] out = new int[W * 2 * H];
        int[] row = new int[W];
        for (int layer = 0; layer < 2; layer++) {
            BufferedImage img = layer == 0 ? back : front;
            for (int y = 0; y < H; y++) {
                img.getRGB(0, y, W, 1, row, 0, W);
                int o = y * W * 2 + layer * W;
                for (int x = 0; x < W; x++) {
                    int p = row[x];   // ARGB → ABGR
                    out[o + x] = (p & 0xFF00FF00) | ((p & 0xFF) << 16) | ((p >> 16) & 0xFF);
                }
            }
        }
        return out;
    }

    private static void upload(int[] px) {
        if (texture == null) {
            texture = new DynamicTexture(() -> "rainium lyrics video", new NativeImage(W * 2, H, false));
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        }
        NativeImage ni = texture.getPixels();
        if (ni == null) return;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W * 2; x++) ni.setPixelABGR(x, y, px[y * W * 2 + x]);
        texture.upload();
        has = true;
    }

    /** Lyrics switched off or the look changed to Simple. */
    static void close() { clear(); }
}
