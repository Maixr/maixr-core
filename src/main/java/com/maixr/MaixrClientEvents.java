package com.maixr;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

/** 客户端：把玩偶注册为玩家渲染层（佩戴时坐在头上，可旋转）。 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MaixrClientEvents {
    private MaixrClientEvents() {}

    /** 玩家身上（原版头盔槽或 Curios 头饰槽）戴着的玩偶；没戴返回空。 */
    static ItemStack getWornDoll(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (!head.isEmpty() && MaixrMod.isDollItem(head.getItem())) return head;
        if (MaixrCurios.isLoaded()) {
            ItemStack curios = MaixrCurios.getHeadStack(player);
            if (!curios.isEmpty() && MaixrMod.isDollItem(curios.getItem())) return curios;
        }
        return ItemStack.EMPTY;
    }

    static boolean isDollWorn(net.minecraft.world.entity.LivingEntity entity) {
        return entity instanceof Player player && !getWornDoll(player).isEmpty();
    }

    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (net.minecraft.client.resources.PlayerSkin.Model skin : event.getSkins()) {
            PlayerRenderer renderer = event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new DollOnHeadLayer(renderer));
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(MaixrBlockEntities.SKIN_DOLL.get(), MaixrDollBlockRenderer::new);
        event.registerBlockEntityRenderer(MaixrBlockEntities.TRUTH_DOLL.get(), MaixrDollBlockRenderer::new);
        event.registerBlockEntityRenderer(MaixrBlockEntities.MEMORY_TOMORROW_DOLL.get(), MaixrDollBlockRenderer::new);
    }

    /** 渲染层：玩偶坐在头顶（原版 CustomHeadLayer 锚点，佩戴旋转开关生效）。 */
    public static final class DollOnHeadLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
        public DollOnHeadLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffer, int light, AbstractClientPlayer player,
                           float limbSwing, float limbSwingAmount, float partialTicks,
                           float ageInTicks, float netHeadYaw, float headPitch) {
            if (player.isSpectator()) return;
            ItemStack worn = getWornDoll(player);
            if (worn.isEmpty()) return;

            pose.pushPose();
            ModelPart head = this.getParentModel().head;
            head.translateAndRotate(pose);
            // 原版"戴在头上的物品"定位（猪咪同款）
            CustomHeadLayer.translateToHead(pose, false);

            // 佩戴旋转（每帧时间驱动）
            if (MaixrDollItem.isSpinning(worn)) {
                float t = (player.level().getGameTime() + partialTicks) / 20.0F;
                pose.mulPose(Axis.YP.rotationDegrees(t * MaixrDollBlockEntity.SPIN_SPEED));
            }

            Minecraft mc = Minecraft.getInstance();
            mc.getItemRenderer().renderStatic(worn, ItemDisplayContext.HEAD,
                    light, OverlayTexture.NO_OVERLAY, pose, buffer, player.level(), 0);
            pose.popPose();
        }
    }
}
