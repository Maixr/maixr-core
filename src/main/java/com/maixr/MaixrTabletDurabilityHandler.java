package com.maixr;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * 转化桌的耐久结算：<b>界面关闭时</b>才扣耐久。
 *
 * <p>这是为了满足「打开后可以反复查看、真正用一次才算一次」的手感：
 * 玩家在界面里翻找物品、关闭后再开，只扣关闭那一次。</p>
 *
 * <p>工作方式：{@link MaixrTransmutationTabletItem} 在打开界面时把
 * 「玩家 UUID → 用哪只手」登记到一张表里；本处理器在
 * {@link PlayerContainerEvent.Close} 时取出并结算。</p>
 *
 * <p>注册方式见 {@code MaixrMod} 构造函数（{@code EVENT_BUS.register}）。</p>
 */
public class MaixrTabletDurabilityHandler {

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        InteractionHand hand = MaixrTransmutationTabletItem.pollOpened(player.getUUID());
        if (hand == null) {
            // 这次关闭的不是我们的转化桌。
            return;
        }

        // 保险：确认关闭的确实是转化桌容器（避免被其它界面顶掉时误扣）。
        AbstractContainerMenu menu = event.getContainer();
        if (menu != null) {
            String name = menu.getClass().getName();
            if (!name.contains("projecte")) {
                // 不是转化桌（例如被其它 mod 抢了关闭事件），把记录还回去。
                return;
            }
        }

        MaixrTransmutationTabletItem.consumeOnClose(player, hand);
    }
}
