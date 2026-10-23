package io.github.raincandyfx.rainium;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * Draws your layers on armor stands with a chosen name (Settings > Visibility), for screenshots and
 * displays. Only you see them, like everything else in the mod. Armor stands are posed like a
 * standing player, so attachment points use the player's resting positions and there is no sway.
 */
final class StandRenderLayer<S extends LivingEntityRenderState, M extends EntityModel<? super S>> extends RenderLayer<S, M> {
    // resting pivots of the player model, in blocks (model space: +X left, +Y down)
    private static final float ARM_X = 5f / 16f, ARM_Y = 2f / 16f, LEG_X = 1.9f / 16f, LEG_Y = 12f / 16f;

    StandRenderLayer(RenderLayerParent<S, M> parent) { super(parent); }

    @Override
    public void submit(PoseStack ps, SubmitNodeCollector collector, int light, S state, float yRot, float xRot) {
        Assets.State st = RainiumClient.ASSETS.current();
        if (st == null || !st.config().enabled || !st.config().armorStands || state.isInvisible) return;
        String want = st.config().armorStandName == null ? "" : st.config().armorStandName.trim();
        String name = MeshRenderLayer.entityName(state.id, true);
        if (name == null || !name.trim().equalsIgnoreCase(want)) return;
        int overlay = LivingEntityRenderer.getOverlayCoords(state, 0f);
        MeshRenderLayer.Bones bones = (p, anchor) -> {
            switch (anchor) {
                case LEFT_ARM -> p.translate(ARM_X, ARM_Y, 0f);
                case RIGHT_ARM -> p.translate(-ARM_X, ARM_Y, 0f);
                case LEFT_LEG -> p.translate(LEG_X, LEG_Y, 0f);
                case RIGHT_LEG -> p.translate(-LEG_X, LEG_Y, 0f);
                default -> { }   // body, head and root all start at the neck
            }
        };
        for (Assets.Resolved r : st.layers()) MeshRenderLayer.submitAll(ps, collector, light, overlay, bones, Sway.Pose.REST, st, r);
    }
}
