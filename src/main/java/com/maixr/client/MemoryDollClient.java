package com.maixr.client;

import com.maixr.MaixrMod;
import com.maixr.MemoryTomorrowDollItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端（mod 总线）：变身快捷键注册 + 玩偶物品的萨姆外观谓词。
 * 快捷键的按下检测在 {@link MemoryDollClientTick}（游戏总线）。
 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MemoryDollClient {
    /** 佩戴时按下触发变身（默认 R，可在控制设置改键）。 */
    public static final KeyMapping MEMORY_TRANSFORM_KEY = new KeyMapping(
            "key.maixr.memory_transform",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.maixr");

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(MEMORY_TRANSFORM_KEY);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(
                MaixrMod.MEMORY_TOMORROW_DOLL_ITEM.get(),
                ResourceLocation.fromNamespaceAndPath(MaixrMod.MODID, "sam"),
                (stack, level, entity, seed) -> MemoryTomorrowDollItem.isSam(stack) ? 1.0F : 0.0F));
    }

    private MemoryDollClient() {}
}
