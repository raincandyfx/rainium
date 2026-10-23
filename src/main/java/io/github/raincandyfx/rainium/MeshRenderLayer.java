package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

/**
 * Player render layer that draws arbitrary meshes from config/rainium. Same technique as
 * a hand-built wing renderer: push the pose, attach to the body, then stream each quad's
 * vertices straight into the vertex consumer via submitCustomGeometry.
 */
public final class MeshRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final int FULL_BRIGHT = 0xF000F0;
    private final Sway sway = new Sway();
    private final Sway previewSway = new Sway();

    public MeshRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack ps, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
        Assets.State st = RainiumClient.ASSETS.current();
        Assets.Resolved preview = RainiumClient.ASSETS.preview();
        if (preview != null && !(Minecraft.getInstance().gui.screen() instanceof RainiumScreen)) {
            RainiumClient.ASSETS.clearPreview();   // editor closed while importing
            preview = null;
        }
        if (st == null || state.isInvisible) return;
        boolean editorPreview = state == RainiumScreen.previewState;   // the player drawn inside the editor
        java.util.List<Assets.Resolved> layers = new java.util.ArrayList<>(st.config().enabled ? st.layers() : java.util.List.of());
        if (preview != null) layers.add(preview);
        if (layers.isEmpty()) return;
        if (!editorPreview && !shouldShow(st.config().showOn, state.id)) return;

        Sway.Pose sp = Sway.Pose.REST;
        if (st.anyPhysics() || preview != null) {
            sp = (editorPreview ? previewSway : sway).sample(state.id, state.ageInTicks / 20.0, state.x, state.y, state.z, state.bodyRot,
                st.config().physics, System.nanoTime() / 1e9);
        }
        int hurtOverlay = LivingEntityRenderer.getOverlayCoords(state, 0f);
        PlayerModel model = getParentModel();

        for (Assets.Resolved r : layers) {
            LayerConfig.Layer l = r.cfg();
            if (l.hideWhenFlying && state.isFallFlying) continue;
            ps.pushPose();
            if (l.physics) {
                float k = st.config().motionOf(l);   // per-folder motion strength
                if (k != 0f) {
                    ps.translate(sp.x() * k, sp.y() * k, sp.z() * k);
                    ps.rotateDegrees(Axis.YP, sp.yawDeg() * k);
                }
            }
            switch (l.anchor) {
                case BODY -> model.body.translateAndRotate(ps);
                case HEAD -> model.head.translateAndRotate(ps);
                case ROOT -> { }
            }
            ps.translate(l.offset[0], l.offset[1], l.offset[2]);
            if (l.rotation[0] != 0) ps.rotateDegrees(Axis.XP, l.rotation[0]);
            if (l.rotation[1] != 0) ps.rotateDegrees(Axis.YP, l.rotation[1]);
            if (l.rotation[2] != 0) ps.rotateDegrees(Axis.ZP, l.rotation[2]);
            ps.scale(l.scale[0], l.scale[1], l.scale[2]);
            if (l.yUp) ps.rotateDegrees(Axis.XP, 180f); // Y-up/front +Z  ->  model space (Y down, back +Z)

            boolean emissive = l.render == LayerConfig.Render.EMISSIVE || l.render == LayerConfig.Render.TRANSLUCENT_EMISSIVE;
            int lightValue = (l.fullbright || emissive) ? FULL_BRIGHT : light;
            int overlay = emissive ? OverlayTexture.NO_OVERLAY : hurtOverlay;
            int order = l.order >= 0 ? l.order : defaultOrder(l.render);
            final Mesh.Group[] groups = r.groups();
            final int color = l.hue == 0 ? l.color : Hue.rotateArgb(l.color, l.hue);
            final boolean mirror = l.mirror;
            SubmitNodeCollector.CustomGeometryRenderer geo = (pose, vc) -> emit(groups, pose, vc, color, lightValue, overlay, mirror);
            OrderedSubmitNodeCollector target = order == 0 ? collector : collector.order(order);
            target.submitCustomGeometry(ps, renderType(l.render, r.texture()), geo);
            ps.popPose();
        }
    }

    private static boolean shouldShow(LayerConfig.ShowOn mode, int id) {
        if (mode == LayerConfig.ShowOn.ALL) return true;
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player == null || mc.player.getId() == id; // no player (e.g. menus) -> treat as self
        return mode == LayerConfig.ShowOn.SELF ? self : !self;
    }

    private static int defaultOrder(LayerConfig.Render r) {
        return switch (r) {
            case CUTOUT, CUTOUT_NO_CULL -> 0;
            case TRANSLUCENT, TRANSLUCENT_CULL -> 1;
            case EMISSIVE, TRANSLUCENT_EMISSIVE -> 2;
        };
    }

    private static RenderType renderType(LayerConfig.Render r, Identifier tex) {
        return switch (r) {
            case CUTOUT -> RenderTypes.entityCutout(tex);
            case CUTOUT_NO_CULL -> RenderTypes.armorCutoutNoCull(tex);
            case TRANSLUCENT -> RenderTypes.entityTranslucent(tex);
            case TRANSLUCENT_CULL -> RenderTypes.entityTranslucentCull(tex);
            case EMISSIVE -> RenderTypes.eyes(tex);
            case TRANSLUCENT_EMISSIVE -> RenderTypes.entityTranslucentEmissive(tex);
        };
    }

    static void emit(Mesh.Group[] groups, PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay, boolean mirror) {
        for (Mesh.Group g : groups) {
            emitGroup(g, pose, vc, color, light, overlay, false);
            if (mirror) emitGroup(g, pose, vc, color, light, overlay, true);
        }
    }

    private static void emitGroup(Mesh.Group g, PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay, boolean mirror) {
        float[] v = g.v();
        int[] q = g.q();
        float sx = mirror ? -1f : 1f;
        for (int k = 0; k < q.length; k += 4) {
            if (mirror) {
                for (int j = 3; j >= 0; j--) vertex(v, q[k + j], pose, vc, color, light, overlay, sx);
            } else {
                for (int j = 0; j < 4; j++) vertex(v, q[k + j], pose, vc, color, light, overlay, sx);
            }
        }
    }

    private static void vertex(float[] v, int idx, PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay, float sx) {
        int o = idx * Mesh.STRIDE;
        vc.addVertex(pose, v[o] * sx, v[o + 1], v[o + 2])
          .setColor(color)
          .setUv(v[o + 3], v[o + 4])
          .setOverlay(overlay)
          .setLight(light)
          .setNormal(pose, v[o + 5] * sx, v[o + 6], v[o + 7]);
    }
}
