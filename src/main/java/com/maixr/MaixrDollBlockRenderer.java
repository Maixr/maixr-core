package com.maixr;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.Level;

/** 绘制放置的玩偶：按方块状态渲染（朝向随放置），开启旋转时绕中心持续旋转。 */
public class MaixrDollBlockRenderer implements BlockEntityRenderer<MaixrDollBlockEntity> {
    public MaixrDollBlockRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(MaixrDollBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light, int overlay) {
        Level level = be.getLevel();
        if (level == null) return;
        BakedModel model = Minecraft.getInstance().getModelManager()
                .getBlockModelShaper().getBlockModel(be.getBlockState());
        if (model == null) return;

        pose.pushPose();
        if (be.isSpinning()) {
            float t = (level.getGameTime() + partialTick) / 20.0F;
            float yaw = t * MaixrDollBlockEntity.SPIN_SPEED;
            pose.translate(0.5D, 0.0D, 0.5D);
            pose.mulPose(Axis.YP.rotationDegrees(yaw));
            pose.translate(-0.5D, 0.0D, -0.5D);
        }
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                pose.last(), buffer.getBuffer(RenderType.cutout()), be.getBlockState(), model,
                1.0F, 1.0F, 1.0F, light, overlay);
        pose.popPose();
    }
}
