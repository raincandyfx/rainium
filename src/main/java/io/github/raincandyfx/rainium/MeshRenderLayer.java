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
        if (!editorPreview && !shouldShow(st.config(), state.id)) return;

        Sway.Pose sp = Sway.Pose.REST;
        if (st.anyPhysics() || preview != null) {
            sp = (editorPreview ? previewSway : sway).sample(state.id, state.ageInTicks / 20.0, state.x, state.y, state.z, state.bodyRot,
                st.config().physics, System.nanoTime() / 1e9);
        }
        int hurtOverlay = LivingEntityRenderer.getOverlayCoords(state, 0f);
        PlayerModel model = getParentModel();
        Bones bones = (p, anchor) -> {
            switch (anchor) {
                case BODY -> model.body.translateAndRotate(p);
                case HEAD -> model.head.translateAndRotate(p);
                case LEFT_ARM -> model.leftArm.translateAndRotate(p);
                case RIGHT_ARM -> model.rightArm.translateAndRotate(p);
                case LEFT_LEG -> model.leftLeg.translateAndRotate(p);
                case RIGHT_LEG -> model.rightLeg.translateAndRotate(p);
                case ROOT -> { }
            }
        };

        for (Assets.Resolved r : layers) {
            LayerConfig.Layer l = r.cfg();
            if (l.hideWhenFlying && state.isFallFlying) continue;
            submitAll(ps, collector, light, hurtOverlay, bones, sp, st, r);
        }
    }

    /** Moves the pose to an attachment point of the model being drawn. */
    interface Bones { void apply(PoseStack ps, LayerConfig.Anchor anchor); }

    static void submitAll(PoseStack ps, SubmitNodeCollector collector, int light, int hurtOverlay, Bones bones, Sway.Pose sp,
                          Assets.State st, Assets.Resolved r) {
        LayerConfig.Layer l = r.cfg();
        if (l.anchor.limb()) {
            // On an arm or leg, "Mirrored copy" means a copy on the opposite limb.
            submitLayer(ps, collector, light, hurtOverlay, bones, sp, st, r, l.anchor, MIRROR_NONE);
            if (l.mirror) submitLayer(ps, collector, light, hurtOverlay, bones, sp, st, r, l.anchor.opposite(), MIRROR_LIMB);
        } else {
            submitLayer(ps, collector, light, hurtOverlay, bones, sp, st, r, l.anchor, l.mirror ? MIRROR_BOTH : MIRROR_NONE);
        }
    }

    private static final int MIRROR_NONE = 0, MIRROR_BOTH = 1, MIRROR_LIMB = 2;

    private static void submitLayer(PoseStack ps, SubmitNodeCollector collector, int light, int hurtOverlay, Bones bones, Sway.Pose sp,
                                    Assets.State st, Assets.Resolved r, LayerConfig.Anchor anchor, int mirrorMode) {
        LayerConfig.Layer l = r.cfg();
        ps.pushPose();
        if (l.physics) {
            float k = st.config().motionOf(l);   // per-folder motion strength
            if (k != 0f) {
                ps.translate(sp.x() * k, sp.y() * k, sp.z() * k);
                ps.rotateDegrees(Axis.YP, sp.yawDeg() * k);
            }
        }
        bones.apply(ps, anchor);
        // The copy on the opposite limb is the layer reflected left/right: reflect the layer's own
        // placement here and its vertices in emit (two reflections, so faces keep their winding).
        if (mirrorMode == MIRROR_LIMB) ps.scale(-1f, 1f, 1f);
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
        SubmitNodeCollector.CustomGeometryRenderer geo = (pose, vc) -> emit(groups, pose, vc, color, lightValue, overlay, mirrorMode);
        OrderedSubmitNodeCollector target = order == 0 ? collector : collector.order(order);
        target.submitCustomGeometry(ps, renderType(l.render, r.texture()), geo);
        ps.popPose();
    }

    private static boolean shouldShow(LayerConfig cfg, int id) {
        LayerConfig.ShowOn mode = cfg.showOn;
        if (mode == LayerConfig.ShowOn.ALL) return true;
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player == null || mc.player.getId() == id; // no player (e.g. menus) -> treat as self
        return switch (mode) {
            case SELF -> self;
            case OTHERS -> !self;
            case LISTED -> cfg.listsPlayer(entityName(id, false));
            default -> true;
        };
    }

    /** The name of the entity with this id, or null. */
    static String entityName(int id, boolean unused) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return mc.player != null && mc.player.getId() == id ? mc.player.getName().getString() : null;
        net.minecraft.world.entity.Entity e = mc.level.getEntity(id);
        return e == null ? null : e.getName().getString();
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

    static void emit(Mesh.Group[] groups, PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay, int mirrorMode) {
        for (Mesh.Group g : groups) {
            if (mirrorMode == MIRROR_LIMB) { emitGroup(g, pose, vc, color, light, overlay, -1f, false); continue; }
            emitGroup(g, pose, vc, color, light, overlay, 1f, false);
            if (mirrorMode == MIRROR_BOTH) emitGroup(g, pose, vc, color, light, overlay, -1f, true);
        }
    }

    private static void emitGroup(Mesh.Group g, PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay, float sx, boolean reverse) {
        float[] v = g.v();
        int[] q = g.q();
        for (int k = 0; k < q.length; k += 4) {
            if (reverse) {
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
