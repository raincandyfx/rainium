package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Music video mode: JIZURA (MIT, bundled in assets/rainium/jizura) runs in an off-screen Chromium
 * page through MCEF Modern, an optional mod. The page draws JIZURA's two transparent layers side by
 * side; Rainium shows that picture as a texture on two planes in the world.
 *
 * MCEF is only reached by reflection, so Rainium loads and works without it (Simple mode).
 */
final class LyricsVideo {
    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("rainium", "lyrics/video");
    static final int W = 800, H = 450;    // one layer; the page is twice as wide

    enum State { MISSING, STARTING, READY, FAILED }

    private static State state = State.STARTING;
    private static String failure = "";
    private static Object init, api, browser;
    private static CompletableFuture<?> future;
    private static Method mStage, mPercent, mCreate, mResize, mTexture, mView, mClose, mCef, mExec;
    private static VideoTexture texture;
    private static String songKey;
    private static long lastTimeNanos;
    private static double lastSent = Double.NaN;
    private static boolean lastPlaying;

    private LyricsVideo() {}

    static boolean installed() {
        try { return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mcef-modern"); }
        catch (Throwable t) { return false; }
    }

    static State state() { return installed() ? state : State.MISSING; }

    /** What to show in Settings: "ready", "downloading 42%", "starting", "missing", "failed: …". */
    static String detail() {
        if (!installed()) return "missing";
        if (state == State.FAILED) return "failed: " + failure;
        if (state == State.READY) return browser != null ? "ready" : "starting";
        try {
            if (init != null && mStage != null) {
                String st = String.valueOf(mStage.invoke(init));
                float pct = mPercent == null ? -1 : ((Number) mPercent.invoke(init)).floatValue();
                if (st.equals("DOWNLOADING")) return "downloading" + (pct >= 0 ? " " + Math.round(pct) + "%" : "");
            }
        } catch (Throwable ignored) { }
        return "starting";
    }

    /** Called every tick while Music video mode is on: starts MCEF and the page when needed. */
    static void tick() {
        if (!installed() || state == State.FAILED) return;
        try {
            if (init == null) {
                Class<?> apiCls = Class.forName("net.dimaskama.mcef.api.MCEFApi");
                Class<?> initCls = Class.forName("net.dimaskama.mcef.api.MCEFApi$Initialization");
                init = apiCls.getMethod("initialize").invoke(null);
                mStage = initCls.getMethod("getStage");
                mPercent = initCls.getMethod("getPercentage");
                future = (CompletableFuture<?>) initCls.getMethod("getFuture").invoke(init);
                mCreate = apiCls.getMethod("createBrowser", String.class, boolean.class);
            }
            if (api == null) {
                if (!future.isDone()) return;
                api = future.join();
            }
            if (browser == null) {
                Path page = unpack();
                browser = mCreate.invoke(api, page.toUri().toString(), true);
                Class<?> bc = Class.forName("net.dimaskama.mcef.api.MCEFBrowser");
                mResize = bc.getMethod("resize", int.class, int.class);
                mTexture = bc.getMethod("getTexture");
                mView = bc.getMethod("getTextureView");
                mClose = bc.getMethod("close");
                mCef = bc.getMethod("getCefBrowser");
                mExec = Class.forName("org.cef.browser.CefBrowser").getMethod("executeJavaScript", String.class, String.class, int.class);
                mResize.invoke(browser, W * 2, H);
                texture = new VideoTexture();
                Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
                songKey = null;
                state = State.READY;
            }
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            failure = String.valueOf(c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
            state = State.FAILED;
            Assets.LOG.warn("[rainium] music video mode is unavailable: " + c);
        }
    }

    static boolean ready() { return installed() && state == State.READY && browser != null; }

    /** The page files, copied out of the jar next to the other Rainium files (replaced when they change). */
    private static Path unpack() throws Exception {
        Path dir = RainiumClient.ASSETS.dir().resolve("jizura");
        Files.createDirectories(dir);
        for (String f : new String[]{"page.html", "engine.js", "LICENSE.txt"}) {
            try (InputStream in = LyricsVideo.class.getResourceAsStream("/assets/rainium/jizura/" + f)) {
                if (in == null) throw new IllegalStateException("missing " + f + " in the Rainium jar");
                byte[] b = in.readAllBytes();
                Path p = dir.resolve(f);
                if (!Files.isRegularFile(p) || Files.size(p) != b.length) Files.write(p, b);
            }
        }
        return dir.resolve("page.html");
    }

    private static void js(String code) {
        if (browser == null) return;
        try {
            Object cef = mCef.invoke(browser);
            // the page may still be loading: try again until its functions are there
            mExec.invoke(cef, "(function go(){ if (window.rainium) { " + code + " } else setTimeout(go, 100); })();", "", 0);
        } catch (Throwable t) {
            Assets.LOG.warn("[rainium] lyrics video page: " + t);
        }
    }

    /** A new song (or a new look) for the page. */
    static void song(String key, String lrc, String projectJson) {
        if (!ready()) return;
        if (key.equals(songKey)) return;
        songKey = key;
        lastSent = Double.NaN;
        js("rainium.song(" + JsonWriter.write(lrc) + ", " + JsonWriter.write(projectJson) + ", " + JsonWriter.write(key) + ");");
    }

    static void clear() {
        if (songKey == null) return;
        songKey = null;
        js("rainium.clear();");
    }

    /** The song position, sent about four times a second (and at once when it jumps or pauses). */
    static void time(double t, boolean playing) {
        if (!ready() || songKey == null) return;
        long now = System.nanoTime();
        double expected = Double.isNaN(lastSent) ? Double.NaN : lastSent + (lastPlaying ? (now - lastTimeNanos) / 1e9 : 0);
        boolean jumped = Double.isNaN(expected) || Math.abs(expected - t) > 0.25 || playing != lastPlaying;
        if (!jumped && now - lastTimeNanos < 250_000_000L) return;
        lastSent = t; lastPlaying = playing; lastTimeNanos = now;
        js("rainium.time(" + t + ", " + playing + ");");
    }

    /** Points the texture at the page's latest picture; false when there's nothing to show yet. */
    static boolean prepare() {
        if (!ready() || texture == null) return false;
        try {
            Object view = mView.invoke(browser), tex = mTexture.invoke(browser);
            if (view == null || tex == null) return false;
            return texture.show((GpuTexture) tex, (GpuTextureView) view);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Closes the page (lyrics switched off or the mode changed). MCEF itself stays ready. */
    static void close() {
        if (browser != null) {
            try { mClose.invoke(browser); } catch (Throwable ignored) { }
            browser = null;
        }
        songKey = null;
        if (state == State.READY) state = State.STARTING;
    }

    /**
     * A texture that shows the browser's picture: it's a plain DynamicTexture whose GPU texture is
     * swapped for the browser's while it's in use. The fields are found by their type, so this works
     * whatever they are called in this Minecraft version.
     */
    static final class VideoTexture extends DynamicTexture {
        private static Field texField, viewField;
        private Object ownTex, ownView;

        VideoTexture() { super(() -> "rainium lyrics video", new NativeImage(1, 1, false)); }

        boolean show(GpuTexture tex, GpuTextureView view) {
            try {
                if (texField == null) {
                    for (Field f : AbstractTexture.class.getDeclaredFields()) {
                        if (f.getType() == GpuTexture.class) { f.setAccessible(true); texField = f; }
                        if (f.getType() == GpuTextureView.class) { f.setAccessible(true); viewField = f; }
                    }
                    if (texField == null || viewField == null) return false;
                }
                if (ownView == null) { ownTex = texField.get(this); ownView = viewField.get(this); }
                texField.set(this, tex);
                viewField.set(this, view);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public void close() {
            try {   // give back its own texture, so closing never touches the browser's
                if (ownView != null) { texField.set(this, ownTex); viewField.set(this, ownView); }
            } catch (Throwable ignored) { }
            super.close();
        }
    }
}
