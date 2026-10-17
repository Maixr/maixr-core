package com.maixr.client;

import com.maixr.ArcLightBoss;
import com.maixr.ArcLightBossModel;
import com.maixr.MaixrMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * 弧光·裂隙意志的渲染器。
 *
 * <p>压迫感来源：<b>相位一之后整个模型切成自发光渲染</b>（{@link RenderType#eyes}），
 * 地形再暗它也是一团烧着的裂缝；影子半径也拉到 2.5 格。
 */
public class ArcLightBossRenderer extends MobRenderer<ArcLightBoss, ArcLightBossModel> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "textures/entity/arc_light_boss.png");

    public ArcLightBossRenderer(EntityRendererProvider.Context context) {
        super(context, new ArcLightBossModel(context.bakeLayer(ArcLightBossModel.LAYER)), 2.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ArcLightBoss entity) {
        return TEXTURE;
    }

    @Override
    protected RenderType getRenderType(ArcLightBoss entity, boolean bodyVisible, boolean translucent, boolean glowing) {
        if (entity.phase() >= 1) {
            return RenderType.eyes(TEXTURE);      // 全亮：像裂隙本身在烧
        }
        return super.getRenderType(entity, bodyVisible, translucent, glowing);
    }

    /** 相位越高，模型整体略微放大 —— 越打越大，压迫感递增 */
    @Override
    protected void scale(ArcLightBoss entity, PoseStack poseStack, float partialTick) {
        float s = 1.0F + entity.phase() * 0.18F;
        poseStack.scale(s, s, s);
    }
}
