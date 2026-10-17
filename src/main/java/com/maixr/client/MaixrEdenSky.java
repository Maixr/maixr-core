package com.maixr.client;

import com.maixr.EdenStage;
import com.maixr.MaixrMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.joml.Matrix4f;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端动态天空：只作用于 maixr:overworld_broken，颜色跟着世界纪元走。
 *
 * <ul>
 *   <li>雾色：直接改 ViewportEvent.ComputeFogColor</li>
 *   <li>天色：在天空渲染完、地形渲染之前，罩一个半径 160 的彩色盒子。
 *       因为**不写深度**（depthMask false），后面的地形照样会盖在它上面，
 *       所以它只会染色天空，不会糊住世界。</li>
 * </ul>
 *
 * <h2>⚠️ 已全局停用</h2>
 *
 * <p>{@link #ENABLED} 现在是 {@code false} —— <b>任何世界都不再染色天空/雾/粒子</b>。</p>
 *
 * <p>停用原因：这个天空盒原本是为"破碎世界"设计的氛围效果，
 * 但在实际游玩中它会盖住原版天色，且判定依据（维度）与玩家预期的"世界类型"不一致，
 * 导致普通世界也被染色。既然氛围不是必需品，干脆整块关掉。</p>
 *
 * <p><b>想重新启用</b>：把 {@code ENABLED} 改成 {@code true} 即可 ——
 * 下面所有代码都保留着，没有删除。</p>
 */
@EventBusSubscriber(modid = MaixrMod.MODID, value = Dist.CLIENT)
public final class MaixrEdenSky {

    /**
     * 全局开关。
     *
     * <p>{@code false} = 完全不生效（不染天色、不改雾色、不放粒子）。
     * 三个事件入口都会先查这个值，为 false 直接返回，零开销。</p>
     */
    public static final boolean ENABLED = false;

    private static final float RADIUS = 160F;
    /** 保留一点透明度，让太阳月亮星星还能隐约透出来 */
    private static final float ALPHA = 0.80F;

    private MaixrEdenSky() {}

    /**
     * 随纪元飘的粒子：低纪元下灰烬，高纪元落花瓣。
     * 纯客户端生成，不占网络与服务器性能。
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        if (!MaixrEdenClientState.inBroken()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        int stage = EdenStage.clamp(MaixrEdenClientState.stage);
        ParticleOptions type;
        int count;
        if (stage <= 2) { type = ParticleTypes.ASH; count = 2 + stage; }
        else if (stage <= 5) { type = ParticleTypes.WHITE_ASH; count = 4; }
        else { type = ParticleTypes.CHERRY_LEAVES; count = 3 + (stage - 5); }

        net.minecraft.world.entity.player.Player player = Minecraft.getInstance().player;
        if (player == null) return;
        for (int i = 0; i < count; i++) {
            double x = player.getX() + (level.random.nextDouble() - 0.5) * 32.0;
            double z = player.getZ() + (level.random.nextDouble() - 0.5) * 32.0;
            double y = player.getY() + 6.0 + level.random.nextDouble() * 10.0;
            level.addParticle(type, x, y, z, 0.0, -0.04, 0.0);
        }
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!ENABLED) return;
        if (!MaixrEdenClientState.inBroken()) return;
        int rgb = EdenStage.fog(MaixrEdenClientState.stage);
        event.setRed(((rgb >> 16) & 0xFF) / 255F);
        event.setGreen(((rgb >> 8) & 0xFF) / 255F);
        event.setBlue((rgb & 0xFF) / 255F);
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (!ENABLED) return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        if (!MaixrEdenClientState.inBroken()) return;

        int rgb = EdenStage.sky(MaixrEdenClientState.stage);
        float r = ((rgb >> 16) & 0xFF) / 255F;
        float g = ((rgb >> 8) & 0xFF) / 255F;
        float b = (rgb & 0xFF) / 255F;

        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        Matrix4f mat = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 【1.21】Tesselator API 变了：
        //   1.20.1: Tesselator.getInstance().getBuilder() + buffer.begin(mode, format)
        //   1.21  : Tesselator.getInstance().begin(mode, format) 直接返回 BufferBuilder
        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float R = RADIUS;
        // 下 / 上 / 北 / 南 / 西 / 东 六个面
        face(buffer, mat, r, g, b, -R, -R, -R,  R, -R, -R,  R, -R,  R, -R, -R,  R);
        face(buffer, mat, r, g, b, -R,  R,  R,  R,  R,  R,  R,  R, -R, -R,  R, -R);
        face(buffer, mat, r, g, b, -R, -R, -R, -R,  R, -R,  R,  R, -R,  R, -R, -R);
        face(buffer, mat, r, g, b,  R, -R,  R,  R,  R,  R, -R,  R,  R, -R, -R,  R);
        face(buffer, mat, r, g, b, -R, -R,  R, -R,  R,  R, -R,  R, -R, -R, -R, -R);
        face(buffer, mat, r, g, b,  R, -R, -R,  R,  R, -R,  R,  R,  R,  R, -R,  R);

        // 【1.21】buffer.end() 改名为 buildOrThrow()
        BufferUploader.drawWithShader(buffer.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /** 一个四边形：四个顶点顺时针给出，XYZ 依次排列 */
    private static void face(BufferBuilder buffer, Matrix4f mat, float r, float g, float b,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        buffer.addVertex(x1, y1, z1).setColor(r, g, b, ALPHA);
        buffer.addVertex(x2, y2, z2).setColor(r, g, b, ALPHA);
        buffer.addVertex(x3, y3, z3).setColor(r, g, b, ALPHA);
        buffer.addVertex(x4, y4, z4).setColor(r, g, b, ALPHA);
    }
}
