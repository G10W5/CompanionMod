package com.example.companionmod.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * Player-shaped model with <b>both</b> arm widths baked in, so classic/slim can be switched per frame
 * without replacing the renderer's model instance.
 *
 * <p>Why not two models and a swap? {@code AgeableMobRenderer.submit()} overwrites
 * {@code this.model = state.isBaby ? babyModel : adultModel} right before {@code LivingEntityRenderer.submit()},
 * which silently undid every slim swap (the render state said SLIM but the wide model was drawn). Keeping one
 * model instance and hiding the inactive arm pair is immune to that, and it is also correct with 26.1's deferred
 * model submission because {@code setupAnim(state)} runs per submitted entity at draw time.</p>
 *
 * <p>The slim arm boxes and pivots are identical to {@code PlayerModel.createMesh(..., true)}:
 * right arm {@code texOffs(40,16) box(-2,-2,-2, 3,12,4)} at {@code (-5,2,0)}, left arm
 * {@code texOffs(32,48) box(-1,-2,-2, 3,12,4)} at {@code (5,2,0)}.</p>
 */
public final class CompanionHumanoidModel extends HumanoidModel<CompanionRenderState> {
    private final ModelPart slimRightArm;
    private final ModelPart slimLeftArm;

    public CompanionHumanoidModel() {
        this(buildRoot());
    }

    private CompanionHumanoidModel(ModelPart root) {
        super(root);
        this.slimRightArm = root.getChild("right_arm_slim");
        this.slimLeftArm = root.getChild("left_arm_slim");
    }

    private static ModelPart buildRoot() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0f);
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("right_arm_slim", CubeListBuilder.create()
                        .texOffs(40, 16).addBox(-2.0f, -2.0f, -2.0f, 3.0f, 12.0f, 4.0f),
                PartPose.offset(-5.0f, 2.0f, 0.0f));
        root.addOrReplaceChild("left_arm_slim", CubeListBuilder.create()
                        .texOffs(32, 48).addBox(-1.0f, -2.0f, -2.0f, 3.0f, 12.0f, 4.0f),
                PartPose.offset(5.0f, 2.0f, 0.0f));
        return root.bake(64, 64);
    }

    @Override
    public void setupAnim(CompanionRenderState state) {
        super.setupAnim(state);
        boolean slim = state.slim;
        this.rightArm.visible = !slim;
        this.leftArm.visible = !slim;
        this.slimRightArm.visible = slim;
        this.slimLeftArm.visible = slim;
        copyPose(this.rightArm, this.slimRightArm);
        copyPose(this.leftArm, this.slimLeftArm);
    }

    private static void copyPose(ModelPart from, ModelPart to) {
        to.x = from.x;
        to.y = from.y;
        to.z = from.z;
        to.xRot = from.xRot;
        to.yRot = from.yRot;
        to.zRot = from.zRot;
        to.xScale = from.xScale;
        to.yScale = from.yScale;
        to.zScale = from.zScale;
    }
}
