package io.github.raincandyfx.rainium;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Pictures and recordings of the editor preview, saved as transparent PNGs.
 *
 * The game side ({@code CaptureRenderer}) asks {@link #beginFrame} whether the frame it is drawing
 * should be kept, reads it back from the graphics card and hands the pixels to {@link #frame}.
 * A recording runs on its own clock: frame k of the clip shows the game exactly k / fps seconds
 * after the start. While recording, the game's own timer follows this clock ({@link #gameStepMs},
 * called from the game loop): each frame either moves game time on by exactly one step and is
 * taken, or doesn't move it at all while saving catches up. Everything that animates with game
 * time (the player model, emotes from other mods, sway, particles) therefore advances by the same
 * even step between saved frames, so the clip plays smoothly at true speed whatever the client's
 * frame rate. Recording has no link to real time: every frame the game draws is the next frame of
 * the clip (the game runs faster or slower than normal while recording), and the only wait is for
 * saving to catch up. PNGs are written on background threads.
 *
 * No Minecraft classes here, so the timing and file writing can be tested on their own.
 */
final class Recorder {
    private Recorder() {}

    enum Mode { IDLE, PICTURE, RECORDING }

    /** How the editor draws the preview while capturing (set by the game side at start-up; null in tests). */
    interface Submit {
        void submit(Object graphics, Object entityState, float scale, Object translation, Object rotation, Object camera,
                    int x0, int y0, int x1, int y1, int sizeFactor);
    }
    static volatile Submit submit;

    /** Largest picture side in pixels: bigger sizes are scaled down to fit. */
    static final int MAX_SIDE = 4096;

    private static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    /** Memory frames waiting to be saved may use (each frame is held as bytes and as converted pixels). */
    private static final long QUEUE_BYTES = Math.min(Runtime.getRuntime().maxMemory() / 4, 1536L << 20);
    private static final ExecutorService pool = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "rainium-capture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static volatile Mode mode = Mode.IDLE;
    private static Path target;                 // picture file, or recording folder
    private static int fps = 30, sizeFactor = 2;
    private static long startNanos;
    private static int nextFrame;               // index of the next frame to take
    private static volatile int clockFrame;     // the frame the recording clock shows
    private static int waits;                   // frames the game drew while saving caught up
    private static int pendingFrame = -1;       // frame the game clock was moved to, waiting to be drawn and taken
    private static volatile boolean driven;     // the game's timer follows the recording clock
    private static volatile long frameBytes = 1;   // memory one queued frame takes
    private static final AtomicInteger queued = new AtomicInteger(), written = new AtomicInteger(), failed = new AtomicInteger();
    private static volatile String error;
    private static volatile Consumer<String> done;   // called (on a background thread) with a message when saving finishes

    static Mode mode() { return mode; }
    static boolean capturing() { return mode != Mode.IDLE; }
    static int sizeFactor() { return sizeFactor; }
    static int framesWritten() { return written.get(); }
    /** Game frames spent waiting for saving to catch up (the recording is then slower than real time). */
    static int framesWaited() { return waits; }
    static int pending() { return queued.get(); }
    static Path target() { return target; }
    /** Whether the game's timer follows the recording clock (false if that hook isn't available). */
    static boolean driven() { return driven && mode == Mode.RECORDING; }

    private static int maxQueued() {
        return (int) Math.max(2, Math.min(THREADS * 3L + 6, QUEUE_BYTES / Math.max(1, frameBytes)));
    }

    /** Length of the clip recorded so far, in seconds (0 when not recording). */
    static float seconds() { return mode == Mode.RECORDING ? clockFrame / (float) fps : 0f; }

    /** The time the editor preview animates to: the recording's clock while recording, else the real time. */
    static long previewNanos() {
        return mode == Mode.RECORDING ? startNanos + Math.round(clockFrame * 1e9 / fps) : System.nanoTime();
    }

    /** Name for a new picture or recording, like rainium_2026-10-23_21-05-09 (no characters Windows rejects). */
    static String stamp(LocalDateTime t) { return "rainium_" + t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")); }

    private static Path unique(Path dir, String base, String ext) {
        Path p = dir.resolve(base + ext);
        for (int i = 2; Files.exists(p); i++) p = dir.resolve(base + "_" + i + ext);
        return p;
    }

    /** Takes one picture of the next preview frame into dir. */
    static synchronized Path picture(Path dir, int size, Consumer<String> onDone) throws IOException {
        if (capturing()) throw new IllegalStateException("already capturing");
        Files.createDirectories(dir);
        reset(size, 30, onDone);
        target = unique(dir, stamp(LocalDateTime.now()), ".png");
        mode = Mode.PICTURE;
        return target;
    }

    /** Starts recording numbered PNG frames into a new folder inside dir, named with the time and frame rate. */
    static synchronized Path start(Path dir, int size, int framesPerSecond, long nowNanos, Consumer<String> onDone) throws IOException {
        if (capturing()) throw new IllegalStateException("already capturing");
        reset(size, framesPerSecond, onDone);
        target = unique(dir, stamp(LocalDateTime.now()) + "_" + fps + "fps", "");   // the frame rate to import the frames at
        Files.createDirectories(target);
        startNanos = nowNanos;
        mode = Mode.RECORDING;
        return target;
    }

    private static void reset(int size, int framesPerSecond, Consumer<String> onDone) {
        sizeFactor = Math.max(1, size);
        fps = Math.max(1, framesPerSecond);
        nextFrame = 0;
        clockFrame = 0;
        waits = 0;
        pendingFrame = -1;
        driven = false;
        written.set(0);
        failed.set(0);
        error = null;
        done = onDone;
    }

    /** Stops a recording; frames still being saved finish in the background. */
    static synchronized void stop() {
        if (mode != Mode.RECORDING) return;
        mode = Mode.IDLE;
        pendingFrame = -1;
        finishIfDone();
    }

    /** Gives up on a picture that never got a frame (the preview wasn't drawn). */
    static synchronized void cancel() {
        if (mode == Mode.PICTURE) mode = Mode.IDLE;
    }

    /**
     * Called while drawing a preview frame: returns the frame's ticket (to pass to {@link #frame}),
     * or -1 if this frame isn't needed.
     */
    static synchronized long beginFrame(long nowNanos) {
        if (mode == Mode.PICTURE) {
            mode = Mode.IDLE;   // one frame only; the picture is still on its way
            queued.incrementAndGet();
            return ticket(0, 0);
        }
        if (mode != Mode.RECORDING) return -1;
        if (driven) {   // the game loop already decided at the start of this frame
            if (pendingFrame < 0) return -1;
            int k = pendingFrame;
            pendingFrame = -1;
            queued.incrementAndGet();
            return ticket(k, k);
        }
        int k = nextFrame;
        if (queued.get() >= maxQueued()) {                                   // saving has fallen behind: wait
            waits++;
            return -1;
        }
        nextFrame = k + 1;
        clockFrame = k;   // the preview drawn for this frame is at k / fps seconds
        queued.incrementAndGet();
        return ticket(k, k);
    }

    /**
     * Called by the game's timer at the start of every frame. Returns how many milliseconds of game
     * time this frame should advance: one recording step if this frame will be taken, 0 while
     * waiting (saving behind, or the last step not yet drawn), or NaN when not
     * recording (the game then uses real time).
     */
    static synchronized double gameStepMs(long nowNanos) {
        if (mode != Mode.RECORDING) return Double.NaN;
        driven = true;
        if (pendingFrame >= 0) return 0;                                     // last step not drawn yet: hold
        int k = nextFrame;
        if (queued.get() >= maxQueued()) { waits++; return 0; }             // saving has fallen behind
        nextFrame = k + 1;
        clockFrame = k;
        pendingFrame = k;
        return k == 0 ? 0 : 1000.0 / fps;
    }

    private static long ticket(int from, int to) { return ((long) from << 32) | (to & 0xFFFFFFFFL); }

    /**
     * The pixels of a requested frame: width × height RGBA bytes, bottom row first, with colours
     * premultiplied by alpha (how the game draws the preview). Ticket -1 is ignored.
     */
    static void frame(long ticket, int width, int height, byte[] rgba) {
        if (ticket < 0) return;
        final Path into = target;
        final boolean picture = !Files.isDirectory(into);
        final int from = (int) (ticket >>> 32), to = (int) ticket;
        frameBytes = (long) width * height * 8;
        pool.execute(() -> {
            try {
                int[] argb = straightArgb(width, height, rgba);
                if (picture) {
                    writePng(into, width, height, argb);
                    written.incrementAndGet();
                } else {
                    Path first = into.resolve(frameName(from));
                    writePng(first, width, height, argb);
                    written.incrementAndGet();
                    for (int i = from + 1; i <= to; i++) {   // repeat for slots no frame was drawn in
                        Files.copy(first, into.resolve(frameName(i)), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        written.incrementAndGet();
                    }
                }
            } catch (Throwable e) {
                failed.incrementAndGet();
                error = String.valueOf(e.getMessage());
            } finally {
                queued.decrementAndGet();
                synchronized (Recorder.class) { finishIfDone(); }
            }
        });
    }

    /** A requested frame that couldn't be read back. */
    static synchronized void abandon(long ticket, String why) {
        if (ticket < 0) return;
        error = why;
        failed.incrementAndGet();
        queued.decrementAndGet();
        finishIfDone();
    }

    /** Called with the lock held: once nothing is left to save, report the result. */
    private static void finishIfDone() {
        if (mode != Mode.IDLE || queued.get() > 0 || done == null) return;
        Consumer<String> d = done;
        done = null;
        d.accept(error != null ? "!" + error : target.getFileName().toString());
    }

    static String frameName(int i) { return String.format("frame_%05d.png", i + 1); }

    // ------------------------------------------------------------------ pixels

    /**
     * Turns premultiplied RGBA bytes (bottom row first) into straight ARGB ints (top row first).
     * Additive glow on an empty background leaves colour with little or no alpha; alpha is raised to
     * the brightest channel so the glow stays visible in the saved picture.
     */
    static int[] straightArgb(int w, int h, byte[] rgba) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4, dst = y * w;
            for (int x = 0; x < w; x++, src += 4) {
                int r = rgba[src] & 0xFF, g = rgba[src + 1] & 0xFF, b = rgba[src + 2] & 0xFF, a = rgba[src + 3] & 0xFF;
                a = Math.max(a, Math.max(r, Math.max(g, b)));
                if (a == 0) continue;
                if (a < 255) {
                    r = Math.min(255, (r * 255 + a / 2) / a);
                    g = Math.min(255, (g * 255 + a / 2) / a);
                    b = Math.min(255, (b * 255 + a / 2) / a);
                }
                out[dst + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ PNG

    /** Writes a 32-bit RGBA PNG. Fast compression: recordings write many frames per second. */
    static void writePng(Path file, int w, int h, int[] argb) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16)) {
            out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            int32(ihdr, w); int32(ihdr, h);
            ihdr.write(8); ihdr.write(6); ihdr.write(0); ihdr.write(0); ihdr.write(0);   // 8-bit RGBA, no interlace
            chunk(out, "IHDR", ihdr.toByteArray(), ihdr.size());

            Deflater def = new Deflater(Deflater.BEST_SPEED);
            ByteArrayOutputStream idat = new ByteArrayOutputStream(w * h);
            byte[] row = new byte[1 + w * 4], buf = new byte[1 << 16];
            for (int y = 0; y < h; y++) {
                row[0] = 1;   // "Sub" filter: each byte minus the one to its left; compresses far better than none
                int prevR = 0, prevG = 0, prevB = 0, prevA = 0;
                for (int x = 0, i = y * w, o = 1; x < w; x++, i++, o += 4) {
                    int p = argb[i], r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF, a = p >>> 24;
                    row[o] = (byte) (r - prevR); row[o + 1] = (byte) (g - prevG); row[o + 2] = (byte) (b - prevB); row[o + 3] = (byte) (a - prevA);
                    prevR = r; prevG = g; prevB = b; prevA = a;
                }
                def.setInput(row);
                while (!def.needsInput()) { int n = def.deflate(buf); idat.write(buf, 0, n); }
            }
            def.finish();
            while (!def.finished()) { int n = def.deflate(buf); idat.write(buf, 0, n); }
            def.end();
            chunk(out, "IDAT", idat.toByteArray(), idat.size());
            chunk(out, "IEND", new byte[0], 0);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void int32(OutputStream o, int v) throws IOException {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }

    private static void chunk(OutputStream out, String type, byte[] data, int len) throws IOException {
        int32(out, len);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(t);
        out.write(data, 0, len);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data, 0, len);
        int32(out, (int) crc.getValue());
    }
}
