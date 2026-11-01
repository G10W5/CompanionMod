package com.example.companionmod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Fallback chest overlay on the vanilla player model, used when Wildfire's Female Gender Mod is not
 * driving the chest. With the mod installed and the owner configured as female, the mod's own
 * {@code GenderLayer} is attached instead and this layer stays hidden.
 *
 * <p>Model space (26.1 player model): the body part pivot sits at the shoulders, the torso cube spans
 * local y 0..12 (shoulders to waist) and z -2..+2, front = -z. Cups are baked around their own centres.</p>
 */
public class FeminineChestLayer extends RenderLayer<CompanionRenderState, HumanoidModel<CompanionRenderState>> {
    private static final float CUP_GAP = 0.2f;      // inner-edge distance from the centre line
    private final ModelPart cupL, cupR;

    public FeminineChestLayer(RenderLayerParent<CompanionRenderState, HumanoidModel<CompanionRenderState>> parent) {
        super(parent);
        this.cupL = bake(-2.05f);
        this.cupR = bake(2.05f);
    }

    /** One cup centred on {@code centreX} (body-local). */
    private static ModelPart bake(float centreX) {
        MeshDefinition mesh = new MeshDefinition();
        CubeListBuilder box = CubeListBuilder.create()
                .texOffs(20, 20).addBox(-1.65f, -1.7f, -0.55f, 3.3f, 3.4f, 1.1f, new CubeDeformation(0.15f));
        mesh.getRoot().addOrReplaceChild("cup", box, PartPose.offset(centreX, 3.3f, -2.55f));
        return mesh.getRoot().bake(64, 64);
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, CompanionRenderState state,
                       float limbSwing, float limbSwingAmount) {
        if (!state.feminine || state.isInvisible || state.wildfireChest) return;
        RenderType type = state.renderType != null ? state.renderType : RenderTypes.entityCutout(state.texture);
        poseStack.pushPose();
        getParentModel().body.translateAndRotate(poseStack);
        poseStack.pushPose();
        poseStack.translate(-CUP_GAP, 0, 0);
        collector.submitModelPart(cupL, poseStack, type, light, OverlayTexture.NO_OVERLAY, null);
        poseStack.popPose();
        poseStack.pushPose();
        poseStack.translate(CUP_GAP, 0, 0);
        collector.submitModelPart(cupR, poseStack, type, light, OverlayTexture.NO_OVERLAY, null);
        poseStack.popPose();
        poseStack.popPose();
    }
}
