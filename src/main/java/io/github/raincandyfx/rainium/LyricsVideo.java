package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
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
    private static volatile LyricDelta frame;                // a finished picture waiting to be uploaded
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
        resetBaseline = true;
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
        resetBaseline = true;
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

    /** Whether a picture has been uploaded yet (until then the simple line is shown). */
    static boolean has() { return has; }

    private static volatile boolean resetBaseline = true;
    private static int[] last;                            // the picture as last handed over (ABGR), drawing thread only

    /** Called from the render thread while the picture can be seen: asks for the next picture and uploads a finished one. False when there's nothing to show yet. */
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
        LyricDelta f = frame;
        if (f != null) {
            frame = null;
            upload(f);
        }
        return has;
    }

    /** On the drawing thread: the two layers, packed side by side as ABGR pixels; only what differs from the last picture is passed on. */
    private static LyricDelta render(LyricEngine e, double t) {
        if (back == null) {
            back = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            front = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        }
        e.draw(t, back, front);
        boolean full = resetBaseline || last == null || frame != null;   // a picture that was never uploaded is lost: send everything
        resetBaseline = false;
        if (last == null) last = new int[W * 2 * H];
        return LyricDelta.diff(((DataBufferInt) back.getRaster().getDataBuffer()).getData(), ((DataBufferInt) front.getRaster().getDataBuffer()).getData(), W, H, last, full);
    }

    private static void upload(LyricDelta f) {
        boolean fresh = texture == null;
        if (fresh) {
            texture = new DynamicTexture(() -> "rainium lyrics video", new NativeImage(W * 2, H, false));
            Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        }
        NativeImage ni = texture.getPixels();
        if (ni == null) return;
        if (f.rows == 0 && !fresh) return;   // nothing moved: no upload at all
        int n = 0;
        for (int y = 0; y < H; y++) {
            if (f.x0[y] < 0) continue;
            for (int x = f.x0[y]; x < f.x1[y]; x++) ni.setPixelABGR(x, y, f.data[n++]);
        }
        texture.upload();
        has = true;
    }

    /** Lyrics switched off or the look changed to Simple. */
    static void close() { clear(); }
}
