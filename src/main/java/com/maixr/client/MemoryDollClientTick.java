package com.maixr.client;

import com.maixr.MaixrMod;
import com.maixr.MaixrNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 客户端（游戏总线）：快捷键按下 → 发送变身请求（服务端校验佩戴与冷却）；断线清空换肤覆盖表。 */
@EventBusSubscriber(modid = MaixrMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class MemoryDollClientTick {
    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        while (MemoryDollClient.MEMORY_TRANSFORM_KEY.consumeClick()) {
            PacketDistributor.sendToServer(new MaixrNetwork.MemoryDollHotkeyMsg());
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MemoryDollSkinManager.clearAll();
    }

    private MemoryDollClientTick() {}
}
