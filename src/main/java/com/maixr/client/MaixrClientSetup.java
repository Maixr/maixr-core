package com.maixr.client;

import com.maixr.ArcLightBossModel;
import com.maixr.MaixrMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.minecraft.resources.ResourceLocation;

/** 客户端注册：Boss 的模型层与渲染器。 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MaixrClientSetup {
    private MaixrClientSetup() {}

    @SubscribeEvent
    public static void registerLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(ArcLightBossModel.LAYER, ArcLightBossModel::createBodyLayer);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(MaixrMod.ARC_LIGHT_BOSS.get(), ArcLightBossRenderer::new);
    }

    /**
     * 人造鸟巢端口的 UI（原版容器风格）。
     * <p>Forge 1.20.1 没有 RegisterMenuScreensEvent（那是 NeoForge 的），
     * 只能用 FMLClientSetupEvent + MenuScreens.register()。
     */
    @SubscribeEvent
    public static void clientSetup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        // 【1.21.1 移植·暂缓】两个鸟巢的界面（随鸟巢一起暂缓）。
        // event.enqueueWork(() -> MenuScreens.register(
        //         com.maixr.MaixrMenus.EGG_SEWER.get(), EggSewerScreen::new));
        // event.enqueueWork(() -> MenuScreens.register(
        //         com.maixr.MaixrMenus.PINK_EGG_SEWER.get(), PinkEggSewerScreen::new));
    }

    /**
     * ★ 自定义 Boss 血条走 Forge 自己的 overlay 管理器渲染。
     *
     * <p>这条路跟 Jade 那类 HUD 模组是同一机制，不依赖"原版快捷栏 overlay 事件"，
     * 所以不会被改过 HUD 的模组（Modern UI / QuadHotbar 之类）掐断。
     * （另外还挂了 RenderGuiEvent.Post 与 RenderGuiLayerEvent.Post 两个兜底入口。）
     */
    @SubscribeEvent
    public static void registerOverlays(net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "rift_boss_bar"),
                (gui, deltaTracker) ->
                        MaixrRiftBossBar.renderFallback(gui, "RegisterGuiLayersEvent"));
    }
}
