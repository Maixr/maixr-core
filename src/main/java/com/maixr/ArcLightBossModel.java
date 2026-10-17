package com.maixr;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * 弧光·裂隙意志的模型（压迫感版）。
 *
 * <p>结构自内向外五层：
 * <ol>
 *   <li><b>core</b>：10³ 的实体核心，缓慢自转 + 呼吸脉动</li>
 *   <li><b>eye</b>：6³ 的亮核突出在正面 —— <b>它会一直盯着你</b></li>
 *   <li><b>8 块碎片</b>：4³，逆时针环绕，相位越高转得越快、散得越开</li>
 *   <li><b>8 根长刺</b>：3×3×12 的利刃，反向环绕并翻滚</li>
 *   <li><b>两圈光环</b>：十字长条，各自以不同倾角反向旋转</li>
 * </ol>
 */
public class ArcLightBossModel extends EntityModel<ArcLightBoss> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "arc_light_boss"), "main");

    private final ModelPart root;
    private final ModelPart core;
    private final ModelPart eye;
    private final ModelPart[] shards = new ModelPart[8];
    private final ModelPart[] spikes = new ModelPart[8];
    private final ModelPart ringA;
    private final ModelPart ringB;

    public ArcLightBossModel(ModelPart root) {
        this.root = root;
        this.core = root.getChild("core");
        this.eye = root.getChild("eye");
        for (int i = 0; i < 8; i++) {
            this.shards[i] = root.getChild("shard" + i);
            this.spikes[i] = root.getChild("spike" + i);
        }
        this.ringA = root.getChild("ringA");
        this.ringB = root.getChild("ringB");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // 核心 10³ —— 贴图左上角 40x30 区域
        root.addOrReplaceChild("core",
                CubeListBuilder.create().texOffs(0, 0).addBox(-5.0F, -5.0F, -5.0F, 10.0F, 10.0F, 10.0F),
                PartPose.offset(0.0F, 8.0F, 0.0F));

        // 亮核（眼睛）6³，往前顶出来一点
        root.addOrReplaceChild("eye",
                CubeListBuilder.create().texOffs(48, 0).addBox(-3.0F, -3.0F, -3.0F, 6.0F, 6.0F, 6.0F),
                PartPose.offset(0.0F, 8.0F, -5.0F));

        // 8 块碎片
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45.0);
            root.addOrReplaceChild("shard" + i,
                    CubeListBuilder.create().texOffs(0, 24).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 4.0F, 4.0F),
                    PartPose.offset((float) (Math.cos(a) * 16.0), 8.0F, (float) (Math.sin(a) * 16.0)));
            // 8 根长刺（用同一块贴图区域）
            root.addOrReplaceChild("spike" + i,
                    CubeListBuilder.create().texOffs(24, 24).addBox(-1.5F, -1.5F, -6.0F, 3.0F, 3.0F, 12.0F),
                    PartPose.offset((float) (Math.cos(a) * 20.0), 8.0F, (float) (Math.sin(a) * 20.0)));
        }

        // 两圈光环
        root.addOrReplaceChild("ringA",
                CubeListBuilder.create().texOffs(0, 48)
                        .addBox(-17.0F, -0.5F, -0.5F, 34.0F, 1.0F, 1.0F)
                        .addBox(-0.5F, -0.5F, -17.0F, 1.0F, 1.0F, 34.0F),
                PartPose.offset(0.0F, 8.0F, 0.0F));
        root.addOrReplaceChild("ringB",
                CubeListBuilder.create().texOffs(0, 56)
                        .addBox(-21.0F, -0.5F, -0.5F, 42.0F, 1.0F, 1.0F)
                        .addBox(-0.5F, -0.5F, -21.0F, 1.0F, 1.0F, 42.0F),
                PartPose.offset(0.0F, 8.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, com.mojang.blaze3d.vertex.VertexConsumer buffer, int packedLight, int packedOverlay, int color) {
        // 【1.21 移植】renderToBuffer 从 8 参（含 RGBA）减到 5 参（色值合并为一个 int）。
        this.root.render(poseStack, buffer, packedLight, packedOverlay, color);
    }

    @Override
    public void setupAnim(ArcLightBoss entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        int phase = entity.phase();
        float spin = ageInTicks * (0.025F + phase * 0.022F);
        float spread = 1.0F + phase * 0.22F;

        // 核心：自转 + 呼吸
        this.core.yRot = spin * 1.4F;
        this.core.xRot = Mth.sin(ageInTicks * 0.05F) * 0.16F;
        float pulse = 1.0F + Mth.sin(ageInTicks * (0.18F + phase * 0.08F)) * (0.06F + phase * 0.03F);
        this.core.xScale = pulse;
        this.core.yScale = pulse;
        this.core.zScale = pulse;

        // 眼睛：死死盯着玩家（用头部的偏航/俯仰）
        this.eye.yRot = netHeadYaw * ((float) Math.PI / 180F) * 0.6F;
        this.eye.xRot = headPitch * ((float) Math.PI / 180F) * 0.6F;
        float eyePulse = 1.0F + Mth.sin(ageInTicks * 0.35F) * 0.12F;
        this.eye.xScale = eyePulse;
        this.eye.yScale = eyePulse;
        this.eye.zScale = eyePulse;

        for (int i = 0; i < 8; i++) {
            double base = Math.toRadians(i * 45.0);

            // 碎片：顺时针环绕
            ModelPart shard = this.shards[i];
            double a1 = base - spin * 1.2;
            shard.x = (float) (Math.cos(a1) * 16.0 * spread);
            shard.z = (float) (Math.sin(a1) * 16.0 * spread);
            shard.y = 8.0F + Mth.sin(ageInTicks * 0.08F + i) * 2.5F + (i % 2 == 0 ? 1.5F : -1.5F);
            shard.xRot = spin * 2.0F;
            shard.yRot = spin * 2.4F;

            // 长刺：逆时针环绕 + 翻滚 + 朝外张开
            ModelPart spike = this.spikes[i];
            double a2 = base + spin * 0.9;
            spike.x = (float) (Math.cos(a2) * 20.0 * spread);
            spike.z = (float) (Math.sin(a2) * 20.0 * spread);
            spike.y = 8.0F + Mth.sin(ageInTicks * 0.06F + i * 1.7F) * 3.5F;
            spike.yRot = (float) (-a2) + (float) Math.PI / 2F;   // 尖端朝外
            spike.xRot = Mth.sin(ageInTicks * 0.09F + i) * 0.5F;
            spike.zRot = spin * 1.6F;
        }

        this.ringA.yRot = -spin * 0.8F;
        this.ringA.xRot = 0.32F;
        this.ringB.yRot = spin * 0.55F;
        this.ringB.zRot = 0.26F;
    }
}
