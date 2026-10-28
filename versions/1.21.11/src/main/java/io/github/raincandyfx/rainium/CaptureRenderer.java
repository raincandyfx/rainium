package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.GuiEntityRenderer;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.pip.GuiEntityRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;

/**
 * Draws the editor preview while a picture or recording is being taken (Minecraft 1.21.11). It draws
 * exactly like the game's own preview (the same entity drawing, into its own transparent texture),
 * but at a chosen multiple of the screen's resolution, and copies the finished texture back from the
 * graphics card for {@link Recorder}.
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
        Entity(MultiBufferSource.BufferSource buffers, EntityRenderDispatcher d) { super(buffers, d); }
        void draw(GuiEntityRenderState s, PoseStack ps) { renderToTexture(s, ps); }
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
        SpecialGuiElementRegistry.register(ctx -> new CaptureRenderer(ctx.vertexConsumers(), ctx.client().getEntityRenderDispatcher()));
        Recorder.submit = CaptureRenderer::submit;
    }

    /** The texture fields, by their names in the running game (mapped names in development, intermediary names otherwise). */
    private static Field field(String name) throws NoSuchFieldException {
        Field f;
        try {
            f = PictureInPictureRenderer.class.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = byOrder(name);
        }
        f.setAccessible(true);
        return f;
    }

    /**
     * Outside development the fields have intermediary names, so find them by type and order:
     * the class declares texture, textureView, depthTexture, depthTextureView in that order.
     */
    private static Field byOrder(String name) throws NoSuchFieldException {
        java.util.List<Field> tex = new java.util.ArrayList<>(), views = new java.util.ArrayList<>();
        for (Field f : PictureInPictureRenderer.class.getDeclaredFields()) {
            if (f.getType() == GpuTexture.class) tex.add(f);
            else if (f.getType() == GpuTextureView.class) views.add(f);
        }
        if (tex.size() != 2 || views.size() != 2) throw new NoSuchFieldException(name);
        return switch (name) {
            case "texture" -> tex.get(0);
            case "depthTexture" -> tex.get(1);
            case "textureView" -> views.get(0);
            default -> views.get(1);
        };
    }

    /** Like GuiGraphics.submitEntityRenderState, but drawn by this renderer. */
    private static void submit(Object graphics, Object entityState, float scale, Object translation, Object rotation, Object camera,
                               int x0, int y0, int x1, int y1, int size) {
        GuiGraphics g = (GuiGraphics) graphics;
        EntityRenderState rs = (EntityRenderState) entityState;
        GuiEntityRenderState es = new GuiEntityRenderState(rs, (Vector3f) translation, (Quaternionf) rotation, (Quaternionf) camera,
            x0, y0, x1, y1, scale, null);
        g.guiRenderState.submitPicturesInPictureState(new State(es, size));
    }

    private final Entity entity;

    private CaptureRenderer(MultiBufferSource.BufferSource buffers, EntityRenderDispatcher dispatcher) {
        super(buffers);
        this.entity = new Entity(buffers, dispatcher);
    }

    @Override
    public Class<State> getRenderStateClass() { return State.class; }

    @Override
    protected void renderToTexture(State s, PoseStack ps) { entity.draw(s.entity(), ps); }

    @Override
    protected float getTranslateY(int height, int guiScale) { return height / 2f; }

    @Override
    protected String getTextureLabel() { return "rainium capture"; }

    @Override
    public void prepare(State s, GuiRenderState gui, int guiScale) {
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
        super.prepare(s, gui, scale);
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
            GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
            TextureFormat.RGBA8, w, h, 1, 1);
        GpuTextureView tv = device.createTextureView(t);
        GpuTexture d = device.createTexture(() -> "UI rainium capture depth texture",
            GpuTexture.USAGE_RENDER_ATTACHMENT, TextureFormat.DEPTH32, w, h, 1, 1);
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
            int bpp = tex.getFormat().pixelSize();
            GpuDevice device = RenderSystem.getDevice();
            GpuBuffer buf = device.createBuffer(() -> "rainium capture buffer",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * bpp);
            CommandEncoder enc = device.createCommandEncoder();
            enc.copyTextureToBuffer(tex, buf, 0L, () -> {
                byte[] px = new byte[w * h * 4];
                try (GpuBuffer.MappedView view = enc.mapBuffer(buf, true, false)) {
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
