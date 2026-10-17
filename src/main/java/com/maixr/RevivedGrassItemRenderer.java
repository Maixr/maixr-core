package com.maixr;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** 复苏草方块物品渲染：3D 草方块本体 + 9 色卫星像素拖尾绕其旋转。 */
public class RevivedGrassItemRenderer extends BlockEntityWithoutLevelRenderer {
    /** 九个卫星颜色（对应九大弧光核心）。 */
    private static final int[] COLORS = {
            0xFF5555, 0xFFA83C, 0xFFE45C, 0x55DD55, 0x4FE0D0,
            0x55A8FF, 0xA86BFF, 0xFF7BD5, 0xFFFFFF
    };
    private static final float RADIUS = 0.78F;
    private static final float TAU = (float) (Math.PI * 2);

    public RevivedGrassItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(), Minecraft.getInstance().getEntityModels());
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack pose,
                             MultiBufferSource buffer, int light, int overlay) {
        // ---- 1) 本体：3D 草方块模型 ----
        BakedModel model = Minecraft.getInstance().getItemRenderer().getModel(stack, null, null, 0);
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                pose.last(), buffer.getBuffer(RenderType.cutout()),
                Blocks.GRASS_BLOCK.defaultBlockState(), model, 1.0F, 1.0F, 1.0F, light, overlay);

        // ---- 2) 九色卫星 + 拖尾绕转 ----
        float time = (Util.getMillis() % 7200000L) / 1000.0F; // 秒
        VertexConsumer vc = buffer.getBuffer(RenderType.debugQuads());
        pose.pushPose();
        pose.translate(0.5D, 0.5D, 0.5D); // 以方块中心为轨道中心
        for (int i = 0; i < 9; i++) {
            float base = time * 0.45F + i * (TAU / 9.0F);
            int color = COLORS[i];
            // 拖尾（由远及近、由小到大）
            for (int k = 6; k >= 1; k--) {
                float a = base - k * 0.16F;
                float r = RADIUS * (0.92F - k * 0.012F);
                float s = 0.055F - k * 0.005F;
                float y = (float) Math.sin(time * 0.8F + i + k * 0.25F) * 0.06F;
                cube(vc, pose, (float) Math.cos(a) * r, y, (float) Math.sin(a) * r, s, color, 0.45F, light);
            }
            // 卫星本体
            float y = (float) Math.sin(time * 0.8F + i) * 0.07F;
            cube(vc, pose, (float) Math.cos(base) * RADIUS, y, (float) Math.sin(base) * RADIUS,
                    0.075F, color, 1.0F, light);
        }
        pose.popPose();
    }

    /** 画一个小立方体（三张正交面片），alpha 用 brightness 缩放表现拖尾衰减。 */
    private static void cube(VertexConsumer vc, PoseStack pose, float x, float y, float z,
                             float s, int rgb, float brightness, int light) {
        int r = (int) (((rgb >> 16) & 0xFF) * brightness);
        int g = (int) (((rgb >> 8) & 0xFF) * brightness);
        int b = (int) ((rgb & 0xFF) * brightness);
        int a = (int) (255 * Math.min(1.0F, brightness + 0.15F));
        org.joml.Matrix4f m = pose.last().pose();
        // XY 面
        quad(vc, m, x - s, y - s, z + s, x + s, y - s, z + s, x + s, y + s, z + s, x - s, y + s, z + s, r, g, b, a);
        // YZ 面
        quad(vc, m, x + s, y - s, z - s, x + s, y - s, z + s, x + s, y + s, z + s, x + s, y + s, z - s, r, g, b, a);
        // XZ 面
        quad(vc, m, x - s, y + s, z - s, x + s, y + s, z - s, x + s, y + s, z + s, x - s, y + s, z + s, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, org.joml.Matrix4f m,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             int r, int g, int b, int a) {
        vc.addVertex(x1, y1, z1).setColor(r, g, b, a);
        vc.addVertex(x2, y2, z2).setColor(r, g, b, a);
        vc.addVertex(x3, y3, z3).setColor(r, g, b, a);
        vc.addVertex(x4, y4, z4).setColor(r, g, b, a);
    }
}
