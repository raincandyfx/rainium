package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.pip.GuiEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

import java.lang.reflect.Field;

/**
 * Draws the editor preview while a picture or recording is being taken. It draws exactly like the
 * game's own preview (the same entity drawing, into its own transparent texture), but at a chosen
 * multiple of the screen's resolution, and copies the finished texture back from the graphics card
 * for {@link Recorder}.
 */
final class CaptureRenderer extends PictureInPictureRenderer<CaptureRenderer.State> {

    /** The preview, with how many times the screen resolution to draw it at. */
    record State(GuiEntityRenderState entity, int size) implements PictureInPictureRenderState {
        public int x0() { return entity.x0(); }
        public int x1() { return entity.x1(); }
        public int y0() { return entity.y0(); }
        public int y1() { return entity.y1(); }
        public float scale() { return entity.scale(); }
        public ScreenRectangle scissorArea() { return entity.scissorArea(); }
        public ScreenRectangle bounds() { return entity.bounds(); }
    }

    /** The game's entity preview drawing, reachable from here. */
    private static final class Entity extends GuiEntityRenderer {
        Entity(EntityRenderDispatcher d) { super(d); }
        void draw(GuiEntityRenderState s, PoseStack ps, SubmitNodeCollector c) { renderToTexture(s, ps, c); }
    }

    private static Field texture, textureView, depthTexture, depthTextureView;

    static void register() {
        try {
            texture = field("texture");
            textureView = field("textureView");
            depthTexture = field("depthTexture");
            depthTextureView = field("depthTextureView");
        } catch (Throwable t) {
            Assets.LOG.warn("[rainium] pictures and recordings are unavailable: " + t);
            return;
        }
        PictureInPictureRendererRegistry.register(ctx -> new CaptureRenderer(ctx.minecraft().getEntityRenderDispatcher()));
        Recorder.submit = CaptureRenderer::submit;
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field f = PictureInPictureRenderer.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Like GuiGraphicsExtractor.entity, but drawn by this renderer. */
    private static void submit(Object graphics, Object entityState, float scale, Object translation, Object rotation, Object camera,
                               int x0, int y0, int x1, int y1, int size) {
        GuiGraphicsExtractor g = (GuiGraphicsExtractor) graphics;
        EntityRenderState rs = (EntityRenderState) entityState;
        rs.lightCoords = 15728880;
        GuiEntityRenderState es = new GuiEntityRenderState(rs, (Vector3fc) translation, (Quaternionfc) rotation, (Quaternionfc) camera,
            x0, y0, x1, y1, scale, null);
        g.guiRenderState.addPicturesInPictureState(new State(es, size));
    }

    private final Entity entity;

    private CaptureRenderer(EntityRenderDispatcher dispatcher) {
        this.entity = new Entity(dispatcher);
    }

    @Override
    public Class<State> getRenderStateClass() { return State.class; }

    @Override
    protected void renderToTexture(State s, PoseStack ps, SubmitNodeCollector c) { entity.draw(s.entity(), ps, c); }

    @Override
    protected float getTranslateY(int height, int guiScale) { return height / 2f; }

    @Override
    protected String getTextureLabel() { return "rainium capture"; }

    @Override
    public void prepare(State s, GuiRenderState gui, FeatureRenderDispatcher features, int guiScale) {
        int w0 = s.x1() - s.x0(), h0 = s.y1() - s.y0();
        int scale = guiScale * Math.max(1, s.size());
        while (scale > 1 && (w0 * scale > Recorder.MAX_SIDE || h0 * scale > Recorder.MAX_SIDE)) scale--;
        int w = w0 * scale, h = h0 * scale;
        long ticket = Recorder.beginFrame(System.nanoTime());
        GpuTexture tex = null;
        try {
            if (w > 0 && h > 0) tex = ensureTextures(w, h);
        } catch (Throwable t) {
            Recorder.abandon(ticket, t.toString());
            ticket = -1;
        }
        super.prepare(s, gui, features, scale);
        if (ticket >= 0) {
            if (tex == null) Recorder.abandon(ticket, "nothing to capture");
            else readBack(tex, w, h, ticket);
        }
    }

    /**
     * The game makes these textures without permission to copy from them; make them here with it.
     * The base class keeps textures of the right size, so it draws into these.
     */
    private GpuTexture ensureTextures(int w, int h) throws IllegalAccessException {
        GpuTexture cur = (GpuTexture) texture.get(this);
        if (cur != null && !cur.isClosed() && cur.getWidth(0) == w && cur.getHeight(0) == h && (cur.usage() & GpuTexture.USAGE_COPY_SRC) != 0) return cur;
        for (Field f : new Field[]{textureView, texture, depthTextureView, depthTexture}) {
            AutoCloseable old = (AutoCloseable) f.get(this);
            if (old != null) { try { old.close(); } catch (Exception ignored) {} }
            f.set(this, null);
        }
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture t = device.createTexture(() -> "UI rainium capture texture",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
            GpuFormat.RGBA8_UNORM, w, h, 1, 1);
        GpuTextureView tv = device.createTextureView(t);
        GpuTexture d = device.createTexture(() -> "UI rainium capture depth texture",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.D32_FLOAT, w, h, 1, 1);
        GpuTextureView dv = device.createTextureView(d);
        texture.set(this, t);
        textureView.set(this, tv);
        depthTexture.set(this, d);
        depthTextureView.set(this, dv);
        return t;
    }

    /** Copies the drawn texture to memory; the pixels reach the Recorder once the graphics card is done. */
    private static void readBack(GpuTexture tex, int w, int h, long ticket) {
        try {
            int bpp = tex.getFormat().blockSize();
            GpuBuffer buf = RenderSystem.getDevice().createBuffer(() -> "rainium capture buffer",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, () -> {
                byte[] px = new byte[w * h * 4];
                try (GpuBufferSlice.MappedView view = buf.map(true, false)) {
                    if (bpp == 4) view.data().get(0, px);
                    else for (int i = 0; i < w * h; i++) for (int k = 0; k < 4; k++) px[i * 4 + k] = view.data().get(i * bpp + k);
                } catch (Throwable t) {
                    buf.close();
                    Recorder.abandon(ticket, t.toString());
                    return;
                }
                buf.close();
                Recorder.frame(ticket, w, h, px);
            }, 0);
        } catch (Throwable t) {
            Recorder.abandon(ticket, t.toString());
        }
    }
}
